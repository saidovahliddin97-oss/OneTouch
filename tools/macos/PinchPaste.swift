// PinchPaste — macOS: жест «разжать два пальца» на тачпаде → `onetouch paste`.
// Сборка:  swiftc -O PinchPaste.swift -o pinchpaste
// Запуск:  ./pinchpaste /usr/local/bin/onetouch
// Нужно разрешение: Системные настройки → Конфиденциальность → Универсальный доступ.
// Статус: экспериментально. Надёжный путь — хоткей (onetouch.lua / Shortcuts).
import Cocoa

let bin = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "/usr/local/bin/onetouch"
var total: CGFloat = 0
var fired = false

func paste() {
    let p = Process()
    p.executableURL = URL(fileURLWithPath: bin)
    p.arguments = ["paste"]
    try? p.run()
    NSSound(named: "Pop")?.play()
}

let opts = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
if !AXIsProcessTrustedWithOptions(opts) {
    print("Дайте доступ в «Универсальный доступ» и перезапустите.")
}

NSEvent.addGlobalMonitorForEvents(matching: .magnify) { e in
    switch e.phase {
    case .began: total = 0; fired = false
    case .changed:
        total += e.magnification
        if total > 0.6 && !fired { fired = true; paste() } // разжатие ≈ +60%
    default: total = 0
    }
}
print("PinchPaste: разожмите два пальца на тачпаде, чтобы вставить из буфера OneTouch")
NSApplication.shared.setActivationPolicy(.accessory)
NSApplication.shared.run()
