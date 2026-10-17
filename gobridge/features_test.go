package t2s

import (
	"net/netip"
	"testing"

	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"golang.org/x/net/dns/dnsmessage"
)

func cnameResponse(t *testing.T, name, target string, ip [4]byte) []byte {
	t.Helper()
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{Response: true})
	n := dnsmessage.MustNewName(name + ".")
	tn := dnsmessage.MustNewName(target + ".")
	_ = b.StartQuestions()
	_ = b.Question(dnsmessage.Question{Name: n, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET})
	_ = b.StartAnswers()
	_ = b.CNAMEResource(dnsmessage.ResourceHeader{Name: n, Class: dnsmessage.ClassINET, TTL: 60}, dnsmessage.CNAMEResource{CNAME: tn})
	_ = b.AResource(dnsmessage.ResourceHeader{Name: tn, Class: dnsmessage.ClassINET, TTL: 60}, dnsmessage.AResource{A: ip})
	out, err := b.Finish()
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func TestCnameCloakingAndFakeIPAttribution(t *testing.T) {
	act.reset()
	res := newResolver(dnsConfig{Enabled: true, BlockAds: true, BlockCNAME: true, FakeIP: true}, &router{})
	q := query(t, "metrics.example.com.", dnsmessage.TypeA)
	var p dnsmessage.Parser
	h, _ := p.Start(q)
	qs, _ := p.Question()

	resp, err := res.inspect(h, qs, "metrics.example.com", cnameResponse(t, "metrics.example.com", "example.com.sc.omtrdc.net", [4]byte{1, 2, 3, 4}))
	if err != nil {
		t.Fatal(err)
	}
	_, ans := answers(t, resp)
	if len(ans) != 1 {
		t.Fatalf("want 1 fake answer, got %d", len(ans))
	}
	fake := netip.AddrFrom4(ans[0].Body.(*dnsmessage.AResource).A)
	if !fakeNet.Contains(fake) || res.cloaked.Load() != 1 {
		t.Fatalf("cloaked tracker not blocked: %v cloaked=%d", fake, res.cloaked.Load())
	}

	// A normal CNAME (CDN) passes and the address is remembered for reports.
	ok, _ := res.inspect(h, qs, "www.example.com", cnameResponse(t, "www.example.com", "example.cdn.cloudflare.net", [4]byte{5, 6, 7, 8}))
	if _, a := answers(t, ok); len(a) != 2 {
		t.Fatal("legit CNAME answer was altered")
	}
	if got := res.nameOf(netip.AddrFrom4([4]byte{5, 6, 7, 8})); got != "www.example.com" {
		t.Fatalf("nameOf = %q", got)
	}

	// The app connecting to the fake address is attributed and refused.
	r := &router{dns: res}
	SetPlatform(fakeOwner(10123))
	defer SetPlatform(nil)
	_, err = r.DialContext(t.Context(), &M.Metadata{Network: M.TCP, SrcIP: netip.MustParseAddr("198.18.0.1"), SrcPort: 40000, DstIP: fake, DstPort: 443})
	if err != errBlocked {
		t.Fatalf("want errBlocked, got %v", err)
	}
	hits := act.trackers["10123|metrics.example.com"]
	if hits == nil || hits.Count != 1 || hits.CNAME != "example.com.sc.omtrdc.net" {
		t.Fatalf("tracker hit not recorded: %+v", hits)
	}
}

type fakeOwner int

func (f fakeOwner) ConnectionOwner(int, string, int, string, int) int { return int(f) }

func TestLANBlock(t *testing.T) {
	r := &router{blockLAN: true}
	for _, ip := range []string{"192.168.1.10", "10.0.0.1", "224.0.0.251", "fe80::1", "172.20.1.1"} {
		if _, err := r.DialContext(t.Context(), &M.Metadata{Network: M.TCP, DstIP: netip.MustParseAddr(ip), DstPort: 80}); err != errBlocked {
			t.Fatalf("%s not blocked: %v", ip, err)
		}
	}
	for _, ip := range []string{"8.8.8.8", "100.64.1.1", "198.18.0.2"} {
		if isLAN(netip.MustParseAddr(ip)) {
			t.Fatalf("%s should not be LAN", ip)
		}
	}
}

func TestClassify(t *testing.T) {
	real, exit := "203.0.113.7", "198.51.100.9"
	if l, _, _ := classify(real, exit, map[string]string{"X-Forwarded-For": exit, "(origin)": exit}); l != "elite" {
		t.Fatalf("want elite, got %s", l)
	}
	if l, _, p := classify(real, exit, map[string]string{"Via": "1.1 squid", "(origin)": exit}); l != "anonymous" || p[0] != "Via" {
		t.Fatalf("want anonymous, got %s %v", l, p)
	}
	if l, leaks, _ := classify(real, exit, map[string]string{"X-Forwarded-For": real + ", " + exit}); l != "transparent" || len(leaks) != 1 {
		t.Fatalf("want transparent, got %s", l)
	}
}
