# syntax=docker/dockerfile:1.7
# Multi-stage build for the Go backend. Build context: backend/go
FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY cmd ./cmd
COPY internal ./internal
COPY pkg ./pkg
ARG VERSION=0.1.0
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/mcvoice-backend ./cmd/mcvoice-backend

FROM gcr.io/distroless/static-debian12:nonroot
LABEL org.opencontainers.image.title="mcvoice-backend-go" \
      org.opencontainers.image.description="MCVoice proximity voice backend (Go implementation)" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.source="https://github.com/RavoxX/MCVoice"
COPY --from=build /out/mcvoice-backend /mcvoice-backend
USER nonroot:nonroot
EXPOSE 8080/tcp 24455/udp
ENTRYPOINT ["/mcvoice-backend"]
