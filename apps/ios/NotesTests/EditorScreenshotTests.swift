import Foundation
import NotesCore
import Testing
import UIKit

@testable import Notes

/// The editor drawn to PNGs for judging spacing and line height by eye, beside Android's
/// (`mise run screenshots:android`). Runs only with TEST_RUNNER_SCREENSHOT_DIR=/some/dir.
@MainActor
struct EditorScreenshotTests {
    // Android's PROSE_MARKDOWN (ScreenshotTest), so the platforms compare line for line.
    static let prose = """
        # Saturday

        Pick up the **bike** from the shop before noon, then take the long way back along the river. \
        If it rains, swap the ride for the museum.

        Tickets are cheaper before ten, and the café on the top floor opens at nine.

        - Groceries
          - Bread
          - Coffee beans
        - Laundry

        Back home by six.

        ### Packing

        > Rest is part of the plan.
        >
        > Leave the laptop at home.

        ![A sketch of the route](notesd:image/\(String(repeating: "a", count: 64))?w=320&h=120)

        ```sh
        rsync -av ~/photos /backup
        ```

        Run `make deploy` when back.

        """

    nonisolated static let dir = ProcessInfo.processInfo.environment["SCREENSHOT_DIR"]

    @Test(.enabled(if: dir != nil), arguments: [UIUserInterfaceStyle.light, .dark])
    func prose(_ appearance: UIUserInterfaceStyle) throws {
        let scene = try #require(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        // An iPhone's width; tall enough that the whole note is in the viewport and laid out.
        let frame = CGRect(x: 0, y: 0, width: 393, height: 1400)
        let window = UIWindow(windowScene: scene)
        window.frame = frame
        window.overrideUserInterfaceStyle = appearance
        window.backgroundColor = Palette.bg
        let view = NoteTextView()
        view.frame = frame
        window.addSubview(view)
        window.isHidden = false
        defer { window.isHidden = true }
        view.setDocument(Document.forNote(title: "Typography", body: Markdown.parse(Self.prose)))
        view.layoutIfNeeded()
        if let layout = view.textLayoutManager { layout.ensureLayout(for: layout.documentRange) }
        view.layoutIfNeeded()
        let image = UIGraphicsImageRenderer(bounds: frame).image { _ in
            window.drawHierarchy(in: frame, afterScreenUpdates: true)
        }
        let name = appearance == .dark ? "editor-prose-dark" : "editor-prose-light"
        let url = URL(fileURLWithPath: try #require(Self.dir)).appending(path: "\(name).png")
        try #require(image.pngData()).write(to: url)
    }
}
