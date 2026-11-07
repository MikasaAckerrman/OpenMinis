//! [M-R2] minisd — the native daemon of the Rust migration
//! (docs/RUST_MIGRATION_SPEC.md + docs/M_R2_TOOL_MAPPING.md).
//!
//! Modes:
//! - `minisd vault [--port <n>] [--storage <dir>] [--archive-dir <dir>]
//!        [--tz-offset-hours <h>] [--smoke-token <secret>]`
//!   The vault server: HTTP JSON-RPC on 127.0.0.1 (default 7824),
//!   Bearer gate (B12 semantics), 21-tool surface. The bearer secret
//!   arrives as ONE line on stdin in production wiring ("no secret, no
//!   service"); `--smoke-token` exists for manual ADB smoke only.
//!   MTProto credentials come from the environment (TG_API_ID /
//!   TG_API_HASH — the same contract the legacy python vault had); a
//!   failed connect degrades to the no-client state (initialize and
//!   tools/list still answer, tools return the session error) instead
//!   of killing the process.
//! - `minisd login --storage <dir>`
//!   The one-time interactive re-login (session formats are
//!   binary-incompatible with pyrogram, spec §1.2): phone, code and the
//!   optional 2FA password arrive on stdin; the session lands in
//!   `<storage>/grammers.session`.
//!
//! fd-3 key injection of the spec §2.1 stays deferred (M-R3 wiring).

mod http;
mod rpc;
mod server;
mod telegram;

use std::path::PathBuf;
use std::process::exit;

#[allow(dead_code)]
const DEFAULT_ADDR: &str = "127.0.0.1:7824";

fn fail(msg: &str) -> ! {
    eprintln!("minisd: {msg}");
    exit(2);
}

struct Args {
    port: u16,
    storage: PathBuf,
    archive_dir: PathBuf,
    tz_offset_secs: i32,
    smoke_token: Option<String>,
}

fn parse_common(args: &[String]) -> Args {
    let mut port = 7824u16;
    let mut storage = PathBuf::from(".");
    let mut archive_dir: Option<PathBuf> = None;
    let mut tz_offset_hours = 0i32;
    let mut smoke_token: Option<String> = None;
    let mut i = 2;
    while i < args.len() {
        match args[i].as_str() {
            "--port" => {
                let Some(v) = args.get(i + 1) else { fail("--port requires a value") };
                port = v.parse().unwrap_or_else(|_| fail("--port must be a number"));
                i += 2;
            }
            "--storage" => {
                let Some(v) = args.get(i + 1) else { fail("--storage requires a value") };
                storage = PathBuf::from(v);
                i += 2;
            }
            "--archive-dir" => {
                let Some(v) = args.get(i + 1) else { fail("--archive-dir requires a value") };
                archive_dir = Some(PathBuf::from(v));
                i += 2;
            }
            "--tz-offset-hours" => {
                let Some(v) = args.get(i + 1) else { fail("--tz-offset-hours requires a value") };
                tz_offset_hours = v
                    .parse()
                    .unwrap_or_else(|_| fail("--tz-offset-hours must be an integer (-12..14)"));
                if !(-12..=14).contains(&tz_offset_hours) {
                    fail("--tz-offset-hours must be between -12 and 14");
                }
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
    Args {
        port,
        archive_dir: archive_dir.unwrap_or_else(|| storage.join("archive")),
        storage,
        tz_offset_secs: tz_offset_hours * 3_600,
        smoke_token,
    }
}

fn env_api_credentials() -> Option<(i32, String)> {
    let id = std::env::var("TG_API_ID").ok()?;
    let hash = std::env::var("TG_API_HASH").ok()?;
    let id: i32 = id.trim().parse().ok()?;
    if hash.trim().is_empty() {
        return None;
    }
    Some((id, hash.trim().to_string()))
}

fn read_secret_from_stdin() -> String {
    let mut line = String::new();
    match std::io::stdin().read_line(&mut line) {
        Ok(0) | Err(_) => fail("no bearer secret on stdin — refusing to serve"),
        Ok(_) => line.trim().to_string(),
    }
}

async fn run_vault(args: Args) -> Result<(), String> {
    let secret = match &args.smoke_token {
        Some(t) => {
            eprintln!("minisd: SMOKE mode — bearer secret from argv (never pass secrets via argv in production wiring)");
            t.clone()
        }
        None => read_secret_from_stdin(),
    };
    if secret.is_empty() {
        fail("empty bearer secret — refusing to serve");
    }

    let ctx = telegram::tools::ToolCtx {
        storage: args.storage.clone(),
        archive_dir: args.archive_dir.clone(),
        tz_offset_secs: args.tz_offset_secs,
    };

    // MTProto boot: a failed connect degrades, it never kills the
    // server (the isAlive probe and the tools/list surface stay up).
    let state = match env_api_credentials() {
        Some((api_id, api_hash)) => {
            eprintln!("minisd: connecting to Telegram (api_id {api_id})…");
            match telegram::client::GrammersApi::connect(&args.storage, api_id, &api_hash).await {
                Ok(api) => {
                    let authorized = api.is_authorized().await;
                    eprintln!("minisd: session authorized={authorized}");
                    server::VaultState::live(api, ctx, secret, authorized)
                }
                Err(e) => {
                    eprintln!("minisd: MTProto unavailable, serving the session-error surface: {e}");
                    server::VaultState::dead(ctx, secret)
                }
            }
        }
        None => {
            eprintln!("minisd: no TG_API_ID/TG_API_HASH in the environment — serving the session-error surface");
            server::VaultState::dead(ctx, secret)
        }
    };

    let addr = format!("127.0.0.1:{}", args.port);
    server::serve(&addr, state)
        .await
        .map_err(|e| format!("server failed: {e}"))
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let mode = args.get(1).map(String::as_str).unwrap_or("");
    match mode {
        "vault" => {
            let parsed = parse_common(&args);
            let runtime = tokio::runtime::Builder::new_multi_thread()
                .enable_all()
                .build()
                .expect("tokio runtime");
            if let Err(e) = runtime.block_on(run_vault(parsed)) {
                eprintln!("minisd: {e}");
                exit(1);
            }
        }
        "login" => {
            let parsed = parse_common(&args);
            let Some((api_id, api_hash)) = env_api_credentials() else {
                fail("login requires TG_API_ID and TG_API_HASH in the environment");
            };
            let runtime = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .expect("tokio runtime");
            if let Err(e) =
                runtime.block_on(telegram::client::run_login(&parsed.storage, api_id, &api_hash))
            {
                eprintln!("minisd: {e}");
                exit(1);
            }
        }
        _ => fail(
            "usage: minisd vault [--port <n>] [--storage <dir>] [--archive-dir <dir>] \
             [--tz-offset-hours <h>] [--smoke-token <secret>]; \
             the secret arrives on stdin otherwise. `minisd login --storage <dir>` re-authenticates.",
        ),
    }
}
