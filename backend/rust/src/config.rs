//! Environment configuration. Variable names are shared with backend/go.

use std::collections::HashMap;

use base64::Engine;

#[derive(Debug, Clone)]
pub struct Config {
    pub control_bind_address: String,
    pub control_port: u16,
    pub voice_bind_address: String,
    pub voice_udp_port: u16,
    pub public_hostname: String,
    pub public_voice_port: u16,
    pub tls_cert_path: String,
    pub tls_key_path: String,
    pub auth_mode: String,
    pub mojang_session_url: String,
    pub jwt_signing_secret: Vec<u8>,
    pub session_secret: Vec<u8>,
    pub log_level: String,
    pub log_format: String,
    pub log_positions: bool,
    pub normal_range: f64,
    pub whisper_range: f64,
    pub max_range: f64,
    pub distance_slack: f64,
    pub key_rotation_secs: u64,
    pub resume_token_ttl: u64,
    pub max_sessions: usize,
    pub session_timeout_secs: u64,
    pub rate_voice_per_sec: f64,
    pub rate_voice_burst: f64,
    pub rate_control_per_sec: f64,
    pub rate_control_burst: f64,
    pub rate_connect_per_min: u32,
    pub rate_auth_fail_per_min: u32,
    pub trust_proxy_headers: bool,
    pub udp_workers: usize,
    pub bans_file: String,
    pub attestation_keys: HashMap<String, Vec<u8>>,
    pub warnings: Vec<String>,
}

struct Env<'a> {
    errs: &'a mut Vec<String>,
}

fn raw(k: &str) -> Option<String> {
    std::env::var(k).ok().filter(|v| !v.is_empty())
}

impl Env<'_> {
    fn s(&self, k: &str, def: &str) -> String {
        raw(k).unwrap_or_else(|| def.to_string())
    }
    fn parse<T: std::str::FromStr>(&mut self, k: &str, def: T, what: &str) -> T {
        match raw(k) {
            None => def,
            Some(v) => v.parse().unwrap_or_else(|_| {
                self.errs.push(format!("{k}: not {what}"));
                def
            }),
        }
    }
    fn b(&mut self, k: &str, def: bool) -> bool {
        match raw(k).map(|v| v.to_lowercase()) {
            None => def,
            Some(v) if ["1", "true", "yes", "on"].contains(&v.as_str()) => true,
            Some(v) if ["0", "false", "no", "off"].contains(&v.as_str()) => false,
            Some(_) => {
                self.errs.push(format!("{k}: not a boolean"));
                def
            }
        }
    }
}

fn random_secret() -> Vec<u8> {
    use rand::RngCore;
    let mut b = vec![0u8; 32];
    rand::rngs::OsRng.fill_bytes(&mut b);
    b
}

impl Config {
    pub fn from_env() -> Result<Config, String> {
        let mut errs = Vec::new();
        let mut warnings = Vec::new();
        let mut e = Env { errs: &mut errs };
        let voice_udp_port: u16 = e.parse("VOICE_UDP_PORT", 24455, "a port");
        let mut c = Config {
            control_bind_address: e.s("CONTROL_BIND_ADDRESS", "0.0.0.0"),
            control_port: e.parse("CONTROL_PORT", 8080, "a port"),
            voice_bind_address: e.s("VOICE_BIND_ADDRESS", "0.0.0.0"),
            voice_udp_port,
            public_hostname: e.s("PUBLIC_HOSTNAME", ""),
            public_voice_port: e.parse("PUBLIC_VOICE_PORT", voice_udp_port, "a port"),
            tls_cert_path: e.s("TLS_CERT_PATH", ""),
            tls_key_path: e.s("TLS_KEY_PATH", ""),
            auth_mode: e.s("AUTH_MODE", "mojang").to_lowercase(),
            mojang_session_url: e.s("MOJANG_SESSION_URL", "https://sessionserver.mojang.com/session/minecraft/hasJoined"),
            jwt_signing_secret: Vec::new(),
            session_secret: Vec::new(),
            log_level: e.s("LOG_LEVEL", "info").to_lowercase(),
            log_format: e.s("LOG_FORMAT", "json").to_lowercase(),
            log_positions: e.b("LOG_POSITIONS", false),
            normal_range: e.parse("NORMAL_RANGE", 48.0, "a number"),
            whisper_range: e.parse("WHISPER_RANGE", 8.0, "a number"),
            max_range: e.parse("MAX_RANGE", 96.0, "a number"),
            distance_slack: e.parse("ROUTING_DISTANCE_SLACK", 4.0, "a number"),
            key_rotation_secs: e.parse("KEY_ROTATION_SECONDS", 600, "an integer"),
            resume_token_ttl: e.parse("RESUME_TOKEN_TTL_SECONDS", 3600, "an integer"),
            max_sessions: e.parse("MAX_SESSIONS", 10000, "an integer"),
            session_timeout_secs: e.parse("SESSION_TIMEOUT_SECONDS", 20, "an integer"),
            rate_voice_per_sec: e.parse("RATE_LIMIT_VOICE_PER_SEC", 75.0, "a number"),
            rate_voice_burst: e.parse("RATE_LIMIT_VOICE_BURST", 150.0, "a number"),
            rate_control_per_sec: e.parse("RATE_LIMIT_CONTROL_PER_SEC", 40.0, "a number"),
            rate_control_burst: e.parse("RATE_LIMIT_CONTROL_BURST", 80.0, "a number"),
            rate_connect_per_min: e.parse("RATE_LIMIT_CONNECT_PER_MIN", 30, "an integer"),
            rate_auth_fail_per_min: e.parse("RATE_LIMIT_AUTH_FAIL_PER_MIN", 10, "an integer"),
            trust_proxy_headers: e.b("TRUST_PROXY_HEADERS", false),
            udp_workers: e.parse("UDP_WORKERS", 0, "an integer"),
            bans_file: e.s("BANS_FILE", ""),
            attestation_keys: HashMap::new(),
            warnings: Vec::new(),
        };
        match c.auth_mode.as_str() {
            "mojang" => {}
            "offline" => warnings.push("AUTH_MODE=offline: player identities are NOT verified; development use only".into()),
            _ => errs.push("AUTH_MODE must be mojang or offline".into()),
        }
        match raw("JWT_SIGNING_SECRET") {
            Some(s) => {
                if s.len() < 32 {
                    errs.push("JWT_SIGNING_SECRET must be at least 32 characters".into());
                }
                c.jwt_signing_secret = s.into_bytes();
            }
            None => {
                c.jwt_signing_secret = random_secret();
                warnings.push("JWT_SIGNING_SECRET not set: generated an ephemeral secret, resume tokens will not survive restarts or work across replicas".into());
            }
        }
        match raw("SESSION_SECRET") {
            Some(s) => {
                if s.len() < 32 {
                    errs.push("SESSION_SECRET must be at least 32 characters".into());
                }
                c.session_secret = s.into_bytes();
            }
            None => c.session_secret = random_secret(),
        }
        if c.tls_cert_path.is_empty() != c.tls_key_path.is_empty() {
            errs.push("TLS_CERT_PATH and TLS_KEY_PATH must be set together".into());
        }
        if c.normal_range <= 0.0 || c.whisper_range <= 0.0 || c.max_range <= 0.0 || c.distance_slack < 0.0 {
            errs.push("ranges must be positive".into());
        }
        if c.key_rotation_secs < 2 {
            errs.push("KEY_ROTATION_SECONDS must be >= 2".into());
        }
        if let Some(ks) = raw("SCOPE_ATTESTATION_KEYS") {
            for part in ks.split(',') {
                let Some((name, key)) = part.trim().split_once(':').filter(|(n, _)| !n.is_empty()) else {
                    errs.push("SCOPE_ATTESTATION_KEYS must be name:base64key,...".into());
                    continue;
                };
                match base64::engine::general_purpose::STANDARD.decode(key) {
                    Ok(k) if k.len() >= 32 => {
                        c.attestation_keys.insert(name.to_string(), k);
                    }
                    _ => errs.push(format!("SCOPE_ATTESTATION_KEYS[{name}]: key must be base64 of >= 32 bytes")),
                }
            }
        }
        if raw("ROUTING_REQUIRE_MUTUAL_VISIBILITY").is_some_and(|v| v.eq_ignore_ascii_case("false")) {
            warnings.push("ROUTING_REQUIRE_MUTUAL_VISIBILITY=false is ignored: mutual visibility is always required (spec 8)".into());
        }
        if raw("DATABASE_URL").is_some() {
            warnings.push("DATABASE_URL is set but this version keeps all state in memory (bans via BANS_FILE); it is ignored".into());
        }
        if raw("REDIS_URL").is_some() {
            warnings.push("REDIS_URL is set but multi-node presence is not implemented in this version; it is ignored".into());
        }
        c.warnings = warnings;
        if errs.is_empty() {
            Ok(c)
        } else {
            Err(format!("invalid configuration: {}", errs.join("; ")))
        }
    }
}
