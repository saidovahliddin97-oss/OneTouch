#!/bin/sh
# End-to-end check of the built OneTouch.app on a macOS machine:
#  1. the app starts the core (control API answers);
#  2. phone → Mac: an upload lands on the Desktop and in the clipboard;
#  3. Mac → phone: copying a file (⌘C) makes a simulated phone receive it.
set -eu
cd "$(dirname "$0")"
APP=build/OneTouch.app
T=$(mktemp -d)
( cd ../core && go build -o "$T/onetouch" ./cmd/onetouch && go build -o "$T/fakephone" ./cmd/fakephone )

mkdir -p ~/Desktop
PHONE_PID=""
"$APP/Contents/MacOS/OneTouch" > "$T/app.log" 2>&1 &
APP_PID=$!
trap 'kill $APP_PID $PHONE_PID 2>/dev/null || true; echo "--- core log:"; cat ~/Library/Logs/OneTouch.log || true; echo "--- app log:"; cat "$T/app.log"; echo "--- phone log:"; cat "$T/phone.log" 2>/dev/null || true' EXIT
for i in $(seq 1 30); do curl -sf http://127.0.0.1:47471/local/peers >/dev/null && break; sleep 1; done
curl -sf http://127.0.0.1:47471/local/peers >/dev/null || { echo "core did not start"; cat "$T/app.log"; exit 1; }
echo "✓ app started the core"

# phone → Mac (the CLI plays the phone's role, same TLS upload as Android)
head -c 3000000 /dev/urandom > "$T/from-phone.jpg"
XDG_CONFIG_HOME="$T/cfg" "$T/onetouch" send "$T/from-phone.jpg" --addr 127.0.0.1:47470
sleep 2
test -f ~/Desktop/from-phone.jpg || { echo "file not on Desktop"; exit 1; }
cmp "$T/from-phone.jpg" ~/Desktop/from-phone.jpg
echo "✓ received file is on the Desktop"
osascript -e 'clipboard info' | grep -q furl || { echo "clipboard has no file"; osascript -e 'clipboard info'; exit 1; }
echo "✓ received file is in the clipboard"

# Mac → phone: ⌘C on a file
mkdir -p "$T/phone"
"$T/fakephone" "$T/phone" -no-mdns > "$T/phone.log" 2>&1 &
PHONE_PID=$!
dns-sd -R Pixel-fake01 _onetouch._tcp local 47480 v=2 id=fake01 name=Pixel os=android > /dev/null 2>&1 &
PHONE_PID="$PHONE_PID $!"
sleep 4
echo "--- system Bonjour sees:"; (dns-sd -B _onetouch._tcp local & P=$!; sleep 3; kill $P) || true
echo "--- Go core (CLI) sees:"; "$T/onetouch" peers || true
head -c 2000000 /dev/urandom > "$T/to-phone.pdf"
osascript -e "set the clipboard to (POSIX file \"$T/to-phone.pdf\")"
osascript -e 'clipboard info' 
for i in $(seq 1 20); do [ -s "$T/phone/to-phone.pdf" ] && break; sleep 1; done
sleep 1
cmp "$T/to-phone.pdf" "$T/phone/to-phone.pdf" || { echo "phone did not get the file"; cat "$T/phone.log"; exit 1; }
echo "✓ ⌘C on a file → phone downloaded it"
echo "ALL SMOKE TESTS PASSED"
