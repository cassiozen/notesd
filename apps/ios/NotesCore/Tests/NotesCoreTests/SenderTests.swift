import Testing

@testable import NotesCore

struct SenderTests {
    @Test func aNameTheServerWouldRefuseBareIsQuotedAndReadBack() {
        #expect(Sender.format(name: "Me", address: "a@b.c") == "Me <a@b.c>")
        #expect(Sender.format(name: " ", address: " a@b.c ") == "a@b.c")
        #expect(Sender.format(name: #"Doe, J. "Jo""#, address: "a@b.c") == #""Doe, J. \"Jo\"" <a@b.c>"#)
        let parsed = Sender.parse(#""Doe, J. \"Jo\"" <a@b.c>"#)
        #expect(parsed.name == #"Doe, J. "Jo""#)
        #expect(parsed.address == "a@b.c")
        #expect(Sender.parse("a@b.c") == ("", "a@b.c"))
    }

    @Test func aKeyIsCheckedAsTheServerChecksIt() {
        #expect(Sender.keyProblem("") == "Paste your Resend API key.")
        #expect(Sender.keyProblem("sk_wrong") == "A Resend API key starts with re_.")
        #expect(Sender.keyProblem("re_12 3456") == "A Resend API key starts with re_.")
        #expect(Sender.keyProblem("re_123456789") == nil)
    }
}
