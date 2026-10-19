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
	xff := r.Header.Get("X-Forwarded-For")
	if !s.cfg.TrustProxyHeaders || !trusted(peer) || len(xff) > 2048 {
		return peer.String()
	}
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
