fn main() {
    // Touch ID: the Swift bridge to the Keychain code the iOS app uses too (swift/, src/touch_id.rs).
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("macos") {
        swift_rs::SwiftLinker::new("14.0")
            .with_package("TouchIDBridge", "swift")
            .link();
    }
    // Listing the commands makes each one a permission, granted per origin: the bundled setup page gets the
    // server ones (capabilities/), the server's pages Touch ID's (main.rs, as their address is the user's).
    tauri_build::try_build(
        tauri_build::Attributes::new().app_manifest(tauri_build::AppManifest::new().commands(&[
            "get_server",
            "set_server",
            "open_server",
            "touch_id_available",
            "touch_id_seal",
            "touch_id_open",
            "touch_id_forget",
        ])),
    )
    .expect("failed to run tauri-build");
}
