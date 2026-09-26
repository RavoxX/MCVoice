//! Normative routing decision (spec section 8), a pure function over peer
//! snapshots, verified against protocol/test-vectors/routing.json.

use std::collections::HashSet;

use crate::protocol::udp::MODE_WHISPER;

#[derive(Debug, Clone)]
pub struct RoutingConfig {
    pub normal_range: f64,
    pub whisper_range: f64,
    pub max_range: f64,
    pub distance_slack: f64,
    pub position_stale_ms: i64,
}

impl Default for RoutingConfig {
    fn default() -> Self {
        RoutingConfig {
            normal_range: 48.0,
            whisper_range: 8.0,
            max_range: 96.0,
            distance_slack: 4.0,
            position_stale_ms: 3000,
        }
    }
}

impl RoutingConfig {
    pub fn range(&self, mode: u8) -> f64 {
        let r = if mode == MODE_WHISPER {
            self.whisper_range
        } else {
            self.normal_range
        };
        r.min(self.max_range)
    }
}

#[derive(Debug, Clone, Default)]
pub struct Peer {
    pub uuid: String,
    pub authenticated: bool,
    pub udp_verified: bool,
    pub in_world: bool,
    /// Dimension key the client reported (spec 6.3).
    pub world_id: String,
    /// Companion-plugin attested scope `<network>/<subserver>`, empty if none (spec 6.3.1).
    pub attested: String,
    pub epoch: u32,
    pub pos: Option<[f64; 3]>,
    pub pos_at_ms: i64,
    pub visible: HashSet<String>,
    pub muted: bool,
    pub deafened: bool,
}

impl Peer {
    fn fresh_pos(&self, cfg: &RoutingConfig, now_ms: i64) -> Option<[f64; 3]> {
        self.pos.filter(|_| now_ms - self.pos_at_ms <= cfg.position_stale_ms)
    }
}

/// Check 5: same dimension, and the same attested sub-server when both are attested.
/// The address a player joined through (`network_id`) is deliberately not compared (spec 6.3).
pub fn compatible_scopes(s: &Peer, r: &Peer) -> bool {
    s.world_id == r.world_id && (s.attested.is_empty() || r.attested.is_empty() || s.attested == r.attested)
}

/// Checks 7-8: each side's own world tracks the other player (mandatory, spec 8).
pub fn mutually_visible(s: &Peer, r: &Peer) -> bool {
    r.visible.contains(&s.uuid) && s.visible.contains(&r.uuid)
}

/// Checks 1-3 (sender side).
pub fn sender_eligible(cfg: &RoutingConfig, now_ms: i64, s: &Peer, packet_epoch: u32) -> bool {
    s.authenticated && s.udp_verified && !s.muted && s.in_world && packet_epoch == s.epoch && s.fresh_pos(cfg, now_ms).is_some()
}

/// Checks 5-9 for one recipient.
pub fn deliver(cfg: &RoutingConfig, now_ms: i64, s: &Peer, r: &Peer, mode: u8) -> bool {
    if r.uuid == s.uuid {
        return false;
    }
    if !compatible_scopes(s, r) || !r.in_world || !r.udp_verified || !r.authenticated || r.deafened {
        return false;
    }
    let (Some(sp), Some(rp)) = (s.fresh_pos(cfg, now_ms), r.fresh_pos(cfg, now_ms)) else {
        return false;
    };
    if !mutually_visible(s, r) {
        return false;
    }
    let limit = cfg.range(mode) + cfg.distance_slack;
    let (dx, dy, dz) = (sp[0] - rp[0], sp[1] - rp[1], sp[2] - rp[2]);
    dx * dx + dy * dy + dz * dz <= limit * limit
}

pub fn route<'a>(
    cfg: &RoutingConfig,
    now_ms: i64,
    s: &Peer,
    packet_epoch: u32,
    mode: u8,
    candidates: impl IntoIterator<Item = &'a Peer>,
) -> Vec<&'a Peer> {
    if !sender_eligible(cfg, now_ms, s, packet_epoch) {
        return Vec::new();
    }
    candidates.into_iter().filter(|r| deliver(cfg, now_ms, s, r, mode)).collect()
}
