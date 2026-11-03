//! Touch ID unlock for locked notes: the key kept in the Keychain behind Touch ID, by the Swift code the iOS app
//! uses for Face ID (swift/). The web app decides when; this side only keeps and gives back the key.

use serde::Serialize;
use url::Url;

/// What asking for Touch ID came to, as the web app reads it.
#[derive(Debug, PartialEq, Serialize)]
#[serde(tag = "outcome", rename_all = "lowercase")]
pub enum Outcome {
    /// `key`: the kept key, when one was asked for.
    Ok {
        #[serde(skip_serializing_if = "Vec::is_empty")]
        key: Vec<u8>,
    },
    /// Dismissed: Cancel, or "Use Password".
    Cancelled,
    /// The kept key is gone: fingers enrolled or removed since, or never kept.
    Invalidated,
    /// Locked out, interrupted, or no Touch ID.
    Failed,
}

impl Outcome {
    fn from_code(code: i32, key: Vec<u8>) -> Self {
        match code {
            0 => Outcome::Ok { key },
            1 => Outcome::Cancelled,
            2 => Outcome::Invalidated,
            _ => Outcome::Failed,
        }
    }
}

/// One Keychain item per server and account: another server's, or another account's, is never read.
pub fn service(page: &Url, user: &str) -> String {
    format!(
        "app.notesd.desktop.locked-notes {} {user}",
        page.origin().ascii_serialization()
    )
}

#[cfg(target_os = "macos")]
mod bridge {
    use std::ffi::{CString, c_char};

    unsafe extern "C" {
        fn notesd_touch_id_available() -> bool;
        fn notesd_touch_id_seal(service: *const c_char, key: *const u8, length: usize) -> i32;
        fn notesd_touch_id_open(service: *const c_char, out: *mut u8, capacity: usize, length: *mut usize) -> i32;
        fn notesd_touch_id_forget(service: *const c_char);
    }

    fn c(service: &str) -> CString {
        // A NUL can't come from an origin and a username; dropping it can't make another account's item.
        CString::new(service.replace('\0', "")).expect("no NUL left")
    }

    pub fn available() -> bool {
        unsafe { notesd_touch_id_available() }
    }

    pub fn seal(service: &str, key: &[u8]) -> i32 {
        let service = c(service);
        unsafe { notesd_touch_id_seal(service.as_ptr(), key.as_ptr(), key.len()) }
    }

    pub fn open(service: &str) -> (i32, Vec<u8>) {
        let service = c(service);
        let mut out = vec![0u8; 64];
        let mut length = 0usize;
        let code = unsafe { notesd_touch_id_open(service.as_ptr(), out.as_mut_ptr(), out.len(), &mut length) };
        out.truncate(if code == 0 { length } else { 0 });
        (code, out)
    }

    pub fn forget(service: &str) {
        let service = c(service);
        unsafe { notesd_touch_id_forget(service.as_ptr()) }
    }
}

#[cfg(not(target_os = "macos"))]
mod bridge {
    pub fn available() -> bool {
        false
    }

    pub fn seal(_: &str, _: &[u8]) -> i32 {
        3
    }

    pub fn open(_: &str) -> (i32, Vec<u8>) {
        (3, Vec::new())
    }

    pub fn forget(_: &str) {}
}

pub fn available() -> bool {
    bridge::available()
}

/// Asks for Touch ID, then keeps `key`, replacing what was kept. Blocks until answered.
pub fn seal(service: &str, mut key: Vec<u8>) -> Outcome {
    let code = bridge::seal(service, &key);
    key.fill(0);
    Outcome::from_code(code, Vec::new())
}

/// Asks for Touch ID, then gives back the kept key. Blocks until answered.
pub fn open(service: &str) -> Outcome {
    let (code, key) = bridge::open(service);
    Outcome::from_code(code, key)
}

pub fn forget(service: &str) {
    bridge::forget(service)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn outcomes_as_the_web_app_reads_them() {
        let json = |o: Outcome| serde_json::to_string(&o).unwrap();
        assert_eq!(
            json(Outcome::from_code(0, vec![1, 2])),
            r#"{"outcome":"ok","key":[1,2]}"#
        );
        assert_eq!(json(Outcome::from_code(0, Vec::new())), r#"{"outcome":"ok"}"#);
        assert_eq!(json(Outcome::from_code(1, Vec::new())), r#"{"outcome":"cancelled"}"#);
        assert_eq!(json(Outcome::from_code(2, Vec::new())), r#"{"outcome":"invalidated"}"#);
        assert_eq!(json(Outcome::from_code(3, Vec::new())), r#"{"outcome":"failed"}"#);
        assert_eq!(json(Outcome::from_code(42, Vec::new())), r#"{"outcome":"failed"}"#);
    }

    #[test]
    fn one_item_per_server_and_account() {
        let page = |s: &str| Url::parse(s).unwrap();
        let a = service(&page("https://notes.example.com/notes/1"), "mara");
        assert_eq!(a, "app.notesd.desktop.locked-notes https://notes.example.com mara");
        assert_eq!(a, service(&page("https://notes.example.com/settings"), "mara"));
        assert_ne!(a, service(&page("https://notes.example.com:8443/"), "mara"));
        assert_ne!(a, service(&page("https://notes.example.com/"), "ana"));
    }
}
