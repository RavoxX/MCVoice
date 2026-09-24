package protocol

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"testing"
)

// VectorDir locates protocol/test-vectors by walking up from the working directory.
func VectorDir(t testing.TB) string {
	if d := os.Getenv("MCVOICE_TEST_VECTORS"); d != "" {
		return d
	}
	dir, _ := os.Getwd()
	for i := 0; i < 8; i++ {
		p := filepath.Join(dir, "protocol", "test-vectors")
		if st, err := os.Stat(p); err == nil && st.IsDir() {
			return p
		}
		dir = filepath.Dir(dir)
	}
	t.Fatal("protocol/test-vectors not found")
	return ""
}

func loadVectors(t *testing.T, name string, v any) {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(VectorDir(t), name))
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(b, v); err != nil {
		t.Fatal(err)
	}
}

func unhex(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

type udpVectors struct {
	Valid []struct {
		Name         string         `json:"name"`
		Key          string         `json:"key"`
		Direction    string         `json:"direction"`
		Type         byte           `json:"type"`
		KeyID        byte           `json:"key_id"`
		ConnectionID string         `json:"connection_id"`
		Counter      uint64         `json:"counter"`
		Fields       map[string]any `json:"fields"`
		Plaintext    string         `json:"plaintext"`
		Datagram     string         `json:"datagram"`
	} `json:"valid"`
	Invalid []struct {
		Name      string `json:"name"`
		Key       string `json:"key"`
		Direction string `json:"direction"`
		Datagram  string `json:"datagram"`
		Error     string `json:"error"`
	} `json:"invalid"`
}

func dirOf(s string) byte {
	if s == "c2s" {
		return DirC2S
	}
	return DirS2C
}

func TestUDPVectorsEncode(t *testing.T) {
	var vs udpVectors
	loadVectors(t, "udp.json", &vs)
	if len(vs.Valid) == 0 {
		t.Fatal("no vectors")
	}
	for _, v := range vs.Valid {
		t.Run(v.Name, func(t *testing.T) {
			a, err := NewAEAD(unhex(t, v.Key))
			if err != nil {
				t.Fatal(err)
			}
			conn, _ := strconv.ParseUint(v.ConnectionID, 16, 64)
			h := Header{Type: v.Type, KeyID: v.KeyID, ConnectionID: conn, Counter: v.Counter}
			pt := buildPlaintext(t, v.Type, v.Fields)
			if !bytes.Equal(pt, unhex(t, v.Plaintext)) {
				t.Fatalf("plaintext mismatch\n got %x\nwant %s", pt, v.Plaintext)
			}
			got := a.Seal(nil, dirOf(v.Direction), h, pt)
			if hex.EncodeToString(got) != v.Datagram {
				t.Fatalf("datagram mismatch\n got %x\nwant %s", got, v.Datagram)
			}
			// decode round trip
			dg := unhex(t, v.Datagram)
			ph, err := ParseHeader(dg, dirOf(v.Direction))
			if err != nil {
				t.Fatal(err)
			}
			if ph != h {
				t.Fatalf("header mismatch %+v vs %+v", ph, h)
			}
			out, err := a.Open(nil, dirOf(v.Direction), ph, dg)
			if err != nil {
				t.Fatal(err)
			}
			if err := ParsePlaintext(ph.Type, out); err != nil {
				t.Fatal(err)
			}
			if !bytes.Equal(out, pt) {
				t.Fatal("open mismatch")
			}
		})
	}
}

func num(m map[string]any, k string) uint64 { return uint64(m[k].(float64)) }

func buildPlaintext(t *testing.T, typ byte, f map[string]any) []byte {
	var out []byte
	switch typ {
	case TypeHello:
		u, ok := UUIDBytes(f["player_uuid"].(string))
		if !ok {
			t.Fatal("bad uuid")
		}
		out = append(out, u[:]...)
		out = append(out, be64(num(f, "client_time_ms"))...)
	case TypeHelloAck, TypePing, TypePong:
		out = be64(num(f, "client_time_ms"))
	case TypeVoice, TypeVoiceRelay:
		v := Voice{Sequence: uint16(num(f, "sequence")), Timestamp: uint32(num(f, "timestamp")),
			Codec: byte(num(f, "codec")), Mode: byte(num(f, "mode")), Flags: byte(num(f, "flags")),
			Payload: unhex(t, f["payload"].(string))}
		if typ == TypeVoice {
			v.Epoch = uint32(num(f, "epoch"))
			out = AppendVoice(nil, v)
		} else {
			v.Epoch = uint32(num(f, "sender_epoch"))
			u, _ := UUIDBytes(f["sender_uuid"].(string))
			out = AppendRelay(nil, u, uint32(num(f, "recipient_epoch")), v)
			r, err := ParseRelay(out)
			if err != nil || r.Sender != u || r.RecipientEpoch != uint32(num(f, "recipient_epoch")) {
				t.Fatal("relay round trip failed")
			}
		}
	}
	return out
}

func be64(v uint64) []byte {
	b := make([]byte, 8)
	for i := 7; i >= 0; i-- {
		b[i] = byte(v)
		v >>= 8
	}
	return b
}

// DecodeDatagram is the full receive pipeline used by the relay minus session lookup.
func decodeDatagram(key []byte, dir byte, dg []byte) error {
	h, err := ParseHeader(dg, dir)
	if err != nil {
		return err
	}
	a, _ := NewAEAD(key)
	pt, err := a.Open(nil, dir, h, dg)
	if err != nil {
		return err
	}
	return ParsePlaintext(h.Type, pt)
}

func TestUDPVectorsReject(t *testing.T) {
	var vs udpVectors
	loadVectors(t, "udp.json", &vs)
	for _, v := range vs.Invalid {
		t.Run(v.Name, func(t *testing.T) {
			err := decodeDatagram(unhex(t, v.Key), dirOf(v.Direction), unhex(t, v.Datagram))
			if err == nil {
				t.Fatalf("expected %s, got accept", v.Error)
			}
			if ClassOf(err) != v.Error {
				t.Fatalf("expected %s, got %v", v.Error, err)
			}
		})
	}
}

func TestReplayVectors(t *testing.T) {
	var vs struct {
		Cases []struct {
			Name     string   `json:"name"`
			Counters []uint64 `json:"counters"`
			Accept   []bool   `json:"accept"`
		} `json:"cases"`
	}
	loadVectors(t, "replay.json", &vs)
	for _, c := range vs.Cases {
		t.Run(c.Name, func(t *testing.T) {
			var w ReplayWindow
			for i, ctr := range c.Counters {
				if got := w.CheckAndUpdate(ctr); got != c.Accept[i] {
					t.Fatalf("counter[%d]=%d: got %v want %v", i, ctr, got, c.Accept[i])
				}
			}
		})
	}
}

func TestControlVectors(t *testing.T) {
	var vs struct {
		Cases []struct {
			Name  string  `json:"name"`
			JSON  string  `json:"json"`
			Error *string `json:"error"`
		} `json:"cases"`
	}
	loadVectors(t, "control.json", &vs)
	for _, c := range vs.Cases {
		t.Run(c.Name, func(t *testing.T) {
			_, err := ParseControl([]byte(c.JSON))
			switch {
			case c.Error == nil && err != nil:
				t.Fatalf("expected accept, got %v", err)
			case c.Error != nil && err == nil:
				t.Fatalf("expected %s, got accept", *c.Error)
			case c.Error != nil && err.Code != *c.Error:
				t.Fatalf("expected %s, got %s", *c.Error, err.Code)
			}
		})
	}
}

func FuzzParseDatagram(f *testing.F) {
	var vs udpVectors
	b, _ := os.ReadFile(filepath.Join(VectorDir(f), "udp.json"))
	_ = json.Unmarshal(b, &vs)
	for _, v := range vs.Valid {
		d, _ := hex.DecodeString(v.Datagram)
		f.Add(d)
	}
	key := make([]byte, 16)
	for i := range key {
		key[i] = byte(i)
	}
	f.Fuzz(func(t *testing.T, dg []byte) {
		_ = decodeDatagram(key, DirC2S, dg)
		_ = decodeDatagram(key, DirS2C, dg)
	})
}

func FuzzParseControl(f *testing.F) {
	f.Add([]byte(`{"type":"hello","protocol":{"major":1,"minor":0},"client":{},"capabilities":[]}`))
	f.Add([]byte(`{"type":"peers","epoch":1,"rev":1,"full":[]}`))
	f.Fuzz(func(t *testing.T, b []byte) { _, _ = ParseControl(b) })
}
