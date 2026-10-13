package protocol

import "time"
const MaxMessageBytes = 64*1024
type Envelope struct { Version int `json:"version"`; Type string `json:"type"`; SessionID string `json:"sessionId,omitempty"`; Sequence uint64 `json:"sequence"`; SentAt time.Time `json:"sentAt"`; Payload any `json:"payload"` }
func (e Envelope) Valid(last uint64) bool { return e.Version==1 && e.Sequence>last && time.Since(e.SentAt)<time.Minute && time.Until(e.SentAt)<10*time.Second }
