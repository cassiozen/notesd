//! The chosen server, kept in the app's config directory.

use std::fs;
use std::path::PathBuf;

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Manager, Url};

#[derive(Default, Serialize, Deserialize)]
struct Settings {
    server: Option<Url>,
}

fn path(app: &AppHandle) -> Option<PathBuf> {
    app.path().app_config_dir().ok().map(|dir| dir.join("settings.json"))
}

/// None when nothing was kept, or what was kept can't be read: the setup page asks again.
pub fn load(app: &AppHandle) -> Option<Url> {
    let text = fs::read_to_string(path(app)?).ok()?;
    serde_json::from_str::<Settings>(&text).ok()?.server
}

pub fn save(app: &AppHandle, server: &Url) -> Result<(), String> {
    let path = path(app).ok_or("No place to keep settings on this Mac.")?;
    if let Some(dir) = path.parent() {
        fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    }
    let text = serde_json::to_string_pretty(&Settings {
        server: Some(server.clone()),
    })
    .map_err(|e| e.to_string())?;
    fs::write(path, text).map_err(|e| e.to_string())
}
