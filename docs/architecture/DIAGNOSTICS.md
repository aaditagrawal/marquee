# Diagnostics

## In-app statistics overlay

Turn it on with Settings → *Playback diagnostics*. During playback you can also
toggle it from the player's settings (gear) menu → *Stats*, or with the remote's
Info or blue key. The overlay shows:

- **Play method:** direct play, remux or transcode, and the fallback step if
  one was used.
- **Reasons:** Jellyfin's transcode reasons.
- **Source:** codec, profile/level, bit depth, HDR type and Dolby Vision
  profile.
- **Video:** the delivered format and frame rate, the decoder name and its
  output format.
- **Audio:** codec, channels, sample rate, and the audio path (decoded PCM,
  passthrough or software decoder).
- **Bitrate:** the source bitrate and the measured bandwidth estimate.
- **Buffer:** buffered seconds and megabytes.
- **Dropped frames:** decoder-dropped frames per minute of actual playing time
  (pauses and rebuffering excluded), next to rendered frames.
- **Audio underruns.**

The overlay never shows URLs, tokens or `Authorization` headers.

## Log markers

The player logs single-line markers under the tag `MarqueePlayer`
(`adb logcat -s MarqueePlayer`). They are logged at info level, so **release
builds strip them** (`app/proguard-release-logs.pro`); warnings and errors are
kept. The markers are available in debug builds and in the local, never
distributed `profile` build, which keeps release optimisations. Each marker is designed to contain
no media URL, no token and no `Authorization` header.

Build the profile variant with `./gradlew :app:assembleProfile`
(`app/build/outputs/apk/profile/app-profile.apk`). It is R8-minified, non-debuggable and
shell-profileable, keeps these markers and the startup markers `MARQUEE_STARTUP` /
`MARQUEE_HOME_READY` (tag `Marquee`), bundles Compose runtime tracing, and is signed with the build
machine's Android debug key, so it cannot update a release install signed with another key. Release
APKs are not shell-profileable. The benchmark tools in `bench/` expect the profile build; see
[bench/README.md](../../bench/README.md#which-build-to-measure).

| Marker | Meaning |
|---|---|
| `MARQUEE_TTFF` | Activity creation to first rendered frame, in ms. |
| `TTFF` | `prepare()` to first rendered frame, in ms. Compare like with like when you measure. |
| `AUDIO_TTFF`, `AUDIO_PROGRESS` | AudioTrack creation; then PCM frame counters advancing. Counters prove delivery to the audio mixer, not that sound came out of a speaker. |
| `PREPARE`, `LOAD`, `TRACK`, `SUBTITLE`, `PLAYBACK_STATE` | Lifecycle and track changes. |
| `SEEK`, `SYNC_START` | Seek and restart landing positions and latency. |
| `QUALITY`, `STATS`, `CADENCE` | Bitrate cap state, periodic statistics, and frame-rate cadence against the display's refresh rate. |
| `SEGMENTS`, `SEGMENT_BUTTON`, `SEGMENT_AUTO`, `UP_NEXT`, `NEXT_EPISODE` | Intro/credits segments and autoplay. |
| `ENCODING`, `CODEC_RELEASED`, `VERIFY_FALLBACK` | Server encode cleanup, codec release, and fallback-ladder steps. |
| `TUNNEL`, `PREWARM`, `CAPTION_GEOMETRY`, `CAPTION_INSET` | Tunneling decision, pre-warm hits, and subtitle placement. |

Before you paste logs into an issue, filter them to Marquee's process and check
them for anything that identifies your server or library, such as titles in
`TRACK` lines or your server's host name in exceptions.

## Device capability report

[`tools/device-capabilities.sh --serial <adb-serial>`](../../tools/device-capabilities.sh)
prints a read-only report of a device's decoders, display HDR modes, ABIs and
page size. The report helps explain why a stream was direct-played or
transcoded, and helps extend capability detection
([TV_CAPABILITIES.md](TV_CAPABILITIES.md)). It needs no Jellyfin server and
contains no account data.

## What the logs cannot tell you

- PCM counters advancing is not the same as audible sound.
- The first video frame is not the same as audio being ready.
- The display's overall HDR state does not prove the video layer is HDR.
- Android's dropped-frame counters do not capture every visible cadence
  error.
- Correlation is not cause. For example, a CPU competitor seen at the moment of
  an underrun did not necessarily cause it.

Benchmark methodology and its limits are in
[../benchmarks/README.md](../benchmarks/README.md).
