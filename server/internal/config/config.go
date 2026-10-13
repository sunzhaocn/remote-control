package config

import (
	"fmt"
	"os"
	"time"
)

type Config struct {
	ListenAddr, DatabaseURL, RedisURL, CredentialPepper         string
	LeaseTTL, LeaseRenew, ConfirmationTimeout, HandshakeTimeout time.Duration
}

func Load() (Config, error) {
	c := Config{ListenAddr: env("LISTEN_ADDR", ":8080"), DatabaseURL: os.Getenv("DATABASE_URL"), RedisURL: os.Getenv("REDIS_URL"), CredentialPepper: os.Getenv("CREDENTIAL_PEPPER"), LeaseTTL: 30 * time.Second, LeaseRenew: 10 * time.Second, ConfirmationTimeout: 30 * time.Second, HandshakeTimeout: 20 * time.Second}
	if c.DatabaseURL == "" || c.RedisURL == "" || len(c.CredentialPepper) < 32 {
		return Config{}, fmt.Errorf("DATABASE_URL, REDIS_URL and a 32+ byte CREDENTIAL_PEPPER are required")
	}
	return c, nil
}
func env(k, fallback string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return fallback
}
