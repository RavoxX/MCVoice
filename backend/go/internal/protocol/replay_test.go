package protocol

import (
	"testing"
	"time"
)

func TestReplayMaximumCounterAdvanceTerminates(t *testing.T) {
	for _, gap := range []uint64{1, WindowSize - 1} {
		w := &ReplayWindow{}
		maximum := ^uint64(0)
		if !w.CheckAndUpdate(maximum - gap) {
			t.Fatal("initial authenticated counter rejected")
		}
		done := make(chan bool, 1)
		go func() { done <- w.CheckAndUpdate(maximum) }()
		select {
		case accepted := <-done:
			if !accepted || w.Check(maximum) || w.Check(maximum-gap) || w.Check(0) {
				t.Fatal("maximum transition changed replay semantics")
			}
			if !w.CheckAndUpdate(maximum-2) || w.Check(maximum-WindowSize) || w.Check(maximum-WindowSize+1) != (gap != WindowSize-1) {
				t.Fatal("window boundary or valid reordering changed")
			}
		case <-time.After(time.Second):
			t.Fatalf("counter advance to maximum did not terminate (gap %d)", gap)
		}
	}
}
