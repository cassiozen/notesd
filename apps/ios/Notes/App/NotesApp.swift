import NotesCore
import SwiftUI

@main
struct NotesApp: App {
    @State private var graph: AppGraph
    @Environment(\.scenePhase) private var scenePhase

    init() {
        Self.styleBars()
        let graph = AppGraph()
        _graph = State(initialValue: graph)
        // Registered before launch ends, as BGTaskScheduler requires.
        let engine = graph.engine
        SyncScheduler.registerBackgroundTask {
            let outcome = await engine.sync().outcome
            return outcome == .done || outcome == .notConnected
        }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(graph)
                .tint(Color.notesTint)
                .onAppear { applyAppearance() }
                .onChange(of: graph.appearance) { applyAppearance() }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active:
                graph.scheduler.enteredForeground()
                graph.enteredForeground()
            case .background:
                graph.scheduler.enteredBackground()
                graph.enteredBackground()
                // Locked notes lock when the app leaves the screen (§10.1), after saving what's typed.
                let locks = graph.locks
                Task { await locks.lock() }
            default: break
            }
        }
    }

    /// Titles in the serif, as on the web and Android; bar buttons in the accent.
    private static func styleBars() {
        let bar = UINavigationBar.appearance()
        bar.tintColor = Palette.tint
        bar.largeTitleTextAttributes = [
            .font: Typeface.scaled(Typeface.serif(36), as: .largeTitle), .foregroundColor: Palette.ink,
        ]
        bar.titleTextAttributes = [
            .font: Typeface.scaled(Typeface.serif(21), as: .headline), .foregroundColor: Palette.ink,
        ]
    }

    private func applyAppearance() {
        for scene in UIApplication.shared.connectedScenes {
            for window in (scene as? UIWindowScene)?.windows ?? [] {
                window.overrideUserInterfaceStyle = graph.appearance.style
            }
        }
    }
}

/// The notes, with the tags in a sidebar that slides in from the left. There's
/// no first-run onboarding on iOS: the app works offline until it's connected
/// to a server (from the empty list, the list's footer or Settings).
///
/// One split view for every width: narrow (an iPhone, the Duo folded or in
/// Split View) it's a stack, the open note over the list; wide (the Duo
/// unfolded) the note sits beside the list, split at the fold while it's
/// partly folded. Folding or unfolding keeps the same screens, so a note being
/// typed in stays open with its cursor.
struct RootView: View {
    @Environment(AppGraph.self) private var graph
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var nav = Navigator()
    @State private var drawer = Drawer()
    /// Partly folded: where the fold is, for the list to end there.
    @State private var fold: CGFloat?

    var body: some View {
        DrawerContainer(drawer: drawer, swipeToOpen: nav.wide || !nav.showsDetail) {
            NavigationSplitView(columnVisibility: .constant(.all), preferredCompactColumn: compactColumn) {
                NotesListView()
                    .navigationSplitViewColumnWidth(min: fold ?? 300, ideal: fold ?? 360, max: fold ?? 420)
                    // The list stays beside the note: no button to hide it.
                    .toolbar(removing: .sidebarToggle)
            } detail: {
                NavigationStack(path: detailPath) {
                    detailRoot.navigationDestination(for: Route.self) { destination($0) }
                }
                // Under the keyboard's bar, where the note's own background stops, the column shows.
                .background(Color.notesBg.ignoresSafeArea())
            }
            .navigationSplitViewStyle(.balanced)
            .onGeometryChange(for: CGFloat?.self) { proxy in
                Self.fold(in: proxy)
            } action: {
                fold = $0
            }
        } side: {
            TagsView {
                drawer.close()
                nav.openSettings()
            }
        }
        .environment(nav)
        .environment(drawer)
        // A note opened from elsewhere (a conflicted copy's banner) closes the sidebar under it.
        .onChange(of: nav.path) {
            if nav.showsDetail && !nav.wide && drawer.isOpen { drawer.close() }
            graph.editorOpen = nav.openNote != nil
        }
        .onChange(of: sizeClass, initial: true) { nav.wide = sizeClass == .regular }
        .background(Color.notesBg)
        .task { graph.scheduler.start() }
        // Asked here, not on the screen the password was typed on: that goes as soon as notes unlock.
        .alert(
            "Use \(graph.biometry.name) for locked notes?",
            // Its buttons answer; the binding's own reset mustn't answer before them.
            isPresented: Binding(get: { graph.biometrics.asking }, set: { _ in })
        ) {
            Button("Not Now", role: .cancel) { graph.biometrics.answer(false) }
            Button("Use \(graph.biometry.name)") { graph.biometrics.answer(true) }
        } message: {
            Text("Open locked notes with \(graph.biometry.name) instead of the lock password on this iPhone.")
        }
    }

    /// Narrow, going back from the detail empties it once it has slid away.
    private var compactColumn: Binding<NavigationSplitViewColumn> {
        Binding(
            get: { nav.compactColumn },
            set: { column in
                nav.compactColumn = column
                if column != .detail { nav.cleared() }
            })
    }

    /// The screens pushed over the detail's first one.
    private var detailPath: Binding<[Route]> {
        Binding(
            get: { Array(nav.path.dropFirst()) },
            set: { nav.path = Array(nav.path.prefix(1)) + $0 })
    }

    @ViewBuilder private var detailRoot: some View {
        if let first = nav.path.first {
            destination(first)
        } else {
            NothingOpen { nav.open(Uuid7.generate(), isNew: true) }
        }
    }

    @ViewBuilder private func destination(_ route: Route) -> some View {
        switch route {
        case .note(let id, let startTag, let isNew):
            NoteScreen(
                store: graph.store, images: graph.images, locks: graph.locks, id: id,
                startTag: startTag, isNew: isNew, initial: graph.opening(id), spaceId: graph.space
            )
            .id(id)
        case .settings: SettingsView()
        case .settingsSection(let section): SettingsSectionView(section: section)
        }
    }

    /// The fold's left edge while the Duo is partly folded (a vertical fold, active); nil otherwise.
    nonisolated private static func fold(in proxy: GeometryProxy) -> CGFloat? {
        guard #available(iOS 27.1, *) else { return nil }
        let fold = proxy.reservedRegions(kind: .division).first { $0.isActive && $0.frame.height > $0.frame.width }
        return fold.map { $0.frame.minX }
    }
}
