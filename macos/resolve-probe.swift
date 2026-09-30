// CI diagnostic: browse and resolve _onetouch._tcp from a plain command-line
// process (not an app bundle, so not subject to the app's Local Network consent).
import Foundation

final class Probe: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {
    let b = NetServiceBrowser()
    var keep: [NetService] = []
    func netServiceBrowser(_ browser: NetServiceBrowser, didFind s: NetService, moreComing: Bool) {
        print("probe found", s.name); keep.append(s); s.delegate = self; s.resolve(withTimeout: 5)
    }
    func netServiceDidResolveAddress(_ s: NetService) { print("probe RESOLVED", s.name, s.hostName ?? "?", s.port) }
    func netService(_ s: NetService, didNotResolve e: [String: NSNumber]) { print("probe resolve FAILED", s.name, e) }
}
let p = Probe()
p.b.delegate = p
p.b.searchForServices(ofType: "_onetouch._tcp.", inDomain: "local.")
RunLoop.main.run(until: Date().addingTimeInterval(8))
