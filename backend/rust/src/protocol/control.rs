//! Control channel messages (spec section 6).

use serde::{Deserialize, Serialize};

pub const MAX_CONTROL_FRAME: usize = 65536;
pub const MAX_PEER_LIST: usize = 512;
pub const MAX_COORDINATE: f64 = 3.0e7;

pub mod code {
    pub const INCOMPATIBLE_PROTOCOL: &str = "incompatible_protocol";
    pub const BAD_MESSAGE: &str = "bad_message";
    pub const UNKNOWN_MESSAGE: &str = "unknown_message";
    pub const AUTH_REQUIRED: &str = "auth_required";
    pub const AUTH_FAILED: &str = "auth_failed";
    pub const UNSUPPORTED_AUTH_METHOD: &str = "unsupported_auth_method";
    pub const BANNED: &str = "banned";
    pub const RATE_LIMITED: &str = "rate_limited";
    pub const SESSION_EXPIRED: &str = "session_expired";
    pub const SESSION_REPLACED: &str = "session_replaced";
    pub const STALE_EPOCH: &str = "stale_epoch";
    pub const SERVER_FULL: &str = "server_full";
    pub const INTERNAL: &str = "internal";
}

pub const SERVER_CAPABILITIES: &[&str] = &["opus", "whisper", "peers_delta", "presence", "key_rotation", "scope_attestation"];

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ControlError {
    pub code: &'static str,
    pub message: &'static str,
}

fn bad(message: &'static str) -> ControlError {
    ControlError {
        code: code::BAD_MESSAGE,
        message,
    }
}

#[derive(Debug, Deserialize)]
pub struct VersionInfo {
    pub major: Option<u32>,
    pub minor: Option<u32>,
}

#[derive(Debug, Deserialize, Default)]
#[serde(default)]
pub struct ClientInfo {
    pub name: String,
    pub version: String,
    pub minecraft: String,
    pub loader: String,
}

#[derive(Debug, Deserialize)]
pub struct Hello {
    pub protocol: Option<VersionInfo>,
    pub client: Option<ClientInfo>,
    #[serde(default)]
    pub capabilities: Option<Vec<String>>,
}

#[derive(Debug, Deserialize)]
pub struct Auth {
    pub method: Option<String>,
    pub username: Option<String>,
    pub uuid: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct Resume {
    pub resume_token: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct Scope {
    pub epoch: Option<u32>,
    pub in_world: Option<bool>,
    pub network_id: Option<String>,
    pub world_id: Option<String>,
    pub attestation: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct Pos {
    pub epoch: Option<u32>,
    pub x: Option<f64>,
    pub y: Option<f64>,
    pub z: Option<f64>,
}

#[derive(Debug, Deserialize)]
pub struct Peers {
    pub epoch: Option<u32>,
    pub rev: Option<u32>,
    pub full: Option<Vec<String>>,
}

#[derive(Debug, Deserialize)]
pub struct PeersDelta {
    pub epoch: Option<u32>,
    pub base: Option<u32>,
    pub rev: Option<u32>,
    pub add: Option<Vec<String>>,
    pub remove: Option<Vec<String>>,
}

#[derive(Debug, Deserialize)]
pub struct State {
    pub muted: Option<bool>,
    pub deafened: Option<bool>,
}

#[derive(Debug, Deserialize)]
pub struct Ping {
    pub nonce: Option<u64>,
}

/// A validated client -> backend control message.
#[derive(Debug)]
pub enum ClientMsg {
    Hello(Hello),
    Auth(Auth),
    Resume(Resume),
    Scope(Scope),
    Pos(Pos),
    Peers(Peers),
    PeersDelta(PeersDelta),
    State(State),
    Ping(Ping),
    Bye,
}

#[derive(Deserialize)]
struct Head {
    #[serde(rename = "type")]
    t: Option<String>,
}

fn parse_as<'a, T: Deserialize<'a>>(frame: &'a [u8]) -> Result<T, ControlError> {
    serde_json::from_slice(frame).map_err(|_| bad("field has wrong type or range"))
}

/// Parse and validate one client control frame.
pub fn parse_control(frame: &[u8]) -> Result<ClientMsg, ControlError> {
    if frame.len() > MAX_CONTROL_FRAME {
        return Err(bad("frame too large"));
    }
    match frame.iter().find(|b| !b" \t\r\n".contains(b)) {
        Some(b'{') => {}
        _ => return Err(bad("frame must be a JSON object")),
    }
    let head: Head = serde_json::from_slice(frame).map_err(|_| bad("invalid JSON"))?;
    let t = head.t.ok_or_else(|| bad("missing type"))?;
    let msg = match t.as_str() {
        "hello" => ClientMsg::Hello(parse_as(frame)?),
        "auth" => ClientMsg::Auth(parse_as(frame)?),
        "resume" => ClientMsg::Resume(parse_as(frame)?),
        "scope" => ClientMsg::Scope(parse_as(frame)?),
        "pos" => ClientMsg::Pos(parse_as(frame)?),
        "peers" => ClientMsg::Peers(parse_as(frame)?),
        "peers_delta" => ClientMsg::PeersDelta(parse_as(frame)?),
        "state" => ClientMsg::State(parse_as(frame)?),
        "ping" => ClientMsg::Ping(parse_as(frame)?),
        "bye" => return Ok(ClientMsg::Bye),
        _ => {
            return Err(ControlError {
                code: code::UNKNOWN_MESSAGE,
                message: "unknown message type",
            })
        }
    };
    validate(&msg)?;
    Ok(msg)
}

pub fn is_uuid(s: &str) -> bool {
    let b = s.as_bytes();
    b.len() == 36
        && b.iter().enumerate().all(|(i, &c)| match i {
            8 | 13 | 18 | 23 => c == b'-',
            _ => c.is_ascii_digit() || (b'a'..=b'f').contains(&c),
        })
}

fn is_username(s: &str) -> bool {
    (1..=16).contains(&s.len()) && s.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'_')
}

fn is_network(s: &str) -> bool {
    (1..=64).contains(&s.len())
        && s.bytes()
            .all(|c| c.is_ascii_digit() || c.is_ascii_lowercase() || b":._-".contains(&c))
}

fn is_world(s: &str) -> bool {
    (1..=128).contains(&s.len())
        && s.bytes()
            .all(|c| c.is_ascii_digit() || c.is_ascii_lowercase() || b":._/-".contains(&c))
}

fn uuid_list(l: &[String]) -> Result<(), ControlError> {
    if l.len() > MAX_PEER_LIST {
        return Err(bad("peer list too long"));
    }
    if l.iter().all(|u| is_uuid(u)) {
        Ok(())
    } else {
        Err(bad("invalid uuid in peer list"))
    }
}

fn validate(m: &ClientMsg) -> Result<(), ControlError> {
    match m {
        ClientMsg::Hello(h) => {
            let p = h.protocol.as_ref().ok_or_else(|| bad("missing protocol version"))?;
            let (Some(major), Some(_)) = (p.major, p.minor) else {
                return Err(bad("missing protocol version"));
            };
            let c = h.client.as_ref().ok_or_else(|| bad("missing client info"))?;
            if [&c.name, &c.version, &c.minecraft, &c.loader].iter().any(|s| s.len() > 64) {
                return Err(bad("client info too long"));
            }
            if let Some(caps) = &h.capabilities {
                if caps.len() > 64 || caps.iter().any(|c| c.len() > 64) {
                    return Err(bad("too many capabilities"));
                }
            }
            if major != u32::from(super::udp::MAJOR) {
                return Err(ControlError {
                    code: code::INCOMPATIBLE_PROTOCOL,
                    message: "unsupported protocol major version",
                });
            }
        }
        ClientMsg::Auth(a) => {
            let method = a
                .method
                .as_deref()
                .filter(|m| m.len() <= 32)
                .ok_or_else(|| bad("missing auth method"))?;
            if !a.username.as_deref().is_some_and(is_username) {
                return Err(bad("invalid username"));
            }
            if let Some(u) = &a.uuid {
                if !is_uuid(u) {
                    return Err(bad("invalid uuid"));
                }
            }
            if method == "offline" && a.uuid.is_none() {
                return Err(bad("offline auth requires uuid"));
            }
        }
        ClientMsg::Resume(r) => {
            if !r.resume_token.as_deref().is_some_and(|t| !t.is_empty() && t.len() <= 4096) {
                return Err(bad("invalid resume token"));
            }
        }
        ClientMsg::Scope(s) => {
            let (Some(_), Some(in_world)) = (s.epoch, s.in_world) else {
                return Err(bad("missing epoch or in_world"));
            };
            if in_world {
                if !s.network_id.as_deref().is_some_and(is_network) {
                    return Err(bad("invalid network_id"));
                }
                if !s.world_id.as_deref().is_some_and(is_world) {
                    return Err(bad("invalid world_id"));
                }
            }
            if s.attestation.as_ref().is_some_and(|a| a.len() > 2048) {
                return Err(bad("attestation too long"));
            }
        }
        ClientMsg::Pos(p) => {
            let (Some(_), Some(x), Some(y), Some(z)) = (p.epoch, p.x, p.y, p.z) else {
                return Err(bad("missing position field"));
            };
            if [x, y, z].iter().any(|c| !c.is_finite() || c.abs() > MAX_COORDINATE) {
                return Err(bad("coordinate out of range"));
            }
        }
        ClientMsg::Peers(p) => {
            let (Some(_), Some(_), Some(full)) = (p.epoch, p.rev, &p.full) else {
                return Err(bad("missing peers field"));
            };
            uuid_list(full)?;
        }
        ClientMsg::PeersDelta(d) => {
            if d.epoch.is_none() || d.base.is_none() || d.rev.is_none() {
                return Err(bad("missing peers_delta field"));
            }
            uuid_list(d.add.as_deref().unwrap_or(&[]))?;
            uuid_list(d.remove.as_deref().unwrap_or(&[]))?;
        }
        ClientMsg::State(s) => {
            if s.muted.is_none() || s.deafened.is_none() {
                return Err(bad("missing state field"));
            }
        }
        ClientMsg::Ping(p) => {
            if p.nonce.is_none() {
                return Err(bad("missing nonce"));
            }
        }
        ClientMsg::Bye => {}
    }
    Ok(())
}

pub fn uuid_bytes(s: &str) -> Option<[u8; 16]> {
    if !is_uuid(s) {
        return None;
    }
    let h: String = s.chars().filter(|c| *c != '-').collect();
    let mut out = [0u8; 16];
    hex::decode_to_slice(h, &mut out).ok()?;
    Some(out)
}

pub fn uuid_string(b: &[u8; 16]) -> String {
    let h = hex::encode(b);
    format!("{}-{}-{}-{}-{}", &h[0..8], &h[8..12], &h[12..16], &h[16..20], &h[20..32])
}

// --- backend -> client messages ---

#[derive(Serialize)]
pub struct ErrorMsg<'a> {
    #[serde(rename = "type")]
    pub t: &'static str,
    pub code: &'a str,
    pub message: &'a str,
    pub fatal: bool,
}

pub fn error_frame(code: &str, message: &str, fatal: bool) -> String {
    serde_json::to_string(&ErrorMsg {
        t: "error",
        code,
        message,
        fatal,
    })
    .unwrap()
}

#[derive(Serialize)]
pub struct Version {
    pub major: u32,
    pub minor: u32,
}

#[derive(Serialize)]
pub struct ServerInfo {
    pub name: &'static str,
    pub version: &'static str,
    pub implementation: &'static str,
}

#[derive(Serialize)]
pub struct AuthInfo {
    pub methods: Vec<String>,
    pub challenge: String,
}

#[derive(Serialize)]
pub struct HelloOk {
    #[serde(rename = "type")]
    pub t: &'static str,
    pub protocol: Version,
    pub server: ServerInfo,
    pub capabilities: &'static [&'static str],
    pub auth: AuthInfo,
}

#[derive(Serialize)]
pub struct VoiceInfo {
    pub host: String,
    pub port: u16,
    pub connection_id: String,
    pub key_id: u8,
    pub key: String,
    pub key_expires_in: u64,
}

#[derive(Serialize)]
pub struct SessionConfig {
    pub normal_range: f64,
    pub whisper_range: f64,
    pub max_range: f64,
    pub codec: &'static str,
    pub sample_rate: u32,
    pub frame_ms: u32,
    pub heartbeat_interval: u32,
    pub position_hz_max: u32,
}

#[derive(Serialize)]
pub struct SessionMsg {
    #[serde(rename = "type")]
    pub t: &'static str,
    pub session_id: String,
    pub player_uuid: String,
    pub username: String,
    pub resume_token: String,
    pub voice: VoiceInfo,
    pub config: SessionConfig,
}

#[derive(Serialize)]
pub struct KeyMsg {
    #[serde(rename = "type")]
    pub t: &'static str,
    pub key_id: u8,
    pub key: String,
    pub key_expires_in: u64,
}

#[derive(Serialize)]
pub struct PresenceMsg {
    #[serde(rename = "type")]
    pub t: &'static str,
    pub epoch: u32,
    pub add: Vec<String>,
    pub remove: Vec<String>,
}

#[derive(Serialize)]
pub struct EpochMsg {
    #[serde(rename = "type")]
    pub t: &'static str,
    pub epoch: u32,
}

#[derive(Serialize)]
pub struct PongMsg {
    #[serde(rename = "type")]
    pub t: &'static str,
    pub nonce: u64,
    pub server_time: i64,
}
