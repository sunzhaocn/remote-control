package api

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/gorilla/websocket"
	"github.com/redis/go-redis/v9"
	"simplerc/server/internal/lease"
	"simplerc/server/internal/protocol"
)

func sessionFixture() (*Server, *session) {
	sess := &session{id: "session", controllerID: "controller", controlledID: "controlled"}
	s := &Server{sessions: map[string]*session{sess.id: sess}, clients: map[string]*client{"controller": {deviceID: "controller"}, "controlled": {deviceID: "controlled"}}}
	return s, sess
}

func TestUnconfirmedSessionCannotRelay(t *testing.T) {
	s, sess := sessionFixture()
	if got, peer := s.confirmedSessionPeer(sess.id, "controller"); got != nil || peer != nil {
		t.Fatal("pending session can relay")
	}
	if !s.confirmSession(sess) {
		t.Fatal("confirm failed")
	}
	if got, peer := s.confirmedSessionPeer(sess.id, "controller"); got != sess || peer == nil || peer.deviceID != "controlled" {
		t.Fatal("confirmed peer missing")
	}
	if got, _ := s.confirmedSessionPeer(sess.id, "stranger"); got != nil {
		t.Fatal("foreign device can relay")
	}
}

func TestConfirmationAndTimeoutAreAtomic(t *testing.T) {
	for i := 0; i < 200; i++ {
		s, sess := sessionFixture()
		var confirmed, expired bool
		var wg sync.WaitGroup
		wg.Add(2)
		go func() { defer wg.Done(); confirmed = s.confirmSession(sess) }()
		go func() { defer wg.Done(); expired, _, _ = s.detachSession(sess, true) }()
		wg.Wait()
		if confirmed == expired {
			t.Fatalf("confirmed=%v expired=%v", confirmed, expired)
		}
		if confirmed && s.sessions[sess.id] != sess {
			t.Fatal("timer removed confirmed session")
		}
	}
}

func TestSessionRevocationIsIdempotentAndIdentityBound(t *testing.T) {
	s, sess := sessionFixture()
	if removed, _, _ := s.detachSession(&session{id: sess.id}, false); removed {
		t.Fatal("different instance removed session")
	}
	if removed, _, _ := s.detachSession(sess, false); !removed {
		t.Fatal("session not removed")
	}
	if removed, _, _ := s.detachSession(sess, false); removed {
		t.Fatal("removed twice")
	}
	if s.confirmSession(sess) {
		t.Fatal("revoked session confirmed")
	}
}

func TestDuplicateConfirmationDoesNotResetSession(t *testing.T) {
	s, sess := sessionFixture()
	if !s.confirmSession(sess) {
		t.Fatal("first confirmation failed")
	}
	seen := sess.controllerSeen
	if s.confirmSession(sess) {
		t.Fatal("duplicate confirmation accepted")
	}
	if !seen.Equal(sess.controllerSeen) {
		t.Fatal("duplicate reset state")
	}
}

func socketFixture(t *testing.T, id string) (*client, *websocket.Conn) {
	t.Helper()
	accepted := make(chan *websocket.Conn, 1)
	done := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		u := websocket.Upgrader{}
		conn, err := u.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		accepted <- conn
		<-done
	}))
	remote, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	if err != nil {
		srv.Close()
		t.Fatal(err)
	}
	local := <-accepted
	t.Cleanup(func() { _ = remote.Close(); _ = local.Close(); close(done); srv.Close() })
	return &client{deviceID: id, conn: local}, remote
}

func TestConfirmationPrecedesConcurrentRelayedSignalOnBothSockets(t *testing.T) {
	controlled, controlledSocket := socketFixture(t, "a-controlled")
	controller, controllerSocket := socketFixture(t, "b-controller")
	s := &Server{}
	controller.writeMu.Lock()
	confirmation := make(chan error, 1)
	go func() { confirmation <- s.sendConfirmation(controlled, controller, "session", map[string]any{}) }()
	// Pause confirmation after acquiring the first writer, before the second.
	deadline := time.Now().Add(time.Second)
	for controlled.writeMu.TryLock() {
		controlled.writeMu.Unlock()
		if time.Now().After(deadline) {
			controller.writeMu.Unlock()
			t.Fatal("confirmation did not acquire writer")
		}
		time.Sleep(time.Millisecond)
	}
	relayed := make(chan error, 1)
	go func() { relayed <- controlled.sendRaw(protocol.Envelope{Type: "OFFER", SessionID: "session"}) }()
	controller.writeMu.Unlock()
	if err := <-confirmation; err != nil {
		t.Fatal(err)
	}
	if err := <-relayed; err != nil {
		t.Fatal(err)
	}
	for _, socket := range []*websocket.Conn{controlledSocket, controllerSocket} {
		_ = socket.SetReadDeadline(time.Now().Add(time.Second))
		var env protocol.Envelope
		if err := socket.ReadJSON(&env); err != nil || env.Type != "CONFIRMED" {
			t.Fatalf("first message=%s err=%v", env.Type, err)
		}
	}
	var env protocol.Envelope
	if err := controlledSocket.ReadJSON(&env); err != nil || env.Type != "OFFER" {
		t.Fatalf("second message=%s err=%v", env.Type, err)
	}
}

func TestFailedConfirmationReleasesLeaseAndRevokesPeer(t *testing.T) {
	controlled, _ := socketFixture(t, "controlled")
	controller, controllerSocket := socketFixture(t, "controller")
	m := miniredis.RunT(t)
	rdb := redis.NewClient(&redis.Options{Addr: m.Addr()})
	defer rdb.Close()
	s, sess := sessionFixture()
	s.clients["controlled"] = controlled
	s.clients["controller"] = controller
	s.leases = lease.New(rdb, 30*time.Second)
	if ok, err := s.leases.Acquire(context.Background(), sess.controlledID, sess.id); err != nil || !ok {
		t.Fatal(err)
	}
	_ = controlled.conn.Close()
	r := httptest.NewRequest("GET", "/v1/ws", nil)
	if err := s.handle(controlled, protocol.Envelope{Type: "CONFIRM", SessionID: sess.id, Payload: []byte(`{"accepted":true}`)}, r); err == nil {
		t.Fatal("closed socket confirmation succeeded")
	}
	if s.sessions[sess.id] != nil || rdb.Exists(context.Background(), "lease:"+sess.controlledID).Val() != 0 {
		t.Fatal("failed confirmation left session/lease")
	}
	_ = controllerSocket.SetReadDeadline(time.Now().Add(time.Second))
	var env protocol.Envelope
	if err := controllerSocket.ReadJSON(&env); err != nil || env.Type != "REVOKED" {
		t.Fatalf("peer message=%s err=%v", env.Type, err)
	}
}
