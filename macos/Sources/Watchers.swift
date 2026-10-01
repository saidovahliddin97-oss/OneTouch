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
        // A copy is clearContents() followed by a write. If we land in between,
        // the pasteboard is empty: don't mark this change as seen, look again.
        guard let types = pb.types, !types.isEmpty else { return }
        lastCount = pb.changeCount
        logLine("clipboard changed: \(types.map(\.rawValue).joined(separator: ", "))")
        if let urls = pb.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL], !urls.isEmpty {
            let files = urls.filter { !$0.hasDirectoryPath }
            if !files.isEmpty { onFiles?(files) }
            return
        }
        // A screenshot copied with ⌃⇧⌘4: image data only, no text.
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

/// Unbuffered log line to stderr (captured in tests and by Console).
func logLine(_ s: String) {
    FileHandle.standardError.write(Data(("OneTouch: " + s + "\n").utf8))
}

extension NSImage {
    func pngData() -> Data? {
        guard let tiff = tiffRepresentation, let rep = NSBitmapImageRep(data: tiff) else { return nil }
        return rep.representation(using: .png, properties: [:])
    }
}

/// Trackpad pinches while Finder (or the Desktop) is in front:
///  • pinch in  (fingers together) → send Finder's selection to the phone;
///  • pinch out (fingers apart)    → pull the phone's latest photo to this Mac.
/// Gesture events are read with a listen-only CGEvent tap, which (unlike an
/// NSEvent global monitor) reliably sees trackpad gestures. It needs the
/// «Мониторинг ввода» (Input Monitoring) permission.
final class PinchWatcher {
    var onPinch: ((_ inward: Bool) -> Void)?
    private var tap: CFMachPort?
    private var source: CFRunLoopSource?
    private var monitor: Any?
    private var total: CGFloat = 0
    private var fired = false

    static var permitted: Bool { CGPreflightListenEventAccess() }

    func start() {
        guard tap == nil, monitor == nil else { return }
        if !CGPreflightListenEventAccess() { _ = CGRequestListenEventAccess() }
        let mask = CGEventMask(1 << 29) | CGEventMask(1 << 30) // NSEventTypeGesture, NSEventTypeMagnify
        let callback: CGEventTapCallBack = { _, type, event, refcon in
            guard let refcon else { return Unmanaged.passUnretained(event) }
            let me = Unmanaged<PinchWatcher>.fromOpaque(refcon).takeUnretainedValue()
            if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
                if let t = me.tap { CGEvent.tapEnable(tap: t, enable: true) }
            } else if type.rawValue == 30, let ns = NSEvent(cgEvent: event) {
                me.handle(phase: ns.phase, magnification: ns.magnification)
            }
            return Unmanaged.passUnretained(event)
        }
        if let t = CGEvent.tapCreate(tap: .cgSessionEventTap, place: .headInsertEventTap, options: .listenOnly,
                                     eventsOfInterest: mask, callback: callback,
                                     userInfo: Unmanaged.passUnretained(self).toOpaque()) {
            tap = t
            source = CFMachPortCreateRunLoopSource(nil, t, 0)
            CFRunLoopAddSource(CFRunLoopGetMain(), source, .commonModes)
            CGEvent.tapEnable(tap: t, enable: true)
            logLine("pinch: event tap active")
        } else {
            // No Input Monitoring permission yet: fall back to the (less reliable) monitor.
            monitor = NSEvent.addGlobalMonitorForEvents(matching: .magnify) { [weak self] e in
                self?.handle(phase: e.phase, magnification: e.magnification)
            }
            logLine("pinch: event tap unavailable (Input Monitoring not granted), using global monitor")
        }
    }

    func stop() {
        if let t = tap { CGEvent.tapEnable(tap: t, enable: false) }
        if let s = source { CFRunLoopRemoveSource(CFRunLoopGetMain(), s, .commonModes) }
        tap = nil
        source = nil
        if let m = monitor { NSEvent.removeMonitor(m) }
        monitor = nil
    }

    /// Restart after the user grants Input Monitoring.
    func restart() {
        stop()
        start()
    }

    private func handle(phase: NSEvent.Phase, magnification: CGFloat) {
        switch phase {
        case .began:
            total = 0
            fired = false
        case .changed:
            total += magnification
            guard !fired, abs(total) > 0.4 else { return }
            fired = true
            let front = NSWorkspace.shared.frontmostApplication?.bundleIdentifier
            logLine("pinch \(total < 0 ? "in" : "out") in \(front ?? "?")")
            if front == "com.apple.finder" {
                let inward = total < 0
                DispatchQueue.main.async { self.onPinch?(inward) }
            }
        default:
            total = 0
        }
    }

    enum SelectionError: Error { case notAllowed, failed(String) }

    /// Paths selected in Finder (asks for Automation permission the first time).
    static func finderSelection() -> Result<[String], SelectionError> {
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
        let res = NSAppleScript(source: src)?.executeAndReturnError(&err)
        if let err {
            let code = err[NSAppleScript.errorNumber] as? Int ?? 0
            return .failure(code == -1743 ? .notAllowed : .failed(err[NSAppleScript.errorMessage] as? String ?? "\(code)"))
        }
        let paths = (res?.stringValue ?? "").split(separator: "\n").map(String.init).filter { !$0.hasSuffix("/") }
        return .success(paths)
    }
}
