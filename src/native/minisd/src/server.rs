//! [M-R1] The accept loop: one tokio task per connection, one request
//! per connection (Connection: close), a read cap and a read timeout so
//! a stuck client cannot pin a task.

use crate::http;
use crate::rpc;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

const MAX_REQUEST_BYTES: usize = 1024 * 1024;
const READ_TIMEOUT: Duration = Duration::from_secs(10);

pub async fn serve(addr: &str, secret: String) -> std::io::Result<()> {
    let listener = TcpListener::bind(addr).await?;
    eprintln!("minisd: vault listening on http://{addr} (contract v1, M-R1 echo skeleton)");
    serve_on(listener, secret).await
}

/// Split out so the tests can bind port 0 and hand the listener over —
/// no bind-race between the test's local_addr probe and the server.
async fn serve_on(listener: TcpListener, secret: String) -> std::io::Result<()> {
    loop {
        let (mut stream, _) = listener.accept().await?;
        let secret = secret.clone();
        tokio::spawn(async move {
            let _ = handle_connection(&mut stream, &secret).await;
            let _ = stream.shutdown().await;
        });
    }
}

async fn handle_connection(stream: &mut TcpStream, secret: &str) -> std::io::Result<()> {
    let mut buf: Vec<u8> = Vec::with_capacity(1024);
    let mut chunk = [0u8; 4096];
    let request = loop {
        let n = match tokio::time::timeout(READ_TIMEOUT, stream.read(&mut chunk)).await {
            Ok(Ok(n)) => n,
            // Timeout (Err) or read error (Ok(Err)) — drop the connection.
            _ => return Ok(()),
        };
        if n == 0 {
            return Ok(()); // client closed mid-request
        }
        buf.extend_from_slice(&chunk[..n]);
        if buf.len() > MAX_REQUEST_BYTES {
            stream
                .write_all(&http::response(413, "Payload Too Large", "{}"))
                .await?;
            return Ok(());
        }
        // None = partial request (or garbage, bounded by the cap above) — keep reading.
        match http::parse_request(&buf) {
            Some(req) => break req,
            None => continue,
        }
    };

    let authorized = rpc::bearer_ok(secret, request.authorization.as_deref());
    let (status, body) = rpc::dispatch(&request.body, authorized);
    let reason = match status {
        200 => "OK",
        401 => "Unauthorized",
        413 => "Payload Too Large",
        _ => "Error",
    };
    stream
        .write_all(&http::response(status, reason, &body.to_string()))
        .await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The M-R1 acceptance line, executed as code: a real socket, a real
    /// request, the probe-relevant answer ("telegram") over the wire.
    #[tokio::test]
    async fn the_skeleton_serves_the_probe_contract_end_to_end() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap().to_string();
        let server = tokio::spawn(serve_on(listener, "tok".to_string()));

        let body = r#"{"jsonrpc":"2.0","id":1,"method":"initialize"}"#;
        let request = format!(
            "POST / HTTP/1.1\r\nAuthorization: Bearer tok\r\nContent-Length: {}\r\n\r\n{}",
            body.len(),
            body
        );
        let mut stream = TcpStream::connect(&addr).await.unwrap();
        stream.write_all(request.as_bytes()).await.unwrap();
        let mut buf = vec![0u8; 4096];
        let n = tokio::time::timeout(Duration::from_secs(5), stream.read(&mut buf))
            .await
            .expect("response within 5s")
            .expect("read");
        let text = String::from_utf8_lossy(&buf[..n]);
        assert!(text.starts_with("HTTP/1.1 200 OK"), "got: {text}");
        assert!(text.contains("\"serverInfo\":{\"name\":\"telegram\""), "got: {text}");

        server.abort();
    }

    #[tokio::test]
    async fn a_wrong_token_gets_the_http_401_gate_over_the_wire() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap().to_string();
        let server = tokio::spawn(serve_on(listener, "tok".to_string()));

        let body = r#"{"jsonrpc":"2.0","id":1,"method":"initialize"}"#;
        let request = format!(
            "POST / HTTP/1.1\r\nAuthorization: Bearer WRONG\r\nContent-Length: {}\r\n\r\n{}",
            body.len(),
            body
        );
        let mut stream = TcpStream::connect(&addr).await.unwrap();
        stream.write_all(request.as_bytes()).await.unwrap();
        let mut buf = vec![0u8; 4096];
        let n = tokio::time::timeout(Duration::from_secs(5), stream.read(&mut buf))
            .await
            .expect("response within 5s")
            .expect("read");
        let text = String::from_utf8_lossy(&buf[..n]);
        assert!(text.starts_with("HTTP/1.1 401 Unauthorized"), "got: {text}");

        server.abort();
    }
}
