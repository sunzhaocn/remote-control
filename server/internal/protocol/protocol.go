package protocol

import (
	"encoding/json"
	"time"
)

const MaxMessageBytes = 64 * 1024

type Envelope struct {
	Version   int             `json:"version"`
	Type      string          `json:"type"`
	SessionID string          `json:"sessionId,omitempty"`
	Sequence  uint64          `json:"sequence"`
	SentAt    int64           `json:"sentAtEpochMs"`
	Payload   json.RawMessage `json:"payload"`
}

func (e Envelope) Valid(last uint64) bool {
	now := time.Now().UnixMilli()
	return e.Version == 1 && e.Sequence > last && e.SentAt >= now-60_000 && e.SentAt <= now+10_000
}
