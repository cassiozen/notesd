package auth

import (
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestTakeLetsNoBurstPastTheLimit(t *testing.T) {
	l := NewLimiter(5, time.Minute)
	var allowed atomic.Int32
	var wg sync.WaitGroup
	for range 50 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if ok, _ := l.Take("a"); ok {
				allowed.Add(1)
			}
		}()
	}
	wg.Wait()
	if allowed.Load() != 5 {
		t.Fatalf("%d allowed", allowed.Load())
	}
	if ok, wait := l.Take("a"); ok || wait <= 0 {
		t.Fatalf("over: %v %v", ok, wait)
	}
	if ok, _ := l.Take("b"); !ok {
		t.Fatal("another key")
	}
}
