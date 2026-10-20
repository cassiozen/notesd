import CryptoKit
import UIKit
import XCTest

/// The app end to end against a real notesd: `scripts/with-server.sh` starts
/// one and passes its address in (TEST_RUNNER_NOTESD_URL). Each launch starts
/// from nothing (`--ui-testing`). Screenshots are kept in the test results.
@MainActor
final class FlowTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() async throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["--ui-testing"]
    }

    private var env: [String: String] { ProcessInfo.processInfo.environment }

    private func screenshot(_ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
        // For looking at by hand: TEST_RUNNER_SCREENSHOT_DIR=/some/dir.
        if let dir = env["SCREENSHOT_DIR"] {
            try? app.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir).appending(path: "\(name).png"))
        }
    }

    /// Calls the test server's API as the owner, as another device would.
    private func api(
        _ method: String, _ path: String, query: String? = nil, body: Data? = nil, type: String = "application/json"
    ) async throws -> [String: Any] {
        let server = try XCTUnwrap(env["NOTESD_URL"], "run through scripts/with-server.sh")
        if token == nil {
            let login = try JSONSerialization.data(withJSONObject: [
                "username": env["NOTESD_USERNAME"] ?? "owner", "password": env["NOTESD_PASSWORD"] ?? "",
                "client": "device", "deviceName": "ui test",
            ])
            token = ""  // the login itself goes without one
            token = try await api("POST", "api/v1/auth/login", body: login)["accessToken"] as? String
        }
        var url = URL(string: server)!.appending(path: path)
        if let query { url = URL(string: url.absoluteString + "?" + query)! }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = body
        request.setValue(type, forHTTPHeaderField: "Content-Type")
        if let token, !token.isEmpty { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let (data, response) = try await URLSession.shared.data(for: request)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        XCTAssertTrue((200..<300).contains(code), "\(method) \(path): \(code) \(String(decoding: data, as: UTF8.self))")
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }

    private var token: String?

    /// How many pixels are about the picture's blue (0.2, 0.4, 0.7).
    private func bluePixels(_ image: UIImage) -> Int {
        guard let cg = image.cgImage else { return 0 }
        let w = cg.width / 4
        let h = cg.height / 4
        var px = [UInt8](repeating: 0, count: w * h * 4)
        let ctx = CGContext(
            data: &px, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        var n = 0
        for i in stride(from: 0, to: px.count, by: 4)
        where abs(Int(px[i]) - 51) < 25 && abs(Int(px[i + 1]) - 102) < 25 && abs(Int(px[i + 2]) - 178) < 25 {
            n += 1
        }
        return n
    }

    /// A note with a picture, made through the API as if on the web.
    private func notesWithAPicture() async throws {
        let picture = UIGraphicsImageRenderer(size: CGSize(width: 400, height: 250)).jpegData(
            withCompressionQuality: 0.8
        ) {
            ctx in
            UIColor(red: 0.2, green: 0.4, blue: 0.7, alpha: 1).setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: 400, height: 250))
            UIColor.systemYellow.setFill()
            ctx.cgContext.fillEllipse(in: CGRect(x: 150, y: 75, width: 100, height: 100))
        }
        let id = SHA256.hash(data: picture).map { String(format: "%02x", $0) }.joined()
        _ = try await api("PUT", "api/v1/images/\(id)", body: picture, type: "image/jpeg")
        let note = try JSONSerialization.data(withJSONObject: [
            "operationId": UUID().uuidString.lowercased(), "id": UUID().uuidString.lowercased(), "title": "Postcard",
            "content": "Wish you were here:\n\n![sun over the sea](notesd:image/\(id)?w=1600&h=1000)\n\nMore soon.\n",
        ])
        _ = try await api("POST", "api/v1/notes", body: note)
    }

    private func audit(
        _ screen: String,
        _ types: XCUIAccessibilityAuditType = [.dynamicType, .sufficientElementDescription, .hitRegion]
    ) throws {
        // Rows fade in as the list changes: audit what they settle to.
        sleep(1)
        // The navigation bars, and the bottom bar's strip (the toolbar element itself spans the screen).
        let screen = app.frame
        let bars =
            app.navigationBars.allElementsBoundByIndex.map(\.frame)
            + [CGRect(x: 0, y: screen.maxY - 110, width: screen.width, height: 110)]
        try app.performAccessibilityAudit(for: types) {
            issue in
            let frame = issue.element?.frame ?? .zero
            // Nothing on screen (the keyboard's bookkeeping): nothing anyone can read.
            if frame.isEmpty { return true }
            let inBar = bars.contains { $0.contains(frame) }
            // Bars cap their text size; their labels show in the Large Content Viewer instead.
            if issue.auditType == .dynamicType && inBar { return true }
            // A row scrolled partly under the bottom bar is cut off, not small.
            if issue.auditType == .hitRegion && frame.maxY > screen.maxY - 110 { return true }
            print("AUDIT \(screen): \(issue.compactDescription) :: \(issue.element?.label ?? "-") \(frame)")
            return false
        }
    }

    func testWithoutAServer() throws {
        app.launch()
        // No onboarding on iOS: the app opens on the list, working offline, and offers a server.
        XCTAssertTrue(app.staticTexts["No notes yet"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Working offline"].exists)
        XCTAssertFalse(app.buttons["Write a note"].exists)
        try audit("empty list")
        screenshot("empty list")
        app.buttons["Connect to a server"].tap()
        XCTAssertTrue(app.textFields["Server address"].waitForExistence(timeout: 5))
        screenshot("connect")
        app.buttons["Cancel"].tap()
        XCTAssertTrue(app.staticTexts["No notes yet"].waitForExistence(timeout: 5))
        app.buttons["New note"].tap()
        app.typeText("Offline thoughts\nWritten with no server.")
        // Undo and Redo in the bar step through the typing.
        let editor = app.textViews.firstMatch
        let typed = editor.value as? String ?? ""
        let undo = app.buttons["Undo"]
        XCTAssertTrue(undo.waitForExistence(timeout: 5) && undo.isEnabled)
        undo.tap()
        XCTAssertNotEqual(editor.value as? String ?? "", typed, "Undo took the typing back")
        app.buttons["Redo"].tap()
        XCTAssertEqual(editor.value as? String ?? "", typed, "Redo put it back")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["Offline thoughts"].waitForExistence(timeout: 5))
        let local = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "On this iPhone only"))
        XCTAssertTrue(local.firstMatch.exists)
    }

    /// Spaces (§7.7) work offline: the sidebar always names the space, and its menu makes new ones.
    func testSpaces() throws {
        app.launch()
        XCTAssertTrue(app.buttons["New note"].waitForExistence(timeout: 10))
        app.buttons["New note"].tap()
        app.typeText("Groceries\nMilk")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["Groceries"].waitForExistence(timeout: 5))
        // The list is titled by what it shows.
        XCTAssertTrue(app.staticTexts["All Notes"].exists)

        app.buttons["Tags"].tap()
        let notesSwitcher = app.buttons["Space: Notes"]
        XCTAssertTrue(notesSwitcher.waitForExistence(timeout: 5))
        notesSwitcher.tap()
        let newSpace = app.buttons["New Space…"]
        XCTAssertTrue(newSpace.waitForExistence(timeout: 5))
        screenshot("space menu, one space")
        newSpace.tap()
        let name = app.textFields["Name"]
        XCTAssertTrue(name.waitForExistence(timeout: 5))
        app.typeText("Work")
        // An icon is required too.
        app.buttons["Create"].tap()
        XCTAssertTrue(app.staticTexts["Choose an icon."].waitForExistence(timeout: 5))
        app.buttons["Diary"].tap()
        screenshot("new space")
        app.buttons["Create"].tap()

        // Straight to the new, empty space.
        XCTAssertTrue(app.staticTexts["No notes in Work yet"].waitForExistence(timeout: 10))
        app.buttons["New note"].tap()
        app.typeText("Standup\nStatus")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["Standup"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["Groceries"].exists)
        try audit("a second space")

        app.buttons["Tags"].tap()
        let switcher = app.buttons["Space: Work"]
        XCTAssertTrue(switcher.waitForExistence(timeout: 5))
        switcher.tap()
        let notes = app.buttons["Notes"]
        XCTAssertTrue(notes.waitForExistence(timeout: 5))
        screenshot("space menu")
        notes.tap()
        // The sidebar closes onto the space's list.
        XCTAssertTrue(app.staticTexts["Groceries"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Groceries"].isHittable)
        XCTAssertFalse(app.staticTexts["Standup"].exists)
        app.buttons["Tags"].tap()
        XCTAssertTrue(app.buttons["Space: Notes"].waitForExistence(timeout: 5))
    }

    func testALongNoteScrolls() throws {
        app.launch()
        XCTAssertTrue(app.buttons["New note"].waitForExistence(timeout: 10))
        app.buttons["New note"].tap()
        app.typeText("Long note\n" + (1...60).map { "Line \($0)" }.joined(separator: "\n"))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["Long note"].waitForExistence(timeout: 5))
        // Opened again, at the top and with no keyboard: a swipe scrolls the text.
        app.staticTexts["Long note"].tap()
        let editor = app.textViews.firstMatch
        XCTAssertTrue(editor.waitForExistence(timeout: 5))
        let top = editor.screenshot().pngRepresentation
        editor.swipeUp()
        XCTAssertNotEqual(editor.screenshot().pngRepresentation, top, "the note scrolled")
    }

    /// Launches and signs in to the test server.
    private func signIn(screenshotted: Bool = false) throws {
        let server = try XCTUnwrap(env["NOTESD_URL"], "run through scripts/with-server.sh")
        app.launch()
        let connect = app.buttons["Connect to a server"]
        XCTAssertTrue(connect.waitForExistence(timeout: 5))
        connect.tap()

        let address = app.textFields["Server address"]
        XCTAssertTrue(address.waitForExistence(timeout: 5))
        address.tap()
        address.typeText(server)
        app.buttons["Continue"].tap()

        let username = app.textFields["Username"]
        XCTAssertTrue(username.waitForExistence(timeout: 10))
        username.tap()
        username.typeText(env["NOTESD_USERNAME"] ?? "owner")
        let password = app.secureTextFields["Password"]
        password.tap()
        password.typeText(env["NOTESD_PASSWORD"] ?? "")
        if screenshotted { screenshot("sign in") }
        app.buttons["Sign in"].tap()
        XCTAssertTrue(app.buttons["New note"].waitForExistence(timeout: 10))
    }

    /// The lock sheet after Lock note: the first lock on any device creates
    /// the lock password; after that (the flows share a server) it's asked for.
    private func enterLockPassword(screenshotted: Bool = false) throws {
        let create = app.navigationBars["Create a lock password"]
        let existing = app.navigationBars["Lock this note"]
        XCTAssertTrue(create.waitForExistence(timeout: 5) || existing.exists)
        let password = app.secureTextFields["Lock password"]
        focus(password)
        password.typeText(lockPassword)
        if existing.exists {
            app.buttons["Lock"].tap()
            return
        }
        let confirm = app.secureTextFields["Confirm password"]
        focus(confirm)
        confirm.typeText(lockPassword)
        if screenshotted { screenshot("create lock password") }
        app.buttons["Create and lock"].tap()
    }

    private let lockPassword = "lock pass 123"

    /// Taps a field until it has the keyboard: a tap while its sheet is still
    /// settling can land without focusing it.
    private func focus(_ field: XCUIElement) {
        for _ in 0..<5 {
            field.tap()
            if field.value(forKey: "hasKeyboardFocus") as? Bool == true { return }
            usleep(500_000)
        }
        XCTFail("\(field) never took the keyboard")
    }

    func testSignInWriteAndSync() async throws {
        try signIn(screenshotted: true)

        // A note with a checklist, a heading and a tag.
        let newNote = app.buttons["New note"]
        XCTAssertTrue(newNote.waitForExistence(timeout: 10))
        newNote.tap()
        app.typeText("Weekend\n")
        app.buttons["Checklist"].tap()
        app.typeText("Milk\nBread #groceries\nCoffee\n\n")
        app.typeText("Plans for the trip")
        screenshot("editor")

        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["Weekend"].waitForExistence(timeout: 5))
        // Pushed 5 s after the last edit; while sync is fine the list says nothing about it.
        var pushed = false
        for _ in 0..<30 where !pushed {
            let changes = try await api("GET", "api/v1/sync", query: "since=0&limit=100")["changes"] as? [[String: Any]]
            pushed = changes?.contains { ($0["title"] as? String) == "Weekend" } ?? false
            if !pushed { try await Task.sleep(for: .seconds(1)) }
        }
        XCTAssertTrue(pushed, "the note reached the server")
        let count = app.staticTexts.matching(NSPredicate(format: "label MATCHES %@", "[0-9]+ notes?")).firstMatch
        XCTAssertTrue(count.waitForExistence(timeout: 5), app.debugDescription)
        screenshot("list")
        try audit("list")

        // Search finds it by a word in the body.
        let search = app.textFields["Search notes"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        search.typeText("bre")
        XCTAssertTrue(app.staticTexts["1 result"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Weekend"].exists)
        screenshot("search")
        app.buttons["Clear search"].tap()

        // The sidebar lists the new tag.
        app.buttons["Tags"].tap()
        XCTAssertTrue(app.buttons["#groceries, 1 note"].waitForExistence(timeout: 5))
        screenshot("tags")
        try audit("tags")
        app.buttons["Settings"].tap()
        let account = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "owner"))
        XCTAssertTrue(account.firstMatch.waitForExistence(timeout: 5))
        screenshot("settings")
        // The last row on screen leaves it as the text grows, which the Dynamic Type check reports as
        // partial support; the form's fonts are the ones the other screens check.
        try audit("settings", [.sufficientElementDescription, .hitRegion])
    }

    func testAPictureFromAnotherDeviceShows() async throws {
        try await notesWithAPicture()
        try signIn()
        let note = app.staticTexts["Postcard"]
        XCTAssertTrue(note.waitForExistence(timeout: 15))
        note.tap()
        XCTAssertTrue(app.textViews.firstMatch.waitForExistence(timeout: 5))
        // The picture is drawn into the text, not an element of its own: look for its blue.
        var blue = 0
        for _ in 0..<20 where blue < 2000 {
            blue = bluePixels(app.screenshot().image)
            if blue < 2000 { try await Task.sleep(for: .milliseconds(500)) }
        }
        XCTAssertGreaterThan(blue, 2000, "the picture is drawn")
        screenshot("picture")
        try audit("picture")
    }

    /// Face ID (faked: it matches at once) is offered right after the lock
    /// password, then opens the locked note without it.
    func testFaceIDUnlocksALockedNote() throws {
        app.launchArguments += ["--fake-biometrics=match"]
        try signIn()
        app.buttons["New note"].tap()
        app.typeText("Bank\nPIN 4321")
        app.buttons["More"].tap()
        app.buttons["Lock note"].tap()
        try enterLockPassword()

        let offer = app.alerts["Use Face ID for locked notes?"]
        XCTAssertTrue(offer.waitForExistence(timeout: 15))
        screenshot("face id offer")
        // A system alert: iOS sizes its text, capped, and the audit reports the cap.
        try audit("face id offer", [.sufficientElementDescription, .hitRegion])
        offer.buttons["Use Face ID"].tap()

        // Locked from its own screen: Face ID waits to be asked.
        let lockNow = app.buttons["Lock now"]
        XCTAssertTrue(lockNow.waitForExistence(timeout: 15))
        lockNow.tap()
        let useFaceID = app.buttons["Use Face ID"]
        XCTAssertTrue(useFaceID.waitForExistence(timeout: 5))
        XCTAssertTrue(app.secureTextFields["Lock password"].exists)
        screenshot("locked note with face id")
        try audit("locked note with face id")

        // Opened again: Face ID is asked for at once and opens it.
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let row = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Bank")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.tap()
        let editor = app.textViews.firstMatch
        XCTAssertTrue(editor.waitForExistence(timeout: 10))
        XCTAssertTrue((editor.value as? String ?? "").contains("PIN 4321"), "\(editor.value ?? "")")

        // Settings has it on; turned off, the password is the only way in.
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["Tags"].tap()
        app.buttons["Settings"].tap()
        let toggle = app.switches["Unlock with Face ID"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 5))
        XCTAssertEqual(toggle.value as? String, "1")
        toggle.switches.firstMatch.tap()
        XCTAssertEqual(toggle.value as? String, "0")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.tap()
        XCTAssertTrue(lockNow.waitForExistence(timeout: 10))
        lockNow.tap()
        XCTAssertTrue(app.secureTextFields["Lock password"].waitForExistence(timeout: 5))
        XCTAssertFalse(useFaceID.exists)
    }

    /// Dismissing the Face ID prompt is a choice: the password alone next time, not asked again.
    func testDismissedFaceIDIsNotOfferedAgain() throws {
        app.launchArguments += ["--fake-biometrics=cancel"]
        try signIn()
        app.buttons["New note"].tap()
        app.typeText("Diary\nDear diary")
        app.buttons["More"].tap()
        app.buttons["Lock note"].tap()
        try enterLockPassword()
        let offer = app.alerts["Use Face ID for locked notes?"]
        XCTAssertTrue(offer.waitForExistence(timeout: 15))
        offer.buttons["Use Face ID"].tap()

        let lockNow = app.buttons["Lock now"]
        XCTAssertTrue(lockNow.waitForExistence(timeout: 15))
        lockNow.tap()
        let field = app.secureTextFields["Lock password"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["Use Face ID"].exists)
        field.tap()
        field.typeText(lockPassword)
        app.buttons["Unlock"].tap()
        XCTAssertTrue(lockNow.waitForExistence(timeout: 15))
        XCTAssertFalse(offer.waitForExistence(timeout: 2), "Face ID was offered again")
    }

    func testLockAndUnlockANote() async throws {
        try signIn()
        app.buttons["New note"].tap()
        app.typeText("Secret plans\nMeet at noon")
        app.buttons["More"].tap()
        app.buttons["Lock note"].tap()
        try enterLockPassword(screenshotted: true)

        let lockNow = app.buttons["Lock now"]
        XCTAssertTrue(lockNow.waitForExistence(timeout: 15))
        // Saved encrypted: the server has an envelope, never the text.
        var stored: [String: Any]?
        for _ in 0..<30 where stored == nil {
            let changes = try await api("GET", "api/v1/sync", query: "since=0&limit=100")["changes"] as? [[String: Any]]
            stored = changes?.first {
                ($0["title"] as? String) == "Secret plans"
                    && (($0["content"] as? String)?.hasPrefix("notesd:locked:v1:") ?? false)
            }
            if stored == nil { try await Task.sleep(for: .seconds(1)) }
        }
        let content = try XCTUnwrap(stored?["content"] as? String, "the locked note reached the server")
        XCTAssertFalse(content.contains("noon"))

        lockNow.tap()
        let field = app.secureTextFields["Lock password"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["This note is locked."].exists)
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        sleep(1)
        screenshot("locked note")
        try audit("locked note")
        field.tap()
        field.typeText("wrong password")
        app.buttons["Unlock"].tap()
        XCTAssertTrue(app.staticTexts["Wrong password."].waitForExistence(timeout: 15))
        field.tap()
        field.typeText(lockPassword)
        app.buttons["Unlock"].tap()
        let editor = app.textViews.firstMatch
        XCTAssertTrue(editor.waitForExistence(timeout: 15))
        XCTAssertTrue((editor.value as? String ?? "").contains("Meet at noon"), "\(editor.value ?? "")")

        // Removing the lock stores the text plainly again.
        app.buttons["More"].tap()
        app.buttons["Remove lock"].tap()
        var plain = false
        for _ in 0..<30 where !plain {
            let changes = try await api("GET", "api/v1/sync", query: "since=0&limit=100")["changes"] as? [[String: Any]]
            plain = changes?.contains { ($0["content"] as? String)?.contains("Meet at noon") ?? false } ?? false
            if !plain { try await Task.sleep(for: .seconds(1)) }
        }
        XCTAssertTrue(plain, "the unlocked note reached the server as text")
    }

    /// Screenshots of every main screen, for looking at by hand. Only with
    /// TEST_RUNNER_SCREENSHOT_DIR set; TEST_RUNNER_TOUR_ONLY skips the audits' flows.
    func testTour() async throws {
        try XCTSkipIf(env["SCREENSHOT_DIR"] == nil, "set TEST_RUNNER_SCREENSHOT_DIR to take the tour")
        let notes: [(String, String)] = [
            (
                "Perfect World adventure",
                "#rpg, #original_content\n\nThe players find themselves cutting a deal with the prosecutors "
                    + "after being charged of civil order disturbance.\n\n- [ ] Print the maps\n- [x] Pick the soundtrack"
            ),
            ("Phonetic pronunciation", "#quickref\n\nKah-ssio, not Cass-ee-oh."),
            ("Stocks", "#quickref\n\nGoogle, Cloudflare, Apple."),
            ("Camping List", "General: bottle opener, lantern, rope."),
            ("Villany", "#rpg, #original_content\n\nA mayor who sells the town's water."),
            ("IBAN Santander", "#quickref\n\nES13 0049 1500 0512 3456 7892"),
            ("Spain Data", "#quickref\n\nNIE: Z5189123"),
        ]
        for (title, content) in notes {
            let body = try JSONSerialization.data(withJSONObject: [
                "operationId": UUID().uuidString.lowercased(), "id": UUID().uuidString.lowercased(), "title": title,
                "content": content,
            ])
            _ = try await api("POST", "api/v1/notes", body: body)
        }
        try signIn()
        let first = app.staticTexts["Perfect World adventure"]
        XCTAssertTrue(first.waitForExistence(timeout: 15))
        sleep(2)
        screenshot("01 list")

        app.buttons["Tags"].tap()
        sleep(1)
        screenshot("02 tags")
        let rpg = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "rpg")).firstMatch
        if rpg.waitForExistence(timeout: 3) {
            rpg.tap()
            sleep(1)
            screenshot("03 filtered")
            // Swiping in from the left edge opens the sidebar too; tapping the list closes it.
            let edge = app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0.5))
            edge.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.5)))
            sleep(1)
            XCTAssertTrue(app.buttons["Settings"].isHittable, "the swipe opened the sidebar")
            app.buttons["Close sidebar"].tap()
            sleep(1)
            XCTAssertFalse(app.buttons["Settings"].exists, "tapping the list closed it")
            app.buttons["Tags"].tap()
            let all = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "All notes")).firstMatch
            if all.waitForExistence(timeout: 3) { all.tap() }
            sleep(1)
        }

        first.tap()
        XCTAssertTrue(app.textViews.firstMatch.waitForExistence(timeout: 5))
        sleep(1)
        screenshot("04 note")
        app.textViews.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3)).tap()
        sleep(2)
        screenshot("05 keyboard")
        app.buttons["More"].tap()
        sleep(1)
        screenshot("06 menu")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        sleep(1)
        app.navigationBars.buttons.element(boundBy: 0).tap()
        sleep(1)

        let search = app.textFields["Search notes"]
        if search.waitForExistence(timeout: 5) {
            search.tap()
            search.typeText("players")
            sleep(1)
            screenshot("07 search")
            app.buttons["Clear search"].tap()
            sleep(1)
        } else {
            print("TREE", app.debugDescription)
        }

        app.buttons["Tags"].tap()
        app.buttons["Settings"].tap()
        sleep(1)
        screenshot("08 settings")
    }
}
