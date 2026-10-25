package api

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
	"simplerc/server/internal/security"
)

// The published hash and owner are one immutable record. CONNECT must never
// verify a hash from one store and then route using an unrelated map entry.
type publishedPairing struct {
	deviceID  string
	hash      string
	expiresAt time.Time // zero for fixed credentials
}

type pairingRepository interface {
	Publish(context.Context, string, string, string, time.Duration, bool) error
	RemoveRandom(context.Context, string, string) error
}

type storedPairings struct {
	db  *pgxpool.Pool
	rdb *redis.Client
}

var claimRandomScript = redis.NewScript(`
local owner = redis.call('GET', KEYS[1])
local storedOwner = redis.call('HGET', KEYS[2], 'device')
if (owner and owner ~= ARGV[1]) or (storedOwner and storedOwner ~= ARGV[1]) then return 0 end
redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
redis.call('HSET', KEYS[2], 'device', ARGV[1], 'hash', ARGV[2])
redis.call('PEXPIRE', KEYS[2], ARGV[3])
return 1`)

var removeRandomScript = redis.NewScript(`
if redis.call('GET', KEYS[1]) == ARGV[1] then redis.call('DEL', KEYS[1]) end
if redis.call('HGET', KEYS[2], 'device') == ARGV[1] then redis.call('DEL', KEYS[2]) end
return 1`)

// The application has one signaling process (clients/sessions live in memory).
// publishMu serializes publications in that process. The SQL advisory lock also
// prevents a concurrent publisher from claiming the same fixed/random ID during
// the Redis operation. SQL and Redis are not a distributed transaction: failure
// never publishes a new active record, and temporary Redis claims expire.
func (p *storedPairings) Publish(ctx context.Context, id, owner, hash string, ttl time.Duration, fixed bool) error {
	tx, err := p.db.Begin(ctx)
	if err != nil {
		return errors.New("SERVER_ERROR")
	}
	defer func() { _ = tx.Rollback(context.Background()) }()
	if _, err = tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1, 0))`, id); err != nil {
		return errors.New("SERVER_ERROR")
	}
	if _, err = tx.Exec(ctx, `DELETE FROM fixed_ids WHERE normalized_id=$1 AND last_online_at < now() - interval '180 days'`, id); err != nil {
		return errors.New("SERVER_ERROR")
	}
	var existing string
	err = tx.QueryRow(ctx, `SELECT device_id::text FROM fixed_ids WHERE normalized_id=$1`, id).Scan(&existing)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return errors.New("SERVER_ERROR")
	}
	if err == nil && existing != owner {
		return errors.New("ID_UNAVAILABLE")
	}

	if fixed {
		values, err := p.rdb.MGet(ctx, "pair-owner:"+id).Result()
		if err != nil {
			return errors.New("SERVER_ERROR")
		}
		if len(values) == 1 && values[0] != nil && values[0] != owner {
			return errors.New("ID_UNAVAILABLE")
		}
		storedOwner, err := p.rdb.HGet(ctx, "pair:"+id, "device").Result()
		if err != nil && !errors.Is(err, redis.Nil) {
			return errors.New("SERVER_ERROR")
		}
		if storedOwner != "" && storedOwner != owner {
			return errors.New("ID_UNAVAILABLE")
		}
	} else {
		claimed, err := claimRandomScript.Run(ctx, p.rdb, []string{"pair-owner:" + id, "pair:" + id}, owner, hash, ttl.Milliseconds()).Int()
		if err != nil {
			return errors.New("SERVER_ERROR")
		}
		if claimed != 1 {
			return errors.New("ID_UNAVAILABLE")
		}
	}
	if _, err = tx.Exec(ctx, `DELETE FROM fixed_ids WHERE device_id=$1`, owner); err == nil && fixed {
		_, err = tx.Exec(ctx, `INSERT INTO fixed_ids(normalized_id,device_id,code_hash) VALUES($1,$2,$3)`, id, owner, hash)
	}
	if err != nil {
		return errors.New("SERVER_ERROR")
	}
	if err = tx.Commit(ctx); err != nil {
		return errors.New("SERVER_ERROR")
	}
	if fixed {
		// An old random hash owned by this same device must not shadow its new
		// fixed credential. A different device's record is never removed.
		_ = p.RemoveRandom(ctx, id, owner)
	}
	return nil
}

func (p *storedPairings) RemoveRandom(ctx context.Context, id, owner string) error {
	return removeRandomScript.Run(ctx, p.rdb, []string{"pair-owner:" + id, "pair:" + id}, owner).Err()
}

func (s *Server) publishPairing(ctx context.Context, c *client, p pairingPayload, fixed bool) error {
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	s.publishMu.Lock()
	defer s.publishMu.Unlock()
	if ctx.Err() != nil {
		return errors.New("SERVER_ERROR")
	}
	s.mu.RLock()
	currentClient := s.clients[c.deviceID] == c
	existing := s.pairings[p.ID]
	s.mu.RUnlock()
	if !currentClient {
		return errors.New("SESSION_INVALID")
	}
	if existing != nil && existing.deviceID != c.deviceID && (existing.expiresAt.IsZero() || time.Now().Before(existing.expiresAt)) {
		return errors.New("ID_UNAVAILABLE")
	}
	hash, err := security.HashCode(p.Code, s.cfg.CredentialPepper)
	if err != nil {
		return errors.New("SERVER_ERROR")
	}
	ttl := time.Duration(0)
	expiresAt := time.Time{}
	if !fixed {
		ttl = time.Until(time.Unix(p.ExpiresAt, 0))
		if ttl <= 0 || ttl > 6*time.Minute {
			ttl = 5 * time.Minute
		}
		expiresAt = time.Now().Add(ttl)
	}
	if err := s.pairingStore.Publish(ctx, p.ID, c.deviceID, hash, ttl, fixed); err != nil {
		return err
	}
	var oldIDs []string
	s.mu.Lock()
	if s.clients[c.deviceID] != c {
		s.mu.Unlock()
		return errors.New("SESSION_INVALID")
	}
	for id, record := range s.pairings {
		if record.deviceID == c.deviceID && id != p.ID {
			delete(s.pairings, id)
			oldIDs = append(oldIDs, id)
		}
	}
	s.pairings[p.ID] = &publishedPairing{deviceID: c.deviceID, hash: hash, expiresAt: expiresAt}
	s.mu.Unlock()
	// Redis I/O is deliberately outside the session/client mutex.
	for _, id := range oldIDs {
		_ = s.pairingStore.RemoveRandom(ctx, id, c.deviceID)
	}
	return nil
}

func (s *Server) verifyPairing(id, code string) (*publishedPairing, *client) {
	s.mu.RLock()
	record := s.pairings[id]
	s.mu.RUnlock()
	if record == nil || (!record.expiresAt.IsZero() && !time.Now().Before(record.expiresAt)) || !security.VerifyCode(record.hash, code, s.cfg.CredentialPepper) {
		return nil, nil
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.pairings[id] != record || (!record.expiresAt.IsZero() && !time.Now().Before(record.expiresAt)) {
		return nil, nil
	}
	return record, s.clients[record.deviceID]
}
