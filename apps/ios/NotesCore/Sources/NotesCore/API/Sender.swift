import Foundation

/// A Resend sender as Settings edits it: a display name and an address, put together as "Name <a@b.c>".
public enum Sender {
    /// RFC 5322 specials in a display name need quotes, or the server refuses the address.
    private static let specials = CharacterSet(charactersIn: "()<>[]:;@\\,.\"")

    public static func format(name: String, address: String) -> String {
        let n = name.trimmingCharacters(in: .whitespaces)
        let a = address.trimmingCharacters(in: .whitespaces)
        if n.isEmpty { return a }
        if n.rangeOfCharacter(from: specials) == nil { return "\(n) <\(a)>" }
        let quoted = n.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
        return "\"\(quoted)\" <\(a)>"
    }

    /// The parts of "Name <a@b.c>"; a bare address has no name.
    public static func parse(_ from: String) -> (name: String, address: String) {
        guard let match = from.wholeMatch(of: /\s*(.*?)\s*<([^<>]+)>\s*/) else {
            return ("", from.trimmingCharacters(in: .whitespaces))
        }
        var name = String(match.1)
        if name.count >= 2, name.hasPrefix("\""), name.hasSuffix("\"") {
            name = String(name.dropFirst().dropLast()).replacing(/\\(.)/) { String($0.1) }
        }
        return (name, String(match.2).trimmingCharacters(in: .whitespaces))
    }

    /// The server's own check on a Resend key, made before it's sent: the key isn't saved until the sender's chosen.
    public static func keyProblem(_ key: String) -> String? {
        if key.isEmpty { return "Paste your Resend API key." }
        if !key.hasPrefix("re_") || key.count < 8 || key.contains(where: \.isWhitespace) {
            return "A Resend API key starts with re_."
        }
        return nil
    }
}
