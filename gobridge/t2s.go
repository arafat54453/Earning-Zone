// Package t2s is a gomobile-friendly wrapper around the tun2socks engine with a local
// encrypted-DNS resolver, proxy chains, runtime failover and privacy filters.
package t2s

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/xjasonlyu/tun2socks/v2/engine"
	"github.com/xjasonlyu/tun2socks/v2/tunnel"
	"github.com/xjasonlyu/tun2socks/v2/tunnel/statistic"
)

const restAddr = "127.0.0.1:18964"

var (
	mu        sync.Mutex
	running   bool
	rt        *router
	restToken = randomToken()
	client    = &http.Client{Timeout: time.Second}
)

func randomToken() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "tun2socks"
	}
	return hex.EncodeToString(b)
}

type config struct {
	Proxies    []string  `json:"proxies"`
	MTU        int       `json:"mtu"`
	UDPTimeout int       `json:"udpTimeout"`
	ProxyUDP   bool      `json:"proxyUdp"`
	BlockQUIC  bool      `json:"blockQuic"`
	BlockSTUN  bool      `json:"blockStun"`
	BlockIPv6  bool      `json:"blockIpv6"`
	BlockLAN   bool      `json:"blockLan"`
	LogLevel   string    `json:"logLevel"`
	DNS        dnsConfig `json:"dns"`
}

// Start brings the engine up on an already-established TUN file descriptor using a JSON config.
func Start(fd int, configJSON string) error {
	mu.Lock()
	defer mu.Unlock()
	if running {
		return errors.New("engine already running")
	}
	if stopping != nil {
		select {
		case <-stopping:
			stopping = nil
		case <-time.After(10 * time.Second):
			return errors.New("previous session is still shutting down, try again in a few seconds")
		}
	}
	var cfg config
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return fmt.Errorf("config: %w", err)
	}
	if cfg.MTU <= 0 {
		cfg.MTU = 1500
	}
	if cfg.UDPTimeout <= 0 {
		cfg.UDPTimeout = 60
	}
	if cfg.LogLevel == "" {
		cfg.LogLevel = "warning"
	}
	spec, _ := json.Marshal(cfg.Proxies)
	upstream, hops, err := parseProxies(string(spec))
	if err != nil {
		return err
	}
	engine.Insert(&engine.Key{
		MTU:        cfg.MTU,
		Proxy:      "direct://",
		Device:     fmt.Sprintf("fd://%d", fd),
		LogLevel:   cfg.LogLevel,
		RestAPI:    fmt.Sprintf("http://%s@%s", restToken, restAddr),
		UDPTimeout: time.Duration(cfg.UDPTimeout) * time.Second,
	})
	// A wedged netstack must never leave the caller stuck in "Connecting": bound the start.
	errc := make(chan error, 1)
	go func() { errc <- engine.Start() }()
	select {
	case err := <-errc:
		if err != nil {
			return err
		}
	case <-time.After(15 * time.Second):
		done := make(chan struct{})
		stopping = done
		go func() {
			if <-errc == nil {
				_ = engine.Stop()
			}
			close(done)
		}()
		return errors.New("engine start timed out")
	}
	r := &router{proxyUDP: cfg.ProxyUDP, blockQUIC: cfg.BlockQUIC, blockSTUN: cfg.BlockSTUN, noIPv6: cfg.BlockIPv6, blockLAN: cfg.BlockLAN}
	r.setUpstream(upstream, hops)
	if cfg.DNS.Enabled {
		r.dns = newResolver(cfg.DNS, r)
		r.dns.noAAAA = cfg.BlockIPv6
	}
	tunnel.T().SetProxy(r)
	resetStatistics()
	rt = r
	running = true
	return nil
}

// Stop tears the engine down. It is safe to call when not running.
// stopping is closed when the last engine.Stop has fully returned.
var stopping chan struct{}

// Stop closes live connections, then tears the engine down. The gVisor stack can take a while to
// drain, so Stop returns after a bounded wait and the teardown finishes in the background; the next
// Start waits for it.
func Stop() error {
	mu.Lock()
	defer mu.Unlock()
	if !running {
		return nil
	}
	running = false
	if rt != nil && rt.dns != nil {
		rt.dns.flush()
	}
	rt = nil
	for _, t := range statistic.DefaultManager.Snapshot().Connections {
		_ = t.Close()
	}
	done := make(chan struct{})
	errc := make(chan error, 1)
	stopping = done
	go func() {
		errc <- engine.Stop()
		close(done)
	}()
	select {
	case err := <-errc:
		return err
	case <-time.After(3 * time.Second):
		return nil
	}
}

// WaitIdle waits until a previous engine teardown has fully finished (so the old TUN fd is
// closed and its number cannot collide with a new interface). It returns false on timeout.
func WaitIdle(timeoutSec int) bool {
	mu.Lock()
	s := stopping
	mu.Unlock()
	if s == nil {
		return true
	}
	select {
	case <-s:
		return true
	case <-time.After(time.Duration(max(timeoutSec, 1)) * time.Second):
		return false
	}
}

func resetStatistics() { statistic.DefaultManager.ResetStatistic() }

func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return running
}

// SetProxies swaps the upstream (single URL or JSON chain) without touching the TUN, then drops
// existing connections so apps reconnect through the new route. Used for automatic failover.
func SetProxies(spec string) error {
	mu.Lock()
	r := rt
	mu.Unlock()
	if r == nil {
		return errors.New("engine not running")
	}
	p, hops, err := parseProxies(spec)
	if err != nil {
		return err
	}
	r.setUpstream(p, hops)
	ResetConnections()
	return nil
}

// ResetConnections closes all tracked connections and flushes DNS state (e.g. after a network change).
func ResetConnections() {
	mu.Lock()
	r := rt
	mu.Unlock()
	if r != nil && r.dns != nil {
		r.dns.flush()
	}
	for _, t := range statistic.DefaultManager.Snapshot().Connections {
		_ = t.Close()
	}
}

// Stats returns a JSON object with traffic and DNS counters.
func Stats() string {
	s := statistic.DefaultManager.Snapshot()
	tx, rx := packets()
	out := map[string]int64{
		"uploadBytes":     s.UploadTotal,
		"downloadBytes":   s.DownloadTotal,
		"uploadPackets":   tx,
		"downloadPackets": rx,
		"connections":     int64(len(s.Connections)),
	}
	mu.Lock()
	r := rt
	mu.Unlock()
	if r != nil && r.dns != nil {
		out["dnsQueries"] = r.dns.queries.Load()
		out["dnsBlocked"] = r.dns.blocked.Load()
		out["dnsCached"] = r.dns.cached.Load()
		out["dnsFailed"] = r.dns.failed.Load()
		out["dnsCloaked"] = r.dns.cloaked.Load()
		out["blocklistSize"] = int64(len(r.dns.block))
	}
	out["lanBlocked"] = act.lanBlocked.Load()
	b, _ := json.Marshal(out)
	return string(b)
}

// ExitIP fetches target (e.g. https://api.ipify.org) along exactly the path apps use inside the
// tunnel - the live router and its in-tunnel resolver - so the leak monitor sees what websites see.
func ExitIP(target string, timeoutSec int) (string, error) {
	mu.Lock()
	r := rt
	mu.Unlock()
	if r == nil {
		return "", errors.New("engine not running")
	}
	if timeoutSec <= 0 {
		timeoutSec = 10
	}
	c := &http.Client{
		Timeout: time.Duration(timeoutSec) * time.Second,
		Transport: &http.Transport{
			DisableKeepAlives: true,
			DialContext: func(ctx context.Context, _, addr string) (net.Conn, error) {
				host, port, err := hostPort(addr)
				if err != nil {
					return nil, err
				}
				var ip netip.Addr
				if r.dns != nil {
					ip, err = r.dns.bootstrap(ctx, host)
				} else {
					var ips []netip.Addr
					if ips, err = net.DefaultResolver.LookupNetIP(ctx, "ip4", host); err == nil && len(ips) > 0 {
						ip = ips[0]
					} else if err == nil {
						err = errors.New("no address for " + host)
					}
				}
				if err != nil {
					return nil, err
				}
				return r.dialTCP(ctx, ip, port)
			},
		},
	}
	resp, err := c.Get(target)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 4096))
	if err != nil {
		return "", err
	}
	if resp.StatusCode >= 400 {
		return "", fmt.Errorf("http %d", resp.StatusCode)
	}
	return strings.TrimSpace(string(body)), nil
}

// packets reads IP packet counters from the engine's loopback REST API.
func packets() (tx int64, rx int64) {
	if !running {
		return 0, 0
	}
	req, err := http.NewRequest(http.MethodGet, "http://"+restAddr+"/netstats", nil)
	if err != nil {
		return 0, 0
	}
	req.Header.Set("Authorization", "Bearer "+restToken)
	resp, err := client.Do(req)
	if err != nil {
		return 0, 0
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return 0, 0
	}
	var root map[string]any
	if json.Unmarshal(body, &root) != nil {
		return 0, 0
	}
	ip, _ := root["IP"].(map[string]any)
	if ip == nil {
		return 0, 0
	}
	num := func(k string) int64 {
		if v, ok := ip[k].(float64); ok {
			return int64(v)
		}
		return 0
	}
	// Packets received by the stack came from apps (upload); packets sent go back to apps (download).
	return num("PacketsReceived"), num("PacketsSent")
}

// toolDialer builds a dialer for diagnostics. Hostnames are passed to the proxy unresolved, so the
// app never needs local DNS (which is what failed on mobile data before). Empty spec = direct.
func toolDialer(spec string) (func(ctx context.Context, network, addr string) (net.Conn, error), error) {
	if spec == "" {
		d := &net.Dialer{}
		return d.DialContext, nil
	}
	urls := []string{spec}
	if spec[0] == '[' {
		if err := json.Unmarshal([]byte(spec), &urls); err != nil {
			return nil, err
		}
	}
	cp := &chainProxy{}
	for _, raw := range urls {
		h, err := parseHop(raw)
		if err != nil {
			return nil, err
		}
		cp.hops = append(cp.hops, h)
	}
	if len(cp.hops) == 0 {
		return nil, errors.New("no proxy configured")
	}
	return func(ctx context.Context, _, addr string) (net.Conn, error) { return cp.dial(ctx, addr) }, nil
}

func toolClient(spec string, timeoutSec int) (*http.Client, error) {
	dial, err := toolDialer(spec)
	if err != nil {
		return nil, err
	}
	if timeoutSec <= 0 {
		timeoutSec = 10
	}
	return &http.Client{
		Timeout:   time.Duration(timeoutSec) * time.Second,
		Transport: &http.Transport{DialContext: dial, ForceAttemptHTTP2: true, DisableKeepAlives: true},
	}, nil
}

// HTTPGet fetches target through the proxy spec (or directly when empty) and returns the body.
func HTTPGet(spec, target string, timeoutSec int) (string, error) {
	c, err := toolClient(spec, timeoutSec)
	if err != nil {
		return "", err
	}
	resp, err := c.Get(target)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 64*1024))
	if err != nil {
		return "", err
	}
	if resp.StatusCode >= 400 {
		return "", fmt.Errorf("http %d", resp.StatusCode)
	}
	return string(body), nil
}

// Latency measures a full round trip (proxy handshake + HTTP request) through spec, in milliseconds.
func Latency(spec string, timeoutSec int) (int, error) {
	if spec == "" {
		return 0, errors.New("no proxy configured")
	}
	dial, err := toolDialer(spec)
	if err != nil {
		return 0, err
	}
	if timeoutSec <= 0 {
		timeoutSec = 8
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutSec)*time.Second)
	defer cancel()
	start := time.Now()
	c, err := dial(ctx, "tcp", "1.1.1.1:80")
	if err != nil {
		return 0, err
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(time.Duration(timeoutSec) * time.Second))
	if _, err := io.WriteString(c, "HEAD / HTTP/1.1\r\nHost: 1.1.1.1\r\nConnection: close\r\n\r\n"); err != nil {
		return 0, err
	}
	if _, err := c.Read(make([]byte, 1)); err != nil {
		return 0, err
	}
	return int(time.Since(start).Milliseconds()), nil
}

// DownloadToFile downloads a blocklist through spec into path and returns how many domains it holds.
func DownloadToFile(spec, target, path string, maxBytes int) (int, error) {
	c, err := toolClient(spec, 60)
	if err != nil {
		return 0, err
	}
	resp, err := c.Get(target)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return 0, fmt.Errorf("http %d", resp.StatusCode)
	}
	if maxBytes <= 0 {
		maxBytes = 32 << 20
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, int64(maxBytes)))
	if err != nil {
		return 0, err
	}
	n := addDomains(map[string]struct{}{}, string(data))
	if n == 0 {
		return 0, errors.New("no domains found in list")
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return 0, err
	}
	return n, os.Rename(tmp, path)
}

// CountDomains reports how many domains a local list file contains.
func CountDomains(path string) int {
	data, err := os.ReadFile(path)
	if err != nil {
		return 0
	}
	return addDomains(map[string]struct{}{}, string(data))
}
