//! Where a download goes: the Downloads folder, numbered like Safari does rather than over an existing file.

use std::ffi::{OsStr, OsString};
use std::path::{Path, PathBuf};

/// `dir/name`, or `dir/name (2).ext`, `(3)`… when taken: by a file, or by a download still under way (`pending`),
/// whose file may not exist yet.
pub fn unused_path(dir: &Path, name: &OsStr, pending: &[PathBuf]) -> PathBuf {
    let free = |path: &PathBuf| !path.exists() && !pending.contains(path);
    let first = dir.join(name);
    if free(&first) {
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
        .find(free)
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
        let zip = OsStr::new("notes.zip");
        assert_eq!(unused_path(&dir, zip, &[]), dir.join("notes.zip"));
        fs::write(dir.join("notes.zip"), "").unwrap();
        assert_eq!(unused_path(&dir, zip, &[]), dir.join("notes (2).zip"));
        fs::write(dir.join("notes (2).zip"), "").unwrap();
        assert_eq!(unused_path(&dir, zip, &[]), dir.join("notes (3).zip"));
        assert_eq!(
            unused_path(&dir, zip, &[dir.join("notes (3).zip")]),
            dir.join("notes (4).zip")
        );
        fs::write(dir.join("README"), "").unwrap();
        assert_eq!(unused_path(&dir, OsStr::new("README"), &[]), dir.join("README (2)"));
        fs::remove_dir_all(&dir).unwrap();
    }
}
