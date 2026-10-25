package api

import (
	"context"
	"net"
	"net/http"
	"time"
)

const validationWindow = 10 * time.Minute

type validationKey struct {
	key string
	max int
}

func (s *Server) clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	peer := net.ParseIP(host)
	for _, subnet := range s.cfg.TrustedProxyCIDRs {
		if peer != nil && subnet.Contains(peer) {
			if forwarded := net.ParseIP(r.Header.Get("X-Real-IP")); forwarded != nil {
				return forwarded.String()
			}
		}
	}
	return host
}

func (s *Server) validationKeys(r *http.Request, device, target string) []validationKey {
	return []validationKey{{"verify:ip:" + s.clientIP(r), 20}, {"verify:device:" + device, 10}, {"verify:target:" + target, 10}}
}

func retrySeconds(left time.Duration) int { return int((left + time.Second - 1) / time.Second) }

func (s *Server) validationRetry(ctx context.Context, r *http.Request, device, target string) int {
	retry := 0
	for _, item := range s.validationKeys(r, device, target) {
		left, err := s.limits.Remaining(ctx, item.key, item.max, validationWindow)
		if err != nil {
			return 60
		} // The validation gate fails closed.
		if seconds := retrySeconds(left); seconds > retry {
			retry = seconds
		}
	}
	return retry
}

func (s *Server) recordValidationFailure(ctx context.Context, r *http.Request, device, target string) int {
	retry := 0
	for _, item := range s.validationKeys(r, device, target) {
		ok, left, err := s.limits.RecordFailure(ctx, item.key, item.max, validationWindow)
		if err != nil {
			return 60
		}
		if seconds := retrySeconds(left); !ok && seconds > retry {
			retry = seconds
		}
	}
	return retry
}
