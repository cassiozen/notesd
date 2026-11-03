//! notesd for macOS: a window on the user's notesd server. The web app runs there as it does in Safari, offline
//! included (its service worker and the notes kept in OPFS); this side only picks the server and gives the window
//! what a Mac app has: menus, window buttons in the app's own top bars, links that open in the browser, downloads.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod downloads;
mod server;
mod store;

use std::sync::Mutex;

use tauri::ipc::CapabilityBuilder;
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem, Submenu};
use tauri::webview::{DownloadEvent, NewWindowResponse, PageLoadEvent};
use tauri::{AppHandle, Manager, Url, WebviewUrl, WebviewWindow, WebviewWindowBuilder, WindowEvent, Wry};
use tauri_plugin_opener::OpenerExt;

const MAIN: &str = "main";
const CHANGE_SERVER: &str = "change-server";
const RELOAD: &str = "reload";

#[derive(Default)]
struct State {
    /// The server the window opens; none until the setup page sets one.
    server: Mutex<Option<Url>>,
    /// The bundled setup page's address, as the window first loaded it: `tauri://localhost/`, or the CLI's dev
    /// server under `tauri dev`.
    setup: Mutex<Option<Url>>,
    /// The page the window shows now.
    current: Mutex<Option<Url>>,
    /// A load of the server's this side asked for, let through when it comes back to `on_navigation`.
    issued: Mutex<Option<Url>>,
    /// Last told to the page; a resize that doesn't change it needn't tell it again.
    fullscreen: Mutex<bool>,
}

#[tauri::command]
fn get_server(state: tauri::State<'_, State>) -> Option<String> {
    state.server.lock().unwrap().as_ref().map(Url::to_string)
}

/// Opens the server in the window. The setup page asks for it rather than navigating itself: see `Navigation`.
#[tauri::command]
fn open_server(app: AppHandle) {
    let server = app.state::<State>().server.lock().unwrap().clone();
    if let Some(server) = server {
        load(&app, server);
    }
}

/// Checks that `address` is a notesd server and keeps it. The setup page then opens it.
#[tauri::command]
async fn set_server(app: AppHandle, address: String) -> Result<String, String> {
    let url = server::normalize(&address).map_err(|e| e.to_string())?;
    let checking = url.clone();
    tauri::async_runtime::spawn_blocking(move || server::check(&checking))
        .await
        .map_err(|e| e.to_string())?
        .map_err(|e| e.to_string())?;
    store::save(&app, &url)?;
    allow_window_moves(&app, &url).map_err(|e| e.to_string())?;
    *app.state::<State>().server.lock().unwrap() = Some(url.clone());
    Ok(url.to_string())
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_window_state::Builder::default().build())
        .manage(State::default())
        .invoke_handler(tauri::generate_handler![get_server, set_server, open_server])
        .menu(menu)
        .on_menu_event(|app, event| {
            let Some(window) = app.get_webview_window(MAIN) else {
                return;
            };
            match event.id().as_ref() {
                CHANGE_SERVER => change_server(app, &window),
                RELOAD => {
                    let _ = window.reload();
                }
                _ => {}
            }
        })
        .on_window_event(|window, event| {
            if let WindowEvent::Resized(_) = event
                && let Some(webview) = window.get_webview_window(MAIN)
            {
                tell_fullscreen(&webview, false);
            }
        })
        .setup(|app| {
            let server = store::load(app.handle());
            if let Some(server) = &server {
                allow_window_moves(app.handle(), server)?;
            }
            *app.state::<State>().server.lock().unwrap() = server;
            let window = main_window(app.handle())?;
            *app.state::<State>().setup.lock().unwrap() = Some(window.url()?);
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running notesd");
}

/// Opens on the setup page, which goes on to the server: when the server can't be reached and the service worker
/// has nothing, a failed load leaves the setup page showing, with a way to retry, instead of a blank window.
fn main_window(app: &AppHandle) -> tauri::Result<WebviewWindow> {
    let (navigating, popups, downloading) = (app.clone(), app.clone(), app.clone());
    let builder = WebviewWindowBuilder::new(app, MAIN, WebviewUrl::App("index.html".into()))
        .title("notesd")
        .inner_size(1200.0, 800.0)
        .min_inner_size(900.0, 560.0);
    title_bar(builder)
        .on_navigation(move |url| {
            let state = navigating.state::<State>();
            let issued = state
                .issued
                .lock()
                .unwrap()
                .take_if(|issued| same_document(issued, url))
                .is_some();
            let decision = navigation(
                url,
                state.server.lock().unwrap().as_ref(),
                state.setup.lock().unwrap().as_ref(),
                state.current.lock().unwrap().as_ref(),
                issued,
            );
            match decision {
                Navigation::Allow => true,
                Navigation::Reissue => {
                    let (app, url) = (navigating.clone(), url.clone());
                    // Not from inside WebKit's policy callback: the window is busy deciding this one.
                    tauri::async_runtime::spawn(async move { load(&app, url) });
                    false
                }
                Navigation::Elsewhere => {
                    let _ = navigating.opener().open_url(url.as_str(), None::<&str>);
                    false
                }
            }
        })
        .on_page_load(|webview, payload| match payload.event() {
            PageLoadEvent::Started => {
                *webview.state::<State>().current.lock().unwrap() = Some(payload.url().clone());
            }
            // A new page knows nothing of it.
            PageLoadEvent::Finished => {
                if let Some(window) = webview.get_webview_window(MAIN) {
                    tell_fullscreen(&window, true);
                }
            }
        })
        .on_new_window(move |url, _| {
            let _ = popups.opener().open_url(url.as_str(), None::<&str>);
            NewWindowResponse::Deny
        })
        .on_download(move |_, event| {
            match event {
                DownloadEvent::Requested { destination, .. } => {
                    let Ok(dir) = downloading.path().download_dir() else {
                        return false;
                    };
                    let name = destination
                        .file_name()
                        .map(|n| n.to_os_string())
                        .unwrap_or_else(|| "download".into());
                    *destination = downloads::unused_path(&dir, &name);
                }
                DownloadEvent::Finished {
                    path: Some(path),
                    success: true,
                    ..
                } => {
                    let _ = downloading.opener().reveal_item_in_dir(path);
                }
                _ => {}
            }
            true
        })
        .build()
}

/// The web app's own top bars hold the window buttons (and are where the window drags from): it lays them out
/// around the buttons when the page is marked `data-desktop="macos"`. The buttons' position lines them up with its
/// top row.
#[cfg(target_os = "macos")]
fn title_bar(builder: WebviewWindowBuilder<'_, Wry, AppHandle>) -> WebviewWindowBuilder<'_, Wry, AppHandle> {
    builder
        .title_bar_style(tauri::TitleBarStyle::Overlay)
        .hidden_title(true)
        .traffic_light_position(tauri::LogicalPosition::new(22.0, 43.5))
        .initialization_script("document.documentElement.dataset.desktop = 'macos'")
}

#[cfg(not(target_os = "macos"))]
fn title_bar(builder: WebviewWindowBuilder<'_, Wry, AppHandle>) -> WebviewWindowBuilder<'_, Wry, AppHandle> {
    builder
}

/// Full screen hides the window buttons, so the page drops the room it keeps for them (`data-fullscreen`).
/// `always`: a page just loaded, which hasn't been told yet.
fn tell_fullscreen(window: &WebviewWindow, always: bool) {
    let full = window.is_fullscreen().unwrap_or(false);
    let told = std::mem::replace(&mut *window.state::<State>().fullscreen.lock().unwrap(), full);
    if always || told != full {
        let _ = window.eval(format!(
            "document.documentElement.toggleAttribute('data-fullscreen', {full})"
        ));
    }
}

/// Lets the server's pages move the window from their drag regions and zoom it with a double-click, the only
/// part of the app's API they get. Granted while the app runs; a server changed to adds its own.
fn allow_window_moves(app: &AppHandle, server: &Url) -> tauri::Result<()> {
    app.add_capability(
        CapabilityBuilder::new("server")
            .remote(format!("{server}*"))
            .local(false)
            .window(MAIN)
            .permission("core:window:allow-start-dragging")
            .permission("core:window:allow-internal-toggle-maximize"),
    )
}

#[derive(Debug, PartialEq)]
enum Navigation {
    Allow,
    /// The server, loaded again as the app's own load. When the page starts a load itself (a script setting
    /// `location`, a link), WebKit first offers it to whatever app claims the address, and a Safari web app made
    /// with Add to Dock claims its site: the server would open there, not here. Loads the app asks for aren't
    /// offered.
    Reissue,
    /// Any other address: the default browser, or mail app.
    Elsewhere,
}

/// What happens to a load of `url` in the window. `issued`: this side asked for it.
fn navigation(url: &Url, server: Option<&Url>, setup: Option<&Url>, current: Option<&Url>, issued: bool) -> Navigation {
    let Some(setup) = setup else {
        // The window's first load, before its address is known: the setup page itself.
        return Navigation::Allow;
    };
    if same_origin(url, setup) || matches!(url.scheme(), "about" | "blob" | "data") {
        return Navigation::Allow;
    }
    if !server.is_some_and(|server| same_origin(url, server)) {
        return Navigation::Elsewhere;
    }
    // Within the page (a #fragment) or a reload: no new load to offer elsewhere.
    if issued || current.is_some_and(|current| same_document(url, current)) {
        Navigation::Allow
    } else {
        Navigation::Reissue
    }
}

fn load(app: &AppHandle, url: Url) {
    let Some(window) = app.get_webview_window(MAIN) else {
        return;
    };
    *app.state::<State>().issued.lock().unwrap() = Some(url.clone());
    let _ = window.navigate(url);
}

/// `Url::origin` can't be used: to it, `tauri://localhost` is an opaque origin, never equal even to itself.
fn same_origin(a: &Url, b: &Url) -> bool {
    a.scheme() == b.scheme() && a.host() == b.host() && a.port_or_known_default() == b.port_or_known_default()
}

/// The same page, whatever the fragment.
fn same_document(a: &Url, b: &Url) -> bool {
    a[..url::Position::AfterQuery] == b[..url::Position::AfterQuery]
}

fn change_server(app: &AppHandle, window: &WebviewWindow) {
    let Some(mut setup) = app.state::<State>().setup.lock().unwrap().clone() else {
        return;
    };
    setup.set_query(Some("change"));
    let _ = window.navigate(setup);
}

fn menu(app: &AppHandle) -> tauri::Result<Menu<Wry>> {
    let separator = || PredefinedMenuItem::separator(app);
    let app_menu = Submenu::with_items(
        app,
        "notesd",
        true,
        &[
            &PredefinedMenuItem::about(app, None, None)?,
            &separator()?,
            &MenuItem::with_id(app, CHANGE_SERVER, "Change Server…", true, None::<&str>)?,
            &separator()?,
            &PredefinedMenuItem::services(app, None)?,
            &separator()?,
            &PredefinedMenuItem::hide(app, None)?,
            &PredefinedMenuItem::hide_others(app, None)?,
            &PredefinedMenuItem::show_all(app, None)?,
            &separator()?,
            &PredefinedMenuItem::quit(app, None)?,
        ],
    )?;
    let edit = Submenu::with_items(
        app,
        "Edit",
        true,
        &[
            &PredefinedMenuItem::undo(app, None)?,
            &PredefinedMenuItem::redo(app, None)?,
            &separator()?,
            &PredefinedMenuItem::cut(app, None)?,
            &PredefinedMenuItem::copy(app, None)?,
            &PredefinedMenuItem::paste(app, None)?,
            &PredefinedMenuItem::select_all(app, None)?,
        ],
    )?;
    let view = Submenu::with_items(
        app,
        "View",
        true,
        &[
            &MenuItem::with_id(app, RELOAD, "Reload", true, Some("CmdOrCtrl+R"))?,
            &separator()?,
            &PredefinedMenuItem::fullscreen(app, None)?,
        ],
    )?;
    let window = Submenu::with_items(
        app,
        "Window",
        true,
        &[
            &PredefinedMenuItem::minimize(app, None)?,
            &PredefinedMenuItem::maximize(app, None)?,
            &separator()?,
            &PredefinedMenuItem::close_window(app, None)?,
        ],
    )?;
    Menu::with_items(app, &[&app_menu, &edit, &view, &window])
}

#[cfg(test)]
mod tests {
    use super::*;

    fn url(s: &str) -> Url {
        Url::parse(s).unwrap()
    }

    const SERVER: &str = "https://notes.example.com/";
    const SETUP: &str = "tauri://localhost/index.html";

    fn decide(to: &str, current: Option<&str>, issued: bool) -> Navigation {
        let current = current.map(url);
        navigation(
            &url(to),
            Some(&url(SERVER)),
            Some(&url(SETUP)),
            current.as_ref(),
            issued,
        )
    }

    #[test]
    fn the_setup_page_stays_in_the_window() {
        assert_eq!(
            decide("tauri://localhost/index.html?change", Some(SERVER), false),
            Navigation::Allow
        );
        assert_eq!(decide("about:blank", None, false), Navigation::Allow);
    }

    #[test]
    fn the_server_loads_as_the_apps_own_load() {
        // From the setup page, or a page of the server's going to another (a script, a link).
        assert_eq!(
            decide("https://notes.example.com/", Some(SETUP), false),
            Navigation::Reissue
        );
        assert_eq!(
            decide("https://notes.example.com/notes/1", Some(SERVER), false),
            Navigation::Reissue
        );
        // That load, back from WebKit.
        assert_eq!(
            decide("https://notes.example.com/notes/1", Some(SERVER), true),
            Navigation::Allow
        );
    }

    #[test]
    fn within_the_page_needs_no_new_load() {
        let note = "https://notes.example.com/notes/1";
        assert_eq!(
            decide("https://notes.example.com/notes/1#heading", Some(note), false),
            Navigation::Allow
        );
        assert_eq!(decide(note, Some(note), false), Navigation::Allow);
    }

    #[test]
    fn everything_else_opens_elsewhere() {
        for to in [
            "https://notesd.app/docs/",
            "http://notes.example.com/",
            "https://notes.example.com:8443/",
            "mailto:a@b.c",
        ] {
            assert_eq!(decide(to, Some(SERVER), false), Navigation::Elsewhere, "{to}");
        }
        let setup = url(SETUP);
        assert_eq!(
            navigation(&url(SERVER), None, Some(&setup), None, false),
            Navigation::Elsewhere
        );
    }

    #[test]
    fn the_first_load_is_allowed() {
        assert_eq!(navigation(&url(SETUP), None, None, None, false), Navigation::Allow);
    }
}
