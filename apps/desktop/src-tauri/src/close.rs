//! Closing the window leaves the app running, as Mac apps do: the window only hides, and clicking the Dock icon
//! brings it back with the page as it was.

use std::ptr::NonNull;
use std::sync::atomic::{AtomicBool, Ordering};

use block2::RcBlock;
use objc2::runtime::AnyObject;
use objc2_app_kit::NSWindowDidExitFullScreenNotification;
use objc2_foundation::{NSNotification, NSNotificationCenter};
use tauri::WebviewWindow;

/// Closed in full screen: hide once out of it.
static HIDE_AFTER_FULLSCREEN: AtomicBool = AtomicBool::new(false);

/// A window hidden in full screen leaves its Space behind, black. So it leaves full screen first, and hides when
/// AppKit says that's done: `is_fullscreen` turns false as soon as it's asked, before the animation.
pub fn watch(window: &WebviewWindow) -> tauri::Result<()> {
    let ns_window = window.ns_window()?.cast::<AnyObject>();
    let window = window.clone();
    let block = RcBlock::new(move |_: NonNull<NSNotification>| {
        if HIDE_AFTER_FULLSCREEN.swap(false, Ordering::Relaxed) {
            let _ = window.hide();
        }
    });
    // SAFETY: `ns_window` is the live NSWindow, kept for the app's life (closing never destroys it). No queue:
    // the block runs where AppKit posts, the main thread.
    let observer = unsafe {
        NSNotificationCenter::defaultCenter().addObserverForName_object_queue_usingBlock(
            Some(NSWindowDidExitFullScreenNotification),
            Some(&*ns_window),
            None,
            &block,
        )
    };
    // Never removed: the window lasts as long as the app.
    std::mem::forget(observer);
    Ok(())
}

pub fn hide(window: &WebviewWindow) {
    if window.is_fullscreen().unwrap_or(false) {
        HIDE_AFTER_FULLSCREEN.store(true, Ordering::Relaxed);
        let _ = window.set_fullscreen(false);
    } else {
        let _ = window.hide();
    }
}

pub fn show(window: &WebviewWindow) {
    // Back from the Dock before full screen was done leaving: stay shown.
    HIDE_AFTER_FULLSCREEN.store(false, Ordering::Relaxed);
    let _ = window.unminimize();
    let _ = window.show();
    let _ = window.set_focus();
}
