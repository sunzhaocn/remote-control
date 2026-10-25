package api

import (
	"context"
	"encoding/json"
	"net"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"
	"simplerc/server/internal/config"
	"simplerc/server/internal/protocol"
	"simplerc/server/internal/ratelimit"
	"simplerc/server/internal/security"
)

func TestConnectBanIsEnforcedBeforeValidPassword(t *testing.T) {
	m := miniredis.RunT(t)
	rdb := redis.NewClient(&redis.Options{Addr: m.Addr()})
	defer rdb.Close()
	s, c, _ := pairingFixture()
	s.limits = ratelimit.New(rdb)
	hash, err := security.HashCode("long-code-123", s.cfg.CredentialPepper)
	if err != nil {
		t.Fatal(err)
	}
	s.pairings["target-id"] = &publishedPairing{deviceID: "target", hash: hash}
	s.clients["target"] = &client{deviceID: "target"}
	r := httptest.NewRequest("GET", "/v1/ws", nil)
	r.RemoteAddr = "192.0.2.10:1234"
	for i := 0; i < 10; i++ {
		s.recordValidationFailure(context.Background(), r, c.deviceID, "target-id")
	}
	payload, _ := json.Marshal(connectPayload{TargetID: "target-id", Code: "long-code-123", VideoPolicy: "tuned"})
	err = s.handle(c, protocol.Envelope{Type: "CONNECT", Payload: payload}, r)
	if limited, ok := err.(rateLimitError); !ok || limited.seconds != 600 {
		t.Fatalf("valid password bypassed ban: %v", err)
	}
	if got := rdb.Get(context.Background(), "limit:verify:device:"+c.deviceID).Val(); got != "10" {
		t.Fatalf("blocked request changed counter: %s", got)
	}
}

func TestForwardedAddressIsOnlyAcceptedFromExplicitTrustedProxy(t *testing.T) {
	_, subnet, _ := net.ParseCIDR("127.0.0.1/32")
	s := &Server{cfg: config.Config{TrustedProxyCIDRs: []*net.IPNet{subnet}}}
	r := httptest.NewRequest("GET", "/", nil)
	r.Header.Set("X-Real-IP", "198.51.100.4")
	r.RemoteAddr = "192.0.2.1:1234"
	if got := s.clientIP(r); got != "192.0.2.1" {
		t.Fatalf("untrusted spoof: %s", got)
	}
	r.RemoteAddr = "127.0.0.1:1234"
	if got := s.clientIP(r); got != "198.51.100.4" {
		t.Fatalf("trusted forwarding ignored: %s", got)
	}
	r.Header.Set("X-Real-IP", "198.51.100.4, 127.0.0.1")
	if got := s.clientIP(r); got != "127.0.0.1" {
		t.Fatalf("invalid forwarded address accepted: %s", got)
	}
}

func TestRetrySecondsRoundsUp(t *testing.T) {
	if retrySeconds(time.Millisecond) != 1 || retrySeconds(time.Second+time.Millisecond) != 2 || retrySeconds(0) != 0 {
		t.Fatal("retry window rounded down")
	}
}
