//! Mojang session verification, HS256 resume tokens and scope attestations.

use std::collections::HashMap;
use std::time::Duration;

use base64::engine::general_purpose::URL_SAFE_NO_PAD as B64;
use base64::Engine;
use hmac::{Hmac, Mac};
use serde::{Deserialize, Serialize};
use sha2::Sha256;

use crate::protocol::control::is_uuid;

type HmacSha256 = Hmac<Sha256>;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Identity {
    pub uuid: String,
    pub username: String,
}

#[derive(Debug)]
pub enum AuthError {
    Rejected,
    Unavailable(String),
}

pub struct MojangVerifier {
    url: String,
    client: reqwest::Client,
}

impl MojangVerifier {
    pub fn new(url: &str) -> Self {
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(10))
            .user_agent("mcvoice-backend/0.1")
            .build()
            .expect("http client");
        MojangVerifier {
            url: url.to_string(),
            client,
        }
    }

    pub async fn verify(&self, username: &str, server_id: &str) -> Result<Identity, AuthError> {
        let resp = self
            .client
            .get(&self.url)
            .query(&[("username", username), ("serverId", server_id)])
            .send()
            .await
            .map_err(|e| AuthError::Unavailable(e.to_string()))?;
        match resp.status().as_u16() {
            204 => return Err(AuthError::Rejected),
            200 => {}
            s => return Err(AuthError::Unavailable(format!("session server status {s}"))),
        }
        #[derive(Deserialize)]
        struct Profile {
            id: String,
            name: String,
        }
        let body = resp.bytes().await.map_err(|e| AuthError::Unavailable(e.to_string()))?;
        if body.len() > 64 * 1024 {
            return Err(AuthError::Rejected);
        }
        let p: Profile = serde_json::from_slice(&body).map_err(|_| AuthError::Rejected)?;
        let uuid = dash_uuid(&p.id);
        if !is_uuid(&uuid) || !p.name.eq_ignore_ascii_case(username) {
            return Err(AuthError::Rejected);
        }
        Ok(Identity { uuid, username: p.name })
    }
}

pub fn dash_uuid(s: &str) -> String {
    let s = s.to_ascii_lowercase();
    if s.len() != 32 {
        return s;
    }
    format!("{}-{}-{}-{}-{}", &s[0..8], &s[8..12], &s[12..16], &s[16..20], &s[20..32])
}

#[derive(Serialize, Deserialize)]
struct ResumeClaims {
    sub: String,
    name: String,
    iat: i64,
    exp: i64,
    typ: String,
}

fn mac(secret: &[u8], data: &[u8]) -> Vec<u8> {
    let mut m = HmacSha256::new_from_slice(secret).expect("hmac accepts any key length");
    m.update(data);
    m.finalize().into_bytes().to_vec()
}

fn mac_ok(secret: &[u8], data: &[u8], sig: &[u8]) -> bool {
    let mut m = HmacSha256::new_from_slice(secret).expect("hmac accepts any key length");
    m.update(data);
    m.verify_slice(sig).is_ok()
}

pub fn issue_resume(secret: &[u8], id: &Identity, ttl_secs: u64, now_unix: i64) -> String {
    let header = B64.encode(br#"{"alg":"HS256","typ":"JWT"}"#);
    let claims = serde_json::to_vec(&ResumeClaims {
        sub: id.uuid.clone(),
        name: id.username.clone(),
        iat: now_unix,
        exp: now_unix + ttl_secs as i64,
        typ: "resume".into(),
    })
    .unwrap();
    let signing = format!("{header}.{}", B64.encode(claims));
    let sig = B64.encode(mac(secret, signing.as_bytes()));
    format!("{signing}.{sig}")
}

pub fn verify_resume(secret: &[u8], token: &str, now_unix: i64) -> Option<Identity> {
    let mut parts = token.split('.');
    let (h, c, s) = (parts.next()?, parts.next()?, parts.next()?);
    if parts.next().is_some() {
        return None;
    }
    #[derive(Deserialize)]
    struct Hdr {
        alg: String,
    }
    let hdr: Hdr = serde_json::from_slice(&B64.decode(h).ok()?).ok()?;
    if hdr.alg != "HS256" {
        return None;
    }
    let sig = B64.decode(s).ok()?;
    if !mac_ok(secret, format!("{h}.{c}").as_bytes(), &sig) {
        return None;
    }
    let claims: ResumeClaims = serde_json::from_slice(&B64.decode(c).ok()?).ok()?;
    if claims.typ != "resume" || !is_uuid(&claims.sub) || now_unix >= claims.exp {
        return None;
    }
    Some(Identity {
        uuid: claims.sub,
        username: claims.name,
    })
}

/// Verify a companion-plugin scope attestation (spec 6.3.1); returns "network/subserver".
pub fn verify_attestation(keys: &HashMap<String, Vec<u8>>, token: &str, player: &str, now_unix: i64) -> Option<String> {
    let (p, m) = token.split_once('.')?;
    let payload = B64.decode(p).ok()?;
    let sig = B64.decode(m).ok()?;
    #[derive(Deserialize)]
    struct A {
        v: i64,
        network: String,
        subserver: String,
        player: String,
        iat: i64,
    }
    let a: A = serde_json::from_slice(&payload).ok()?;
    if a.v != 1 {
        return None;
    }
    let key = keys.get(&a.network)?;
    if !mac_ok(key, &payload, &sig) || a.player != player {
        return None;
    }
    let d = now_unix - a.iat;
    if !(-300..=300).contains(&d) || a.subserver.len() > 64 {
        return None;
    }
    Some(format!("{}/{}", a.network, a.subserver))
}

/// Keyed hash of an IP address for logs (never log raw addresses).
pub fn ip_tag(secret: &[u8], ip: &str) -> String {
    hex::encode(mac(secret, ip.as_bytes()))[..12].to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resume_roundtrip_and_tamper() {
        let id = Identity {
            uuid: "069a79f4-44e9-4726-a5be-fca90e38aaf5".into(),
            username: "Notch".into(),
        };
        let t = issue_resume(b"0123456789abcdef0123456789abcdef", &id, 60, 1000);
        assert_eq!(verify_resume(b"0123456789abcdef0123456789abcdef", &t, 1001), Some(id));
        assert!(verify_resume(b"0123456789abcdef0123456789abcdef", &t, 2000).is_none());
        assert!(verify_resume(b"other-secret-other-secret-other-s", &t, 1001).is_none());
        let mut bad = t.clone();
        bad.push('x');
        assert!(verify_resume(b"0123456789abcdef0123456789abcdef", &bad, 1001).is_none());
    }
}
