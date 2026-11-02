import Foundation
import Synchronization

/// Stands in for Resend's API: notesd posts to it, so it has to be a real
/// listener (plain BSD sockets on loopback, one request per connection).
/// Answers each request with the next `enqueue`d reply (500 when none is
/// queued) and keeps the JSON bodies it received.
final class FakeResend: Sendable {
    struct Reply: Sendable {
        var status: Int
        var body: String
        var location: String?
    }

    private let socket: Int32
    private let state = Mutex<(replies: [Reply], bodies: [String])>(([], []))
    let port: UInt16

    var url: String { "http://127.0.0.1:\(port)" }
    var bodies: [String] { state.withLock { $0.bodies } }

    init() throws {
        let fd = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        addr.sin_port = 0
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        let bound = withUnsafeMutablePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(fd, $0, len) == 0 && listen(fd, 16) == 0 && getsockname(fd, $0, &len) == 0
            }
        }
        guard fd >= 0, bound else { throw POSIXError(.EADDRNOTAVAIL) }
        socket = fd
        port = UInt16(bigEndian: addr.sin_port)
        let thread = Thread { [weak self] in
            while true {
                let client = accept(fd, nil, nil)
                guard client >= 0 else { return }
                self?.serve(client)
                close(client)
            }
        }
        thread.start()
    }

    func enqueue(_ status: Int, _ body: String, location: String? = nil) {
        state.withLock { $0.replies.append(Reply(status: status, body: body, location: location)) }
    }

    func accepted() { enqueue(200, #"{"id":"e1"}"#) }

    private func serve(_ client: Int32) {
        var buffer = Data()
        var chunk = [UInt8](repeating: 0, count: 65536)
        var body: String?
        while body == nil {
            let n = read(client, &chunk, chunk.count)
            if n <= 0 { return }
            buffer.append(contentsOf: chunk[..<n])
            body = Self.body(buffer)
        }
        let reply = state.withLock { state in
            state.bodies.append(body!)
            return state.replies.isEmpty ? Reply(status: 500, body: "{}") : state.replies.removeFirst()
        }
        let payload = Data(reply.body.utf8)
        let head =
            "HTTP/1.1 \(reply.status) X\r\nContent-Type: application/json\r\nContent-Length: \(payload.count)\r\n"
            + (reply.location.map { "Location: \($0)\r\n" } ?? "") + "Connection: close\r\n\r\n"
        let out = Data(head.utf8) + payload
        _ = out.withUnsafeBytes { write(client, $0.baseAddress, out.count) }
    }

    /// The request's body once it has all arrived.
    private static func body(_ buffer: Data) -> String? {
        guard let end = buffer.range(of: Data("\r\n\r\n".utf8)) else { return nil }
        let head = String(decoding: buffer[..<end.lowerBound], as: UTF8.self).lowercased()
        let length =
            head.split(separator: "\r\n")
            .first { $0.hasPrefix("content-length:") }
            .flatMap { Int($0.dropFirst("content-length:".count).trimmingCharacters(in: .whitespaces)) } ?? 0
        let body = buffer[end.upperBound...]
        return body.count >= length ? String(decoding: body.prefix(length), as: UTF8.self) : nil
    }

    deinit {
        // Unblocks accept(), which ends the thread.
        shutdown(socket, SHUT_RDWR)
        close(socket)
    }
}
