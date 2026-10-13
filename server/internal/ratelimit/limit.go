package ratelimit

import (
	"context"
	"github.com/redis/go-redis/v9"
	"time"
)

type Limiter struct{ rdb *redis.Client }

func New(r *redis.Client) *Limiter { return &Limiter{rdb: r} }
func (l *Limiter) Allow(ctx context.Context, key string, max int, window time.Duration) (bool, error) {
	n, e := l.rdb.Incr(ctx, "limit:"+key).Result()
	if e != nil {
		return false, e
	}
	if n == 1 {
		l.rdb.Expire(ctx, "limit:"+key, window)
	}
	return n <= int64(max), nil
}
