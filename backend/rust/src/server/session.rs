use std::collections::HashSet;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use rand::RngCore;
use tokio::sync::{mpsc, watch};

use crate::auth::Identity;
use crate::protocol::replay::ReplayWindow;
use crate::protocol::udp::{VoiceKey, KEY_LEN};
use crate::routing::Peer;

pub const KEY_GRACE: Duration = Duration::from_secs(30);

pub fn random_bytes<const N: usize>() -> [u8; N] {
    let mut b = [0u8; N];
    rand::rngs::OsRng.fill_bytes(&mut b);
    b
}

pub struct KeySlot {
    pub id: u8,
    pub raw: [u8; KEY_LEN],
    pub key: VoiceKey,
    pub window: ReplayWindow,
    pub send_counter: u64,
    pub retire_at: Option<Instant>,
    pub expires_at: Instant,
}

impl KeySlot {
    pub fn new(id: u8, ttl: Duration, now: Instant) -> KeySlot {
        let raw = random_bytes::<KEY_LEN>();
        KeySlot {
            id,
            raw,
            key: VoiceKey::new(&raw).expect("16-byte key"),
            window: ReplayWindow::default(),
            send_counter: 0,
            retire_at: None,
            expires_at: now + ttl,
        }
    }

    pub fn b64(&self) -> String {
        use base64::Engine;
        base64::engine::general_purpose::STANDARD.encode(self.raw)
    }
}

pub struct TokenBucket {
    rate: f64,
    burst: f64,
    tokens: f64,
    last: Option<Instant>,
}

impl TokenBucket {
    pub fn new(rate: f64, burst: f64) -> Self {
        TokenBucket {
            rate,
            burst,
            tokens: burst,
            last: None,
        }
    }

    pub fn allow(&mut self, now: Instant) -> bool {
        if let Some(last) = self.last {
            self.tokens = (self.tokens + now.saturating_duration_since(last).as_secs_f64() * self.rate).min(self.burst);
        }
        self.last = Some(now);
        if self.tokens >= 1.0 {
            self.tokens -= 1.0;
            true
        } else {
            false
        }
    }
}

/// UDP-path state of a session, guarded by `Session::udp`.
pub struct UdpState {
    pub cur: KeySlot,
    pub prev: Option<KeySlot>,
    pub send_with_new: bool,
    pub addr: Option<SocketAddr>,
    pub udp_ok_sent: bool,
    pub voice_bucket: TokenBucket,
    pub jitter: f64,
    pub last_transit: Option<f64>,
    pub last_voice: Option<Instant>,
}

impl UdpState {
    /// Key for an incoming datagram's key id.
    pub fn key_for(&mut self, id: u8, now: Instant) -> Option<&mut KeySlot> {
        if self.cur.id == id && now < self.cur.expires_at {
            return Some(&mut self.cur);
        }
        match &mut self.prev {
            Some(p) if p.id == id && p.retire_at.is_some_and(|r| now < r) => Some(p),
            _ => None,
        }
    }

    pub fn is_cur(&self, id: u8) -> bool {
        self.cur.id == id
    }

    /// Key used for backend -> client datagrams.
    pub fn send_key(&mut self, now: Instant) -> &mut KeySlot {
        let use_prev = !self.send_with_new && self.prev.as_ref().is_some_and(|p| p.retire_at.is_some_and(|r| now < r));
        if use_prev {
            self.prev.as_mut().unwrap()
        } else {
            &mut self.cur
        }
    }

    pub fn rotate(&mut self, ttl: Duration, now: Instant) -> (u8, String) {
        let next = KeySlot::new(self.cur.id.wrapping_add(1), ttl, now);
        let mut old = std::mem::replace(&mut self.cur, next);
        old.retire_at = Some(now + KEY_GRACE);
        self.prev = Some(old);
        self.send_with_new = false;
        (self.cur.id, self.cur.b64())
    }
}

pub struct Session {
    pub id: String,
    pub conn_id: u64,
    pub ident: Identity,
    pub uuid_b: [u8; 16],
    pub out: mpsc::Sender<String>,
    closed_tx: watch::Sender<bool>,
    close_code: Mutex<&'static str>,
    closed: AtomicBool,
    pub udp: Mutex<UdpState>,
    pub last_seen: AtomicI64,
}

impl Session {
    pub fn new(ident: Identity, uuid_b: [u8; 16], out: mpsc::Sender<String>, udp: UdpState) -> Self {
        let (closed_tx, _) = watch::channel(false);
        let conn_id = loop {
            let v = u64::from_be_bytes(random_bytes::<8>());
            if v != 0 {
                break v;
            }
        };
        Session {
            id: format!("s_{}", hex::encode(random_bytes::<12>())),
            conn_id,
            ident,
            uuid_b,
            out,
            closed_tx,
            close_code: Mutex::new(""),
            closed: AtomicBool::new(false),
            udp: Mutex::new(udp),
            last_seen: AtomicI64::new(0),
        }
    }

    /// Queue a control frame; a client that cannot keep up is disconnected.
    pub fn send(&self, msg: String) {
        if self.closed.load(Ordering::Relaxed) {
            return;
        }
        if self.out.try_send(msg).is_err() {
            self.close("slow_consumer");
        }
    }

    pub fn close(&self, code: &'static str) {
        if !self.closed.swap(true, Ordering::SeqCst) {
            *self.close_code.lock().unwrap() = code;
            let _ = self.closed_tx.send(true);
        }
    }

    pub fn is_closed(&self) -> bool {
        self.closed.load(Ordering::SeqCst)
    }

    pub fn close_code(&self) -> &'static str {
        *self.close_code.lock().unwrap()
    }

    pub fn closed_rx(&self) -> watch::Receiver<bool> {
        self.closed_tx.subscribe()
    }
}

/// Routing-relevant state of a session, guarded by the hub lock.
pub struct PeerState {
    pub peer: Peer,
    pub peers_rev: u32,
    pub presence: HashSet<String>,
    pub self_muted: bool,
    pub admin_muted: bool,
    pub has_scope: bool,
    /// the client advertised the `groups` capability
    pub groups_cap: bool,
    /// set while the session is out of a world (group grace, spec 6.12)
    pub out_of_world_since: Option<Instant>,
    pub group_fail_window: Instant,
    pub group_fails: u32,
}
