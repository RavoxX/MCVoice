package conformance

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"strconv"
	"time"
)

// Target describes a running backend under test.
type Target struct {
	Name       string
	ControlURL string // ws://127.0.0.1:port/v1/control
	HTTPBase   string // http://127.0.0.1:port
	Mojang     *MojangMock
	// AttestationKey is the key configured for network "testnet" (base64).
	AttestationKey []byte
	// Rotation is the configured KEY_ROTATION_SECONDS.
	Rotation time.Duration
}

// FreePort returns an unused TCP port (and hopes UDP is free too).
func FreePort() (int, error) {
	for i := 0; i < 20; i++ {
		l, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			return 0, err
		}
		port := l.Addr().(*net.TCPAddr).Port
		l.Close()
		u, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port})
		if err != nil {
			continue
		}
		u.Close()
		return port, nil
	}
	return 0, errors.New("no free port")
}

// TestEnv returns the environment every conformance run uses, for a given auth mode.
func TestEnv(mode string, mojang *MojangMock, attestKey []byte) map[string]string {
	env := map[string]string{
		"AUTH_MODE":                  mode,
		"PUBLIC_HOSTNAME":            "127.0.0.1",
		"CONTROL_BIND_ADDRESS":       "127.0.0.1",
		"VOICE_BIND_ADDRESS":         "127.0.0.1",
		"KEY_ROTATION_SECONDS":       "3",
		"LOG_LEVEL":                  "warn",
		"JWT_SIGNING_SECRET":         "conformance-test-secret-0123456789abcdef",
		"SESSION_SECRET":             "conformance-session-secret-0123456789ab",
		"RATE_LIMIT_CONNECT_PER_MIN": "100000",
		"RATE_LIMIT_VOICE_PER_SEC":   "75",
		"RATE_LIMIT_VOICE_BURST":     "150",
		"SCOPE_ATTESTATION_KEYS":     "testnet:" + base64.StdEncoding.EncodeToString(attestKey),
	}
	if mojang != nil {
		env["MOJANG_SESSION_URL"] = mojang.HasJoinedURL()
	}
	return env
}

func NewAttestationKey() []byte {
	k := make([]byte, 32)
	_, _ = rand.Read(k)
	return k
}

// Launch starts an external backend binary with the conformance environment.
func Launch(name string, argv []string, mode string) (*Target, func(), error) {
	ctlPort, err := FreePort()
	if err != nil {
		return nil, nil, err
	}
	udpPort, err := FreePort()
	if err != nil {
		return nil, nil, err
	}
	var mock *MojangMock
	if mode == "mojang" {
		mock = NewMojangMock()
	}
	key := NewAttestationKey()
	env := TestEnv(mode, mock, key)
	env["CONTROL_PORT"] = strconv.Itoa(ctlPort)
	env["VOICE_UDP_PORT"] = strconv.Itoa(udpPort)
	cmd := exec.Command(argv[0], argv[1:]...)
	cmd.Env = os.Environ()
	for k, v := range env {
		cmd.Env = append(cmd.Env, k+"="+v)
	}
	cmd.Stdout, cmd.Stderr = os.Stderr, os.Stderr
	if err := cmd.Start(); err != nil {
		return nil, nil, err
	}
	stop := func() {
		_ = cmd.Process.Signal(os.Interrupt)
		done := make(chan struct{})
		go func() { _ = cmd.Wait(); close(done) }()
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			_ = cmd.Process.Kill()
		}
		if mock != nil {
			mock.Close()
		}
	}
	t := &Target{Name: name, ControlURL: fmt.Sprintf("ws://127.0.0.1:%d/v1/control", ctlPort),
		HTTPBase: fmt.Sprintf("http://127.0.0.1:%d", ctlPort), Mojang: mock, AttestationKey: key, Rotation: 3 * time.Second}
	if err := WaitReady(t.HTTPBase, 20*time.Second); err != nil {
		stop()
		return nil, nil, err
	}
	return t, stop, nil
}

// WaitReady polls /ready.
func WaitReady(base string, timeout time.Duration) error {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	for {
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, base+"/ready", nil)
		resp, err := http.DefaultClient.Do(req)
		if err == nil {
			resp.Body.Close()
			if resp.StatusCode == 200 {
				return nil
			}
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("backend at %s not ready: %v", base, err)
		case <-time.After(100 * time.Millisecond):
		}
	}
}
