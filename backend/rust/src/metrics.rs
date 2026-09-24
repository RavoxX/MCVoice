//! Prometheus text exposition; metric names match backend/go.

use std::fmt::Write;
use std::sync::atomic::{AtomicI64, AtomicU64, Ordering::Relaxed};

pub const DROP_REASONS: &[&str] = &["no_hello", "not_routable", "rate_limited", "replay", "stale_epoch"];
pub const INVALID_REASONS: &[&str] = &[
    "auth_failed",
    "bad_counter",
    "bad_flags",
    "bad_magic",
    "bad_payload",
    "bad_version",
    "too_large",
    "too_short",
    "unknown_key",
    "unknown_session",
    "unknown_type",
    "uuid_mismatch",
];

const LATENCY_BOUNDS: [f64; 9] = [0.0001, 0.00025, 0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05];

#[derive(Default)]
pub struct Metrics {
    pub connected_clients: AtomicI64,
    pub active_voice_sessions: AtomicI64,
    pub packets_received: AtomicU64,
    pub packets_sent: AtomicU64,
    pub bytes_received: AtomicU64,
    pub bytes_sent: AtomicU64,
    pub auth_failures: AtomicU64,
    pub control_messages: AtomicU64,
    dropped: [AtomicU64; 5],
    invalid: [AtomicU64; 12],
    latency_buckets: [AtomicU64; 9],
    latency_sum_ns: AtomicU64,
    latency_count: AtomicU64,
    jitter_bits: AtomicU64,
}

impl Metrics {
    pub fn drop(&self, reason: &str) {
        if let Some(i) = DROP_REASONS.iter().position(|r| *r == reason) {
            self.dropped[i].fetch_add(1, Relaxed);
        }
    }

    pub fn invalid(&self, reason: &str) {
        if let Some(i) = INVALID_REASONS.iter().position(|r| *r == reason) {
            self.invalid[i].fetch_add(1, Relaxed);
        }
    }

    pub fn observe_latency(&self, secs: f64) {
        for (i, b) in LATENCY_BOUNDS.iter().enumerate() {
            if secs <= *b {
                self.latency_buckets[i].fetch_add(1, Relaxed);
            }
        }
        self.latency_sum_ns.fetch_add((secs * 1e9) as u64, Relaxed);
        self.latency_count.fetch_add(1, Relaxed);
    }

    pub fn set_jitter(&self, secs: f64) {
        self.jitter_bits.store(secs.to_bits(), Relaxed);
    }

    pub fn render(&self, version: &str) -> String {
        let mut o = String::with_capacity(4096);
        let _ = writeln!(
            o,
            "# HELP mcvoice_build_info Backend implementation and version.\n# TYPE mcvoice_build_info gauge"
        );
        let _ = writeln!(o, "mcvoice_build_info{{implementation=\"rust\",version=\"{version}\"}} 1");
        let gauge = |o: &mut String, n: &str, h: &str, v: i64| {
            let _ = writeln!(o, "# HELP {n} {h}\n# TYPE {n} gauge\n{n} {v}");
        };
        gauge(
            &mut o,
            "mcvoice_connected_clients",
            "Control connections with an authenticated session.",
            self.connected_clients.load(Relaxed),
        );
        gauge(
            &mut o,
            "mcvoice_active_voice_sessions",
            "Sessions with a verified UDP voice path.",
            self.active_voice_sessions.load(Relaxed),
        );
        let counter = |o: &mut String, n: &str, h: &str, v: u64| {
            let _ = writeln!(o, "# HELP {n} {h}\n# TYPE {n} counter\n{n} {v}");
        };
        counter(
            &mut o,
            "mcvoice_packets_received_total",
            "UDP datagrams received.",
            self.packets_received.load(Relaxed),
        );
        counter(
            &mut o,
            "mcvoice_packets_sent_total",
            "UDP datagrams sent.",
            self.packets_sent.load(Relaxed),
        );
        counter(
            &mut o,
            "mcvoice_bytes_received_total",
            "UDP bytes received.",
            self.bytes_received.load(Relaxed),
        );
        counter(&mut o, "mcvoice_bytes_sent_total", "UDP bytes sent.", self.bytes_sent.load(Relaxed));
        let _ = writeln!(o, "# HELP mcvoice_packets_dropped_total Authenticated datagrams that were not relayed.\n# TYPE mcvoice_packets_dropped_total counter");
        for (i, r) in DROP_REASONS.iter().enumerate() {
            let _ = writeln!(
                o,
                "mcvoice_packets_dropped_total{{reason=\"{r}\"}} {}",
                self.dropped[i].load(Relaxed)
            );
        }
        let _ = writeln!(
            o,
            "# HELP mcvoice_invalid_packets_total Datagrams rejected as invalid.\n# TYPE mcvoice_invalid_packets_total counter"
        );
        for (i, r) in INVALID_REASONS.iter().enumerate() {
            let _ = writeln!(
                o,
                "mcvoice_invalid_packets_total{{reason=\"{r}\"}} {}",
                self.invalid[i].load(Relaxed)
            );
        }
        counter(
            &mut o,
            "mcvoice_auth_failures_total",
            "Failed control authentications.",
            self.auth_failures.load(Relaxed),
        );
        counter(
            &mut o,
            "mcvoice_control_messages_total",
            "Control messages received.",
            self.control_messages.load(Relaxed),
        );
        let _ = writeln!(o, "# HELP mcvoice_voice_jitter_seconds Average interarrival jitter of active voice streams.\n# TYPE mcvoice_voice_jitter_seconds gauge");
        let _ = writeln!(o, "mcvoice_voice_jitter_seconds {}", f64::from_bits(self.jitter_bits.load(Relaxed)));
        let _ = writeln!(o, "# HELP mcvoice_relay_latency_seconds Time from datagram receipt to relay send completion.\n# TYPE mcvoice_relay_latency_seconds histogram");
        for (i, b) in LATENCY_BOUNDS.iter().enumerate() {
            let _ = writeln!(
                o,
                "mcvoice_relay_latency_seconds_bucket{{le=\"{b}\"}} {}",
                self.latency_buckets[i].load(Relaxed)
            );
        }
        let count = self.latency_count.load(Relaxed);
        let _ = writeln!(o, "mcvoice_relay_latency_seconds_bucket{{le=\"+Inf\"}} {count}");
        let _ = writeln!(
            o,
            "mcvoice_relay_latency_seconds_sum {}",
            self.latency_sum_ns.load(Relaxed) as f64 / 1e9
        );
        let _ = writeln!(o, "mcvoice_relay_latency_seconds_count {count}");
        o
    }
}
