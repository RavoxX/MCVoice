# Docker images

Multi-stage Dockerfiles live next to each backend:

* `backend/rust/Dockerfile` -> `ghcr.io/ravoxx/mcvoice/voice-backend-rust`
* `backend/go/Dockerfile` -> `ghcr.io/ravoxx/mcvoice/voice-backend-go`

Both produce a distroless, non-root image containing only the binary (no
sources, no `.env`). Configuration is passed as environment variables
(`backend/*/.env.example`). Build locally:

```sh
docker build -t mcvoice-backend-rust backend/rust
docker build -t mcvoice-backend-go backend/go
docker run --rm -p 8080:8080 -p 24455:24455/udp -e AUTH_MODE=offline mcvoice-backend-rust
```

Published tags: `<version>` for releases, `latest` only for stable releases
from `main`, `edge` / `sha-<commit>` for every push to `main`.
