import Foundation

/// Finds OneTouch phones through the system Bonjour daemon (mDNSResponder).
/// On macOS the port-5353 socket belongs to mDNSResponder, so the Go core
/// cannot browse reliably itself; it gets the phone addresses from here.
/// Browsing is cheap: mDNSResponder caches and backs off on its own.
final class PhoneBrowser: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {
    struct Phone {
        let name: String
        let host: String
        let port: Int
    }

    var onChange: (() -> Void)?
    private(set) var phones: [String: Phone] = [:]   // keyed by service instance name
    private var browser = NetServiceBrowser()
    private var resolving: [NetService] = []           // NetService must be retained while resolving

    func start() {
        browser.delegate = self
        browser.searchForServices(ofType: "_onetouch._tcp.", inDomain: "local.")
    }

    /// Drops cached results and browses again (e.g. after a failed offer).
    func refresh() {
        browser.stop()
        browser = NetServiceBrowser()
        phones.removeAll()
        resolving.removeAll()
        start()
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didNotSearch errorDict: [String: NSNumber]) {
        // -65570 (PolicyDenied) = the user has not allowed Local Network access.
        logLine("bonjour browse failed: \(errorDict)")
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        logLine("bonjour found: \(service.name)")
        resolving.append(service)
        service.delegate = self
        service.resolve(withTimeout: 5)
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        phones[service.name] = nil
        resolving.removeAll { $0 == service }
        onChange?()
    }

    func netServiceDidResolveAddress(_ service: NetService) {
        guard let data = service.txtRecordData() else { return }
        let txt = NetService.dictionary(fromTXTRecord: data).mapValues { String(decoding: $0, as: UTF8.self) }
        logLine("resolved \(service.name): txt=\(txt) addrs=\(service.addresses?.count ?? 0)")
        guard txt["os"] == "android", let host = Self.address(service.addresses ?? []) else { return }
        phones[service.name] = Phone(name: txt["name"] ?? service.name, host: host, port: service.port)
        logLine("phone found: \(service.name) at \(host):\(service.port)")
        onChange?()
    }

    func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
        logLine("bonjour resolve failed: \(sender.name) \(errorDict)")
        resolving.removeAll { $0 == sender }
    }

    /// First IPv4 address (falls back to IPv6) from a list of sockaddr blobs.
    static func address(_ addrs: [Data]) -> String? {
        func numeric(_ d: Data, family: Int32) -> String? {
            d.withUnsafeBytes { raw -> String? in
                guard let sa = raw.baseAddress?.assumingMemoryBound(to: sockaddr.self),
                      Int32(sa.pointee.sa_family) == family else { return nil }
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                guard getnameinfo(sa, socklen_t(d.count), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { return nil }
                return String(cString: host)
            }
        }
        return addrs.lazy.compactMap { numeric($0, family: AF_INET) }.first
            ?? addrs.lazy.compactMap { numeric($0, family: AF_INET6) }.first
    }
}
