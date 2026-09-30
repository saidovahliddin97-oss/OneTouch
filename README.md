# OneTouch

Мгновенная P2P-передача файлов между Android, macOS и Windows по локальной Wi‑Fi сети. Без облака, без интернета, без привязки к бренду.

```
Android ──🤏 щипок по фото──▶  буфер Mac/PC  ──⌃⌥V / разжатие на тачпаде──▶  файл на рабочем столе
```

## Быстрый старт (5 минут)

### 1. Компьютер (Mac / Windows / Linux)

Готовые бинарники лежат в [`dist/`](dist/):

| ОС | Файл |
|---|---|
| macOS Apple Silicon (M1–M4) | `onetouch-darwin-arm64` |
| macOS Intel | `onetouch-darwin-amd64` |
| Windows | `onetouch-windows-amd64.exe` (ARM: `-arm64.exe`) |
| Linux | `onetouch-linux-amd64` / `-arm64` |

**macOS:**
```sh
chmod +x onetouch-darwin-arm64
xattr -d com.apple.quarantine onetouch-darwin-arm64   # бинарник не подписан
./onetouch-darwin-arm64 serve
```
При первом запуске macOS спросит «Разрешить входящие подключения / доступ к локальной сети» — нажмите **Разрешить**.
Для постоянной работы в фоне: `sh tools/macos/install.sh` (launchd-агент, автозапуск).

**Windows** (PowerShell):
```powershell
.\onetouch-windows-amd64.exe serve
```
Когда брандмауэр спросит, отметьте **Частные сети → Разрешить**. Автозапуск: `tools\windows\install.ps1`.

В терминале появится QR-код и ссылка вида `http://192.168.1.20:47471/?t=…`.

### 2. Телефон — два варианта

**A. Без установки (любой телефон, прямо сейчас):** наведите камеру на QR-код → откроется страница. Выберите фото, затем:
- **щипок** (сведите два пальца) по фото → файл уходит в **буфер** компьютера;
- кнопка **↑** → файл сразу ложится на **рабочий стол**.

**B. Android-приложение:** установите [`dist/OneTouch.apk`](dist/) (разрешите «установку из неизвестных источников»). Приложение само находит компьютеры через mDNS — ни QR, ни IP вводить не нужно. Жесты те же: щипок → буфер, ↑ → рабочий стол.

### 3. «Вставить» на компьютере

```sh
onetouch paste          # буфер → рабочий стол
```
Повесьте это на жест или хоткей:
- **macOS, хоткей ⌃⌥V:** [Hammerspoon](https://www.hammerspoon.org) + [`tools/macos/onetouch.lua`](tools/macos/onetouch.lua)
- **macOS, жест «разжать пальцы» на тачпаде (экспериментально):** `swiftc -O tools/macos/PinchPaste.swift -o pinchpaste && ./pinchpaste /usr/local/bin/onetouch`
- **Windows, хоткей Ctrl+Alt+V:** [AutoHotkey v2](https://www.autohotkey.com) + [`tools/windows/onetouch-hotkey.ahk`](tools/windows/onetouch-hotkey.ahk)

Не хотите буфер? `onetouch serve --auto-save` — щипок кладёт файл сразу на рабочий стол.

### Все команды

```
onetouch serve  [--name N] [--out DIR] [--auto-save]   узел: приём файлов + веб-страница для телефона
onetouch peers                                         кто есть в сети
onetouch send   <файл...> [--to ИМЯ]                   отправить на рабочий стол пира
onetouch clip   <файл>    [--to ИМЯ]                   положить в буфер пира
onetouch paste  [--from ИМЯ]                           вставить из своего буфера (или забрать из буфера пира)
onetouch copy   <файл>                                 положить в свой буфер (телефон/другой ПК заберёт)
onetouch id     [--name N]                             имя, id, отпечаток сертификата
```
Если роутер блокирует multicast (гостевые сети, «изоляция клиентов»): `onetouch send file --addr 192.168.1.20`.

---

## Архитектура

```
┌─────────────────────── Устройство (любое) ───────────────────────┐
│  UI: Flutter (Android/macOS/Windows)  |  CLI / веб-страница      │
│               │                                                  │
│  Core (Go сейчас → Rust/Go FFI в приложении):                    │
│   ├─ identity   — ID устройства + самоподписанный ECDSA P-256    │
│   ├─ discovery  — mDNS/DNS-SD  _onetouch._tcp  (TXT: id,name,fp) │
│   ├─ transfer   — HTTPS/TLS 1.3 стриминг, атомарная запись       │
│   └─ buffer     — «буфер экосистемы» (1 слот, как clipboard)     │
└──────────────────────────────────────────────────────────────────┘
          ▲  mDNS (UDP 5353, только по запросу)
          ▼  TLS 1.3 (TCP 47470) — файл идёт напрямую, P2P
```

### Сеть

| Слой | Решение | Почему |
|---|---|---|
| Обнаружение | mDNS / DNS-SD, сервис `_onetouch._tcp` | Родной Bonjour на macOS, NsdManager на Android, работает на Windows 10+. Никаких серверов. |
| Анонс | TXT: `v, id, name, os, fp, web` | `fp` — SHA-256 сертификата: клиент пиннингует его, MITM в сети не пройдёт. |
| Транспорт | HTTP/1.1 поверх TLS 1.3, `PUT` потоком | Нулевые копии в памяти (стрим 1 МБ буфером), упирается в скорость Wi‑Fi. На localhost ~600 МБ/с. |
| Запись | во временный `.part` → `rename` | Недокачанный файл никогда не появится под настоящим именем. |
| Буфер | один слот на принимающей стороне + `GET /v1/clip/data` | Работает и «push» (щипок отправляет), и «pull» (`paste` сам заберёт у пира). |
| Браузер | HTTP + случайный токен в QR | Телефон без приложения. (Трафик браузера не шифрован — для чувствительного используйте APK.) |

**Протокол v1** (`core/internal/transfer/server.go`):

```
GET  /v1/info                                   {"id","name","os","fp"}
PUT  /v1/files?name=X&mode=save|clip&from=Y     тело = байты файла
GET  /v1/clip                                   метаданные буфера (404 если пуст)
GET  /v1/clip/data                              содержимое буфера
```

### Энергосбережение

- **Нет фонового сканирования.** Десктоп-узел только *отвечает* на mDNS-запросы и спит в `accept()` — 0% CPU в простое.
- **Телефон ищет пиров только пока приложение на экране** (`AppLifecycleState.paused` → `stopDiscovery`).
- Keep-alive соединения закрываются через 30–60 с простоя, радиомодуль засыпает.
- Нет полинга: «пробуждение» = входящее TCP-соединение (локальный push).

---

## План реализации MVP

| # | Этап | Статус |
|---|---|---|
| 1 | P2P-ядро: mDNS + TLS-передача + пиннинг сертификата | ✅ `core/` |
| 2 | Буфер экосистемы (clip/paste, push+pull) | ✅ |
| 3 | Телефон без установки: веб-страница + щипок | ✅ |
| 4 | Android-приложение (Flutter): автопоиск, щипок → буфер | ✅ `app/` |
| 5 | Хоткей/жест «вставить» на Mac/Windows | ✅ скрипты в `tools/` (жест на Mac — эксперимент) |
| 6 | «Поделиться → OneTouch» из Галереи Android (share intent) | ⏭ следующий |
| 7 | Сопряжение по 6-значному коду (вместо доверия к mDNS-отпечатку при первом контакте) | ⏭ |
| 8 | Desktop-приложение на Flutter (трей, drag&drop) с Go-ядром через FFI (`c-shared`) | ⏭ каркас `app/macos`, `app/windows` |
| 9 | Папки, возобновление обрыва (`Range`), параллельные потоки | ⏭ |
| 10 | Hand-off медиа (см. ниже) | 🔭 |

### Масштабирование: Hand-off видео (альтернатива AirPlay/Chromecast)

Архитектура уже готова: достаточно нового `mode`/эндпоинта в том же TLS-канале.

```
POST /v1/handoff  {"kind":"url","url":"https://youtu.be/…","position":754.2,"playing":true}
```
- **URL-сессии (YouTube, VK Видео):** передаём ссылку + позицию, приёмник (TV/планшет с OneTouch) открывает плеер и продолжает с той же секунды. Трафик видео идёт с CDN напрямую на TV — телефон не тратит батарею.
- **Локальное видео:** `GET /v1/stream/{id}` с поддержкой `Range` (HLS/прогрессивное) — TV тянет файл с телефона по LAN.
- **Свайп-жест:** на телефоне свайп вверх по плееру → `handoff` на выбранный (или ближайший по RSSI/последнему использованию) пир.
- Для экрана/камеры в реальном времени — WebRTC поверх того же mDNS-обнаружения (сигнализация через `/v1/rtc/offer`).

---

## Сборка из исходников

```sh
# Ядро / CLI (Go 1.24+)
cd core && go build -o onetouch ./cmd/onetouch
# кросс-сборка: GOOS=darwin GOARCH=arm64 CGO_ENABLED=0 go build ./cmd/onetouch

# Android APK (Flutter 3.35+)
cd app && flutter build apk --release
```

CI (`.github/workflows/build.yml`) собирает все бинарники и APK на каждый push и публикует релиз при теге `v*`.

## Структура

```
core/                 Go: ядро и CLI
  cmd/onetouch/       CLI: serve, peers, send, clip, paste, copy, id
  internal/identity/  ID устройства, TLS-сертификат
  internal/discovery/ mDNS анонс и поиск
  internal/transfer/  сервер, клиент, буфер, веб-страница для телефона
app/                  Flutter: Android (+ каркас macOS/Windows)
tools/                хоткеи, жесты, установщики для macOS/Windows
dist/                 готовые сборки
```

## Проверка / устранение неполадок

- `onetouch peers` ничего не видит → устройства в одной сети? В гостевой сети/с «изоляцией точек доступа» multicast режется — используйте `--addr IP`.
- На Windows телефон не подключается → разрешите `onetouch.exe` в брандмауэре для **частной** сети, а сеть Wi‑Fi пометьте как «Частная».
- macOS: «не удаётся открыть, разработчик не проверен» → `xattr -d com.apple.quarantine onetouch-darwin-*`.
