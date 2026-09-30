import AppKit

/// Watches the general pasteboard. When the user copies files in Finder
/// (⌘C) — or takes a screenshot to the clipboard — it reports them so they
/// can be offered to the phone. macOS has no pasteboard notification, so we
/// compare `changeCount` on a lazy timer (a single integer read, ~2×/s).
final class ClipboardWatcher {
    var onFiles: (([URL]) -> Void)?
    private var timer: Timer?
    private var lastCount = NSPasteboard.general.changeCount

    func start() {
        guard timer == nil else { return }
        lastCount = NSPasteboard.general.changeCount
        let t = Timer(timeInterval: 0.5, repeats: true) { [weak self] _ in self?.tick() }
        t.tolerance = 0.3 // let the system coalesce wakeups
        RunLoop.main.add(t, forMode: .common)
        timer = t
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    /// Call after writing to the pasteboard ourselves so we don't echo it back.
    func ignoreCurrent() {
        lastCount = NSPasteboard.general.changeCount
    }

    private func tick() {
        let pb = NSPasteboard.general
        guard pb.changeCount != lastCount else { return }
        lastCount = pb.changeCount
        if let urls = pb.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL], !urls.isEmpty {
            let files = urls.filter { !$0.hasDirectoryPath }
            if !files.isEmpty { onFiles?(files) }
            return
        }
        // A screenshot copied with ⌃⇧⌘4: image data only, no text.
        let types = pb.types ?? []
        guard !types.contains(.string), types.contains(.png) || types.contains(.tiff) else { return }
        guard let img = NSImage(pasteboard: pb), let png = img.pngData() else { return }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("OneTouch", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd 'в' HH.mm.ss"
        let url = dir.appendingPathComponent("Снимок экрана \(f.string(from: Date())).png")
        if (try? png.write(to: url)) != nil { onFiles?([url]) }
    }
}

extension NSImage {
    func pngData() -> Data? {
        guard let tiff = tiffRepresentation, let rep = NSBitmapImageRep(data: tiff) else { return nil }
        return rep.representation(using: .png, properties: [:])
    }
}

/// Pinch-in (two fingers together) on the trackpad while Finder is in front
/// sends Finder's current selection to the phone.
final class PinchWatcher {
    var onPinch: (() -> Void)?
    private var monitor: Any?
    private var total: CGFloat = 0
    private var fired = false

    func start() {
        guard monitor == nil else { return }
        monitor = NSEvent.addGlobalMonitorForEvents(matching: .magnify) { [weak self] e in self?.handle(e) }
    }

    func stop() {
        if let m = monitor { NSEvent.removeMonitor(m) }
        monitor = nil
    }

    private func handle(_ e: NSEvent) {
        switch e.phase {
        case .began:
            total = 0
            fired = false
        case .changed:
            total += e.magnification
            if total < -0.45 && !fired {
                fired = true
                if NSWorkspace.shared.frontmostApplication?.bundleIdentifier == "com.apple.finder" {
                    onPinch?()
                }
            }
        default:
            total = 0
        }
    }

    /// Paths selected in Finder (asks for Automation permission the first time).
    static func finderSelection() -> [String] {
        let src = """
        tell application "Finder"
            set out to ""
            repeat with f in (selection as alias list)
                set out to out & POSIX path of f & linefeed
            end repeat
            return out
        end tell
        """
        var err: NSDictionary?
        guard let res = NSAppleScript(source: src)?.executeAndReturnError(&err).stringValue else { return [] }
        return res.split(separator: "\n").map(String.init).filter { !$0.hasSuffix("/") }
    }
}
