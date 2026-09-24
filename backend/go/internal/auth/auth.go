// Package auth implements Mojang session verification, resume tokens (HS256 JWT)
// and companion-plugin scope attestations.
package auth

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/RavoxX/MCVoice/backend/go/internal/protocol"
)

var ErrAuth = errors.New("authentication failed")

// Identity is a verified Minecraft profile.
type Identity struct {
	UUID     string
	Username string
}

// MojangVerifier calls sessionserver hasJoined.
type MojangVerifier struct {
	URL    string
	Client *http.Client
}

func NewMojangVerifier(u string) *MojangVerifier {
	return &MojangVerifier{URL: u, Client: &http.Client{Timeout: 10 * time.Second}}
}

// Verify asks Mojang whether username joined with serverID (the challenge).
func (m *MojangVerifier) Verify(ctx context.Context, username, serverID string) (Identity, error) {
	q := url.Values{"username": {username}, "serverId": {serverID}}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, m.URL+"?"+q.Encode(), nil)
	if err != nil {
		return Identity{}, err
	}
	resp, err := m.Client.Do(req)
	if err != nil {
		return Identity{}, fmt.Errorf("session server unreachable: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNoContent {
		return Identity{}, ErrAuth
	}
	if resp.StatusCode != http.StatusOK {
		return Identity{}, fmt.Errorf("session server status %d", resp.StatusCode)
	}
	var body struct {
		ID   string `json:"id"`
		Name string `json:"name"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 64*1024)).Decode(&body); err != nil {
		return Identity{}, ErrAuth
	}
	u := DashUUID(body.ID)
	if !protocol.ValidUUID(u) || !strings.EqualFold(body.Name, username) {
		return Identity{}, ErrAuth
	}
	return Identity{UUID: u, Username: body.Name}, nil
}

// DashUUID converts Mojang's undashed hex UUID to canonical form.
func DashUUID(s string) string {
	s = strings.ToLower(s)
	if len(s) != 32 {
		return s
	}
	return s[0:8] + "-" + s[8:12] + "-" + s[12:16] + "-" + s[16:20] + "-" + s[20:32]
}

var b64 = base64.RawURLEncoding

type resumeClaims struct {
	Sub  string `json:"sub"`
	Name string `json:"name"`
	Iat  int64  `json:"iat"`
	Exp  int64  `json:"exp"`
	Typ  string `json:"typ"`
}

// IssueResume returns an HS256 JWT resume token.
func IssueResume(secret []byte, id Identity, ttl time.Duration, now time.Time) string {
	header := b64.EncodeToString([]byte(`{"alg":"HS256","typ":"JWT"}`))
	claims, _ := json.Marshal(resumeClaims{Sub: id.UUID, Name: id.Username, Iat: now.Unix(), Exp: now.Add(ttl).Unix(), Typ: "resume"})
	signing := header + "." + b64.EncodeToString(claims)
	return signing + "." + b64.EncodeToString(sign(secret, signing))
}

func sign(secret []byte, s string) []byte {
	m := hmac.New(sha256.New, secret)
	m.Write([]byte(s))
	return m.Sum(nil)
}

// VerifyResume validates a resume token and returns the identity.
func VerifyResume(secret []byte, token string, now time.Time) (Identity, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return Identity{}, ErrAuth
	}
	var hdr struct {
		Alg string `json:"alg"`
	}
	hb, err := b64.DecodeString(parts[0])
	if err != nil || json.Unmarshal(hb, &hdr) != nil || hdr.Alg != "HS256" {
		return Identity{}, ErrAuth
	}
	sig, err := b64.DecodeString(parts[2])
	if err != nil || !hmac.Equal(sig, sign(secret, parts[0]+"."+parts[1])) {
		return Identity{}, ErrAuth
	}
	cb, err := b64.DecodeString(parts[1])
	if err != nil {
		return Identity{}, ErrAuth
	}
	var c resumeClaims
	if json.Unmarshal(cb, &c) != nil || c.Typ != "resume" || !protocol.ValidUUID(c.Sub) || now.Unix() >= c.Exp {
		return Identity{}, ErrAuth
	}
	return Identity{UUID: c.Sub, Username: c.Name}, nil
}

// Attestation is a verified companion-plugin scope attestation.
type Attestation struct {
	Network   string
	Subserver string
}

// VerifyAttestation checks "<payload_b64url>.<mac_b64url>" (spec 6.3.1).
func VerifyAttestation(keys map[string][]byte, token, playerUUID string, now time.Time) (Attestation, bool) {
	p, m, ok := strings.Cut(token, ".")
	if !ok || len(keys) == 0 {
		return Attestation{}, false
	}
	payload, err := b64.DecodeString(p)
	if err != nil {
		return Attestation{}, false
	}
	mac, err := b64.DecodeString(m)
	if err != nil {
		return Attestation{}, false
	}
	var a struct {
		V         int    `json:"v"`
		Network   string `json:"network"`
		Subserver string `json:"subserver"`
		Player    string `json:"player"`
		Iat       int64  `json:"iat"`
	}
	if json.Unmarshal(payload, &a) != nil || a.V != 1 {
		return Attestation{}, false
	}
	key, ok := keys[a.Network]
	if !ok {
		return Attestation{}, false
	}
	h := hmac.New(sha256.New, key)
	h.Write(payload)
	if !hmac.Equal(mac, h.Sum(nil)) || a.Player != playerUUID {
		return Attestation{}, false
	}
	d := now.Unix() - a.Iat
	if d < -300 || d > 300 || len(a.Subserver) > 64 {
		return Attestation{}, false
	}
	return Attestation{Network: a.Network, Subserver: a.Subserver}, true
}
