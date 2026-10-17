package t2s

import (
	"encoding/json"
	"net"
	"net/netip"
	"sort"
	"sync"
	"sync/atomic"
	"time"
)

// Platform is implemented on the Android side so connections can be attributed to apps.
type Platform interface {
	// ConnectionOwner returns the UID that owns the socket (protocol 6 = TCP, 17 = UDP), or -1.
	ConnectionOwner(protocol int, srcIP string, srcPort int, dstIP string, dstPort int) int
}

type platformHolder struct{ p Platform }

var platformV atomic.Value

// SetPlatform registers (or clears with nil) the app-attribution callback.
func SetPlatform(p Platform) { platformV.Store(platformHolder{p}) }

func connectionOwner(proto int, src netip.Addr, sport uint16, dst netip.Addr, dport uint16) int {
	h, _ := platformV.Load().(platformHolder)
	if h.p == nil || !src.IsValid() || !dst.IsValid() {
		return -1
	}
	return h.p.ConnectionOwner(proto, src.Unmap().String(), int(sport), dst.Unmap().String(), int(dport))
}

const (
	maxDestsPerApp = 256
	otherDest      = "(other)"
)

type destStat struct {
	up, down, conns, blocked atomic.Int64
	last                     atomic.Int64
}

type appStat struct {
	up, down, conns, blocked atomic.Int64
	mu                       sync.Mutex
	dests                    map[string]*destStat
}

type trackerHit struct {
	UID    int    `json:"uid"`
	Domain string `json:"domain"`
	CNAME  string `json:"cname,omitempty"`
	Count  int64  `json:"count"`
	Last   int64  `json:"last"`
}

// activity keeps per-app traffic, destinations and tracker attempts in memory only.
type activity struct {
	mu         sync.Mutex
	apps       map[int]*appStat
	trackers   map[string]*trackerHit
	lanBlocked atomic.Int64
}

var act = &activity{apps: map[int]*appStat{}, trackers: map[string]*trackerHit{}}

func (a *activity) app(uid int) *appStat {
	a.mu.Lock()
	defer a.mu.Unlock()
	s := a.apps[uid]
	if s == nil {
		s = &appStat{dests: map[string]*destStat{}}
		a.apps[uid] = s
	}
	return s
}

func (s *appStat) dest(host string) *destStat {
	s.mu.Lock()
	defer s.mu.Unlock()
	d := s.dests[host]
	if d == nil {
		if len(s.dests) >= maxDestsPerApp {
			host = otherDest
			if d = s.dests[host]; d != nil {
				return d
			}
		}
		d = &destStat{}
		s.dests[host] = d
	}
	return d
}

func (a *activity) open(uid int, host string) (*appStat, *destStat) {
	s := a.app(uid)
	d := s.dest(host)
	s.conns.Add(1)
	d.conns.Add(1)
	d.last.Store(time.Now().UnixMilli())
	return s, d
}

func (a *activity) block(uid int, host string) {
	s := a.app(uid)
	d := s.dest(host)
	s.blocked.Add(1)
	d.blocked.Add(1)
	d.last.Store(time.Now().UnixMilli())
}

func (a *activity) tracker(uid int, domain, cname string) {
	a.block(uid, domain)
	a.mu.Lock()
	defer a.mu.Unlock()
	key := itoa(uid) + "|" + domain
	h := a.trackers[key]
	if h == nil {
		if len(a.trackers) > 4096 {
			return
		}
		h = &trackerHit{UID: uid, Domain: domain, CNAME: cname}
		a.trackers[key] = h
	}
	h.Count++
	h.Last = time.Now().UnixMilli()
}

func itoa(i int) string { b, _ := json.Marshal(i); return string(b) }

func (a *activity) reset() {
	a.mu.Lock()
	a.apps = map[int]*appStat{}
	a.trackers = map[string]*trackerHit{}
	a.mu.Unlock()
	a.lanBlocked.Store(0)
}

type destJSON struct {
	Host    string `json:"host"`
	Up      int64  `json:"up"`
	Down    int64  `json:"down"`
	Conns   int64  `json:"conns"`
	Blocked int64  `json:"blocked"`
	Last    int64  `json:"last"`
}

type appJSON struct {
	UID     int        `json:"uid"`
	Up      int64      `json:"up"`
	Down    int64      `json:"down"`
	Conns   int64      `json:"conns"`
	Blocked int64      `json:"blocked"`
	Dests   []destJSON `json:"dests"`
}

func (a *activity) snapshot() string {
	a.mu.Lock()
	apps := make(map[int]*appStat, len(a.apps))
	for k, v := range a.apps {
		apps[k] = v
	}
	a.mu.Unlock()
	out := struct {
		Apps       []appJSON `json:"apps"`
		LanBlocked int64     `json:"lanBlocked"`
	}{Apps: []appJSON{}, LanBlocked: a.lanBlocked.Load()}
	for uid, s := range apps {
		aj := appJSON{UID: uid, Up: s.up.Load(), Down: s.down.Load(), Conns: s.conns.Load(), Blocked: s.blocked.Load()}
		s.mu.Lock()
		for host, d := range s.dests {
			aj.Dests = append(aj.Dests, destJSON{host, d.up.Load(), d.down.Load(), d.conns.Load(), d.blocked.Load(), d.last.Load()})
		}
		s.mu.Unlock()
		sort.Slice(aj.Dests, func(i, j int) bool {
			x, y := aj.Dests[i], aj.Dests[j]
			if x.Up+x.Down != y.Up+y.Down {
				return x.Up+x.Down > y.Up+y.Down
			}
			return x.Conns+x.Blocked > y.Conns+y.Blocked
		})
		if len(aj.Dests) > 100 {
			aj.Dests = aj.Dests[:100]
		}
		out.Apps = append(out.Apps, aj)
	}
	sort.Slice(out.Apps, func(i, j int) bool { return out.Apps[i].Up+out.Apps[i].Down > out.Apps[j].Up+out.Apps[j].Down })
	b, _ := json.Marshal(out)
	return string(b)
}

func (a *activity) drainTrackers() string {
	a.mu.Lock()
	hits := make([]*trackerHit, 0, len(a.trackers))
	for _, h := range a.trackers {
		hits = append(hits, h)
	}
	a.trackers = map[string]*trackerHit{}
	a.mu.Unlock()
	b, _ := json.Marshal(hits)
	return string(b)
}

// Activity returns per-app data usage and destinations for the current session as JSON.
func Activity() string { return act.snapshot() }

// DrainTrackers returns tracker attempts recorded since the previous call and forgets them.
func DrainTrackers() string { return act.drainTrackers() }

// ClearActivity wipes per-app usage, tracker attempts, the DNS cache and traffic statistics.
func ClearActivity() {
	act.reset()
	mu.Lock()
	r := rt
	mu.Unlock()
	if r != nil && r.dns != nil {
		r.dns.flush()
		r.dns.resetCounters()
	}
	resetStatistics()
}

type countedConn struct {
	net.Conn
	a *appStat
	d *destStat
}

func (c *countedConn) Read(b []byte) (int, error) {
	n, err := c.Conn.Read(b)
	if n > 0 {
		c.a.down.Add(int64(n))
		c.d.down.Add(int64(n))
	}
	return n, err
}

func (c *countedConn) Write(b []byte) (int, error) {
	n, err := c.Conn.Write(b)
	if n > 0 {
		c.a.up.Add(int64(n))
		c.d.up.Add(int64(n))
	}
	return n, err
}

type countedPacketConn struct {
	net.PacketConn
	a *appStat
	d *destStat
}

func (c *countedPacketConn) ReadFrom(b []byte) (int, net.Addr, error) {
	n, addr, err := c.PacketConn.ReadFrom(b)
	if n > 0 {
		c.a.down.Add(int64(n))
		c.d.down.Add(int64(n))
	}
	return n, addr, err
}

func (c *countedPacketConn) WriteTo(b []byte, addr net.Addr) (int, error) {
	n, err := c.PacketConn.WriteTo(b, addr)
	if n > 0 {
		c.a.up.Add(int64(n))
		c.d.up.Add(int64(n))
	}
	return n, err
}

// Fake addresses handed out for blocked tracker domains so the app's connection attempt reaches
// the tunnel and can be attributed (DuckDuckGo-style) instead of silently failing at DNS.
var fakeNet = netip.MustParsePrefix("198.18.128.0/17")

type fakeEntry struct{ domain, cname string }

type fakePool struct {
	mu     sync.Mutex
	next   uint32
	byName map[string]netip.Addr
	byIP   map[netip.Addr]fakeEntry
}

var fakes = &fakePool{byName: map[string]netip.Addr{}, byIP: map[netip.Addr]fakeEntry{}}

func (f *fakePool) addr(domain, cname string) netip.Addr {
	f.mu.Lock()
	defer f.mu.Unlock()
	if ip, ok := f.byName[domain]; ok {
		f.byIP[ip] = fakeEntry{domain, cname}
		return ip
	}
	f.next = f.next%32766 + 1
	base := fakeNet.Addr().As4()
	n := uint32(base[2])<<8 | uint32(base[3]) + f.next
	ip := netip.AddrFrom4([4]byte{base[0], base[1], byte(n >> 8), byte(n)})
	if old, ok := f.byIP[ip]; ok {
		delete(f.byName, old.domain)
	}
	f.byName[domain] = ip
	f.byIP[ip] = fakeEntry{domain, cname}
	return ip
}

func (f *fakePool) lookup(ip netip.Addr) (fakeEntry, bool) {
	ip = ip.Unmap()
	if !fakeNet.Contains(ip) {
		return fakeEntry{}, false
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	e, ok := f.byIP[ip]
	if !ok {
		e = fakeEntry{domain: ip.String()}
	}
	return e, true
}

var lanPrefixes = []netip.Prefix{
	netip.MustParsePrefix("10.0.0.0/8"),
	netip.MustParsePrefix("172.16.0.0/12"),
	netip.MustParsePrefix("192.168.0.0/16"),
	netip.MustParsePrefix("169.254.0.0/16"),
	netip.MustParsePrefix("224.0.0.0/4"),
	netip.MustParsePrefix("255.255.255.255/32"),
	netip.MustParsePrefix("fc00::/7"),
	netip.MustParsePrefix("fe80::/10"),
	netip.MustParsePrefix("ff00::/8"),
}

// isLAN reports private, link-local, multicast and broadcast destinations.
func isLAN(ip netip.Addr) bool {
	ip = ip.Unmap()
	if isDNSVIP(ip) {
		return false
	}
	for _, p := range lanPrefixes {
		if p.Contains(ip) {
			return true
		}
	}
	return false
}
