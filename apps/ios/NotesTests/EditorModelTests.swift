import Foundation
import NotesCore
import Testing

@testable import Notes

/// The open note's lifecycle: following the store while shown, failed saves, deleting.
@MainActor
struct EditorModelTests {
    let dir = FileManager.default.temporaryDirectory.appending(path: "notes-editor-\(UUID().uuidString)")

    /// A store whose database opens on first use, so a missing directory makes every call fail until it's created.
    private func makeStore(createDir: Bool = true) -> NoteStore {
        if createDir { try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true) }
        return NoteStore(db: Database(path: dir.appending(path: "notes.db").path))
    }

    private func doc(_ title: String, _ body: String) -> Document {
        Document.forNote(title: title, body: Markdown.parse(body))
    }

    /// Polls `condition` for up to two seconds.
    private func eventually(_ condition: () -> Bool) async -> Bool {
        for _ in 0..<200 {
            if condition() { return true }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return condition()
    }

    @Test func runFollowsStoreChanges() async throws {
        let store = makeStore()
        let note = try #require(try await store.save("n1", title: "One", content: "body"))
        let model = EditorModel(store: store, id: "n1", initial: note)
        let running = Task { await model.run() }
        defer { running.cancel() }
        // The subscription may not exist yet; saving until it's seen covers that.
        for n in 0..<200 where !model.title.hasPrefix("Two") {
            try await store.save("n1", title: "Two \(n)", content: "body")
            try? await Task.sleep(for: .milliseconds(10))
        }
        #expect(model.title.hasPrefix("Two"))
    }

    @Test func aMergedNoteOffersUndo() async throws {
        let store = makeStore()
        try await store.save("n", title: "Plan", content: "One\n\nTwo\n")
        // Stands in for sync: the note changed underneath an editor that started from "One/Two".
        try await store.save("n", title: "Plan", content: "One, elsewhere\n\nTwo\n")
        try await store.save(
            "n", title: "Plan", content: "One\n\nTwo, here\n", basedOn: (title: "Plan", content: "One\n\nTwo\n"))
        let model = EditorModel(store: store, id: "n", initial: try await store.get("n"))
        let running = Task { await model.run() }
        defer { running.cancel() }
        #expect(await eventually { model.merged })

        await model.undoMerge()
        #expect(!model.merged)
        #expect(model.undoneAs == "Plan (Conflicted copy)")
        #expect(try await store.get("n")?.content == "One\n\nTwo, here\n")
    }

    @Test func modelIsFreedOnceRunIsCancelled() async throws {
        let store = makeStore()
        weak var weakModel: EditorModel?
        do {
            let model = EditorModel(store: store, id: "n1", fresh: true)
            weakModel = model
            let running = Task { await model.run() }
            try? await Task.sleep(for: .milliseconds(50))
            running.cancel()
            await running.value
        }
        #expect(await eventually { weakModel == nil })
    }

    @Test func failedSaveIsReportedAndRetried() async throws {
        let store = makeStore(createDir: false)
        let model = EditorModel(store: store, id: "n1", fresh: true)
        let text = doc("Title", "typed")
        model.edited()
        model.save(text)
        await model.flushed()
        #expect(model.saveProblem != nil)
        #expect(model.status == .unsaved)

        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        model.save(text)
        await model.flushed()
        #expect(model.saveProblem == nil)
        #expect(model.status == .saved)
        #expect(try await store.get("n1")?.title == "Title")
    }

    @Test func saveAfterDeleteDoesNotBringTheNoteBack() async throws {
        let store = makeStore()
        let note = try #require(try await store.save("n1", title: "One", content: "body"))
        let model = EditorModel(store: store, id: "n1", initial: note)
        model.edited()
        await model.delete()
        model.save(doc("One", "typed after"))
        await model.flushed()
        #expect(try await store.get("n1") == nil)
        #expect(try await store.list().isEmpty)
    }

    @Test func saveQueuedBeforeDeleteDoesNotBringTheNoteBack() async throws {
        let store = makeStore()
        let note = try #require(try await store.save("n1", title: "One", content: "body"))
        let model = EditorModel(store: store, id: "n1", initial: note)
        model.edited()
        model.save(doc("One", "first"))
        model.edited()
        model.save(doc("One", "second"))
        await model.delete()
        #expect(try await store.get("n1") == nil)
        #expect(try await store.list().isEmpty)
    }
}
