import SwiftUI

/// Settings' categories, in the order docs/contributing/settings-guidelines.md gives. Server is the account row at
/// the top.
enum SettingsSection: String, Hashable, CaseIterable {
    case server, security, spaces, integrations, appearance, device, about

    var title: String {
        switch self {
        case .server: "Server"
        case .security: "Security"
        case .spaces: "Spaces"
        case .integrations: "Integrations"
        case .appearance: "Appearance"
        case .device: "Sync & Data"
        case .about: "About"
        }
    }

    var symbol: String {
        switch self {
        case .server: "server.rack"
        case .security: "lock"
        case .spaces: "square.grid.2x2"
        case .integrations: "envelope"
        case .appearance: "circle.lefthalf.filled"
        case .device: "arrow.triangle.2.circlepath.icloud"
        case .about: "info.circle"
        }
    }
}
