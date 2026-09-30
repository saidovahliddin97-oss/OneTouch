import AppKit
import ServiceManagement
import UserNotifications

/// OneTouch for macOS: a menu-bar app around the Go core.
///  • phone → Mac: files land on the Desktop and in the clipboard (⌘V);
///  • Mac → phone: ⌘C on files in Finder, or a pinch on the trackpad, and the
///    phone shows «Получить».
final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate, UNUserNotificationCenterDelegate {
    private let daemon = Daemon()
    private let clipboard = ClipboardWatcher()
    private let pinch = PinchWatcher()
    private let phones = PhoneBrowser()
    private var statusItem: NSStatusItem!
    private let statusLine = NSMenuItem(title: "Запуск…", action: nil, keyEquivalent: "")
    private let phoneLine = NSMenuItem(title: "Телефон: ищу…", action: nil, keyEquivalent: "")
    private var lastReceived: URL?
    private var coreError: String?
    private var warnedLocalNetwork = false
    private let defaults = UserDefaults.standard

    private enum Key {
        static let offerOnCopy = "offerOnCopy"
        static let pinch = "pinchToSend"
        static let toClipboard = "receivedToClipboard"
        static let launched = "launchedBefore"
    }

    func applicationDidFinishLaunching(_ note: Notification) {
        defaults.register(defaults: [Key.offerOnCopy: true, Key.pinch: true, Key.toClipboard: true])

        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        setIcon(ok: true)
        buildMenu()

        let center = UNUserNotificationCenter.current()
        center.delegate = self
        center.requestAuthorization(options: [.alert, .sound]) { _, _ in }
        let reveal = UNNotificationAction(identifier: "reveal", title: "Показать в Finder")
        center.setNotificationCategories([UNNotificationCategory(identifier: "received", actions: [reveal], intentIdentifiers: [])])

        daemon.onEvent = { [weak self] e in self?.handle(e) }
        daemon.onStatus = { [weak self] err in
            self?.coreError = err
            self?.setIcon(ok: err == nil)
            self?.statusLine.title = err.map { "⚠︎ \($0)" } ?? "OneTouch работает"
        }
        daemon.start()
        phones.onChange = { [weak self] in self?.updatePhoneLine() }
        phones.start()

        clipboard.onFiles = { [weak self] urls in self?.offer(urls.map(\.path), reason: "⌘C") }
        pinch.onPinch = { [weak self] in
            let paths = PinchWatcher.finderSelection()
            if paths.isEmpty {
                self?.notify("Выделите файл в Finder", "Потом сведите два пальца на тачпаде")
            } else {
                self?.offer(paths, reason: "щипок")
            }
        }
        applyToggles()

        if !defaults.bool(forKey: Key.launched) {
            defaults.set(true, forKey: Key.launched)
            try? SMAppService.mainApp.register() // start at login by default
            notify("OneTouch в строке меню", "Щипок по фото на телефоне — файл тут. ⌘C на файле — «Получить» на телефоне.")
        }
    }

    func applicationWillTerminate(_ note: Notification) {
        daemon.stop()
    }

    // MARK: - Menu

    private func setIcon(ok: Bool) {
        let name = ok ? "arrow.left.arrow.right.circle" : "exclamationmark.triangle"
        let img = NSImage(systemSymbolName: name, accessibilityDescription: "OneTouch")
        img?.isTemplate = true
        statusItem.button?.image = img
    }

    private func buildMenu() {
        let m = NSMenu()
        m.delegate = self
        statusLine.isEnabled = false
        phoneLine.isEnabled = false
        m.addItem(statusLine)
        m.addItem(phoneLine)
        m.addItem(.separator())
        m.addItem(item("Отправить файл на телефон…", #selector(pickAndSend), "o"))
        m.addItem(item("Показать последний полученный", #selector(revealLast), ""))
        m.addItem(.separator())
        m.addItem(toggle("Предлагать телефону при ⌘C", Key.offerOnCopy))
        m.addItem(toggle("Щипок на тачпаде в Finder → телефон", Key.pinch))
        m.addItem(toggle("Полученное — сразу в буфер (⌘V)", Key.toClipboard))
        let login = item("Запускать при входе", #selector(toggleLogin), "")
        login.state = SMAppService.mainApp.status == .enabled ? .on : .off
        m.addItem(login)
        m.addItem(.separator())
        m.addItem(item("Настройки «Локальная сеть»…", #selector(openLocalNetworkSettings), ""))
        m.addItem(item("Журнал", #selector(openLog), ""))
        m.addItem(item("Выйти из OneTouch", #selector(quit), "q"))
        statusItem.menu = m
    }

    private func item(_ title: String, _ action: Selector, _ key: String) -> NSMenuItem {
        let i = NSMenuItem(title: title, action: action, keyEquivalent: key)
        i.target = self
        return i
    }

    private func toggle(_ title: String, _ key: String) -> NSMenuItem {
        let i = item(title, #selector(flip(_:)), "")
        i.representedObject = key
        i.state = defaults.bool(forKey: key) ? .on : .off
        return i
    }

    func menuWillOpen(_ menu: NSMenu) {
        if let login = menu.items.first(where: { $0.action == #selector(toggleLogin) }) {
            login.state = SMAppService.mainApp.status == .enabled ? .on : .off
        }
        updatePhoneLine()
    }

    private func updatePhoneLine() {
        let names = Set(phones.phones.values.map(\.name)).sorted()
        if !names.isEmpty {
            phoneLine.title = "Телефон: " + names.joined(separator: ", ")
        } else if phones.unresolvedCount > 0 {
            phoneLine.title = "⚠︎ Разрешите OneTouch «Локальную сеть» в настройках"
            if !warnedLocalNetwork {
                warnedLocalNetwork = true
                notify("Нужен доступ к локальной сети",
                       "Системные настройки → Конфиденциальность и безопасность → Локальная сеть → включите OneTouch")
            }
        } else {
            phoneLine.title = "Телефон не найден — откройте OneTouch на Android"
        }
    }

    @objc private func flip(_ sender: NSMenuItem) {
        guard let key = sender.representedObject as? String else { return }
        let on = !defaults.bool(forKey: key)
        defaults.set(on, forKey: key)
        sender.state = on ? .on : .off
        applyToggles()
    }

    private func applyToggles() {
        defaults.bool(forKey: Key.offerOnCopy) ? clipboard.start() : clipboard.stop()
        defaults.bool(forKey: Key.pinch) ? pinch.start() : pinch.stop()
    }

    @objc private func toggleLogin(_ sender: NSMenuItem) {
        do {
            if SMAppService.mainApp.status == .enabled {
                try SMAppService.mainApp.unregister()
            } else {
                try SMAppService.mainApp.register()
            }
        } catch {
            notify("Не получилось", "Переместите OneTouch в папку «Программы» и попробуйте снова")
        }
        sender.state = SMAppService.mainApp.status == .enabled ? .on : .off
    }

    @objc private func pickAndSend() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.prompt = "На телефон"
        NSApp.activate(ignoringOtherApps: true)
        if panel.runModal() == .OK {
            offer(panel.urls.map(\.path), reason: "меню")
        }
    }

    @objc private func revealLast() {
        if let u = lastReceived {
            NSWorkspace.shared.activateFileViewerSelecting([u])
        }
    }

    @objc private func openLocalNetworkSettings() {
        let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_LocalNetwork")!
        NSWorkspace.shared.open(url)
    }

    @objc private func openLog() {
        let url = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Logs/OneTouch.log")
        NSWorkspace.shared.open(url)
    }

    @objc private func quit() {
        NSApp.terminate(nil)
    }

    // MARK: - Transfers

    private func offer(_ paths: [String], reason: String, retry: Bool = true) {
        logLine("offering \(paths.joined(separator: ", ")) (\(reason)) to \(phones.phones.count) phone(s)")
        var targets = Array(phones.phones.values)
        // Test hook: ONETOUCH_EXTRA_PHONE=host:port adds a phone that Bonjour may not
        // see (CI machines cannot grant the Local Network permission).
        if let extra = ProcessInfo.processInfo.environment["ONETOUCH_EXTRA_PHONE"],
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
            var subtitle = "На рабочем столе"
            if defaults.bool(forKey: Key.toClipboard) {
                let pb = NSPasteboard.general
                pb.clearContents()
                pb.writeObjects([url as NSURL])
                clipboard.ignoreCurrent() // don't offer it straight back to the phone
                subtitle = "В буфере — нажмите ⌘V, чтобы вставить"
            }
            notify("📥 \(e.name ?? url.lastPathComponent) от \(e.from ?? "телефона")", subtitle, file: url)
        case "error":
            if let msg = e.error { statusLine.title = "⚠︎ \(msg)" }
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
