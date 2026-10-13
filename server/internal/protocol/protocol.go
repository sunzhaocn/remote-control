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
	return e.RejectionReason(last) == ""
}

func (e Envelope) RejectionReason(last uint64) string {
	now := time.Now().UnixMilli()
	if e.Version != 1 {
		return "VERSION"
	}
	if e.Sequence <= last {
		return "SEQUENCE"
	}
	if e.SentAt < now-60_000 || e.SentAt > now+10_000 {
		return "TIME"
	}
	return ""
}
