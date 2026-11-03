//! Where a download goes: the Downloads folder, numbered like Safari does rather than over an existing file.

use std::ffi::{OsStr, OsString};
use std::path::{Path, PathBuf};

/// `dir/name`, or `dir/name (2).ext`, `(3)`… when taken.
pub fn unused_path(dir: &Path, name: &OsStr) -> PathBuf {
    let first = dir.join(name);
    if !first.exists() {
        return first;
    }
    let name = Path::new(name);
    let stem = name.file_stem().unwrap_or(name.as_os_str());
    let ext = name.extension();
    (2..)
        .map(|n| {
            let mut candidate = OsString::from(stem);
            candidate.push(format!(" ({n})"));
            if let Some(ext) = ext {
                candidate.push(".");
                candidate.push(ext);
            }
            dir.join(candidate)
        })
        .find(|path| !path.exists())
        .expect("some number is free")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn numbers_a_taken_name() {
        let dir = std::env::temp_dir().join(format!("notesd-downloads-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        assert_eq!(unused_path(&dir, OsStr::new("notes.zip")), dir.join("notes.zip"));
        fs::write(dir.join("notes.zip"), "").unwrap();
        assert_eq!(unused_path(&dir, OsStr::new("notes.zip")), dir.join("notes (2).zip"));
        fs::write(dir.join("notes (2).zip"), "").unwrap();
        assert_eq!(unused_path(&dir, OsStr::new("notes.zip")), dir.join("notes (3).zip"));
        fs::write(dir.join("README"), "").unwrap();
        assert_eq!(unused_path(&dir, OsStr::new("README")), dir.join("README (2)"));
        fs::remove_dir_all(&dir).unwrap();
    }
}
