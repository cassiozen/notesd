import Foundation
import Testing

@testable import NotesCore

struct BlockMergeTests {
    @Test func mergesAsInTheSharedFixtures() {
        #expect(failures(Fixtures.merge) == [])
    }

    @Test func generatedCorpusMatchesTheWebsAnswers() {
        #expect(failures(Fixtures.mergeGenerated) == [])
    }

    @Test func givesUpOnHugeRewritesRatherThanStall() {
        func doc(_ prefix: String) -> String { (0..<2100).map { "\(prefix)\($0)" }.joined(separator: "\n\n") }
        guard case .conflict = BlockMerge.merge(base: doc("a"), mine: doc("b"), theirs: doc("c")) else {
            Issue.record("expected a conflict")
            return
        }
    }

    /// Byte for byte: String == would let canonically equivalent text pass.
    private func failures(_ cases: [Fixtures.MergeCase]) -> [String] {
        cases.compactMap { c in
            let got = BlockMerge.merge(base: c.base, mine: c.mine, theirs: c.theirs)
            let ok: Bool =
                switch (got, c.expect) {
                case (.clean(let a), .clean(let b)): a.same(as: b)
                case (.partial(let t, let m), .partial(let u, let n)): t.same(as: u) && m.same(as: n)
                case (.conflict, .conflict): true
                default: false
                }
            return ok ? nil : "\(c.name): expected \(c.expect) got \(got)"
        }
    }
}
