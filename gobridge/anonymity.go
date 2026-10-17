package t2s

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"sort"
	"strings"
	"sync"
	"time"
)

const headerEchoHost = "httpbin.org"

// Headers a proxy adds when it forwards a request; any of them reveals that a proxy is in use.
var proxyHeaders = map[string]bool{
	"via": true, "forwarded": true, "x-forwarded-for": true, "x-real-ip": true, "client-ip": true,
	"x-client-ip": true, "true-client-ip": true, "x-proxy-id": true, "proxy-connection": true,
	"x-forwarded-host": true, "x-forwarded-server": true, "x-originating-ip": true, "x-remote-ip": true,
	"x-remote-addr": true, "proxy-agent": true, "x-bluecoat-via": true, "x-proxy-connection": true,
	"x-cluster-client-ip": true, "cdn-loop": false,
}

var dnsblZones = []string{"zen.spamhaus.org", "bl.spamcop.net", "b.barracudacentral.org", "dnsbl.dronebl.org", "psbl.surriel.com", "all.s5h.net"}

type blacklistResult struct {
	Zone   string `json:"zone"`
	Listed bool   `json:"listed"`
	Answer string `json:"answer,omitempty"`
	Error  string `json:"error,omitempty"`
}

type anonymityResult struct {
	RealIP       string            `json:"realIp"`
	ExitIP       string            `json:"exitIp"`
	Level        string            `json:"level"` // transparent | anonymous | elite | unknown
	RealIPLeaked bool              `json:"realIpLeaked"`
	LeakHeaders  []string          `json:"leakHeaders"`
	ProxyHeaders []string          `json:"proxyHeaders"`
	Mode         string            `json:"mode"`
	Blacklists   []blacklistResult `json:"blacklists"`
	Listed       int               `json:"listed"`
	Checked      int               `json:"checked"`
	Error        string            `json:"error,omitempty"`
}

// classify decides the anonymity level from the request headers the echo server received.
// LB headers the echo service adds itself (X-Forwarded-For = exit IP, -Proto, -Port, X-Amzn-*)
// are ignored unless they carry an address other than the exit IP.
func classify(realIP, exitIP string, headers map[string]string) (level string, leaks, proxied []string) {
	for k, v := range headers {
		key := strings.ToLower(k)
		if realIP != "" && strings.Contains(v, realIP) {
			leaks = append(leaks, k)
			continue
		}
		if !proxyHeaders[key] {
			continue
		}
		if key == "x-forwarded-for" || key == "x-real-ip" {
			extra := false
			for _, part := range strings.Split(v, ",") {
				if p := strings.TrimSpace(part); p != "" && p != exitIP {
					extra = true
				}
			}
			if !extra {
				continue
			}
		}
		proxied = append(proxied, k)
	}
	sort.Strings(leaks)
	sort.Strings(proxied)
	switch {
	case len(leaks) > 0:
		return "transparent", leaks, proxied
	case len(proxied) > 0:
		return "anonymous", leaks, proxied
	default:
		return "elite", leaks, proxied
	}
}

func parseHops(spec string) ([]*hop, error) {
	urls := []string{spec}
	if strings.HasPrefix(strings.TrimSpace(spec), "[") {
		if err := json.Unmarshal([]byte(spec), &urls); err != nil {
			return nil, err
		}
	}
	var hops []*hop
	for _, raw := range urls {
		h, err := parseHop(raw)
		if err != nil {
			return nil, err
		}
		hops = append(hops, h)
	}
	if len(hops) == 0 {
		return nil, errors.New("no proxy configured")
	}
	return hops, nil
}

// echoHeaders sends a plain-HTTP request through the route. When the last hop is an HTTP proxy the
// request is sent in forward mode (absolute URI) because that is where proxies inject headers.
func echoHeaders(ctx context.Context, hops []*hop) (map[string]string, string, error) {
	last := hops[len(hops)-1]
	var c net.Conn
	var err error
	mode := "tunnel"
	path := "/get?show_env=1"
	if last.scheme == "http" {
		mode = "http-forward"
		path = "http://" + headerEchoHost + path
		if len(hops) == 1 {
			c, err = (&net.Dialer{}).DialContext(ctx, "tcp", last.addr)
		} else {
			c, err = (&chainProxy{hops: hops[:len(hops)-1]}).dial(ctx, last.addr)
		}
	} else {
		c, err = (&chainProxy{hops: hops}).dial(ctx, headerEchoHost+":80")
	}
	if err != nil {
		return nil, mode, err
	}
	defer c.Close()
	if dl, ok := ctx.Deadline(); ok {
		_ = c.SetDeadline(dl)
	}
	req := "GET " + path + " HTTP/1.1\r\nHost: " + headerEchoHost + "\r\nUser-Agent: Mozilla/5.0 (Linux; Android 14)\r\nAccept: application/json\r\nConnection: close\r\n"
	if mode == "http-forward" && last.user != "" {
		req += "Proxy-Authorization: Basic " + base64.StdEncoding.EncodeToString([]byte(last.user+":"+last.pass)) + "\r\n"
	}
	if _, err := io.WriteString(c, req+"\r\n"); err != nil {
		return nil, mode, err
	}
	resp, err := http.ReadResponse(bufio.NewReader(c), nil)
	if err != nil {
		return nil, mode, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, mode, fmt.Errorf("header check: http %d", resp.StatusCode)
	}
	var body struct {
		Headers map[string]any `json:"headers"`
		Origin  string         `json:"origin"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 64*1024)).Decode(&body); err != nil {
		return nil, mode, err
	}
	out := map[string]string{"(origin)": body.Origin}
	for k, v := range body.Headers {
		switch t := v.(type) {
		case string:
			out[k] = t
		case []any:
			var parts []string
			for _, p := range t {
				parts = append(parts, fmt.Sprint(p))
			}
			out[k] = strings.Join(parts, ", ")
		}
	}
	return out, mode, nil
}

func dnsblCheck(c *http.Client, ip netip.Addr, zone string) blacklistResult {
	r := blacklistResult{Zone: zone}
	b := ip.As4()
	name := fmt.Sprintf("%d.%d.%d.%d.%s", b[3], b[2], b[1], b[0], zone)
	resp, err := c.Get("https://dns.google/resolve?type=A&name=" + name)
	if err != nil {
		r.Error = err.Error()
		return r
	}
	defer resp.Body.Close()
	var out struct {
		Status int `json:"Status"`
		Answer []struct {
			Type int    `json:"type"`
			Data string `json:"data"`
		} `json:"Answer"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 16*1024)).Decode(&out); err != nil {
		r.Error = err.Error()
		return r
	}
	if out.Status == 3 {
		return r
	}
	for _, a := range out.Answer {
		if a.Type != 1 {
			continue
		}
		// 127.255.255.x means the list refused the query (e.g. public resolver), not "listed".
		if strings.HasPrefix(a.Data, "127.255.255.") || !strings.HasPrefix(a.Data, "127.") {
			r.Error = "list refused query"
			return r
		}
		r.Listed, r.Answer = true, a.Data
		return r
	}
	return r
}

// AnonymityTest checks whether the route (single proxy URL or JSON chain) exposes the real IP or
// proxy headers, classifies it as transparent / anonymous / elite, and looks the exit IP up in DNSBLs.
func AnonymityTest(spec string, timeoutSec int) (string, error) {
	if strings.TrimSpace(spec) == "" {
		return "", errors.New("no proxy configured")
	}
	if timeoutSec <= 0 {
		timeoutSec = 15
	}
	hops, err := parseHops(spec)
	if err != nil {
		return "", err
	}
	res := anonymityResult{Level: "unknown", LeakHeaders: []string{}, ProxyHeaders: []string{}, Blacklists: []blacklistResult{}}
	if direct, err := HTTPGet("", "https://api.ipify.org", timeoutSec); err == nil {
		res.RealIP = strings.TrimSpace(direct)
	}
	exit, err := HTTPGet(spec, "https://api.ipify.org", timeoutSec)
	if err != nil {
		return "", fmt.Errorf("proxy unreachable: %w", err)
	}
	res.ExitIP = strings.TrimSpace(exit)

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutSec)*time.Second)
	headers, mode, herr := echoHeaders(ctx, hops)
	cancel()
	res.Mode = mode
	if herr != nil {
		res.Error = herr.Error()
	} else {
		res.Level, res.LeakHeaders, res.ProxyHeaders = classify(res.RealIP, res.ExitIP, headers)
		res.RealIPLeaked = len(res.LeakHeaders) > 0
	}
	if res.RealIP != "" && res.RealIP == res.ExitIP {
		res.Level, res.RealIPLeaked = "transparent", true
	}
	if res.LeakHeaders == nil {
		res.LeakHeaders = []string{}
	}
	if res.ProxyHeaders == nil {
		res.ProxyHeaders = []string{}
	}

	if ip, err := netip.ParseAddr(res.ExitIP); err == nil && ip.Unmap().Is4() {
		client, err := toolClient(spec, timeoutSec)
		if err == nil {
			out := make([]blacklistResult, len(dnsblZones))
			var wg sync.WaitGroup
			for i, z := range dnsblZones {
				wg.Add(1)
				go func() {
					defer wg.Done()
					out[i] = dnsblCheck(client, ip.Unmap(), z)
				}()
			}
			wg.Wait()
			res.Blacklists = out
			for _, b := range out {
				if b.Error == "" {
					res.Checked++
				}
				if b.Listed {
					res.Listed++
				}
			}
		}
	}
	b, _ := json.Marshal(res)
	return string(b), nil
}
