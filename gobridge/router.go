package t2s

import (
	"context"
	"errors"
	"net"
	"net/netip"

	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"github.com/xjasonlyu/tun2socks/v2/proxy"
	"go.uber.org/atomic"
)

var errBlocked = errors.New("blocked by privacy policy")

const (
	protoTCP = 6
	protoUDP = 17
)

// router sits in front of the real proxy: it answers DNS locally, applies UDP policy and
// lets the upstream proxy be swapped at runtime (failover) without rebuilding the TUN.
type router struct {
	upstream  atomic.Value // proxy.Proxy
	udpOK     atomic.Bool
	proxyUDP  bool
	blockQUIC bool
	blockSTUN bool
	noIPv6    bool
	blockLAN  bool
	dns       *resolver
}

func (r *router) proxy() proxy.Proxy { return r.upstream.Load().(proxy.Proxy) }

func (r *router) setUpstream(p proxy.Proxy, hops int) {
	r.upstream.Store(p)
	r.udpOK.Store(r.proxyUDP && hops == 1)
}

func isDNSVIP(ip netip.Addr) bool { return ip == dnsVIP4 || ip == dnsVIP6 }

// host names a destination for reports, preferring the domain the app looked up.
func (r *router) host(ip netip.Addr) string {
	if r.dns != nil {
		if name := r.dns.nameOf(ip); name != "" {
			return name
		}
	}
	return ip.Unmap().String()
}

// screen applies tracker and LAN policy that is attributed to the calling app.
func (r *router) screen(proto int, m *M.Metadata) (int, error) {
	uid := connectionOwner(proto, m.SrcIP, m.SrcPort, m.DstIP, m.DstPort)
	if e, ok := fakes.lookup(m.DstIP); ok {
		act.tracker(uid, e.domain, e.cname)
		return uid, errBlocked
	}
	if r.blockLAN && isLAN(m.DstIP) {
		act.lanBlocked.Add(1)
		act.block(uid, m.DstIP.Unmap().String())
		return uid, errBlocked
	}
	return uid, nil
}

func (r *router) DialContext(ctx context.Context, m *M.Metadata) (net.Conn, error) {
	if r.dns != nil {
		if m.DstPort == 53 {
			return r.dns.serveTCP(), nil
		}
		// Refuse DNS-over-TLS to our virtual resolver so Android falls back to port 53, which we encrypt.
		if m.DstPort == 853 && isDNSVIP(m.DstIP) {
			return nil, errBlocked
		}
	}
	uid, err := r.screen(protoTCP, m)
	if err != nil {
		return nil, err
	}
	host := r.host(m.DstIP)
	if r.noIPv6 && m.DstIP.Is6() && !m.DstIP.Is4In6() {
		act.block(uid, host)
		return nil, errBlocked
	}
	c, err := r.proxy().DialContext(ctx, m)
	if err != nil {
		return nil, err
	}
	a, d := act.open(uid, host)
	return &countedConn{Conn: c, a: a, d: d}, nil
}

var stunPorts = map[uint16]bool{3478: true, 3479: true, 5349: true, 19302: true, 19303: true, 19305: true, 19307: true, 19309: true}

func (r *router) DialUDP(m *M.Metadata) (net.PacketConn, error) {
	if r.dns != nil && m.DstPort == 53 {
		return r.dns.packetConn(m), nil
	}
	uid, err := r.screen(protoUDP, m)
	if err != nil {
		return nil, err
	}
	host := r.host(m.DstIP)
	if (r.noIPv6 && m.DstIP.Is6() && !m.DstIP.Is4In6()) ||
		(r.blockQUIC && (m.DstPort == 443 || m.DstPort == 80)) ||
		(r.blockSTUN && stunPorts[m.DstPort]) {
		act.block(uid, host)
		return nil, errBlocked
	}
	if !r.udpOK.Load() {
		return nil, errors.New("udp disabled")
	}
	pc, err := r.proxy().DialUDP(m)
	if err != nil {
		return nil, err
	}
	a, d := act.open(uid, host)
	return &countedPacketConn{PacketConn: pc, a: a, d: d}, nil
}

// dialTCP opens a TCP stream to an IP:port through the current upstream proxy.
func (r *router) dialTCP(ctx context.Context, ip netip.Addr, port uint16) (net.Conn, error) {
	return r.proxy().DialContext(ctx, &M.Metadata{Network: M.TCP, DstIP: ip, DstPort: port})
}
