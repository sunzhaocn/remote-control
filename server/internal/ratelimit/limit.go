package ratelimit

import (
	"context"
	"time"

	"github.com/redis/go-redis/v9"
)

// Increment and expiry must be one operation: a disconnected client must not
// leave a permanent counter behind. Repair counters written by older servers.
var recordScript = redis.NewScript(`
local n = redis.call('INCR', KEYS[1])
local ttl = redis.call('PTTL', KEYS[1])
if ttl < 0 then redis.call('PEXPIRE', KEYS[1], ARGV[1]); ttl = tonumber(ARGV[1]) end
return {n, ttl}`)
var remainingScript = redis.NewScript(`
local n = tonumber(redis.call('GET', KEYS[1]) or '0')
if n < tonumber(ARGV[1]) then return 0 end
local ttl = redis.call('PTTL', KEYS[1])
if ttl < 0 then redis.call('PEXPIRE', KEYS[1], ARGV[2]); ttl = tonumber(ARGV[2]) end
return ttl`)

type Limiter struct{ rdb *redis.Client }

func New(r *redis.Client) *Limiter { return &Limiter{rdb: r} }
func (l *Limiter) RecordFailure(ctx context.Context, key string, max int, window time.Duration) (bool, time.Duration, error) {
	values, err := recordScript.Run(ctx, l.rdb, []string{"limit:" + key}, window.Milliseconds()).Int64Slice()
	if err != nil {
		return false, 0, err
	}
	return values[0] < int64(max), time.Duration(values[1]) * time.Millisecond, nil
}

// Remaining is checked before password verification, including correct guesses.
// Requests during a ban do not extend it or perform the expensive hash.
func (l *Limiter) Remaining(ctx context.Context, key string, max int, window time.Duration) (time.Duration, error) {
	ms, err := remainingScript.Run(ctx, l.rdb, []string{"limit:" + key}, max, window.Milliseconds()).Int64()
	return time.Duration(ms) * time.Millisecond, err
}
