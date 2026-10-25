package protocol

import (
	"testing"
	"time"
)

func TestEnvelopeRejectsReplayAndUnsupportedVersion(t *testing.T) {
	e := Envelope{Version: 1, Sequence: 3, SentAt: time.Now().UnixMilli()}
	if !e.Valid(2) {
		t.Fatal("fresh message rejected")
	}
	if e.RejectionReason(3) != "SEQUENCE" {
		t.Fatal("replay accepted")
	}
	e.Version = 2
	if e.RejectionReason(2) != "VERSION" {
		t.Fatal("unknown version accepted")
	}
}

func TestEnvelopeRejectsExpiredAndFutureMessages(t *testing.T) {
	now := time.Now().UnixMilli()
	for _, stamp := range []int64{now - 61000, now + 11000} {
		if reason := (Envelope{Version: 1, Sequence: 1, SentAt: stamp}).RejectionReason(0); reason != "TIME" {
			t.Fatalf("timestamp %d accepted", stamp)
		}
	}
	for _, stamp := range []int64{now - 59000, now + 9000} {
		if !(Envelope{Version: 1, Sequence: 1, SentAt: stamp}).Valid(0) {
			t.Fatalf("timestamp %d rejected", stamp)
		}
	}
}
