//! Control service + voice relay.

mod control;
mod groups;
mod http;
pub mod session;
mod tls;
mod udp;

use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock, RwLock};
use std::time::{Instant, SystemTime};

use serde::Deserialize;
use tokio::net::UdpSocket;
use tracing::{info, warn};

use crate::auth::{ip_tag, MojangVerifier};
use crate::config::Config;
use crate::metrics::Metrics;
use crate::protocol::control::{code, error_frame, is_uuid, KeyMsg, PresenceMsg};
use crate::routing::{self, RoutingConfig};
use session::{PeerState, Session};

pub use http::run;

pub const VERSION: &str = env!("CARGO_PKG_VERSION");

#[derive(Default)]
pub(crate) struct Hub {
    pub by_conn: HashMap<u64, Arc<Session>>,
    pub by_uuid: HashMap<String, u64>,
    pub peers: HashMap<u64, PeerState>,
    pub(crate) groups: HashMap<String, groups::Group>,
    /// Messages queued under the lock (group changes), sent after it is released.
    pub(crate) outbox: Vec<(Arc<Session>, String)>,
}

impl Hub {
    /// Session of a player UUID the given peer reports as visible (routing candidates, spec 8).
    pub(crate) fn peer_of(&self, uuid: &str) -> Option<(u64, &PeerState)> {
        let c = *self.by_uuid.get(uuid)?;
        Some((c, self.peers.get(&c)?))
    }

    fn remove(&mut self, conn: u64) -> Option<Arc<Session>> {
        self.leave_group(conn, None); // disconnected: out of the group (spec 6.12)
        self.peers.remove(&conn);
        let s = self.by_conn.remove(&conn)?;
        if self.by_uuid.get(&s.ident.uuid) == Some(&conn) {
            self.by_uuid.remove(&s.ident.uuid);
        }
        Some(s)
    }
}

#[derive(Default)]
struct Bans {
    banned: HashSet<String>,
    muted: HashSet<String>,
    mtime: Option<SystemTime>,
}

pub(crate) struct IpLimiter {
    per_min: u32,
    inner: Mutex<(Instant, HashMap<String, u32>)>,
}

impl IpLimiter {
    fn new(per_min: u32) -> Self {
        IpLimiter {
            per_min,
            inner: Mutex::new((Instant::now(), HashMap::new())),
        }
    }

    fn roll(&self, g: &mut (Instant, HashMap<String, u32>), now: Instant) {
        if now.duration_since(g.0).as_secs() >= 60 {
            g.0 = now;
            g.1.clear();
        }
    }

    pub fn allow(&self, key: &str, now: Instant) -> bool {
        if self.per_min == 0 {
            return true;
        }
        let mut g = self.inner.lock().unwrap();
        self.roll(&mut g, now);
        if g.1.len() >= 100_000 && !g.1.contains_key(key) {
            return false; // fail closed under key flooding
        }
        let n = g.1.entry(key.to_string()).or_insert(0);
        if *n >= self.per_min {
            return false;
        }
        *n += 1;
        true
    }

    pub fn blocked(&self, key: &str, now: Instant) -> bool {
        if self.per_min == 0 {
            return false;
        }
        let mut g = self.inner.lock().unwrap();
        self.roll(&mut g, now);
        g.1.get(key).is_some_and(|n| *n >= self.per_min)
    }
}

pub struct Server {
    pub cfg: Config,
    pub rcfg: RoutingConfig,
    pub metrics: Metrics,
    mojang: MojangVerifier,
    pub(crate) hub: RwLock<Hub>,
    bans: RwLock<Bans>,
    pub(crate) connect_limiter: IpLimiter,
    pub(crate) auth_fail_limiter: IpLimiter,
    pub(crate) udp: OnceLock<Arc<UdpSocket>>,
    pub(crate) ready: AtomicBool,
    pub(crate) shutdown: AtomicBool,
    epoch: Instant,
}

impl Server {
    pub fn new(cfg: Config) -> Arc<Server> {
        let rcfg = RoutingConfig {
            normal_range: cfg.normal_range,
            whisper_range: cfg.whisper_range,
            max_range: cfg.max_range,
            distance_slack: cfg.distance_slack,
            position_stale_ms: 3000,
        };
        Arc::new(Server {
            mojang: MojangVerifier::new(&cfg.mojang_session_url),
            connect_limiter: IpLimiter::new(cfg.rate_connect_per_min),
            auth_fail_limiter: IpLimiter::new(cfg.rate_auth_fail_per_min),
            rcfg,
            cfg,
            metrics: Metrics::default(),
            hub: RwLock::new(Hub::default()),
            bans: RwLock::new(Bans::default()),
            udp: OnceLock::new(),
            ready: AtomicBool::new(false),
            shutdown: AtomicBool::new(false),
            epoch: Instant::now(),
        })
    }

    /// Monotonic milliseconds used for position freshness.
    pub(crate) fn now_ms(&self) -> i64 {
        self.epoch.elapsed().as_millis() as i64
    }

    pub(crate) fn ip_tag(&self, ip: &str) -> String {
        ip_tag(&self.cfg.session_secret, ip)
    }

    pub(crate) fn is_banned(&self, uuid: &str) -> bool {
        self.bans.read().unwrap().banned.contains(uuid)
    }

    pub(crate) fn is_admin_muted(&self, uuid: &str) -> bool {
        self.bans.read().unwrap().muted.contains(uuid)
    }

    /// Register a session, replacing an older one for the same player.
    pub(crate) fn register(&self, sess: Arc<Session>, ps: PeerState) -> bool {
        let old = {
            let mut hub = self.hub.write().unwrap();
            if hub.by_conn.len() >= self.cfg.max_sessions {
                return false;
            }
            let old = hub.by_uuid.get(&sess.ident.uuid).copied().and_then(|c| hub.remove(c));
            hub.by_uuid.insert(sess.ident.uuid.clone(), sess.conn_id);
            hub.peers.insert(sess.conn_id, ps);
            hub.by_conn.insert(sess.conn_id, sess);
            (old, hub.take_outbox())
        };
        let (old, out) = old;
        groups::send_all(out);
        if let Some(old) = old {
            self.forget_udp(&old);
            self.metrics.connected_clients.fetch_sub(1, Ordering::Relaxed);
            old.send(error_frame(code::SESSION_REPLACED, "replaced by a newer session", true));
            old.close(code::SESSION_REPLACED);
        }
        self.metrics.connected_clients.fetch_add(1, Ordering::Relaxed);
        true
    }

    fn forget_udp(&self, sess: &Session) {
        let mut u = sess.udp.lock().unwrap();
        if u.addr.take().is_some() {
            self.metrics.active_voice_sessions.fetch_sub(1, Ordering::Relaxed);
        }
    }

    pub(crate) fn unregister(&self, sess: &Arc<Session>) {
        let (removed, out) = {
            let mut hub = self.hub.write().unwrap();
            let removed = if hub.by_conn.get(&sess.conn_id).is_some_and(|s| Arc::ptr_eq(s, sess)) {
                hub.remove(sess.conn_id).is_some()
            } else {
                false
            };
            (removed, hub.take_outbox())
        };
        groups::send_all(out);
        if removed {
            self.metrics.connected_clients.fetch_sub(1, Ordering::Relaxed);
            self.forget_udp(sess);
        }
    }

    pub(crate) fn reload_bans(&self) {
        if self.cfg.bans_file.is_empty() {
            return;
        }
        let Ok(meta) = std::fs::metadata(&self.cfg.bans_file) else {
            warn!(category = "moderation", "bans file unreadable");
            return;
        };
        let mtime = meta.modified().ok();
        if mtime.is_some() && self.bans.read().unwrap().mtime >= mtime {
            return;
        }
        #[derive(Deserialize, Default)]
        struct F {
            #[serde(default)]
            banned: Vec<String>,
            #[serde(default)]
            muted: Vec<String>,
        }
        let Ok(bytes) = std::fs::read(&self.cfg.bans_file) else { return };
        let f: F = match serde_json::from_slice(&bytes) {
            Ok(f) => f,
            Err(e) => {
                warn!(category = "moderation", error = %e, "bans file invalid");
                return;
            }
        };
        let banned: HashSet<String> = f.banned.into_iter().filter(|u| is_uuid(u)).collect();
        let muted: HashSet<String> = f.muted.into_iter().filter(|u| is_uuid(u)).collect();
        info!(category = "moderation", banned = banned.len(), muted = muted.len(), "bans loaded");
        let mut kick = Vec::new();
        {
            let mut hub = self.hub.write().unwrap();
            let Hub { by_conn, peers, .. } = &mut *hub;
            for (conn, s) in by_conn.iter() {
                if let Some(ps) = peers.get_mut(conn) {
                    ps.admin_muted = muted.contains(&s.ident.uuid);
                    ps.peer.muted = ps.self_muted || ps.admin_muted;
                }
                if banned.contains(&s.ident.uuid) {
                    kick.push(s.clone());
                }
            }
        }
        *self.bans.write().unwrap() = Bans { banned, muted, mtime };
        for s in kick {
            s.send(error_frame(code::BANNED, "banned", true));
            s.close(code::BANNED);
        }
    }

    /// Recompute presence for all sessions and send diffs (spec 6.7).
    pub(crate) fn presence_tick(&self) {
        self.group_tick(Instant::now());
        let mut updates = Vec::new();
        {
            let mut hub = self.hub.write().unwrap();
            let conns: Vec<u64> = hub.peers.keys().copied().collect();
            for conn in conns {
                let next: HashSet<String> = {
                    let r = &hub.peers[&conn];
                    if !r.peer.in_world {
                        HashSet::new()
                    } else {
                        r.peer
                            .visible
                            .iter()
                            .filter(|u| {
                                hub.peer_of(u).is_some_and(|(_, o)| {
                                    o.peer.in_world
                                        && o.peer.udp_verified
                                        && routing::compatible_scopes(&o.peer, &r.peer)
                                        && routing::mutually_visible(&o.peer, &r.peer)
                                })
                            })
                            .cloned()
                            .collect()
                    }
                };
                let r = hub.peers.get_mut(&conn).unwrap();
                let mut add: Vec<String> = next.difference(&r.presence).cloned().collect();
                let mut remove: Vec<String> = r.presence.difference(&next).cloned().collect();
                if add.is_empty() && remove.is_empty() {
                    continue;
                }
                add.sort();
                remove.sort();
                r.presence = next;
                let msg = serde_json::to_string(&PresenceMsg {
                    t: "presence",
                    epoch: r.peer.epoch,
                    add,
                    remove,
                })
                .unwrap();
                if let Some(s) = hub.by_conn.get(&conn) {
                    updates.push((s.clone(), msg));
                }
            }
        }
        for (s, m) in updates {
            s.send(m);
        }
    }

    pub(crate) fn update_jitter_gauge(&self) {
        let now = Instant::now();
        let hub = self.hub.read().unwrap();
        let (mut sum, mut n) = (0.0, 0);
        for s in hub.by_conn.values() {
            let u = s.udp.lock().unwrap();
            if u.last_voice.is_some_and(|t| now.duration_since(t).as_secs() < 5) && u.last_transit.is_some() {
                sum += u.jitter;
                n += 1;
            }
        }
        self.metrics.set_jitter(if n > 0 { sum / n as f64 } else { 0.0 });
    }

    /// Force a key rotation for every session (tests / admin).
    pub fn rotate_all_keys(&self) {
        let sessions: Vec<Arc<Session>> = self.hub.read().unwrap().by_conn.values().cloned().collect();
        for s in sessions {
            self.rotate_session(&s);
        }
    }

    pub(crate) fn key_ttl(&self) -> std::time::Duration {
        std::time::Duration::from_secs(self.cfg.key_rotation_secs * 2)
    }

    pub(crate) fn rotate_session(&self, s: &Session) {
        let (id, key) = s.udp.lock().unwrap().rotate(self.key_ttl(), Instant::now());
        s.send(
            serde_json::to_string(&KeyMsg {
                t: "key",
                key_id: id,
                key,
                key_expires_in: self.key_ttl().as_secs(),
            })
            .unwrap(),
        );
    }
}
