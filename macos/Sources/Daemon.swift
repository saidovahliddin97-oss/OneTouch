import Foundation

/// Runs the bundled Go core (`onetouch serve --events`) and talks to it:
/// events arrive as JSON lines on its stdout, commands go to its loopback API.
final class Daemon {
    struct Event {
        let type: String
        let path: String?
        let name: String?
        let from: String?
        let error: String?
        let peers: [String]
    }

    var onEvent: ((Event) -> Void)?
    var onStatus: ((String?) -> Void)?   // nil = running fine, otherwise an error text

    private var process: Process?
    private var stopping = false
    private var lineBuffer = Data()
    private var lastError = ""
    private let control = URL(string: "http://127.0.0.1:47471")!
    private lazy var log: FileHandle? = {
        let url = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Logs/OneTouch.log")
        FileManager.default.createFile(atPath: url.path, contents: nil)
        let h = try? FileHandle(forWritingTo: url)
        h?.truncateFile(atOffset: 0)
        return h
    }()

    func start() {
        stopping = false
        guard let bin = Bundle.main.url(forAuxiliaryExecutable: "onetouch-core") else {
            onStatus?("Не найден onetouch-core внутри приложения")
            return
        }
        let p = Process()
        p.executableURL = bin
        p.arguments = ["serve", "--events", "--name", Host.current().localizedName ?? "Mac"]
        let out = Pipe()
        let err = Pipe()
        p.standardOutput = out
        p.standardError = err
        out.fileHandleForReading.readabilityHandler = { [weak self] h in
            let data = h.availableData
            if data.isEmpty { h.readabilityHandler = nil; return } // EOF
            DispatchQueue.main.async { self?.consume(data) }
        }
        err.fileHandleForReading.readabilityHandler = { [weak self] h in
            let data = h.availableData
            if data.isEmpty { h.readabilityHandler = nil; return }
            DispatchQueue.main.async {
                self?.log?.write(data)
                if let s = String(data: data, encoding: .utf8), s.contains("ошибка") {
                    self?.lastError = s.trimmingCharacters(in: .whitespacesAndNewlines)
                }
            }
        }
        p.terminationHandler = { [weak self] proc in
            DispatchQueue.main.async {
                guard let self, !self.stopping else { return }
                let msg = self.lastError.isEmpty ? "Ядро остановилось (код \(proc.terminationStatus))" : self.lastError
                self.onStatus?(msg)
                DispatchQueue.main.asyncAfter(deadline: .now() + 3) { if !self.stopping { self.start() } }
            }
        }
        do {
            try p.run()
            process = p
            lastError = ""
            onStatus?(nil)
        } catch {
            onStatus?("Не удалось запустить ядро: \(error.localizedDescription)")
        }
    }

    func stop() {
        stopping = true
        process?.terminate()
        process = nil
    }

    private func consume(_ data: Data) {
        lineBuffer.append(data)
        while let nl = lineBuffer.firstIndex(of: 0x0A) {
            let line = lineBuffer.subdata(in: lineBuffer.startIndex..<nl)
            lineBuffer.removeSubrange(lineBuffer.startIndex...nl)
            guard let obj = try? JSONSerialization.jsonObject(with: line) as? [String: Any] else { continue }
            onEvent?(Event(
                type: obj["type"] as? String ?? "",
                path: obj["path"] as? String,
                name: obj["name"] as? String,
                from: obj["from"] as? String,
                error: obj["error"] as? String,
                peers: obj["peers"] as? [String] ?? []
            ))
        }
    }

    /// Offers files to phones. Completion gets the phone names or an error text.
    func offer(_ paths: [String], completion: @escaping (Result<[String], OfferError>) -> Void) {
        var req = URLRequest(url: control.appendingPathComponent("local/offer"))
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try? JSONSerialization.data(withJSONObject: ["paths": paths])
        req.timeoutInterval = 15
        URLSession.shared.dataTask(with: req) { data, _, error in
            var result: Result<[String], OfferError>
            if let error {
                result = .failure(OfferError(message: "OneTouch не запущен: \(error.localizedDescription)"))
            } else if let data, let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                if let e = obj["error"] as? String {
                    result = .failure(OfferError(message: e))
                } else {
                    result = .success(obj["peers"] as? [String] ?? [])
                }
            } else {
                result = .failure(OfferError(message: "Пустой ответ от ядра"))
            }
            DispatchQueue.main.async { completion(result) }
        }.resume()
    }

    /// Names of devices currently visible on the network.
    func peers(completion: @escaping ([(name: String, os: String)]) -> Void) {
        URLSession.shared.dataTask(with: control.appendingPathComponent("local/peers")) { data, _, _ in
            var list: [(String, String)] = []
            if let data, let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] {
                list = arr.map { ($0["name"] as? String ?? "?", $0["os"] as? String ?? "") }
            }
            DispatchQueue.main.async { completion(list.map { (name: $0.0, os: $0.1) }) }
        }.resume()
    }
}

struct OfferError: Error {
    let message: String
}
