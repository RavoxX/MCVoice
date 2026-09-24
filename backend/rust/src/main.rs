use mcvoice_backend::config::Config;
use mcvoice_backend::server::{self, Server};
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() {
    if std::env::args().nth(1).is_some_and(|a| a == "--version" || a == "version") {
        println!("mcvoice-backend (rust) {}", server::VERSION);
        return;
    }
    let cfg = match Config::from_env() {
        Ok(c) => c,
        Err(e) => {
            eprintln!("{e}");
            std::process::exit(2);
        }
    };
    let level = match cfg.log_level.as_str() {
        "trace" => "trace",
        "debug" => "debug",
        "warn" | "warning" => "warn",
        "error" => "error",
        _ => "info",
    };
    let filter = EnvFilter::new(format!("mcvoice_backend={level},warn"));
    if cfg.log_format == "text" {
        tracing_subscriber::fmt().with_env_filter(filter).init();
    } else {
        tracing_subscriber::fmt().json().with_env_filter(filter).init();
    }
    let srv = Server::new(cfg);
    let shutdown = async {
        let ctrl_c = tokio::signal::ctrl_c();
        #[cfg(unix)]
        {
            let mut term = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()).expect("signal handler");
            tokio::select! { _ = ctrl_c => {}, _ = term.recv() => {} }
        }
        #[cfg(not(unix))]
        {
            let _ = ctrl_c.await;
        }
    };
    if let Err(e) = server::run(srv, shutdown).await {
        tracing::error!(error = %e, "backend failed");
        std::process::exit(1);
    }
}
