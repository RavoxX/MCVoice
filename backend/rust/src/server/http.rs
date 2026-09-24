use std::net::SocketAddr;
use std::sync::atomic::Ordering::Relaxed;
use std::sync::Arc;
use std::time::{Duration, Instant};

use axum::extract::connect_info::Connected;
use axum::extract::ws::WebSocketUpgrade;
use axum::extract::{ConnectInfo, State};
use axum::http::{header, HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::serve::IncomingStream;
use axum::Router;
use tokio::net::{TcpListener, UdpSocket};
use tracing::{info, warn};

use super::control::{handle_socket, host_only};
use super::Server;
use crate::protocol::control::MAX_CONTROL_FRAME;

/// Remote address of a control connection, for both plain TCP and TLS listeners.
#[derive(Clone, Copy, Debug)]
pub struct ClientAddr(pub SocketAddr);

impl Connected<IncomingStream<'_, TcpListener>> for ClientAddr {
    fn connect_info(s: IncomingStream<'_, TcpListener>) -> Self {
        ClientAddr(*s.remote_addr())
    }
}

impl Connected<IncomingStream<'_, super::tls::TlsListener>> for ClientAddr {
    fn connect_info(s: IncomingStream<'_, super::tls::TlsListener>) -> Self {
        ClientAddr(*s.remote_addr())
    }
}

async fn control(
    State(srv): State<Arc<Server>>,
    ConnectInfo(ClientAddr(peer)): ConnectInfo<ClientAddr>,
    headers: HeaderMap,
    ws: WebSocketUpgrade,
) -> Response {
    let ip = if srv.cfg.trust_proxy_headers {
        headers
            .get("x-forwarded-for")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.split(',').next())
            .map(|s| s.trim().to_string())
            .unwrap_or_else(|| peer.ip().to_string())
    } else {
        peer.ip().to_string()
    };
    if srv.shutdown.load(Relaxed) {
        return (StatusCode::SERVICE_UNAVAILABLE, "shutting down").into_response();
    }
    let now = Instant::now();
    if !srv.connect_limiter.allow(&ip, now) || srv.auth_fail_limiter.blocked(&ip, now) {
        return (StatusCode::TOO_MANY_REQUESTS, "rate limited").into_response();
    }
    let host = headers
        .get(header::HOST)
        .and_then(|h| h.to_str().ok())
        .map(host_only)
        .unwrap_or_default();
    ws.max_message_size(MAX_CONTROL_FRAME + 1024)
        .max_frame_size(MAX_CONTROL_FRAME + 1024)
        .on_upgrade(move |socket| handle_socket(srv, socket, ip, host))
}

fn json(status: StatusCode, body: &'static str) -> Response {
    (status, [(header::CONTENT_TYPE, "application/json")], body).into_response()
}

pub fn router(srv: Arc<Server>) -> Router {
    Router::new()
        .route("/v1/control", get(control))
        .route("/health", get(|| async { json(StatusCode::OK, r#"{"status":"ok"}"#) }))
        .route(
            "/ready",
            get(|State(s): State<Arc<Server>>| async move {
                if s.ready.load(Relaxed) && !s.shutdown.load(Relaxed) {
                    json(StatusCode::OK, r#"{"status":"ready"}"#)
                } else {
                    json(StatusCode::SERVICE_UNAVAILABLE, r#"{"status":"not_ready"}"#)
                }
            }),
        )
        .route(
            "/metrics",
            get(|State(s): State<Arc<Server>>| async move {
                (
                    [(header::CONTENT_TYPE, "text/plain; version=0.0.4; charset=utf-8")],
                    s.metrics.render(super::VERSION),
                )
            }),
        )
        .route("/", get(|| async { format!("mcvoice-backend (rust) {}\n", super::VERSION) }))
        .with_state(srv)
}

/// Bind listeners and serve until `shutdown` resolves.
pub async fn run(srv: Arc<Server>, shutdown: impl std::future::Future<Output = ()> + Send + 'static) -> std::io::Result<()> {
    for w in &srv.cfg.warnings {
        warn!(category = "config", "{w}");
    }
    srv.reload_bans();
    let udp = Arc::new(UdpSocket::bind((srv.cfg.voice_bind_address.as_str(), srv.cfg.voice_udp_port)).await?);
    let _ = srv.udp.set(udp.clone());
    let tcp = TcpListener::bind((srv.cfg.control_bind_address.as_str(), srv.cfg.control_port)).await?;
    let ctl_addr = tcp.local_addr()?;

    let workers = if srv.cfg.udp_workers == 0 {
        std::thread::available_parallelism().map(|n| n.get()).unwrap_or(2)
    } else {
        srv.cfg.udp_workers
    };
    let mut tasks = Vec::new();
    for _ in 0..workers {
        tasks.push(tokio::spawn(super::udp::worker(srv.clone(), udp.clone())));
    }
    let bg = srv.clone();
    tasks.push(tokio::spawn(async move {
        let mut presence = tokio::time::interval(Duration::from_millis(500));
        let mut bans = tokio::time::interval(Duration::from_secs(30));
        let mut jitter = tokio::time::interval(Duration::from_secs(5));
        loop {
            tokio::select! {
                _ = presence.tick() => bg.presence_tick(),
                _ = bans.tick() => bg.reload_bans(),
                _ = jitter.tick() => bg.update_jitter_gauge(),
            }
        }
    }));

    let app = router(srv.clone()).into_make_service_with_connect_info::<ClientAddr>();
    srv.ready.store(true, Relaxed);
    info!(category = "control", implementation = "rust", version = super::VERSION, control = %ctl_addr,
          voice_udp = %udp.local_addr()?, tls = !srv.cfg.tls_cert_path.is_empty(), auth_mode = %srv.cfg.auth_mode, "backend started");

    let ssrv = srv.clone();
    let graceful = async move {
        shutdown.await;
        ssrv.shutdown.store(true, Relaxed);
        info!(category = "control", "shutting down");
        let sessions: Vec<_> = ssrv.hub.read().unwrap().by_conn.values().cloned().collect();
        for s in sessions {
            s.close("shutdown");
        }
    };
    let result = if srv.cfg.tls_cert_path.is_empty() {
        axum::serve(tcp, app).with_graceful_shutdown(graceful).await
    } else {
        let listener = super::tls::TlsListener::new(tcp, &srv.cfg.tls_cert_path, &srv.cfg.tls_key_path)?;
        axum::serve(listener, app).with_graceful_shutdown(graceful).await
    };
    for t in tasks {
        t.abort();
    }
    result
}
