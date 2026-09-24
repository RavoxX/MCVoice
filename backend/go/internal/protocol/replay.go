package protocol

// ReplayWindow is an RFC 6479 style sliding anti-replay window of 1024 counters.
// Not safe for concurrent use; callers hold the session lock.
type ReplayWindow struct {
	top    uint64
	bitmap [WindowSize / 64]uint64
}

const WindowSize = 1024

// Check reports whether counter c is acceptable without modifying the window.
func (w *ReplayWindow) Check(c uint64) bool {
	if c == 0 {
		return false
	}
	if c > w.top {
		return true
	}
	if w.top-c >= WindowSize {
		return false
	}
	idx := c % WindowSize
	return w.bitmap[idx/64]&(1<<(idx%64)) == 0
}

// Update marks c as seen. It must only be called after Check(c) returned true
// and the datagram authenticated. It reports whether c advanced the window top.
func (w *ReplayWindow) Update(c uint64) (newTop bool) {
	if c > w.top {
		diff := c - w.top
		if diff >= WindowSize {
			w.bitmap = [WindowSize / 64]uint64{}
		} else {
			for i := w.top + 1; i <= c; i++ {
				idx := i % WindowSize
				w.bitmap[idx/64] &^= 1 << (idx % 64)
			}
		}
		w.top = c
		newTop = true
	}
	idx := c % WindowSize
	w.bitmap[idx/64] |= 1 << (idx % 64)
	return newTop
}

// CheckAndUpdate is Check followed by Update.
func (w *ReplayWindow) CheckAndUpdate(c uint64) bool {
	if !w.Check(c) {
		return false
	}
	w.Update(c)
	return true
}
