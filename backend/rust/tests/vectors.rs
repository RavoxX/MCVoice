//! Shared protocol test vectors (protocol/test-vectors/*.json).

use std::collections::HashSet;
use std::path::PathBuf;

use mcvoice_backend::protocol::control::{parse_control, uuid_bytes, uuid_string};
use mcvoice_backend::protocol::replay::ReplayWindow;
use mcvoice_backend::protocol::udp::*;
use mcvoice_backend::routing::{route, scope_key, Peer, RoutingConfig};
use serde_json::Value;

fn vectors(name: &str) -> Value {
    let dir = std::env::var("MCVOICE_TEST_VECTORS")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../protocol/test-vectors"));
    let text = std::fs::read_to_string(dir.join(name)).expect("vector file");
    serde_json::from_str(&text).unwrap()
}

fn unhex(s: &str) -> Vec<u8> {
    hex::decode(s).unwrap()
}

fn dir(s: &str) -> u8 {
    if s == "c2s" {
        DIR_C2S
    } else {
        DIR_S2C
    }
}

fn u(v: &Value, k: &str) -> u64 {
    v[k].as_u64().unwrap_or_else(|| panic!("missing {k}"))
}

fn build_plaintext(t: u8, f: &Value) -> Vec<u8> {
    let mut out = Vec::new();
    match t {
        TYPE_HELLO => {
            out.extend_from_slice(&uuid_bytes(f["player_uuid"].as_str().unwrap()).unwrap());
            out.extend_from_slice(&u(f, "client_time_ms").to_be_bytes());
        }
        TYPE_HELLO_ACK | TYPE_PING | TYPE_PONG => out.extend_from_slice(&u(f, "client_time_ms").to_be_bytes()),
        TYPE_VOICE | TYPE_VOICE_RELAY => {
            let payload = unhex(f["payload"].as_str().unwrap());
            let epoch = if t == TYPE_VOICE { u(f, "epoch") } else { u(f, "sender_epoch") } as u32;
            let v = Voice {
                epoch,
                sequence: u(f, "sequence") as u16,
                timestamp: u(f, "timestamp") as u32,
                codec: u(f, "codec") as u8,
                mode: u(f, "mode") as u8,
                flags: u(f, "flags") as u8,
                payload: &payload,
            };
            if t == TYPE_VOICE {
                write_voice(&mut out, &v);
            } else {
                let sender = uuid_bytes(f["sender_uuid"].as_str().unwrap()).unwrap();
                write_relay(&mut out, &sender, u(f, "recipient_epoch") as u32, &v);
                let r = parse_relay(&out).unwrap();
                assert_eq!(uuid_string(&r.sender), f["sender_uuid"].as_str().unwrap());
                assert_eq!(r.voice, v);
            }
        }
        _ => panic!("unknown type"),
    }
    out
}

#[test]
fn udp_valid_vectors_encode_byte_exact() {
    let vs = vectors("udp.json");
    let valid = vs["valid"].as_array().unwrap();
    assert!(valid.len() >= 10);
    for v in valid {
        let name = v["name"].as_str().unwrap();
        let key = VoiceKey::new(&unhex(v["key"].as_str().unwrap())).unwrap();
        let t = u(v, "type") as u8;
        let h = Header {
            ptype: t,
            key_id: u(v, "key_id") as u8,
            connection_id: u64::from_str_radix(v["connection_id"].as_str().unwrap(), 16).unwrap(),
            counter: u(v, "counter"),
        };
        let pt = build_plaintext(t, &v["fields"]);
        assert_eq!(hex::encode(&pt), v["plaintext"].as_str().unwrap(), "{name}: plaintext");
        let mut dg = Vec::new();
        key.seal(&mut dg, dir(v["direction"].as_str().unwrap()), &h, &pt);
        assert_eq!(hex::encode(&dg), v["datagram"].as_str().unwrap(), "{name}: datagram");
        let mut scratch = Vec::new();
        let got = decode_datagram(&key, dir(v["direction"].as_str().unwrap()), &dg, &mut scratch).unwrap();
        assert_eq!(got, h, "{name}: header");
        assert_eq!(scratch, pt, "{name}: open");
    }
}

#[test]
fn udp_invalid_vectors_rejected_with_class() {
    let vs = vectors("udp.json");
    for v in vs["invalid"].as_array().unwrap() {
        let name = v["name"].as_str().unwrap();
        let key = VoiceKey::new(&unhex(v["key"].as_str().unwrap())).unwrap();
        let mut scratch = Vec::new();
        let err = decode_datagram(
            &key,
            dir(v["direction"].as_str().unwrap()),
            &unhex(v["datagram"].as_str().unwrap()),
            &mut scratch,
        )
        .expect_err(name);
        assert_eq!(err.as_str(), v["error"].as_str().unwrap(), "{name}");
    }
}

#[test]
fn replay_window_vectors() {
    let vs = vectors("replay.json");
    for c in vs["cases"].as_array().unwrap() {
        let mut w = ReplayWindow::default();
        for (ctr, want) in c["counters"].as_array().unwrap().iter().zip(c["accept"].as_array().unwrap()) {
            assert_eq!(
                w.check_and_update(ctr.as_u64().unwrap()),
                want.as_bool().unwrap(),
                "{} counter {ctr}",
                c["name"]
            );
        }
    }
}

#[test]
fn control_vectors() {
    let vs = vectors("control.json");
    for c in vs["cases"].as_array().unwrap() {
        let name = c["name"].as_str().unwrap();
        let res = parse_control(c["json"].as_str().unwrap().as_bytes());
        match (c["error"].as_str(), res) {
            (None, Err(e)) => panic!("{name}: expected accept, got {e:?}"),
            (Some(want), Ok(_)) => panic!("{name}: expected {want}, got accept"),
            (Some(want), Err(e)) => assert_eq!(e.code, want, "{name}"),
            (None, Ok(_)) => {}
        }
    }
}

#[test]
fn routing_vectors() {
    let vs = vectors("routing.json");
    let cases = vs["cases"].as_array().unwrap();
    assert!(cases.len() >= 10);
    for c in cases {
        let name = c["name"].as_str().unwrap();
        let cf = &c["config"];
        let cfg = RoutingConfig {
            normal_range: cf["normal_range"].as_f64().unwrap(),
            whisper_range: cf["whisper_range"].as_f64().unwrap(),
            max_range: cf["max_range"].as_f64().unwrap(),
            distance_slack: cf["distance_slack"].as_f64().unwrap(),
            require_mutual: cf["require_mutual_visibility"].as_bool().unwrap(),
            position_stale_ms: cf["position_stale_ms"].as_i64().unwrap(),
        };
        let peers: Vec<Peer> = c["sessions"]
            .as_array()
            .unwrap()
            .iter()
            .map(|s| Peer {
                uuid: s["uuid"].as_str().unwrap().into(),
                authenticated: s["authenticated"].as_bool().unwrap(),
                udp_verified: s["udp_verified"].as_bool().unwrap(),
                in_world: s["in_world"].as_bool().unwrap(),
                scope_key: scope_key(s["network_id"].as_str().unwrap(), "", s["world_id"].as_str().unwrap()),
                epoch: s["epoch"].as_u64().unwrap() as u32,
                pos: s["pos"]
                    .as_array()
                    .map(|p| [p[0].as_f64().unwrap(), p[1].as_f64().unwrap(), p[2].as_f64().unwrap()]),
                pos_at_ms: s["pos_at_ms"].as_i64().unwrap(),
                visible: s["visible"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .map(|x| x.as_str().unwrap().to_string())
                    .collect::<HashSet<_>>(),
                muted: s["muted"].as_bool().unwrap(),
                deafened: s["deafened"].as_bool().unwrap(),
            })
            .collect();
        let pkt = &c["packet"];
        let sender = peers.iter().find(|p| p.uuid == pkt["sender"].as_str().unwrap()).unwrap();
        let mut got: Vec<String> = route(
            &cfg,
            c["now_ms"].as_i64().unwrap(),
            sender,
            pkt["epoch"].as_u64().unwrap() as u32,
            pkt["mode"].as_u64().unwrap() as u8,
            peers.iter(),
        )
        .into_iter()
        .map(|p| p.uuid.clone())
        .collect();
        got.sort();
        let want: Vec<String> = c["expect"]
            .as_array()
            .unwrap()
            .iter()
            .map(|x| x.as_str().unwrap().to_string())
            .collect();
        assert_eq!(got, want, "{name}");
    }
}

/// Deterministic fuzz: random and mutated datagrams must never panic.
#[test]
fn fuzz_datagram_parser_never_panics() {
    let vs = vectors("udp.json");
    let key = VoiceKey::new(&[0u8, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15]).unwrap();
    let seeds: Vec<Vec<u8>> = vs["valid"]
        .as_array()
        .unwrap()
        .iter()
        .map(|v| unhex(v["datagram"].as_str().unwrap()))
        .collect();
    let mut state: u64 = 0x9E3779B97F4A7C15;
    let mut next = || {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        state
    };
    let mut scratch = Vec::new();
    for i in 0..200_000 {
        let mut dg = if i % 2 == 0 {
            seeds[(next() as usize) % seeds.len()].clone()
        } else {
            (0..(next() % 1400) as usize).map(|_| next() as u8).collect()
        };
        for _ in 0..(next() % 4) {
            if !dg.is_empty() {
                let idx = (next() as usize) % dg.len();
                dg[idx] ^= next() as u8;
            }
        }
        if next() % 5 == 0 {
            dg.truncate((next() as usize) % (dg.len() + 1));
        }
        let _ = decode_datagram(&key, DIR_C2S, &dg, &mut scratch);
        let _ = decode_datagram(&key, DIR_S2C, &dg, &mut scratch);
        let _ = parse_voice(&dg);
        let _ = parse_relay(&dg);
    }
}

#[test]
fn fuzz_control_parser_never_panics() {
    let vs = vectors("control.json");
    let seeds: Vec<Vec<u8>> = vs["cases"]
        .as_array()
        .unwrap()
        .iter()
        .map(|c| c["json"].as_str().unwrap().as_bytes().to_vec())
        .collect();
    let mut state: u64 = 0xD1B54A32D192ED03;
    let mut next = || {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        state
    };
    for _ in 0..100_000 {
        let mut f = seeds[(next() as usize) % seeds.len()].clone();
        for _ in 0..(1 + next() % 3) {
            if !f.is_empty() {
                let idx = (next() as usize) % f.len();
                f[idx] = next() as u8;
            }
        }
        let _ = parse_control(&f);
    }
}
