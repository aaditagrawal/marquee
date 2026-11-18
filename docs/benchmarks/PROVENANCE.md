# Benchmark provenance

This file maps every measurement campaign in the private development history to
the APK it measured, how that build was compiled on the TV, where its sanitized
numbers live in [`data/`](data/), and whether the result was accepted. The same
content is in machine-readable form in [`provenance.json`](provenance.json).

**None of these campaigns measured the public repository's build.** The record
ends on 2026-10-13 at 09:03 IST, when the owner stopped all TV use. A later
release (2026-11-08, Skip Credits padding fix, SHA-256 `214e95eb…bfaa47c9`) was
installed on the TV, but no benchmark of it exists: its hash appears nowhere in
the benchmark tree.

## Common conditions

| Item | Value |
|---|---|
| Device | Sony BRAVIA VU1, Android 11 (API 30), 32-bit `armeabi-v7a` userspace |
| SoC / GPU | Realtek RTD287O, 4× Cortex-A55; Mali-G31 (GLES 3.2) |
| Memory | 2 GiB RAM; Dalvik growth limit 192 MiB (512 MiB with `largeHeap`) |
| Display | one mode only, 1920×1080 at 60 Hz, density 320 |
| Network | TV on household Wi-Fi to a LAN Jellyfin server, with normal household traffic. Not isolated. |
| Server | Jellyfin 10.11.11 |
| Build identity | every artifact reports `versionCode 1` / `versionName 0.1.0`, so the SHA-256 is the only build identity |
| Compilation | full `speed` AOT, verified with `dumpsys package` before captures, unless a row says otherwise |
| Cache state | process-cold launches keep app data, the saved login, the disk image cache and the OS page cache; frame captures warm the path once first |
| Times | IST (UTC+05:30), as recorded by the capturing host |

How builds were attributed: frame captures from r5 onwards store the installed
APK's SHA-256, which the harness checked before and after each capture. Player
and QA cases store it in each case result. The r4 captures were matched by the
package's `lastUpdateTime` to install logs. When a log lacks a hash, the build is
listed as **unattributed**. All 69 surviving artifact files (60 distinct builds) were hashed directly
([`data/apk_artifacts.csv`](data/apk_artifacts.csv)).

## Campaigns, oldest first

Origins are paths in the private development tree, shown only so the chronology
can be followed. Folders named after library titles have been aliased (for
example `mediaA`).

| Campaign | Date (IST) | APK SHA-256 | Build; compilation | Measures | Samples | Dataset | Original evidence | Status |
|---|---|---|---|---|---|---|---|---|
| `renderer-spike` | 2026-10-12 (before app work) | separate synthetic spike APKs (Compose / Views / GLES3); hashes not carried into this record | release, R8; speed (verified) | synthetic focus-grid scene: HWUI frame time, SurfaceFlinger focus cadence, CPU, PSS, cold start | 2-3 runs per approach | data/renderer_spike.json | `spikes/results/aggregate.json` | **historical (not the Marquee app)** |
| `r4-compiler-modes` | 2026-10-12 13:11-13:13 | 67142546ffa4e25bc5807e69580afc2c700dcbc9f04f7b95221fe7f2accab2bc | release, R8, bundled baseline profile; verify / speed-profile / speed (switched with cmd package compile; verified per mode) | cold start: am start -W TotalTime, MARQUEE_HOME_READY | 4 cold launches per mode | data/startup_samples.csv (campaign r4-compiler-modes) | `bench/evidence/perf/entry-{verify,profile,speed}.json` | **accepted (historical; entry UI build)** |
| `r4-frames` | 2026-10-12 13:28-14:27 | unattributed (installs 13:23, 13:29, 13:42, 14:02; install logs lack a hash)<br>618e6561e5029aefb62923244c59b9c1c3928ff025af50ee4ed8b990679e3738 (installed 14:13:43) | release, R8; speed (verified by installer) | held-D-pad HWUI frame times (12 s path), bitmap-mode A/B | 1 run per screen; A/B 1-2 runs per variant | data/frame_runs.csv + frame_samples.csv (campaign r4) | `bench/evidence/perf/r4-*-frames/, bench/evidence/r4-home/bitmap-*` | **historical / control; one misrouted (search), one failed (app crash)** |
| `r4-largeheap-ab` | 2026-10-12 13:38-13:43 | unattributed (largeHeap=true: the installed release, pulled)<br>4a1574ddb743daeb1925b49b98775db9bcb011955b48753dd27de418f7ab344b (largeHeap=false: same DEX, manifest flag only, re-signed) | release, R8; speed | one cold launch + PSS after a short browse, per variant | 1 per variant | data/startup_samples.csv, data/memory_samples.csv (r4-largeheap-ab) | `bench/evidence/perf/heap-*` | **control (single samples)** |
| `r4-final-startup` | 2026-10-12 14:03 and 14:10 | unattributed (installed 14:02:30) | release, R8; speed | cold/warm start | 8 cold, 1 warm | data/startup_samples.csv (r4-final) | `bench/evidence/perf/final-speed.json, bench/evidence/r4-home/startup.json` | **historical** |
| `r4-player-memory` | 2026-10-12 13:43-13:47 | unattributed | release, R8; speed | PSS 6 s and 25 s into playback (media-A, media-B) | 1 per case | data/memory_samples.csv (r4-player) | `bench/evidence/perf/release-*-meminfo-*.txt` | **historical** |
| `qa-perf-cycle-01` | 2026-10-12 13:25-14:26 | unattributed (release installed 13:23:23) | release; speed | cold/warm HOME_READY | 1 cold, 1 warm | data/startup_samples.csv | `bench/evidence/qa-perf-cycle-01/startup/` | **historical** |
| `early-qa-cycles` | 2026-10-12 13:00-16:43 | mixed debug and release builds, mostly unattributed | mixed; mixed | functional QA screenshots/logs (qa-cycle-01..08, qa-r5*, r5-*, qa-review-*, screens-orch, player-orch) | - | not extracted | `bench/evidence/qa-*, r5-*` | **not used: functional QA, debug builds, superseded by r9+** |
| `r5-hook` | 2026-10-12 14:47 | 93352bdfa92ad9e3a29350a318f5f2b111bcaa56466e0a3e044645c6b2cb5e28 | release; speed | Movies held-D-pad, 2 runs | 2 runs | data/frame_runs.csv (r5-hook-movies-on) | `bench/perf/results/r5-hook-movies-on/` | **historical** |
| `r9-baseline` | 2026-10-12 23:15-23:33 | 5b6b9df05c213eadf0882374e4d8c58a311eb430ad9c8635f9b41e51c5b9ba52 | release, R8; speed (verified per capture) | 5-surface held-D-pad frames; QA cold/warm start; player start baseline | 1 run per surface; 3 cold + 3 warm | frame_runs (r9-baseline*, r9-movies-no-prefetch), startup (qa-r9-hdrfix), playback (r9p/before-*) | `bench/perf/results/r9-baseline-*, bench/evidence/qa-r9-hdrfix/, bench/offline/r9-player/before-*` | **accepted baseline** |
| `r9-qa` | 2026-10-12 23:35 - 2026-10-13 00:29 | 4519f3d3ea7f251b80ff5523433c2abd801b349beec06def0c41e0dff6c5d618 | release, R8; speed | 5-surface frames; QA startup; QA playback matrix; r9 player acceptance | 1 run per surface; 3+3 starts; 1 sample per playback action | frame_runs (r9-anchor-*), startup (qa-r9-integrated), playback (qa-r9/*, r9p/after-*) | `bench/perf/results/r9-anchor-*, bench/evidence/qa-r9-integrated/, bench/offline/r9-player/after-*` | **accepted (this is the build docs/QA_REPORT.md describes; superseded by later rounds)** |
| `r9-ab` | 2026-10-12 23:23 and 2026-10-13 00:36-00:47 | 5b6b9df0... (prefetch A/B)<br>eec219270a3b4ece01fb94df6c75fd6bcc3cf6738aef5a6a61f1907f1e2ccce9 (grid and parent-anchor A/B) | release, R8; speed | same-APK runtime-flag A/B | 1 run per variant | frame_runs (r9-pool-*, r9-parent-*, r9-movies-no-prefetch) | `bench/perf/results/r9-pool-*, r9-parent-*` | **control (fast-reset and timed-out pairs excluded)** |
| `r9-final + r10-player` | 2026-10-13 00:55-01:16 | f0370893dd332b6ec3e05b54c23553a3451496fb31e35cd276f14e1b6f6a1ec4 | release, R8; speed | 5-surface frames; r10 player acceptance (start/resume/seek, PGS restarts) | 1 run per surface; 1 per playback action | frame_runs (r9-final-*), playback (r10p/final-*) | `bench/perf/results/r9-final-*, bench/offline/r10-player/final-*` | **accepted (superseded)** |
| `r10-ui` | 2026-10-13 01:19-02:31 | f0370893... (before)<br>55cc9857c1bdae1696af35502987863981fd89a809f945fbe9732ff2644a7657 (intermediate)<br>3053e78e122ca7db116aa28429646b4543d82cc7c58d2debf28d373031c5eeb3 (final) | release, R8; speed | 5-surface frames before/after, paced Home reset | 1 run per surface | frame_runs (r10-*) | `bench/perf/results/r10-*` | **accepted (final 3053e78e); rushed Home and screenshot-timeout captures excluded** |
| `r11-player` | 2026-10-13 01:51-02:04 | 55cc9857... | release, R8; speed | exact-position resume/seek | 1 per action | playback (r11p/*) | `bench/offline/r11-player/` | **accepted (superseded by r12 player)** |
| `r12-player` | 2026-10-13 02:14-02:53 | 3053e78e... / 8461dbe99a17faa8ced2cd659af6681cb00de3ddc3026223cbfa5c8d4004f868 / 19dca9d2879ff23f83d515c4aea5f406a485b4db7bea71179c4c33b570a4f78e (intermediate)<br>d2d2e7528749bcb8ee05bf0d29b31dc5205bd952d2f2555876aa9c416e0f6fda (final) | release, R8; speed | keyframe-snapped resume/seek | 1 per action | playback (r12p/*) | `bench/offline/r12-player/` | **accepted (d2d2e752); last accepted playback timing in the record** |
| `r11-ui` | 2026-10-13 02:55-06:00 | 3053e78e... (before)<br>experiments incl. c4aff44c, a456661b, cee0b85b (invalid: blank native cards)<br>89ac29d5f469fa77053ac8196d2ce3cebbc057c7873b952d95895617e242c3df (accepted)<br>cfd54fa0863b51a51623bc6a11224ad2d5c970ccc9f4050971ed5c2a0a97933b (final, repeats)<br>profile builds 36980ee9, a6c400a9, 2ab1cd76, d70c2f18, 67501449, 44c7a637 (traces) | release, R8; profile = release-inherited, non-debuggable, Compose tracing; speed | 5-surface frames; Perfetto/atrace attribution | 1-4 runs per surface | frame_runs (r11-*) | `bench/perf/results/r11-*` | **accepted (89ac29d5, cfd54fa0); blank-render runs invalid; traces diagnostic only** |
| `r12-ui` | 2026-10-13 07:21-07:57 | 7098d3c607d4db29731eb85e77d6e1e1f86f8004517a658c8008702cf19b0bba (candidate)<br>f266c76a47b5c93c493f8ce9eaec69293edd1989ad4c40266fc805311fe5b2c0 (final)<br>profile 842bdbc5 (trace) | release, R8; speed | Home x3, Movies x2, Episodes, Search; OSD attempted | Home 3 runs, Movies 2, others 1 | frame_runs (r12-*) | `bench/perf/results/r12-*` | **accepted; OSD failed (decoder failure before first frame)** |
| `r13-player` | 2026-10-13 08:02-08:27 | f266c76a... (reproduction)<br>debug fixture builds (unattributed)<br>414b94087dc8ae2544a7456a9f34b05c6e3645d6b9ad665a6002678839c33aa8<br>868ef47ba81eeae0fa055aad2a5b065b1e931904d9dfd4544d3365d332e5f1c7 (final) | release + debug probes; speed (release) | playback recovery ladder | - | playback (r12p/r13-baseline, r13p/*) | `bench/offline/r13-player/` | **failed: no first frame; hardware decoder resource acquisition failed; TV rebooted 08:44 by the owner** |
| `r14-player` | 2026-10-13 08:53-09:02 | a1e3841bf265cf128ba2e0aef4f80aa5638854305452f091af9e37ed2a5aab23 | release, R8; speed | post-reboot start/seek | 2 valid single samples, 1 misrouted, 6 failed | playback (r14p/*) | `bench/offline/r14-player/` | **partial: round cancelled by the owner at 09:03 (all TV use stopped)** |
| `host-nav-budget` | 2026-10-13 01:55 | n/a (JVM, offline fixtures) | Robolectric SDK 30; n/a | app key-handler time per D-pad event (host proxy) | 10-15 events per screen | data/host_proxies.json | `bench/offline/nav-budget.json` | **host proxy only** |
| `host-remux-latency` | 2026-10-12 17:08 | n/a (server + host ffprobe) | -; - | server time to first decodable frame of a remuxed 4K stream | 1-8 per variant | data/host_proxies.json | `bench/offline/remux-latency.json` | **server-side proxy, historical (before the r10-r12 player changes)** |
| `skip-credits-2026-11-08` | 2026-11-08 | 214e95ebf58d6db1b15e6b5bdc2d19af2648863e6aeb79a0d5fed23bbfaa47c9 | release; unknown | none | 0 | - | `hash does not occur anywhere in the benchmark tree` | **UNMEASURED** |
| `marquee-public-fork` | - | none | -; - | none | 0 | - | `-` | **UNMEASURED: nothing in this record measures the public repository build** |

## Final evidence per metric

"Latest" means the most recent accepted evidence in the record, not evidence of
the current code.

| Metric | Latest accepted evidence | APK | Notes |
|---|---|---|---|
| Held-D-pad frames: Home, Movies, Episodes, Search | `r12-final` (r12-clip1..3-home, r12-clip1..2-movies, r12-finalrest-series/search) | `f266c76a` | Home pooled over 3 runs, Movies over 2, the others are single runs |
| Held-D-pad frames: player OSD | `r11-final2-osd` | `cfd54fa0` | OSD could not be measured on `7098d3c6` or `f266c76a`: playback failed to start |
| Cold/warm start, dedicated protocol | `qa-r9-integrated` | `4519f3d3` | 3 cold + 3 warm. Later builds only have harness-launch samples (below). |
| Cold start, harness launches | launches inside the r12 frame captures | `f266c76a` | 8 `am start` samples, 6 with HOME_READY. Navigation input begins ~1.5 s after launch. |
| Compilation-mode effect on start | `r4-compiler-modes` | `67142546` | entry UI build only |
| Playback start/resume/seek | `r12-player` accepted cases | `d2d2e752` | one sample per action. The r14 samples (`a1e3841b`) are partial, and one of them is misrouted. |
| Memory (PSS) | `meminfo` after each r12 final capture | `f266c76a` | single snapshots after browsing; no peak or long-duration data |
| largeHeap A/B | `r4-largeheap-ab` | unattributed / `4a1574dd` | one launch per variant |
| APK size | artifact files | all | release 3,755,006 → 3,804,158 bytes over r9 → r14 |

## Status key

| Status | Meaning |
|---|---|
| accepted | A complete capture the original round accepted for its APK |
| control | One APK with a runtime flag toggled; one side of a same-APK A/B pair |
| baseline | The "before" sample for a later accepted build |
| superseded | A complete capture of an intermediate candidate that was later replaced |
| historical | An early protocol or an unattributed build, kept for context only |
| partial | Valid single samples from a round that was cancelled before acceptance |
| auxiliary | Valid but off-protocol, for example a preview-tile probe or a CPU-contention test |
| incidental | A launch measured as a side effect of a frame capture |
| diagnostic | A tracing session; tracing overhead makes its timing incomparable |
| excluded | Removed by the original notes, for example an exploratory path or an attempt that ended early |
| incomplete | No completion marker (timeout or screenshot failure) |
| failed | No first frame, an app crash, or a failed acceptance assertion |
| misrouted | Captured on the wrong screen or with the wrong media |
| invalid | Captured, but the screen was not drawn correctly (blank native cards), so the timing is meaningless |
| proof-only | An idle-state snapshot with no timing |
