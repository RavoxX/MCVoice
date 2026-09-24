//! Optional in-process TLS termination (TLS_CERT_PATH / TLS_KEY_PATH).
//! Handshakes run in their own tasks so a slow client cannot stall accepts.

use std::io;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use tokio::net::{TcpListener, TcpStream};
use tokio::sync::mpsc;
use tokio_rustls::server::TlsStream;
use tokio_rustls::TlsAcceptor;

pub struct TlsListener {
    rx: mpsc::Receiver<(TlsStream<TcpStream>, SocketAddr)>,
    local: SocketAddr,
}

fn load_config(cert: &str, key: &str) -> io::Result<rustls::ServerConfig> {
    let certs: Vec<_> = rustls_pemfile::certs(&mut io::BufReader::new(std::fs::File::open(cert)?)).collect::<Result<_, _>>()?;
    let key = rustls_pemfile::private_key(&mut io::BufReader::new(std::fs::File::open(key)?))?
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "no private key in TLS_KEY_PATH"))?;
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let mut cfg = rustls::ServerConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?
        .with_no_client_auth()
        .with_single_cert(certs, key)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    cfg.alpn_protocols = vec![b"http/1.1".to_vec()];
    Ok(cfg)
}

impl TlsListener {
    pub fn new(tcp: TcpListener, cert: &str, key: &str) -> io::Result<Self> {
        let acceptor = TlsAcceptor::from(Arc::new(load_config(cert, key)?));
        let local = tcp.local_addr()?;
        let (tx, rx) = mpsc::channel(256);
        tokio::spawn(async move {
            loop {
                let Ok((stream, addr)) = tcp.accept().await else {
                    tokio::time::sleep(Duration::from_millis(50)).await;
                    continue;
                };
                let acceptor = acceptor.clone();
                let tx = tx.clone();
                tokio::spawn(async move {
                    if let Ok(Ok(tls)) = tokio::time::timeout(Duration::from_secs(10), acceptor.accept(stream)).await {
                        let _ = tx.send((tls, addr)).await;
                    }
                });
            }
        });
        Ok(TlsListener { rx, local })
    }
}

impl axum::serve::Listener for TlsListener {
    type Io = TlsStream<TcpStream>;
    type Addr = SocketAddr;

    async fn accept(&mut self) -> (Self::Io, Self::Addr) {
        loop {
            if let Some(v) = self.rx.recv().await {
                return v;
            }
            std::future::pending::<()>().await;
        }
    }

    fn local_addr(&self) -> io::Result<Self::Addr> {
        Ok(self.local)
    }
}
