//! [M-R1] Minimal HTTP/1.1 subset for the minisd skeleton: request
//! line, case-insensitive header names, Content-Length body. The client
//! surface is controlled (our app process and curl smoke runs); a
//! hardened parser is an M-R2 item if the contract surface grows.

/// One parsed HTTP/1.1 request — only what the contract needs.
#[allow(dead_code)]
pub struct Request {
    pub method: String,
    pub path: String,
    pub authorization: Option<String>,
    pub body: Vec<u8>,
}

fn find_header_end(buf: &[u8]) -> Option<usize> {
    buf.windows(4).position(|w| w == b"\r\n\r\n")
}

/// Returns None both for garbage and for a PARTIAL request (headers
/// without the full Content-Length body yet) — the accept loop keeps
/// reading and retries. The caller bounds the buffer size.
pub fn parse_request(buf: &[u8]) -> Option<Request> {
    let head_end = find_header_end(buf)?;
    let head = std::str::from_utf8(&buf[..head_end]).ok()?;
    let mut body: Vec<u8> = buf[head_end + 4..].to_vec();

    let mut lines = head.split("\r\n");
    let request_line = lines.next()?;
    let mut parts = request_line.split_whitespace();
    let method = parts.next()?.to_string();
    let path = parts.next()?.to_string();

    let mut authorization: Option<String> = None;
    let mut content_length: usize = 0;
    for line in lines {
        let Some((name, value)) = line.split_once(':') else { continue };
        match name.trim().to_ascii_lowercase().as_str() {
            "authorization" => authorization = Some(value.trim().to_string()),
            "content-length" => content_length = value.trim().parse().unwrap_or(0),
            _ => {}
        }
    }

    if body.len() < content_length {
        return None; // body not fully arrived yet
    }
    body.truncate(content_length);

    Some(Request { method, path, authorization, body })
}

/// The skeleton always answers `Connection: close` — no keep-alive
/// state, one request per connection.
pub fn response(status: u16, reason: &str, body: &str) -> Vec<u8> {
    format!(
        "HTTP/1.1 {} {}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
        status,
        reason,
        body.len(),
        body
    )
    .into_bytes()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_a_post_with_headers_and_body() {
        let raw = b"POST / HTTP/1.1\r\nHost: 127.0.0.1:7824\r\nAuthorization: Bearer abc123\r\nContent-Length: 13\r\n\r\n{\"jsonrpc\":1}";
        let req = parse_request(raw).expect("well-formed request");
        assert_eq!(req.method, "POST");
        assert_eq!(req.path, "/");
        assert_eq!(req.authorization.as_deref(), Some("Bearer abc123"));
        assert_eq!(req.body, b"{\"jsonrpc\":1}");
    }

    #[test]
    fn header_names_are_case_insensitive() {
        let raw = b"POST / HTTP/1.1\r\naUtHoRiZaTiOn: Bearer x\r\nCONTENT-LENGTH: 2\r\n\r\n{}";
        let req = parse_request(raw).expect("well-formed request");
        assert_eq!(req.authorization.as_deref(), Some("Bearer x"));
        assert_eq!(req.body, b"{}");
    }

    #[test]
    fn a_body_shorter_than_content_length_is_a_partial_read() {
        let raw = b"POST / HTTP/1.1\r\nContent-Length: 50\r\n\r\n{\"a\":1}";
        assert!(parse_request(raw).is_none());
    }

    #[test]
    fn a_body_longer_than_content_length_is_truncated_to_it() {
        let raw = b"POST / HTTP/1.1\r\nContent-Length: 7\r\n\r\n1234567EXTRA";
        let req = parse_request(raw).expect("well-formed request");
        assert_eq!(req.body, b"1234567");
    }

    #[test]
    fn garbage_is_rejected() {
        assert!(parse_request(b"not http at all").is_none());
        assert!(parse_request(b"").is_none());
        // Missing the request line entirely.
        assert!(parse_request(b"\r\n\r\nbody").is_none());
    }

    #[test]
    fn missing_authorization_header_yields_none_field() {
        let raw = b"POST / HTTP/1.1\r\nContent-Length: 0\r\n\r\n";
        let req = parse_request(raw).expect("well-formed request");
        assert!(req.authorization.is_none());
        assert!(req.body.is_empty());
    }

    #[test]
    fn response_carries_status_and_exact_content_length() {
        let bytes = response(401, "Unauthorized", "{\"e\":1}");
        let text = String::from_utf8(bytes).unwrap();
        assert!(text.starts_with("HTTP/1.1 401 Unauthorized\r\n"));
        assert!(text.contains("Content-Length: 7\r\n"));
        assert!(text.ends_with("{\"e\":1}"));
    }
}
