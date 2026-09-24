// Package config loads backend configuration from environment variables.
// Variable names are shared with the Rust backend (see backend/*/.env.example).
package config

import (
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"os"
	"strconv"
	"strings"
)

type Config struct {
	ControlBindAddress string
	ControlPort        int
	VoiceBindAddress   string
	VoiceUDPPort       int
	PublicHostname     string
	PublicVoicePort    int

	TLSCertPath string
	TLSKeyPath  string

	AuthMode         string // mojang | offline
	MojangSessionURL string
	JWTSigningSecret []byte
	SessionSecret    []byte

	LogLevel     string
	LogFormat    string
	LogPositions bool

	NormalRange       float64
	WhisperRange      float64
	MaxRange          float64
	DistanceSlack     float64
	RequireMutual     bool
	KeyRotationSecs   int
	ResumeTokenTTL    int
	MaxSessions       int
	SessionTimeoutSec int

	RateVoicePerSec     float64
	RateVoiceBurst      float64
	RateControlPerSec   float64
	RateControlBurst    float64
	RateConnectPerMin   int
	RateAuthFailPerMin  int
	TrustProxyHeaders   bool
	UDPWorkers          int
	BansFile            string
	AttestationKeys     map[string][]byte
	DatabaseURL         string
	RedisURL            string
	Warnings            []string
	GeneratedJWTSecret  bool
	GeneratedSessSecret bool
}

func env(k, def string) string {
	if v, ok := os.LookupEnv(k); ok && v != "" {
		return v
	}
	return def
}

func envInt(k string, def int, errs *[]string) int {
	v := env(k, "")
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		*errs = append(*errs, fmt.Sprintf("%s: not an integer", k))
		return def
	}
	return n
}

func envFloat(k string, def float64, errs *[]string) float64 {
	v := env(k, "")
	if v == "" {
		return def
	}
	f, err := strconv.ParseFloat(v, 64)
	if err != nil {
		*errs = append(*errs, fmt.Sprintf("%s: not a number", k))
		return def
	}
	return f
}

func envBool(k string, def bool, errs *[]string) bool {
	v := strings.ToLower(env(k, ""))
	switch v {
	case "":
		return def
	case "1", "true", "yes", "on":
		return true
	case "0", "false", "no", "off":
		return false
	}
	*errs = append(*errs, fmt.Sprintf("%s: not a boolean", k))
	return def
}

func randomSecret() []byte {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return b
}

// Load reads the configuration. It returns an error for invalid or insecure settings.
func Load() (*Config, error) {
	var errs []string
	c := &Config{
		ControlBindAddress: env("CONTROL_BIND_ADDRESS", "0.0.0.0"),
		ControlPort:        envInt("CONTROL_PORT", 8080, &errs),
		VoiceBindAddress:   env("VOICE_BIND_ADDRESS", "0.0.0.0"),
		VoiceUDPPort:       envInt("VOICE_UDP_PORT", 24455, &errs),
		PublicHostname:     env("PUBLIC_HOSTNAME", ""),
		TLSCertPath:        env("TLS_CERT_PATH", ""),
		TLSKeyPath:         env("TLS_KEY_PATH", ""),
		AuthMode:           strings.ToLower(env("AUTH_MODE", "mojang")),
		MojangSessionURL:   env("MOJANG_SESSION_URL", "https://sessionserver.mojang.com/session/minecraft/hasJoined"),
		LogLevel:           strings.ToLower(env("LOG_LEVEL", "info")),
		LogFormat:          strings.ToLower(env("LOG_FORMAT", "json")),
		LogPositions:       envBool("LOG_POSITIONS", false, &errs),
		NormalRange:        envFloat("NORMAL_RANGE", 48, &errs),
		WhisperRange:       envFloat("WHISPER_RANGE", 8, &errs),
		MaxRange:           envFloat("MAX_RANGE", 96, &errs),
		DistanceSlack:      envFloat("ROUTING_DISTANCE_SLACK", 4, &errs),
		RequireMutual:      envBool("ROUTING_REQUIRE_MUTUAL_VISIBILITY", true, &errs),
		KeyRotationSecs:    envInt("KEY_ROTATION_SECONDS", 600, &errs),
		ResumeTokenTTL:     envInt("RESUME_TOKEN_TTL_SECONDS", 3600, &errs),
		MaxSessions:        envInt("MAX_SESSIONS", 10000, &errs),
		SessionTimeoutSec:  envInt("SESSION_TIMEOUT_SECONDS", 20, &errs),
		RateVoicePerSec:    envFloat("RATE_LIMIT_VOICE_PER_SEC", 75, &errs),
		RateVoiceBurst:     envFloat("RATE_LIMIT_VOICE_BURST", 150, &errs),
		RateControlPerSec:  envFloat("RATE_LIMIT_CONTROL_PER_SEC", 40, &errs),
		RateControlBurst:   envFloat("RATE_LIMIT_CONTROL_BURST", 80, &errs),
		RateConnectPerMin:  envInt("RATE_LIMIT_CONNECT_PER_MIN", 30, &errs),
		RateAuthFailPerMin: envInt("RATE_LIMIT_AUTH_FAIL_PER_MIN", 10, &errs),
		TrustProxyHeaders:  envBool("TRUST_PROXY_HEADERS", false, &errs),
		UDPWorkers:         envInt("UDP_WORKERS", 0, &errs),
		BansFile:           env("BANS_FILE", ""),
		DatabaseURL:        env("DATABASE_URL", ""),
		RedisURL:           env("REDIS_URL", ""),
		AttestationKeys:    map[string][]byte{},
	}
	c.PublicVoicePort = envInt("PUBLIC_VOICE_PORT", c.VoiceUDPPort, &errs)

	switch c.AuthMode {
	case "mojang":
	case "offline":
		c.Warnings = append(c.Warnings, "AUTH_MODE=offline: player identities are NOT verified; development use only")
	default:
		errs = append(errs, "AUTH_MODE must be mojang or offline")
	}
	if s := env("JWT_SIGNING_SECRET", ""); s != "" {
		if len(s) < 32 {
			errs = append(errs, "JWT_SIGNING_SECRET must be at least 32 characters")
		}
		c.JWTSigningSecret = []byte(s)
	} else {
		c.JWTSigningSecret = randomSecret()
		c.GeneratedJWTSecret = true
		c.Warnings = append(c.Warnings, "JWT_SIGNING_SECRET not set: generated an ephemeral secret, resume tokens will not survive restarts or work across replicas")
	}
	if s := env("SESSION_SECRET", ""); s != "" {
		if len(s) < 32 {
			errs = append(errs, "SESSION_SECRET must be at least 32 characters")
		}
		c.SessionSecret = []byte(s)
	} else {
		c.SessionSecret = randomSecret()
		c.GeneratedSessSecret = true
	}
	if (c.TLSCertPath == "") != (c.TLSKeyPath == "") {
		errs = append(errs, "TLS_CERT_PATH and TLS_KEY_PATH must be set together")
	}
	if c.NormalRange <= 0 || c.WhisperRange <= 0 || c.MaxRange <= 0 || c.DistanceSlack < 0 {
		errs = append(errs, "ranges must be positive")
	}
	if c.KeyRotationSecs < 2 {
		errs = append(errs, "KEY_ROTATION_SECONDS must be >= 2")
	}
	if c.ControlPort < 0 || c.ControlPort > 65535 || c.VoiceUDPPort < 0 || c.VoiceUDPPort > 65535 || c.PublicVoicePort < 0 || c.PublicVoicePort > 65535 {
		errs = append(errs, "ports must be in 0..65535 (0 = ephemeral)")
	}
	if ks := env("SCOPE_ATTESTATION_KEYS", ""); ks != "" {
		for _, part := range strings.Split(ks, ",") {
			name, key, ok := strings.Cut(strings.TrimSpace(part), ":")
			if !ok || name == "" {
				errs = append(errs, "SCOPE_ATTESTATION_KEYS must be name:base64key,...")
				continue
			}
			k, err := base64.StdEncoding.DecodeString(key)
			if err != nil || len(k) < 32 {
				errs = append(errs, fmt.Sprintf("SCOPE_ATTESTATION_KEYS[%s]: key must be base64 of >= 32 bytes", name))
				continue
			}
			c.AttestationKeys[name] = k
		}
	}
	if c.DatabaseURL != "" {
		c.Warnings = append(c.Warnings, "DATABASE_URL is set but this version keeps all state in memory (bans via BANS_FILE); it is ignored")
	}
	if c.RedisURL != "" {
		c.Warnings = append(c.Warnings, "REDIS_URL is set but multi-node presence is not implemented in this version; it is ignored")
	}
	if len(errs) > 0 {
		return nil, fmt.Errorf("invalid configuration: %s", strings.Join(errs, "; "))
	}
	return c, nil
}
