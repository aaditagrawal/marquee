#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Install a Marquee APK on one explicitly chosen Android device over ADB and verify the installed bytes.
#
# Usage:
#   tools/install-apk.sh --serial <adb-serial> --apk <path/to/app.apk> [--compile-speed] [--launch]
#
#   --serial         Required. The device as listed by `adb devices` (USB serial or host:port).
#                    The script never guesses a device and never acts on more than one.
#   --apk            Required. The APK to install (signed; Android refuses unsigned APKs).
#   --compile-speed  Optional. After installing, ask ART to compile the app fully ahead of time
#                    (`cmd package compile -m speed -f`). Uses CPU on the device for a while.
#                    Do not run it while something is playing.
#   --launch         Optional. Start Marquee after installing.
#
# The script prints the device's manufacturer, model, Android version and ABI list, refuses to install
# an APK whose native libraries do not match the device's ABIs, installs with `adb install -r`,
# compares the installed APK's SHA-256 with the local file, and reports whether the package is debuggable.
# It changes no device settings, does not uninstall anything, and does not restart the ADB server.
set -euo pipefail

usage() { sed -n '3,14p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-2}"; }

serial="" apk="" compile=false launch=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial) [[ $# -ge 2 ]] || usage; serial=$2; shift 2 ;;
    --apk) [[ $# -ge 2 ]] || usage; apk=$2; shift 2 ;;
    --compile-speed) compile=true; shift ;;
    --launch) launch=true; shift ;;
    -h|--help) usage 0 ;;
    *) echo "Unknown argument: $1" >&2; usage ;;
  esac
done
[[ -n "$serial" && -n "$apk" ]] || { echo "Both --serial and --apk are required." >&2; usage; }
[[ -f "$apk" ]] || { echo "APK not found: $apk" >&2; exit 1; }
command -v adb >/dev/null || { echo "adb is not on PATH (Android SDK platform-tools)." >&2; exit 1; }

adb_dev=(adb -s "$serial")
state=$("${adb_dev[@]}" get-state 2>/dev/null || true)
[[ "$state" == device ]] || { echo "Device $serial is not available (state: ${state:-none}). Is it authorised?" >&2; exit 1; }

prop() { "${adb_dev[@]}" shell getprop "$1" | tr -d '\r'; }
manufacturer=$(prop ro.product.manufacturer)
model=$(prop ro.product.model)
release=$(prop ro.build.version.release)
sdk=$(prop ro.build.version.sdk)
abis=$(prop ro.product.cpu.abilist)
echo "Device: $manufacturer $model, Android $release (API $sdk), ABIs: $abis"

# Package name and native ABIs inside the APK.
pkg=$(command -v aapt2 >/dev/null \
      && aapt2 dump packagename "$apk" 2>/dev/null || true)
apk_abis=$(unzip -Z1 "$apk" 2>/dev/null | sed -n 's#^lib/\([^/]*\)/.*\.so$#\1#p' | sort -u | tr '\n' ' ')
echo "APK:    $(basename "$apk") ${pkg:+(package $pkg) }native ABIs: ${apk_abis:-none}"
if [[ -n "$apk_abis" ]]; then
  ok=false
  for a in $apk_abis; do [[ ",$abis," == *",$a,"* ]] && ok=true; done
  $ok || { echo "None of the APK's ABIs ($apk_abis) is supported by this device ($abis)." >&2; exit 1; }
fi
if [[ -z "$pkg" ]]; then
  case "$(basename "$apk")" in
    *debug*) pkg=com.mav.marquee.debug ;;
    *) pkg=com.mav.marquee ;;
  esac
  echo "(aapt2 not found; assuming package $pkg from the file name)"
fi

"${adb_dev[@]}" install -r "$apk"

expected=$(sha256sum "$apk" | awk '{print $1}')
path=$("${adb_dev[@]}" shell pm path "$pkg" | tr -d '\r' | sed -n 's/^package://p' | grep -m1 'base.apk$' || true)
[[ -n "$path" ]] || { echo "Installed, but could not locate $pkg on the device." >&2; exit 1; }
actual=$("${adb_dev[@]}" shell sha256sum "$path" 2>/dev/null | awk '{print $1}' || true)
if [[ -z "$actual" ]]; then
  echo "Installed. The device has no sha256sum, so the installed bytes were not verified."
elif [[ "$actual" == "$expected" ]]; then
  echo "Installed and verified: SHA-256 $actual"
else
  echo "Installed APK SHA-256 ($actual) does not match the local file ($expected)." >&2; exit 1
fi

if "${adb_dev[@]}" shell dumpsys package "$pkg" | grep -q 'flags=.*DEBUGGABLE'; then
  echo "Note: $pkg is a debuggable build. Use a release build for any performance comparison."
fi

if $compile; then
  echo "Compiling $pkg ahead of time (speed)…"
  result=$("${adb_dev[@]}" shell cmd package compile -m speed -f "$pkg" | tr -d '\r')
  echo "$result"
  [[ "$result" == *Success* ]] || { echo "Compilation did not report success." >&2; exit 1; }
fi

if $launch; then
  "${adb_dev[@]}" shell monkey -p "$pkg" -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1 \
    || "${adb_dev[@]}" shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null
  echo "Launched $pkg."
fi
