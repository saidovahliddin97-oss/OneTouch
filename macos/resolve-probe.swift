// CI check of the app's own PhoneBrowser class from a command-line process
// (CI cannot grant the app bundle the Local Network permission).
import Foundation

func logLine(_ s: String) { print("probe:", s) }

let browser = PhoneBrowser()
browser.start()
RunLoop.main.run(until: Date().addingTimeInterval(10))
print("probe phones:", browser.phones.values.map { "\($0.name) \($0.host):\($0.port)" })
