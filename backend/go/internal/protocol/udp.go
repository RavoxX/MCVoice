// Package protocol implements the MCVoice v1 wire format
// (protocol/specification/mcvoice-protocol-v1.md).
package protocol

import (
	"crypto/aes"
	"crypto/cipher"
	"encoding/binary"
	"errors"
	"fmt"
)

const (
	Major = 1
	Minor = 0

	HeaderLen   = 22
	TagLen      = 16
	MaxDatagram = 1200
	MaxPayload  = 1000
	KeyLen      = 16

	TypeHello      byte = 0x01
	TypeHelloAck   byte = 0x02
	TypeVoice      byte = 0x10
	TypeVoiceRelay byte = 0x11
	TypePing       byte = 0x20
	TypePong       byte = 0x21

	DirC2S byte = 0x43
	DirS2C byte = 0x53

	CodecOpus   byte = 1
	ModeNormal  byte = 0
	ModeWhisper byte = 1
	FlagEOS     byte = 0x01

	voiceFixed = 15
	relayFixed = 35
)

var magic = [2]byte{'M', 'V'}

// DecodeError classifies why a datagram was rejected. The class names match
// the "error" field of protocol/test-vectors/udp.json and the metric labels.
type DecodeError string

func (e DecodeError) Error() string { return string(e) }

const (
	ErrTooShort    DecodeError = "too_short"
	ErrTooLarge    DecodeError = "too_large"
	ErrBadMagic    DecodeError = "bad_magic"
	ErrBadVersion  DecodeError = "bad_version"
	ErrBadFlags    DecodeError = "bad_flags"
	ErrUnknownType DecodeError = "unknown_type"
	ErrBadCounter  DecodeError = "bad_counter"
	ErrAuthFailed  DecodeError = "auth_failed"
	ErrBadPayload  DecodeError = "bad_payload"
)

// Header is the cleartext (authenticated) datagram header.
type Header struct {
	Type         byte
	KeyID        byte
	ConnectionID uint64
	Counter      uint64
}

// ParseHeader validates the fixed header without decrypting. dir is the
// direction the datagram travels (DirC2S when the backend receives).
func ParseHeader(b []byte, dir byte) (Header, error) {
	if len(b) < HeaderLen+TagLen {
		return Header{}, ErrTooShort
	}
	if len(b) > MaxDatagram {
		return Header{}, ErrTooLarge
	}
	if b[0] != magic[0] || b[1] != magic[1] {
		return Header{}, ErrBadMagic
	}
	if b[2] != Major {
		return Header{}, ErrBadVersion
	}
	if b[4] != 0 {
		return Header{}, ErrBadFlags
	}
	t := b[3]
	if !typeAllowed(t, dir) {
		return Header{}, ErrUnknownType
	}
	h := Header{Type: t, KeyID: b[5],
		ConnectionID: binary.BigEndian.Uint64(b[6:14]),
		Counter:      binary.BigEndian.Uint64(b[14:22])}
	if h.Counter == 0 {
		return Header{}, ErrBadCounter
	}
	return h, nil
}

func typeAllowed(t, dir byte) bool {
	if dir == DirC2S {
		return t == TypeHello || t == TypeVoice || t == TypePing
	}
	return t == TypeHelloAck || t == TypeVoiceRelay || t == TypePong
}

// AEAD wraps an AES-128-GCM key.
type AEAD struct{ gcm cipher.AEAD }

func NewAEAD(key []byte) (*AEAD, error) {
	if len(key) != KeyLen {
		return nil, fmt.Errorf("voice key must be %d bytes", KeyLen)
	}
	blk, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	g, err := cipher.NewGCM(blk)
	if err != nil {
		return nil, err
	}
	return &AEAD{gcm: g}, nil
}

func nonce(dir, keyID byte, counter uint64) [12]byte {
	var n [12]byte
	n[0], n[1] = dir, keyID
	binary.BigEndian.PutUint64(n[4:], counter)
	return n
}

// Seal appends a complete datagram to dst and returns it.
func (a *AEAD) Seal(dst []byte, dir byte, h Header, plaintext []byte) []byte {
	start := len(dst)
	dst = append(dst, magic[0], magic[1], Major, h.Type, 0, h.KeyID)
	dst = binary.BigEndian.AppendUint64(dst, h.ConnectionID)
	dst = binary.BigEndian.AppendUint64(dst, h.Counter)
	n := nonce(dir, h.KeyID, h.Counter)
	hdr := dst[start : start+HeaderLen]
	return a.gcm.Seal(dst, n[:], plaintext, hdr)
}

// Open authenticates and decrypts datagram b (whose header was parsed into h)
// and appends the plaintext to dst.
func (a *AEAD) Open(dst []byte, dir byte, h Header, b []byte) ([]byte, error) {
	n := nonce(dir, h.KeyID, h.Counter)
	out, err := a.gcm.Open(dst, n[:], b[HeaderLen:], b[:HeaderLen])
	if err != nil {
		return nil, ErrAuthFailed
	}
	return out, nil
}

// Voice is the plaintext of a VOICE datagram (client -> backend).
type Voice struct {
	Epoch     uint32
	Sequence  uint16
	Timestamp uint32
	Codec     byte
	Mode      byte
	Flags     byte
	Payload   []byte // aliases the decoded buffer
}

func ParseVoice(p []byte) (Voice, error) {
	if len(p) < voiceFixed {
		return Voice{}, ErrBadPayload
	}
	v := Voice{
		Epoch:     binary.BigEndian.Uint32(p[0:4]),
		Sequence:  binary.BigEndian.Uint16(p[4:6]),
		Timestamp: binary.BigEndian.Uint32(p[6:10]),
		Codec:     p[10], Mode: p[11], Flags: p[12],
	}
	n := int(binary.BigEndian.Uint16(p[13:15]))
	if err := checkVoiceFields(v.Codec, v.Mode, v.Flags, n); err != nil {
		return Voice{}, err
	}
	if len(p) != voiceFixed+n {
		return Voice{}, ErrBadPayload
	}
	v.Payload = p[voiceFixed:]
	return v, nil
}

func checkVoiceFields(codec, mode, flags byte, n int) error {
	if codec != CodecOpus || mode > ModeWhisper || flags&^FlagEOS != 0 || n > MaxPayload {
		return ErrBadPayload
	}
	return nil
}

func AppendVoice(dst []byte, v Voice) []byte {
	dst = binary.BigEndian.AppendUint32(dst, v.Epoch)
	dst = binary.BigEndian.AppendUint16(dst, v.Sequence)
	dst = binary.BigEndian.AppendUint32(dst, v.Timestamp)
	dst = append(dst, v.Codec, v.Mode, v.Flags)
	dst = binary.BigEndian.AppendUint16(dst, uint16(len(v.Payload)))
	return append(dst, v.Payload...)
}

// Relay is the plaintext of a VOICE_RELAY datagram (backend -> client).
type Relay struct {
	Sender         [16]byte
	RecipientEpoch uint32
	Voice          Voice // Epoch holds the sender epoch
}

// AppendRelay writes a VOICE_RELAY plaintext. v.Epoch is the sender epoch.
func AppendRelay(dst []byte, sender [16]byte, recipientEpoch uint32, v Voice) []byte {
	dst = append(dst, sender[:]...)
	dst = binary.BigEndian.AppendUint32(dst, recipientEpoch)
	return AppendVoice(dst, v)
}

func ParseRelay(p []byte) (Relay, error) {
	if len(p) < relayFixed {
		return Relay{}, ErrBadPayload
	}
	var r Relay
	copy(r.Sender[:], p[:16])
	r.RecipientEpoch = binary.BigEndian.Uint32(p[16:20])
	v, err := ParseVoice(p[20:])
	if err != nil {
		return Relay{}, err
	}
	r.Voice = v
	return r, nil
}

// ParseHello returns the player UUID and client time of a HELLO plaintext.
func ParseHello(p []byte) (uuid [16]byte, clientTime uint64, err error) {
	if len(p) != 24 {
		return uuid, 0, ErrBadPayload
	}
	copy(uuid[:], p[:16])
	return uuid, binary.BigEndian.Uint64(p[16:]), nil
}

// ParseTime parses the 8-byte plaintext of HELLO_ACK / PING / PONG.
func ParseTime(p []byte) (uint64, error) {
	if len(p) != 8 {
		return 0, ErrBadPayload
	}
	return binary.BigEndian.Uint64(p), nil
}

// ParsePlaintext validates the plaintext for the given type.
func ParsePlaintext(t byte, p []byte) error {
	var err error
	switch t {
	case TypeHello:
		_, _, err = ParseHello(p)
	case TypeHelloAck, TypePing, TypePong:
		_, err = ParseTime(p)
	case TypeVoice:
		_, err = ParseVoice(p)
	case TypeVoiceRelay:
		_, err = ParseRelay(p)
	default:
		err = ErrUnknownType
	}
	return err
}

// ClassOf returns the decode error class of err, or "" if err is not a DecodeError.
func ClassOf(err error) string {
	var de DecodeError
	if errors.As(err, &de) {
		return string(de)
	}
	return ""
}
