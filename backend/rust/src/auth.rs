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

impl AuthError {
    /// False when the session server could not be asked: that must not count
    /// against the client's authentication-failure budget.
    pub fn is_client_fault(&self) -> bool {
        matches!(self, AuthError::Rejected)
    }
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
        let mut resp = self
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
        if resp.content_length().is_some_and(|n| n > 64 * 1024) {
            return Err(AuthError::Rejected);
        }
        let mut body = Vec::new();
        while let Some(chunk) = resp.chunk().await.map_err(|e| AuthError::Unavailable(e.to_string()))? {
            if chunk.len() > 64 * 1024 - body.len() {
                return Err(AuthError::Rejected);
            }
            body.extend_from_slice(&chunk);
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
    if s.len() != 32 || !s.is_ascii() {
        return s; // not a hex id: left as is, rejected by is_uuid (and never sliced mid-character)
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
    /// When the player last proved the account to the session server. Absent in
    /// tokens issued before 0.1.16, where `iat` is the best available bound.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    auth_time: Option<i64>,
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

/// `auth_time` is the time of the last real authentication; it is copied from
/// the presented token when a session was resumed.
pub fn issue_resume(secret: &[u8], id: &Identity, ttl_secs: u64, now_unix: i64, auth_time: i64) -> String {
    let header = B64.encode(br#"{"alg":"HS256","typ":"JWT"}"#);
    let claims = serde_json::to_vec(&ResumeClaims {
        sub: id.uuid.clone(),
        name: id.username.clone(),
        iat: now_unix,
        exp: now_unix.saturating_add(i64::try_from(ttl_secs).unwrap_or(i64::MAX)),
        typ: "resume".into(),
        auth_time: Some(auth_time),
    })
    .unwrap();
    let signing = format!("{header}.{}", B64.encode(claims));
    let sig = B64.encode(mac(secret, signing.as_bytes()));
    format!("{signing}.{sig}")
}

/// Returns the identity and the time of its last real authentication.
/// Tokens renew on every resume, so `max_lifetime_secs` bounds the whole chain.
pub fn verify_resume(secret: &[u8], token: &str, now_unix: i64, max_lifetime_secs: u64) -> Option<(Identity, i64)> {
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
    let auth_time = claims.auth_time.unwrap_or(claims.iat);
    let age = now_unix.saturating_sub(auth_time);
    if age < -300 || age >= i64::try_from(max_lifetime_secs).unwrap_or(i64::MAX) {
        return None;
    }
    Some((
        Identity {
            uuid: claims.sub,
            username: claims.name,
        },
        auth_time,
    ))
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
        let t = issue_resume(b"0123456789abcdef0123456789abcdef", &id, 60, 1000, 1000);
        assert_eq!(
            verify_resume(b"0123456789abcdef0123456789abcdef", &t, 1001, 86400),
            Some((id, 1000))
        );
        assert!(verify_resume(b"0123456789abcdef0123456789abcdef", &t, 2000, 86400).is_none());
        assert!(verify_resume(b"other-secret-other-secret-other-s", &t, 1001, 86400).is_none());
        let mut bad = t.clone();
        bad.push('x');
        assert!(verify_resume(b"0123456789abcdef0123456789abcdef", &bad, 1001, 86400).is_none());
    }

    #[test]
    fn resume_chain_cannot_outlive_the_last_real_authentication() {
        let secret = b"0123456789abcdef0123456789abcdef";
        let id = Identity {
            uuid: "069a79f4-44e9-4726-a5be-fca90e38aaf5".into(),
            username: "Notch".into(),
        };
        // Renew every 30 minutes with a one-hour token and a two-hour cap.
        let mut now = 1_000_000;
        let mut token = issue_resume(secret, &id, 3600, now, now);
        let mut renewals = 0;
        loop {
            now += 1800;
            match verify_resume(secret, &token, now, 7200) {
                Some((who, auth_time)) => {
                    assert_eq!(who, id);
                    assert_eq!(auth_time, 1_000_000, "renewal must keep the original authentication time");
                    token = issue_resume(secret, &id, 3600, now, auth_time);
                    renewals += 1;
                }
                None => break,
            }
        }
        assert_eq!(renewals, 3, "accepted at +30, +60 and +90 minutes, rejected at the two-hour cap");

        // A token without auth_time (issued before 0.1.16) is bounded by its iat.
        let header = B64.encode(br#"{"alg":"HS256","typ":"JWT"}"#);
        let claims = B64.encode(format!(
            r#"{{"sub":"{}","name":"Notch","iat":1000,"exp":999999,"typ":"resume"}}"#,
            id.uuid
        ));
        let signing = format!("{header}.{claims}");
        let legacy = format!("{signing}.{}", B64.encode(mac(secret, signing.as_bytes())));
        assert_eq!(verify_resume(secret, &legacy, 1500, 7200), Some((id, 1000)));
        assert!(verify_resume(secret, &legacy, 1000 + 7200, 7200).is_none());
    }

    #[test]
    fn only_rejections_count_against_the_client() {
        assert!(AuthError::Rejected.is_client_fault());
        assert!(!AuthError::Unavailable("session server status 503".into()).is_client_fault());
    }

    #[test]
    fn dash_uuid_never_slices_inside_a_character() {
        assert_eq!(
            dash_uuid("069A79F444E94726A5BEFCA90E38AAF5"),
            "069a79f4-44e9-4726-a5be-fca90e38aaf5"
        );
        let odd = "\u{e9}".repeat(16); // 32 bytes, 16 characters
        assert_eq!(odd.len(), 32);
        assert!(!is_uuid(&dash_uuid(&odd)));
    }
}

#[cfg(test)]
mod body_limit_tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    #[tokio::test]
    async fn profile_body_is_bounded_even_without_content_length() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut request = [0; 4096];
            let _ = stream.read(&mut request).await;
            stream
                .write_all(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
                .await
                .unwrap();
            for n in [32768, 32768, 1] {
                let mut chunk = format!("{n:x}\r\n").into_bytes();
                chunk.extend(std::iter::repeat(b'x').take(n));
                chunk.extend_from_slice(b"\r\n");
                if stream.write_all(&chunk).await.is_err() {
                    return;
                }
            }
            let _ = stream.write_all(b"0\r\n\r\n").await;
        });
        let verifier = MojangVerifier::new(&format!("http://{addr}"));
        assert!(matches!(verifier.verify("test", "test").await, Err(AuthError::Rejected)));
        server.await.unwrap();
    }
}
