package api

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"simplerc/server/internal/protocol"
)

func (s *Server) sessionPeer(id, deviceID string) (*session, *client) {
	return s.findSessionPeer(id, deviceID, false)
}

func (s *Server) confirmedSessionPeer(id, deviceID string) (*session, *client) {
	return s.findSessionPeer(id, deviceID, true)
}

func (s *Server) confirmSession(sess *session) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if current := s.sessions[sess.id]; current != sess || current.confirmed {
		return false
	}
	sess.confirmed = true
	sess.controllerSeen = time.Now()
	sess.controlledSeen = time.Now()
	return true
}

// Lock both socket writers before notifying either side. A fast peer may send
// its OFFER immediately after CONFIRMED; that relay must not overtake the other
// peer's CONFIRMED. Stable lock ordering avoids cycles across shared clients.
func (s *Server) sendConfirmation(controlled, controller *client, id string, payload any) error {
	if controlled == nil || controller == nil || controlled == controller {
		return errors.New("PEER_OFFLINE")
	}
	first, second := controlled, controller
	if first.deviceID > second.deviceID {
		first, second = second, first
	}
	first.writeMu.Lock()
	defer first.writeMu.Unlock()
	second.writeMu.Lock()
	defer second.writeMu.Unlock()
	body, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	env := protocol.Envelope{Version: 1, Type: "CONFIRMED", SessionID: id, Sequence: uint64(time.Now().UnixNano()), SentAt: time.Now().UnixMilli(), Payload: body}
	if err = controlled.sendRawLocked(env); err != nil {
		return err
	}
	return controller.sendRawLocked(env)
}

func (s *Server) findSessionPeer(id, deviceID string, confirmedOnly bool) (*session, *client) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	sess := s.sessions[id]
	if sess == nil || (confirmedOnly && !sess.confirmed) {
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

// Checking pending state and removing the session must happen under the same
// lock as confirmation. An expired timer cannot revoke a confirmed session.
func (s *Server) detachSession(sess *session, pendingOnly bool) (bool, *client, *client) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.sessions[sess.id] != sess || (pendingOnly && sess.confirmed) {
		return false, nil, nil
	}
	delete(s.sessions, sess.id)
	return true, s.clients[sess.controllerID], s.clients[sess.controlledID]
}

func (s *Server) endSession(ctx context.Context, sess *session) {
	if removed, _, _ := s.detachSession(sess, false); removed {
		_ = s.leases.Release(ctx, sess.controlledID, sess.id)
	}
}

func (s *Server) revokeSession(sess *session, reason string) { s.revokeSessionIf(sess, reason, false) }

func (s *Server) revokeSessionIf(sess *session, reason string, pendingOnly bool) {
	removed, controller, controlled := s.detachSession(sess, pendingOnly)
	if !removed {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = s.leases.Release(ctx, sess.controlledID, sess.id)
	_ = s.send(controller, "REVOKED", sess.id, map[string]any{"reason": reason})
	_ = s.send(controlled, "REVOKED", sess.id, map[string]any{"reason": reason})
}
