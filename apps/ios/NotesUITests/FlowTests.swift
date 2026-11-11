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
    private func bluePixels(_ image: UIImage) -> Int { pixels(image, near: (51, 102, 178)) }

    /// How many pixels are about `rgb`, in a copy of `image` shrunk `shrink` times.
    private func pixels(_ image: UIImage, near rgb: (Int, Int, Int), shrink: Int = 4) -> Int {
        guard let cg = image.cgImage else { return 0 }
        let w = cg.width / shrink
        let h = cg.height / shrink
        var px = [UInt8](repeating: 0, count: w * h * 4)
        let ctx = CGContext(
            data: &px, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        var n = 0
        for i in stride(from: 0, to: px.count, by: 4)
        where abs(Int(px[i]) - rgb.0) < 25 && abs(Int(px[i + 1]) - rgb.1) < 25 && abs(Int(px[i + 2]) - rgb.2) < 25 {
            n += 1
        }
        return n
    }

    /// The colour behind the list at the screen's left edge, halfway down: the space's theme (§7.7).
    private func background(_ image: UIImage) -> (Int, Int, Int) {
        let cg = image.cgImage!
        var px = [UInt8](repeating: 0, count: 4)
        let ctx = CGContext(
            data: &px, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        let strip = cg.cropping(to: CGRect(x: 0, y: cg.height / 2, width: cg.width / 40, height: cg.height / 10))!
        ctx.draw(strip, in: CGRect(x: 0, y: 0, width: 1, height: 1))
        return (Int(px[0]), Int(px[1]), Int(px[2]))
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
        XCTAssertTrue(app.staticTexts["No notes yet"].appears(within: 5))
        XCTAssertTrue(app.staticTexts["Working offline"].exists)
        XCTAssertFalse(app.buttons["Write a note"].exists)
        try audit("empty list")
        screenshot("empty list")
        app.buttons["Connect to a server"].tap()
        XCTAssertTrue(app.textFields["Server address"].appears(within: 5))
        screenshot("connect")
        app.buttons["Cancel"].tap()
        XCTAssertTrue(app.staticTexts["No notes yet"].appears(within: 5))
        writeNewNote("Offline thoughts\nWritten with no server.")
        // Undo and Redo in the bar step through the typing.
        let editor = app.textViews.firstMatch
        let typed = editor.value as? String ?? ""
        let undo = app.buttons["Undo"]
        XCTAssertTrue(undo.appears(within: 5) && undo.isEnabled)
        undo.tap()
        XCTAssertNotEqual(editor.value as? String ?? "", typed, "Undo took the typing back")
        app.buttons["Redo"].tap()
        XCTAssertEqual(editor.value as? String ?? "", typed, "Redo put it back")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["Offline thoughts"].appears(within: 5))
        let local = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "On this iPhone only"))
        XCTAssertTrue(local.firstMatch.exists)

        // Settings says what syncs once there's a server, and offers one from the account row.
        openSidebar()
        app.buttons["Settings"].tap()
        XCTAssertTrue(app.staticTexts["Synced once you connect"].appears(within: 5))
        XCTAssertFalse(app.buttons["Security"].exists)
        XCTAssertFalse(app.buttons["Integrations"].exists)
        XCTAssertEqual(app.buttons["Sync & Data"].value as? String, "1 waiting")
        try audit("settings offline", [.sufficientElementDescription, .hitRegion])
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Not connected")).firstMatch.tap()
        XCTAssertTrue(app.buttons["Connect to a server"].appears(within: 5))
    }

    /// Spaces (§7.7) work offline: the sidebar always names the space, and its switcher makes new ones.
    /// Space info's name, edited where it's shown.
    private var nameEditor: XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Name")).firstMatch
    }

    func testSpaces() throws {
        app.launch()
        XCTAssertTrue(app.buttons["New note"].appears(within: 10))
        writeNewNote("Groceries\nMilk")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["Groceries"].appears(within: 5))
        // The list is titled by what it shows.
        XCTAssertTrue(app.staticTexts["All Notes"].exists)

        openSidebar()
        let notesSwitcher = app.buttons["Space: Notes"]
        XCTAssertTrue(notesSwitcher.appears(within: 5))
        notesSwitcher.tap()
        let newSpace = app.buttons["New Space…"]
        XCTAssertTrue(newSpace.appears(within: 5))
        // One space: the card and New Space only. The caption shows uppercased, so matched without case.
        let switchTo = app.staticTexts.matching(NSPredicate(format: "label ==[c] %@", "Switch to")).firstMatch
        XCTAssertFalse(switchTo.exists)
        screenshot("space switcher, one space")
        newSpace.tap()
        let name = app.textFields["Name"]
        XCTAssertTrue(name.appears(within: 5))
        // The sheet focuses the field on appear, a moment after it exists: typing sooner goes nowhere.
        let focused = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "hasKeyboardFocus == true"), object: name)
        XCTAssertEqual(XCTWaiter().wait(for: [focused], timeout: 5), .completed, "the name field has the keyboard")
        app.typeText("Work")
        // An icon is required too.
        app.buttons["Create"].tap()
        XCTAssertTrue(app.staticTexts["Choose an icon."].appears(within: 5))
        app.buttons["Diary"].tap()
        screenshot("new space")
        app.buttons["Create"].tap()

        // Straight to the new, empty space, in its icon's colours.
        XCTAssertTrue(app.staticTexts["No notes in Work yet"].appears(within: 10))
        writeNewNote("Standup\nStatus")
        screenshot("a note in the diary's theme")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["Standup"].appears(within: 5))
        XCTAssertFalse(app.staticTexts["Groceries"].exists)
        try audit("a second space")
        screenshot("the diary's theme")
        let diary = background(app.screenshot().image)

        openSidebar()
        let switcher = app.buttons["Space: Work"]
        XCTAssertTrue(switcher.appears(within: 5))
        switcher.tap()
        // The current space is the card; the others are listed to switch to. Without traits a row is its name
        // alone: private goes unsaid.
        let notes = app.buttons["Notes"]
        XCTAssertTrue(notes.appears(within: 5))
        XCTAssertTrue(switchTo.exists)
        XCTAssertFalse(app.buttons["Work"].exists)
        XCTAssertFalse(app.staticTexts["Only you"].exists)
        screenshot("space switcher")
        try audit("space switcher", [.sufficientElementDescription, .hitRegion])

        // Holding a row opens that space's info (§7.8) without switching: the default can't be deleted.
        notes.press(forDuration: 1)
        XCTAssertTrue(app.staticTexts["This space can be renamed but not deleted."].appears(within: 5))
        XCTAssertTrue(nameEditor.exists)
        XCTAssertTrue(app.buttons["Change icon"].exists)
        XCTAssertFalse(app.buttons["Delete Space…"].isEnabled)
        screenshot("space info")
        try audit("space info", [.sufficientElementDescription, .hitRegion])
        // Done: back on the sidebar it was opened from.
        app.buttons["Done"].tap()
        XCTAssertTrue(switcher.appears(within: 5))
        switcher.tap()
        XCTAssertTrue(notes.appears(within: 5))
        notes.tap()
        // The sidebar closes onto the space's list, back in the app icon's colours.
        XCTAssertTrue(app.staticTexts["Groceries"].appears(within: 5))
        XCTAssertTrue(app.staticTexts["Groceries"].isHittable)
        XCTAssertFalse(app.staticTexts["Standup"].exists)
        sleep(1)
        screenshot("the app icon's theme")
        let home = background(app.screenshot().image)
        XCTAssertNotEqual([diary.0, diary.1, diary.2], [home.0, home.1, home.2])

        // The card's Info is the current space's.
        openSidebar()
        XCTAssertTrue(app.buttons["Space: Notes"].appears(within: 5))
        app.buttons["Space: Notes"].tap()
        let info = app.buttons["Space info"]
        XCTAssertTrue(info.appears(within: 5))
        info.tap()
        XCTAssertTrue(app.staticTexts["This space can be renamed but not deleted."].appears(within: 5))
        app.buttons["Done"].tap()

        // Settings › Spaces: each row opens its Space info.
        XCTAssertTrue(app.buttons["Settings"].appears(within: 5))
        app.buttons["Settings"].tap()
        let spaces = app.buttons["Spaces"]
        XCTAssertTrue(spaces.appears(within: 5))
        spaces.tap()
        let work = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Work")).firstMatch
        XCTAssertTrue(work.appears(within: 5))
        work.tap()
        XCTAssertTrue(app.staticTexts["Move or delete its notes to delete this space."].appears(within: 5))
        XCTAssertFalse(app.buttons["Delete Space…"].isEnabled)

        // Its owner renames it where the name is shown, and picks an icon under the header: each saved as made.
        let nameField = nameEditor
        nameField.tap()
        nameField.typeText("Office\n")
        app.buttons["Change icon"].tap()
        let castle = app.buttons["Castle"]
        XCTAssertTrue(castle.appears(within: 5))
        castle.tap()
        XCTAssertTrue(castle.isSelected)
        screenshot("space info, icons")
        app.buttons["Done"].tap()
        let office = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Office,")).firstMatch
        XCTAssertTrue(office.appears(within: 5))
        office.tap()
        XCTAssertEqual(app.buttons["Change icon"].value as? String, "Castle")
        app.buttons["Done"].tap()
    }

    /// As another account would, from its own device: a JSON call with `token`'s session.
    private func call(
        _ method: String, _ path: String, token: String, _ body: [String: Any]? = nil
    ) async throws -> [String: Any] {
        let server = try XCTUnwrap(env["NOTESD_URL"], "run through scripts/with-server.sh")
        var request = URLRequest(url: URL(string: server)!.appending(path: path))
        request.httpMethod = method
        if let body { request.httpBody = try JSONSerialization.data(withJSONObject: body) }
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if !token.isEmpty { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let (data, response) = try await URLSession.shared.data(for: request)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        XCTAssertTrue((200..<300).contains(code), "\(method) \(path): \(code) \(String(decoding: data, as: UTF8.self))")
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }

    /// Another account on the test server, made by the owner (an admin), signed in: its token.
    private func account(_ username: String) async throws -> String {
        let password = env["NOTESD_PASSWORD"] ?? "correct horse"
        let made = try await call("POST", "api/v1/users", token: token ?? "", ["username": username])
        let link = try XCTUnwrap((made["link"] as? [String: Any])?["token"] as? String)
        _ = try await call("POST", "api/v1/auth/setup-link/use", token: "", ["token": link, "password": password])
        let session = try await call(
            "POST", "api/v1/auth/login", token: "",
            ["username": username, "password": password, "client": "device", "deviceName": "ui test"])
        return try XCTUnwrap(session["accessToken"] as? String)
    }

    private func newId() -> String { UUID().uuidString.lowercased() }

    /// Pull to refresh: a sync now, not at the next 30-second one.
    private func refreshList() {
        let list = app.collectionViews.firstMatch
        XCTAssertTrue(list.appears(within: 5))
        list.swipeDown(velocity: .slow)
        sleep(2)
    }

    /// Publishing (§7.10) from Space info: on from the address the app reaches the server by, moved to another
    /// handle once asked, then off.
    func testPublishing() async throws {
        _ = try await api("GET", "api/v1/auth/session")
        let pam = try await account("Pam")
        let garden = newId()
        _ = try await call("PUT", "api/v1/spaces/\(garden)", token: pam, ["name": "Garden", "icon": "open-book"])
        _ = try await call(
            "POST", "api/v1/notes", token: pam,
            ["operationId": newId(), "id": newId(), "title": "Tomatoes", "content": "Red at last\n", "spaceId": garden])

        try await signIn(as: "Pam")
        openSidebar()
        let switcher = app.buttons["Space: Notes"]
        XCTAssertTrue(switcher.appears(within: 5))
        switcher.tap()
        let row = app.buttons["Garden"]
        XCTAssertTrue(row.appears(within: 10))
        row.press(forDuration: 1)
        let publish = app.buttons["Publish"]
        XCTAssertTrue(publish.appears(within: 10))
        publish.tap()

        let address = app.textFields["Address"]
        XCTAssertTrue(address.appears(within: 15))
        XCTAssertEqual(address.value as? String, "garden")
        XCTAssertTrue(app.buttons["Stop Publishing…"].exists)
        XCTAssertTrue(app.links["Open"].exists || app.buttons["Open"].exists)
        screenshot("ios-published")

        // A new handle asks first: links to the old address stop working.
        address.tap()
        address.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: "garden".count))
        address.typeText("our-plot\n")
        let change = app.buttons["Change Address"]
        XCTAssertTrue(change.appears(within: 5))
        change.tap()
        let moved = try await waitFor {
            let s = try await self.call("GET", "api/v1/spaces/\(garden)/publish", token: pam)
            return s["handle"] as? String == "our-plot"
        }
        XCTAssertTrue(moved)

        app.buttons["Stop Publishing…"].tap()
        let stop = app.buttons["Stop Publishing"]
        XCTAssertTrue(stop.appears(within: 5))
        stop.tap()
        XCTAssertTrue(app.buttons["Publish"].appears(within: 15))
        let settings = try await call("GET", "api/v1/spaces/\(garden)/publish", token: pam)
        XCTAssertEqual(settings["published"] as? Bool, false)
        XCTAssertEqual(settings["handle"] as? String, "our-plot")
    }

    /// Polls `check` for up to 10 s.
    private func waitFor(_ check: @escaping () async throws -> Bool) async throws -> Bool {
        for _ in 0..<50 {
            if try await check() { return true }
            try await Task.sleep(for: .milliseconds(200))
        }
        return false
    }

    /// Shared spaces (§7.9) between this app's account and Ana's, who works through the API: an invitation in
    /// the switcher, sharing from Space info, what Ana changed, who edited, and the checks on moving out and locking.
    func testSharedSpaces() async throws {
        // The app signs in as Sam, new here: the owner's spaces are the other flows'.
        _ = try await api("GET", "api/v1/auth/session")
        let sam = try await account("Sam")
        let ana = try await account("Ana")
        _ = try await account("Ben")
        let people = try await call("GET", "api/v1/people", token: ana)["people"] as? [[String: Any]] ?? []
        let samId = try XCTUnwrap(people.first { $0["username"] as? String == "Sam" }?["id"])
        // Sam's Family, with a note in it, made on the web.
        let family = newId()
        _ = try await call("PUT", "api/v1/spaces/\(family)", token: sam, ["name": "Family", "icon": "cookbook"])
        let groceries = newId()
        _ = try await call(
            "POST", "api/v1/notes", token: sam,
            ["operationId": newId(), "id": groceries, "title": "Groceries", "content": "Milk\n", "spaceId": family])
        // Ana's Book club, and Sam invited to it.
        let club = newId()
        _ = try await call("PUT", "api/v1/spaces/\(club)", token: ana, ["name": "Book club", "icon": "journal"])
        _ = try await call("POST", "api/v1/spaces/\(club)/invites", token: ana, ["userId": samId])

        try await signIn(as: "Sam")
        openSidebar()
        let switcher = app.buttons["Space: Notes"]
        XCTAssertTrue(switcher.appears(within: 5))
        switcher.tap()
        let join = app.buttons["Join Book club"]
        XCTAssertTrue(join.appears(within: 15))
        XCTAssertTrue(app.buttons["Decline Book club"].exists)
        sleep(1)
        screenshot("ios-shared-switcher-invitation")
        try audit("switcher with an invitation", [.sufficientElementDescription, .hitRegion])

        // Joining switches to the space.
        join.tap()
        XCTAssertTrue(app.staticTexts["No notes in Book club yet"].appears(within: 15))

        // A member's Space info: the name is the owner's to change; People lists who's in it, and Leave.
        openSidebar()
        let bookClubSwitcher = app.buttons["Space: Book club"]
        XCTAssertTrue(bookClubSwitcher.appears(within: 5))
        bookClubSwitcher.tap()
        app.buttons["Space info"].tap()
        XCTAssertTrue(app.staticTexts["Ana, Owner"].appears(within: 10))
        XCTAssertTrue(app.staticTexts["Sam, You"].exists)
        XCTAssertTrue(app.buttons["Leave Space…"].exists)
        XCTAssertFalse(nameEditor.exists)
        XCTAssertFalse(app.buttons["Options for Ana"].exists)
        XCTAssertFalse(app.buttons["Delete Space…"].exists)
        screenshot("ios-shared-member")
        app.buttons["Done"].tap()

        // Family's Space info: People says what inviting does and lists everyone else, not the account itself.
        XCTAssertTrue(bookClubSwitcher.appears(within: 5))
        bookClubSwitcher.tap()
        let familyRow = app.buttons["Family"]
        XCTAssertTrue(familyRow.appears(within: 5))
        familyRow.press(forDuration: 1)
        let lead = "Invite people to read and edit every note in Family, and add their own."
        XCTAssertTrue(app.staticTexts[lead].appears(within: 5))
        let inviteAna = app.buttons["Invite Ana"]
        XCTAssertTrue(inviteAna.appears(within: 10))
        XCTAssertTrue(app.buttons["Invite Ben"].exists)
        XCTAssertFalse(app.buttons["Invite Sam"].exists)
        screenshot("ios-shared-people-invite")
        // One tap invites, which shares it: Ana lands in the list, invited, and the glyph arrives.
        inviteAna.tap()
        XCTAssertTrue(app.staticTexts["Ana, Invited"].appears(within: 15))
        XCTAssertTrue(app.staticTexts["Sam, Owner · You"].exists)
        XCTAssertTrue(app.buttons["Stop Sharing…"].exists)
        screenshot("ios-shared-invited")
        // Ben too, then not: Cancel needs no confirmation.
        app.buttons["Invite Ben"].tap()
        let cancelBen = app.buttons["Cancel invitation for Ben"]
        XCTAssertTrue(cancelBen.appears(within: 15))
        cancelBen.tap()
        XCTAssertTrue(app.buttons["Invite Ben"].appears(within: 15))
        app.buttons["Done"].tap()
        app.buttons["Close sidebar"].tap()

        // Ana joins, edits the note and adds one.
        let invites = try await call("GET", "api/v1/invites", token: ana)["invites"] as? [[String: Any]] ?? []
        let invite = try XCTUnwrap(invites.first { $0["spaceId"] as? String == family }?["id"] as? String)
        _ = try await call("POST", "api/v1/invites/\(invite)/accept", token: ana, [:])
        let note = try await call("GET", "api/v1/notes/\(groceries)", token: ana)
        _ = try await call(
            "PUT", "api/v1/notes/\(groceries)", token: ana,
            [
                "operationId": newId(), "baseRevision": note["revision"] ?? 0, "title": "Groceries",
                "content": "Milk, eggs, bread\n",
            ])
        _ = try await call(
            "POST", "api/v1/notes", token: ana,
            [
                "operationId": newId(), "id": newId(), "title": "Birthday ideas", "content": "A kite\n",
                "spaceId": family,
            ])

        // Back on Book club's list: a sync brings what Ana did; the switcher says so.
        XCTAssertTrue(app.staticTexts["No notes in Book club yet"].appears(within: 5))
        refreshList()
        openSidebar()
        app.buttons["Space: Book club"].tap()
        let news = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Family, Ana edited 2 notes"))
            .firstMatch
        XCTAssertTrue(news.appears(within: 40), app.debugDescription)
        sleep(1)
        screenshot("ios-shared-switcher-news")

        // Space info: Ana's in; the owner manages her from her row, each change asked first where it was asked.
        news.press(forDuration: 1)
        XCTAssertTrue(app.staticTexts["Ana"].appears(within: 10))
        XCTAssertTrue(app.buttons["Invite Ben"].exists)
        XCTAssertTrue(app.buttons["Stop Sharing…"].exists)
        screenshot("ios-shared-space-info")
        try audit("shared", [.sufficientElementDescription, .hitRegion])
        app.buttons["Options for Ana"].tap()
        let makeOwner = app.buttons["Make Owner"]
        XCTAssertTrue(makeOwner.appears(within: 5))
        makeOwner.tap()
        XCTAssertTrue(app.staticTexts["Make Ana the owner of “Family”?"].appears(within: 5))
        screenshot("ios-shared-make-owner")
        // A popover on iOS 26 has no Cancel: tapping outside it is.
        if app.buttons["Cancel"].exists {
            app.buttons["Cancel"].tap()
        } else {
            app.otherElements["PopoverDismissRegion"].firstMatch.tap()
        }
        XCTAssertTrue(app.staticTexts["Ana"].appears(within: 5))
        app.buttons["Done"].tap()

        // Family's list says who edited; having shown it, what changed there is no longer news.
        XCTAssertTrue(app.buttons["Space: Book club"].appears(within: 5))
        app.buttons["Space: Book club"].tap()
        XCTAssertTrue(news.appears(within: 5))
        news.tap()
        let edited = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Ana · ")).firstMatch
        XCTAssertTrue(edited.appears(within: 10))
        screenshot("ios-shared-note-row")
        openSidebar()
        app.buttons["Space: Family"].tap()
        let bookClub = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Book club,")).firstMatch
        XCTAssertTrue(bookClub.appears(within: 5))
        bookClub.tap()
        XCTAssertTrue(app.staticTexts["No notes in Book club yet"].appears(within: 5))
        openSidebar()
        app.buttons["Space: Book club"].tap()
        let seen = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Family,")).firstMatch
        XCTAssertTrue(seen.appears(within: 5))
        XCTAssertFalse(seen.label.contains("edited"), seen.label)
        seen.tap()

        // The editor says who edited; Lock explains; moving out asks.
        let row = app.staticTexts["Groceries"]
        XCTAssertTrue(row.appears(within: 5))
        row.tap()
        let editedBy = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Edited by Ana · "))
            .firstMatch
        XCTAssertTrue(editedBy.appears(within: 10))
        screenshot("ios-shared-editor")
        app.buttons["More"].tap()
        app.buttons["Lock note"].tap()
        XCTAssertTrue(app.alerts["Locked notes can’t be in a shared space."].appears(within: 5))
        app.alerts.buttons["OK"].tap()
        app.buttons["More"].tap()
        app.buttons["Move to Space"].tap()
        app.buttons["Notes"].tap()
        let moveOut = app.alerts["Move “Groceries” to Notes?"]
        XCTAssertTrue(moveOut.appears(within: 5))
        XCTAssertTrue(moveOut.staticTexts["It will no longer be:\nVisible to Ana"].exists, moveOut.debugDescription)
        screenshot("ios-shared-move-out")
        moveOut.buttons["Cancel"].tap()
    }

    /// Wide (an unfolded iPhone Duo), a note opens beside the list, and another one takes its place.
    func testNotesOpenBesideTheListWhenWide() throws {
        app.launch()
        XCTAssertTrue(app.staticTexts["No notes yet"].appears(within: 5))
        guard app.staticTexts["Pick a note, or start one."].appears(within: 2) else {
            throw XCTSkip("A narrow window: notes open over the list")
        }
        writeNewNote("First note\nBeside the list.")
        XCTAssertTrue(app.staticTexts["First note"].appears(within: 5), "the list shows it beside the note")
        writeNewNote("Second note\nIn its place.")
        XCTAssertTrue(app.staticTexts["Second note"].appears(within: 5))
        XCTAssertEqual(app.textViews.count, 1, "one note open at a time")
        let heading = app.buttons["Heading 1"]
        XCTAssertTrue(heading.appears(within: 5))
        XCTAssertGreaterThanOrEqual(
            heading.frame.minX, app.textViews.firstMatch.frame.minX,
            "the formatting bar is under the note, not the list")
        app.staticTexts["First note"].tap()
        let first = app.textViews.matching(NSPredicate(format: "value CONTAINS %@", "Beside the list."))
        XCTAssertTrue(first.firstMatch.appears(within: 5), "the first note took the second's place")
        XCTAssertEqual(app.textViews.count, 1)
        screenshot("wide")
    }

    func testALongNoteScrolls() throws {
        app.launch()
        XCTAssertTrue(app.buttons["New note"].appears(within: 10))
        writeNewNote("Long note\n" + (1...60).map { "Line \($0)" }.joined(separator: "\n"))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["Long note"].appears(within: 5))
        // Opened again, at the top and with no keyboard: a swipe scrolls the text.
        app.staticTexts["Long note"].tap()
        let editor = app.textViews.firstMatch
        XCTAssertTrue(editor.appears(within: 5))
        let top = editor.screenshot().pngRepresentation
        editor.swipeUp()
        XCTAssertNotEqual(editor.screenshot().pngRepresentation, top, "the note scrolled")
    }

    /// Launches signed in to the test server, as the owner or (`user`) another account `account(_:)` made.
    /// The session comes from the API, not the sign-in screens: those cost ~20 s, and `signInOnScreen` covers them.
    private func signIn(as user: String? = nil) async throws {
        let server = try XCTUnwrap(env["NOTESD_URL"], "run through scripts/with-server.sh")
        let session = try await call(
            "POST", "api/v1/auth/login", token: "",
            [
                "username": user ?? env["NOTESD_USERNAME"] ?? "owner", "password": env["NOTESD_PASSWORD"] ?? "",
                "client": "device", "deviceName": "ui test",
            ])
        app.launchEnvironment["UITEST_SERVER"] = server
        app.launchEnvironment["UITEST_SIGN_IN"] = String(
            decoding: try JSONSerialization.data(withJSONObject: session), as: UTF8.self)
        app.launch()
        XCTAssertTrue(app.buttons["New note"].appears(within: 10))
    }

    /// Launches and signs in to the test server as the owner, through the screens.
    private func signInOnScreen(screenshotted: Bool = false) throws {
        let server = try XCTUnwrap(env["NOTESD_URL"], "run through scripts/with-server.sh")
        app.launch()
        let connect = app.buttons["Connect to a server"]
        XCTAssertTrue(connect.appears(within: 5))
        connect.tap()

        let address = app.textFields["Server address"]
        XCTAssertTrue(address.appears(within: 5))
        focus(address)
        address.typeText(server)
        let username = app.textFields["Username"]
        tap(app.buttons["Continue"], until: username, timeout: 10)

        focus(username)
        username.typeText(env["NOTESD_USERNAME"] ?? "owner")
        let password = app.secureTextFields["Password"]
        focus(password)
        password.typeText(env["NOTESD_PASSWORD"] ?? "")
        if screenshotted { screenshot("sign in") }
        app.buttons["Sign in"].tap()
        XCTAssertTrue(app.buttons["New note"].appears(within: 10))
    }

    /// The lock sheet after Lock note: the first lock on any device creates
    /// the lock password; after that (the flows share a server) it's asked for.
    private func enterLockPassword(screenshotted: Bool = false) throws {
        let create = app.navigationBars["Create a lock password"]
        let existing = app.navigationBars["Lock this note"]
        XCTAssertTrue(create.appears(within: 5) || existing.exists)
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

    /// Taps `button` until `result` shows: a tap while the screen is still moving (a sheet closing, the
    /// keyboard, the bar laying out again) can land on nothing. Only for taps that are safe to repeat.
    private func tap(_ button: XCUIElement, until result: XCUIElement, timeout: TimeInterval = 5) {
        for _ in 0..<3 {
            if button.exists && button.isHittable { button.tap() }
            if result.appears(within: timeout) { return }
        }
        XCTFail("tapping \(button) never showed \(result)")
    }

    /// Tags opens the sidebar, Settings at its foot. Right after a space switch or a sheet the sidebar is still
    /// closing, and the tap is lost.
    private func openSidebar() {
        tap(app.buttons["Tags"], until: app.buttons["Settings"], timeout: 2)
    }

    /// Opens a new note and types into it. A tap while a sheet is still closing (sign-in's, on CI) can be
    /// lost, and the text would go to the sheet's field: so it waits for the editor to have the keyboard.
    private func writeNewNote(_ text: String) {
        let editor = app.textViews.firstMatch
        for _ in 0..<3 {
            let newNote = app.buttons["New note"]
            XCTAssertTrue(newNote.appears(within: 10))
            newNote.tap()
            if editor.appears(within: 5) { break }
        }
        let deadline = Date().addingTimeInterval(5)
        while editor.value(forKey: "hasKeyboardFocus") as? Bool != true && Date() < deadline { usleep(200_000) }
        XCTAssertEqual(editor.value(forKey: "hasKeyboardFocus") as? Bool, true, "the new note took the keyboard")
        app.typeText(text)
    }

    /// The More menu's Find and Find and Replace open the system find bar; replacing is an edit like
    /// typing: it keeps the formatting, undoes in one step, and syncs.
    func testFindAndReplace() async throws {
        let content =
            "## Fruit apples\n\n- apples in a list\n- **apples** to buy\n\n> apples quoted\n\n`apples code`\n\n"
            + "Plain apples.\n"
        let note = try JSONSerialization.data(withJSONObject: [
            "operationId": UUID().uuidString.lowercased(), "id": UUID().uuidString.lowercased(), "title": "Apples",
            "content": content,
        ])
        _ = try await api("POST", "api/v1/notes", body: note)
        try await signIn()
        let row = app.staticTexts["Apples"]
        XCTAssertTrue(row.appears(within: 15))
        row.tap()
        let editor = app.textViews.firstMatch
        XCTAssertTrue(editor.appears(within: 5))

        // Find alone: no replace field. The search field has the keyboard straight away.
        tap(app.buttons["More"], until: app.buttons["Find"])
        XCTAssertTrue(app.buttons["Find and Replace"].exists)
        app.buttons["Find"].tap()
        let search = app.searchFields["find.searchField"]
        XCTAssertTrue(search.appears(within: 5))
        XCTAssertFalse(app.textFields["find.replaceField"].exists)
        XCTAssertEqual(search.value(forKey: "hasKeyboardFocus") as? Bool, true)
        search.typeText("apples")
        func waitForFocus(_ element: XCUIElement) -> Bool {
            let focused = NSPredicate(format: "hasKeyboardFocus == true")
            return XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: focused, object: element)], timeout: 5)
                == .completed
        }
        let count = app.staticTexts["find.resultLabel"]
        // The bar counts a moment after typing stops.
        func counted(_ n: Int) -> Bool {
            // "1 of 6", "5/5", or just "5" with none highlighted.
            let shown = NSPredicate(format: "label MATCHES %@", "(.*( of |/))?\(n)")
            return XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: shown, object: count)], timeout: 5)
                == .completed
        }
        XCTAssertTrue(counted(6), "title, heading, list, bold, quote, paragraph: \(count.label)")
        screenshot("find")
        // Not the hit regions: the system bar's clear button is small.
        try audit("find", .sufficientElementDescription)
        app.buttons["find.doneButton"].tap()
        XCTAssertTrue(search.waitForNonExistence(timeout: 5))
        // The bar's field leaves the tree before its keyboard is done sliding away: a tap before then lands on it.
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5))

        // Opened while reading, Done leaves the keyboard down; opened while typing, it goes back to typing.
        XCTAssertNotEqual(editor.value(forKey: "hasKeyboardFocus") as? Bool, true)
        // A tap while the screen still settles can be lost; another only moves the caret.
        var focused = false
        for _ in 0..<3 where !focused {
            editor.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.6)).tap()
            focused = waitForFocus(editor)
        }
        XCTAssertTrue(focused, "tapping the editor gives it the keyboard")
        // ⌘F, on the last search. The simulator sometimes drops a hardware key after the on-screen
        // keyboard was in use: a few tries.
        for _ in 0..<3 where !search.exists {
            app.typeKey(XCUIKeyboardKey.leftArrow.rawValue, modifierFlags: [])
            sleep(1)
            app.typeKey("f", modifierFlags: .command)
            _ = search.appears(within: 3)
        }
        XCTAssertTrue(search.exists, "⌘F")
        XCTAssertEqual(search.value as? String, "apples")
        app.buttons["find.doneButton"].tap()
        XCTAssertTrue(waitForFocus(editor), "back to typing")

        tap(app.buttons["More"], until: app.buttons["Find and Replace"])
        app.buttons["Find and Replace"].tap()
        XCTAssertTrue(search.appears(within: 5))
        let replace = app.textFields["find.replaceField"]
        replace.tap()
        replace.typeText("pears")
        let button = app.buttons["find.replaceButton"]
        XCTAssertTrue(button.isEnabled)
        button.tap()
        XCTAssertTrue(counted(5), count.label)
        screenshot("replaced one")
        defer { XCUIDevice.shared.appearance = .light }
        XCUIDevice.shared.appearance = .dark
        sleep(1)
        screenshot("replaced one, dark")
        XCUIDevice.shared.appearance = .light
        button.press(forDuration: 1)
        let all = app.buttons["Replace All"]
        XCTAssertTrue(all.appears(within: 5))
        all.tap()
        let replaced = editor.value as? String ?? ""
        XCTAssertFalse(replaced.lowercased().contains("apples"), replaced)

        // One Undo takes the whole Replace All back, not the single replace before it; the bar finds them again.
        app.buttons["Undo"].tap()
        let undone = editor.value as? String ?? ""
        // Which match the single replace took depends on where the tap left the cursor: only that there was one.
        XCTAssertEqual(undone.components(separatedBy: "pears").count, 2, undone)
        XCTAssertTrue(counted(5), "searched again after Undo: \(count.label)")
        screenshot("undone with the bar open")
        app.buttons["Redo"].tap()
        XCTAssertEqual(editor.value as? String, replaced)
        app.buttons["find.doneButton"].tap()

        // Saved and synced with its formatting; nothing of the find bar's in it.
        var synced: String?
        for _ in 0..<30 where synced == nil {
            let changes = try await api("GET", "api/v1/sync", query: "since=0&limit=100")["changes"] as? [[String: Any]]
            synced = changes?.compactMap { $0["content"] as? String }.first { $0.contains("pears in a list") }
            if synced == nil { try await Task.sleep(for: .seconds(1)) }
        }
        XCTAssertEqual(
            synced,
            "## Fruit pears\n\n- pears in a list\n- **pears** to buy\n\n> pears quoted\n\n`apples code`\n\n"
                + "Plain pears.\n")
    }

    func testSignInWriteAndSync() async throws {
        try signInOnScreen(screenshotted: true)

        // A note with a checklist, a heading and a tag.
        writeNewNote("Weekend\n")
        app.buttons["Checklist"].tap()
        app.typeText("Milk\nBread #groceries\nCoffee\n\n")
        app.typeText("Plans for the trip")
        screenshot("editor")

        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["Weekend"].appears(within: 5))
        // Pushed 2 s after the last edit; while sync is fine the list says nothing about it.
        var pushed = false
        for _ in 0..<30 where !pushed {
            let changes = try await api("GET", "api/v1/sync", query: "since=0&limit=100")["changes"] as? [[String: Any]]
            pushed = changes?.contains { ($0["title"] as? String) == "Weekend" } ?? false
            if !pushed { try await Task.sleep(for: .seconds(1)) }
        }
        XCTAssertTrue(pushed, "the note reached the server")
        let count = app.staticTexts.matching(NSPredicate(format: "label MATCHES %@", "[0-9]+ notes?")).firstMatch
        XCTAssertTrue(count.appears(within: 5), app.debugDescription)
        screenshot("list")
        try audit("list")

        // Search finds it by a word in the body.
        let search = app.textFields["Search notes"]
        XCTAssertTrue(search.appears(within: 5))
        search.tap()
        search.typeText("bre")
        XCTAssertTrue(app.staticTexts["1 result"].appears(within: 5))
        XCTAssertTrue(app.staticTexts["Weekend"].exists)
        screenshot("search")
        app.buttons["Clear search"].tap()

        // The sidebar lists the new tag.
        openSidebar()
        XCTAssertTrue(app.buttons["#groceries, 1 note"].appears(within: 5))
        screenshot("tags")
        try audit("tags")
        app.buttons["Settings"].tap()
        let account = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "owner"))
        XCTAssertTrue(account.firstMatch.appears(within: 5))
        screenshot("settings")
        // The last row on screen leaves it as the text grows, which the Dynamic Type check reports as
        // partial support; the form's fonts are the ones the other screens check.
        try audit("settings", [.sufficientElementDescription, .hitRegion])
        // Each category says its state; the account row opens Server.
        XCTAssertEqual(app.buttons["Spaces"].value as? String, "1")
        // The lock tests share the server, so its lock password may exist already.
        XCTAssertTrue(["Off", "Locked", "Unlocked"].contains(app.buttons["Security"].value as? String ?? ""))
        account.firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Signed in as"].appears(within: 5))
        screenshot("settings server")
        try audit("settings server", [.sufficientElementDescription, .hitRegion])
    }

    /// Settings → Integrations, signed in.
    private func openIntegrations() async throws {
        try await signIn()
        openSidebar()
        app.buttons["Settings"].tap()
        let integrations = app.buttons["Integrations"]
        XCTAssertTrue(integrations.appears(within: 5))
        integrations.tap()
    }

    /// Resend connects in two steps, then is managed and disconnected. Server-wide: it ends disconnected again,
    /// for the tests after it.
    func testResendConnectsInTwoSteps() async throws {
        try await openIntegrations()
        let connect = app.buttons["Connect Resend"]
        XCTAssertTrue(connect.appears(within: 10), app.debugDescription)
        connect.tap()

        let key = app.secureTextFields["API key"]
        XCTAssertTrue(key.appears(within: 5), app.debugDescription)
        app.buttons["Continue"].tap()
        XCTAssertTrue(app.staticTexts["Paste your Resend API key."].appears(within: 5))
        key.tap()
        key.typeText("re_123456789")
        app.buttons["Continue"].tap()

        let address = app.textFields["Address"]
        XCTAssertTrue(address.appears(within: 5))
        app.textFields["Name"].tap()
        app.textFields["Name"].typeText("Notes")
        address.tap()
        address.typeText("notes@example.com")
        screenshot("resend sender")
        app.buttons["Connect"].tap()
        XCTAssertTrue(app.staticTexts["Ready to Send"].appears(within: 10), app.debugDescription)
        screenshot("resend connected")
        app.buttons["Done"].tap()

        let manage = app.buttons["Manage Resend"]
        XCTAssertTrue(manage.appears(within: 5))
        XCTAssertTrue(app.staticTexts["Sending as Notes <notes@example.com>"].exists)
        manage.tap()
        let disconnect = app.buttons["Disconnect Resend"]
        XCTAssertTrue(disconnect.appears(within: 5))
        disconnect.tap()
        let confirm = app.buttons.matching(NSPredicate(format: "label == %@", "Disconnect")).firstMatch
        XCTAssertTrue(confirm.appears(within: 5))
        confirm.tap()
        XCTAssertTrue(app.buttons["Connect Resend"].appears(within: 10))
    }

    /// Only the refusal: the tests share the owner, and a real change signs out their sessions (NotesCore tests it).
    func testChangePasswordRefusesAWrongCurrentOne() async throws {
        try await signIn()
        openSidebar()
        app.buttons["Settings"].tap()
        let account = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "owner"))
        XCTAssertTrue(account.firstMatch.appears(within: 5))
        account.firstMatch.tap()
        let change = app.buttons["Change Password"]
        XCTAssertTrue(change.appears(within: 5))
        change.tap()

        let current = app.secureTextFields["Current password"]
        XCTAssertTrue(current.appears(within: 5), app.debugDescription)
        current.tap()
        current.typeText("not it")
        app.secureTextFields["New password"].tap()
        app.secureTextFields["New password"].typeText("a brand new one")
        app.secureTextFields["Confirm new password"].tap()
        app.secureTextFields["Confirm new password"].typeText("a brand new one")
        app.buttons["Change"].tap()
        XCTAssertTrue(app.staticTexts["That isn’t your current password."].appears(within: 10))
        // Still signed in: the refusal isn't a lost session.
        app.buttons["Cancel"].tap()
        XCTAssertTrue(app.staticTexts["Signed in as"].appears(within: 5))
    }

    /// The tests share the owner: the name keeps "owner" in it, so the others still find the account row if this
    /// one stops halfway, and it's cleared again at the end.
    func testANameIsSetFromServer() async throws {
        try await signIn()
        openSidebar()
        app.buttons["Settings"].tap()
        let account = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "owner"))
        XCTAssertTrue(account.firstMatch.appears(within: 5))
        account.firstMatch.tap()

        func edit(_ name: String) {
            let row = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Name and Photo")).firstMatch
            XCTAssertTrue(row.appears(within: 5), app.debugDescription)
            row.tap()
            let field = app.textFields["Name"]
            XCTAssertTrue(field.appears(within: 5), app.debugDescription)
            XCTAssertTrue(app.buttons["Choose Photo…"].exists)
            field.tap()
            if let old = field.value as? String, !old.isEmpty, old != "Name" {
                field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: old.count))
            }
            field.typeText(name)
            app.buttons["Save"].tap()
            XCTAssertTrue(app.staticTexts["Signed in as"].appears(within: 10))
        }

        edit("The owner")
        XCTAssertTrue(app.buttons["Name and Photo, The owner"].appears(within: 10), app.debugDescription)
        edit("")
        XCTAssertTrue(app.buttons["Name and Photo, Optional"].appears(within: 10), app.debugDescription)
    }

    /// Signed out, the notes stay, so only their owner may sign in again: anyone else's first sync would take them.
    func testSignInAgainKeepsTheUsername() async throws {
        try await signIn()
        openSidebar()
        app.buttons["Settings"].tap()
        let account = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "owner"))
        XCTAssertTrue(account.firstMatch.appears(within: 5))
        account.firstMatch.tap()
        let signOut = app.buttons["Sign out"]
        XCTAssertTrue(signOut.appears(within: 5))
        let row = signOut.frame
        signOut.tap()
        // The dialog has a Sign out too: the one that isn't the row.
        XCTAssertTrue(app.staticTexts["Sign out of this server?"].appears(within: 5))
        let confirm = app.buttons.matching(NSPredicate(format: "label == %@", "Sign out")).allElementsBoundByIndex
            .first { $0.frame != row }
        try XCTUnwrap(confirm, app.debugDescription).tap()

        let again = app.buttons["Sign in again"]
        XCTAssertTrue(again.appears(within: 10))
        again.tap()
        let username = app.textFields["Username"]
        XCTAssertTrue(username.appears(within: 5))
        XCTAssertEqual(username.value as? String, env["NOTESD_USERNAME"] ?? "owner")
        XCTAssertFalse(username.isEnabled, "the username is fixed while this iPhone holds its notes")
    }

    func testAPictureFromAnotherDeviceShows() async throws {
        try await notesWithAPicture()
        try await signIn()
        let note = app.staticTexts["Postcard"]
        XCTAssertTrue(note.appears(within: 15))
        note.tap()
        XCTAssertTrue(app.textViews.firstMatch.appears(within: 5))
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

    func testACodeFenceFromAnotherDeviceShowsItsCode() async throws {
        let content =
            "Two ways to say hello.\n\n```go\nfunc main() {\n\t// greet\n\tfmt.Println(\"hi\", 42)\n}\n```\n\n"
            + "```python\ndef greet(name):\n    return f\"hi {name}\"  # friendly\n```\n\n```\nplain, no language\n```\n"
        let note = try JSONSerialization.data(withJSONObject: [
            "operationId": UUID().uuidString.lowercased(), "id": UUID().uuidString.lowercased(), "title": "Code",
            "content": content,
        ])
        _ = try await api("POST", "api/v1/notes", body: note)
        try await signIn()
        let row = app.staticTexts["Code"]
        XCTAssertTrue(row.appears(within: 15))
        row.tap()
        XCTAssertTrue(app.textViews.firstMatch.appears(within: 5))
        sleep(1)
        // The fences are drawn into the text: look for the keyword purple and the string green,
        // at full size, as thin strokes fade into the block when shrunk.
        // Which fence shows what (labels, code without fence lines) is EditorTests' job.
        let shot = app.screenshot().image
        XCTAssertGreaterThan(pixels(shot, near: (195, 165, 249), shrink: 1), 100, "keywords are highlighted")
        XCTAssertGreaterThan(pixels(shot, near: (139, 210, 141), shrink: 1), 100, "strings are highlighted")
        screenshot("code fence")
        try audit("code fence")
    }

    /// Face ID (faked: it matches at once) is offered right after the lock
    /// password, then opens the locked note without it.
    func testFaceIDUnlocksALockedNote() async throws {
        app.launchArguments += ["--fake-biometrics=match"]
        try await signIn()
        writeNewNote("Bank\nPIN 4321")
        tap(app.buttons["More"], until: app.buttons["Lock note"])
        app.buttons["Lock note"].tap()
        try enterLockPassword()

        let offer = app.alerts["Use Face ID for locked notes?"]
        XCTAssertTrue(offer.appears(within: 15))
        screenshot("face id offer")
        // A system alert: iOS sizes its text, capped, and the audit reports the cap.
        try audit("face id offer", [.sufficientElementDescription, .hitRegion])
        offer.buttons["Use Face ID"].tap()

        // Locked from its own screen: Face ID waits to be asked.
        let lockNow = app.buttons["Lock now"]
        XCTAssertTrue(lockNow.appears(within: 15))
        let useFaceID = app.buttons["Use Face ID"]
        tap(lockNow, until: useFaceID)
        XCTAssertTrue(app.secureTextFields["Lock password"].exists)
        screenshot("locked note with face id")
        try audit("locked note with face id")

        // Opened again: Face ID is asked for at once and opens it.
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let row = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Bank")).firstMatch
        XCTAssertTrue(row.appears(within: 5))
        row.tap()
        let editor = app.textViews.firstMatch
        XCTAssertTrue(editor.appears(within: 10))
        XCTAssertTrue((editor.value as? String ?? "").contains("PIN 4321"), "\(editor.value ?? "")")

        // Settings has it on; turned off, the password is the only way in.
        app.navigationBars.buttons.element(boundBy: 0).tap()
        openSidebar()
        app.buttons["Settings"].tap()
        let security = app.buttons["Security"]
        XCTAssertTrue(security.appears(within: 5))
        XCTAssertEqual(security.value as? String, "Unlocked")
        security.tap()
        let toggle = app.switches["Unlock with Face ID"]
        XCTAssertTrue(toggle.appears(within: 5))
        let onlyHere = NSPredicate(format: "label CONTAINS %@", "only on this iPhone")
        XCTAssertTrue(app.staticTexts.matching(onlyHere).firstMatch.exists)
        XCTAssertEqual(toggle.value as? String, "1")
        // Tapped again only if still on: a second tap that landed would turn it back on.
        for _ in 0..<2 where toggle.value as? String != "0" {
            toggle.switches.firstMatch.tap()
            let deadline = Date().addingTimeInterval(5)
            while toggle.value as? String != "0" && Date() < deadline { usleep(200_000) }
        }
        XCTAssertEqual(toggle.value as? String, "0")
        // Back to Settings, then to the list.
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(security.appears(within: 5))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(row.appears(within: 5))
        row.tap()
        XCTAssertTrue(lockNow.appears(within: 10))
        tap(lockNow, until: app.secureTextFields["Lock password"])
        XCTAssertFalse(useFaceID.exists)
    }

    /// Dismissing the Face ID prompt is a choice: the password alone next time, not asked again.
    func testDismissedFaceIDIsNotOfferedAgain() async throws {
        app.launchArguments += ["--fake-biometrics=cancel"]
        try await signIn()
        writeNewNote("Diary\nDear diary")
        tap(app.buttons["More"], until: app.buttons["Lock note"])
        app.buttons["Lock note"].tap()
        try enterLockPassword()
        let offer = app.alerts["Use Face ID for locked notes?"]
        XCTAssertTrue(offer.appears(within: 15))
        offer.buttons["Use Face ID"].tap()

        let lockNow = app.buttons["Lock now"]
        XCTAssertTrue(lockNow.appears(within: 15))
        let field = app.secureTextFields["Lock password"]
        tap(lockNow, until: field)
        XCTAssertFalse(app.buttons["Use Face ID"].exists)
        field.tap()
        field.typeText(lockPassword)
        app.buttons["Unlock"].tap()
        XCTAssertTrue(lockNow.appears(within: 15))
        XCTAssertFalse(offer.appears(within: 2), "Face ID was offered again")
    }

    func testLockAndUnlockANote() async throws {
        try await signIn()
        writeNewNote("Secret plans\nMeet at noon")
        tap(app.buttons["More"], until: app.buttons["Lock note"])
        app.buttons["Lock note"].tap()
        try enterLockPassword(screenshotted: true)

        let lockNow = app.buttons["Lock now"]
        XCTAssertTrue(lockNow.appears(within: 15))
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

        let field = app.secureTextFields["Lock password"]
        tap(lockNow, until: field)
        XCTAssertTrue(app.staticTexts["This note is locked."].exists)
        XCTAssertTrue(app.keyboards.firstMatch.appears(within: 5))
        sleep(1)
        screenshot("locked note")
        try audit("locked note")
        field.tap()
        field.typeText("wrong password")
        app.buttons["Unlock"].tap()
        XCTAssertTrue(app.staticTexts["Wrong password."].appears(within: 15))
        field.tap()
        field.typeText(lockPassword)
        app.buttons["Unlock"].tap()
        let editor = app.textViews.firstMatch
        XCTAssertTrue(editor.appears(within: 15))
        XCTAssertTrue((editor.value as? String ?? "").contains("Meet at noon"), "\(editor.value ?? "")")

        // Removing the lock stores the text plainly again.
        tap(app.buttons["More"], until: app.buttons["Remove lock"])
        app.buttons["Remove lock"].tap()
        var plain = false
        for _ in 0..<30 where !plain {
            let changes = try await api("GET", "api/v1/sync", query: "since=0&limit=100")["changes"] as? [[String: Any]]
            plain = changes?.contains { ($0["content"] as? String)?.contains("Meet at noon") ?? false } ?? false
            if !plain { try await Task.sleep(for: .seconds(1)) }
        }
        XCTAssertTrue(plain, "the unlocked note reached the server as text")
    }

    /// Security's forms open in sheets: changing the lock password, and resetting it.
    func testLockPasswordSheetsOpenFromSecurity() async throws {
        try await signIn()
        writeNewNote("Secret plans\nMeet at noon")
        tap(app.buttons["More"], until: app.buttons["Lock note"])
        app.buttons["Lock note"].tap()
        try enterLockPassword()
        XCTAssertTrue(app.buttons["Lock now"].appears(within: 15))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        openSidebar()
        app.buttons["Settings"].tap()
        let security = app.buttons["Security"]
        XCTAssertTrue(security.appears(within: 5))
        security.tap()

        let change = app.buttons["Change lock password"]
        XCTAssertTrue(change.appears(within: 5))
        change.tap()
        XCTAssertTrue(app.secureTextFields["Current lock password"].appears(within: 5), app.debugDescription)
        app.buttons["Cancel"].tap()

        let forgot = app.buttons["Forgot the lock password?"]
        XCTAssertTrue(forgot.appears(within: 5))
        forgot.tap()
        XCTAssertTrue(app.secureTextFields["Account password"].appears(within: 5), app.debugDescription)
        app.buttons["Cancel"].tap()
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
        try await signIn()
        let first = app.staticTexts["Perfect World adventure"]
        XCTAssertTrue(first.appears(within: 15))
        sleep(2)
        screenshot("01 list")

        openSidebar()
        sleep(1)
        screenshot("02 tags")
        let rpg = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "rpg")).firstMatch
        if rpg.appears(within: 3) {
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
            openSidebar()
            let all = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "All notes")).firstMatch
            if all.appears(within: 3) { all.tap() }
            sleep(1)
        }

        first.tap()
        XCTAssertTrue(app.textViews.firstMatch.appears(within: 5))
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
        if search.appears(within: 5) {
            search.tap()
            search.typeText("players")
            sleep(1)
            screenshot("07 search")
            app.buttons["Clear search"].tap()
            sleep(1)
        } else {
            print("TREE", app.debugDescription)
        }

        openSidebar()
        app.buttons["Settings"].tap()
        sleep(1)
        screenshot("08 settings")
        app.buttons["Security"].tap()
        sleep(1)
        screenshot("09 settings security")
    }
}
