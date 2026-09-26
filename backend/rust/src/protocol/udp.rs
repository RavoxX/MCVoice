//! UDP datagram format (spec section 7).

use aes_gcm::aead::{AeadInPlace, KeyInit};
use aes_gcm::{Aes128Gcm, Nonce, Tag};

pub const MAJOR: u8 = 1;
pub const MINOR: u8 = 1;
pub const HEADER_LEN: usize = 22;
pub const TAG_LEN: usize = 16;
pub const MAX_DATAGRAM: usize = 1200;
pub const MAX_PAYLOAD: usize = 1000;
pub const KEY_LEN: usize = 16;

pub const TYPE_HELLO: u8 = 0x01;
pub const TYPE_HELLO_ACK: u8 = 0x02;
pub const TYPE_VOICE: u8 = 0x10;
pub const TYPE_VOICE_RELAY: u8 = 0x11;
pub const TYPE_PING: u8 = 0x20;
pub const TYPE_PONG: u8 = 0x21;

pub const DIR_C2S: u8 = 0x43;
pub const DIR_S2C: u8 = 0x53;

pub const CODEC_OPUS: u8 = 1;
pub const MODE_NORMAL: u8 = 0;
pub const MODE_WHISPER: u8 = 1;
/// Voice-group frame (spec 8.1): delivered to the sender's group only, relayed with this mode.
pub const MODE_GROUP: u8 = 2;
pub const FLAG_EOS: u8 = 0x01;
/// On a normal/whisper frame: also deliver to the sender's group.
pub const FLAG_GROUP: u8 = 0x02;

const VOICE_FIXED: usize = 15;
const RELAY_FIXED: usize = 35;

/// Why a datagram was rejected. `as_str` matches protocol/test-vectors/udp.json.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DecodeError {
    TooShort,
    TooLarge,
    BadMagic,
    BadVersion,
    BadFlags,
    UnknownType,
    BadCounter,
    AuthFailed,
    BadPayload,
}

impl DecodeError {
    pub fn as_str(self) -> &'static str {
        match self {
            DecodeError::TooShort => "too_short",
            DecodeError::TooLarge => "too_large",
            DecodeError::BadMagic => "bad_magic",
            DecodeError::BadVersion => "bad_version",
            DecodeError::BadFlags => "bad_flags",
            DecodeError::UnknownType => "unknown_type",
            DecodeError::BadCounter => "bad_counter",
            DecodeError::AuthFailed => "auth_failed",
            DecodeError::BadPayload => "bad_payload",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Header {
    pub ptype: u8,
    pub key_id: u8,
    pub connection_id: u64,
    pub counter: u64,
}

fn type_allowed(t: u8, dir: u8) -> bool {
    if dir == DIR_C2S {
        matches!(t, TYPE_HELLO | TYPE_VOICE | TYPE_PING)
    } else {
        matches!(t, TYPE_HELLO_ACK | TYPE_VOICE_RELAY | TYPE_PONG)
    }
}

/// Validate the cleartext header of a datagram travelling in direction `dir`.
pub fn parse_header(b: &[u8], dir: u8) -> Result<Header, DecodeError> {
    if b.len() < HEADER_LEN + TAG_LEN {
        return Err(DecodeError::TooShort);
    }
    if b.len() > MAX_DATAGRAM {
        return Err(DecodeError::TooLarge);
    }
    if b[0] != b'M' || b[1] != b'V' {
        return Err(DecodeError::BadMagic);
    }
    if b[2] != MAJOR {
        return Err(DecodeError::BadVersion);
    }
    if b[4] != 0 {
        return Err(DecodeError::BadFlags);
    }
    if !type_allowed(b[3], dir) {
        return Err(DecodeError::UnknownType);
    }
    let h = Header {
        ptype: b[3],
        key_id: b[5],
        connection_id: u64::from_be_bytes(b[6..14].try_into().unwrap()),
        counter: u64::from_be_bytes(b[14..22].try_into().unwrap()),
    };
    if h.counter == 0 {
        return Err(DecodeError::BadCounter);
    }
    Ok(h)
}

fn nonce(dir: u8, key_id: u8, counter: u64) -> [u8; 12] {
    let mut n = [0u8; 12];
    n[0] = dir;
    n[1] = key_id;
    n[4..].copy_from_slice(&counter.to_be_bytes());
    n
}

/// AES-128-GCM voice key.
pub struct VoiceKey {
    cipher: Aes128Gcm,
}

impl VoiceKey {
    pub fn new(key: &[u8]) -> Option<Self> {
        if key.len() != KEY_LEN {
            return None;
        }
        Some(VoiceKey {
            cipher: Aes128Gcm::new_from_slice(key).ok()?,
        })
    }

    /// Append a complete datagram to `out`.
    pub fn seal(&self, out: &mut Vec<u8>, dir: u8, h: &Header, plaintext: &[u8]) {
        let start = out.len();
        out.extend_from_slice(&[b'M', b'V', MAJOR, h.ptype, 0, h.key_id]);
        out.extend_from_slice(&h.connection_id.to_be_bytes());
        out.extend_from_slice(&h.counter.to_be_bytes());
        out.extend_from_slice(plaintext);
        let n = nonce(dir, h.key_id, h.counter);
        let (hdr, body) = out[start..].split_at_mut(HEADER_LEN);
        let tag = self
            .cipher
            .encrypt_in_place_detached(Nonce::from_slice(&n), hdr, body)
            .expect("AES-GCM encryption cannot fail for valid sizes");
        out.extend_from_slice(&tag);
    }

    /// Authenticate and decrypt `dg`, writing the plaintext into `out` (cleared first).
    pub fn open(&self, out: &mut Vec<u8>, dir: u8, h: &Header, dg: &[u8]) -> Result<(), DecodeError> {
        let body = &dg[HEADER_LEN..dg.len() - TAG_LEN];
        let tag = Tag::from_slice(&dg[dg.len() - TAG_LEN..]);
        out.clear();
        out.extend_from_slice(body);
        let n = nonce(dir, h.key_id, h.counter);
        self.cipher
            .decrypt_in_place_detached(Nonce::from_slice(&n), &dg[..HEADER_LEN], out, tag)
            .map_err(|_| DecodeError::AuthFailed)
    }
}

/// VOICE plaintext (client -> backend). `payload` borrows the decrypted buffer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Voice<'a> {
    pub epoch: u32,
    pub sequence: u16,
    pub timestamp: u32,
    pub codec: u8,
    pub mode: u8,
    pub flags: u8,
    pub payload: &'a [u8],
}

pub fn parse_voice(p: &[u8]) -> Result<Voice<'_>, DecodeError> {
    if p.len() < VOICE_FIXED {
        return Err(DecodeError::BadPayload);
    }
    let v = Voice {
        epoch: u32::from_be_bytes(p[0..4].try_into().unwrap()),
        sequence: u16::from_be_bytes(p[4..6].try_into().unwrap()),
        timestamp: u32::from_be_bytes(p[6..10].try_into().unwrap()),
        codec: p[10],
        mode: p[11],
        flags: p[12],
        payload: &p[VOICE_FIXED..],
    };
    let n = u16::from_be_bytes(p[13..15].try_into().unwrap()) as usize;
    if v.codec != CODEC_OPUS
        || v.mode > MODE_GROUP
        || v.flags & !(FLAG_EOS | FLAG_GROUP) != 0
        || (v.mode == MODE_GROUP && v.flags & FLAG_GROUP != 0)
        || n > MAX_PAYLOAD
    {
        return Err(DecodeError::BadPayload);
    }
    if p.len() != VOICE_FIXED + n {
        return Err(DecodeError::BadPayload);
    }
    Ok(v)
}

pub fn write_voice(out: &mut Vec<u8>, v: &Voice<'_>) {
    out.extend_from_slice(&v.epoch.to_be_bytes());
    out.extend_from_slice(&v.sequence.to_be_bytes());
    out.extend_from_slice(&v.timestamp.to_be_bytes());
    out.extend_from_slice(&[v.codec, v.mode, v.flags]);
    out.extend_from_slice(&(v.payload.len() as u16).to_be_bytes());
    out.extend_from_slice(v.payload);
}

/// Write a VOICE_RELAY plaintext; `v.epoch` is the sender epoch.
pub fn write_relay(out: &mut Vec<u8>, sender: &[u8; 16], recipient_epoch: u32, v: &Voice<'_>) {
    out.extend_from_slice(sender);
    out.extend_from_slice(&recipient_epoch.to_be_bytes());
    write_voice(out, v);
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Relay<'a> {
    pub sender: [u8; 16],
    pub recipient_epoch: u32,
    pub voice: Voice<'a>,
}

pub fn parse_relay(p: &[u8]) -> Result<Relay<'_>, DecodeError> {
    if p.len() < RELAY_FIXED {
        return Err(DecodeError::BadPayload);
    }
    Ok(Relay {
        sender: p[..16].try_into().unwrap(),
        recipient_epoch: u32::from_be_bytes(p[16..20].try_into().unwrap()),
        voice: parse_voice(&p[20..])?,
    })
}

pub fn parse_hello(p: &[u8]) -> Result<([u8; 16], u64), DecodeError> {
    if p.len() != 24 {
        return Err(DecodeError::BadPayload);
    }
    Ok((p[..16].try_into().unwrap(), u64::from_be_bytes(p[16..24].try_into().unwrap())))
}

pub fn parse_time(p: &[u8]) -> Result<u64, DecodeError> {
    if p.len() != 8 {
        return Err(DecodeError::BadPayload);
    }
    Ok(u64::from_be_bytes(p.try_into().unwrap()))
}

/// Validate the plaintext of a datagram of type `t`.
pub fn parse_plaintext(t: u8, p: &[u8]) -> Result<(), DecodeError> {
    match t {
        TYPE_HELLO => parse_hello(p).map(|_| ()),
        TYPE_HELLO_ACK | TYPE_PING | TYPE_PONG => parse_time(p).map(|_| ()),
        TYPE_VOICE => parse_voice(p).map(|_| ()),
        TYPE_VOICE_RELAY => parse_relay(p).map(|_| ()),
        _ => Err(DecodeError::UnknownType),
    }
}

/// Full receive pipeline minus session lookup (used by tests and fuzzing).
pub fn decode_datagram(key: &VoiceKey, dir: u8, dg: &[u8], scratch: &mut Vec<u8>) -> Result<Header, DecodeError> {
    let h = parse_header(dg, dir)?;
    key.open(scratch, dir, &h, dg)?;
    parse_plaintext(h.ptype, scratch)?;
    Ok(h)
}
