#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Print a read-only playback-capability report for one Android TV device, for bug reports and for
# extending Marquee's capability detection. It needs no Jellyfin server and contains no credentials.
#
# Usage:
#   tools/device-capabilities.sh --serial <adb-serial> [--out <file>]
#
# Collects: device identity, ABIs, page size, RAM and heap limits, display modes and HDR capabilities,
# and the platform's decoder list (names, types, max sizes, profile/level lines) from MediaCodec XML
# and `dumpsys media.player`. Everything is read-only: no apps are started, nothing is installed,
# and no settings are changed.
#
# Review the output before sharing it. It contains no account data, but it does contain the device's
# model and firmware build fingerprint.
set -euo pipefail

usage() { sed -n '3,14p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-2}"; }
serial="" out=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial) [[ $# -ge 2 ]] || usage; serial=$2; shift 2 ;;
    --out) [[ $# -ge 2 ]] || usage; out=$2; shift 2 ;;
    -h|--help) usage 0 ;;
    *) echo "Unknown argument: $1" >&2; usage ;;
  esac
done
[[ -n "$serial" ]] || { echo "--serial is required." >&2; usage; }
command -v adb >/dev/null || { echo "adb is not on PATH (Android SDK platform-tools)." >&2; exit 1; }
adb_dev=(adb -s "$serial")
[[ "$("${adb_dev[@]}" get-state 2>/dev/null || true)" == device ]] || { echo "Device $serial is not available." >&2; exit 1; }

dsh() { "${adb_dev[@]}" shell "$@" 2>/dev/null | tr -d '\r'; }
report() {
  echo "# Marquee device capability report"
  echo "generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo
  echo "## Identity"
  for p in ro.product.manufacturer ro.product.model ro.product.device ro.board.platform ro.soc.model \
           ro.build.version.release ro.build.version.sdk ro.build.fingerprint ro.product.cpu.abilist \
           dalvik.vm.heapgrowthlimit dalvik.vm.heapsize ro.config.low_ram; do
    printf '%-28s %s\n' "$p" "$(dsh getprop "$p")"
  done
  printf '%-28s %s\n' "page size" "$(dsh getconf PAGE_SIZE)"
  printf '%-28s %s\n' "MemTotal" "$(dsh grep MemTotal /proc/meminfo | awk '{print $2, $3}')"
  echo
  echo "## Display (modes and HDR)"
  dsh dumpsys display | grep -E 'mSupportedModes|mActiveModeId|HdrCapabilities|mSupportedHdrTypes|density' | sort -u | head -20
  echo
  echo "## Decoders (dumpsys media.player)"
  dsh dumpsys media.player | sed -n '/Decoder infos/,/Encoder infos/p' | grep -vE '^\s*$' | head -400
  echo
  echo "## Codec XML (/vendor/etc, /odm/etc, /system/etc)"
  for f in $(dsh ls /vendor/etc/media_codecs*.xml /odm/etc/media_codecs*.xml /system/etc/media_codecs*.xml 2>/dev/null); do
    echo "### $f"
    dsh cat "$f" | grep -E '<MediaCodec |<Type |<Limit |<Feature ' | sed 's/^[[:space:]]*//' | head -300
  done
  echo
  echo "## Audio outputs"
  dsh dumpsys media.audio_policy | grep -E 'Encoded surround|formats|- Output|Devices' | head -60
}
if [[ -n "$out" ]]; then report > "$out"; echo "Wrote $out"; else report; fi
