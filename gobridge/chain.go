package t2s

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/xjasonlyu/tun2socks/v2/dialer"
	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"github.com/xjasonlyu/tun2socks/v2/proxy"
	"github.com/xjasonlyu/tun2socks/v2/transport/shadowsocks/core"
	"github.com/xjasonlyu/tun2socks/v2/transport/socks4"
	"github.com/xjasonlyu/tun2socks/v2/transport/socks5"
)

// hop is one proxy server in a chain that can open a tunnel to the next address over an existing conn.
type hop struct {
	scheme string
	addr   string
	user   string
	pass   string
	cipher core.Cipher
}

func parseHop(raw string) (*hop, error) {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil {
		return nil, err
	}
	if u.Host == "" {
		return nil, fmt.Errorf("missing host in %q", u.Scheme+"://")
	}
	h := &hop{scheme: u.Scheme, addr: u.Host, user: u.User.Username()}
	h.pass, _ = u.User.Password()
	switch u.Scheme {
	case "socks5", "socks4", "http":
	case "ss":
		method, password := h.user, h.pass
		if _, set := u.User.Password(); !set && u.User.String() != "" {
			data, _ := base64.RawURLEncoding.DecodeString(u.User.String())
			parts := strings.SplitN(string(data), ":", 2)
			if len(parts) == 2 {
				method, password = parts[0], parts[1]
			}
		}
		c, err := core.PickCipher(method, nil, password)
		if err != nil {
			return nil, fmt.Errorf("shadowsocks: %w", err)
		}
		h.cipher = c
	default:
		return nil, fmt.Errorf("unsupported proxy type %q", u.Scheme)
	}
	return h, nil
}

// connect performs the hop's handshake on c so that c becomes a stream to target ("host:port").
func (h *hop) connect(c net.Conn, target string) (net.Conn, error) {
	_ = c.SetDeadline(time.Now().Add(15 * time.Second))
	defer c.SetDeadline(time.Time{})
	switch h.scheme {
	case "socks5":
		var user *socks5.User
		if h.user != "" {
			user = &socks5.User{Username: h.user, Password: h.pass}
		}
		_, err := socks5.ClientHandshake(c, socks5.ParseAddrString(target), socks5.CmdConnect, user)
		return c, err
	case "socks4":
		return c, socks4.ClientHandshake(c, target, socks4.CmdConnect, h.user)
	case "http":
		req := &http.Request{Method: http.MethodConnect, URL: &url.URL{Host: target}, Host: target, Header: http.Header{}}
		if h.user != "" {
			req.Header.Set("Proxy-Authorization", "Basic "+base64.StdEncoding.EncodeToString([]byte(h.user+":"+h.pass)))
		}
		if err := req.Write(c); err != nil {
			return nil, err
		}
		br := bufio.NewReader(c)
		resp, err := http.ReadResponse(br, req)
		if err != nil {
			return nil, err
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			return nil, fmt.Errorf("http proxy %s: %s", h.addr, resp.Status)
		}
		return &bufferedConn{Conn: c, r: br}, nil
	case "ss":
		sc := h.cipher.StreamConn(c)
		_, err := sc.Write(socks5.ParseAddrString(target))
		return sc, err
	}
	return nil, errors.ErrUnsupported
}

type bufferedConn struct {
	net.Conn
	r *bufio.Reader
}

func (b *bufferedConn) Read(p []byte) (int, error) { return b.r.Read(p) }

// chainProxy tunnels TCP through every hop in order. UDP is not supported over chains.
type chainProxy struct{ hops []*hop }

func (cp *chainProxy) dial(ctx context.Context, target string) (net.Conn, error) {
	c, err := dialer.DialContext(ctx, "tcp", cp.hops[0].addr)
	if err != nil {
		return nil, fmt.Errorf("connect to %s: %w", cp.hops[0].addr, err)
	}
	if tc, ok := c.(*net.TCPConn); ok {
		_ = tc.SetKeepAlive(true)
	}
	for i, h := range cp.hops {
		next := target
		if i+1 < len(cp.hops) {
			next = cp.hops[i+1].addr
		}
		if c, err = h.connect(c, next); err != nil {
			if c != nil {
				c.Close()
			}
			return nil, fmt.Errorf("hop %d (%s): %w", i+1, h.addr, err)
		}
	}
	return c, nil
}

func (cp *chainProxy) DialContext(ctx context.Context, m *M.Metadata) (net.Conn, error) {
	return cp.dial(ctx, m.DestinationAddress())
}

func (cp *chainProxy) DialUDP(*M.Metadata) (net.PacketConn, error) {
	return nil, errors.New("udp is not supported through a proxy chain")
}

// parseProxies accepts a single proxy URL or a JSON array of URLs (a chain, first hop first).
func parseProxies(spec string) (proxy.Proxy, int, error) {
	spec = strings.TrimSpace(spec)
	urls := []string{spec}
	if strings.HasPrefix(spec, "[") {
		if err := json.Unmarshal([]byte(spec), &urls); err != nil {
			return nil, 0, err
		}
	}
	if len(urls) == 0 || urls[0] == "" {
		return nil, 0, errors.New("no proxy configured")
	}
	if len(urls) == 1 {
		u, err := url.Parse(urls[0])
		if err != nil {
			return nil, 0, err
		}
		p, err := proxy.Parse(u)
		return p, 1, err
	}
	cp := &chainProxy{}
	for _, raw := range urls {
		h, err := parseHop(raw)
		if err != nil {
			return nil, 0, err
		}
		cp.hops = append(cp.hops, h)
	}
	return cp, len(cp.hops), nil
}

func hostPort(addr string) (string, uint16, error) {
	host, portStr, err := net.SplitHostPort(addr)
	if err != nil {
		return "", 0, err
	}
	port, err := strconv.ParseUint(portStr, 10, 16)
	return host, uint16(port), err
}
