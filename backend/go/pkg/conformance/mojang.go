package conformance

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
)

// MojangMock imitates sessionserver.mojang.com: POST /join records a join,
// GET /hasJoined answers it. Tokens are never checked for real.
type MojangMock struct {
	srv    *httptest.Server
	mu     sync.Mutex
	joined map[string]profile // serverId -> profile
	Tokens map[string]profile // accessToken -> profile
}

type profile struct{ ID, Name string }

func NewMojangMock() *MojangMock {
	m := &MojangMock{joined: map[string]profile{}, Tokens: map[string]profile{}}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /join", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			AccessToken     string `json:"accessToken"`
			SelectedProfile string `json:"selectedProfile"`
			ServerID        string `json:"serverId"`
		}
		if json.NewDecoder(r.Body).Decode(&req) != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		m.mu.Lock()
		defer m.mu.Unlock()
		p, ok := m.Tokens[req.AccessToken]
		if !ok || p.ID != req.SelectedProfile {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		m.joined[req.ServerID] = p
		w.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("GET /hasJoined", func(w http.ResponseWriter, r *http.Request) {
		m.mu.Lock()
		p, ok := m.joined[r.URL.Query().Get("serverId")]
		if ok {
			delete(m.joined, r.URL.Query().Get("serverId"))
		}
		m.mu.Unlock()
		if !ok || !strings.EqualFold(p.Name, r.URL.Query().Get("username")) {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]string{"id": p.ID, "name": p.Name})
	})
	m.srv = httptest.NewServer(mux)
	return m
}

// AddAccount registers an access token for a profile (undashed UUID).
func (m *MojangMock) AddAccount(token, undashedUUID, name string) {
	m.mu.Lock()
	m.Tokens[token] = profile{ID: undashedUUID, Name: name}
	m.mu.Unlock()
}

// HasJoinedURL is the value for MOJANG_SESSION_URL.
func (m *MojangMock) HasJoinedURL() string { return m.srv.URL + "/hasJoined" }

// Join performs the client-side join like a Minecraft client would.
func (m *MojangMock) Join(token, undashedUUID, serverID string) error {
	body, _ := json.Marshal(map[string]string{"accessToken": token, "selectedProfile": undashedUUID, "serverId": serverID})
	resp, err := http.Post(m.srv.URL+"/join", "application/json", strings.NewReader(string(body)))
	if err != nil {
		return err
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		return errStatus(resp.StatusCode)
	}
	return nil
}

func (m *MojangMock) Close() { m.srv.Close() }

type errStatus int

func (e errStatus) Error() string { return "status " + http.StatusText(int(e)) }
