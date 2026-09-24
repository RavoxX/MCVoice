// Command mcvoice-backend is the Go implementation of the MCVoice control
// service and voice relay. It is protocol-compatible with backend/rust.
package main

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	"github.com/RavoxX/MCVoice/backend/go/internal/config"
	"github.com/RavoxX/MCVoice/backend/go/internal/server"
)

func main() {
	if len(os.Args) > 1 && (os.Args[1] == "--version" || os.Args[1] == "version") {
		fmt.Println("mcvoice-backend (go)", server.Version)
		return
	}
	cfg, err := config.Load()
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	log := newLogger(cfg.LogLevel, cfg.LogFormat)
	s := server.New(cfg, log)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := s.Run(ctx, nil); err != nil {
		log.Error("backend failed", "err", err)
		os.Exit(1)
	}
}

func newLogger(level, format string) *slog.Logger {
	var l slog.Level
	switch level {
	case "trace", "debug":
		l = slog.LevelDebug
	case "warn", "warning":
		l = slog.LevelWarn
	case "error":
		l = slog.LevelError
	default:
		l = slog.LevelInfo
	}
	opts := &slog.HandlerOptions{Level: l}
	if format == "text" {
		return slog.New(slog.NewTextHandler(os.Stdout, opts))
	}
	return slog.New(slog.NewJSONHandler(os.Stdout, opts))
}
