//! Voice groups (spec 6.12): up to 15 sessions that hear each other non-positionally,
//! across worlds and Minecraft servers. State lives in the hub, under its lock.

use std::sync::Arc;
use std::time::{Duration, Instant};

use rand::Rng;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;

use super::session::Session;
use super::{Hub, Server};
use crate::protocol::control::{
    code, error_frame, GroupCreate, GroupJoin, GROUP_ID_ALPHABET, GROUP_ID_LEN, GROUP_LIST_MAX, GROUP_MAX_MEMBERS,
};

/// Out of a world for longer than this ends the membership (respawn, dimension or server switch are shorter).
pub(crate) const OUT_OF_WORLD_GRACE: Duration = Duration::from_secs(10);
const PASSWORD_FAILS_PER_MIN: u32 = 5;

pub(crate) struct Group {
    pub id: String,
    /// salt, SHA-256(salt || password); never logged, never sent
    password: Option<([u8; 16], [u8; 32])>,
    /// connection ids in join order
    pub members: Vec<u64>,
}

fn hash(salt: &[u8; 16], password: &str) -> [u8; 32] {
    let mut h = Sha256::new();
    h.update(salt);
    h.update(password.as_bytes());
    h.finalize().into()
}

impl Group {
    fn new(id: String, password: Option<&str>) -> Group {
        let password = password.map(|p| {
            let salt: [u8; 16] = rand::thread_rng().gen();
            (salt, hash(&salt, p))
        });
        Group {
            id,
            password,
            members: Vec::new(),
        }
    }

    fn password_ok(&self, given: Option<&str>) -> bool {
        match (&self.password, given) {
            (None, _) => true,
            (Some((salt, want)), Some(p)) => hash(salt, p).ct_eq(want).into(),
            (Some(_), None) => false,
        }
    }
}

impl Hub {
    fn new_group_id(&self) -> String {
        let mut rng = rand::thread_rng();
        loop {
            let id: String = (0..GROUP_ID_LEN)
                .map(|_| GROUP_ID_ALPHABET[rng.gen_range(0..GROUP_ID_ALPHABET.len())] as char)
                .collect();
            if !self.groups.contains_key(&id) {
                return id;
            }
        }
    }

    fn members_json(&self, g: &Group) -> Vec<Value> {
        g.members
            .iter()
            .filter_map(|c| self.by_conn.get(c))
            .map(|s| json!({"uuid": s.ident.uuid, "name": s.ident.username}))
            .collect()
    }

    fn queue(&mut self, conn: u64, msg: String) {
        if let Some(s) = self.by_conn.get(&conn) {
            self.outbox.push((s.clone(), msg));
        }
    }

    fn announce_members(&mut self, id: &str, except: Option<u64>) {
        let Some(g) = self.groups.get(id) else { return };
        let msg = json!({"type": "group_update", "id": g.id, "members": self.members_json(g)}).to_string();
        let targets: Vec<u64> = g.members.iter().copied().filter(|c| Some(*c) != except).collect();
        for c in targets {
            self.queue(c, msg.clone());
        }
    }

    /// Remove `conn` from its group (if any). `reason` is sent to the leaver unless None.
    pub(crate) fn leave_group(&mut self, conn: u64, reason: Option<&str>) {
        let Some(ps) = self.peers.get_mut(&conn) else { return };
        let id = std::mem::take(&mut ps.peer.group);
        if id.is_empty() {
            return;
        }
        if let Some(r) = reason {
            let msg = json!({"type": "group_left", "id": id, "reason": r}).to_string();
            self.queue(conn, msg);
        }
        let empty = match self.groups.get_mut(&id) {
            Some(g) => {
                g.members.retain(|c| *c != conn);
                g.members.is_empty()
            }
            None => false,
        };
        if empty {
            self.groups.remove(&id);
        } else {
            self.announce_members(&id, None);
        }
    }

    fn join_group(&mut self, conn: u64, id: &str) {
        if let Some(ps) = self.peers.get_mut(&conn) {
            ps.peer.group = id.to_string();
        }
        let Some(g) = self.groups.get_mut(id) else { return };
        if !g.members.contains(&conn) {
            g.members.push(conn);
        }
        let g = &self.groups[id];
        let joined = json!({"type": "group_joined", "group": {
            "id": g.id, "max": GROUP_MAX_MEMBERS, "password": g.password.is_some(), "members": self.members_json(g)
        }})
        .to_string();
        self.queue(conn, joined);
        self.announce_members(id, Some(conn));
    }

    fn group_list_json(&self, query: Option<&str>) -> String {
        let q = query.map(|q| q.to_ascii_uppercase());
        let mut l: Vec<&Group> = self
            .groups
            .values()
            .filter(|g| q.as_deref().map_or(true, |q| g.id.contains(q)))
            .collect();
        l.sort_by(|a, b| b.members.len().cmp(&a.members.len()).then_with(|| a.id.cmp(&b.id)));
        let groups: Vec<Value> = l
            .into_iter()
            .take(GROUP_LIST_MAX)
            .map(|g| json!({"id": g.id, "members": g.members.len(), "max": GROUP_MAX_MEMBERS, "password": g.password.is_some()}))
            .collect();
        json!({"type": "group_list", "groups": groups}).to_string()
    }

    /// Messages queued by group operations, to send once the hub lock is released.
    pub(crate) fn take_outbox(&mut self) -> Vec<(Arc<Session>, String)> {
        std::mem::take(&mut self.outbox)
    }
}

pub(crate) fn send_all(out: Vec<(Arc<Session>, String)>) {
    for (s, m) in out {
        s.send(m);
    }
}

impl Server {
    fn group_session_ok(&self, sess: &Session, hub: &Hub) -> bool {
        let caps = hub.peers.get(&sess.conn_id).is_some_and(|p| p.groups_cap);
        if !caps {
            sess.send(error_frame(code::UNKNOWN_MESSAGE, "groups capability not negotiated", false));
        }
        caps
    }

    pub(crate) fn group_list(&self, sess: &Session, query: Option<&str>) {
        let hub = self.hub.read().unwrap();
        if !self.group_session_ok(sess, &hub) {
            return;
        }
        let msg = hub.group_list_json(query);
        drop(hub);
        sess.send(msg);
    }

    pub(crate) fn group_create(&self, sess: &Session, m: GroupCreate) {
        let mut hub = self.hub.write().unwrap();
        if !self.group_session_ok(sess, &hub) {
            return;
        }
        if !hub.peers.get(&sess.conn_id).is_some_and(|p| p.peer.in_world) {
            drop(hub);
            sess.send(error_frame(code::NOT_IN_WORLD, "join a server first", false));
            return;
        }
        hub.leave_group(sess.conn_id, Some("replaced"));
        let id = hub.new_group_id();
        hub.groups.insert(id.clone(), Group::new(id.clone(), m.password.as_deref()));
        hub.join_group(sess.conn_id, &id);
        let out = hub.take_outbox();
        drop(hub);
        send_all(out);
    }

    pub(crate) fn group_join(&self, sess: &Session, m: GroupJoin, now: Instant) {
        let id = m.id.unwrap_or_default().to_ascii_uppercase();
        let mut hub = self.hub.write().unwrap();
        if !self.group_session_ok(sess, &hub) {
            return;
        }
        let Some(ps) = hub.peers.get_mut(&sess.conn_id) else { return };
        if !ps.peer.in_world {
            drop(hub);
            sess.send(error_frame(code::NOT_IN_WORLD, "join a server first", false));
            return;
        }
        if now.duration_since(ps.group_fail_window) > Duration::from_secs(60) {
            ps.group_fail_window = now;
            ps.group_fails = 0;
        }
        if ps.group_fails >= PASSWORD_FAILS_PER_MIN {
            drop(hub);
            sess.send(error_frame(code::RATE_LIMITED, "too many wrong group passwords", false));
            return;
        }
        let already = ps.peer.group == id;
        let err = match hub.groups.get(&id) {
            None => Some((code::GROUP_NOT_FOUND, "no such group")),
            Some(_) if already => None,
            Some(g) if g.members.len() >= GROUP_MAX_MEMBERS => Some((code::GROUP_FULL, "group is full")),
            Some(g) if !g.password_ok(m.password.as_deref()) => Some((code::GROUP_PASSWORD, "wrong or missing password")),
            Some(_) => None,
        };
        if let Some((c, msg)) = err {
            if c == code::GROUP_PASSWORD {
                if let Some(ps) = hub.peers.get_mut(&sess.conn_id) {
                    ps.group_fails += 1;
                }
            }
            drop(hub);
            sess.send(error_frame(c, msg, false));
            return;
        }
        if !already {
            hub.leave_group(sess.conn_id, Some("replaced"));
        }
        hub.join_group(sess.conn_id, &id);
        let out = hub.take_outbox();
        drop(hub);
        send_all(out);
    }

    pub(crate) fn group_leave(&self, sess: &Session) {
        let mut hub = self.hub.write().unwrap();
        if !self.group_session_ok(sess, &hub) {
            return;
        }
        hub.leave_group(sess.conn_id, Some("left"));
        let out = hub.take_outbox();
        drop(hub);
        send_all(out);
    }

    /// End memberships of sessions that have been out of a world longer than the grace period.
    pub(crate) fn group_tick(&self, now: Instant) {
        let mut hub = self.hub.write().unwrap();
        let expired: Vec<u64> = hub
            .peers
            .iter()
            .filter(|(_, p)| !p.peer.group.is_empty() && p.out_of_world_since.is_some_and(|t| now.duration_since(t) > OUT_OF_WORLD_GRACE))
            .map(|(c, _)| *c)
            .collect();
        for c in expired {
            hub.leave_group(c, Some("not_in_world"));
        }
        let out = hub.take_outbox();
        drop(hub);
        send_all(out);
    }
}
