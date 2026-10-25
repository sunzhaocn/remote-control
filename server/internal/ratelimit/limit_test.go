package ratelimit

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"
)

func testLimiter(t *testing.T) (*Limiter, *miniredis.Miniredis, *redis.Client) {
	t.Helper()
	m := miniredis.RunT(t)
	r := redis.NewClient(&redis.Options{Addr: m.Addr()})
	t.Cleanup(func() { _ = r.Close() })
	return New(r), m, r
}

func TestFailureLimitBlocksUntilOriginalWindowExpires(t *testing.T) {
	l, m, _ := testLimiter(t)
	ctx := context.Background()
	for i := 0; i < 10; i++ {
		if _, _, err := l.RecordFailure(ctx, "device", 10, time.Minute); err != nil {
			t.Fatal(err)
		}
	}
	if left, err := l.Remaining(ctx, "device", 10, time.Minute); err != nil || left != time.Minute {
		t.Fatalf("left=%v err=%v", left, err)
	}
	m.FastForward(30 * time.Second)
	if left, _ := l.Remaining(ctx, "device", 10, time.Minute); left != 30*time.Second {
		t.Fatalf("ban extended: %v", left)
	}
	m.FastForward(31 * time.Second)
	if left, _ := l.Remaining(ctx, "device", 10, time.Minute); left != 0 {
		t.Fatalf("still banned: %v", left)
	}
}

func TestLegacyCounterWithoutExpiryIsRepaired(t *testing.T) {
	l, _, r := testLimiter(t)
	ctx := context.Background()
	if err := r.Set(ctx, "limit:legacy", 10, 0).Err(); err != nil {
		t.Fatal(err)
	}
	if left, err := l.Remaining(ctx, "legacy", 10, time.Minute); err != nil || left != time.Minute {
		t.Fatalf("left=%v err=%v", left, err)
	}
	if ttl := r.TTL(ctx, "limit:legacy").Val(); ttl != time.Minute {
		t.Fatalf("persistent counter: %v", ttl)
	}
}

func TestConcurrentFailureCountAndExpiryAreAtomic(t *testing.T) {
	l, _, r := testLimiter(t)
	ctx := context.Background()
	var wg sync.WaitGroup
	for i := 0; i < 30; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, _, err := l.RecordFailure(ctx, "parallel", 10, time.Minute); err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	if n := r.Get(ctx, "limit:parallel").Val(); n != "30" {
		t.Fatalf("count=%s", n)
	}
	if ttl := r.TTL(ctx, "limit:parallel").Val(); ttl != time.Minute {
		t.Fatalf("ttl=%v", ttl)
	}
}
