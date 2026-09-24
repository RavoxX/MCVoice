package server

import (
	"context"
	"errors"
	"net"
	"net/http"
	"time"
)

// Handler returns the HTTP handler (control WebSocket + operational endpoints).
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v1/control", s.HandleControl)
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"status":"ok"}`))
	})
	mux.HandleFunc("GET /ready", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if !s.ready.Load() || s.shutdown.Load() {
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = w.Write([]byte(`{"status":"not_ready"}`))
			return
		}
		_, _ = w.Write([]byte(`{"status":"ready"}`))
	})
	mux.HandleFunc("GET /metrics", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
		s.metrics.Write(w)
	})
	mux.HandleFunc("GET /", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		_, _ = w.Write([]byte("mcvoice-backend (go) " + Version + "\n"))
	})
	return mux
}

func (s *Server) background(ctx context.Context) {
	presence := time.NewTicker(500 * time.Millisecond)
	bans := time.NewTicker(30 * time.Second)
	jitter := time.NewTicker(5 * time.Second)
	defer presence.Stop()
	defer bans.Stop()
	defer jitter.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-presence.C:
			s.presenceTick()
		case <-bans.C:
			s.reloadBans()
		case <-jitter.C:
			s.updateJitterGauge()
		}
	}
}

// Run binds both listeners and serves until ctx is cancelled. If ctlListener
// is nil a TCP listener is bound from the configuration.
func (s *Server) Run(ctx context.Context, ctlListener net.Listener) error {
	for _, w := range s.cfg.Warnings {
		s.log.Warn(w, "category", "config")
	}
	s.reloadBans()
	if s.udp == nil {
		if err := s.ListenUDP(); err != nil {
			return err
		}
	}
	if ctlListener == nil {
		l, err := net.Listen("tcp", net.JoinHostPort(s.cfg.ControlBindAddress, itoa(s.cfg.ControlPort)))
		if err != nil {
			return err
		}
		ctlListener = l
	}
	srv := &http.Server{Handler: s.Handler(), ReadHeaderTimeout: 10 * time.Second, MaxHeaderBytes: 16 << 10}
	udpCtx, udpCancel := context.WithCancel(context.Background())
	udpDone := make(chan struct{})
	go func() { s.ServeUDP(udpCtx); close(udpDone) }()
	go s.background(udpCtx)

	errc := make(chan error, 1)
	go func() {
		var err error
		if s.cfg.TLSCertPath != "" {
			err = srv.ServeTLS(ctlListener, s.cfg.TLSCertPath, s.cfg.TLSKeyPath)
		} else {
			err = srv.Serve(ctlListener)
		}
		if !errors.Is(err, http.ErrServerClosed) {
			errc <- err
		}
		close(errc)
	}()
	s.ready.Store(true)
	s.log.Info("backend started", "category", "control", "implementation", "go", "version", Version,
		"control", ctlListener.Addr().String(), "voice_udp", s.UDPAddr().String(), "tls", s.cfg.TLSCertPath != "", "auth_mode", s.cfg.AuthMode)

	var runErr error
	select {
	case <-ctx.Done():
	case runErr = <-errc:
	}
	s.shutdown.Store(true)
	s.log.Info("shutting down", "category", "control")
	sctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	s.mu.RLock()
	for _, sess := range s.byConn {
		sess.close("shutdown")
	}
	s.mu.RUnlock()
	_ = srv.Shutdown(sctx)
	udpCancel()
	<-udpDone
	return runErr
}
