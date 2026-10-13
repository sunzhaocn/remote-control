package main

import (
	"context"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
	"log"
	"net/http"
	"simplerc/server/internal/api"
	"simplerc/server/internal/config"
	"time"
)

func main() {
	c, err := config.Load()
	if err != nil {
		log.Fatal(err)
	}
	db, err := pgxpool.New(context.Background(), c.DatabaseURL)
	if err != nil {
		log.Fatal(err)
	}
	defer db.Close()
	if _, err = db.Exec(context.Background(), `CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE TABLE IF NOT EXISTS devices (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), public_key_der bytea NOT NULL UNIQUE, created_at timestamptz NOT NULL DEFAULT now(), last_seen_at timestamptz NOT NULL DEFAULT now(), credential_generation bigint NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS fixed_ids (normalized_id varchar(32) PRIMARY KEY, device_id uuid NOT NULL UNIQUE REFERENCES devices(id) ON DELETE CASCADE, code_hash text NOT NULL, updated_at timestamptz NOT NULL DEFAULT now(), last_online_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX IF NOT EXISTS fixed_ids_reclaim_idx ON fixed_ids(last_online_at);`); err != nil {
		log.Fatal(err)
	}
	ro, err := redis.ParseURL(c.RedisURL)
	if err != nil {
		log.Fatal(err)
	}
	rdb := redis.NewClient(ro)
	defer rdb.Close()
	log.Printf("SimpleRC signaling listening on %s", c.ListenAddr)
	server := &http.Server{Addr: c.ListenAddr, Handler: api.New(c, db, rdb).Handler(), ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 75 * time.Second}
	log.Fatal(server.ListenAndServe())
}
