package t2s

import (
	"context"
	"encoding/binary"
	"io"
	"net"
	"strconv"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

// socks5Server is a minimal no-auth CONNECT-only SOCKS5 server (no UDP), like many real proxies.
func socks5Server(t *testing.T) string {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				b := make([]byte, 262)
				if _, err := io.ReadFull(c, b[:2]); err != nil {
					return
				}
				io.ReadFull(c, b[:b[1]])
				c.Write([]byte{5, 0})
				io.ReadFull(c, b[:4])
				var host string
				switch b[3] {
				case 1:
					io.ReadFull(c, b[:4])
					host = net.IP(b[:4]).String()
				case 3:
					io.ReadFull(c, b[:1])
					n := int(b[0])
					io.ReadFull(c, b[:n])
					host = string(b[:n])
				case 4:
					io.ReadFull(c, b[:16])
					host = net.IP(b[:16]).String()
				}
				io.ReadFull(c, b[:2])
				port := binary.BigEndian.Uint16(b[:2])
				up, err := net.DialTimeout("tcp", net.JoinHostPort(host, strconv.Itoa(int(port))), 10*time.Second)
				if err != nil {
					c.Write([]byte{5, 5, 0, 1, 0, 0, 0, 0, 0, 0})
					return
				}
				defer up.Close()
				c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
				go io.Copy(up, c)
				io.Copy(c, up)
			}(c)
		}
	}()
	return ln.Addr().String()
}

func query(t *testing.T, name string, typ dnsmessage.Type) []byte {
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: 4242, RecursionDesired: true})
	b.StartQuestions()
	b.Question(dnsmessage.Question{Name: dnsmessage.MustNewName(name), Type: typ, Class: dnsmessage.ClassINET})
	q, err := b.Finish()
	if err != nil {
		t.Fatal(err)
	}
	return q
}

func answers(t *testing.T, resp []byte) (dnsmessage.Header, []dnsmessage.Resource) {
	var m dnsmessage.Message
	if err := m.Unpack(resp); err != nil {
		t.Fatal(err)
	}
	return m.Header, m.Answers
}

func TestResolverOverTCPOnlyProxy(t *testing.T) {
	addr := socks5Server(t)
	for _, tc := range []struct {
		name, spec string
		cfg        dnsConfig
	}{
		{"doh-ip", "socks5://" + addr, dnsConfig{Mode: "doh", URL: "https://1.1.1.1/dns-query", BlockAds: true}},
		{"doh-hostname-bootstrap", "socks5://" + addr, dnsConfig{Mode: "doh", URL: "https://dns.quad9.net/dns-query"}},
		{"dot", "socks5://" + addr, dnsConfig{Mode: "dot", Server: "1.1.1.1", ServerName: "one.one.one.one"}},
		{"chain-doh", `["socks5://` + addr + `","socks5://` + addr + `"]`, dnsConfig{Mode: "doh", URL: "https://1.1.1.1/dns-query"}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			p, hops, err := parseProxies(tc.spec)
			if err != nil {
				t.Fatal(err)
			}
			r := &router{}
			r.setUpstream(p, hops)
			res := newResolver(tc.cfg, r)
			ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
			defer cancel()
			resp, err := res.Exchange(ctx, query(t, "example.com.", dnsmessage.TypeA))
			if err != nil {
				t.Fatal(err)
			}
			h, ans := answers(t, resp)
			if h.ID != 4242 || h.RCode != dnsmessage.RCodeSuccess || len(ans) == 0 {
				t.Fatalf("bad answer: %+v %d answers, failed=%d", h, len(ans), res.failed.Load())
			}
			if _, err := res.Exchange(ctx, query(t, "example.com.", dnsmessage.TypeA)); err != nil || res.cached.Load() != 1 {
				t.Fatalf("expected cache hit, cached=%d err=%v", res.cached.Load(), err)
			}
		})
	}
}

func TestBlockingAndIPv6Filter(t *testing.T) {
	res := newResolver(dnsConfig{BlockAds: true, Custom: []string{"evil.test"}, Allow: []string{"allowed.doubleclick.net"}}, &router{})
	res.noAAAA = true
	if !res.isBlocked("ads.g.doubleclick.net") || !res.isBlocked("x.evil.test") || res.isBlocked("allowed.doubleclick.net") || res.isBlocked("example.com") {
		t.Fatal("suffix matching wrong")
	}
	resp, _ := res.Exchange(context.Background(), query(t, "stats.doubleclick.net.", dnsmessage.TypeA))
	_, ans := answers(t, resp)
	if len(ans) != 1 || ans[0].Body.(*dnsmessage.AResource).A != [4]byte{} {
		t.Fatalf("expected sinkhole, got %v", ans)
	}
	resp, _ = res.Exchange(context.Background(), query(t, "example.com.", dnsmessage.TypeAAAA))
	if _, ans := answers(t, resp); len(ans) != 0 {
		t.Fatal("expected empty AAAA")
	}
	n := addDomains(map[string]struct{}{}, "0.0.0.0 ads.example\n||tracker.example^$important\n@@||ok.example^\n# c\n*.wild.example\n/regex/\n")
	if n != 3 {
		t.Fatalf("parsed %d domains", n)
	}
}

func TestToolsThroughChain(t *testing.T) {
	addr := socks5Server(t)
	spec := `["socks5://` + addr + `","socks5://` + addr + `"]`
	if ms, err := Latency(spec, 10); err != nil {
		t.Fatalf("latency: %v", err)
	} else {
		t.Logf("chain latency %d ms", ms)
	}
	body, err := HTTPGet("socks5://"+addr, "https://cloudflare.com/cdn-cgi/trace", 15)
	if err != nil || len(body) == 0 {
		t.Fatalf("httpget: %v", err)
	}
}
