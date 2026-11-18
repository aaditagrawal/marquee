# Benchmark tooling

These tools measure Marquee (or any Android TV app) on your own TV and Jellyfin
server, and regenerate the published record in
[`docs/benchmarks/`](../docs/benchmarks/README.md).

They were ported from the private harness that produced the reference
measurements. That harness had hardcoded device addresses, private paths, a
shared TV lock and the owner's library titles; all of these have been removed,
and every device-specific setting is now a command-line option. **The device
paths have not been re-run on a TV since porting.** Everything that can run
offline has unit tests: framestats parsing, statistics, held-input generation,
the device-safety guards (with a fake `adb`), the log-marker parser and the
dataset checks.

Requirements: Python 3.10+ via [uv](https://docs.astral.sh/uv/), standard
library only (pytest for the tests), plus Android platform-tools (`adb`) for the
device tools.

## Which build to measure

Public `release` APKs remove `Log.v/d/i` calls with R8 (`app/proguard-release-logs.pro`) and are not
shell-profileable. The markers that `capture_startup.py` and `observe_playback.py` read
(`MARQUEE_STARTUP`, `MARQUEE_HOME_READY`, and the player's `PREPARE`, `TTFF`, `AUDIO_TTFF`,
`SEEK first_frame_ms`, `SYNC_FRAME`/`EXACT_FRAME` lines) are logged at info level, so they exist only in
`debug` and `profile` builds. Measure the **`profile`** build:

```bash
./gradlew :app:assembleProfile     # app/build/outputs/apk/profile/app-profile.apk
```

`profile` inherits the release configuration (R8 minify and resource shrinking, non-debuggable,
`armeabi-v7a` + `arm64-v8a`, package `com.mav.marquee`), keeps the log markers, adds
`<profileable android:shell="true">`, and bundles Compose runtime tracing and the Perfetto tracing
library (about 11.6 MB instead of about 5.3 MB). It is signed with the build machine's Android
debug key, so it cannot replace a release install signed with another key; uninstall first. Never
distribute it.

`capture_frames.py` only reads `dumpsys gfxinfo` and works on any build, including `release`.
The `hardwareBitmaps`, `lazyPrefetch`, `captionLayouts`, `nestedPrefetch`, `parentAnchor` and
`contextBackdrop` launch extras of `MainActivity` are read in every build type; only the log lines that
confirm them (`MARQUEE_GRID_PREFETCH`, `MARQUEE_DETAIL_PREFETCH`) are release-stripped.

**Comparability with the published record.** The reference measurements in `docs/benchmarks/` were
taken on private *release* builds from before this change, which still logged these markers and
shipped `armeabi-v7a` only. Profile builds add the tracing libraries, the profileable flag and the
64-bit libraries, so profile-build numbers are close to, but not strictly like-for-like with, that
record. Say which build type you measured.

## Offline commands (no device)

```bash
uv run --no-project --with pytest pytest bench/     # unit tests
uv run --no-project python bench/summarize.py       # recompute docs/benchmarks/data/summary.json
uv run --no-project python bench/make_charts.py     # regenerate docs/benchmarks/charts/*.svg
uv run --no-project python bench/validate_data.py   # schema, cross-file, privacy and freshness checks
```

## Safety rules built into the device tools

* **No default device.** Every tool requires `--serial`. All `adb` calls use
  `-s <serial>`, plus `-P <port>` if you pass `--adb-server-port`.
* **Input goes only to the app under test.** Before any key event or HID
  replay, the tool checks that `--package` owns the resumed activity. Only the
  current resumed-activity records of `dumpsys activity activities` count
  (`mResumedActivity:`, `ResumedActivity:`, `topResumedActivity=`; history
  records such as `mLastResumedActivity` never do). If anything else is in the
  foreground, or no such record is found, it stops and sends nothing.
* **Captures stay local.** Write `--out` under `results/` (gitignored, like
  `bench/results/` and `bench/evidence/`). Capture context includes the device's
  build fingerprint; review before publishing anything from it.
* **Mostly read-only.** The tools read `getprop`, `dumpsys package`, `pm path`
  plus `sha256sum`, `dumpsys gfxinfo` and `dumpsys meminfo`. The exceptions:
  `gfxinfo reset`, which clears only the app's own frame buffer; `am force-stop`
  for the target package in cold-start mode; and temporary files under
  `/data/local/tmp`, which are removed afterwards.
* **No credentials.** The tools never call the Jellyfin API and need no token.
  `observe_playback.py` stores no item IDs, URLs or titles.
* The tools do not install builds, compile packages, reboot, change settings or
  restart the adb server. You do those steps yourself, deliberately, and record
  them.

## What to record with every result

A number without these items cannot be compared with anything. The tools
capture the first four automatically in `context.json`.

1. **Device identity:** manufacturer, model, Android version, ABI and platform.
2. **Exact APK:** the installed SHA-256. The version name is not enough; every
   development build here reported 0.1.0.
3. **Build type:** `profile` (R8, keeps the log markers; see above), `release` (frame captures only)
   or `debug`. Debug builds are not representative.
4. **ART compilation state** (`dumpsys package <pkg>`, the `[status=…]` lines):
   * `speed`: full AOT. All reference data used this state, set with
     `adb -s <serial> shell cmd package compile -m speed -f com.mav.marquee`.
   * `speed-profile`: the baseline profile has been applied. To reproduce the
     state after a Play-style install, install and then run
     `adb -s <serial> shell cmd package compile -m speed-profile -f com.mav.marquee`.
     Only the very first UI build was ever measured this way.
   * `verify`: no AOT, for a control run
     (`cmd package compile -m verify -f com.mav.marquee`). Android 11 cannot
     reset learned profiles from the shell, so `verify` does not reproduce a
     fresh install exactly.
5. **Cache state:** cold process with a warm disk cache (the reference default),
   or a truly cold start after clearing the app's cache, or a first login.
   Clearing data destroys the app's settings, so do it only on a test install.
6. **Network and server:** Wi-Fi or Ethernet, the server version, whether the
   server was transcoding for other clients, and what else was using the
   network.
7. **Run count.** Report every completed run, including the bad ones. Pool runs
   of the same build only when the protocol is identical.

## Protocols

### Held-key navigation (`capture_frames.py`)

1. Install the build you are measuring (`profile`, or `release` for frame captures only), compile
   with `speed`, and launch it. Navigate
   with the remote to the surface you want to measure. Put focus where the path
   should start, for example the first card of the first row.
2. Choose a path that stays inside that surface. `--reference-path` offers the
   paths used for the reference data (Home, Movies, Episodes, Search, OSD), but
   they depend on the layout, so check that yours does not scroll into a header
   or leave the screen. Use `--path "right:1700,down:760,…"` to define your own.
3. Run:
   ```bash
   uv run --no-project python bench/capture_frames.py --serial <adb-serial> \
       --surface home --reference-path home --runs 3 \
       --reset-path "left:2500,up:1500" --require-compile speed \
       --expect-sha256 <sha256-of-the-apk-you-installed> --out results/home-<build>
   ```
   The tool runs one unmeasured warm-up pass by default, then the measured
   runs. While the held input plays, it polls `dumpsys gfxinfo <pkg> framestats`
   about every 0.65 s. Frames are de-duplicated by `IntendedVsync`, and only
   valid frames inside the input window are kept.
4. Fewer than `--min-frames` frames (default 50), a process restart, or the app
   leaving the foreground makes the result incomplete. Discard it rather than
   rerunning until it looks good.

Report `FrameCompleted − IntendedVsync` with nearest-rank percentiles and the
share of frames over 16.7 ms, as `summarize_rows` does. Do not call this frame
rate, display latency or input latency. Tracing (atrace or Perfetto) adds
overhead, so never mix traced and untraced runs.

### Cold and warm start (`capture_startup.py`)

```bash
uv run --no-project python bench/capture_startup.py --serial <adb-serial> --mode cold --count 5 --out results/startup-cold.json
uv run --no-project python bench/capture_startup.py --serial <adb-serial> --mode warm --count 5 --out results/startup-warm.json
```

*Cold* means `am force-stop`, then `am start -W`. The tool records `TotalTime`
and `WaitTime`, then waits for the app's `MARQUEE_HOME_READY` log line, which
means the Home hero and visible first-row cards have been drawn, measured from
process start. *Warm* presses HOME after a foreground check, then brings the app
back. Warm HOME_READY is measured from Activity start and excludes the
bring-to-front time that `am start -W` includes. Do not send other input while
a start is being measured.

### Playback time to first frame and seeks (`observe_playback.py`)

```bash
uv run --no-project python bench/observe_playback.py --serial <adb-serial> --seconds 60 \
    --label "4K HEVC Main10 HDR10, E-AC3 5.1, MKV, direct play" --out results/play-4k.json
```

This tool is passive. Start it, then start, resume or seek playback with your
remote. It reads the player's log markers and records one entry per player
session:

* `since_activity_ms`: player Activity created → first rendered frame. Only the
  first session's value is a latency; later ones are cumulative.
* `prepare_to_first_frame_ms`: each prepare → first frame.
* `seeks_to_frame_ms`: seek committed → first new frame.
* `positions`: requested and landed position for each start or seek, with the
  policy (`exact` or keyframe `sync`).
* `method`, `mode` and `reasons`: how the server delivered the stream.

Label media by technical character, never by title. Each figure includes server
and network time. When comparing builds, use the same media, the same position,
the same activation method (D-pad, not touch) and similar server load.

## Files

| File | Purpose |
|---|---|
| `marquee_bench/framestats.py` | gfxinfo framestats parser and stage durations |
| `marquee_bench/stats.py` | nearest-rank percentiles, late-frame share, summaries |
| `marquee_bench/held_input.py` | virtual USB keyboard (`hid`) command generation, reference paths |
| `marquee_bench/device.py` | guarded `adb` wrapper: required serial, foreground check, identity and compile-state readers |
| `marquee_bench/markers.py` | parsers for Marquee's startup and player log markers |
| `capture_frames.py`, `capture_startup.py`, `observe_playback.py` | device CLIs (not re-run after porting) |
| `summarize.py`, `make_charts.py`, `validate_data.py` | regenerate and check `docs/benchmarks/` |
| `tests/` | offline tests with synthetic fixtures |

The capture tools depend on log markers in the app: `MARQUEE_STARTUP`,
`MARQUEE_HOME_READY`, and the player's `PREPARE`, `TTFF`, `AUDIO_TTFF`,
`SEEK first_frame_ms` and `SYNC_FRAME`/`EXACT_FRAME` lines, which only `debug` and `profile` builds
emit. If you change those log lines in the app, update `marquee_bench/markers.py` and its tests too;
`tests/test_markers.py` checks that the app sources still contain every prefix the parser needs.
