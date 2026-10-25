package api

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"
	"simplerc/server/internal/config"
	"simplerc/server/internal/security"
)

type fakePairingRepository struct {
	err     error
	calls   int
	removed []string
}

func (p *fakePairingRepository) Publish(context.Context, string, string, string, time.Duration, bool) error {
	p.calls++
	return p.err
}
func (p *fakePairingRepository) RemoveRandom(_ context.Context, id, owner string) error {
	p.removed = append(p.removed, id+":"+owner)
	return nil
}

func pairingFixture() (*Server, *client, *fakePairingRepository) {
	c := &client{deviceID: "owner"}
	p := &fakePairingRepository{}
	s := &Server{cfg: config.Config{CredentialPepper: "test-pepper"}, clients: map[string]*client{c.deviceID: c}, pairings: map[string]*publishedPairing{}, pairingStore: p}
	return s, c, p
}

func TestPublishFailureKeepsPreviousCredentialActive(t *testing.T) {
	s, c, p := pairingFixture()
	old := &publishedPairing{deviceID: c.deviceID, hash: "old"}
	s.pairings["old-id"] = old
	p.err = errors.New("SERVER_ERROR")
	if err := s.publishPairing(context.Background(), c, pairingPayload{ID: "new-id", Code: "long-code-123"}, true); err == nil {
		t.Fatal("failure hidden")
	}
	if s.pairings["old-id"] != old || s.pairings["new-id"] != nil || len(p.removed) != 0 {
		t.Fatal("failed publish changed active credential")
	}
}

func TestPublishingCannotReplaceAnotherActiveOwner(t *testing.T) {
	s, c, p := pairingFixture()
	old := &publishedPairing{deviceID: "another", hash: "old"}
	s.pairings["shared-id"] = old
	for _, fixed := range []bool{false, true} {
		if err := s.publishPairing(context.Background(), c, pairingPayload{ID: "shared-id", Code: "long-code-123"}, fixed); err == nil || err.Error() != "ID_UNAVAILABLE" {
			t.Fatalf("err=%v", err)
		}
	}
	if s.pairings["shared-id"] != old || p.calls != 0 {
		t.Fatal("collision changed storage")
	}
}

func TestExpiredConnectionCannotPublish(t *testing.T) {
	s, c, p := pairingFixture()
	s.clients[c.deviceID] = &client{deviceID: c.deviceID}
	if err := s.publishPairing(context.Background(), c, pairingPayload{ID: "test-id", Code: "long-code-123"}, true); err == nil {
		t.Fatal("old connection published")
	}
	if p.calls != 0 {
		t.Fatal("old connection reached persistence")
	}
}

func TestPairingVerificationBindsHashOwnerAndExpiry(t *testing.T) {
	s, c, _ := pairingFixture()
	hash, err := security.HashCode("long-code-123", s.cfg.CredentialPepper)
	if err != nil {
		t.Fatal(err)
	}
	record := &publishedPairing{deviceID: c.deviceID, hash: hash}
	s.pairings["test-id"] = record
	if got, peer := s.verifyPairing("test-id", "long-code-123"); got != record || peer != c {
		t.Fatal("owner/hash binding failed")
	}
	if got, _ := s.verifyPairing("test-id", "wrong-code"); got != nil {
		t.Fatal("wrong code accepted")
	}
	s.pairings["test-id"] = &publishedPairing{deviceID: c.deviceID, hash: hash, expiresAt: time.Now().Add(-time.Second)}
	if got, _ := s.verifyPairing("test-id", "long-code-123"); got != nil {
		t.Fatal("expired code accepted")
	}
}

func TestRandomClaimAndTTLAreAtomic(t *testing.T) {
	m := miniredis.RunT(t)
	r := redis.NewClient(&redis.Options{Addr: m.Addr()})
	defer r.Close()
	ctx := context.Background()
	keys := []string{"pair-owner:id", "pair:id"}
	if n, err := claimRandomScript.Run(ctx, r, keys, "a", "hash-a", 60000).Int(); err != nil || n != 1 {
		t.Fatalf("n=%d err=%v", n, err)
	}
	if n, err := claimRandomScript.Run(ctx, r, keys, "b", "hash-b", 60000).Int(); err != nil || n != 0 {
		t.Fatalf("collision n=%d err=%v", n, err)
	}
	if r.HGet(ctx, keys[1], "hash").Val() != "hash-a" {
		t.Fatal("other owner overwrote hash")
	}
	if r.PTTL(ctx, keys[0]).Val() != time.Minute || r.PTTL(ctx, keys[1]).Val() != time.Minute {
		t.Fatal("claim and hash TTL differ")
	}
	m.FastForward(time.Minute + time.Millisecond)
	if n, err := claimRandomScript.Run(ctx, r, keys, "b", "hash-b", 60000).Int(); err != nil || n != 1 {
		t.Fatalf("reclaim n=%d err=%v", n, err)
	}
}

func TestOldOwnerCleanupCannotDeleteReclaimedPairing(t *testing.T) {
	m := miniredis.RunT(t)
	r := redis.NewClient(&redis.Options{Addr: m.Addr()})
	defer r.Close()
	ctx := context.Background()
	keys := []string{"pair-owner:id", "pair:id"}
	if err := claimRandomScript.Run(ctx, r, keys, "new-owner", "new-hash", 60000).Err(); err != nil {
		t.Fatal(err)
	}
	store := &storedPairings{rdb: r}
	if err := store.RemoveRandom(ctx, "id", "old-owner"); err != nil {
		t.Fatal(err)
	}
	if r.Get(ctx, keys[0]).Val() != "new-owner" || r.HGet(ctx, keys[1], "hash").Val() != "new-hash" {
		t.Fatal("stale cleanup deleted new owner's pairing")
	}
	if err := store.RemoveRandom(ctx, "id", "new-owner"); err != nil {
		t.Fatal(err)
	}
	if r.Exists(ctx, keys...).Val() != 0 {
		t.Fatal("owner could not remove own pairing")
	}
}
