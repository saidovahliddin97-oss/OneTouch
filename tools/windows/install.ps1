# Установка OneTouch на Windows: копирует exe в %LOCALAPPDATA%\OneTouch, открывает порты
# в брандмауэре (нужен запуск от администратора) и добавляет автозапуск.
$ErrorActionPreference = "Stop"
$arch = if ($env:PROCESSOR_ARCHITECTURE -eq "ARM64") { "arm64" } else { "amd64" }
$dst = Join-Path $env:LOCALAPPDATA "OneTouch"
New-Item -ItemType Directory -Force $dst | Out-Null
Copy-Item (Join-Path $PSScriptRoot "..\..\dist\onetouch-windows-$arch.exe") (Join-Path $dst "onetouch.exe") -Force
Copy-Item (Join-Path $PSScriptRoot "onetouch-hotkey.ahk") $dst -Force
$exe = Join-Path $dst "onetouch.exe"
try {
  New-NetFirewallRule -DisplayName "OneTouch" -Direction Inbound -Program $exe -Action Allow -Profile Private -ErrorAction Stop | Out-Null
  New-NetFirewallRule -DisplayName "OneTouch mDNS" -Direction Inbound -Protocol UDP -LocalPort 5353 -Action Allow -Profile Private -ErrorAction Stop | Out-Null
} catch { Write-Warning "Брандмауэр не настроен (запустите от администратора или разрешите в окне Windows при первом запуске)" }
$startup = [Environment]::GetFolderPath("Startup")
$lnk = (New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path $startup "OneTouch.lnk"))
$lnk.TargetPath = $exe; $lnk.Arguments = "serve"; $lnk.WindowStyle = 7; $lnk.Save()
Start-Process $exe -ArgumentList "serve"
Write-Host "✓ OneTouch установлен: $exe (автозапуск включён). Хоткей: запустите $dst\onetouch-hotkey.ahk"
