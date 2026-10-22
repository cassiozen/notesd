import SwiftUI

/// Where the stack can go from the list.
enum Route: Hashable {
    /// A note by id; a new note gets its id up front and exists once it has text.
    case note(id: String, startTag: String? = nil, isNew: Bool = false)
    case settings
    case settingsSection(SettingsSection)
}

/// What's open beside the list: the detail's first screen, then the ones pushed
/// over it. The same state lays out as a stack on a narrow window (the list,
/// then the note over it) and side by side on a wide one (an unfolded iPhone
/// Duo), so folding or unfolding keeps the note open, as Android does.
@MainActor
@Observable
final class Navigator {
    var path: [Route] = [] {
        didSet { compactColumn = path.isEmpty ? .sidebar : .detail }
    }
    /// The list and the open note side by side.
    var wide = false
    /// Narrow: whether the list or the detail is the one on screen.
    var compactColumn: NavigationSplitViewColumn = .sidebar
    private var clearing: Task<Void, Never>?

    /// The open note's id, if the detail starts with one.
    var openNote: String? {
        if case .note(let id, _, _) = path.first { id } else { nil }
    }

    /// Something is open: narrow, it's the screen shown.
    var showsDetail: Bool { !path.isEmpty && (wide || compactColumn == .detail) }

    /// From the list: the note replaces what was beside it rather than stacking.
    func open(_ id: String, startTag: String? = nil, isNew: Bool = false) {
        if !isNew && openNote == id && path.count == 1 { return }
        path = [.note(id: id, startTag: startTag, isNew: isNew)]
    }

    /// Settings, in place of what was open.
    func openSettings() {
        path = [.settings]
    }

    /// Over the screen shown (a conflicted copy's original).
    func push(_ route: Route) {
        path.append(route)
    }

    /// The note `id` goes (deleted, moved), with anything pushed over it.
    func close(note id: String) {
        guard let i = path.firstIndex(where: { if case .note(id, _, _) = $0 { true } else { false } }) else { return }
        if i == 0 { closeAll() } else { path.removeSubrange(i...) }
    }

    /// Wide: the note beside the list goes, as when it's in another space now; Settings stays.
    func leaveNote() {
        if wide && openNote != nil { path = [] }
    }

    /// Back to the list.
    func closeAll() {
        if wide {
            path = []
        } else {
            compactColumn = .sidebar
            cleared()
        }
    }

    /// Narrow, the detail went (back, or `closeAll`). It's emptied once it has slid away, not while it's
    /// still on screen; opening something meanwhile keeps it.
    func cleared() {
        clearing?.cancel()
        clearing = Task {
            try? await Task.sleep(for: .milliseconds(500))
            guard !Task.isCancelled, !wide, compactColumn == .sidebar else { return }
            path = []
        }
    }
}
