package server

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"net"
	"sort"
	"testing"
	"time"

	"github.com/RavoxX/MCVoice/backend/go/internal/config"
	"github.com/RavoxX/MCVoice/backend/go/pkg/conformance"
)

func startInProcess(t *testing.T, mode string) *conformance.Target {
	t.Helper()
	var mock *conformance.MojangMock
	if mode == "mojang" {
		mock = conformance.NewMojangMock()
		t.Cleanup(mock.Close)
	}
	key := conformance.NewAttestationKey()
	for k, v := range conformance.TestEnv(mode, mock, key) {
		t.Setenv(k, v)
	}
	t.Setenv("CONTROL_PORT", "0")
	t.Setenv("VOICE_UDP_PORT", "0")
	cfg, err := config.Load()
	if err != nil {
		t.Fatal(err)
	}
	s := New(cfg, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err := s.ListenUDP(); err != nil {
		t.Fatal(err)
	}
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- s.Run(ctx, l) }()
	t.Cleanup(func() {
		cancel()
		select {
		case <-done:
		case <-time.After(10 * time.Second):
			t.Error("server did not shut down")
		}
	})
	base := fmt.Sprintf("http://%s", l.Addr())
	if err := conformance.WaitReady(base, 5*time.Second); err != nil {
		t.Fatal(err)
	}
	return &conformance.Target{Name: "go-inprocess", ControlURL: fmt.Sprintf("ws://%s/v1/control", l.Addr()),
		HTTPBase: base, Mojang: mock, AttestationKey: key, Rotation: 3 * time.Second}
}

func runMode(t *testing.T, mode string) {
	target := startInProcess(t, mode)
	res := conformance.Run(target, mode, nil)
	if len(res) == 0 {
		t.Fatal("no scenarios")
	}
	names := make([]string, 0, len(res))
	for n := range res {
		names = append(names, n)
	}
	sort.Strings(names)
	for _, n := range names {
		err := res[n]
		t.Run(n, func(t *testing.T) {
			if err != nil {
				t.Fatal(err)
			}
		})
	}
}

func TestConformanceOffline(t *testing.T) { runMode(t, "offline") }
func TestConformanceMojang(t *testing.T)  { runMode(t, "mojang") }
