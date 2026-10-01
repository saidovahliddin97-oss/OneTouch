import AppKit
import AVFoundation
import CoreMedia

/// Turns phone gestures into mouse and keyboard events on the main display.
/// Coordinates arrive normalized to 0…1 of the streamed screen.
final class InputInjector {
    private let source = CGEventSource(stateID: .hidSystemState)
    private var lastClick = Date.distantPast
    private var clickCount = 0
    private var buttonDown = false

    static var allowed: Bool { AXIsProcessTrusted() }

    static func requestPermission() {
        let opts = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        _ = AXIsProcessTrustedWithOptions(opts)
    }

    private func point(_ e: [String: Any]) -> CGPoint {
        let b = CGDisplayBounds(CGMainDisplayID())
        let x = (e["x"] as? Double ?? 0).clamped(0, 1)
        let y = (e["y"] as? Double ?? 0).clamped(0, 1)
        return CGPoint(x: b.minX + x * (b.width - 1), y: b.minY + y * (b.height - 1))
    }

    func handle(_ e: [String: Any]) {
        guard let t = e["t"] as? String else { return }
        switch t {
        case "move":
            mouse(buttonDown ? .leftMouseDragged : .mouseMoved, point(e), .left)
        case "down":
            let now = Date()
            clickCount = now.timeIntervalSince(lastClick) < 0.4 ? min(clickCount + 1, 3) : 1
            lastClick = now
            buttonDown = true
            mouse(.leftMouseDown, point(e), .left, clicks: clickCount)
        case "up":
            buttonDown = false
            mouse(.leftMouseUp, point(e), .left, clicks: clickCount)
        case "right":
            let p = point(e)
            mouse(.rightMouseDown, p, .right)
            mouse(.rightMouseUp, p, .right)
        case "scroll":
            let dx = Int32(e["dx"] as? Double ?? 0)
            let dy = Int32(e["dy"] as? Double ?? 0)
            CGEvent(scrollWheelEvent2Source: source, units: .pixel, wheelCount: 2, wheel1: dy, wheel2: dx, wheel3: 0)?
                .post(tap: .cghidEventTap)
        case "text":
            typeText(e["s"] as? String ?? "")
        case "key":
            key(e["k"] as? String ?? "")
        default:
            break
        }
    }

    private func mouse(_ type: CGEventType, _ p: CGPoint, _ button: CGMouseButton, clicks: Int = 1) {
        guard let ev = CGEvent(mouseEventSource: source, mouseType: type, mouseCursorPosition: p, mouseButton: button) else { return }
        ev.setIntegerValueField(.mouseEventClickState, value: Int64(clicks))
        ev.post(tap: .cghidEventTap)
    }

    private func typeText(_ s: String) {
        // Unicode strings are typed in chunks; this works for any keyboard layout.
        var chars = Array(s.utf16)
        while !chars.isEmpty {
            let chunk = Array(chars.prefix(16))
            chars.removeFirst(chunk.count)
            for down in [true, false] {
                guard let ev = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: down) else { continue }
                ev.keyboardSetUnicodeString(stringLength: chunk.count, unicodeString: chunk)
                ev.post(tap: .cghidEventTap)
            }
        }
    }

    /// Names like "return", "backspace", "cmd+c", "cmd+shift+z".
    private func key(_ name: String) {
        var parts = name.lowercased().split(separator: "+").map(String.init)
        guard let last = parts.popLast(), let code = Self.keyCodes[last] else { return }
        var flags: CGEventFlags = []
        for m in parts {
            switch m {
            case "cmd": flags.insert(.maskCommand)
            case "shift": flags.insert(.maskShift)
            case "alt", "opt": flags.insert(.maskAlternate)
            case "ctrl": flags.insert(.maskControl)
            default: break
            }
        }
        for down in [true, false] {
            guard let ev = CGEvent(keyboardEventSource: source, virtualKey: code, keyDown: down) else { continue }
            ev.flags = flags
            ev.post(tap: .cghidEventTap)
        }
    }

    private static let keyCodes: [String: CGKeyCode] = [
        "return": 36, "enter": 36, "tab": 48, "space": 49, "backspace": 51, "delete": 117, "escape": 53, "esc": 53,
        "left": 123, "right": 124, "down": 125, "up": 126, "home": 115, "end": 119, "pageup": 116, "pagedown": 121,
        "a": 0, "s": 1, "d": 2, "f": 3, "h": 4, "g": 5, "z": 6, "x": 7, "c": 8, "v": 9, "b": 11, "q": 12, "w": 13,
        "e": 14, "r": 15, "y": 16, "t": 17, "o": 31, "u": 32, "i": 34, "p": 35, "l": 37, "j": 38, "k": 40, "n": 45, "m": 46,
        "f11": 103, "f3": 99,
    ]
}

extension Double {
    func clamped(_ lo: Double, _ hi: Double) -> Double { Swift.min(hi, Swift.max(lo, self)) }
}

/// The phone watches (and controls) this Mac.
final class ScreenSession {
    let peer: SessionPeer
    private let fc: FrameConn
    private let source: FrameSource
    private let encoder = H264Encoder()
    private let input = InputInjector()
    private var waitingForKey = false
    private var stopped = false
    var onEnd: (() -> Void)?
    /// Files to hand to the phone for the «Забрать выделенное» button.
    var selection: (() -> [String])?
    var registerOffer: (([String], @escaping (Data?) -> Void) -> Void)?

    init(peer: SessionPeer, fc: FrameConn, fake: Bool) {
        self.peer = peer
        self.fc = fc
        self.source = fake ? SyntheticSource() : ScreenSource()
    }

    func start() {
        fc.onClose = { [weak self] in self?.stop() }
        let controllable = InputInjector.allowed
        if !controllable { InputInjector.requestPermission() }
        source.onFrame = { [weak self] pb, pts in self?.encoder.encode(pb, pts: pts) }
        encoder.onOutput = { [weak self] config, frame, isKey in
            guard let self else { return }
            self.fc.queue.async { self.send(config: config, frame: frame, isKey: isKey) }
        }
        source.start(maxWidth: 1920) { [weak self] result in
            guard let self else { return }
            self.fc.queue.async {
                switch result {
                case .success(let (w, h)):
                    do {
                        try self.encoder.setup(width: w, height: h)
                    } catch {
                        self.fail(error.localizedDescription)
                        return
                    }
                    let b = CGDisplayBounds(CGMainDisplayID())
                    self.fc.sendJSON(.hello, ["w": w, "h": h, "name": Host.current().localizedName ?? "Mac",
                                              "screenW": b.width, "screenH": b.height, "control": controllable])
                    if !controllable {
                        self.status("Только просмотр: на Mac включите OneTouch в «Универсальный доступ», чтобы управлять")
                    }
                    logLine("screen session \(self.peer.name): streaming \(w)x\(h)")
                    self.readLoop()
                case .failure(let e):
                    self.fail("Нет доступа к экрану Mac: разрешите OneTouch «Запись экрана» в настройках (\(e.localizedDescription))")
                }
            }
        }
    }

    private func send(config: Data?, frame: Data, isKey: Bool) {
        // Back-pressure: if the phone falls behind, drop frames until the next key frame
        // instead of building up seconds of latency.
        if fc.pendingBytes > 2_500_000 && !isKey {
            if !waitingForKey { waitingForKey = true; encoder.requestKeyframe() }
            return
        }
        if waitingForKey && !isKey { return }
        waitingForKey = false
        if let config { fc.send(.config, config) }
        var payload = Data([isKey ? 1 : 0])
        payload.append(frame)
        fc.send(.frame, payload)
    }

    private func readLoop() {
        fc.readFrame { [weak self] f in
            guard let self else { return }
            guard let f else { self.stop(); return }
            let (type, payload) = f
            let obj = (try? JSONSerialization.jsonObject(with: payload)) as? [String: Any] ?? [:]
            switch FrameConn.Kind(rawValue: type) {
            case .input:
                if InputInjector.allowed { self.input.handle(obj) }
                if ProcessInfo.processInfo.environment["ONETOUCH_FAKE_SCREEN"] != nil { logLine("input \(obj)") }
            case .command:
                self.command(obj["cmd"] as? String ?? "")
            default:
                break
            }
            self.readLoop()
        }
    }

    private func command(_ cmd: String) {
        switch cmd {
        case "keyframe":
            encoder.requestKeyframe()
        case "grab":
            DispatchQueue.main.async {
                let paths = self.selection?() ?? []
                guard !paths.isEmpty else {
                    self.fc.queue.async { self.status("Выделите файл в Finder, затем нажмите «Забрать»") }
                    return
                }
                self.registerOffer?(paths) { offer in
                    self.fc.queue.async {
                        if let offer { self.fc.send(.offer, offer) } else { self.status("Не удалось подготовить файлы") }
                    }
                }
            }
        default:
            break
        }
    }

    private func status(_ text: String) {
        fc.sendJSON(.status, ["text": text])
    }

    private func fail(_ text: String) {
        status(text)
        logLine("screen session failed: \(text)")
        fc.queue.asyncAfter(deadline: .now() + 0.5) { self.fc.close() }
    }

    func stop() {
        guard !stopped else { return }
        stopped = true
        source.stop()
        encoder.invalidate()
        fc.close()
        DispatchQueue.main.async { self.onEnd?() }
    }
}

/// The phone shows its screen here, in a window. Files dropped on the
/// window are sent to the phone straight away.
final class MirrorSession: NSObject, NSWindowDelegate {
    let peer: SessionPeer
    private let fc: FrameConn
    private var window: NSWindow?
    private let view = MirrorView()
    private var format: CMVideoFormatDescription?
    private var stopped = false
    private(set) var framesShown = 0
    var onEnd: (() -> Void)?
    var registerOffer: (([String], @escaping (Data?) -> Void) -> Void)?

    init(peer: SessionPeer, fc: FrameConn) {
        self.peer = peer
        self.fc = fc
    }

    func start() {
        fc.onClose = { [weak self] in DispatchQueue.main.async { self?.stop() } }
        view.onDrop = { [weak self] urls in self?.offer(urls.map(\.path)) }
        readLoop()
    }

    private func readLoop() {
        fc.readFrame { [weak self] f in
            guard let self else { return }
            guard let f else { DispatchQueue.main.async { self.stop() }; return }
            let (type, payload) = f
            switch FrameConn.Kind(rawValue: type) {
            case .hello:
                let obj = (try? JSONSerialization.jsonObject(with: payload)) as? [String: Any] ?? [:]
                let w = obj["w"] as? Int ?? 720, h = obj["h"] as? Int ?? 1600
                DispatchQueue.main.async { self.openWindow(w: w, h: h) }
            case .config:
                self.format = Self.formatDescription(payload)
            case .frame:
                if let sb = self.sampleBuffer(payload) {
                    DispatchQueue.main.async { self.view.enqueue(sb) { self.requestKeyframe() } }
                    self.framesShown += 1
                    if self.framesShown == 30 { logLine("mirror \(self.peer.name): 30 frames decoded, layer=\(self.view.statusText)") }
                }
            default:
                break
            }
            self.readLoop()
        }
    }

    private func requestKeyframe() {
        fc.queue.async { self.fc.sendJSON(.command, ["cmd": "keyframe"]) }
    }

    private func openWindow(w: Int, h: Int) {
        let screen = NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
        let height = screen.height * 0.85
        let width = height * CGFloat(w) / CGFloat(h)
        let win = NSWindow(contentRect: NSRect(x: 0, y: 0, width: width, height: height),
                           styleMask: [.titled, .closable, .resizable, .miniaturizable, .fullSizeContentView],
                           backing: .buffered, defer: false)
        win.title = "\(peer.name) — экран"
        win.titlebarAppearsTransparent = true
        win.backgroundColor = .black
        win.contentAspectRatio = NSSize(width: w, height: h)
        win.contentView = view
        win.delegate = self
        win.isReleasedWhenClosed = false
        win.center()
        NSApp.activate(ignoringOtherApps: true)
        win.makeKeyAndOrderFront(nil)
        window = win
    }

    func windowWillClose(_ notification: Notification) {
        stop()
    }

    private func offer(_ paths: [String]) {
        registerOffer?(paths) { [weak self] offer in
            guard let self, let offer else { return }
            self.fc.queue.async { self.fc.send(.offer, offer) }
            DispatchQueue.main.async { self.view.flash("→ \(self.peer.name)") }
        }
    }

    func stop() {
        guard !stopped else { return }
        stopped = true
        fc.close()
        window?.delegate = nil
        window?.close()
        window = nil
        onEnd?()
    }

    // MARK: H.264 Annex-B → CMSampleBuffer for AVSampleBufferDisplayLayer

    static func formatDescription(_ annexB: Data) -> CMVideoFormatDescription? {
        let nals = splitNALs(annexB)
        guard let sps = nals.first(where: { ($0.first ?? 0) & 0x1F == 7 }),
              let pps = nals.first(where: { ($0.first ?? 0) & 0x1F == 8 }) else { return nil }
        var fd: CMVideoFormatDescription?
        let status = sps.withUnsafeBytes { s in
            pps.withUnsafeBytes { p in
                let ptrs: [UnsafePointer<UInt8>] = [s.bindMemory(to: UInt8.self).baseAddress!, p.bindMemory(to: UInt8.self).baseAddress!]
                let sizes = [sps.count, pps.count]
                return CMVideoFormatDescriptionCreateFromH264ParameterSets(allocator: nil, parameterSetCount: 2,
                                                                           parameterSetPointers: ptrs, parameterSetSizes: sizes,
                                                                           nalUnitHeaderLength: 4, formatDescriptionOut: &fd)
            }
        }
        return status == noErr ? fd : nil
    }

    private func sampleBuffer(_ payload: Data) -> CMSampleBuffer? {
        guard let format, payload.count > 1 else { return nil }
        var avcc = Data()
        for nal in splitNALs(payload.subdata(in: 1..<payload.count)) {
            let t = (nal.first ?? 0) & 0x1F
            if t == 7 || t == 8 || t == 9 { continue } // SPS/PPS/AUD live in the format description
            var len = UInt32(nal.count).bigEndian
            avcc.append(Data(bytes: &len, count: 4))
            avcc.append(nal)
        }
        guard !avcc.isEmpty else { return nil }
        var block: CMBlockBuffer?
        guard CMBlockBufferCreateWithMemoryBlock(allocator: nil, memoryBlock: nil, blockLength: avcc.count, blockAllocator: nil,
                                                 customBlockSource: nil, offsetToData: 0, dataLength: avcc.count,
                                                 flags: kCMBlockBufferAssureMemoryNowFlag,
                                                 blockBufferOut: &block) == noErr, let block else { return nil }
        let copied = avcc.withUnsafeBytes { raw in
            CMBlockBufferReplaceDataBytes(with: raw.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: avcc.count)
        }
        guard copied == noErr else { return nil }
        var sb: CMSampleBuffer?
        var size = avcc.count
        guard CMSampleBufferCreateReady(allocator: nil, dataBuffer: block, formatDescription: format, sampleCount: 1,
                                        sampleTimingEntryCount: 0, sampleTimingArray: nil, sampleSizeEntryCount: 1,
                                        sampleSizeArray: &size, sampleBufferOut: &sb) == noErr, let sb else { return nil }
        if let arr = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: true), CFArrayGetCount(arr) > 0 {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(arr, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(dict, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                                 Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        return sb
    }
}

/// Black view hosting the video layer; accepts dropped files.
final class MirrorView: NSView {
    private let videoLayer = AVSampleBufferDisplayLayer()
    private let label = NSTextField(labelWithString: "Перетащите файл сюда — он появится на телефоне")
    var onDrop: (([URL]) -> Void)?

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layer?.backgroundColor = NSColor.black.cgColor
        videoLayer.videoGravity = .resizeAspect
        layer?.addSublayer(videoLayer)
        registerForDraggedTypes([.fileURL])
        label.textColor = .white
        label.font = .systemFont(ofSize: 13, weight: .medium)
        label.alignment = .center
        label.wantsLayer = true
        label.layer?.backgroundColor = NSColor.black.withAlphaComponent(0.55).cgColor
        label.layer?.cornerRadius = 8
        addSubview(label)
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) { [weak self] in
            NSAnimationContext.runAnimationGroup { $0.duration = 0.6; self?.label.animator().alphaValue = 0 }
        }
    }

    required init?(coder: NSCoder) { fatalError() }

    var statusText: String { videoLayer.status == .failed ? "failed: \(videoLayer.error?.localizedDescription ?? "")" : "ok" }

    override func layout() {
        super.layout()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        videoLayer.frame = bounds
        CATransaction.commit()
        label.sizeToFit()
        label.frame = NSRect(x: (bounds.width - label.frame.width - 24) / 2, y: 24, width: label.frame.width + 24, height: 26)
    }

    func enqueue(_ sb: CMSampleBuffer, onFailure: () -> Void) {
        if videoLayer.status == .failed {
            videoLayer.flush()
            onFailure()
        }
        videoLayer.enqueue(sb)
    }

    func flash(_ text: String) {
        label.stringValue = text
        label.alphaValue = 1
        needsLayout = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in
            NSAnimationContext.runAnimationGroup { $0.duration = 0.6; self?.label.animator().alphaValue = 0 }
        }
    }

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
        layer?.borderWidth = 4
        layer?.borderColor = NSColor.systemBlue.cgColor
        return .copy
    }

    override func draggingExited(_ sender: NSDraggingInfo?) {
        layer?.borderWidth = 0
    }

    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        layer?.borderWidth = 0
        let urls = sender.draggingPasteboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL] ?? []
        let files = urls.filter { !$0.hasDirectoryPath }
        if !files.isEmpty { onDrop?(files) }
        return !files.isEmpty
    }
}
