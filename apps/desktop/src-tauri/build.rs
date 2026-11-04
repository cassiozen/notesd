fn main() {
    // Listing the commands makes each one a permission, granted per origin in capabilities/: the bundled setup
    // page gets these, the server's pages don't.
    tauri_build::try_build(
        tauri_build::Attributes::new().app_manifest(tauri_build::AppManifest::new().commands(&[
            "get_server",
            "set_server",
            "open_server",
        ])),
    )
    .expect("failed to run tauri-build");
}
