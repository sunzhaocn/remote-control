package lease

import (
	"context"
	"github.com/redis/go-redis/v9"
	"time"
)

var releaseScript = redis.NewScript(`if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end`)
var renewScript = redis.NewScript(`if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('PEXPIRE', KEYS[1], ARGV[2]) else return 0 end`)
type Store struct { rdb *redis.Client; ttl time.Duration }
func New(rdb *redis.Client, ttl time.Duration) *Store { return &Store{rdb:rdb, ttl:ttl} }
func (s *Store) Acquire(ctx context.Context, targetID, sessionID string) (bool,error) { return s.rdb.SetNX(ctx,"lease:"+targetID,sessionID,s.ttl).Result() }
func (s *Store) Renew(ctx context.Context,targetID,sessionID string)(bool,error){ n,e:=renewScript.Run(ctx,s.rdb,[]string{"lease:"+targetID},sessionID,s.ttl.Milliseconds()).Int(); return n==1,e }
func (s *Store) Release(ctx context.Context,targetID,sessionID string) error { _,e:=releaseScript.Run(ctx,s.rdb,[]string{"lease:"+targetID},sessionID).Result(); return e }
