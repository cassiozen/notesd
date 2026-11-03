//! The server address: what the user typed, made into an origin, and checked to be a notesd.

use std::fmt;
use std::time::Duration;

use tauri::Url;
use url::Host;

#[derive(Debug, PartialEq)]
pub enum AddressError {
    Empty,
    Invalid,
    NeedsHttps,
}

impl fmt::Display for AddressError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Empty => "Enter your server’s address.",
            Self::Invalid => "That isn’t a web address. It looks like https://notes.example.com.",
            // Without HTTPS the window isn't a secure context: no notes kept on the Mac, no locked notes.
            Self::NeedsHttps => "notesd needs HTTPS to keep your notes on this Mac. Use the server’s https:// address.",
        })
    }
}

/// The server's origin from what the user typed: https:// when no scheme was given; any path (a pasted note's
/// link) dropped. Plain http only for this Mac itself, which browsers treat as secure too.
pub fn normalize(input: &str) -> Result<Url, AddressError> {
    let input = input.trim();
    if input.is_empty() {
        return Err(AddressError::Empty);
    }
    let input = if input.contains("://") {
        input.to_owned()
    } else {
        format!("https://{input}")
    };
    let url = Url::parse(&input).map_err(|_| AddressError::Invalid)?;
    let loopback = match url.host() {
        Some(Host::Domain(domain)) => domain == "localhost",
        Some(Host::Ipv4(ip)) => ip.is_loopback(),
        Some(Host::Ipv6(ip)) => ip.is_loopback(),
        None => return Err(AddressError::Invalid),
    };
    match url.scheme() {
        "https" => {}
        "http" if loopback => {}
        "http" => return Err(AddressError::NeedsHttps),
        _ => return Err(AddressError::Invalid),
    }
    Url::parse(&url.origin().ascii_serialization()).map_err(|_| AddressError::Invalid)
}

#[derive(Debug)]
pub enum CheckError {
    Unreachable { host: String },
    Untrusted { host: String },
    NotNotesd { host: String },
}

impl fmt::Display for CheckError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Unreachable { host } => {
                write!(f, "No answer from {host}. Check the address, and that you’re online.")
            }
            Self::Untrusted { host } => write!(f, "This Mac doesn’t trust {host}’s HTTPS certificate."),
            Self::NotNotesd { host } => write!(f, "{host} answered, but it isn’t a notesd server."),
        }
    }
}

/// Whether `server` is a notesd: every answer from one carries `X-Notesd-Server`, and auth/status answers
/// before sign-in. A redirect isn't followed: notesd doesn't send one, a sign-in portal or a proxy's page does.
pub fn check(server: &Url) -> Result<(), CheckError> {
    let host = server.host_str().unwrap_or_default().to_owned();
    let agent: ureq::Agent = ureq::Agent::config_builder()
        .timeout_global(Some(Duration::from_secs(15)))
        .max_redirects(0)
        .http_status_as_error(false)
        .tls_config(
            ureq::tls::TlsConfig::builder()
                .provider(ureq::tls::TlsProvider::NativeTls)
                .build(),
        )
        .build()
        .into();
    let status = server
        .join("api/v1/auth/status")
        .expect("a relative path joins any origin");
    let response = agent.get(status.as_str()).call().map_err(|err| match err {
        ureq::Error::Tls(_) | ureq::Error::NativeTls(_) => CheckError::Untrusted { host: host.clone() },
        _ => CheckError::Unreachable { host: host.clone() },
    })?;
    if response.headers().contains_key("x-notesd-server") {
        Ok(())
    } else {
        Err(CheckError::NotNotesd { host })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ok(input: &str) -> String {
        normalize(input).unwrap().to_string()
    }

    #[test]
    fn a_bare_host_gets_https() {
        assert_eq!(ok("notes.example.com"), "https://notes.example.com/");
        assert_eq!(ok("  notes.example.com:8443  "), "https://notes.example.com:8443/");
    }

    #[test]
    fn a_pasted_link_keeps_only_the_origin() {
        assert_eq!(
            ok("https://notes.example.com/notes/abc?x=1#top"),
            "https://notes.example.com/"
        );
        assert_eq!(ok("https://me:secret@notes.example.com/"), "https://notes.example.com/");
    }

    #[test]
    fn plain_http_only_for_this_mac() {
        assert_eq!(ok("http://localhost:8080"), "http://localhost:8080/");
        assert_eq!(ok("http://127.0.0.1:8080/"), "http://127.0.0.1:8080/");
        assert_eq!(ok("http://[::1]:8080"), "http://[::1]:8080/");
        assert_eq!(normalize("http://192.168.1.20:8080"), Err(AddressError::NeedsHttps));
        assert_eq!(normalize("http://notes.example.com"), Err(AddressError::NeedsHttps));
    }

    #[test]
    fn nonsense_is_refused() {
        assert_eq!(normalize(""), Err(AddressError::Empty));
        assert_eq!(normalize("   "), Err(AddressError::Empty));
        assert_eq!(normalize("ftp://notes.example.com"), Err(AddressError::Invalid));
        assert_eq!(normalize("https://"), Err(AddressError::Invalid));
        assert_eq!(normalize("not a host"), Err(AddressError::Invalid));
    }
}
