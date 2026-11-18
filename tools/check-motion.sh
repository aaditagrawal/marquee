#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Motion gate: fails if app sources use Compose animation APIs. Marquee's UI is "instant";
# only framework spinners, work-driven progress bars and the Skip Intro countdown may move.
# Policy: docs/architecture/MOTION_ALLOWLIST.md. There are no exceptions; adding one means
# listing the exact expression in that document and matching it narrowly here.
#
# Usage: tools/check-motion.sh            # scans app/src/main (the full gate)
#        tools/check-motion.sh <dir>...   # scans only the given directories
set -euo pipefail
cd "$(dirname "$0")/.."
pattern='animate[[:alnum:]_]*AsState|tween\(|animateScroll|AnimatedVisibility|Crossfade|Animatable\(|animateTo\('
if [[ $# -eq 0 ]]; then roots=(app/src/main); else roots=("$@"); fi
status=0
matches=$(grep -rnE --include='*.kt' --include='*.java' --include='*.xml' "$pattern" "${roots[@]}") || status=$?
if [[ $status -gt 1 ]]; then exit "$status"; fi
if [[ -n "$matches" ]]; then
  printf '%s\n' 'Motion gate failed. Only spinners, progress bars and the Skip Intro countdown may move;' \
    'see docs/architecture/MOTION_ALLOWLIST.md.' "$matches" >&2
  exit 1
fi
echo 'Motion gate PASS.'
