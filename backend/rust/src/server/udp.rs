use std::net::SocketAddr;
use std::sync::atomic::Ordering::Relaxed;
use std::sync::Arc;
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use tokio::net::UdpSocket;

use super::session::Session;
use super::Server;
use crate::protocol::udp::*;
use crate::routing;

/// Per-worker reusable buffers so the 20 ms hot path does not allocate.
pub(crate) struct Scratch {
    plain: Vec<u8>,
    relay_plain: Vec<u8>,
    outgoing: Vec<(SocketAddr, Vec<u8>)>,
    free: Vec<Vec<u8>>,
}

impl Scratch {
    pub fn new() -> Self {
        Scratch {
            plain: Vec::with_capacity(MAX_DATAGRAM),
            relay_plain: Vec::with_capacity(MAX_DATAGRAM),
            outgoing: Vec::new(),
            free: Vec::new(),
        }
    }

    fn buf(&mut self) -> Vec<u8> {
        let mut b = self.free.pop().unwrap_or_else(|| Vec::with_capacity(MAX_DATAGRAM + 64));
        b.clear();
        b
    }
}

pub(crate) async fn worker(srv: Arc<Server>, sock: Arc<UdpSocket>) {
    let mut buf = vec![0u8; MAX_DATAGRAM + 1];
    let mut scratch = Scratch::new();
    loop {
        let (n, from) = match sock.recv_from(&mut buf).await {
            Ok(v) => v,
            Err(_) => {
                if srv.shutdown.load(Relaxed) {
                    return;
                }
                continue;
            }
        };
        srv.metrics.packets_received.fetch_add(1, Relaxed);
        srv.metrics.bytes_received.fetch_add(n as u64, Relaxed);
        let start = Instant::now();
        let relayed = srv.handle_datagram(&buf[..n], from, &mut scratch);
        let mut outgoing = std::mem::take(&mut scratch.outgoing);
        for (addr, dg) in outgoing.drain(..) {
            if sock.send_to(&dg, addr).await.is_ok() {
                srv.metrics.packets_sent.fetch_add(1, Relaxed);
                srv.metrics.bytes_sent.fetch_add(dg.len() as u64, Relaxed);
            }
            scratch.free.push(dg);
        }
        scratch.outgoing = outgoing;
        if relayed {
            srv.metrics.observe_latency(start.elapsed().as_secs_f64());
        }
    }
}

impl Server {
    /// Process one datagram; queues responses into `scratch.outgoing`.
    /// Returns true if it was a voice frame that went through routing.
    pub(crate) fn handle_datagram(&self, dg: &[u8], from: SocketAddr, scratch: &mut Scratch) -> bool {
        let h = match parse_header(dg, DIR_C2S) {
            Ok(h) => h,
            Err(e) => {
                self.metrics.invalid(e.as_str());
                return false;
            }
        };
        let Some(sess) = self.hub.read().unwrap().by_conn.get(&h.connection_id).cloned() else {
            self.metrics.invalid("unknown_session");
            return false;
        };
        let now = Instant::now();
        let mut u = sess.udp.lock().unwrap();
        let is_cur = u.is_cur(h.key_id);
        let has_prev = u.prev.is_some();
        let Some(key) = u.key_for(h.key_id, now) else {
            self.metrics.invalid("unknown_key");
            return false;
        };
        if !key.window.check(h.counter) {
            self.metrics.drop("replay");
            return false;
        }
        if key.key.open(&mut scratch.plain, DIR_C2S, &h, dg).is_err() {
            self.metrics.invalid("auth_failed");
            return false;
        }
        if let Err(e) = parse_plaintext(h.ptype, &scratch.plain) {
            self.metrics.invalid(e.as_str());
            return false;
        }
        let new_top = key.window.update(h.counter);
        if is_cur && has_prev {
            u.send_with_new = true; // client proved it has the rotated key
        }
        match h.ptype {
            TYPE_HELLO => {
                let (uid, ct) = parse_hello(&scratch.plain).unwrap();
                if uid != sess.uuid_b {
                    self.metrics.invalid("uuid_mismatch");
                    return false;
                }
                let first = u.addr.is_none();
                u.addr = Some(from);
                let send_ok = !u.udp_ok_sent;
                u.udp_ok_sent = true;
                drop(u);
                if first {
                    self.metrics.active_voice_sessions.fetch_add(1, Relaxed);
                    if let Some(ps) = self.hub.write().unwrap().peers.get_mut(&sess.conn_id) {
                        ps.peer.udp_verified = true;
                    }
                }
                self.reply(&sess, TYPE_HELLO_ACK, ct, scratch);
                if send_ok {
                    sess.send(r#"{"type":"udp_ok"}"#.to_string());
                }
                false
            }
            TYPE_PING => {
                if u.addr.is_none() {
                    self.metrics.drop("no_hello");
                    return false;
                }
                if new_top {
                    u.addr = Some(from);
                }
                drop(u);
                let ct = parse_time(&scratch.plain).unwrap();
                self.reply(&sess, TYPE_PONG, ct, scratch);
                false
            }
            TYPE_VOICE => {
                if u.addr.is_none() {
                    self.metrics.drop("no_hello");
                    return false;
                }
                if new_top {
                    u.addr = Some(from); // NAT rebinding only on a fresh authenticated counter
                }
                if !u.voice_bucket.allow(now) {
                    self.metrics.drop("rate_limited");
                    return false;
                }
                let plain = std::mem::take(&mut scratch.plain);
                let v = parse_voice(&plain).unwrap();
                track_jitter(&mut u, v.timestamp, now);
                drop(u);
                self.relay_voice(&sess, &v, scratch);
                scratch.plain = plain;
                true
            }
            _ => false,
        }
    }

    fn reply(&self, sess: &Session, t: u8, ct: u64, scratch: &mut Scratch) {
        let mut out = scratch.buf();
        let mut u = sess.udp.lock().unwrap();
        let Some(addr) = u.addr else { return };
        let k = u.send_key(Instant::now());
        k.send_counter += 1;
        let h = Header {
            ptype: t,
            key_id: k.id,
            connection_id: sess.conn_id,
            counter: k.send_counter,
        };
        k.key.seal(&mut out, DIR_S2C, &h, &ct.to_be_bytes());
        scratch.outgoing.push((addr, out));
    }

    fn relay_voice(&self, sender: &Session, v: &Voice<'_>, scratch: &mut Scratch) {
        let now_ms = self.now_ms();
        let targets: Vec<(Arc<Session>, u32)> = {
            let hub = self.hub.read().unwrap();
            let Some(sp) = hub.peers.get(&sender.conn_id) else { return };
            if !routing::sender_eligible(&self.rcfg, now_ms, &sp.peer, v.epoch) {
                let reason = if sp.peer.in_world && v.epoch != sp.peer.epoch {
                    "stale_epoch"
                } else {
                    "not_routable"
                };
                self.metrics.drop(reason);
                return;
            }
            let Some(bucket) = hub.buckets.get(&sp.peer.scope_key) else {
                return;
            };
            bucket
                .iter()
                .filter_map(|c| {
                    let rp = hub.peers.get(c)?;
                    if routing::deliver(&self.rcfg, now_ms, &sp.peer, &rp.peer, v.mode) {
                        Some((hub.by_conn.get(c)?.clone(), rp.peer.epoch))
                    } else {
                        None
                    }
                })
                .collect()
        };
        let now = Instant::now();
        for (r, epoch) in targets {
            scratch.relay_plain.clear();
            write_relay(&mut scratch.relay_plain, &sender.uuid_b, epoch, v);
            let mut out = scratch.buf();
            let mut u = r.udp.lock().unwrap();
            let Some(addr) = u.addr else { continue };
            let k = u.send_key(now);
            k.send_counter += 1;
            let h = Header {
                ptype: TYPE_VOICE_RELAY,
                key_id: k.id,
                connection_id: r.conn_id,
                counter: k.send_counter,
            };
            k.key.seal(&mut out, DIR_S2C, &h, &scratch.relay_plain);
            drop(u);
            scratch.outgoing.push((addr, out));
        }
    }
}

/// RFC 3550 interarrival jitter estimate.
fn track_jitter(u: &mut super::session::UdpState, ts: u32, now: Instant) {
    let arrival = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs_f64()).unwrap_or(0.0);
    let transit = arrival - ts as f64 / 48000.0;
    if let (Some(last), Some(lv)) = (u.last_transit, u.last_voice) {
        if now.duration_since(lv).as_secs_f64() < 1.0 {
            let d = (transit - last).abs();
            if d < 1.0 {
                u.jitter += (d - u.jitter) / 16.0;
            }
        }
    }
    u.last_transit = Some(transit);
    u.last_voice = Some(now);
}
