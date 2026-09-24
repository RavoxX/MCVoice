# syntax=docker/dockerfile:1.7
# Multi-stage build for the Rust backend. Build context: backend/rust
FROM rust:1-bookworm AS build
WORKDIR /src
# Cache dependencies separately from sources.
COPY Cargo.toml Cargo.lock ./
RUN mkdir src && echo 'fn main(){}' > src/main.rs && echo '' > src/lib.rs \
 && cargo build --release --locked \
 && rm -rf src target/release/deps/*mcvoice_backend* target/release/deps/mcvoice-backend* \
           target/release/.fingerprint/mcvoice-backend-* target/release/mcvoice-backend*
COPY src ./src
# COPY keeps the checkout's mtimes, which can be older than the stub build: force a rebuild of our crate
RUN find src -type f -exec touch {} + && cargo build --release --locked && cp target/release/mcvoice-backend /mcvoice-backend

FROM gcr.io/distroless/cc-debian12:nonroot
LABEL org.opencontainers.image.title="mcvoice-backend-rust" \
      org.opencontainers.image.description="MCVoice proximity voice backend (Rust implementation)" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.source="https://github.com/RavoxX/MCVoice"
COPY --from=build /mcvoice-backend /mcvoice-backend
USER nonroot:nonroot
EXPOSE 8080/tcp 24455/udp
ENTRYPOINT ["/mcvoice-backend"]
