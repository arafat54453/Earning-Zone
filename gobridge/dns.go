package t2s

import (
	"bufio"
	"bytes"
	"context"
	"crypto/tls"
	_ "embed"
	"encoding/binary"
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

	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"go.uber.org/atomic"
	"golang.org/x/net/dns/dnsmessage"
)

var (
	dnsVIP4 = netip.MustParseAddr("198.18.0.2")
	dnsVIP6 = netip.MustParseAddr("fc00::2")
)

//go:embed blocklist.txt
var builtinBlocklist string

//go:embed cname_trackers.txt
var builtinCnameTrackers string

type dnsConfig struct {
	Enabled    bool     `json:"enabled"`
	Mode       string   `json:"mode"` // doh | dot | tcp
	URL        string   `json:"url"`
	Server     string   `json:"server"`
	ServerName string   `json:"serverName"`
	Fallback   bool     `json:"fallback"`
	BlockAds   bool     `json:"blockAds"`
	ListFiles  []string `json:"listFiles"`
	Custom     []string `json:"custom"`
	Allow      []string `json:"allow"`
	BlockCNAME bool     `json:"blockCname"`
	FakeIP     bool     `json:"fakeIp"`
}

type cacheEntry struct {
	msg     []byte
	expires time.Time
}

// resolver answers every DNS query from the TUN itself and forwards it, encrypted, over the
// proxy's TCP path. This avoids SOCKS5 UDP ASSOCIATE (unsupported by most proxies and broken on
// carrier NAT), so DNS keeps working on mobile data and never leaks to the ISP.
type resolver struct {
	cfg   dnsConfig
	r     *router
	doh   *http.Client
	block map[string]struct{}
	allow map[string]struct{}
	cname map[string]struct{}

	mu    sync.Mutex
	cache map[string]cacheEntry
	boot  map[string]netip.Addr
	names map[netip.Addr]string
	pool  chan net.Conn

	noAAAA bool

	queries, blocked, cached, failed, cloaked atomic.Int64
}

func newResolver(cfg dnsConfig, r *router) *resolver {
	res := &resolver{
		cfg:   cfg,
		r:     r,
		block: map[string]struct{}{},
		allow: map[string]struct{}{},
		cname: map[string]struct{}{},
		names: map[netip.Addr]string{},
		cache: map[string]cacheEntry{},
		boot:  map[string]netip.Addr{},
		pool:  make(chan net.Conn, 4),
	}
	if res.cfg.Mode == "" {
		res.cfg.Mode = "doh"
	}
	if res.cfg.URL == "" {
		res.cfg.URL = "https://1.1.1.1/dns-query"
	}
	if cfg.BlockAds {
		addDomains(res.block, builtinBlocklist)
		for _, f := range cfg.ListFiles {
			if data, err := os.ReadFile(f); err == nil {
				addDomains(res.block, string(data))
			}
		}
	}
	addDomains(res.block, strings.Join(cfg.Custom, "\n"))
	addDomains(res.allow, strings.Join(cfg.Allow, "\n"))
	if cfg.BlockCNAME {
		addDomains(res.cname, builtinCnameTrackers)
	}
	res.doh = &http.Client{
		Timeout: 10 * time.Second,
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, _, addr string) (net.Conn, error) {
				host, port, err := hostPort(addr)
				if err != nil {
					return nil, err
				}
				ip, err := res.bootstrap(ctx, host)
				if err != nil {
					return nil, err
				}
				return r.dialTCP(ctx, ip, port)
			},
			TLSClientConfig:     &tls.Config{MinVersion: tls.VersionTLS12},
			ForceAttemptHTTP2:   true,
			TLSHandshakeTimeout: 10 * time.Second,
			IdleConnTimeout:     90 * time.Second,
			MaxIdleConnsPerHost: 4,
		},
	}
	return res
}

func addDomains(set map[string]struct{}, text string) int {
	n := 0
	sc := bufio.NewScanner(strings.NewReader(text))
	sc.Buffer(make([]byte, 64*1024), 1024*1024)
	for sc.Scan() {
		line := sc.Text()
		if i := strings.IndexAny(line, "#!"); i >= 0 {
			line = line[:i]
		}
		fields := strings.Fields(line)
		if len(fields) == 0 {
			continue
		}
		d := fields[0]
		if len(fields) >= 2 {
			if _, err := netip.ParseAddr(fields[0]); err == nil {
				d = fields[1]
			}
		}
		if strings.HasPrefix(d, "@@") {
			continue
		}
		d = strings.TrimPrefix(d, "||")
		if i := strings.IndexAny(d, "^$"); i >= 0 {
			d = d[:i]
		}
		d = strings.TrimPrefix(d, "*.")
		d = strings.Trim(strings.ToLower(d), ".")
		if d == "" || d == "localhost" || d == "localhost.localdomain" || d == "local" || d == "broadcasthost" || d == "0.0.0.0" || !strings.Contains(d, ".") || strings.ContainsAny(d, "/*|=:") {
			continue
		}
		set[d] = struct{}{}
		n++
	}
	return n
}

func matchSuffix(set map[string]struct{}, name string) bool {
	for n := name; n != ""; {
		if _, ok := set[n]; ok {
			return true
		}
		i := strings.IndexByte(n, '.')
		if i < 0 {
			break
		}
		n = n[i+1:]
	}
	return false
}

func (res *resolver) isBlocked(name string) bool {
	if len(res.block) == 0 || matchSuffix(res.allow, name) {
		return false
	}
	return matchSuffix(res.block, name)
}

func (res *resolver) flush() {
	res.mu.Lock()
	res.cache = map[string]cacheEntry{}
	res.boot = map[string]netip.Addr{}
	res.names = map[netip.Addr]string{}
	res.mu.Unlock()
	for {
		select {
		case c := <-res.pool:
			c.Close()
		default:
			if t, ok := res.doh.Transport.(*http.Transport); ok {
				t.CloseIdleConnections()
			}
			return
		}
	}
}

// Exchange resolves one wire-format DNS query. It always returns a response for parseable queries.
func (res *resolver) Exchange(ctx context.Context, q []byte) ([]byte, error) {
	var p dnsmessage.Parser
	h, err := p.Start(q)
	if err != nil {
		return nil, err
	}
	qs, err := p.Question()
	if err != nil {
		return nil, err
	}
	res.queries.Inc()
	name := strings.ToLower(strings.TrimSuffix(qs.Name.String(), "."))
	if res.isBlocked(name) {
		res.blocked.Inc()
		return res.blockedReply(h, qs, name, "")
	}
	if res.noAAAA && qs.Type == dnsmessage.TypeAAAA {
		return buildReply(h, qs, dnsmessage.RCodeSuccess, false)
	}
	key := fmt.Sprintf("%s/%d/%d", name, qs.Type, qs.Class)
	res.mu.Lock()
	e, ok := res.cache[key]
	res.mu.Unlock()
	if ok && time.Now().Before(e.expires) {
		res.cached.Inc()
		out := append([]byte(nil), e.msg...)
		binary.BigEndian.PutUint16(out, h.ID)
		return res.inspect(h, qs, name, out)
	}

	resp, err := res.upstream(ctx, q)
	if err != nil && res.cfg.Fallback {
		fb := "https://1.1.1.1/dns-query"
		if res.cfg.Mode == "doh" && strings.Contains(res.cfg.URL, "1.1.1.1") {
			fb = "https://9.9.9.9/dns-query"
		}
		resp, err = res.dohExchange(ctx, fb, q)
	}
	if err != nil || len(resp) < 12 {
		res.failed.Inc()
		return buildReply(h, qs, dnsmessage.RCodeServerFailure, false)
	}
	binary.BigEndian.PutUint16(resp, h.ID)
	if ttl, cacheable := responseTTL(resp); cacheable {
		res.mu.Lock()
		if len(res.cache) > 4096 {
			res.cache = map[string]cacheEntry{}
		}
		res.cache[key] = cacheEntry{msg: append([]byte(nil), resp...), expires: time.Now().Add(ttl)}
		res.mu.Unlock()
	}
	return res.inspect(h, qs, name, resp)
}

// inspect blocks answers whose CNAME chain points at a tracker (CNAME cloaking) and remembers
// which domain each answered address belongs to, for per-app reports.
func (res *resolver) inspect(h dnsmessage.Header, qs dnsmessage.Question, name string, resp []byte) ([]byte, error) {
	targets, addrs := parseAnswers(resp)
	if t := res.cloakedBy(name, targets); t != "" {
		res.blocked.Inc()
		res.cloaked.Inc()
		return res.blockedReply(h, qs, name, t)
	}
	if len(addrs) > 0 {
		res.mu.Lock()
		if len(res.names) > 16384 {
			res.names = map[netip.Addr]string{}
		}
		for _, a := range addrs {
			res.names[a] = name
		}
		res.mu.Unlock()
	}
	return resp, nil
}

func (res *resolver) cloakedBy(name string, targets []string) string {
	if !res.cfg.BlockCNAME || matchSuffix(res.allow, name) {
		return ""
	}
	for _, t := range targets {
		if t == name || matchSuffix(res.allow, t) {
			continue
		}
		if matchSuffix(res.cname, t) || (len(res.block) > 0 && matchSuffix(res.block, t)) {
			return t
		}
	}
	return ""
}

// parseAnswers returns CNAME targets and A/AAAA addresses found in a response.
func parseAnswers(resp []byte) (targets []string, addrs []netip.Addr) {
	var p dnsmessage.Parser
	if _, err := p.Start(resp); err != nil {
		return nil, nil
	}
	if err := p.SkipAllQuestions(); err != nil {
		return nil, nil
	}
	for {
		ah, err := p.AnswerHeader()
		if err != nil {
			return
		}
		switch ah.Type {
		case dnsmessage.TypeCNAME:
			r, err := p.CNAMEResource()
			if err != nil {
				return
			}
			targets = append(targets, strings.ToLower(strings.TrimSuffix(r.CNAME.String(), ".")))
		case dnsmessage.TypeA:
			r, err := p.AResource()
			if err != nil {
				return
			}
			addrs = append(addrs, netip.AddrFrom4(r.A))
		case dnsmessage.TypeAAAA:
			r, err := p.AAAAResource()
			if err != nil {
				return
			}
			addrs = append(addrs, netip.AddrFrom16(r.AAAA))
		default:
			if err := p.SkipAnswer(); err != nil {
				return
			}
		}
	}
}

// blockedReply answers a blocked name. With FakeIP, A queries get an address from fakeNet so the
// app's connection attempt can be attributed to it; otherwise the usual 0.0.0.0 / :: sinkhole.
func (res *resolver) blockedReply(h dnsmessage.Header, qs dnsmessage.Question, name, cname string) ([]byte, error) {
	if !res.cfg.FakeIP {
		return buildReply(h, qs, dnsmessage.RCodeSuccess, true)
	}
	if qs.Type != dnsmessage.TypeA {
		return buildReply(h, qs, dnsmessage.RCodeSuccess, false)
	}
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{
		ID: h.ID, Response: true, RecursionDesired: h.RecursionDesired, RecursionAvailable: true,
	})
	if err := b.StartQuestions(); err != nil {
		return nil, err
	}
	if err := b.Question(qs); err != nil {
		return nil, err
	}
	if err := b.StartAnswers(); err != nil {
		return nil, err
	}
	ip := fakes.addr(name, cname)
	if err := b.AResource(dnsmessage.ResourceHeader{Name: qs.Name, Class: qs.Class, TTL: 10}, dnsmessage.AResource{A: ip.As4()}); err != nil {
		return nil, err
	}
	return b.Finish()
}

func (res *resolver) nameOf(ip netip.Addr) string {
	res.mu.Lock()
	defer res.mu.Unlock()
	if n, ok := res.names[ip]; ok {
		return n
	}
	return res.names[ip.Unmap()]
}

func (res *resolver) resetCounters() {
	res.queries.Store(0)
	res.blocked.Store(0)
	res.cached.Store(0)
	res.failed.Store(0)
	res.cloaked.Store(0)
}

func responseTTL(resp []byte) (time.Duration, bool) {
	var p dnsmessage.Parser
	h, err := p.Start(resp)
	if err != nil || h.Truncated || (h.RCode != dnsmessage.RCodeSuccess && h.RCode != dnsmessage.RCodeNameError) {
		return 0, false
	}
	if err := p.SkipAllQuestions(); err != nil {
		return 0, false
	}
	ttl := uint32(60)
	first := true
	for {
		ah, err := p.AnswerHeader()
		if err != nil {
			break
		}
		if first || ah.TTL < ttl {
			ttl, first = ah.TTL, false
		}
		if err := p.SkipAnswer(); err != nil {
			break
		}
	}
	ttl = max(10, min(ttl, 3600))
	return time.Duration(ttl) * time.Second, true
}

func buildReply(h dnsmessage.Header, q dnsmessage.Question, rcode dnsmessage.RCode, sinkhole bool) ([]byte, error) {
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{
		ID: h.ID, Response: true, RecursionDesired: h.RecursionDesired, RecursionAvailable: true, RCode: rcode,
	})
	b.EnableCompression()
	if err := b.StartQuestions(); err != nil {
		return nil, err
	}
	if err := b.Question(q); err != nil {
		return nil, err
	}
	if sinkhole {
		if err := b.StartAnswers(); err != nil {
			return nil, err
		}
		rh := dnsmessage.ResourceHeader{Name: q.Name, Class: q.Class, TTL: 300}
		switch q.Type {
		case dnsmessage.TypeA:
			_ = b.AResource(rh, dnsmessage.AResource{})
		case dnsmessage.TypeAAAA:
			_ = b.AAAAResource(rh, dnsmessage.AAAAResource{})
		}
	}
	return b.Finish()
}

func (res *resolver) upstream(ctx context.Context, q []byte) ([]byte, error) {
	switch res.cfg.Mode {
	case "dot":
		return res.streamExchange(ctx, q, res.cfg.Server, 853, res.cfg.ServerName, true, true)
	case "tcp":
		return res.streamExchange(ctx, q, res.cfg.Server, 53, "", false, true)
	default:
		return res.dohExchange(ctx, res.cfg.URL, q)
	}
}

func (res *resolver) dohExchange(ctx context.Context, url string, q []byte) ([]byte, error) {
	body := append([]byte(nil), q...)
	binary.BigEndian.PutUint16(body, 0)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/dns-message")
	req.Header.Set("Accept", "application/dns-message")
	resp, err := res.doh.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("doh: %s", resp.Status)
	}
	return io.ReadAll(io.LimitReader(resp.Body, 65535))
}

func (res *resolver) streamExchange(ctx context.Context, q []byte, server string, defPort uint16, sni string, useTLS, pooled bool) ([]byte, error) {
	for attempt := 0; attempt < 2; attempt++ {
		var c net.Conn
		reused := false
		if pooled {
			select {
			case c = <-res.pool:
				reused = true
			default:
			}
		}
		if c == nil {
			var err error
			if c, err = res.dialStream(ctx, server, defPort, sni, useTLS); err != nil {
				return nil, err
			}
		}
		resp, err := exchangeOn(c, q)
		if err == nil {
			if pooled {
				select {
				case res.pool <- c:
				default:
					c.Close()
				}
			} else {
				c.Close()
			}
			return resp, nil
		}
		c.Close()
		if !reused {
			return nil, err
		}
	}
	return nil, errors.New("dns: exchange failed")
}

func (res *resolver) dialStream(ctx context.Context, server string, defPort uint16, sni string, useTLS bool) (net.Conn, error) {
	host, port, err := hostPort(server)
	if err != nil {
		host, port = strings.Trim(server, "[]"), defPort
	}
	ip, err := res.bootstrap(ctx, host)
	if err != nil {
		return nil, err
	}
	c, err := res.r.dialTCP(ctx, ip, port)
	if err != nil || !useTLS {
		return c, err
	}
	if sni == "" {
		sni = host
	}
	tc := tls.Client(c, &tls.Config{ServerName: sni, MinVersion: tls.VersionTLS12})
	hctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	if err := tc.HandshakeContext(hctx); err != nil {
		c.Close()
		return nil, err
	}
	return tc, nil
}

func exchangeOn(c net.Conn, q []byte) ([]byte, error) {
	_ = c.SetDeadline(time.Now().Add(10 * time.Second))
	defer c.SetDeadline(time.Time{})
	buf := make([]byte, 2+len(q))
	binary.BigEndian.PutUint16(buf, uint16(len(q)))
	copy(buf[2:], q)
	if _, err := c.Write(buf); err != nil {
		return nil, err
	}
	return readFramed(c)
}

func readFramed(r io.Reader) ([]byte, error) {
	var l [2]byte
	if _, err := io.ReadFull(r, l[:]); err != nil {
		return nil, err
	}
	msg := make([]byte, binary.BigEndian.Uint16(l[:]))
	_, err := io.ReadFull(r, msg)
	return msg, err
}

// bootstrap resolves the encrypted resolver's own hostname over plain DNS-over-TCP through the proxy.
func (res *resolver) bootstrap(ctx context.Context, host string) (netip.Addr, error) {
	if ip, err := netip.ParseAddr(host); err == nil {
		return ip, nil
	}
	res.mu.Lock()
	ip, ok := res.boot[host]
	res.mu.Unlock()
	if ok {
		return ip, nil
	}
	name, err := dnsmessage.NewName(strings.TrimSuffix(host, ".") + ".")
	if err != nil {
		return netip.Addr{}, err
	}
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: 1, RecursionDesired: true})
	_ = b.StartQuestions()
	_ = b.Question(dnsmessage.Question{Name: name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET})
	q, _ := b.Finish()
	var lastErr error
	for _, srv := range []string{"1.1.1.1:53", "8.8.8.8:53", "9.9.9.9:53"} {
		resp, err := res.streamExchange(ctx, q, srv, 53, "", false, false)
		if err != nil {
			lastErr = err
			continue
		}
		var p dnsmessage.Parser
		if _, err := p.Start(resp); err != nil {
			lastErr = err
			continue
		}
		_ = p.SkipAllQuestions()
		for {
			ah, err := p.AnswerHeader()
			if err != nil {
				break
			}
			if ah.Type == dnsmessage.TypeA {
				a, err := p.AResource()
				if err == nil {
					ip := netip.AddrFrom4(a.A)
					res.mu.Lock()
					res.boot[host] = ip
					res.mu.Unlock()
					return ip, nil
				}
			}
			_ = p.SkipAnswer()
		}
		lastErr = fmt.Errorf("no A record for %s", host)
	}
	return netip.Addr{}, fmt.Errorf("bootstrap %s: %v", host, lastErr)
}

// serveTCP returns a conn that speaks DNS-over-TCP (RFC 7766 framing) backed by the resolver.
func (res *resolver) serveTCP() net.Conn {
	client, server := net.Pipe()
	go func() {
		defer server.Close()
		for {
			_ = server.SetReadDeadline(time.Now().Add(30 * time.Second))
			q, err := readFramed(server)
			if err != nil {
				return
			}
			ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			resp, err := res.Exchange(ctx, q)
			cancel()
			if err != nil {
				return
			}
			out := make([]byte, 2+len(resp))
			binary.BigEndian.PutUint16(out, uint16(len(resp)))
			copy(out[2:], resp)
			_ = server.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if _, err := server.Write(out); err != nil {
				return
			}
		}
	}()
	return client
}

// dnsPacketConn is a virtual UDP socket: each datagram written is a query, each read is an answer.
type dnsPacketConn struct {
	res      *resolver
	from     net.Addr
	answers  chan []byte
	closed   chan struct{}
	once     sync.Once
	deadline atomic.Time
}

func (res *resolver) packetConn(m *M.Metadata) net.PacketConn {
	return &dnsPacketConn{
		res:     res,
		from:    net.UDPAddrFromAddrPort(m.DestinationAddrPort()),
		answers: make(chan []byte, 16),
		closed:  make(chan struct{}),
	}
}

func (d *dnsPacketConn) WriteTo(b []byte, _ net.Addr) (int, error) {
	select {
	case <-d.closed:
		return 0, net.ErrClosed
	default:
	}
	q := append([]byte(nil), b...)
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		resp, err := d.res.Exchange(ctx, q)
		if err != nil {
			return
		}
		select {
		case d.answers <- resp:
		case <-d.closed:
		}
	}()
	return len(b), nil
}

func (d *dnsPacketConn) ReadFrom(b []byte) (int, net.Addr, error) {
	var timeout <-chan time.Time
	if dl := d.deadline.Load(); !dl.IsZero() {
		t := time.NewTimer(time.Until(dl))
		defer t.Stop()
		timeout = t.C
	}
	select {
	case resp := <-d.answers:
		return copy(b, resp), d.from, nil
	case <-d.closed:
		return 0, nil, net.ErrClosed
	case <-timeout:
		return 0, nil, os.ErrDeadlineExceeded
	}
}

func (d *dnsPacketConn) Close() error {
	d.once.Do(func() { close(d.closed) })
	return nil
}

func (d *dnsPacketConn) LocalAddr() net.Addr               { return &net.UDPAddr{IP: net.IPv4zero} }
func (d *dnsPacketConn) SetDeadline(t time.Time) error     { d.deadline.Store(t); return nil }
func (d *dnsPacketConn) SetReadDeadline(t time.Time) error { d.deadline.Store(t); return nil }
func (d *dnsPacketConn) SetWriteDeadline(time.Time) error  { return nil }
