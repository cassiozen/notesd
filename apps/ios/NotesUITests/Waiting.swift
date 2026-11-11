import XCTest

extension XCUIElement {
    /// `waitForExistence` checks once a second, so even an element already on screen costs a second, and the
    /// flows wait hundreds of times. This looks at once, then every tenth of a second.
    func appears(within timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while true {
            if exists { return true }
            if Date() >= deadline { return false }
            usleep(100_000)
        }
    }
}
