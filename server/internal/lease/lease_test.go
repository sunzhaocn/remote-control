package lease

import (
	"context"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"
)

func TestLeasePreventsSecondSessionAndOnlyOwnerCanRenewOrRelease(t *testing.T) {
	m := miniredis.RunT(t)
	r := redis.NewClient(&redis.Options{Addr: m.Addr()})
	defer r.Close()
	s := New(r, 30*time.Second)
	ctx := context.Background()
	if ok, err := s.Acquire(ctx, "controlled-device", "first"); err != nil || !ok {
		t.Fatalf("acquire: %v %v", ok, err)
	}
	if ok, err := s.Acquire(ctx, "controlled-device", "second"); err != nil || ok {
		t.Fatalf("duplicate acquire: %v %v", ok, err)
	}
	if ok, err := s.Renew(ctx, "controlled-device", "second"); err != nil || ok {
		t.Fatalf("foreign renewal: %v %v", ok, err)
	}
	if err := s.Release(ctx, "controlled-device", "second"); err != nil {
		t.Fatal(err)
	}
	if r.Get(ctx, "lease:controlled-device").Val() != "first" {
		t.Fatal("foreign release succeeded")
	}
	m.FastForward(20 * time.Second)
	if ok, err := s.Renew(ctx, "controlled-device", "first"); err != nil || !ok {
		t.Fatalf("owner renewal: %v %v", ok, err)
	}
	if r.TTL(ctx, "lease:controlled-device").Val() != 30*time.Second {
		t.Fatal("owner renewal did not extend lease")
	}
}

func TestExpiredSessionCannotReleaseReplacementLease(t *testing.T) {
	m := miniredis.RunT(t)
	r := redis.NewClient(&redis.Options{Addr: m.Addr()})
	defer r.Close()
	s := New(r, time.Second)
	ctx := context.Background()
	if ok, err := s.Acquire(ctx, "device", "old"); err != nil || !ok {
		t.Fatal(err)
	}
	m.FastForward(2 * time.Second)
	if ok, err := s.Acquire(ctx, "device", "new"); err != nil || !ok {
		t.Fatal(err)
	}
	if err := s.Release(ctx, "device", "old"); err != nil {
		t.Fatal(err)
	}
	if r.Get(ctx, "lease:device").Val() != "new" {
		t.Fatal("stale session released replacement")
	}
}
