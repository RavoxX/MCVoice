package protocol

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"math"
	"regexp"
)

const (
	MaxControlFrame = 65536
	MaxPeerList     = 512
	MaxCoordinate   = 3.0e7
)

// Control error codes (spec section 5.1).
const (
	CodeIncompatibleProtocol  = "incompatible_protocol"
	CodeBadMessage            = "bad_message"
	CodeUnknownMessage        = "unknown_message"
	CodeAuthRequired          = "auth_required"
	CodeAuthFailed            = "auth_failed"
	CodeUnsupportedAuthMethod = "unsupported_auth_method"
	CodeBanned                = "banned"
	CodeRateLimited           = "rate_limited"
	CodeSessionExpired        = "session_expired"
	CodeSessionReplaced       = "session_replaced"
	CodeStaleEpoch            = "stale_epoch"
	CodeServerFull            = "server_full"
	CodeInternal              = "internal"
)

var (
	reUUID     = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
	reUsername = regexp.MustCompile(`^[A-Za-z0-9_]{1,16}$`)
	reNetwork  = regexp.MustCompile(`^[0-9a-z:._-]{1,64}$`)
	reWorld    = regexp.MustCompile(`^[0-9a-z:._/-]{1,128}$`)
)

// ControlError is returned by ParseControl.
type ControlError struct {
	Code    string
	Message string
}

func (e *ControlError) Error() string { return e.Code + ": " + e.Message }

func bad(msg string) *ControlError { return &ControlError{Code: CodeBadMessage, Message: msg} }

type VersionInfo struct {
	Major *uint32 `json:"major"`
	Minor *uint32 `json:"minor"`
}

type ClientInfo struct {
	Name      string `json:"name"`
	Version   string `json:"version"`
	Minecraft string `json:"minecraft"`
	Loader    string `json:"loader"`
}

type Hello struct {
	Protocol     *VersionInfo `json:"protocol"`
	Client       *ClientInfo  `json:"client"`
	Capabilities []string     `json:"capabilities"`
}

type Auth struct {
	Method   *string `json:"method"`
	Username *string `json:"username"`
	UUID     *string `json:"uuid"`
}

type Resume struct {
	ResumeToken *string `json:"resume_token"`
}

type Scope struct {
	Epoch       *uint32 `json:"epoch"`
	InWorld     *bool   `json:"in_world"`
	NetworkID   *string `json:"network_id"`
	WorldID     *string `json:"world_id"`
	Attestation *string `json:"attestation"`
}

type Pos struct {
	Epoch *uint32  `json:"epoch"`
	X     *float64 `json:"x"`
	Y     *float64 `json:"y"`
	Z     *float64 `json:"z"`
}

type Peers struct {
	Epoch *uint32  `json:"epoch"`
	Rev   *uint32  `json:"rev"`
	Full  []string `json:"full"`
}

type PeersDelta struct {
	Epoch  *uint32  `json:"epoch"`
	Base   *uint32  `json:"base"`
	Rev    *uint32  `json:"rev"`
	Add    []string `json:"add"`
	Remove []string `json:"remove"`
}

type State struct {
	Muted    *bool `json:"muted"`
	Deafened *bool `json:"deafened"`
}

type Ping struct {
	Nonce *uint64 `json:"nonce"`
}

type Bye struct{}

// ParseControl parses and validates one client -> backend control frame.
// It returns one of *Hello, *Auth, *Resume, *Scope, *Pos, *Peers, *PeersDelta,
// *State, *Ping, *Bye.
func ParseControl(frame []byte) (any, *ControlError) {
	if len(frame) > MaxControlFrame {
		return nil, bad("frame too large")
	}
	trimmed := bytes.TrimLeft(frame, " \t\r\n")
	if len(trimmed) == 0 || trimmed[0] != '{' {
		return nil, bad("frame must be a JSON object")
	}
	var head struct {
		Type *string `json:"type"`
	}
	if err := json.Unmarshal(frame, &head); err != nil {
		return nil, bad("invalid JSON")
	}
	if head.Type == nil {
		return nil, bad("missing type")
	}
	var msg any
	switch *head.Type {
	case "hello":
		msg = &Hello{}
	case "auth":
		msg = &Auth{}
	case "resume":
		msg = &Resume{}
	case "scope":
		msg = &Scope{}
	case "pos":
		msg = &Pos{}
	case "peers":
		msg = &Peers{}
	case "peers_delta":
		msg = &PeersDelta{}
	case "state":
		msg = &State{}
	case "ping":
		msg = &Ping{}
	case "bye":
		return &Bye{}, nil
	default:
		return nil, &ControlError{Code: CodeUnknownMessage, Message: "unknown message type"}
	}
	if err := json.Unmarshal(frame, msg); err != nil {
		return nil, bad("field has wrong type or range")
	}
	if cerr := validate(msg); cerr != nil {
		return nil, cerr
	}
	return msg, nil
}

func shortString(s string, max int) bool { return len(s) <= max }

func validate(m any) *ControlError {
	switch v := m.(type) {
	case *Hello:
		if v.Protocol == nil || v.Protocol.Major == nil || v.Protocol.Minor == nil {
			return bad("missing protocol version")
		}
		if v.Client == nil {
			return bad("missing client info")
		}
		c := v.Client
		if !shortString(c.Name, 64) || !shortString(c.Version, 64) || !shortString(c.Minecraft, 64) || !shortString(c.Loader, 64) {
			return bad("client info too long")
		}
		if len(v.Capabilities) > 64 {
			return bad("too many capabilities")
		}
		for _, cp := range v.Capabilities {
			if !shortString(cp, 64) {
				return bad("capability too long")
			}
		}
		if *v.Protocol.Major != Major {
			return &ControlError{Code: CodeIncompatibleProtocol, Message: "unsupported protocol major version"}
		}
	case *Auth:
		if v.Method == nil || !shortString(*v.Method, 32) {
			return bad("missing auth method")
		}
		if v.Username == nil || !reUsername.MatchString(*v.Username) {
			return bad("invalid username")
		}
		if v.UUID != nil && !reUUID.MatchString(*v.UUID) {
			return bad("invalid uuid")
		}
		if *v.Method == "offline" && v.UUID == nil {
			return bad("offline auth requires uuid")
		}
	case *Resume:
		if v.ResumeToken == nil || len(*v.ResumeToken) == 0 || len(*v.ResumeToken) > 4096 {
			return bad("invalid resume token")
		}
	case *Scope:
		if v.Epoch == nil || v.InWorld == nil {
			return bad("missing epoch or in_world")
		}
		if *v.InWorld {
			if v.NetworkID == nil || !reNetwork.MatchString(*v.NetworkID) {
				return bad("invalid network_id")
			}
			if v.WorldID == nil || !reWorld.MatchString(*v.WorldID) {
				return bad("invalid world_id")
			}
		}
		if v.Attestation != nil && len(*v.Attestation) > 2048 {
			return bad("attestation too long")
		}
	case *Pos:
		if v.Epoch == nil || v.X == nil || v.Y == nil || v.Z == nil {
			return bad("missing position field")
		}
		for _, c := range []float64{*v.X, *v.Y, *v.Z} {
			if math.IsNaN(c) || math.IsInf(c, 0) || math.Abs(c) > MaxCoordinate {
				return bad("coordinate out of range")
			}
		}
	case *Peers:
		if v.Epoch == nil || v.Rev == nil || v.Full == nil {
			return bad("missing peers field")
		}
		if e := validUUIDList(v.Full); e != nil {
			return e
		}
	case *PeersDelta:
		if v.Epoch == nil || v.Base == nil || v.Rev == nil {
			return bad("missing peers_delta field")
		}
		if e := validUUIDList(v.Add); e != nil {
			return e
		}
		if e := validUUIDList(v.Remove); e != nil {
			return e
		}
	case *State:
		if v.Muted == nil || v.Deafened == nil {
			return bad("missing state field")
		}
	case *Ping:
		if v.Nonce == nil {
			return bad("missing nonce")
		}
	}
	return nil
}

func validUUIDList(l []string) *ControlError {
	if len(l) > MaxPeerList {
		return bad("peer list too long")
	}
	for _, u := range l {
		if !reUUID.MatchString(u) {
			return bad("invalid uuid in peer list")
		}
	}
	return nil
}

// ValidUUID reports whether s is a canonical lowercase UUID string.
func ValidUUID(s string) bool { return reUUID.MatchString(s) }

// UUIDBytes converts a canonical UUID string to 16 bytes.
func UUIDBytes(s string) ([16]byte, bool) {
	var out [16]byte
	if !reUUID.MatchString(s) {
		return out, false
	}
	h := s[0:8] + s[9:13] + s[14:18] + s[19:23] + s[24:36]
	if _, err := hex.Decode(out[:], []byte(h)); err != nil {
		return out, false
	}
	return out, true
}

// UUIDString formats 16 bytes as a canonical UUID string.
func UUIDString(b [16]byte) string {
	h := hex.EncodeToString(b[:])
	return h[0:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:32]
}

// --- backend -> client messages ---

type ErrorMsg struct {
	Type    string `json:"type"`
	Code    string `json:"code"`
	Message string `json:"message"`
	Fatal   bool   `json:"fatal"`
}

type ServerInfo struct {
	Name           string `json:"name"`
	Version        string `json:"version"`
	Implementation string `json:"implementation"`
}

type AuthInfo struct {
	Methods   []string `json:"methods"`
	Challenge string   `json:"challenge"`
}

type Version struct {
	Major int `json:"major"`
	Minor int `json:"minor"`
}

type HelloOK struct {
	Type         string     `json:"type"`
	Protocol     Version    `json:"protocol"`
	Server       ServerInfo `json:"server"`
	Capabilities []string   `json:"capabilities"`
	Auth         AuthInfo   `json:"auth"`
}

type VoiceInfo struct {
	Host         string `json:"host"`
	Port         int    `json:"port"`
	ConnectionID string `json:"connection_id"`
	KeyID        int    `json:"key_id"`
	Key          string `json:"key"`
	KeyExpiresIn int    `json:"key_expires_in"`
}

type SessionConfig struct {
	NormalRange       float64 `json:"normal_range"`
	WhisperRange      float64 `json:"whisper_range"`
	MaxRange          float64 `json:"max_range"`
	Codec             string  `json:"codec"`
	SampleRate        int     `json:"sample_rate"`
	FrameMs           int     `json:"frame_ms"`
	HeartbeatInterval int     `json:"heartbeat_interval"`
	PositionHzMax     int     `json:"position_hz_max"`
}

type SessionMsg struct {
	Type        string        `json:"type"`
	SessionID   string        `json:"session_id"`
	PlayerUUID  string        `json:"player_uuid"`
	Username    string        `json:"username"`
	ResumeToken string        `json:"resume_token"`
	Voice       VoiceInfo     `json:"voice"`
	Config      SessionConfig `json:"config"`
}

type KeyMsg struct {
	Type         string `json:"type"`
	KeyID        int    `json:"key_id"`
	Key          string `json:"key"`
	KeyExpiresIn int    `json:"key_expires_in"`
}

type PresenceMsg struct {
	Type   string   `json:"type"`
	Epoch  uint32   `json:"epoch"`
	Add    []string `json:"add"`
	Remove []string `json:"remove"`
}

type PeersResync struct {
	Type  string `json:"type"`
	Epoch uint32 `json:"epoch"`
}

type Pong struct {
	Type       string `json:"type"`
	Nonce      uint64 `json:"nonce"`
	ServerTime int64  `json:"server_time"`
}

type Simple struct {
	Type string `json:"type"`
}

// Capabilities advertised by both backend implementations.
var ServerCapabilities = []string{"opus", "whisper", "peers_delta", "presence", "key_rotation", "scope_attestation"}
