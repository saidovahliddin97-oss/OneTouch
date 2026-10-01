import Foundation
import Network

/// One screen session as seen by the app: the Go core has already done TLS
/// and the HTTP upgrade, and hands us a plain loopback TCP stream that starts
/// with a JSON preamble line, followed by frames:
///
///     [type u8][length u32 big-endian][payload]
///
/// All callbacks run on `queue`.
final class FrameConn {
    enum Kind: UInt8 {
        case hello = 1, config = 2, frame = 3, status = 4, offer = 6
        case input = 16, command = 17
    }

    let queue: DispatchQueue
    private let conn: NWConnection
    private var buf = Data()
    private var closed = false
    private(set) var pendingBytes = 0
    var onClose: (() -> Void)?

    init(_ conn: NWConnection, queue: DispatchQueue) {
        self.conn = conn
        self.queue = queue
    }

    func start() {
        conn.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled: self?.finish()
            default: break
            }
        }
        conn.start(queue: queue)
    }

    func readLine(_ done: @escaping (String?) -> Void) {
        if let i = buf.firstIndex(of: 0x0A) {
            let line = buf.subdata(in: buf.startIndex..<i)
            buf.removeSubrange(buf.startIndex...i)
            done(String(decoding: line, as: UTF8.self))
            return
        }
        if buf.count > 64 * 1024 { done(nil); return }
        fill { [weak self] ok in ok ? self?.readLine(done) : done(nil) }
    }

    func readFrame(_ done: @escaping ((UInt8, Data)?) -> Void) {
        if buf.count >= 5 {
            let s = buf.startIndex
            let len = Int(buf[s + 1]) << 24 | Int(buf[s + 2]) << 16 | Int(buf[s + 3]) << 8 | Int(buf[s + 4])
            if len > 32 << 20 { done(nil); return }
            if buf.count >= 5 + len {
                let type = buf[s]
                let payload = buf.subdata(in: (s + 5)..<(s + 5 + len))
                buf.removeSubrange(s..<(s + 5 + len))
                done((type, payload))
                return
            }
        }
        fill { [weak self] ok in ok ? self?.readFrame(done) : done(nil) }
    }

    private func fill(_ done: @escaping (Bool) -> Void) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 1 << 20) { [weak self] data, _, _, _ in
            guard let self, let data, !data.isEmpty else { done(false); return }
            self.buf.append(data)
            done(true)
        }
    }

    func send(_ kind: Kind, _ payload: Data) {
        guard !closed else { return }
        var len = UInt32(payload.count).bigEndian
        var packet = Data([kind.rawValue])
        packet.append(Data(bytes: &len, count: 4))
        packet.append(payload)
        let n = packet.count
        pendingBytes += n
        conn.send(content: packet, completion: .contentProcessed { [weak self] _ in self?.pendingBytes -= n })
    }

    func sendJSON(_ kind: Kind, _ obj: [String: Any]) {
        if let d = try? JSONSerialization.data(withJSONObject: obj) { send(kind, d) }
    }

    func close() {
        conn.cancel()
    }

    private func finish() {
        guard !closed else { return }
        closed = true
        onClose?()
    }
}

/// Who is on the other end, as reported by the core.
struct SessionPeer: Decodable {
    let kind: String   // screen | mirror
    let id: String
    let name: String
    let token: String
    let addr: String
}

/// Accepts sessions spliced by the core on 127.0.0.1:47472.
final class SessionServer {
    var onSession: ((SessionPeer, FrameConn) -> Void)?
    private var listener: NWListener?
    private let queue = DispatchQueue(label: "onetouch.sessions")

    func start() {
        let params = NWParameters.tcp
        params.requiredLocalEndpoint = NWEndpoint.hostPort(host: "127.0.0.1", port: 47472)
        params.allowLocalEndpointReuse = true
        guard let l = try? NWListener(using: params) else {
            logLine("session listener failed to start")
            return
        }
        l.newConnectionHandler = { [weak self] c in self?.accept(c) }
        l.stateUpdateHandler = { state in logLine("session listener: \(state)") }
        l.start(queue: queue)
        listener = l
    }

    private func accept(_ c: NWConnection) {
        let q = DispatchQueue(label: "onetouch.session")
        let fc = FrameConn(c, queue: q)
        fc.start()
        fc.readLine { [weak self] line in
            guard let line, let peer = try? JSONDecoder().decode(SessionPeer.self, from: Data(line.utf8)) else {
                fc.close()
                return
            }
            self?.onSession?(peer, fc)
        }
    }
}

/// Phones allowed to open sessions without asking (id → token).
enum TrustStore {
    private static let key = "trustedDevices"

    static func isTrusted(_ p: SessionPeer) -> Bool {
        let all = UserDefaults.standard.dictionary(forKey: key) as? [String: String] ?? [:]
        return all[p.id] == p.token
    }

    static func trust(_ p: SessionPeer) {
        var all = UserDefaults.standard.dictionary(forKey: key) as? [String: String] ?? [:]
        all[p.id] = p.token
        UserDefaults.standard.set(all, forKey: key)
    }

    static func forgetAll() {
        UserDefaults.standard.removeObject(forKey: key)
    }
}
