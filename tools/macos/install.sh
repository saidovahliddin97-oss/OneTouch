#!/bin/sh
# Установка OneTouch на macOS: бинарник + автозапуск (launchd, без нагрузки в простое).
set -e
ARCH=$(uname -m); [ "$ARCH" = "x86_64" ] && ARCH=amd64
SRC="$(cd "$(dirname "$0")/../../dist" && pwd)/onetouch-darwin-$ARCH"
sudo mkdir -p /usr/local/bin
sudo cp "$SRC" /usr/local/bin/onetouch
sudo chmod +x /usr/local/bin/onetouch
sudo xattr -d com.apple.quarantine /usr/local/bin/onetouch 2>/dev/null || true
PL=~/Library/LaunchAgents/app.onetouch.plist
mkdir -p ~/Library/LaunchAgents
cat > "$PL" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>app.onetouch</string>
  <key>ProgramArguments</key><array><string>/usr/local/bin/onetouch</string><string>serve</string><string>--no-qr</string></array>
  <key>RunAtLoad</key><true/><key>KeepAlive</key><true/>
  <key>ProcessType</key><string>Background</string>
  <key>StandardOutPath</key><string>/tmp/onetouch.log</string>
  <key>StandardErrorPath</key><string>/tmp/onetouch.log</string>
</dict></plist>
PLIST
launchctl unload "$PL" 2>/dev/null || true
launchctl load "$PL"
echo "✓ OneTouch установлен и запущен в фоне. Лог: /tmp/onetouch.log"
sleep 1; grep "телефон" /tmp/onetouch.log | tail -1
echo "  Откройте эту ссылку на телефоне (или поставьте APK). Хоткей: tools/macos/onetouch.lua"
