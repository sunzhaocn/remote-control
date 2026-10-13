package security

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"fmt"
	"golang.org/x/crypto/argon2"
	"strings"
)

func HashCode(code, pepper string) (string, error) {
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	h := argon2.IDKey([]byte(code+pepper), salt, 3, 64*1024, 2, 32)
	return fmt.Sprintf("v1$%s$%s", base64.RawStdEncoding.EncodeToString(salt), base64.RawStdEncoding.EncodeToString(h)), nil
}
func VerifyCode(encoded, code, pepper string) bool {
	parts := strings.Split(encoded, "$")
	if len(parts) != 3 || parts[0] != "v1" {
		return false
	}
	sv, hv := parts[1], parts[2]
	salt, e1 := base64.RawStdEncoding.DecodeString(sv)
	want, e2 := base64.RawStdEncoding.DecodeString(hv)
	if e1 != nil || e2 != nil {
		return false
	}
	got := argon2.IDKey([]byte(code+pepper), salt, 3, 64*1024, 2, 32)
	return len(got) == len(want) && subtle.ConstantTimeCompare(got, want) == 1
}
