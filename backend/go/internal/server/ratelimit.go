package server

import (
	"sync"
	"time"
)

// tokenBucket is a classic token bucket. Not safe for concurrent use.
type tokenBucket struct {
	rate, burst, tokens float64
	last                time.Time
}

func newBucket(rate, burst float64) tokenBucket {
	return tokenBucket{rate: rate, burst: burst, tokens: burst}
}

func (b *tokenBucket) allow(now time.Time) bool {
	if !b.last.IsZero() {
		b.tokens += now.Sub(b.last).Seconds() * b.rate
		if b.tokens > b.burst {
			b.tokens = b.burst
		}
	}
	b.last = now
	if b.tokens >= 1 {
		b.tokens--
		return true
	}
	return false
}

// ipLimiter limits events per key per minute (fixed window) with bounded memory.
type ipLimiter struct {
	mu      sync.Mutex
	perMin  int
	window  time.Time
	counts  map[string]int
	maxKeys int
}

func newIPLimiter(perMin int) *ipLimiter {
	return &ipLimiter{perMin: perMin, counts: map[string]int{}, maxKeys: 100000}
}

func (l *ipLimiter) allow(key string, now time.Time) bool {
	if l.perMin <= 0 {
		return true
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	if now.Sub(l.window) >= time.Minute {
		l.window = now
		clear(l.counts)
	}
	n := l.counts[key]
	if n >= l.perMin {
		return false
	}
	if len(l.counts) >= l.maxKeys {
		return false // fail closed under key flooding
	}
	l.counts[key] = n + 1
	return true
}

// peek reports whether key is currently over its budget without counting.
func (l *ipLimiter) blocked(key string, now time.Time) bool {
	if l.perMin <= 0 {
		return false
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	if now.Sub(l.window) >= time.Minute {
		return false
	}
	return l.counts[key] >= l.perMin
}
