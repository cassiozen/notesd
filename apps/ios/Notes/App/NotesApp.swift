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
struct RootView: View {
    @Environment(AppGraph.self) private var graph
    @State private var path: [Route] = []
    @State private var drawer = Drawer()

    var body: some View {
        DrawerContainer(drawer: drawer, swipeToOpen: path.isEmpty) {
            NavigationStack(path: $path) {
                NotesListView(path: $path)
                    .navigationDestination(for: Route.self) { route in
                        switch route {
                        case .note(let id, let startTag, let isNew):
                            NoteScreen(
                                store: graph.store, images: graph.images, locks: graph.locks, id: id,
                                startTag: startTag, isNew: isNew, initial: graph.opening(id),
                                spaceId: graph.space, path: $path
                            )
                            .id(id)
                        case .settings: SettingsView()
                        }
                    }
            }
        } side: {
            TagsView {
                drawer.close()
                path.append(.settings)
            }
        }
        .environment(drawer)
        // A note opened from elsewhere (a conflicted copy's banner) closes the sidebar under it.
        .onChange(of: path) {
            if !path.isEmpty && drawer.isOpen { drawer.close() }
            graph.editorOpen = path.contains { if case .note = $0 { true } else { false } }
        }
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
}
