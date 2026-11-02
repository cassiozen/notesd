import Foundation
import Testing

@testable import NotesCore

/// shared/crypto-fixtures/locked.json, the vectors the web app and Android check too.
struct LockedNotesTests {
    struct Suite: Decodable {
        struct Kdf: Decodable {
            var name, password, salt, key: String
            var iterations: Int
        }
        struct Hkdf: Decodable {
            var name, prf, salt, key: String
        }
        struct Wrap: Decodable {
            var name, kind, kek, key, wrapped: String
        }
        struct Envelope: Decodable {
            var name, plaintext, key, envelope: String
        }
        struct Invalid: Decodable {
            var name, envelope, key: String
        }
        struct Image: Decodable {
            var name, key, sealed: String
            var plain: String?
        }
        struct Verifier: Decodable {
            var name, key, verifier: String
        }
        struct Unsupported: Decodable {
            var name, envelope: String
            var version: String?
        }
        struct ImageType: Decodable {
            var type: String
            var locked, supported: Bool
        }
        struct KeyringEntry: Decodable {
            var name, kind, verdict: String
            var kdf: NotesCore.Kdf?
            var iterations: Int?
        }
        var kdf: [Kdf]
        var hkdf: [Hkdf]
        var wraps: [Wrap]
        var envelopes: [Envelope]
        var invalid: [Invalid]
        var images: [Image]
        var verifiers: [Verifier]
        var unsupported: [Unsupported]
        var imageTypes: [ImageType]
        var keyring: [KeyringEntry]
    }

    static let fixtures: Suite = {
        let data = try! Data(contentsOf: Fixtures.sharedDir.appending(path: "crypto-fixtures/locked.json"))
        return try! JSONDecoder().decode(Suite.self, from: data)
    }()

    private var f: Suite { Self.fixtures }
    private func b(_ s: String) -> Data { try! LockedNotes.decode(s) }

    @Test func kdf() throws {
        for v in f.kdf {
            #expect(
                LockedNotes.encode(try LockedNotes.passwordKek(v.password, salt: b(v.salt), iterations: v.iterations))
                    == v.key, "\(v.name)")
        }
    }

    @Test func hkdf() {
        for v in f.hkdf {
            #expect(LockedNotes.encode(LockedNotes.passkeyKek(prf: b(v.prf), salt: b(v.salt))) == v.key, "\(v.name)")
        }
    }

    @Test func unwrap() throws {
        for v in f.wraps {
            let kind = try #require(WrapKind(rawValue: v.kind))
            #expect(try LockedNotes.encode(LockedNotes.unwrap(b(v.kek), v.wrapped, kind: kind)) == v.key, "\(v.name)")
            let other: WrapKind = kind == .password ? .recovery : .password
            #expect(throws: LockedError.self) { try LockedNotes.unwrap(b(v.kek), v.wrapped, kind: other) }
            let rewrapped = LockedNotes.wrap(b(v.kek), b(v.key), kind: kind)
            #expect(try LockedNotes.encode(LockedNotes.unwrap(b(v.kek), rewrapped, kind: kind)) == v.key)
        }
    }

    @Test func envelopes() throws {
        for v in f.envelopes {
            #expect(LockedNotes.isLocked(v.envelope), "\(v.name)")
            #expect(try LockedNotes.decrypt(b(v.key), v.envelope) == v.plaintext, "\(v.name)")
            let again = LockedNotes.encrypt(b(v.key), v.plaintext)
            #expect(again != v.envelope, "fresh nonce: \(v.name)")
            #expect(try LockedNotes.decrypt(b(v.key), again) == v.plaintext, "\(v.name)")
        }
    }

    @Test func refusesInvalid() {
        for v in f.invalid {
            // A v1 envelope that doesn't open: never mistaken for a newer version.
            #expect("\(v.name)") { try LockedNotes.decrypt(b(v.key), v.envelope) } throws: {
                ($0 as? LockedError)?.reason == .invalid
            }
        }
    }

    @Test func laterVersionsAreLockedButDontOpen() {
        let key = b(f.envelopes[0].key)
        for v in f.unsupported {
            #expect(LockedNotes.isLocked(v.envelope), "\(v.name)")
            #expect(LockedNotes.lockVersion(v.envelope) == v.version, "\(v.name)")
            #expect(LockedNotes.isUnsupported(v.envelope), "\(v.name)")
            // Never "wrong password".
            #expect(throws: LockedError.unsupportedVersion, "\(v.name)") { try LockedNotes.decrypt(key, v.envelope) }
        }
        for v in f.envelopes {
            #expect(LockedNotes.lockVersion(v.envelope) == "v1")
            #expect(!LockedNotes.isUnsupported(v.envelope))
        }
        for content in ["notesd:locked:v0:AAAA", "notesd:locked:v01:AAAA", "notesd:locked:v1", "notesd:locked:"] {
            #expect(LockedNotes.lockVersion(content) == nil, "\(content)")
        }
        #expect(!LockedNotes.isUnsupported("plain text"))
    }

    @Test func lockedImageTypes() {
        for v in f.imageTypes {
            #expect(isLockedImageType(v.type) == v.locked, "\(v.type)")
            #expect((v.type == lockedImageType || !isLockedImageType(v.type)) == v.supported, "\(v.type)")
        }
    }

    @Test func keyringEntries() {
        for v in f.keyring {
            let entry = LockKey(
                id: v.kind, kind: v.kind, salt: "", iterations: v.iterations ?? 0, wrapped: "", kdf: v.kdf)
            let reason: LockedError.Reason? =
                switch v.verdict {
                case "ok": nil
                case "unsupported": .unsupportedKdf
                default: .malformedEntry
                }
            do {
                _ = try Keyring.derivation(entry)
                #expect(reason == nil, "\(v.name)")
            } catch let error as LockedError {
                #expect(error.reason == reason, "\(v.name)")
            } catch {
                Issue.record(error)
            }
        }
    }

    @Test func aLockFromANewerVersionIsNeverAWrongPassword() {
        let salt = LockedNotes.encode(Data(repeating: 1, count: 16))
        let argon = LockKey(
            id: "password", kind: "password", salt: salt, wrapped: "AAAA", kdf: Kdf(name: "argon2id"))
        #expect(throws: LockedError.unsupportedKdf) { try Keyring.unlock([argon], "secret") }
        // A count PBKDF2 can't run is an error, not a crash.
        for iterations in [0, -1, Int.max] {
            let entry = LockKey(
                id: "password", kind: "password", salt: salt, iterations: iterations, wrapped: "AAAA",
                kdf: Kdf(name: Kdf.pbkdf2, iterations: iterations))
            #expect(throws: LockedNotes.malformedEntry) { try Keyring.unlock([entry], "secret") }
            #expect(throws: LockedNotes.malformedEntry) {
                try LockedNotes.passwordKek("secret", salt: Data(), iterations: iterations)
            }
        }
    }

    @Test func newEntriesSayHowTheyreDerived() throws {
        let created = Keyring.create("secret", iterations: 1000)
        #expect(created.entry.kdf == Kdf(name: Kdf.pbkdf2, iterations: 1000))
        #expect(created.entry.iterations == 1000)
        #expect(try Keyring.unlock([created.entry], "secret") == created.key)
        // An entry from before 1.0, without kdf, still opens.
        var legacy = created.entry
        legacy.kdf = nil
        #expect(try Keyring.unlock([legacy], "secret") == created.key)
    }

    @Test func images() throws {
        for v in f.images {
            guard let plain = v.plain else {
                #expect(throws: LockedError.self, "\(v.name)") { try LockedNotes.decryptImage(b(v.key), b(v.sealed)) }
                continue
            }
            #expect(try LockedNotes.encode(LockedNotes.decryptImage(b(v.key), b(v.sealed))) == plain, "\(v.name)")
            let again = LockedNotes.encryptImage(b(v.key), b(plain))
            #expect(LockedNotes.encode(again) != v.sealed, "fresh nonce: \(v.name)")
            #expect(try LockedNotes.encode(LockedNotes.decryptImage(b(v.key), again)) == plain, "\(v.name)")
        }
    }

    @Test func verifiers() {
        for v in f.verifiers { #expect(LockedNotes.verifier(b(v.key)) == v.verifier, "\(v.name)") }
    }

    @Test func base64urlIsStrict() {
        for bad in ["a+b", "a/b", "ab==", "a b", "abcde"] {
            #expect(throws: LockedError.self, "\(bad)") { try LockedNotes.decode(bad) }
        }
    }
}
