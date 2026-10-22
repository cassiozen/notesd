import NotesCore
import SwiftUI

/// The notes, newest first, in date sections; search as you type; the
/// filter chosen in the sidebar. Deleting is undoable for a few seconds.
struct NotesListView: View {
    @Environment(AppGraph.self) private var graph
    @Environment(Drawer.self) private var drawer
    @Environment(Navigator.self) private var nav

    @State private var notes: [NoteSummary] = []
    @State private var loaded = false
    @State private var hits: [SearchHit]?
    /// Searching: the matches in every other space (§7.7), listed once asked for.
    @State private var elsewhere: [SearchHit] = []
    /// How many match in the other spaces: `elsewhere` stops at a page.
    @State private var elsewhereCount = 0
    @State private var elsewhereShownFor: String?
    @State private var query = ""
    @State private var pendingDeletes: [String: Task<Void, Never>] = [:]
    @State private var pendingNotes = 0
    /// The large title has scrolled away: the bar shows it small.
    @State private var collapsed = false
    @State private var syncSheet = false
    @State private var connecting = false
    /// A note is being read before it opens; a second tap waits.
    @State private var opening = false
    @FocusState private var searching: Bool

    var body: some View {
        List {
            header
            if !notes.isEmpty || !query.isEmpty || graph.listFilter != nil {
                SearchField(
                    query: $query, prompt: (scope ?? (many ? spaceName : nil)).map { "Search in \($0)" } ?? "Search",
                    focused: $searching
                )
                .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 6, trailing: 16))
                .plainRow()
            }
            if let notice = graph.notice {
                Notice(text: notice) { withAnimation { graph.notice = nil } }
                    .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
                    .plainRow()
            }
            if case .signedOut = graph.account {
                SignedOutBanner { nav.openSettings() }
                    .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
                    .plainRow()
            }
            if let all = hits {
                // Notes waiting out the undo window are hidden, as in the list.
                let hits = all.filter { pendingDeletes[$0.id] == nil }
                let others = elsewhere.filter { pendingDeletes[$0.id] == nil }
                let othersCount = elsewhereCount - (elsewhere.count - others.count)
                if hits.isEmpty && othersCount <= 0 {
                    EmptyList(
                        kicker: "Search", title: "No matches",
                        text: "Nothing in your notes matches “\(query.trimmed)”.")
                } else if !hits.isEmpty {
                    GroupLabel(hits.count == 1 ? "1 result" : "\(hits.count) results")
                }
                ForEach(hits) { hit in
                    row(id: hit.id, locked: hit.locked) {
                        NoteRow(
                            title: hit.title, time: listTime(hit.updatedAt), preview: highlighted(hit.excerpt),
                            locked: hit.locked)
                    }
                }
                if othersCount > 0 {
                    if elsewhereShownFor == query.trimmed {
                        GroupLabel("Other spaces")
                        ForEach(others) { hit in
                            row(id: hit.id, locked: hit.locked, open: { openElsewhere(hit) }) {
                                NoteRow(
                                    title: hit.title, time: listTime(hit.updatedAt),
                                    preview: AttributedString(graph.spaceName(hit.spaceId) + " · ")
                                        + highlighted(hit.excerpt),
                                    locked: hit.locked)
                            }
                        }
                    } else {
                        Button("\(othersCount) more in \(otherSpaces)") {
                            elsewhereShownFor = query.trimmed
                        }
                        .font(.notesButton)
                        .foregroundStyle(Color.notesTint)
                        .frame(minHeight: 44)
                        .listRowInsets(EdgeInsets(top: 8, leading: 24, bottom: 8, trailing: 16))
                        .plainRow()
                    }
                }
            } else if loaded {
                let visible = notes.filter { pendingDeletes[$0.id] == nil }
                if visible.isEmpty { empty }
                ForEach(groupByDate(visible, updatedAt: \.updatedAt), id: \.label) { group in
                    GroupLabel(group.label)
                    ForEach(group.items) { note in
                        row(id: note.id, locked: note.locked) {
                            NoteRow(
                                title: note.title, time: listTime(note.updatedAt),
                                preview: AttributedString(preview(note)), locked: note.locked)
                        }
                    }
                }
            }
        }
        .listStyle(.plain)
        .environment(\.defaultMinListRowHeight, 0)
        .scrollContentBackground(.hidden)
        .scrollDismissesKeyboard(.immediately)
        .background { Backdrop() }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { _ = await graph.engine.sync() }
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button("Tags", systemImage: "sidebar.left") {
                    searching = false
                    drawer.open()
                }
            }
            ToolbarItem(placement: .principal) {
                if collapsed {
                    Text(title).font(.custom("InstrumentSerif-Regular", size: 21)).foregroundStyle(Color.ink)
                        .lineLimit(1)
                        .transition(.opacity)
                } else {
                    // Something must hold the place, or the bar shows the title anyway.
                    Color.clear.frame(width: 1, height: 1).accessibilityHidden(true)
                }
            }
        }
        .safeAreaBar(edge: .bottom) {
            // While searching, the keyboard needs the room more than the compose button does.
            if !searching { footer }
        }
        .task(id: Watched(filter: graph.listFilter, space: graph.space)) { await watch() }
        .task(id: Watched(filter: graph.listFilter, space: graph.space, query: query)) { await search() }
        .onDisappear { commitDeletes() }
        .sheet(isPresented: $syncSheet) {
            SyncSheet(status: graph.syncStatus, pending: pendingNotes) {
                syncSheet = false
                graph.scheduler.request()
            }
        }
        .sheet(isPresented: $connecting) {
            OnboardingView(model: OnboardingModel(accounts: graph.accounts, deviceName: graph.deviceName))
        }
    }

    private var title: String { scope ?? "All Notes" }

    /// More than one space (§7.7): search names the space, and finds notes in the others.
    private var many: Bool { graph.spaces.count > 1 }

    private var spaceName: String { graph.spaceName(graph.space) }

    /// With two spaces, the other one by name.
    private var otherSpaces: String {
        let others = graph.spaces.filter { $0.id != graph.space }
        return others.count == 1 ? others[0].name : "other spaces"
    }

    /// What the list follows: a change to any of these reloads it.
    private struct Watched: Hashable {
        var filter: NoteFilter?
        var space: String
        var query = ""
    }

    /// The filter's name, or nil for every note.
    private var scope: String? {
        switch graph.listFilter {
        case nil: nil
        case .untagged: "Untagged"
        case .locked: "Locked"
        case .tag(_, let name): "#" + name
        }
    }

    /// The large title, the filter shown, led by the space's icon so the space
    /// shows with the sidebar away; with a filter, a way back to all notes.
    private var header: some View {
        HStack(spacing: 12) {
            SpaceIconImage(icon: graph.spaces.first { $0.id == graph.space }?.icon ?? "", size: 30)
            Text(title)
                .font(.notesDisplay)
                .foregroundStyle(Color.ink)
                .lineLimit(1)
                .accessibilityAddTraits(.isHeader)
            if graph.listFilter != nil {
                Button {
                    withAnimation(.snappy) { graph.listFilter = nil }
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(Color.ink2)
                        .frame(width: 24, height: 24)
                        .background(Color.on, in: .circle)
                        .frame(width: 44, height: 44)
                        .contentShape(.rect)
                }
                .buttonStyle(PressScaleStyle())
                .accessibilityLabel("Show all notes")
            }
            Spacer(minLength: 0)
        }
        .frame(minHeight: 48)
        .accessibilityElement(children: .contain)
        // Half under the bar: the bar shows the title small.
        .onGeometryChange(for: Bool.self) {
            $0.frame(in: .global).midY < 100
        } action: { now in
            withAnimation(.easeOut(duration: 0.2)) { collapsed = now }
        }
        .listRowInsets(EdgeInsets(top: 0, leading: 20, bottom: 10, trailing: 8))
        .plainRow()
    }

    @ViewBuilder private var empty: some View {
        switch graph.listFilter {
        case nil:
            if many {
                EmptyList(
                    kicker: spaceName, title: "No notes in \(spaceName) yet",
                    text: "Notes you write here stay in \(spaceName).", action: newNote)
            } else {
                NoNotes(offline: graph.account == .none) { connecting = true }
            }
        case .untagged:
            EmptyList(kicker: "Untagged", title: "Nothing here yet", text: "Every note has a tag.", action: newNote)
        case .locked:
            EmptyList(
                kicker: "Locked", title: "Nothing here yet", text: "Lock a note from its menu to keep it here.",
                action: newNote)
        case .tag(_, let name):
            EmptyList(
                kicker: "#" + name, title: "Nothing here yet",
                text: "Notes you write while #\(name) is open start with the tag.", action: newNote)
        }
    }

    /// One note: tap opens (or `open`), swipe or long-press deletes.
    private func row(
        id: String, locked: Bool, open custom: (() -> Void)? = nil, @ViewBuilder _ label: () -> some View
    ) -> some View {
        Button {
            if let custom { custom() } else { open(id) }
        } label: {
            label()
        }
        // Beside the list, the open note's row stays marked.
        .buttonStyle(RowButtonStyle(selected: nav.wide && nav.openNote == id))
        .listRowInsets(EdgeInsets(top: 1, leading: 8, bottom: 1, trailing: 8))
        .plainRow()
        .swipeActions {
            Button("Delete", systemImage: "trash", role: .destructive) { delete(id) }
        }
        .contextMenu {
            Button("Open", systemImage: "doc.text") { if let custom { custom() } else { open(id) } }
            Button("Delete Note", systemImage: "trash", role: .destructive) { delete(id) }
        }
        .accessibilityAction(named: "Delete note") { delete(id) }
    }

    /// The count, and what's wrong with sync when something is; the compose button at the end.
    private var footer: some View {
        VStack(spacing: 8) {
            undoBar
            HStack {
                if drawer.isOpen {
                    // Dimmed under the sidebar, and outside what it hides from VoiceOver: left out.
                    EmptyView()
                } else if let problem = syncProblem {
                    Button {
                        if graph.syncStatus == .notConnected { connecting = true } else { syncSheet = true }
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: graph.syncStatus == .notConnected ? "iphone" : "icloud.slash")
                                .font(.system(size: 13, weight: .medium))
                            Text(countText.isEmpty ? problem : "\(countText) · \(problem)")
                        }
                        .font(.notesSmall)
                        .foregroundStyle(Color.ink2)
                        .frame(minHeight: 44)
                        .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .accessibilityShowsLargeContentViewer()
                } else {
                    Text(countText).font(.notesSmall).foregroundStyle(Color.ink3)
                        .accessibilityShowsLargeContentViewer()
                }
                Spacer()
                ComposeButton(action: newNote)
                    .keyboardShortcut("n", modifiers: .command)
            }
            .padding(.leading, 24)
            .padding(.trailing, 16)
        }
        .padding(.bottom, 4)
    }

    private var countText: String {
        let count = notes.count - pendingDeletes.count
        if hits != nil || count <= 0 { return "" }
        return count == 1 ? "1 note" : "\(count) notes"
    }

    /// Nil while sync is fine: the footer stays quiet.
    private var syncProblem: String? {
        switch graph.syncStatus {
        case .notConnected: "On this iPhone only"
        case .offline: pendingNotes > 0 ? "Offline · \(pendingNotes) not synced" : "Offline"
        case .failed: pendingNotes > 0 ? "\(pendingNotes) not synced" : "Sync problem"
        case .signedOut: nil
        case .syncing, .synced: nil
        }
    }

    @ViewBuilder private var undoBar: some View {
        if !pendingDeletes.isEmpty {
            HStack {
                Text(pendingDeletes.count == 1 ? "Note deleted" : "\(pendingDeletes.count) notes deleted")
                    .font(.notesBody)
                    .foregroundStyle(Color.ink)
                Spacer()
                Button("Undo") { undoDeletes() }.font(.notesButton).foregroundStyle(Color.notesTint)
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 12)
            .glassEffect(.regular, in: .capsule)
            .padding(.horizontal, 16)
            .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }

    private func preview(_ note: NoteSummary) -> String {
        let text = note.preview.split(separator: "\n").joined(separator: " · ")
        return text.isEmpty ? "No additional text" : text
    }

    private func watch() async {
        // Subscribed before the first read, so a change landing in between isn't missed.
        let changes = graph.store.changes()
        await reload()
        for await _ in changes { await reload() }
    }

    private func reload() async {
        let next = (try? await graph.store.list(graph.listFilter, space: graph.space)) ?? notes
        pendingNotes = (try? await graph.store.pendingNotes()) ?? 0
        if !query.trimmed.isEmpty { await runSearch() }
        if loaded && next != notes {
            withAnimation(.snappy) { notes = next }
        } else {
            notes = next
        }
        loaded = true
    }

    private func search() async {
        if query.trimmed.isEmpty {
            hits = nil
            elsewhere = []
            elsewhereCount = 0
            return
        }
        try? await Task.sleep(for: .milliseconds(120))
        if Task.isCancelled { return }
        await runSearch()
    }

    /// The current space's matches, and the other spaces' for the row under them.
    private func runSearch() async {
        hits = (try? await graph.store.search(query, filter: graph.listFilter, scope: .only(graph.space))) ?? []
        elsewhere = (try? await graph.store.search(query, scope: .except(graph.space))) ?? []
        elsewhereCount = (try? await graph.store.countElsewhere(query, space: graph.space)) ?? elsewhere.count
    }

    /// A match in another space: the list moves there, then the note opens.
    private func openElsewhere(_ hit: SearchHit) {
        graph.switchSpace(hit.spaceId)
        open(hit.id)
    }

    /// Reads the note first, so its screen slides in with the text already there.
    private func open(_ id: String) {
        guard !opening else { return }
        opening = true
        searching = false
        Task {
            await graph.preload(id)
            nav.open(id)
            opening = false
        }
    }

    private func newNote() {
        var tag: String?
        if case .tag(_, let name) = graph.listFilter { tag = name }
        searching = false
        nav.open(Uuid7.generate(), startTag: tag, isNew: true)
    }

    /// The note is only hidden until the undo window ends; deleting at once and
    /// restoring on undo would race sync, which could send the delete first.
    private func delete(_ id: String) {
        let store = graph.store
        withAnimation(.snappy) {
            pendingDeletes[id] = Task {
                try? await Task.sleep(for: .seconds(5))
                guard !Task.isCancelled else { return }
                try? await store.delete(id)
                withAnimation(.snappy) { pendingDeletes[id] = nil }
            }
        }
    }

    private func undoDeletes() {
        withAnimation(.snappy) {
            for task in pendingDeletes.values { task.cancel() }
            pendingDeletes = [:]
        }
    }

    private func commitDeletes() {
        let store = graph.store
        for (id, task) in pendingDeletes {
            task.cancel()
            Task { try? await store.delete(id) }
        }
        pendingDeletes = [:]
    }

    /// The excerpt with its matches marked, as the web's <mark>.
    private func highlighted(_ excerpt: String) -> AttributedString {
        var out = AttributedString()
        var marked = false
        for piece in excerpt.split(
            omittingEmptySubsequences: false, whereSeparator: { $0 == "\u{E000}" || $0 == "\u{E001}" })
        {
            var run = AttributedString(String(piece))
            if marked {
                run.backgroundColor = Color.accent.opacity(0.45)
                run.foregroundColor = Color.ink
            }
            out += run
            marked.toggle()
        }
        return out
    }
}

extension View {
    /// A list row with nothing of the list's own: no separator, no background.
    func plainRow() -> some View {
        listRowSeparator(.hidden).listRowBackground(Color.clear)
    }
}

/// "Today", "Previous 7 days": a quiet label over a group of rows.
private struct GroupLabel: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.notesKicker)
            .foregroundStyle(Color.ink3)
            .accessibilityAddTraits(.isHeader)
            .listRowInsets(EdgeInsets(top: 18, leading: 24, bottom: 6, trailing: 16))
            .plainRow()
            .selectionDisabled()
    }
}

/// A note's title, then when it changed and the start of its text ("Locked" for a locked note).
private struct NoteRow: View {
    let title: String
    let time: String
    let preview: AttributedString
    let locked: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title.trimmed.isEmpty ? "New note" : title)
                .font(.notesRowTitle)
                .foregroundStyle(Color.ink)
                .lineLimit(1)
            HStack(spacing: 8) {
                Text(time).font(.notesRowMeta.weight(.medium)).foregroundStyle(Color.ink2)
                    .fixedSize()
                if locked {
                    HStack(spacing: 3) {
                        Image(systemName: "lock").font(.system(size: 11, weight: .semibold))
                        Text("Locked")
                    }
                    .foregroundStyle(Color.ink3)
                } else {
                    Text(preview).foregroundStyle(Color.ink3).lineLimit(1)
                }
            }
            .font(.notesRowMeta)
        }
        .frame(maxWidth: .infinity, minHeight: 40, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .accessibilityElement(children: .combine)
    }
}

/// The search box at the top of the list (web: the list's search field).
private struct SearchField: View {
    @Binding var query: String
    let prompt: String
    var focused: FocusState<Bool>.Binding

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass").font(.system(size: 15, weight: .medium)).foregroundStyle(Color.ink3)
            TextField(prompt, text: $query, prompt: Text(prompt).foregroundStyle(Color.ink3))
                .font(.notesLede)
                .foregroundStyle(Color.ink)
                .submitLabel(.search)
                .autocorrectionDisabled()
                .focused(focused)
                .accessibilityLabel("Search notes")
            if !query.isEmpty {
                Button {
                    query = ""
                    focused.wrappedValue = false
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(Color.ink2)
                        .frame(width: 20, height: 20)
                        .background(Color.on, in: .circle)
                        .frame(width: 44, height: 44)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear search")
                .transition(.opacity.combined(with: .scale(scale: 0.6)))
            }
        }
        .padding(.leading, 14)
        .padding(.trailing, query.isEmpty ? 14 : 0)
        .frame(height: 46)
        .background(Color.field, in: .rect(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Color.hair))
        .contentShape(.rect)
        .onTapGesture { focused.wrappedValue = true }
        .animation(.snappy(duration: 0.2), value: query.isEmpty)
    }
}

/// Why notes are stuck on this iPhone, and a way to try again.
private struct SyncSheet: View {
    let status: SyncStatus
    let pending: Int
    let syncNow: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(pending == 0 ? "Not syncing" : pending == 1 ? "1 note not synced" : "\(pending) notes not synced")
                .font(.notesTitle)
                .foregroundStyle(Color.ink)
            Text(
                "They’re saved on this iPhone and sync on their own once the server can be reached."
            )
            .font(.notesBody)
            .foregroundStyle(Color.ink2)
            .fixedSize(horizontal: false, vertical: true)
            if case .failed(let message) = status {
                Text("The server said: \(message)").font(.notesSmall).foregroundStyle(Color.ink3)
            }
            Button(action: syncNow) {
                Text("Sync now").font(.notesButton).frame(maxWidth: .infinity, minHeight: 36)
            }
            .buttonStyle(.glassProminent)
            .padding(.top, 6)
        }
        .padding(24)
        .presentationDetents([.height(260)])
        .presentationBackground(Color.notesBg)
    }
}

/// Nothing to list: what this place is for, and a way to start.
private struct EmptyList: View {
    let kicker: String
    let title: String
    let text: String
    var action: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(kicker).font(.notesKicker).foregroundStyle(Color.ink3)
            Text(title).font(.notesHeading).foregroundStyle(Color.ink).accessibilityAddTraits(.isHeader)
            Text(text).font(.notesLede).foregroundStyle(Color.ink2)
            if let action {
                Button(action: action) {
                    Label("Write a note", systemImage: "square.and.pencil")
                        .font(.notesButton)
                        .foregroundStyle(Color(Palette.onSolid))
                        .padding(.horizontal, 18)
                        .frame(minHeight: 44)
                        .background(Color(Palette.solid), in: .capsule)
                }
                .buttonStyle(PressScaleStyle(scale: 0.95))
                .padding(.top, 8)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 28)
        .padding(.vertical, 40)
        .listRowInsets(EdgeInsets())
        .plainRow()
    }
}

/// The list with no notes at all. Working offline (never connected), it says
/// what a server adds and connects to one: on iOS that's offered here rather
/// than in a first-run onboarding, so the app works from the start without one.
private struct NoNotes: View {
    let offline: Bool
    let connect: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("No notes yet").font(.notesHeading).foregroundStyle(Color.ink).accessibilityAddTraits(.isHeader)
            if offline {
                VStack(alignment: .leading, spacing: 10) {
                    Text("Working offline").font(.notesKicker).foregroundStyle(Color.ink3)
                    Text(
                        "Your notes are saved on this iPhone. Connect to your notesd server to sync them across your devices; notes you’ve already written upload when you connect."
                    )
                    .font(.notesLede).foregroundStyle(Color.ink2)
                    Button(action: connect) {
                        Label("Connect to a server", systemImage: "arrow.triangle.2.circlepath")
                            .font(.notesButton)
                            .foregroundStyle(Color(Palette.onSolid))
                            .padding(.horizontal, 18)
                            .frame(minHeight: 44)
                            .background(Color(Palette.solid), in: .capsule)
                    }
                    .buttonStyle(PressScaleStyle(scale: 0.95))
                    .padding(.top, 8)
                }
                .padding(.top, 28)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 28)
        .padding(.vertical, 40)
        .listRowInsets(EdgeInsets())
        .plainRow()
    }
}

struct Notice: View {
    let text: String
    let dismiss: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Text(text).font(.notesSmall).foregroundStyle(Color.warnInk)
            Spacer(minLength: 0)
            Button("Dismiss", systemImage: "xmark", action: dismiss).labelStyle(.iconOnly).foregroundStyle(
                Color.warnInk)
        }
        .padding(12)
        .background(Color.warnBg, in: .rect(cornerRadius: 12))
    }
}

private struct SignedOutBanner: View {
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            HStack {
                Text("Signed out. Notes stay on this iPhone; sign in to sync them.").font(.notesSmall)
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
            }
            .foregroundStyle(Color.warnInk)
            .padding(12)
            .background(Color.warnBg, in: .rect(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }
}

/// What sync is doing, in a word or two.
struct SyncLabel: View {
    let status: SyncStatus
    let pending: Int

    var body: some View {
        Text(text).font(.notesSmall).foregroundStyle(Color.ink2)
    }

    private var text: String {
        switch status {
        case .notConnected: "On this iPhone only"
        case .syncing: "Syncing…"
        case .synced: pending > 0 ? "\(pending) not synced" : "Synced"
        case .offline: pending > 0 ? "Offline · \(pending) not synced" : "Offline"
        case .signedOut: "Signed out"
        case .failed: "Sync problem"
        }
    }
}
