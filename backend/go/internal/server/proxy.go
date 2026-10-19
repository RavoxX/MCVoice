package server

import (
	"net"
	"net/http"
	"net/netip"
	"strings"
)

func (s *Server) clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	peer, err := netip.ParseAddr(host)
	if err != nil {
		return "0.0.0.0"
	}
	peer = peer.Unmap()
	trusted := func(ip netip.Addr) bool {
		for _, prefix := range s.cfg.TrustedProxyCIDRs {
			if prefix.Contains(ip) {
				return true
			}
		}
		return false
	}
	if !s.cfg.TrustProxyHeaders || !trusted(peer) {
		return peer.String()
	}
	values := r.Header.Values("X-Forwarded-For")
	total := 0
	for _, value := range values {
		total += len(value) + 1
		if total > 2049 {
			return peer.String()
		}
	}
	xff := strings.Join(values, ",")
	hops := strings.Split(xff, ",")
	if len(hops) > 32 {
		return peer.String()
	}
	current := peer
	for i := len(hops) - 1; i >= 0 && trusted(current); i-- {
		next, err := netip.ParseAddr(strings.TrimSpace(hops[i]))
		if err != nil || next.Zone() != "" {
			return peer.String()
		}
		current = next.Unmap()
	}
	return current.String()
}
