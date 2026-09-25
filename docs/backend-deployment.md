# Backend deployment

The Rust (`backend/rust`) and Go (`backend/go`) backends are drop-in
replacements for each other. They read the same environment variables,
expose the same endpoints and metrics, and pass the same conformance suite.
Choose one. The Rust image is the default in the compose and Kubernetes
examples.

## What must be reachable

| Port | Protocol | Purpose | Exposure |
|---|---|---|---|
| `CONTROL_PORT` (8080) | TCP, WebSocket `/v1/control` | control channel | public **through TLS** (`wss://`), via reverse proxy or `TLS_CERT_PATH`/`TLS_KEY_PATH` |
| `VOICE_UDP_PORT` (24455) | UDP | encrypted voice | public, direct (no proxy) |
| `/health`, `/ready` | HTTP on the control port | liveness / readiness | may be public |
| `/metrics` | HTTP on the control port | Prometheus | **internal only** |

`PUBLIC_HOSTNAME` (and `PUBLIC_VOICE_PORT` behind NAT) is the address that
clients are told to send UDP to. Clients never learn each other's IP addresses.

## Configuration

Copy `backend/rust/.env.example` (identical to the Go one) to `.env` and set at least:

* `AUTH_MODE=mojang` (the default). `offline` trusts claimed UUIDs and is for
  development only.
* `JWT_SIGNING_SECRET` and `SESSION_SECRET`: at least 32 random characters
  each (`openssl rand -base64 48`). Shorter values are rejected at startup.
  When a secret is unset, an ephemeral one is generated with a warning:
  resume tokens then stop working after every restart.
* `PUBLIC_HOSTNAME`.

**Never commit `.env`.** The repository's `.gitignore` excludes `.env` and
`.env.*` except `.env.example`, and CI fails if such a file is tracked.
Kubernetes users put the secrets into a `Secret` (see below). Other useful
settings: ranges (`NORMAL_RANGE`, `WHISPER_RANGE`, `MAX_RANGE`),
`ROUTING_REQUIRE_MUTUAL_VISIBILITY`, rate limits, `MAX_SESSIONS`, `BANS_FILE`
(`{"banned":[uuid…],"muted":[uuid…]}`, re-read every 30 s), and
`SCOPE_ATTESTATION_KEYS` for the optional companion plugin.

## Docker Compose

```sh
cd deployment/compose
cp ../../backend/rust/.env.example .env      # edit secrets and PUBLIC_HOSTNAME
docker compose up -d                          # Rust backend + Caddy (automatic TLS)
docker compose --profile go up -d backend-go  # or the Go backend (set BACKEND_SERVICE=backend-go for Caddy)
```

Caddy terminates TLS for `/v1/control`, `/health` and `/ready` and hides
`/metrics`. UDP goes straight to the backend container. Containers run
read-only, as non-root, with all capabilities dropped.

## Kubernetes

`deployment/kubernetes/mcvoice.yaml` contains:

* a ConfigMap (non-secret settings);
* a Deployment with one replica and probes on `/ready` and `/health`;
* a ClusterIP Service for control;
* a LoadBalancer Service for UDP voice;
* an Ingress for `wss://`.

Create the secret first:

```sh
kubectl create secret generic mcvoice-secrets \
  --from-literal=JWT_SIGNING_SECRET="$(openssl rand -base64 48)" \
  --from-literal=SESSION_SECRET="$(openssl rand -base64 48)"
kubectl apply -f deployment/kubernetes/mcvoice.yaml
```

Set `PUBLIC_HOSTNAME` in the ConfigMap to the name that resolves to the UDP
LoadBalancer.

## Scaling and state

Session state (positions, visibility, keys) lives in memory. All players
who should hear each other must be on the **same instance**, so keep
`replicas: 1` per voice host and scale by running more hosts (for example
one per community or region, each with its own client backend URL). One
instance handles hundreds of concurrent speakers; see `tools/load-test` for
measurements (200 clients at about 16k relayed packets/s with p99 under 5 ms
on a CI runner). `REDIS_URL`/`DATABASE_URL` are reserved and ignored in 0.1.x.

A restart drops sessions. Clients reconnect with exponential backoff and
resume using their resume token when it is still valid.

## Monitoring

Prometheus metrics (both implementations):

| Metric | Meaning |
|---|---|
| `mcvoice_build_info` | version, implementation |
| `mcvoice_connected_clients`, `mcvoice_active_voice_sessions` | gauges |
| `mcvoice_packets_received_total`, `mcvoice_packets_sent_total`, `mcvoice_packets_dropped_total{reason}` | UDP traffic and drops (replay, rate, routing, …) |
| `mcvoice_invalid_packets_total`, `mcvoice_auth_failures_total` | abuse indicators |
| `mcvoice_bytes_received_total`, `mcvoice_bytes_sent_total` | bandwidth |
| `mcvoice_control_messages_total{type}` | control traffic |
| `mcvoice_relay_latency_seconds` (histogram), `mcvoice_voice_jitter_seconds` | relay performance |

Logs are JSON (`LOG_FORMAT=json`). They contain no audio, no positions
(unless `LOG_POSITIONS=true` **and** `LOG_LEVEL=debug`), and IP addresses
only as keyed hashes.

## Behind nginx (host nginx + certbot)

The public backend `mcvoice.ravoxx.dev` runs this way: the Compose service
publishes the control port on `127.0.0.1` only and UDP 24455 publicly, and the
host's nginx terminates TLS (certificate from certbot) and proxies the
WebSocket:

```nginx
location = /v1/control {
    proxy_pass http://127.0.0.1:18455;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    # TRUST_PROXY_HEADERS=true takes the FIRST X-Forwarded-For entry:
    # overwrite it with the peer, never append ($proxy_add_x_forwarded_for is spoofable)
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_read_timeout 3600s;
}
location = /health { proxy_pass http://127.0.0.1:18455; }
location = /ready  { proxy_pass http://127.0.0.1:18455; }
location / { return 404; }   # keep /metrics internal
```

Voice never passes nginx or a CDN: the DNS name must point at the host
itself (no Cloudflare proxy), and the host firewall must accept UDP 24455
(for Docker-published ports that is the FORWARD path, not INPUT).

## Pointing clients at a backend

The client's backend URL comes from its config file
(`config/mcvoice.json`, `backendUrl`), the `MCVOICE_BACKEND_URL` environment
variable / `-Dmcvoice.backendUrl`, or the default compiled into the jar. That
default is `mcvoiceBackendUrl` in `client/gradle.properties`
(`wss://mcvoice.ravoxx.dev/v1/control`); `port.py` copies it into every
generated Minecraft build. An empty `backendUrl` in the config file means
"use the default". Production URLs must use `wss://`. The client refuses plain `ws://`
to non-loopback hosts unless `allowInsecureControl` is set (development only).

## Images

`ghcr.io/<owner>/mcvoice/voice-backend-rust` and `…/voice-backend-go`:

* `<version>` tags from releases;
* `latest` only from stable releases on `main`;
* `edge` and `sha-<commit>` for every push to `main` that changes the backend.

The images are distroless and contain only the binary.
