#!/bin/bash
set -eu
probe_dir="$(cd "$(dirname "$0")" && pwd)"
repo_dir="$(cd "$probe_dir/../.." && pwd)"
output="$repo_dir/app/build/handy-ble-probe/Handy BLE Probe.app"
mkdir -p "$output/Contents/MacOS"
/usr/bin/swiftc -swift-version 5 -O -framework AppKit -framework CoreBluetooth \
  "$probe_dir/ProbeProtocol.swift" "$probe_dir/ProbeTests.swift" "$probe_dir/ProbeApp.swift" "$probe_dir/main.swift" \
  -o "$output/Contents/MacOS/HandyBLEProbe"
cat > "$output/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>jp.hyoutan.handybleprobe</string>
<key>CFBundleName</key><string>Handy BLE Probe</string>
<key>CFBundleExecutable</key><string>HandyBLEProbe</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>0.1.0</string>
<key>LSMinimumSystemVersion</key><string>12.0</string>
<key>NSHighResolutionCapable</key><true/>
<key>NSBluetoothAlwaysUsageDescription</key><string>明示した操作でHandy 2を検索し、停止応答・位置を確認する限定診断にBluetoothを使用します。</string>
<key>NSBluetoothPeripheralUsageDescription</key><string>Handy 2の限定診断で、明示した接続と停止・位置の確認にBluetoothを使用します。</string>
</dict></plist>
PLIST
if [ -n "${HANDY_PROBE_DEVICE_NAME:-}" ]; then
  /usr/bin/plutil -insert HandyProbeExpectedAdvertisementName -string "$HANDY_PROBE_DEVICE_NAME" "$output/Contents/Info.plist"
fi
# Movement stays disabled unless the reviewed follow-up build explicitly opts in.
if [ "${HANDY_PROBE_ENABLE_MOVE:-0}" = "1" ]; then
  /usr/bin/plutil -insert HandyProbeMoveEnabled -bool true "$output/Contents/Info.plist"
fi
/usr/bin/codesign --force --sign - --identifier jp.hyoutan.handybleprobe "$output"
"$output/Contents/MacOS/HandyBLEProbe" --self-test
printf '%s\n' "$output"
