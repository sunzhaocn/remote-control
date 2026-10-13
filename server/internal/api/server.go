package api

import (
	"context"
	"crypto/ecdsa"
	"crypto/hmac"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
	"simplerc/server/internal/config"
	"simplerc/server/internal/lease"
	"simplerc/server/internal/protocol"
	"simplerc/server/internal/ratelimit"
	"simplerc/server/internal/security"
)

type Server struct {
	cfg      config.Config
	db       *pgxpool.Pool
	rdb      *redis.Client
	leases   *lease.Store
	limits   *ratelimit.Limiter
	upgrader websocket.Upgrader
	mu       sync.RWMutex
	clients  map[string]*client
	pairings map[string]string
	sessions map[string]*session
}
type client struct {
	deviceID string
	conn     *websocket.Conn
	writeMu  sync.Mutex
	lastSeq  uint64
}
type session struct {
	id, targetID, controllerID, controlledID string
	videoPolicy                              string
	confirmed                                bool
	controllerSeen, controlledSeen           time.Time
}
type registerRequest struct {
	PublicKey string `json:"publicKey"`
	Timestamp int64  `json:"timestamp"`
	Signature string `json:"signature"`
}
type pairingPayload struct {
	ID        string `json:"id"`
	Code      string `json:"code"`
	ExpiresAt int64  `json:"expiresAtEpochSeconds,omitempty"`
}
type connectPayload struct {
	TargetID    string `json:"targetId"`
	Code        string `json:"code"`
	VideoPolicy string `json:"videoPolicy"`
}
type confirmPayload struct {
	Accepted bool `json:"accepted"`
}

func New(cfg config.Config, db *pgxpool.Pool, rdb *redis.Client) *Server {
	return &Server{cfg: cfg, db: db, rdb: rdb, leases: lease.New(rdb, cfg.LeaseTTL), limits: ratelimit.New(rdb), upgrader: websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }, ReadBufferSize: 4096, WriteBufferSize: 4096}, clients: map[string]*client{}, pairings: map[string]string{}, sessions: map[string]*session{}}
}
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", s.health)
	mux.HandleFunc("POST /v1/devices/register", s.registerDevice)
	mux.HandleFunc("GET /v1/ws", s.webSocket)
	return securityHeaders(mux)
}
func (s *Server) health(w http.ResponseWriter, r *http.Request) {
	if s.db.Ping(r.Context()) != nil || s.rdb.Ping(r.Context()).Err() != nil {
		http.Error(w, "unavailable", 503)
		return
	}
	w.WriteHeader(204)
}
func (s *Server) registerDevice(w http.ResponseWriter, r *http.Request) {
	var req registerRequest
	if !decodeJSON(w, r, &req) || !fresh(req.Timestamp) {
		writeError(w, 400, "INVALID_REQUEST")
		return
	}
	der, err := base64.StdEncoding.DecodeString(req.PublicKey)
	if err != nil || len(der) > 2048 {
		writeError(w, 400, "INVALID_REQUEST")
		return
	}
	key, err := parsePublicKey(der)
	if err != nil || !verify(key, []byte(fmt.Sprintf("register\n%d\n%s", req.Timestamp, req.PublicKey)), req.Signature) {
		writeError(w, 401, "AUTH_FAILED")
		return
	}
	var id string
	err = s.db.QueryRow(r.Context(), `INSERT INTO devices(public_key_der) VALUES($1) ON CONFLICT(public_key_der) DO UPDATE SET last_seen_at=now() RETURNING id`, der).Scan(&id)
	if err != nil {
		writeError(w, 503, "SERVER_ERROR")
		return
	}
	writeJSON(w, 200, map[string]any{"deviceId": id, "serverTimeEpochMs": time.Now().UnixMilli()})
}
func (s *Server) webSocket(w http.ResponseWriter, r *http.Request) {
	deviceID, ts, sig := r.Header.Get("X-Device-Id"), r.Header.Get("X-Timestamp"), r.Header.Get("X-Signature")
	if deviceID == "" {
		http.Error(w, "unauthorized", 401)
		return
	}
	var timestamp int64
	if _, err := fmt.Sscan(ts, &timestamp); err != nil || !fresh(timestamp) {
		http.Error(w, "unauthorized", 401)
		return
	}
	var der []byte
	if s.db.QueryRow(r.Context(), `SELECT public_key_der FROM devices WHERE id=$1`, deviceID).Scan(&der) != nil {
		http.Error(w, "unauthorized", 401)
		return
	}
	key, err := parsePublicKey(der)
	if err != nil || !verify(key, []byte(fmt.Sprintf("ws\n%s\n%d", deviceID, timestamp)), sig) {
		http.Error(w, "unauthorized", 401)
		return
	}
	conn, err := s.upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	c := &client{deviceID: deviceID, conn: conn}
	s.mu.Lock()
	if old := s.clients[deviceID]; old != nil {
		_ = old.conn.Close()
	}
	s.clients[deviceID] = c
	s.mu.Unlock()
	defer s.removeClient(c)
	conn.SetReadLimit(protocol.MaxMessageBytes)
	_ = conn.SetReadDeadline(time.Now().Add(45 * time.Second))
	conn.SetPongHandler(func(string) error { return conn.SetReadDeadline(time.Now().Add(45 * time.Second)) })
	conn.SetPingHandler(func(data string) error {
		if err := conn.SetReadDeadline(time.Now().Add(45 * time.Second)); err != nil {
			return err
		}
		return conn.WriteControl(websocket.PongMessage, []byte(data), time.Now().Add(10*time.Second))
	})
	for {
		var env protocol.Envelope
		if conn.ReadJSON(&env) != nil {
			return
		}
		if reason := env.RejectionReason(c.lastSeq); reason != "" {
			log.Printf("signal_rejected type=%s code=STALE_OR_INVALID reason=%s sequence=%d previous=%d age_ms=%d", safeType(env.Type), reason, env.Sequence, c.lastSeq, time.Now().UnixMilli()-env.SentAt)
			_ = s.send(c, "ERROR", env.SessionID, map[string]any{"code": "STALE_OR_INVALID", "reason": reason, "requestType": safeType(env.Type), "requestSequence": env.Sequence})
			continue
		}
		c.lastSeq = env.Sequence
		_ = conn.SetReadDeadline(time.Now().Add(45 * time.Second))
		if err := s.handle(c, env, r); err != nil {
			log.Printf("signal_rejected type=%s code=%s sequence=%d", safeType(env.Type), err.Error(), env.Sequence)
			if limited, ok := err.(rateLimitError); ok {
				s.sendErrorWithRetry(c, env.SessionID, limited.seconds)
			} else {
				s.sendError(c, env.SessionID, err.Error())
			}
		}
	}
}
func (s *Server) handle(c *client, env protocol.Envelope, r *http.Request) error {
	switch env.Type {
	case "REGISTER_RANDOM", "REGISTER_FIXED":
		var p pairingPayload
		if json.Unmarshal(env.Payload, &p) != nil || !validID(p.ID) || len(p.Code) < 8 || len(p.Code) > 32 || strings.IndexFunc(p.Code, func(r rune) bool { return r == ' ' || r == '\t' || r == '\n' || r == '\r' }) >= 0 {
			return errors.New("INVALID_REQUEST")
		}
		hash, err := security.HashCode(p.Code, s.cfg.CredentialPepper)
		if err != nil {
			return errors.New("SERVER_ERROR")
		}
		if env.Type == "REGISTER_FIXED" {
			if len(p.Code) < 12 || len(p.Code) > 32 {
				return errors.New("INVALID_REQUEST")
			}
			_, _ = s.db.Exec(r.Context(), `DELETE FROM fixed_ids WHERE last_online_at < now() - interval '180 days'`)
			var owner string
			lookupErr := s.db.QueryRow(r.Context(), `SELECT device_id::text FROM fixed_ids WHERE normalized_id=$1`, p.ID).Scan(&owner)
			if lookupErr == nil && owner != c.deviceID {
				return errors.New("ID_UNAVAILABLE")
			}
			if lookupErr != nil && !errors.Is(lookupErr, pgx.ErrNoRows) {
				return errors.New("SERVER_ERROR")
			}
			tx, err := s.db.Begin(r.Context())
			if err != nil {
				return errors.New("SERVER_ERROR")
			}
			defer func() { _ = tx.Rollback(r.Context()) }()
			if _, err = tx.Exec(r.Context(), `DELETE FROM fixed_ids WHERE device_id=$1`, c.deviceID); err == nil {
				_, err = tx.Exec(r.Context(), `INSERT INTO fixed_ids(normalized_id,device_id,code_hash) VALUES($1,$2,$3)`, p.ID, c.deviceID, hash)
			}
			if err != nil || tx.Commit(r.Context()) != nil {
				return errors.New("ID_UNAVAILABLE")
			}
		} else {
			_, _ = s.db.Exec(r.Context(), `DELETE FROM fixed_ids WHERE device_id=$1`, c.deviceID)
			ttl := time.Until(time.Unix(p.ExpiresAt, 0))
			if ttl <= 0 || ttl > 6*time.Minute {
				ttl = 5 * time.Minute
			}
			ownerKey := "pair-owner:" + p.ID
			claimed, err := s.rdb.SetNX(r.Context(), ownerKey, c.deviceID, ttl).Result()
			if err != nil || !claimed {
				return errors.New("ID_UNAVAILABLE")
			}
			if s.rdb.HSet(r.Context(), "pair:"+p.ID, "device", c.deviceID, "hash", hash).Err() != nil {
				_ = s.rdb.Del(r.Context(), ownerKey).Err()
				return errors.New("SERVER_ERROR")
			}
			s.rdb.Expire(r.Context(), "pair:"+p.ID, ttl)
		}
		s.mu.Lock()
		for oldID, owner := range s.pairings {
			if owner == c.deviceID && oldID != p.ID {
				delete(s.pairings, oldID)
				_ = s.rdb.Del(r.Context(), "pair:"+oldID, "pair-owner:"+oldID).Err()
			}
		}
		s.pairings[p.ID] = c.deviceID
		s.mu.Unlock()
		return s.send(c, "REGISTERED", "", map[string]any{"id": p.ID})
	case "CONNECT":
		var p connectPayload
		if json.Unmarshal(env.Payload, &p) != nil || !validID(p.TargetID) {
			return errors.New("AUTH_FAILED")
		}
		start := time.Now()
		if p.VideoPolicy == "" {
			p.VideoPolicy = "tuned"
		}
		if p.VideoPolicy != "tuned" && p.VideoPolicy != "default" {
			return errors.New("INVALID_REQUEST")
		}
		validationFailure := func() error {
			retry := s.recordValidationFailure(r.Context(), r, c.deviceID, p.TargetID)
			uniformFailureDelay(start)
			if retry > 0 {
				return rateLimitError{retry}
			}
			return errors.New("AUTH_FAILED")
		}
		ok := s.verifyPairing(r.Context(), p.TargetID, p.Code)
		s.mu.RLock()
		controlledID := s.pairings[p.TargetID]
		controlled := s.clients[controlledID]
		s.mu.RUnlock()
		if !ok {
			return validationFailure()
		}
		if controlled == nil || controlledID == c.deviceID {
			return validationFailure()
		}
		sessionID := randomID(c.deviceID + p.TargetID + time.Now().String())
		acquired, err := s.leases.Acquire(r.Context(), p.TargetID, sessionID)
		if err != nil || !acquired {
			return validationFailure()
		}
		now := time.Now()
		sess := &session{id: sessionID, targetID: p.TargetID, controllerID: c.deviceID, controlledID: controlledID, controllerSeen: now, controlledSeen: now, videoPolicy: p.VideoPolicy}
		s.mu.Lock()
		s.sessions[sessionID] = sess
		s.mu.Unlock()
		go s.watchSession(sess)
		time.AfterFunc(s.cfg.ConfirmationTimeout, func() {
			s.mu.RLock()
			current := s.sessions[sessionID]
			expired := current != nil && !current.confirmed
			s.mu.RUnlock()
			if expired {
				_, peer := s.sessionPeer(sessionID, controlledID)
				if peer != nil {
					_ = s.send(peer, "REVOKED", sessionID, map[string]any{"reason": "CONFIRMATION_TIMEOUT"})
				}
				s.endSession(context.Background(), sess)
			}
		})
		return s.send(controlled, "INCOMING", sessionID, map[string]any{"controllerDeviceId": c.deviceID, "expiresAtEpochMs": time.Now().Add(s.cfg.ConfirmationTimeout).UnixMilli()})
	case "CONFIRM":
		var p confirmPayload
		if json.Unmarshal(env.Payload, &p) != nil {
			return errors.New("INVALID_REQUEST")
		}
		sess, peer := s.sessionPeer(env.SessionID, c.deviceID)
		if sess == nil || c.deviceID != sess.controlledID {
			return errors.New("SESSION_INVALID")
		}
		if !p.Accepted {
			s.endSession(r.Context(), sess)
			return s.send(peer, "REJECTED", sess.id, map[string]any{})
		}
		if ok, err := s.leases.Renew(r.Context(), sess.targetID, sess.id); err != nil || !ok {
			s.revokeSession(sess, "LEASE_LOST")
			return errors.New("LEASE_LOST")
		}
		s.mu.Lock()
		if current := s.sessions[sess.id]; current == sess {
			current.confirmed = true
			current.controllerSeen = time.Now()
			current.controlledSeen = time.Now()
		} else {
			s.mu.Unlock()
			return errors.New("SESSION_INVALID")
		}
		s.mu.Unlock()
		payload := s.turnCredentials(c.deviceID)
		payload["videoPolicy"] = sess.videoPolicy
		if err := s.send(peer, "CONFIRMED", sess.id, payload); err != nil {
			return err
		}
		return s.send(c, "CONFIRMED", sess.id, payload)
	case "OFFER", "ANSWER", "ICE", "GEOMETRY", "CONTROL_ACK", "AUDIO_STATE", "ICE_RESTART", "CONTROL_OFFER", "CONTROL_ANSWER", "CONTROL_ICE", "CONTROL_RESTART":
		_, peer := s.sessionPeer(env.SessionID, c.deviceID)
		if peer == nil {
			return errors.New("SESSION_INVALID")
		}
		err := peer.sendRaw(env)
		if env.Type == "OFFER" || env.Type == "ANSWER" || env.Type == "GEOMETRY" {
			log.Printf("signal_relay type=%s sequence=%d ok=%t", env.Type, env.Sequence, err == nil)
		}
		return err
	case "HEARTBEAT", "LEASE_RENEW":
		sess, _ := s.sessionPeer(env.SessionID, c.deviceID)
		if sess == nil {
			return errors.New("SESSION_INVALID")
		}
		ok, err := s.leases.Renew(r.Context(), sess.targetID, sess.id)
		if err != nil || !ok {
			s.revokeSession(sess, "LEASE_LOST")
			return errors.New("LEASE_LOST")
		}
		s.mu.Lock()
		if current := s.sessions[sess.id]; current == sess {
			if c.deviceID == sess.controllerID {
				current.controllerSeen = time.Now()
			} else {
				current.controlledSeen = time.Now()
			}
		}
		s.mu.Unlock()
		return s.send(c, "HEARTBEAT_ACK", sess.id, map[string]any{})
	case "REVOKE":
		sess, peer := s.sessionPeer(env.SessionID, c.deviceID)
		if sess == nil {
			return nil // Revocation is idempotent, including simultaneous local stops.
		}
		_ = s.send(peer, "REVOKED", sess.id, map[string]any{})
		s.endSession(r.Context(), sess)
		return nil
	default:
		return errors.New("INVALID_TYPE")
	}
}
func (s *Server) verifyPairing(ctx context.Context, id, code string) bool {
	if values, err := s.rdb.HMGet(ctx, "pair:"+id, "hash").Result(); err == nil && len(values) == 1 && values[0] != nil {
		return security.VerifyCode(fmt.Sprint(values[0]), code, s.cfg.CredentialPepper)
	}
	var hash string
	if s.db.QueryRow(ctx, `SELECT code_hash FROM fixed_ids WHERE normalized_id=$1`, id).Scan(&hash) != nil {
		return false
	}
	return security.VerifyCode(hash, code, s.cfg.CredentialPepper)
}
func (s *Server) sessionPeer(id, deviceID string) (*session, *client) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	sess := s.sessions[id]
	if sess == nil {
		return nil, nil
	}
	peerID := sess.controllerID
	if deviceID == sess.controllerID {
		peerID = sess.controlledID
	} else if deviceID != sess.controlledID {
		return nil, nil
	}
	return sess, s.clients[peerID]
}
func (s *Server) endSession(ctx context.Context, sess *session) {
	s.mu.Lock()
	if s.sessions[sess.id] != sess {
		s.mu.Unlock()
		return
	}
	delete(s.sessions, sess.id)
	s.mu.Unlock()
	_ = s.leases.Release(ctx, sess.targetID, sess.id)
}
func (s *Server) revokeSession(sess *session, reason string) {
	s.mu.RLock()
	current := s.sessions[sess.id]
	controller, controlled := s.clients[sess.controllerID], s.clients[sess.controlledID]
	s.mu.RUnlock()
	if current != sess {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	s.endSession(ctx, sess)
	_ = s.send(controller, "REVOKED", sess.id, map[string]any{"reason": reason})
	_ = s.send(controlled, "REVOKED", sess.id, map[string]any{"reason": reason})
}
func (s *Server) watchSession(sess *session) {
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	lastRenew := time.Now()
	for now := range ticker.C {
		s.mu.RLock()
		current := s.sessions[sess.id]
		confirmed := sess.confirmed
		controllerSeen, controlledSeen := sess.controllerSeen, sess.controlledSeen
		s.mu.RUnlock()
		if current != sess {
			return
		}
		if confirmed {
			if now.Sub(controllerSeen) > s.cfg.LeaseRenew+s.cfg.ReconnectGrace || now.Sub(controlledSeen) > s.cfg.LeaseRenew+s.cfg.ReconnectGrace {
				s.revokeSession(sess, "RECONNECT_TIMEOUT")
				return
			}
		} else if now.Sub(lastRenew) >= s.cfg.LeaseRenew {
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			ok, err := s.leases.Renew(ctx, sess.targetID, sess.id)
			cancel()
			if err != nil || !ok {
				s.revokeSession(sess, "LEASE_LOST")
				return
			}
			lastRenew = now
		}
	}
}
func (s *Server) removeClient(c *client) {
	s.mu.Lock()
	removed := false
	if s.clients[c.deviceID] == c {
		removed = true
		delete(s.clients, c.deviceID)
		for id, d := range s.pairings {
			if d == c.deviceID {
				delete(s.pairings, id)
			}
		}
	}
	s.mu.Unlock()
	_ = c.conn.Close()
	if !removed {
		return
	}
	time.AfterFunc(s.cfg.ReconnectGrace, func() {
		s.mu.RLock()
		if s.clients[c.deviceID] != nil {
			s.mu.RUnlock()
			return
		}
		var expired []*session
		for _, sess := range s.sessions {
			if sess.controllerID == c.deviceID || sess.controlledID == c.deviceID {
				expired = append(expired, sess)
			}
		}
		s.mu.RUnlock()
		for _, sess := range expired {
			s.revokeSession(sess, "RECONNECT_TIMEOUT")
		}
	})
}
func (s *Server) send(c *client, typ, sessionID string, payload any) error {
	if c == nil {
		return errors.New("PEER_OFFLINE")
	}
	b, _ := json.Marshal(payload)
	return c.sendRaw(protocol.Envelope{Version: 1, Type: typ, SessionID: sessionID, Sequence: uint64(time.Now().UnixNano()), SentAt: time.Now().UnixMilli(), Payload: b})
}
func (c *client) sendRaw(env protocol.Envelope) error {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	_ = c.conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
	return c.conn.WriteJSON(env)
}
func (s *Server) sendError(c *client, sessionID, code string) {
	_ = s.send(c, "ERROR", sessionID, map[string]any{"code": code})
}
func safeType(value string) string {
	switch value {
	case "REGISTER_RANDOM", "REGISTER_FIXED", "CONNECT", "CONFIRM", "OFFER", "ANSWER", "ICE", "GEOMETRY", "CONTROL_ACK", "AUDIO_STATE", "ICE_RESTART", "CONTROL_OFFER", "CONTROL_ANSWER", "CONTROL_ICE", "CONTROL_RESTART", "HEARTBEAT", "LEASE_RENEW", "REVOKE":
		return value
	default:
		return "UNKNOWN"
	}
}

type rateLimitError struct{ seconds int }

func (e rateLimitError) Error() string { return "RATE_LIMITED" }
func (s *Server) sendErrorWithRetry(c *client, sessionID string, seconds int) {
	_ = s.send(c, "ERROR", sessionID, map[string]any{"code": "RATE_LIMITED", "retryAfterSeconds": seconds})
}
func (s *Server) recordValidationFailure(ctx context.Context, r *http.Request, device, target string) int {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	keys := []struct {
		key string
		max int
	}{{"verify:ip:" + host, 20}, {"verify:device:" + device, 10}, {"verify:target:" + target, 10}}
	retry := 0
	for _, item := range keys {
		ok, left, err := s.limits.RecordFailure(ctx, item.key, item.max, 10*time.Minute)
		if err != nil {
			return 60
		}
		if !ok && int(left.Seconds()) > retry {
			retry = int(left.Seconds())
		}
	}
	return retry
}
func (s *Server) turnCredentials(deviceID string) map[string]any {
	if s.cfg.TurnRealm == "" || s.cfg.TurnSecret == "" {
		return map[string]any{"iceServers": []any{}}
	}
	username := fmt.Sprintf("%d:%s", time.Now().Add(time.Hour).Unix(), deviceID)
	mac := hmac.New(sha1.New, []byte(s.cfg.TurnSecret))
	_, _ = mac.Write([]byte(username))
	return map[string]any{"iceServers": []any{map[string]any{"urls": []string{"stun:" + s.cfg.TurnRealm + ":3480", "turn:" + s.cfg.TurnRealm + ":3480?transport=udp", "turn:" + s.cfg.TurnRealm + ":3480?transport=tcp", "turns:" + s.cfg.TurnRealm + ":5350?transport=tcp"}, "username": username, "credential": base64.StdEncoding.EncodeToString(mac.Sum(nil))}}}
}
func parsePublicKey(der []byte) (*ecdsa.PublicKey, error) {
	k, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		return nil, err
	}
	key, ok := k.(*ecdsa.PublicKey)
	if !ok {
		return nil, errors.New("not ecdsa")
	}
	return key, nil
}
func verify(key *ecdsa.PublicKey, payload []byte, encoded string) bool {
	sig, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return false
	}
	h := sha256.Sum256(payload)
	return ecdsa.VerifyASN1(key, h[:], sig)
}
func fresh(ts int64) bool { now := time.Now().UnixMilli(); return ts >= now-60_000 && ts <= now+10_000 }
func validID(v string) bool {
	if len(v) < 6 || len(v) > 16 || v != strings.ToLower(strings.TrimSpace(v)) {
		return false
	}
	for _, r := range v {
		if !(r >= 'a' && r <= 'z' || r >= '0' && r <= '9' || r == '_' || r == '-') {
			return false
		}
	}
	for _, reserved := range []string{"admin", "root", "support", "simplerc", "system", "official"} {
		if v == reserved || strings.Contains(v, reserved+"_") {
			return false
		}
	}
	return true
}
func randomID(seed string) string {
	h := sha256.Sum256([]byte(seed))
	return hex.EncodeToString(h[:16])
}
func uniformFailureDelay(start time.Time) {
	target := 350*time.Millisecond + time.Duration(time.Now().UnixNano()%100)*time.Millisecond
	if left := target - time.Since(start); left > 0 {
		time.Sleep(left)
	}
}
func decodeJSON(w http.ResponseWriter, r *http.Request, out any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, protocol.MaxMessageBytes)
	d := json.NewDecoder(r.Body)
	d.DisallowUnknownFields()
	return d.Decode(out) == nil
}
func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}
func writeError(w http.ResponseWriter, status int, code string) {
	writeJSON(w, status, map[string]string{"error": code})
}
func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Cache-Control", "no-store")
		next.ServeHTTP(w, r)
	})
}
