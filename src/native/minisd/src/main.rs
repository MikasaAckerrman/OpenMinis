//! [M-R1] minisd — the native daemon skeleton of the Rust migration
//! (docs/RUST_MIGRATION_SPEC.md). Mode `vault`: HTTP JSON-RPC on
//! 127.0.0.1:7824 (contract v1, echo skeleton — `initialize` and
//! `tools/list`), Bearer gate with the B12 semantics (HTTP 401 on any
//! mismatch).
//!
//! Secret hand-off, per the reviewer's M-R1 plan: ONE line on stdin in
//! production wiring ("no secret, no service" — stdin EOF without a
//! secret exits instead of serving), or `--smoke-token` for manual ADB
//! smoke runs. The fd-3 design of the spec §2.1 needs the JNI fork
//! helper (ProcessBuilder closes non-standard fds) and is deliberately
//! deferred to M-R2.

mod http;
mod rpc;
mod server;

const DEFAULT_ADDR: &str = "127.0.0.1:7824";

fn fail(msg: &str) -> ! {
    eprintln!("minisd: {msg}");
    std::process::exit(2);
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let mode = args.get(1).map(String::as_str).unwrap_or("");
    if mode != "vault" {
        fail("usage: minisd vault [--port <n>] [--smoke-token <secret>]; otherwise the secret arrives on stdin");
    }

    let mut addr = DEFAULT_ADDR.to_string();
    let mut smoke_token: Option<String> = None;
    let mut i = 2;
    while i < args.len() {
        match args[i].as_str() {
            "--port" => {
                let Some(v) = args.get(i + 1) else { fail("--port requires a value") };
                let Ok(p) = v.parse::<u16>() else { fail("--port must be a number") };
                addr = format!("127.0.0.1:{p}");
                i += 2;
            }
            "--smoke-token" => {
                let Some(v) = args.get(i + 1) else { fail("--smoke-token requires a value") };
                smoke_token = Some(v.clone());
                i += 2;
            }
            other => fail(&format!("unknown argument: {other}")),
        }
    }

    let secret = match smoke_token {
        Some(t) => {
            eprintln!("minisd: SMOKE mode — bearer secret from argv (never pass secrets via argv in production wiring)");
            t
        }
        None => {
            let mut line = String::new();
            match std::io::stdin().read_line(&mut line) {
                Ok(0) | Err(_) => fail("no bearer secret on stdin — refusing to serve"),
                Ok(_) => line.trim().to_string(),
            }
        }
    };
    if secret.is_empty() {
        fail("empty bearer secret — refusing to serve");
    }

    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("tokio runtime");
    if let Err(e) = runtime.block_on(server::serve(&addr, secret)) {
        eprintln!("minisd: server failed: {e}");
        std::process::exit(1);
    }
}
