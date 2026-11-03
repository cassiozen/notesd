// swift-tools-version: 6.2

import PackageDescription

// The app's logic without UIKit: document model, Markdown codec, plain text and
// tags, database, API client and sync. Builds for macOS too, so `swift test` runs
// on the Mac against the shared fixtures and a real notesd.
let package = Package(
    name: "NotesCore",
    platforms: [.iOS(.v26), .macOS(.v26)],
    products: [
        .library(name: "NotesCore", targets: ["NotesCore"])
    ],
    dependencies: [
        .package(path: "../../../shared/keychain-biometrics")
    ],
    targets: [
        .target(
            name: "NotesCore",
            dependencies: [.product(name: "NotesBiometrics", package: "keychain-biometrics")],
            resources: [
                // markdown-it, exactly the build the web app uses, and the web's tokenizer rules.
                .copy("Resources/markdown-it.min.js"),
                .copy("Resources/markdown.js"),
                .copy("Resources/migrations"),
            ]
        ),
        .testTarget(name: "NotesCoreTests", dependencies: ["NotesCore"]),
    ]
)
