import AppKit
import ServiceManagement
import SwiftUI
import UserNotifications

/// OneTouch for macOS: a menu-bar panel around the Go core.
///  • phone → Mac: files land on the Desktop and in the clipboard (⌘V);
///  • Mac → phone: ⌘C on files in Finder, or a pinch on the trackpad → «Получить»;
///  • screen: the phone watches and controls this Mac, or shows its own screen here.
final class AppDelegate: NSObject, NSApplicationDelegate, UNUserNotificationCenterDelegate {
    private let daemon = Daemon()
    private let clipboard = ClipboardWatcher()
    private let pinch = PinchWatcher()
    private let phones = PhoneBrowser()
    private let sessionServer = SessionServer()
    private let state = AppState()
    private var statusItem: NSStatusItem!
    private let popover = NSPopover()
    private var lastReceived: URL?
    private var warnedLocalNetwork = false
    private var screenSessions: [String: ScreenSession] = [:]
    private var mirrorSessions: [String: MirrorSession] = [:]
    private let defaults = UserDefaults.standard
    private let env = ProcessInfo.processInfo.environment

    private enum Key {
        static let offerOnCopy = "offerOnCopy"
        static let pinch = "pinchToSend"
        static let toClipboard = "receivedToClipboard"
        static let launched = "launchedBefore"
    }

    func applicationDidFinishLaunching(_ note: Notification) {
        defaults.register(defaults: [Key.offerOnCopy: true, Key.pinch: true, Key.toClipboard: true])
        state.offerOnCopy = defaults.bool(forKey: Key.offerOnCopy)
        state.pinch = defaults.bool(forKey: Key.pinch)
        state.toClipboard = defaults.bool(forKey: Key.toClipboard)
        state.launchAtLogin = SMAppService.mainApp.status == .enabled

        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        statusItem.button?.target = self
        statusItem.button?.action = #selector(togglePanel)
        updateIcon()
        popover.behavior = .transient
        popover.contentViewController = NSHostingController(rootView: PanelView(state: state, actions: panelActions()))

        let center = UNUserNotificationCenter.current()
        center.delegate = self
        center.requestAuthorization(options: [.alert, .sound]) { _, _ in }
        let reveal = UNNotificationAction(identifier: "reveal", title: "Показать в Finder")
        center.setNotificationCategories([UNNotificationCategory(identifier: "received", actions: [reveal], intentIdentifiers: [])])

        daemon.onEvent = { [weak self] e in self?.handle(e) }
        daemon.onStatus = { [weak self] err in
            self?.state.coreError = err
            self?.updateIcon()
        }
        daemon.start()
        phones.onChange = { [weak self] in self?.updatePhones() }
        phones.start()
        sessionServer.onSession = { [weak self] peer, fc in
            DispatchQueue.main.async { self?.incoming(peer, fc) }
        }
        sessionServer.start()

        clipboard.onFiles = { [weak self] urls in self?.offer(urls.map(\.path), reason: "⌘C") }
        pinch.onPinch = { [weak self] in
            let paths = PinchWatcher.finderSelection()
            if paths.isEmpty {
                self?.notify("Выделите файл в Finder", "Потом сведите два пальца на тачпаде")
            } else {
                self?.offer(paths, reason: "щипок")
            }
        }
        applySettings()

        if !defaults.bool(forKey: Key.launched) {
            defaults.set(true, forKey: Key.launched)
            try? SMAppService.mainApp.register() // start at login by default
            state.launchAtLogin = SMAppService.mainApp.status == .enabled
            notify("OneTouch в строке меню", "Файлы и экран между Mac и телефоном. Нажмите на значок ⇄ в строке меню.")
        }
    }

    func applicationWillTerminate(_ note: Notification) {
        screenSessions.values.forEach { $0.stop() }
        mirrorSessions.values.forEach { $0.stop() }
        daemon.stop()
    }

    // MARK: - Panel

    @objc private func togglePanel() {
        if popover.isShown {
            popover.performClose(nil)
            return
        }
        refreshPermissions()
        updatePhones()
        if let button = statusItem.button {
            popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
            popover.contentViewController?.view.window?.makeKey()
        }
    }

    private func updateIcon() {
        let live = !screenSessions.isEmpty || !mirrorSessions.isEmpty
        let name = state.coreError != nil ? "exclamationmark.triangle" : (live ? "record.circle" : "arrow.left.arrow.right.circle")
        let img = NSImage(systemSymbolName: name, accessibilityDescription: "OneTouch")
        img?.isTemplate = !live
        statusItem.button?.image = img
        statusItem.button?.contentTintColor = live ? .systemRed : nil
    }

    private func panelActions() -> PanelActions {
        PanelActions(
            sendFile: { [weak self] in self?.pickAndSend() },
            revealLast: { [weak self] in
                if let u = self?.lastReceived { NSWorkspace.shared.activateFileViewerSelecting([u]) }
            },
            endSession: { [weak self] id in
                self?.screenSessions[id]?.stop()
                self?.mirrorSessions[id]?.stop()
            },
            openScreenSettings: {
                _ = CGRequestScreenCaptureAccess()
                NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture")!)
            },
            openAccessibilitySettings: {
                InputInjector.requestPermission()
                NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")!)
            },
            openLocalNetworkSettings: {
                NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_LocalNetwork")!)
            },
            settingsChanged: { [weak self] in self?.applySettings() },
            forgetDevices: { TrustStore.forgetAll() },
            openLog: {
                NSWorkspace.shared.open(FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Logs/OneTouch.log"))
            },
            quit: { NSApp.terminate(nil) }
        )
    }

    private func refreshPermissions() {
        state.screenAllowed = CGPreflightScreenCaptureAccess()
        state.controlAllowed = InputInjector.allowed
    }

    private func applySettings() {
        defaults.set(state.offerOnCopy, forKey: Key.offerOnCopy)
        defaults.set(state.pinch, forKey: Key.pinch)
        defaults.set(state.toClipboard, forKey: Key.toClipboard)
        state.offerOnCopy ? clipboard.start() : clipboard.stop()
        state.pinch ? pinch.start() : pinch.stop()
        let enabled = SMAppService.mainApp.status == .enabled
        if state.launchAtLogin != enabled {
            do {
                if state.launchAtLogin { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            } catch {
                notify("Не получилось", "Переместите OneTouch в папку «Программы» и попробуйте снова")
                state.launchAtLogin = enabled
            }
        }
    }

    private func updatePhones() {
        let names = Set(phones.phones.values.map(\.name)).sorted()
        state.phones = names
        state.localNetworkProblem = names.isEmpty && phones.unresolvedCount > 0
        if state.localNetworkProblem && !warnedLocalNetwork {
            warnedLocalNetwork = true
            notify("Нужен доступ к локальной сети",
                   "Системные настройки → Конфиденциальность и безопасность → Локальная сеть → включите OneTouch")
        }
    }

    private func pickAndSend() {
        popover.performClose(nil)
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.prompt = "На телефон"
        NSApp.activate(ignoringOtherApps: true)
        if panel.runModal() == .OK {
            offer(panel.urls.map(\.path), reason: "меню")
        }
    }

    // MARK: - Screen sessions

    private func incoming(_ peer: SessionPeer, _ fc: FrameConn) {
        let start = { [weak self] in self?.startSession(peer, fc) }
        if TrustStore.isTrusted(peer) || env["ONETOUCH_AUTO_TRUST"] != nil {
            start()
            return
        }
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = peer.kind == "screen"
            ? "«\(peer.name)» хочет видеть экран этого Mac и управлять им"
            : "«\(peer.name)» хочет показать свой экран на этом Mac"
        alert.informativeText = "Разрешайте только своему телефону. Трансляцию можно завершить в любой момент из значка OneTouch в строке меню."
        alert.addButton(withTitle: "Разрешить")
        alert.addButton(withTitle: "Запретить")
        alert.showsSuppressionButton = true
        alert.suppressionButton?.title = "Запомнить этот телефон"
        alert.suppressionButton?.state = .on
        if alert.runModal() == .alertFirstButtonReturn {
            if alert.suppressionButton?.state == .on { TrustStore.trust(peer) }
            start()
        } else {
            fc.queue.async {
                fc.sendJSON(.status, ["text": "Mac отклонил запрос"])
                fc.queue.asyncAfter(deadline: .now() + 0.3) { fc.close() }
            }
        }
    }

    private func startSession(_ peer: SessionPeer, _ fc: FrameConn) {
        let key = peer.kind + ":" + peer.id
        if peer.kind == "screen" {
            screenSessions[key]?.stop()
            let fake = env["ONETOUCH_FAKE_SCREEN"] != nil
            if !fake && !CGPreflightScreenCaptureAccess() {
                _ = CGRequestScreenCaptureAccess()
                fc.queue.async {
                    fc.sendJSON(.status, ["text": "Разрешите OneTouch «Запись экрана» на Mac (Настройки → Конфиденциальность), затем перезапустите OneTouch"])
                    fc.queue.asyncAfter(deadline: .now() + 0.5) { fc.close() }
                }
                notify("Нужно разрешение «Запись экрана»", "Чтобы показать экран Mac на телефоне")
                return
            }
            let s = ScreenSession(peer: peer, fc: fc, fake: fake)
            s.selection = { [weak self] in
                if let test = self?.env["ONETOUCH_TEST_GRAB"] { return [test] }
                return PinchWatcher.finderSelection()
            }
            s.registerOffer = { [weak self] paths, done in self?.daemon.registerOffer(paths, completion: done) }
            s.onEnd = { [weak self] in
                self?.screenSessions[key] = nil
                self?.sessionsChanged()
            }
            screenSessions[key] = s
            s.start()
            notify("\(peer.name) смотрит экран Mac", "Завершить можно из значка OneTouch в строке меню")
        } else {
            mirrorSessions[key]?.stop()
            let m = MirrorSession(peer: peer, fc: fc)
            m.registerOffer = { [weak self] paths, done in self?.daemon.registerOffer(paths, completion: done) }
            m.onEnd = { [weak self] in
                self?.mirrorSessions[key] = nil
                self?.sessionsChanged()
            }
            mirrorSessions[key] = m
            m.start()
        }
        sessionsChanged()
    }

    private func sessionsChanged() {
        state.sessions = screenSessions.map { AppState.Session(id: $0.key, title: "\($0.value.peer.name) смотрит этот Mac", symbol: "display") }
            + mirrorSessions.map { AppState.Session(id: $0.key, title: "Экран \($0.value.peer.name)", symbol: "iphone") }
        updateIcon()
    }

    // MARK: - Transfers

    private func offer(_ paths: [String], reason: String, retry: Bool = true) {
        logLine("offering \(paths.joined(separator: ", ")) (\(reason)) to \(phones.phones.count) phone(s)")
        var targets = Array(phones.phones.values)
        // Test hook: ONETOUCH_EXTRA_PHONE=host:port adds a phone that Bonjour may not
        // see (CI machines cannot grant the Local Network permission).
        if let extra = env["ONETOUCH_EXTRA_PHONE"],
           let colon = extra.lastIndex(of: ":"), let port = Int(extra[extra.index(after: colon)...]) {
            targets.append(PhoneBrowser.Phone(name: "test-phone", host: String(extra[..<colon]), port: port))
        }
        daemon.offer(paths, to: targets) { [weak self] result in
            let what = paths.count == 1 ? (paths[0] as NSString).lastPathComponent : "\(paths.count) файлов"
            switch result {
            case .success(let phones):
                self?.notify("\(what) → \(phones.joined(separator: ", "))", "Нажмите «Получить» на телефоне")
            case .failure(let e):
                logLine("offer failed: \(e.message)")
                if retry, let self {
                    // The phone may have changed its address: browse afresh and try once more.
                    self.phones.refresh()
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { self.offer(paths, reason: reason, retry: false) }
                    return
                }
                // Silent for ⌘C: copying files is common and the phone may simply be away.
                if reason != "⌘C" { self?.notify("Не отправлено", e.message) }
            }
        }
    }

    private func handle(_ e: Daemon.Event) {
        switch e.type {
        case "ready":
            if let id = e.id, let fp = e.fp, let port = e.port {
                phones.publish(name: e.name ?? "Mac", id: id, fp: fp, port: port)
            }
        case "received":
            guard let path = e.path else { return }
            let url = URL(fileURLWithPath: path)
            lastReceived = url
            state.lastReceived = url.lastPathComponent
            var subtitle = "На рабочем столе"
            if state.toClipboard {
                let pb = NSPasteboard.general
                pb.clearContents()
                pb.writeObjects([url as NSURL])
                clipboard.ignoreCurrent() // don't offer it straight back to the phone
                subtitle = "В буфере — нажмите ⌘V, чтобы вставить"
            }
            notify("📥 \(e.name ?? url.lastPathComponent) от \(e.from ?? "телефона")", subtitle, file: url)
        case "error":
            if let msg = e.error { logLine("core: \(msg)") }
        default:
            break
        }
    }

    private func notify(_ title: String, _ body: String, file: URL? = nil) {
        let c = UNMutableNotificationContent()
        c.title = title
        c.body = body
        if let file {
            c.categoryIdentifier = "received"
            c.userInfo = ["path": file.path]
            if let att = try? UNNotificationAttachment(identifier: "file", url: copyForPreview(file), options: nil) {
                c.attachments = [att]
            }
        }
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: c, trigger: nil))
    }

    /// Notification attachments are moved into the system store, so hand it a copy.
    private func copyForPreview(_ url: URL) -> URL {
        let ext = url.pathExtension.lowercased()
        guard ["jpg", "jpeg", "png", "heic", "gif"].contains(ext) else { return URL(fileURLWithPath: "/nonexistent") }
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "." + ext)
        try? FileManager.default.copyItem(at: url, to: tmp)
        return tmp
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completion: @escaping (UNNotificationPresentationOptions) -> Void) {
        completion([.banner, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completion: @escaping () -> Void) {
        if let path = response.notification.request.content.userInfo["path"] as? String {
            NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)])
        }
        completion()
    }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.accessory)
app.run()
