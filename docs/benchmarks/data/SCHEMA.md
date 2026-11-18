# Data schema

All files are UTF-8 CSV with a header row, or JSON. Empty CSV cells mean "not
available". Times are local to the capturing host (IST, UTC+05:30) in
`YYYY-MM-DDTHH:MM` format. `apk_sha256` is a full 64-hex SHA-256, or
`unattributed` when the original evidence does not identify the build. Run
`uv run --no-project python bench/validate_data.py` to check these files against
this schema, `bench/summarize.py` to recompute `summary.json`, and
`bench/make_charts.py` to redraw the charts.

## frame_runs.csv: one row per held-input capture run

| Column | Type / unit | Meaning |
|---|---|---|
| run_id | text | capture label (unique with `run`) |
| run | int | run number within the capture (1-based) |
| campaign | text | optimization round + phase, see PROVENANCE.md |
| captured_at | timestamp | capture start |
| apk_sha256 | sha / `unattributed` | installed APK, verified before and after the capture (r5+) |
| build | enum | `release` (R8, non-debuggable), `profile` (release-inherited, non-debuggable, Compose tracing on), `release (unattributed)` |
| aot | enum | ART compilation status; `speed` for every capture |
| surface | enum | `home`, `movies` (library grid), `series` (episodes rail), `search`, `osd` (player controls while playing), `episode-detail`, `movie-detail`, `series-detail` |
| protocol | text | held path + reset; `held-v1-<surface>-paced` is the r10–r12 protocol, `-r9reset` the r9 one; same held-input file per surface from r9 to r12 |
| mode | enum | `held` (timing), `trace` (tracing on: not comparable), `proof` (no frames) |
| n_frames | int | valid frames in the input window |
| p50_ms … max_ms | ms | nearest-rank percentiles of `FrameCompleted − IntendedVsync` |
| late_count, late_pct | int, % | frames with total > 16.7 ms |
| main_until_sync_*_ms | ms | `SyncQueued − IntendedVsync` (UI-thread portion) |
| renderthread_*_ms | ms | `SwapBuffers − IssueDrawCommandsStart` |
| gpu_ready_*_ms | ms | `GpuCompleted − IntendedVsync` |
| launch_total_ms | ms | `am start -W` TotalTime of the process-cold launch that began the capture |
| pss_total_kib | KiB | `dumpsys meminfo` TOTAL PSS at the end of the capture |
| status | enum | see the PROVENANCE.md status key |
| note | text | why a run is excluded/superseded, etc. |
| source | text | generic origin path in the private tree (titles aliased) |

## frame_samples.csv: one row per frame

`run_id`, `run`, `frame` (0-based), `t_ms` (IntendedVsync relative to the run's first frame, ms),
`total_ns` (FrameCompleted − IntendedVsync, **nanoseconds, exact**), `main_until_sync_us`,
`renderthread_us`, `gpu_ready_us` (µs, rounded), `dequeue_us`, `queue_us` (µs, buffer queue durations
reported by HWUI). Rows exist only for valid frames (Flags = 0, complete timestamps, inside the input
window, de-duplicated by IntendedVsync).

## startup_samples.csv: one row per launch

| Column | Meaning |
|---|---|
| campaign, captured_at, apk_sha256, build | as above |
| compile_state | `verify` (no AOT), `speed-profile`, `speed` |
| protocol | `dedicated-cold` (force-stop, then `am start -W`), `dedicated-warm` (app backgrounded, `am start -W` brings it back), `harness-cold` (the launch at the start of a frame capture; navigation keys begin ≈1.5 s after `am start` returns) |
| sample | 1-based index |
| launch_state | `COLD` / `HOT` as reported by `am start -W` |
| am_total_ms, am_wait_ms | `am start -W` TotalTime / WaitTime (time to the activity's first frame) |
| home_ready_ms | `MARQUEE_HOME_READY`: cold = process start → hero + visible first-row cards drawn; warm = Activity start → same (excludes bring-to-front) |
| stage_*_ms | `MARQUEE_STARTUP` stages, ms since process start: application, session-ready, snapshot-read, home-composed, disk-art-ready |
| cache | cache state (disk image cache + saved login retained) |
| status, note | see PROVENANCE.md |

## playback_samples.csv: one row per playback event

| Column | Meaning |
|---|---|
| case_id | `<round>/<case>`; titles replaced by media aliases |
| media | alias, described in `media_aliases.csv` |
| session | index of the player prepare within the Activity (0 = first) |
| event | `start` (from 0), `resume` (from a saved position), `seek-restart` (seek that re-opens the stream), `seek-in-place` (seek inside the open stream), `restart` (track change / fallback / next episode), `none` (case produced no markers) |
| play_method, mode, video_copy, server_reasons | how the server delivered the stream (DirectPlay, or Transcode with video copy and audio conversion, etc.) |
| since_activity_ms | player Activity `onCreate` → first rendered video frame; **only for session 0** (later sessions are cumulative and left empty) |
| prepare_to_first_frame_ms | player prepare → first rendered frame (includes server negotiation and buffering) |
| audio_since_activity_ms | player Activity → AudioTrack ready (session 0 only); not an acoustic measurement |
| seek_to_frame_ms | seek committed → first new frame (excludes the 650 ms scrub debounce) |
| requested_s, landing_delta_ms, position_policy | requested position (s), landed − requested (ms), and `exact` (r11, frame-exact) or `sync` (r12+, keyframe-snapped) |
| status, note | see PROVENANCE.md |

## memory_samples.csv

`sample_id`, `campaign`, `captured_at`, `apk_sha256`, `context` (when the snapshot was taken),
`large_heap`, then `dumpsys meminfo` App Summary PSS values in KiB: `total_pss_kib`, `java_heap_kib`,
`native_heap_kib`, `code_kib`, `stack_kib`, `graphics_kib` (this vendor build reports 0), `private_other_kib`,
`system_kib`; `status`. Single snapshots, not peaks.

## apk_artifacts.csv

`file` (artifact name), `apk_sha256`, `bytes`, `build` (`release` / `profile` / `debug`), `version_code`,
`version_name`, `debuggable`, `built_at` (from the file name).

## media_aliases.csv

`media` alias → neutral technical descriptor (container, codec, resolution, HDR format, audio, delivery).

## JSON files

* `summary.json`: generated by `bench/summarize.py` from the CSVs (pooled build sets, same-APK controls,
  cross-build comparisons, startup groups, accepted playback events, final memory and APK sizes). Do not edit.
* `host_proxies.json`: host-side proxies, **not TV measurements**. `nav_key_work` = JVM (Robolectric) time
  spent in app key handlers per D-pad event, before/after the r9/r10 navigation work; `remux_first_frame` =
  server + host time from PlaybackInfo to the first decodable frame of a remuxed 4K stream (seconds); `final=true` marks the serialized version-2 zero-position samples used in the report, `final=false` the exploratory runs.
* `renderer_spike.json`: synthetic Compose / Views / GLES3 scene comparison on the same TV (not the app).
