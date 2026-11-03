// swift-tools-version: 6.2

import PackageDescription

// The locked-notes key kept behind Face ID or Touch ID, in the Keychain: the iOS
// app's (through NotesCore) and the macOS desktop app's, so both behave alike.
let package = Package(
    name: "NotesBiometrics",
    platforms: [.iOS(.v26), .macOS(.v14)],
    products: [
        .library(name: "NotesBiometrics", targets: ["NotesBiometrics"])
    ],
    targets: [
        .target(name: "NotesBiometrics")
    ]
)
