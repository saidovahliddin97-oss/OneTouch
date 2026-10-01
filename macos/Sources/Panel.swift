import SwiftUI

/// Everything the menu-bar panel shows. Updated by AppDelegate on the main thread.
final class AppState: ObservableObject {
    struct Session: Identifiable {
        let id: String
        let title: String
        let symbol: String
    }

    @Published var coreError: String?
    @Published var phones: [String] = []
    @Published var localNetworkProblem = false
    @Published var sessions: [Session] = []
    @Published var lastReceived: String?
    @Published var screenAllowed = true
    @Published var controlAllowed = true

    @Published var offerOnCopy = true
    @Published var pinch = true
    @Published var toClipboard = true
    @Published var launchAtLogin = false
}

struct PanelActions {
    var sendFile: () -> Void
    var revealLast: () -> Void
    var endSession: (String) -> Void
    var openScreenSettings: () -> Void
    var openAccessibilitySettings: () -> Void
    var openLocalNetworkSettings: () -> Void
    var settingsChanged: () -> Void
    var forgetDevices: () -> Void
    var openLog: () -> Void
    var quit: () -> Void
}

struct PanelView: View {
    @ObservedObject var state: AppState
    let actions: PanelActions

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header
            if let err = state.coreError {
                Banner(symbol: "exclamationmark.triangle.fill", tint: .orange, text: err)
            }
            devices
            if !state.sessions.isEmpty { sessions }
            actionsRow
            if !state.screenAllowed || !state.controlAllowed { permissions }
            settings
            footer
        }
        .padding(16)
        .frame(width: 340)
    }

    private var header: some View {
        HStack(spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .fill(LinearGradient(colors: [Color(red: 0.36, green: 0.58, blue: 1), Color(red: 0.2, green: 0.36, blue: 0.95)],
                                         startPoint: .top, endPoint: .bottom))
                Image(systemName: "arrow.left.arrow.right").font(.system(size: 15, weight: .bold)).foregroundColor(.white)
            }
            .frame(width: 32, height: 32)
            VStack(alignment: .leading, spacing: 1) {
                Text("OneTouch").font(.system(size: 15, weight: .semibold))
                HStack(spacing: 5) {
                    Circle().fill(state.coreError == nil ? Color.green : Color.orange).frame(width: 7, height: 7)
                    Text(state.coreError == nil ? "Работает" : "Проблема").font(.caption).foregroundColor(.secondary)
                }
            }
            Spacer()
        }
    }

    private var devices: some View {
        Card(title: "Устройства") {
            if state.phones.isEmpty {
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: state.localNetworkProblem ? "wifi.exclamationmark" : "iphone.slash")
                        .foregroundColor(.secondary).frame(width: 22)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(state.localNetworkProblem ? "Нет доступа к локальной сети" : "Телефон не найден")
                            .font(.system(size: 13, weight: .medium))
                        Text(state.localNetworkProblem
                             ? "Разрешите OneTouch «Локальную сеть» в настройках."
                             : "Откройте OneTouch на Android в этой же Wi‑Fi сети.")
                            .font(.caption).foregroundColor(.secondary)
                        if state.localNetworkProblem {
                            Button("Открыть настройки", action: actions.openLocalNetworkSettings).buttonStyle(.link).font(.caption)
                        }
                    }
                }
            } else {
                ForEach(state.phones, id: \.self) { name in
                    HStack(spacing: 10) {
                        Image(systemName: "iphone").font(.system(size: 16)).foregroundColor(.accentColor).frame(width: 22)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(name).font(.system(size: 13, weight: .medium))
                            Text("В сети").font(.caption).foregroundColor(.secondary)
                        }
                        Spacer()
                        Circle().fill(Color.green).frame(width: 7, height: 7)
                    }
                }
            }
        }
    }

    private var sessions: some View {
        Card(title: "Трансляция") {
            ForEach(state.sessions) { s in
                HStack(spacing: 10) {
                    Image(systemName: s.symbol).foregroundColor(.red).frame(width: 22)
                    Text(s.title).font(.system(size: 13, weight: .medium))
                    Spacer()
                    Button("Завершить") { actions.endSession(s.id) }.controlSize(.small)
                }
            }
        }
    }

    private var actionsRow: some View {
        HStack(spacing: 8) {
            BigButton(symbol: "paperplane.fill", title: "Файл на телефон", action: actions.sendFile)
            BigButton(symbol: "tray.and.arrow.down.fill", title: "Последний полученный", action: actions.revealLast)
                .disabled(state.lastReceived == nil)
        }
    }

    private var permissions: some View {
        Card(title: "Разрешения для экрана") {
            if !state.screenAllowed {
                PermissionRow(symbol: "rectangle.dashed.badge.record", text: "Запись экрана — чтобы видеть Mac с телефона",
                              action: actions.openScreenSettings)
            }
            if !state.controlAllowed {
                PermissionRow(symbol: "hand.tap", text: "Универсальный доступ — чтобы управлять Mac с телефона",
                              action: actions.openAccessibilitySettings)
            }
        }
    }

    private var settings: some View {
        Card(title: "Настройки") {
            Toggle("⌘C на файле — предложить телефону", isOn: $state.offerOnCopy)
            Toggle("Щипок на тачпаде в Finder — на телефон", isOn: $state.pinch)
            Toggle("Полученное — сразу в буфер (⌘V)", isOn: $state.toClipboard)
            Toggle("Запускать при входе", isOn: $state.launchAtLogin)
        }
        .toggleStyle(.switch)
        .controlSize(.mini)
        .font(.system(size: 12))
        .onChange(of: state.offerOnCopy) { _ in actions.settingsChanged() }
        .onChange(of: state.pinch) { _ in actions.settingsChanged() }
        .onChange(of: state.toClipboard) { _ in actions.settingsChanged() }
        .onChange(of: state.launchAtLogin) { _ in actions.settingsChanged() }
    }

    private var footer: some View {
        HStack {
            Menu("Ещё") {
                Button("Забыть все телефоны (снова спрашивать разрешение)", action: actions.forgetDevices)
                Button("Настройки «Локальная сеть»…", action: actions.openLocalNetworkSettings)
                Button("Журнал", action: actions.openLog)
            }
            .menuStyle(.borderlessButton)
            .fixedSize()
            Spacer()
            Button("Выйти", action: actions.quit).buttonStyle(.borderless).foregroundColor(.secondary)
        }
        .font(.system(size: 12))
    }
}

private struct Card<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title.uppercased()).font(.system(size: 10, weight: .semibold)).foregroundColor(.secondary)
            content
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color.primary.opacity(0.05)))
    }
}

private struct Banner: View {
    let symbol: String
    let tint: Color
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: symbol).foregroundColor(tint)
            Text(text).font(.caption).fixedSize(horizontal: false, vertical: true)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(tint.opacity(0.12)))
    }
}

private struct BigButton: View {
    let symbol: String
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: symbol).font(.system(size: 17))
                Text(title).font(.system(size: 11, weight: .medium)).multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity, minHeight: 58)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color.accentColor.opacity(0.12)))
        .foregroundColor(.accentColor)
    }
}

private struct PermissionRow: View {
    let symbol: String
    let text: String
    let action: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: symbol).foregroundColor(.orange).frame(width: 22)
            Text(text).font(.caption).fixedSize(horizontal: false, vertical: true)
            Spacer()
            Button("Открыть", action: action).controlSize(.small)
        }
    }
}
