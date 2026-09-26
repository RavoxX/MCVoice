use super::*;
use crate::auth::Identity;
use crate::config::Config;
use crate::protocol::control::{GroupCreate, GroupJoin};
use crate::routing::Peer;
use crate::server::session::{KeySlot, PeerState, TokenBucket, UdpState};
use std::collections::HashSet;
use std::time::Duration;
use tokio::sync::mpsc;

fn player(srv: &Server, id: u8) -> (Arc<Session>, mpsc::Receiver<String>) {
    let now = Instant::now();
    let (tx, rx) = mpsc::channel(128);
    let ident = Identity {
        uuid: format!("00000000-0000-4000-8000-{id:012x}"),
        username: format!("p{id}"),
    };
    let sess = Arc::new(Session::new(
        ident.clone(),
        [id; 16],
        tx,
        UdpState {
            cur: KeySlot::new(0, Duration::from_secs(600), now),
            prev: None,
            send_with_new: false,
            addr: Some(SocketAddr::from(([127, 0, 0, 1], 20000 + id as u16))),
            udp_ok_sent: true,
            voice_bucket: TokenBucket::new(75.0, 150.0),
            jitter: 0.0,
            last_transit: None,
            last_voice: None,
        },
    ));
    let ps = PeerState {
        peer: Peer {
            uuid: ident.uuid,
            authenticated: true,
            udp_verified: true,
            in_world: true,
            world_id: "world".into(),
            epoch: id as u32,
            pos: Some([0.0; 3]),
            pos_at_ms: srv.now_ms(),
            ..Default::default()
        },
        peers_rev: 0,
        presence: HashSet::new(),
        self_muted: false,
        admin_muted: false,
        has_scope: true,
        groups_cap: true,
        out_of_world_since: None,
        group_fail_window: now,
        group_fails: 0,
    };
    assert!(srv.register(sess.clone(), ps));
    (sess, rx)
}

fn visible(srv: &Server, a: &Session, b: &Session) {
    let mut hub = srv.hub.write().unwrap();
    hub.peers.get_mut(&a.conn_id).unwrap().peer.visible.insert(b.ident.uuid.clone());
    hub.peers.get_mut(&b.conn_id).unwrap().peer.visible.insert(a.ident.uuid.clone());
}

fn voice(epoch: u32) -> Voice<'static> {
    Voice {
        epoch,
        sequence: 1,
        timestamp: 960,
        codec: CODEC_OPUS,
        mode: MODE_NORMAL,
        flags: 0,
        payload: &[1, 2, 3],
    }
}

fn recycle(s: &mut Scratch) {
    for (_, dg) in s.outgoing.drain(..) {
        s.free.push(dg);
    }
}

fn relay(s: &Scratch, recipient: &Session) -> (u32, u8, u8) {
    let (_, dg) = s
        .outgoing
        .iter()
        .find(|(_, dg)| parse_header(dg, DIR_S2C).unwrap().connection_id == recipient.conn_id)
        .unwrap();
    let h = parse_header(dg, DIR_S2C).unwrap();
    let mut plain = Vec::new();
    recipient.udp.lock().unwrap().cur.key.open(&mut plain, DIR_S2C, &h, dg).unwrap();
    let r = parse_relay(&plain).unwrap();
    (r.recipient_epoch, r.voice.mode, r.voice.flags)
}

#[tokio::test]
async fn targets_reuse_capacity_without_retaining_sessions_or_stale_routes() {
    let srv = Server::new(Config::from_env().unwrap());
    let (sender, _rx) = player(&srv, 1);
    let (recipient, _rx2) = player(&srv, 9);
    visible(&srv, &sender, &recipient);
    let references = Arc::strong_count(&recipient);
    let mut scratch = Scratch::new();
    srv.relay_voice(&sender, &voice(1), &mut scratch);
    assert_eq!(relay(&scratch, &recipient), (9, MODE_NORMAL, 0));
    let capacity = scratch.targets.capacity();
    let pointer = scratch.targets.as_ptr();
    assert!(capacity > 0 && scratch.targets.is_empty());
    assert_eq!(Arc::strong_count(&recipient), references);
    recycle(&mut scratch);
    for epoch in 10..20 {
        srv.hub.write().unwrap().peers.get_mut(&recipient.conn_id).unwrap().peer.epoch = epoch;
        srv.relay_voice(&sender, &voice(1), &mut scratch);
        assert_eq!(relay(&scratch, &recipient), (epoch, MODE_NORMAL, 0));
        assert_eq!(scratch.targets.capacity(), capacity);
        assert_eq!(scratch.targets.as_ptr(), pointer);
        recycle(&mut scratch);
    }
    srv.hub
        .write()
        .unwrap()
        .peers
        .get_mut(&recipient.conn_id)
        .unwrap()
        .peer
        .visible
        .clear();
    srv.relay_voice(&sender, &voice(1), &mut scratch);
    assert!(scratch.outgoing.is_empty());
    visible(&srv, &sender, &recipient);
    srv.relay_voice(&sender, &voice(0), &mut scratch);
    assert!(scratch.outgoing.is_empty());
    assert_eq!(scratch.targets.capacity(), capacity);
    assert_eq!(Scratch::new().targets.capacity(), 0);
}

#[tokio::test]
async fn group_and_proximity_remain_disjoint_and_current() {
    let srv = Server::new(Config::from_env().unwrap());
    let (sender, _rx) = player(&srv, 1);
    let (member, _rx2) = player(&srv, 2);
    let (nearby, _rx3) = player(&srv, 3);
    visible(&srv, &sender, &member);
    visible(&srv, &sender, &nearby);
    srv.group_create(&sender, GroupCreate { password: None });
    let id = srv.hub.read().unwrap().peers[&sender.conn_id].peer.group.clone();
    srv.group_join(
        &member,
        GroupJoin {
            id: Some(id),
            password: None,
        },
        Instant::now(),
    );
    let mut scratch = Scratch::new();
    let mut v = Voice {
        flags: FLAG_GROUP | FLAG_EOS,
        ..voice(1)
    };
    srv.relay_voice(&sender, &v, &mut scratch);
    assert_eq!(scratch.outgoing.len(), 2);
    assert_eq!(relay(&scratch, &member), (2, MODE_GROUP, FLAG_EOS));
    assert_eq!(relay(&scratch, &nearby), (3, MODE_NORMAL, FLAG_GROUP | FLAG_EOS));
    recycle(&mut scratch);
    v.epoch = 0; // group delivery deliberately ignores the sender's world epoch
    srv.relay_voice(&sender, &v, &mut scratch);
    assert_eq!(scratch.outgoing.len(), 1);
    assert_eq!(relay(&scratch, &member).1, MODE_GROUP);
    recycle(&mut scratch);
    srv.group_leave(&member);
    srv.relay_voice(&sender, &v, &mut scratch);
    assert!(scratch.outgoing.is_empty());
}

#[tokio::test]
async fn presence_diffs_match_current_visibility_scope_and_verification() {
    let srv = Server::new(Config::from_env().unwrap());
    let (a, mut rx) = player(&srv, 1);
    let (b, _rx2) = player(&srv, 2);
    let (c, _rx3) = player(&srv, 3);
    visible(&srv, &a, &c);
    visible(&srv, &a, &b);
    let mut updates = Vec::new();
    srv.presence_tick(&mut updates);
    let msg: serde_json::Value = serde_json::from_str(&rx.try_recv().unwrap()).unwrap();
    assert_eq!(msg["epoch"], 1);
    assert_eq!(msg["add"], serde_json::json!([b.ident.uuid, c.ident.uuid]));
    let capacity = updates.capacity();
    srv.presence_tick(&mut updates);
    assert!(rx.try_recv().is_err());
    assert_eq!(updates.capacity(), capacity);
    {
        let mut hub = srv.hub.write().unwrap();
        hub.peers.get_mut(&b.conn_id).unwrap().peer.udp_verified = false;
        hub.peers.get_mut(&c.conn_id).unwrap().peer.world_id = "other".into();
    }
    srv.presence_tick(&mut updates);
    let msg: serde_json::Value = serde_json::from_str(&rx.try_recv().unwrap()).unwrap();
    assert_eq!(msg["remove"], serde_json::json!([b.ident.uuid, c.ident.uuid]));
    assert!(srv.hub.read().unwrap().peers[&a.conn_id].presence.is_empty());
    {
        let mut hub = srv.hub.write().unwrap();
        hub.peers.get_mut(&b.conn_id).unwrap().peer.udp_verified = true;
    }
    srv.presence_tick(&mut updates);
    assert!(rx.try_recv().is_ok());
    srv.hub.write().unwrap().peers.get_mut(&b.conn_id).unwrap().peer.visible.clear();
    srv.presence_tick(&mut updates);
    let msg: serde_json::Value = serde_json::from_str(&rx.try_recv().unwrap()).unwrap();
    assert_eq!(msg["remove"], serde_json::json!([b.ident.uuid]));
}

#[tokio::test]
async fn blocked_recipient_does_not_hold_hub_and_keeps_captured_epoch() {
    let srv = Server::new(Config::from_env().unwrap());
    let (sender, _rx) = player(&srv, 1);
    let (recipient, _rx2) = player(&srv, 9);
    visible(&srv, &sender, &recipient);
    let udp = recipient.udp.lock().unwrap();
    let references = Arc::strong_count(&recipient);
    let other = srv.clone();
    let worker = std::thread::spawn(move || {
        let mut scratch = Scratch::new();
        other.relay_voice(&sender, &voice(1), &mut scratch);
        scratch
    });
    let deadline = Instant::now() + Duration::from_secs(5);
    while Arc::strong_count(&recipient) == references {
        assert!(Instant::now() < deadline, "relay never selected recipient");
        std::thread::yield_now();
    }
    loop {
        if let Ok(mut hub) = srv.hub.try_write() {
            hub.peers.get_mut(&recipient.conn_id).unwrap().peer.epoch = 42;
            break;
        }
        assert!(Instant::now() < deadline, "Hub held while recipient UDP lock is blocked");
        std::thread::yield_now();
    }
    drop(udp);
    let scratch = worker.join().unwrap();
    assert_eq!(relay(&scratch, &recipient).0, 9);
    assert!(scratch.targets.is_empty());
}

#[tokio::test]
async fn jitter_sweep_releases_hub_before_waiting_for_udp() {
    let srv = Server::new(Config::from_env().unwrap());
    let (session, _rx) = player(&srv, 1);
    let udp = session.udp.lock().unwrap();
    let references = Arc::strong_count(&session);
    let other = srv.clone();
    let worker = std::thread::spawn(move || {
        let mut sessions = Vec::new();
        other.update_jitter_gauge(&mut sessions);
        assert!(sessions.is_empty());
    });
    let deadline = Instant::now() + Duration::from_secs(5);
    while Arc::strong_count(&session) == references {
        assert!(Instant::now() < deadline, "jitter sweep never selected session");
        std::thread::yield_now();
    }
    loop {
        if srv.hub.try_write().is_ok() {
            break;
        }
        assert!(Instant::now() < deadline, "jitter sweep holds Hub while blocked on UDP");
        std::thread::yield_now();
    }
    drop(udp);
    worker.join().unwrap();
}
