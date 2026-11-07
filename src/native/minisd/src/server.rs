//! [M-R2] The accept loop: one tokio task per connection, one request
//! per connection (Connection: close), a read cap and a read timeout so
//! a stuck client cannot pin a task. The loop now carries the vault
//! state (api seam + tool context + bearer), so `tools/call` reaches
//! the MTProto adapter.

use crate::http;
use crate::rpc;
use crate::telegram::tools::ToolCtx;
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

const MAX_REQUEST_BYTES: usize = 1024 * 1024;
const READ_TIMEOUT: Duration = Duration::from_secs(10);

/// Everything one vault server instance needs. `authorized` is the
/// boot-time snapshot of the session state (the M-R3 supervisor
/// restarts the daemon when the app re-authorizes).
pub struct VaultState<A> {
    pub api: Arc<A>,
    pub ctx: ToolCtx,
    pub bearer: String,
    pub authorized: bool,
}

impl VaultState<crate::telegram::client::RuntimeApi> {
    pub fn live(
        api: crate::telegram::client::GrammersApi,
        ctx: ToolCtx,
        bearer: String,
        authorized: bool,
    ) -> Arc<Self> {
        Arc::new(Self {
            api: Arc::new(crate::telegram::client::RuntimeApi::Live(api)),
            ctx,
            bearer,
            authorized,
        })
    }

    /// The no-client state: initialize/tools/list still answer, every
    /// tool returns the §5.2 session error.
    pub fn dead(ctx: ToolCtx, bearer: String) -> Arc<Self> {
        Arc::new(Self {
            api: Arc::new(crate::telegram::client::RuntimeApi::Dead(
                crate::telegram::client::DeadApi,
            )),
            ctx,
            bearer,
            authorized: false,
        })
    }
}

pub async fn serve<A: crate::telegram::api::TgApi + 'static>(
    addr: &str,
    state: Arc<VaultState<A>>,
) -> std::io::Result<()> {
    let listener = TcpListener::bind(addr).await?;
    eprintln!(
        "minisd: vault listening on http://{addr} (contract v1, authorized={})",
        state.authorized
    );
    serve_on(listener, state).await
}

/// Split out so the tests can bind port 0 and hand the listener over —
/// no bind-race between the test's local_addr probe and the server.
async fn serve_on<A: crate::telegram::api::TgApi + 'static>(
    listener: TcpListener,
    state: Arc<VaultState<A>>,
) -> std::io::Result<()> {
    loop {
        let (mut stream, _) = listener.accept().await?;
        let state = state.clone();
        tokio::spawn(async move {
            let _ = handle_connection(&mut stream, &state).await;
            let _ = stream.shutdown().await;
        });
    }
}

async fn handle_connection<A: crate::telegram::api::TgApi + 'static>(
    stream: &mut TcpStream,
    state: &VaultState<A>,
) -> std::io::Result<()> {
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

    let authorized_request =
        rpc::bearer_ok(&state.bearer, request.authorization.as_deref());
    if !authorized_request {
        let body = rpc_error_body();
        stream
            .write_all(&http::response(401, "Unauthorized", &body))
            .await?;
        return Ok(());
    }
    let reply = rpc::dispatch(&request.body, state.authorized, &state.api, &state.ctx).await;
    stream
        .write_all(&http::response(200, "OK", &reply.to_string()))
        .await?;
    Ok(())
}

/// The 401 body — the B12 error envelope (§5.2 of the migration spec
/// describes the same shape over the OLD python daemon).
fn rpc_error_body() -> String {
    serde_json::json!({
        "jsonrpc": "2.0", "id": null,
        "error": {"code": -32001, "message": "unauthorized: bearer token mismatch"}
    })
    .to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::telegram::api::MockApi;

    fn state_with(bearer: &str) -> Arc<VaultState<MockApi>> {
        Arc::new(VaultState {
            api: Arc::new(MockApi::new()),
            ctx: ToolCtx {
                storage: std::env::temp_dir().join("minisd-server-test"),
                archive_dir: std::env::temp_dir().join("minisd-server-test/archive"),
                tz_offset_secs: 0,
            },
            bearer: bearer.to_string(),
            authorized: false,
        })
    }

    /// The M-R1 acceptance line, executed as code: a real socket, a real
    /// request, the probe-relevant answer ("telegram") over the wire.
    #[tokio::test]
    async fn the_skeleton_serves_the_probe_contract_end_to_end() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap().to_string();
        let server = tokio::spawn(serve_on(listener, state_with("tok")));

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
        let server = tokio::spawn(serve_on(listener, state_with("tok")));

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

    #[tokio::test]
    async fn tools_call_over_the_wire_carries_the_mcp_envelope() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap().to_string();
        // authorized=false → the session error rides inside a 200 envelope.
        let server = tokio::spawn(serve_on(listener, state_with("tok")));

        let body = r#"{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"tg_read","arguments":{"chat_id":1}}}"#;
        let request = format!(
            "POST / HTTP/1.1\r\nAuthorization: Bearer tok\r\nContent-Length: {}\r\n\r\n{}",
            body.len(),
            body
        );
        let mut stream = TcpStream::connect(&addr).await.unwrap();
        stream.write_all(request.as_bytes()).await.unwrap();
        let mut buf = vec![0u8; 8192];
        let n = tokio::time::timeout(Duration::from_secs(5), stream.read(&mut buf))
            .await
            .expect("response within 5s")
            .expect("read");
        let text = String::from_utf8_lossy(&buf[..n]);
        assert!(text.starts_with("HTTP/1.1 200 OK"), "got: {text}");
        assert!(
            text.contains("not authorized - run send-code/verify first"),
            "got: {text}"
        );

        server.abort();
    }
}
