// swift-tools-version: 6.2

import PackageDescription

// The shared Keychain code (NotesBiometrics, the iOS app's too) as C functions for the Rust side, linked by
// swift-rs from build.rs.
let package = Package(
    name: "TouchIDBridge",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "TouchIDBridge", type: .static, targets: ["TouchIDBridge"])
    ],
    dependencies: [
        .package(path: "../../../../shared/keychain-biometrics")
    ],
    targets: [
        .target(
            name: "TouchIDBridge",
            dependencies: [.product(name: "NotesBiometrics", package: "keychain-biometrics")]
        )
    ]
)
