#!/usr/bin/env python3
"""Generate the shared MCVoice protocol test vectors.

This is an *independent* reference implementation of the wire format written
directly from protocol/specification/mcvoice-protocol-v1.md using the
`cryptography` package's AES-GCM. The Java client, Rust backend and Go backend
must all reproduce these bytes exactly (they are checked in CI).

Run:  python3 protocol/test-vectors/generate.py   (rewrites *.json in this folder)
      python3 protocol/test-vectors/generate.py --check   (fails if files differ)
"""
from __future__ import annotations

import argparse
import json
import os
import struct
import sys
import uuid

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

HERE = os.path.dirname(os.path.abspath(__file__))

MAGIC = b"MV"
VERSION = 1
T_HELLO, T_HELLO_ACK, T_VOICE, T_VOICE_RELAY, T_PING, T_PONG = 0x01, 0x02, 0x10, 0x11, 0x20, 0x21
DIR_C2S, DIR_S2C = 0x43, 0x53
HEADER_LEN = 22
TAG_LEN = 16

KEY = bytes(range(16))
KEY2 = bytes.fromhex("f0e1d2c3b4a5968778695a4b3c2d1e0f")
CONN = 0x1A2B3C4D5E6F7081

UUID_A = "069a79f4-44e9-4726-a5be-fca90e38aaf5"
UUID_B = "853c80ef-3c37-49fd-aa49-938b674adae6"
UUID_C = "61699b2e-d327-4a01-9f1e-0ea8c3f06bc6"
UUID_D = "e2b8c3d1-0f4a-4c6e-9b7d-2a1f3e5c7b90"


def header(ptype: int, key_id: int, conn: int, counter: int, flags: int = 0) -> bytes:
    return MAGIC + bytes([VERSION, ptype, flags, key_id]) + struct.pack(">QQ", conn, counter)


def nonce(direction: int, key_id: int, counter: int) -> bytes:
    return bytes([direction, key_id, 0, 0]) + struct.pack(">Q", counter)


def seal(key, direction, ptype, key_id, conn, counter, plaintext):
    h = header(ptype, key_id, conn, counter)
    return h + AESGCM(key).encrypt(nonce(direction, key_id, counter), plaintext, h)


def voice_pt(epoch, seq, ts, codec, mode, flags, payload):
    return struct.pack(">IHIBBBH", epoch, seq, ts, codec, mode, flags, len(payload)) + payload


def relay_pt(sender, r_epoch, s_epoch, seq, ts, codec, mode, flags, payload):
    return uuid.UUID(sender).bytes + struct.pack(">IIHIBBBH", r_epoch, s_epoch, seq, ts, codec, mode, flags, len(payload)) + payload


def udp_vectors():
    opus_small = bytes.fromhex("fc ff fe")  # a 3-byte Opus DTX-style frame
    opus_20 = bytes((i * 37 + 11) & 0xFF for i in range(60))
    opus_max = bytes((i * 13) & 0xFF for i in range(1000))
    valid = []

    def add(name, key, direction, ptype, key_id, counter, fields, pt):
        dg = seal(key, direction, ptype, key_id, CONN, counter, pt)
        valid.append({
            "name": name,
            "key": key.hex(),
            "direction": "c2s" if direction == DIR_C2S else "s2c",
            "type": ptype,
            "key_id": key_id,
            "connection_id": f"{CONN:016x}",
            "counter": counter,
            "fields": fields,
            "plaintext": pt.hex(),
            "datagram": dg.hex(),
        })

    ts = 123456789
    add("hello", KEY, DIR_C2S, T_HELLO, 0, 1,
        {"player_uuid": UUID_A, "client_time_ms": 1727200000123},
        uuid.UUID(UUID_A).bytes + struct.pack(">Q", 1727200000123))
    add("hello_ack", KEY, DIR_S2C, T_HELLO_ACK, 0, 1,
        {"client_time_ms": 1727200000123}, struct.pack(">Q", 1727200000123))
    add("ping", KEY, DIR_C2S, T_PING, 0, 2, {"client_time_ms": 42}, struct.pack(">Q", 42))
    add("pong", KEY, DIR_S2C, T_PONG, 0, 2, {"client_time_ms": 42}, struct.pack(">Q", 42))
    for name, epoch, seq, mode, flags, payload, counter, key, kid in [
        ("voice_normal", 7, 1000, 0, 0, opus_20, 3, KEY, 0),
        ("voice_whisper", 7, 65535, 1, 0, opus_small, 4, KEY, 0),
        ("voice_seq_wrap", 7, 0, 0, 0, opus_small, 5, KEY, 0),
        ("voice_end_of_stream_empty", 7, 1, 0, 1, b"", 6, KEY, 0),
        ("voice_max_payload", 4294967295, 12, 0, 0, opus_max, 2**40, KEY, 0),
        ("voice_rotated_key", 8, 77, 0, 0, opus_20, 1, KEY2, 1),
    ]:
        add(name, key, DIR_C2S, T_VOICE, kid, counter,
            {"epoch": epoch, "sequence": seq, "timestamp": ts, "codec": 1, "mode": mode, "flags": flags,
             "payload": payload.hex()},
            voice_pt(epoch, seq, ts, 1, mode, flags, payload))
    add("voice_relay", KEY, DIR_S2C, T_VOICE_RELAY, 0, 9,
        {"sender_uuid": UUID_B, "recipient_epoch": 3, "sender_epoch": 7, "sequence": 1000, "timestamp": ts,
         "codec": 1, "mode": 0, "flags": 0, "payload": opus_20.hex()},
        relay_pt(UUID_B, 3, 7, 1000, ts, 1, 0, 0, opus_20))
    add("voice_relay_whisper_eos", KEY, DIR_S2C, T_VOICE_RELAY, 0, 10,
        {"sender_uuid": UUID_C, "recipient_epoch": 11, "sender_epoch": 2, "sequence": 5, "timestamp": 0,
         "codec": 1, "mode": 1, "flags": 1, "payload": ""},
        relay_pt(UUID_C, 11, 2, 5, 0, 1, 1, 1, b""))

    good = bytes.fromhex(valid[4]["datagram"])  # voice_normal
    invalid = []

    def bad(name, data, error, key=KEY, direction="c2s"):
        invalid.append({"name": name, "key": key.hex(), "direction": direction, "datagram": data.hex(), "error": error})

    bad("empty", b"", "too_short")
    bad("short_header", good[:21], "too_short")
    bad("header_only_no_tag", good[:HEADER_LEN + 15], "too_short")
    bad("bad_magic", b"XV" + good[2:], "bad_magic")
    bad("bad_version", good[:2] + b"\x02" + good[3:], "bad_version")
    bad("nonzero_flags", good[:4] + b"\x01" + good[5:], "bad_flags")
    bad("unknown_type", seal(KEY, DIR_C2S, 0x7F, 0, CONN, 3, b"\x00" * 8), "unknown_type")
    bad("tampered_ciphertext", good[:30] + bytes([good[30] ^ 1]) + good[31:], "auth_failed")
    bad("tampered_header_counter", good[:21] + bytes([good[21] ^ 1]) + good[22:], "auth_failed")
    bad("wrong_key", good, "auth_failed", key=KEY2)
    bad("type_not_valid_in_direction", good, "unknown_type", direction="s2c")
    bad("s2c_nonce_on_c2s_datagram", seal(KEY, DIR_S2C, T_VOICE, 0, CONN, 3, voice_pt(1, 1, 1, 1, 0, 0, b"\x01")),
        "auth_failed")
    bad("oversize", seal(KEY, DIR_C2S, T_VOICE, 0, CONN, 3, voice_pt(1, 1, 1, 1, 0, 0, b"\x00" * 1200)), "too_large")
    bad("payload_length_mismatch", seal(KEY, DIR_C2S, T_VOICE, 0, CONN, 3,
                                        voice_pt(1, 1, 1, 1, 0, 0, b"\x01\x02\x03")[:-1]), "bad_payload")
    bad("payload_length_overclaims", seal(KEY, DIR_C2S, T_VOICE, 0, CONN, 3,
                                          struct.pack(">IHIBBBH", 1, 1, 1, 1, 0, 0, 900) + b"\x00" * 10), "bad_payload")
    bad("payload_too_long", seal(KEY, DIR_C2S, T_VOICE, 0, CONN, 3,
                                 voice_pt(1, 1, 1, 1, 0, 0, b"\x00" * 1001)), "bad_payload")
    bad("bad_codec", seal(KEY, DIR_C2S, T_VOICE, 0, CONN, 3, voice_pt(1, 1, 1, 9, 0, 0, b"\x01")), "bad_payload")
    bad("bad_mode", seal(KEY, DIR_C2S, T_VOICE, 0, CONN, 3, voice_pt(1, 1, 1, 1, 7, 0, b"\x01")), "bad_payload")
    bad("bad_voice_flags", seal(KEY, DIR_C2S, T_VOICE, 0, CONN, 3, voice_pt(1, 1, 1, 1, 0, 0x80, b"\x01")), "bad_payload")
    bad("zero_counter", seal(KEY, DIR_C2S, T_VOICE, 0, CONN, 0, voice_pt(1, 1, 1, 1, 0, 0, b"\x01")), "bad_counter")
    bad("hello_wrong_length", seal(KEY, DIR_C2S, T_HELLO, 0, CONN, 3, b"\x00" * 23), "bad_payload")
    bad("relay_truncated", seal(KEY, DIR_S2C, T_VOICE_RELAY, 0, CONN, 3, b"\x00" * 20), "bad_payload", direction="s2c")
    return {"description": "UDP datagram vectors. Implementations must encode 'valid' byte-exactly and reject 'invalid' with the given error class.",
            "header_len": HEADER_LEN, "tag_len": TAG_LEN, "max_datagram": 1200, "max_payload": 1000,
            "valid": valid, "invalid": invalid}


class Window:
    SIZE = 1024

    def __init__(self):
        self.top = 0
        self.bits = set()

    def check_and_update(self, c: int) -> bool:
        if c == 0:
            return False
        if c > self.top:
            self.top = c
            self.bits = {x for x in self.bits if self.top - x < self.SIZE}
            self.bits.add(c)
            return True
        if self.top - c >= self.SIZE:
            return False
        if c in self.bits:
            return False
        self.bits.add(c)
        return True


def replay_vectors():
    seqs = {
        "in_order": list(range(1, 11)),
        "duplicate": [1, 2, 3, 3, 2, 4],
        "reordered_within_window": [1, 5, 3, 2, 4, 5],
        "zero_rejected": [0, 1, 0],
        "jump_then_old": [1, 2000, 1000, 976, 977, 1999, 2000],
        "window_edge": [1025, 1, 2, 1024],
        "big_jump": [1, 2**40, 2**40 - 1023, 2**40 - 1024, 2**40 + 1],
        "exactly_window": [2048, 1025, 1024, 1026],
    }
    out = []
    for name, cs in seqs.items():
        w = Window()
        out.append({"name": name, "counters": cs, "accept": [w.check_and_update(c) for c in cs]})
    return {"description": "Replay window (size 1024) accept/reject sequences, applied in order.", "window": 1024, "cases": out}


def control_vectors():
    cases = [
        ("hello_ok", {"type": "hello", "protocol": {"major": 1, "minor": 0},
                      "client": {"name": "mcvoice", "version": "0.1.0", "minecraft": "1.20.1", "loader": "fabric"},
                      "capabilities": ["opus", "peers_delta"]}, None),
        ("hello_newer_minor", {"type": "hello", "protocol": {"major": 1, "minor": 9},
                               "client": {"name": "x", "version": "9", "minecraft": "26.3", "loader": "forge"},
                               "capabilities": [], "future_field": {"a": 1}}, None),
        ("hello_major_2", {"type": "hello", "protocol": {"major": 2, "minor": 0},
                           "client": {"name": "x", "version": "1", "minecraft": "1", "loader": "x"},
                           "capabilities": []}, "incompatible_protocol"),
        ("hello_missing_protocol", {"type": "hello", "client": {}, "capabilities": []}, "bad_message"),
        ("scope_ok", {"type": "scope", "epoch": 7, "in_world": True, "network_id": "n1:6b86b273ff34fce19d6b804eff5a3f57",
                      "world_id": "minecraft:overworld"}, None),
        ("scope_not_in_world", {"type": "scope", "epoch": 8, "in_world": False}, None),
        ("scope_bad_network_chars", {"type": "scope", "epoch": 7, "in_world": True, "network_id": "N1 BAD!",
                                     "world_id": "minecraft:overworld"}, "bad_message"),
        ("scope_negative_epoch", {"type": "scope", "epoch": -1, "in_world": False}, "bad_message"),
        ("scope_epoch_too_big", {"type": "scope", "epoch": 4294967296, "in_world": False}, "bad_message"),
        ("scope_fractional_epoch", {"type": "scope", "epoch": 1.5, "in_world": False}, "bad_message"),
        ("pos_ok", {"type": "pos", "epoch": 7, "x": 100.5, "y": 64, "z": -20.25}, None),
        ("pos_out_of_world", {"type": "pos", "epoch": 7, "x": 3.1e7, "y": 64, "z": 0}, "bad_message"),
        ("pos_string", {"type": "pos", "epoch": 7, "x": "1", "y": 64, "z": 0}, "bad_message"),
        ("peers_ok", {"type": "peers", "epoch": 7, "rev": 1, "full": [UUID_B, UUID_C]}, None),
        ("peers_bad_uuid", {"type": "peers", "epoch": 7, "rev": 1, "full": ["not-a-uuid"]}, "bad_message"),
        ("peers_uppercase_uuid", {"type": "peers", "epoch": 7, "rev": 1, "full": [UUID_B.upper()]}, "bad_message"),
        ("peers_too_many", {"type": "peers", "epoch": 7, "rev": 1,
                            "full": [str(uuid.UUID(int=i + 1)) for i in range(513)]}, "bad_message"),
        ("peers_delta_ok", {"type": "peers_delta", "epoch": 7, "base": 1, "rev": 2, "add": [UUID_D], "remove": [UUID_B]}, None),
        ("state_ok", {"type": "state", "muted": True, "deafened": False}, None),
        ("state_bad_type", {"type": "state", "muted": "yes", "deafened": False}, "bad_message"),
        ("ping_ok", {"type": "ping", "nonce": 42}, None),
        ("auth_mojang", {"type": "auth", "method": "mojang", "username": "Notch"}, None),
        ("auth_bad_username", {"type": "auth", "method": "mojang", "username": "no spaces allowed"}, "bad_message"),
        ("auth_offline", {"type": "auth", "method": "offline", "username": "Dev_1", "uuid": UUID_A}, None),
        ("resume", {"type": "resume", "resume_token": "a.b.c"}, None),
        ("bye", {"type": "bye"}, None),
        ("missing_type", {"epoch": 1}, "bad_message"),
        ("unknown_type", {"type": "teleport", "x": 1}, "unknown_message"),
    ]
    raw = [
        ("not_json", "{nope", "bad_message"),
        ("json_array", "[1,2]", "bad_message"),
        ("too_large", '{"type":"ping","nonce":1,"pad":"' + "x" * 65536 + '"}', "bad_message"),
    ]
    out = [{"name": n, "json": json.dumps(m, separators=(",", ":")), "error": e} for n, m, e in cases]
    out += [{"name": n, "json": s, "error": e} for n, s, e in raw]
    return {"description": "Control message parse/validate vectors: error null means the message must be accepted.",
            "max_frame": 65536, "cases": out}


def routing_vectors():
    net, net2 = "n1:6b86b273ff34fce19d6b804eff5a3f57", "n1:d4735e3a265e16eee03f59718b9b5d03"
    ow, nether = "minecraft:overworld", "minecraft:the_nether"
    cfg = {"normal_range": 48.0, "whisper_range": 8.0, "max_range": 96.0, "distance_slack": 4.0,
           "require_mutual_visibility": True, "position_stale_ms": 3000}
    now = 100000

    def s(u, pos, visible, network=net, world=ow, epoch=1, **kw):
        d = {"uuid": u, "authenticated": True, "udp_verified": True, "in_world": True,
             "network_id": network, "world_id": world, "epoch": epoch, "pos": pos, "pos_at_ms": now - 100,
             "visible": visible, "muted": False, "deafened": False}
        d.update(kw)
        return d

    A, B, C, D = UUID_A, UUID_B, UUID_C, UUID_D
    scen = [
        ("distance_10_accepted", [s(A, [0, 64, 0], [B]), s(B, [10, 64, 0], [A])], A, 1, 0, [B]),
        ("distance_60_rejected", [s(A, [0, 64, 0], [B]), s(B, [60, 64, 0], [A])], A, 1, 0, []),
        ("distance_within_slack", [s(A, [0, 64, 0], [B]), s(B, [51, 64, 0], [A])], A, 1, 0, [B]),
        ("whisper_range", [s(A, [0, 64, 0], [B, C]), s(B, [5, 64, 0], [A]), s(C, [20, 64, 0], [A])], A, 1, 1, [B]),
        ("different_dimension_same_coords", [s(A, [100, 64, 100], [B]), s(B, [100, 64, 100], [A], world=nether)], A, 1, 0, []),
        ("different_network_same_coords", [s(A, [100, 64, 100], [B]), s(B, [100, 64, 100], [A], network=net2)], A, 1, 0, []),
        ("proxy_subserver_not_visible", [s(A, [100, 64, 100], []), s(B, [100, 64, 100], [])], A, 1, 0, []),
        ("recipient_does_not_see_sender", [s(A, [0, 64, 0], [B]), s(B, [3, 64, 0], [])], A, 1, 0, []),
        ("sender_does_not_see_recipient_mutual", [s(A, [0, 64, 0], []), s(B, [3, 64, 0], [A])], A, 1, 0, []),
        ("stale_packet_epoch", [s(A, [0, 64, 0], [B], epoch=2), s(B, [3, 64, 0], [A])], A, 1, 0, []),
        ("sender_not_in_world", [s(A, [0, 64, 0], [B], in_world=False), s(B, [3, 64, 0], [A])], A, 1, 0, []),
        ("recipient_not_in_world", [s(A, [0, 64, 0], [B]), s(B, [3, 64, 0], [A], in_world=False)], A, 1, 0, []),
        ("recipient_deafened", [s(A, [0, 64, 0], [B]), s(B, [3, 64, 0], [A], deafened=True)], A, 1, 0, []),
        ("sender_muted", [s(A, [0, 64, 0], [B], muted=True), s(B, [3, 64, 0], [A])], A, 1, 0, []),
        ("recipient_no_udp", [s(A, [0, 64, 0], [B]), s(B, [3, 64, 0], [A], udp_verified=False)], A, 1, 0, []),
        ("sender_stale_position", [s(A, [0, 64, 0], [B], pos_at_ms=now - 5000), s(B, [3, 64, 0], [A])], A, 1, 0, []),
        ("recipient_stale_position", [s(A, [0, 64, 0], [B]), s(B, [3, 64, 0], [A], pos_at_ms=now - 5000)], A, 1, 0, []),
        ("recipient_no_position", [s(A, [0, 64, 0], [B]), s(B, None, [A])], A, 1, 0, []),
        ("multi_recipient", [s(A, [0, 64, 0], [B, C, D]), s(B, [10, 64, 0], [A]), s(C, [0, 64, 30], [A]),
                             s(D, [0, 64, 70], [A])], A, 1, 0, [B, C]),
        ("vertical_distance", [s(A, [0, 0, 0], [B]), s(B, [0, 60, 0], [A])], A, 1, 0, []),
        ("recipient_other_epoch_fine", [s(A, [0, 64, 0], [B]), s(B, [3, 64, 0], [A], epoch=9)], A, 1, 0, [B]),
    ]
    out = []
    for name, sessions, sender, epoch, mode, expect in scen:
        out.append({"name": name, "config": cfg, "now_ms": now, "sessions": sessions,
                    "packet": {"sender": sender, "epoch": epoch, "mode": mode}, "expect": sorted(expect)})
    nm = {"name": "non_mutual_mode_allows_one_sided",
          "config": dict(cfg, require_mutual_visibility=False), "now_ms": now,
          "sessions": [s(A, [0, 64, 0], []), s(B, [3, 64, 0], [A])],
          "packet": {"sender": A, "epoch": 1, "mode": 0}, "expect": [B]}
    out.append(nm)
    return {"description": "Backend routing decisions (spec section 8). expect = sorted recipient UUIDs.", "cases": out}


def playback_vectors():
    A, B, C = UUID_A, UUID_B, UUID_C
    ow, nether = "minecraft:overworld", "minecraft:the_nether"

    def case(name, tracked, frame, expect, local=None, muted=(), deafened=False, normal=48.0, whisper=8.0):
        return {"name": name,
                "local": local or {"in_world": True, "world": ow, "epoch": 5, "pos": [0, 64, 0]},
                "tracked": tracked, "frame": frame, "muted": list(muted), "deafened": deafened,
                "normal_range": normal, "whisper_range": whisper, "expect": expect}

    def fr(sender=B, epoch=5, mode=0):
        return {"sender": sender, "recipient_epoch": epoch, "mode": mode}

    return {"description": "Client playback validation (spec section 9). expect = accept | reject:<reason>.",
            "cases": [
                case("distance_10_range_48", [{"uuid": B, "world": ow, "pos": [10, 64, 0]}], fr(), "accept"),
                case("distance_60_range_48", [{"uuid": B, "world": ow, "pos": [60, 64, 0]}], fr(), "reject:out_of_range"),
                case("exactly_at_range", [{"uuid": B, "world": ow, "pos": [48, 64, 0]}], fr(), "accept"),
                case("same_coords_other_dimension", [{"uuid": B, "world": nether, "pos": [0, 64, 0]}], fr(), "reject:other_world"),
                case("not_tracked_same_coords", [{"uuid": C, "world": ow, "pos": [0, 64, 0]}], fr(), "reject:not_tracked"),
                case("proxy_subserver_population", [{"uuid": C, "world": ow, "pos": [1, 64, 1]}], fr(sender=B), "reject:not_tracked"),
                case("old_epoch_after_switch", [{"uuid": B, "world": ow, "pos": [1, 64, 0]}], fr(epoch=4), "reject:stale_epoch"),
                case("not_in_world", [{"uuid": B, "world": ow, "pos": [1, 64, 0]}], fr(), "reject:not_in_world",
                     local={"in_world": False, "world": ow, "epoch": 5, "pos": [0, 64, 0]}),
                case("self_audio", [{"uuid": A, "world": ow, "pos": [0, 64, 0]}], fr(sender=A), "reject:self",
                     local={"in_world": True, "world": ow, "epoch": 5, "pos": [0, 64, 0], "uuid": A}),
                case("muted_sender", [{"uuid": B, "world": ow, "pos": [1, 64, 0]}], fr(), "reject:muted", muted=[B]),
                case("deafened", [{"uuid": B, "world": ow, "pos": [1, 64, 0]}], fr(), "reject:deafened", deafened=True),
                case("whisper_in_range", [{"uuid": B, "world": ow, "pos": [5, 64, 0]}], fr(mode=1), "accept"),
                case("whisper_out_of_range", [{"uuid": B, "world": ow, "pos": [20, 64, 0]}], fr(mode=1), "reject:out_of_range"),
                case("svc_frame_no_epoch", [{"uuid": B, "world": ow, "pos": [5, 64, 0]}],
                     {"sender": B, "recipient_epoch": None, "mode": 0}, "accept"),
            ]}


def dedup_vectors():
    A, B, C = UUID_A, UUID_B, UUID_C
    # events: t(ms), ev, sender; for frames an "expect" of play/drop
    def tl(name, events):
        return {"name": name, "events": events}

    def f(t, transport, sender, expect):
        return {"t": t, "ev": transport + "_frame", "sender": sender, "expect": expect}

    cases = [
        tl("both_transports_cloud_healthy_one_stream", [
            {"t": 0, "ev": "cloud_link_up"}, {"t": 0, "ev": "presence_add", "sender": B},
            f(1000, "cloud", B, "play"), f(1005, "svc", B, "drop"),
            f(1020, "cloud", B, "play"), f(1025, "svc", B, "drop"),
            f(1040, "cloud", B, "play"), f(1045, "svc", B, "drop")]),
        tl("svc_arrives_first_is_suppressed", [
            {"t": 0, "ev": "cloud_link_up"}, {"t": 0, "ev": "presence_add", "sender": B},
            f(1000, "svc", B, "drop"), f(1010, "cloud", B, "play"), f(1020, "svc", B, "drop")]),
        tl("cloud_link_failure_falls_back_to_svc", [
            {"t": 0, "ev": "cloud_link_up"}, {"t": 0, "ev": "presence_add", "sender": B},
            f(1000, "cloud", B, "play"), f(1001, "svc", B, "drop"),
            {"t": 1010, "ev": "cloud_link_down"},
            f(1021, "svc", B, "play"), f(1041, "svc", B, "play"), f(1045, "cloud", B, "drop")]),
        tl("cloud_stall_for_one_speaker_falls_back_after_grace", [
            {"t": 0, "ev": "cloud_link_up"}, {"t": 0, "ev": "presence_add", "sender": B},
            f(1000, "svc", B, "drop"), f(1100, "svc", B, "drop"), f(1199, "svc", B, "drop"),
            f(1200, "svc", B, "play"), f(1220, "svc", B, "play"),
            f(1230, "cloud", B, "play"), f(1240, "svc", B, "drop")]),
        tl("svc_only_user", [
            {"t": 0, "ev": "cloud_link_up"},
            f(1000, "svc", C, "play"), f(1020, "svc", C, "play")]),
        tl("our_mod_only_user", [
            {"t": 0, "ev": "cloud_link_up"}, {"t": 0, "ev": "presence_add", "sender": B},
            f(1000, "cloud", B, "play"), f(1020, "cloud", B, "play")]),
        tl("cloud_frames_without_presence_still_play", [
            {"t": 0, "ev": "cloud_link_up"},
            f(1000, "cloud", B, "play")]),
        tl("independent_speakers", [
            {"t": 0, "ev": "cloud_link_up"}, {"t": 0, "ev": "presence_add", "sender": A},
            f(1000, "cloud", A, "play"), f(1001, "svc", C, "play"), f(1002, "svc", A, "drop"),
            f(1020, "cloud", A, "play"), f(1021, "svc", C, "play")]),
        tl("cloud_recovers_after_link_up", [
            {"t": 0, "ev": "cloud_link_down"}, {"t": 0, "ev": "presence_add", "sender": B},
            f(1000, "svc", B, "play"), f(1020, "svc", B, "play"),
            {"t": 1030, "ev": "cloud_link_up"},
            f(1040, "cloud", B, "play"), f(1041, "svc", B, "drop"), f(1060, "cloud", B, "play")]),
        tl("presence_removed_mid_stream_prefers_svc_if_fresh", [
            {"t": 0, "ev": "cloud_link_up"}, {"t": 0, "ev": "presence_add", "sender": B},
            f(1000, "cloud", B, "play"), f(1001, "svc", B, "drop"),
            {"t": 1010, "ev": "presence_remove", "sender": B},
            f(1021, "svc", B, "play"), f(1022, "cloud", B, "drop")]),
    ]
    return {"description": "Transport selection timelines (dedup-state-machine.md). STALL_MS=300, FALLBACK_MS=200.",
            "stall_ms": 300, "fallback_ms": 200, "cases": cases}


FILES = {
    "udp.json": udp_vectors,
    "replay.json": replay_vectors,
    "control.json": control_vectors,
    "routing.json": routing_vectors,
    "playback.json": playback_vectors,
    "dedup.json": dedup_vectors,
}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    bad = 0
    for fname, fn in FILES.items():
        text = json.dumps(fn(), indent=1) + "\n"
        path = os.path.join(HERE, fname)
        if args.check:
            with open(path, encoding="utf-8") as fh:
                if fh.read() != text:
                    print(f"{fname} is out of date", file=sys.stderr)
                    bad += 1
        else:
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(text)
            print("wrote", fname)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
