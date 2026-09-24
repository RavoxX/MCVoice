use std::collections::HashSet;
use std::net::SocketAddr;
use std::sync::atomic::Ordering::Relaxed;
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use axum::extract::ws::{CloseFrame, Message, WebSocket};
use futures_util::stream::{SplitSink, SplitStream};
use futures_util::{SinkExt, StreamExt};
use tokio::sync::mpsc;
use tracing::{debug, info};

use super::session::{random_bytes, KeySlot, PeerState, Session, TokenBucket, UdpState};
use super::Server;
use crate::auth::{self, AuthError, Identity};
use crate::protocol::control::*;
use crate::protocol::udp::{MAJOR, MINOR};
use crate::routing::{scope_key, Peer};

const HELLO_TIMEOUT: Duration = Duration::from_secs(10);
const AUTH_TIMEOUT: Duration = Duration::from_secs(30);

type Sink = SplitSink<WebSocket, Message>;
type Stream = SplitStream<WebSocket>;

fn unix_now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

fn unix_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

enum ReadErr {
    Timeout,
    Closed,
    Binary,
}

async fn read_text(stream: &mut Stream, timeout: Duration) -> Result<String, ReadErr> {
    let deadline = tokio::time::Instant::now() + timeout;
    loop {
        match tokio::time::timeout_at(deadline, stream.next()).await {
            Err(_) => return Err(ReadErr::Timeout),
            Ok(None) | Ok(Some(Err(_))) => return Err(ReadErr::Closed),
            Ok(Some(Ok(Message::Text(t)))) => return Ok(t.to_string()),
            Ok(Some(Ok(Message::Binary(_)))) => return Err(ReadErr::Binary),
            Ok(Some(Ok(Message::Close(_)))) => return Err(ReadErr::Closed),
            Ok(Some(Ok(_))) => continue, // ping/pong handled by the library
        }
    }
}

async fn send_text(sink: &mut Sink, s: String) -> bool {
    tokio::time::timeout(Duration::from_secs(5), sink.send(Message::Text(s.into())))
        .await
        .is_ok_and(|r| r.is_ok())
}

async fn fail(sink: &mut Sink, code_: &'static str, msg: &str) {
    let _ = send_text(sink, error_frame(code_, msg, true)).await;
    let status = if code_ == code::INTERNAL { 1011 } else { 1008 };
    let _ = tokio::time::timeout(
        Duration::from_secs(2),
        sink.send(Message::Close(Some(CloseFrame {
            code: status,
            reason: code_.into(),
        }))),
    )
    .await;
}

fn hex_u64(v: u64) -> String {
    hex::encode(v.to_be_bytes())
}

pub(crate) async fn handle_socket(srv: Arc<Server>, socket: WebSocket, ip: String, host: String) {
    let (mut sink, mut stream) = socket.split();
    let tag = srv.ip_tag(&ip);

    // --- hello ---
    let frame = match read_text(&mut stream, HELLO_TIMEOUT).await {
        Ok(f) => f,
        Err(_) => return fail(&mut sink, code::BAD_MESSAGE, "expected hello").await,
    };
    let hello = match parse_control(frame.as_bytes()) {
        Ok(ClientMsg::Hello(h)) => h,
        Ok(_) => return fail(&mut sink, code::BAD_MESSAGE, "first message must be hello").await,
        Err(e) => return fail(&mut sink, e.code, e.message).await,
    };
    let challenge = hex::encode(random_bytes::<16>());
    let ok = HelloOk {
        t: "hello_ok",
        protocol: Version {
            major: MAJOR as u32,
            minor: MINOR as u32,
        },
        server: ServerInfo {
            name: "mcvoice-backend",
            version: super::VERSION,
            implementation: "rust",
        },
        capabilities: SERVER_CAPABILITIES,
        auth: AuthInfo {
            methods: vec![srv.cfg.auth_mode.clone()],
            challenge: challenge.clone(),
        },
    };
    if !send_text(&mut sink, serde_json::to_string(&ok).unwrap()).await {
        return;
    }
    let challenge_at = Instant::now();

    // --- auth / resume ---
    let frame = match read_text(&mut stream, AUTH_TIMEOUT).await {
        Ok(f) => f,
        Err(_) => return fail(&mut sink, code::AUTH_REQUIRED, "expected auth").await,
    };
    let auth_failed = |srv: &Server| {
        srv.metrics.auth_failures.fetch_add(1, Relaxed);
        srv.auth_fail_limiter.allow(&ip, Instant::now());
    };
    let ident: Identity = match parse_control(frame.as_bytes()) {
        Err(e) => return fail(&mut sink, e.code, e.message).await,
        Ok(ClientMsg::Auth(a)) => {
            let method = a.method.unwrap();
            let username = a.username.unwrap();
            if method != srv.cfg.auth_mode {
                return fail(&mut sink, code::UNSUPPORTED_AUTH_METHOD, "auth method not enabled").await;
            }
            if challenge_at.elapsed() > Duration::from_secs(60) {
                return fail(&mut sink, code::AUTH_FAILED, "challenge expired").await;
            }
            if method == "mojang" {
                match srv.mojang.verify(&username, &challenge).await {
                    Ok(id) => id,
                    Err(e) => {
                        auth_failed(&srv);
                        info!(category = "control", ip = %tag, error = ?e, "authentication failed");
                        return fail(&mut sink, code::AUTH_FAILED, "could not verify Minecraft session").await;
                    }
                }
            } else {
                Identity {
                    uuid: a.uuid.unwrap(),
                    username,
                }
            }
        }
        Ok(ClientMsg::Resume(r)) => match auth::verify_resume(&srv.cfg.jwt_signing_secret, &r.resume_token.unwrap(), unix_now()) {
            Some(id) => id,
            None => {
                auth_failed(&srv);
                return fail(&mut sink, code::SESSION_EXPIRED, "resume token invalid or expired").await;
            }
        },
        Ok(_) => return fail(&mut sink, code::AUTH_REQUIRED, "authenticate first").await,
    };
    let _ = AuthError::Rejected; // variants used by verify()
    if srv.is_banned(&ident.uuid) {
        return fail(&mut sink, code::BANNED, "banned").await;
    }

    let now = Instant::now();
    let ttl = srv.key_ttl();
    let (tx, mut rx) = mpsc::channel::<String>(128);
    let uuid_b = uuid_bytes(&ident.uuid).expect("validated uuid");
    let udp = UdpState {
        cur: KeySlot::new(0, ttl, now),
        prev: None,
        send_with_new: false,
        addr: None,
        udp_ok_sent: false,
        voice_bucket: TokenBucket::new(srv.cfg.rate_voice_per_sec, srv.cfg.rate_voice_burst),
        jitter: 0.0,
        last_transit: None,
        last_voice: None,
    };
    let sess = Arc::new(Session::new(ident.clone(), uuid_b, tx, udp));
    let admin_muted = srv.is_admin_muted(&ident.uuid);
    let ps = PeerState {
        peer: Peer {
            uuid: ident.uuid.clone(),
            authenticated: true,
            muted: admin_muted,
            ..Default::default()
        },
        network_id: String::new(),
        world_id: String::new(),
        attested: String::new(),
        peers_rev: 0,
        presence: HashSet::new(),
        self_muted: false,
        admin_muted,
        has_scope: false,
    };
    if !srv.register(sess.clone(), ps) {
        return fail(&mut sink, code::SERVER_FULL, "server full").await;
    }
    info!(category = "control", ip = %tag, session = %sess.id, player = %ident.uuid,
          client = %hello.client.as_ref().map(|c| c.version.as_str()).unwrap_or(""),
          minecraft = %hello.client.as_ref().map(|c| c.minecraft.as_str()).unwrap_or(""), "session started");

    let (key_id, key_b64) = {
        let u = sess.udp.lock().unwrap();
        (u.cur.id, u.cur.b64())
    };
    let port = if srv.cfg.public_voice_port != 0 {
        srv.cfg.public_voice_port
    } else {
        srv.udp.get().and_then(|s| s.local_addr().ok()).map(|a| a.port()).unwrap_or(0)
    };
    let msg = SessionMsg {
        t: "session",
        session_id: sess.id.clone(),
        player_uuid: ident.uuid.clone(),
        username: ident.username.clone(),
        resume_token: auth::issue_resume(&srv.cfg.jwt_signing_secret, &ident, srv.cfg.resume_token_ttl, unix_now()),
        voice: VoiceInfo {
            host: if srv.cfg.public_hostname.is_empty() {
                host
            } else {
                srv.cfg.public_hostname.clone()
            },
            port,
            connection_id: hex_u64(sess.conn_id),
            key_id,
            key: key_b64,
            key_expires_in: ttl.as_secs(),
        },
        config: SessionConfig {
            normal_range: srv.cfg.normal_range,
            whisper_range: srv.cfg.whisper_range,
            max_range: srv.cfg.max_range,
            codec: "opus",
            sample_rate: 48000,
            frame_ms: 20,
            heartbeat_interval: 5,
            position_hz_max: 10,
        },
    };
    if !send_text(&mut sink, serde_json::to_string(&msg).unwrap()).await {
        srv.unregister(&sess);
        return;
    }

    // writer task: drains the session queue, then sends the close frame
    let wsess = sess.clone();
    let writer = tokio::spawn(async move {
        let mut closed = wsess.closed_rx();
        loop {
            tokio::select! {
                m = rx.recv() => match m {
                    Some(m) => if !send_text(&mut sink, m).await { wsess.close("write_failed"); return; },
                    None => return,
                },
                _ = closed.changed() => {
                    while let Ok(m) = rx.try_recv() {
                        if !send_text(&mut sink, m).await { return; }
                    }
                    let code_ = wsess.close_code();
                    let status = if code_ == "bye" || code_ == "timeout" || code_ == "client_closed" { 1000 } else { 1008 };
                    let _ = tokio::time::timeout(Duration::from_secs(2),
                        sink.send(Message::Close(Some(CloseFrame { code: status, reason: code_.into() })))).await;
                    return;
                }
            }
        }
    });

    // key rotation task
    let rsrv = srv.clone();
    let rsess = sess.clone();
    let rotation = tokio::spawn(async move {
        let period = Duration::from_secs(rsrv.cfg.key_rotation_secs);
        let mut t = tokio::time::interval_at(tokio::time::Instant::now() + period, period);
        let mut closed = rsess.closed_rx();
        loop {
            tokio::select! {
                _ = t.tick() => rsrv.rotate_session(&rsess),
                _ = closed.changed() => return,
            }
        }
    });

    let timeout = Duration::from_secs(srv.cfg.session_timeout_secs);
    let mut ctl_bucket = TokenBucket::new(srv.cfg.rate_control_per_sec, srv.cfg.rate_control_burst);
    let mut last_pos: Option<Instant> = None;
    let (mut strikes, mut strike_window) = (0u32, Instant::now());
    let mut closed = sess.closed_rx();
    let reason: &'static str = loop {
        let frame = tokio::select! {
            r = read_text(&mut stream, timeout) => r,
            _ = closed.changed() => break "closed",
        };
        let frame = match frame {
            Ok(f) => f,
            Err(ReadErr::Timeout) => {
                sess.close("timeout");
                break "timeout";
            }
            Err(ReadErr::Closed) => {
                sess.close("client_closed");
                break "client_closed";
            }
            Err(ReadErr::Binary) => {
                sess.send(error_frame(code::BAD_MESSAGE, "binary frames are not allowed", true));
                sess.close(code::BAD_MESSAGE);
                break "binary";
            }
        };
        srv.metrics.control_messages.fetch_add(1, Relaxed);
        let now = Instant::now();
        sess.last_seen.store(unix_ms(), Relaxed);
        if now.duration_since(strike_window) > Duration::from_secs(10) {
            strikes = 0;
            strike_window = now;
        }
        if !ctl_bucket.allow(now) {
            strikes += 1;
            if strikes > 200 {
                sess.send(error_frame(code::RATE_LIMITED, "sustained control flood", true));
                sess.close(code::RATE_LIMITED);
                break "rate_limited";
            }
            sess.send(error_frame(code::RATE_LIMITED, "slow down", false));
            continue;
        }
        match parse_control(frame.as_bytes()) {
            Err(e) if e.code == code::UNKNOWN_MESSAGE => sess.send(error_frame(e.code, e.message, false)),
            Err(e) => {
                sess.send(error_frame(e.code, e.message, true));
                sess.close(e.code);
                break "bad_message";
            }
            Ok(ClientMsg::Bye) => {
                sess.close("bye");
                break "bye";
            }
            Ok(m) => srv.dispatch(&sess, m, now, &mut last_pos),
        }
    };
    srv.unregister(&sess);
    sess.close(reason);
    rotation.abort();
    let _ = tokio::time::timeout(Duration::from_secs(3), writer).await;
    info!(category = "control", session = %sess.id, reason = %if reason == "closed" { sess.close_code() } else { reason }, "session ended");
}

impl Server {
    fn dispatch(&self, sess: &Arc<Session>, msg: ClientMsg, now: Instant, last_pos: &mut Option<Instant>) {
        match msg {
            ClientMsg::Hello(_) | ClientMsg::Auth(_) | ClientMsg::Resume(_) => {
                sess.send(error_frame(code::BAD_MESSAGE, "already authenticated", false))
            }
            ClientMsg::Scope(s) => {
                let epoch = s.epoch.unwrap();
                let in_world = s.in_world.unwrap();
                let mut hub = self.hub.write().unwrap();
                let Some(ps) = hub.peers.get(&sess.conn_id) else { return };
                if ps.has_scope && epoch <= ps.peer.epoch {
                    drop(hub);
                    sess.send(error_frame(code::STALE_EPOCH, "epoch must increase", false));
                    return;
                }
                hub.leave_bucket(sess.conn_id);
                *last_pos = None; // the first position of a new epoch is always accepted
                let attested = if in_world {
                    s.attestation.as_deref().and_then(|a| {
                        let r = auth::verify_attestation(&self.cfg.attestation_keys, a, &sess.ident.uuid, unix_now());
                        if r.is_none() {
                            debug!(category = "control", "ignored invalid scope attestation");
                        }
                        r
                    })
                } else {
                    None
                };
                let ps = hub.peers.get_mut(&sess.conn_id).unwrap();
                ps.has_scope = true;
                ps.peer.epoch = epoch;
                ps.peer.in_world = in_world;
                ps.peer.pos = None;
                ps.peer.visible.clear();
                ps.peers_rev = 0;
                ps.presence.clear();
                ps.attested = attested.unwrap_or_default();
                if in_world {
                    ps.network_id = s.network_id.unwrap();
                    ps.world_id = s.world_id.unwrap();
                    let key = scope_key(&ps.network_id, &ps.attested, &ps.world_id);
                    hub.join_bucket(sess.conn_id, key);
                }
            }
            ClientMsg::Pos(p) => {
                if last_pos.is_some_and(|t| now.duration_since(t) < Duration::from_millis(90)) {
                    return; // above position_hz_max: dropped silently
                }
                *last_pos = Some(now);
                let (e, x, y, z) = (p.epoch.unwrap(), p.x.unwrap(), p.y.unwrap(), p.z.unwrap());
                let now_ms = self.now_ms();
                let mut hub = self.hub.write().unwrap();
                if let Some(ps) = hub.peers.get_mut(&sess.conn_id) {
                    if e == ps.peer.epoch && ps.peer.in_world {
                        ps.peer.pos = Some([x, y, z]);
                        ps.peer.pos_at_ms = now_ms;
                    }
                }
                drop(hub);
                if self.cfg.log_positions {
                    debug!(category = "control", epoch = e, x, y, z, "position");
                }
            }
            ClientMsg::Peers(p) => {
                let mut hub = self.hub.write().unwrap();
                if let Some(ps) = hub.peers.get_mut(&sess.conn_id) {
                    if p.epoch.unwrap() == ps.peer.epoch {
                        ps.peer.visible = p.full.unwrap().into_iter().filter(|u| *u != sess.ident.uuid).collect();
                        ps.peers_rev = p.rev.unwrap();
                    }
                }
            }
            ClientMsg::PeersDelta(d) => {
                let mut hub = self.hub.write().unwrap();
                let Some(ps) = hub.peers.get_mut(&sess.conn_id) else { return };
                if d.epoch.unwrap() != ps.peer.epoch {
                    return;
                }
                let (base, rev) = (d.base.unwrap(), d.rev.unwrap());
                if base != ps.peers_rev || rev <= base {
                    let epoch = ps.peer.epoch;
                    drop(hub);
                    sess.send(serde_json::to_string(&EpochMsg { t: "peers_resync", epoch }).unwrap());
                    return;
                }
                for u in d.remove.unwrap_or_default() {
                    ps.peer.visible.remove(&u);
                }
                for u in d.add.unwrap_or_default() {
                    if u != sess.ident.uuid && ps.peer.visible.len() < MAX_PEER_LIST {
                        ps.peer.visible.insert(u);
                    }
                }
                ps.peers_rev = rev;
            }
            ClientMsg::State(s) => {
                let mut hub = self.hub.write().unwrap();
                if let Some(ps) = hub.peers.get_mut(&sess.conn_id) {
                    ps.self_muted = s.muted.unwrap();
                    ps.peer.muted = ps.self_muted || ps.admin_muted;
                    ps.peer.deafened = s.deafened.unwrap();
                }
            }
            ClientMsg::Ping(p) => sess.send(
                serde_json::to_string(&PongMsg {
                    t: "pong",
                    nonce: p.nonce.unwrap(),
                    server_time: unix_ms(),
                })
                .unwrap(),
            ),
            ClientMsg::Bye => {}
        }
    }
}

/// Voice host announced to clients when PUBLIC_HOSTNAME is unset.
pub(crate) fn host_only(host_header: &str) -> String {
    if let Ok(sa) = host_header.parse::<SocketAddr>() {
        return sa.ip().to_string();
    }
    match host_header.rsplit_once(':') {
        Some((h, p)) if p.chars().all(|c| c.is_ascii_digit()) && !h.contains(':') => h.to_string(),
        _ => host_header.trim_start_matches('[').trim_end_matches(']').to_string(),
    }
}
