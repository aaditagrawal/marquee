# Playback decisions

This document explains how Marquee decides **how** to play an item, and why.
Most of these decisions were made and measured on one reference device: a Sony
BRAVIA VU1 (Realtek RTD287O, Android 11) with Jellyfin server 10.11.11 (see
[TV_CAPABILITIES.md](TV_CAPABILITIES.md)). Wherever a rule exists only because
of that device or that server version, it is labelled as such.

Code: `player/DeviceCapabilities.kt`, `PlaybackCapabilities.kt`,
`DeviceProfile.kt`, `BraviaVu1Profile.kt`, `StreamResolver.kt`,
`TranscodePolicy.kt`, `AudioSelection.kt`, `TrackConversionPolicy.kt`,
`MarqueeRenderersFactory.kt`, `PlayerActivity.kt`. The pure decision code is
covered by JVM tests (`StreamResolverTest`, `TranscodePolicyTest`,
`AudioSelectionTest`, …) using synthetic Jellyfin responses. See
[../dev/TESTING.md](../dev/TESTING.md).

## 1. Capabilities first

A Jellyfin client describes what it can play in a **DeviceProfile**. The server
then compares each media source with that profile and answers with direct play,
direct stream (remux) or transcode, plus the reasons.

- **Reference device.** A narrow quirk (`DeviceQuirks.isBraviaVu1`) checks for
  manufacturer "Sony", model "BRAVIA VU1" and a Realtek (`OMX.realtek.*`)
  decoder whenever the decoder list can be read. When it matches, Marquee uses
  the profile measured on that TV (`BraviaVu1Profile`). That profile is
  snapshot-tested and is not edited without re-measuring the TV.
- **Every other device.** `PlaybackCapabilities` probes once per process on a
  background thread. It reads `MediaCodecList` decoders (sizes, frame rates,
  bitrates, profiles, levels, bit depth, hardware or software) and the display's
  HDR types, and the profile is built from those values. Software video decoders
  are capped at 1080p30, because TV-class CPUs cannot decode more in real time.
- **Probe failure.** Marquee falls back to `DeviceCapabilities.SAFE`: 8-bit H.264
  up to 1080p30 level 4.1 SDR, with AAC/MP3 audio. The server transcodes
  everything else.

Generic detection is new in the public version and is **untested on real
non-reference hardware**. See [../RELEASE_CHECKLIST.md](../RELEASE_CHECKLIST.md).

## 2. The fallback ladder

Every play request goes down this ladder. It stops at the first step that
produces a frame.

| Step | `DeviceProfile.Mode` | What the server does | When it is used |
|---|---|---|---|
| 1 | `Direct` | Serves the original file as is (static HTTP with byte ranges). | The source's container, codecs, level, bit depth and HDR type are all supported, and the bitrate is under the cap. |
| 2 | `Remux` | Copies the video stream, converts only what is unsupported (usually audio), and repackages it. | The video can be decoded but the audio or container cannot. This keeps the original picture, including HDR. |
| 3 | `Hevc` | Encodes HEVC within the device's decoder limits. | The video codec itself cannot be decoded (for example AV1 or 10-bit H.264 on the reference TV), or step 2 failed. |
| 4 | `H264` | Encodes H.264, 8-bit, at most 1920x1080, SDR. | Last resort. |

**Recovery.** If a step fails, either with a decoder error or with a playback
clock that stops advancing, Marquee releases the current stream, asks the
server to stop that encode, and renegotiates one step down. A network stall can
also lower the bitrate. When the last step fails, the player stops with a
clear error and a Back action; it does not keep retrying.

**Why HEVC before H.264.** On the reference TV, H.264 is limited to 1920x1088
at level 4.2, whereas HEVC decodes up to 4096x2176 at level 5.1. Encoding to
HEVC keeps resolution. The server's own H.264 fallback once produced a 2048-wide
stream that the TV could not decode.

**One codec per transcoding profile.** Jellyfin 10.11 applies any unqualified
Width/Height condition to *every* codec listed in a transcoding profile. With
"hevc,h264" listed, H.264's 1080p limit would shrink an HEVC stream as well.
Marquee therefore negotiates one output codec at a time.

**H.264 sizes.** Full H.264 encodes request an explicit width rounded down to a
multiple of 16, an even height, no upscaling, at most 1920x1080, 8-bit High at
level ≤ 4.2. This is conservative policy for strict hardware decoders. It has
not been shown to fix a particular decoder failure.

## 3. Bitrate

- The **Auto** setting (the default) sends an explicit `Int.MAX_VALUE` as the
  streaming limit. If a client leaves the limit out, Jellyfin 10.11 assumes
  **8 Mbps**, which forces high-bitrate files to transcode for no reason.
- A positive cap chosen in Settings or in the player's Quality menu applies to
  direct play and to transcodes.
- The encoder bitrate for a transcode (up to 20 Mbps) is set in the stream
  request itself. It does not change the source's direct-play negotiation.
- Measured bandwidth is shown in diagnostics. It never sets negotiation caps.

## 4. HDR and Dolby Vision

Displays that support HDR10/HLG play those formats natively through the video
plane (`SurfaceView`).

| Source | Handling |
|---|---|
| HDR10, HLG | Direct play or video-copy remux. PQ/HLG colour metadata is preserved. |
| HDR10+ | Plays as HDR10. The decoder ignores the dynamic metadata. |
| Dolby Vision profile 8.x (HDR10/HLG/SDR base layer) | Played as HEVC using the base layer. Media3 maps profile 8 to the HEVC decoder when the display has no Dolby Vision. |
| Dolby Vision profile 7 (base layer compatible with HDR10) | `MarqueeRenderersFactory` maps it to HEVC and restores 10-bit PQ colour information, because Matroska DV extraction can leave it out. On HLS remux, Jellyfin strips the DV metadata and copies the video. |
| Dolby Vision profile 5 (no compatible base layer), or invalid DV without a base layer | Excluded from the profile, so the server tone-maps to SDR. |
| Native Dolby Vision | Only if the device has a `video/dolby-vision` decoder **and** the display reports Dolby Vision. The reference TV has neither. |

Jellyfin 10.11 labels profile 8.1 with HDR10+ as `DOVIWithHDR10Plus` and
profile 7 with an HDR10 base layer as `DOVIWithEL`. The profile must list these
range types; if it does not, the server transcodes these files for no reason.

**HDR remux uses the copy playlist.** Jellyfin 10.11's HLS master playlist
always adds SDR compatibility variants next to the HDR copy variant, with the
same bandwidth and codec declaration. A player that picks the "wrong" variant
silently gets an SDR encode. For HEVC PQ/HLG sources, Marquee therefore
requests the copy media playlist (`main.m3u8`) directly, with
`hevc-rangetype=SDR,HDR10,HLG`.

**HDR is not preserved by server encodes.** Jellyfin 10.11's encoder produces
8-bit HEVC Main (SDR) even when the request allows HDR. A client profile cannot
change that. When a full video encode is needed, the result is SDR, and the
player's start chip does not label it HDR.

## 5. Audio

**Default output is decoded PCM.** Compressed passthrough (AC-3/E-AC-3/DTS to an
AV receiver) is **off by default**. When the user turns it on in the player's
settings menu, Marquee only uses it if the active route is HDMI ARC/eARC, that
device lists the codec in its encodings, and Media3's `AudioCapabilities` accepts
the format. Connecting HDMI alone never enables it. A TV speaker that reports
"offload" support does not guarantee working sound. On the reference TV,
passthrough to the speakers produced a playback clock that never advanced.

**What the profile advertises.**

- The profile includes only formats that a **platform decoder** handles well.
- TrueHD/MLP and ALAC are **not** advertised for direct play, so the server
  converts them.
- DTS is advertised only as DTS core. DTS-HD MA/HRA, and DTS whose profile is
  unknown, are converted.
- FLAC is limited to ≤ 2 channels, ≤ 48 kHz and ≤ 24 bit. This is a
  conservative software-decoding budget, not a measured limit.

**Conversion targets.** Sources with six or more channels are converted to
E-AC-3 5.1, with HEVC video copied. Low-channel sources are converted to AAC.
Jellyfin 10.11's encoder allowlist leaves out E-AC-3. A mixed `eac3,ac3,aac`
request therefore yields AC-3, so Marquee requests a single codec, `eac3`. The
recovery profile uses `ac3,aac`, and full video encodes use AC-3 because of a
server error seen when it re-encodes E-AC-3.

**Track choice** (`AudioSelection`). A valid explicit choice is kept. If that
track is "heavy" (needs server conversion), playback renegotiates instead of
switching locally to software decoding. Automatic selection prefers a
compatible track in the preferred or default language and skips commentary.

**FFmpeg as last resort.** The Jellyfin `media3-ffmpeg-decoder` software audio
renderer is registered with `EXTENSION_RENDERER_MODE_ON`. It is used only after
the platform renderers, for formats no platform decoder accepts. On the
reference TV, software TrueHD 7.1 decoding on four Cortex-A55 cores was
associated with stalled playback. The cause was not isolated, so it is not
claimed as CPU saturation. That is why TrueHD is converted on the server rather
than decoded locally.

**Realtek quirk.** The reference TV's hardware audio decoder reports its
2-channel output limit as an input limit for AC-3/E-AC-3/DTS. A gated quirk
(`DeviceQuirks.realtekStereoInputLimit`) lets Media3 send 5.1 input to it to be
downmixed.

**Tunneling** is off. On the reference TV, tunneled playback with the Realtek
audio decoder ran its A/V-sync output but produced no sound. Software audio and
every transcode always disable tunneling.

## 6. Transports for audio-only conversion

When only the audio is converted (video copied), the client must know where the
stream starts. Marquee uses one of two transports:

- **Start and cold resume: progressive Matroska (`stream.mkv`)** with
  `StartTimeTicks`, `CopyTimestamps=true` and audio copy disabled. Matroska
  keeps HDR and DV metadata for the base-layer renderer. Media3 starts this
  stream at local time zero. `progressiveStartMs` records the source offset,
  and the extractor shifts video, audio and subtitle timestamps by it, so
  progress, scrubbing and reports use source time.
- **Seeks, track changes and recovery:** a fresh negotiation. Seeks first stop
  the old encode with a DELETE scoped to this device and play session.

Seeking favours a fast start over exact landing:

- Direct play seeks use `SeekParameters.CLOSEST_SYNC`. Direct resume picks the
  preceding Matroska cue before preparing, because Media3 does not apply seek
  tolerance to the initial position.
- Copied-stream resumes and restarts begin at a nearby keyframe (cue). FFmpeg
  needs a request about 250 ms past the cue, otherwise it copies from the
  previous GOP. A resume can therefore start a few seconds before the saved
  position.
- Exact decoding through the target is available only as an explicit fallback.
  It is off by default.

The first visible frame is held until the audio sink has data, so the picture
never starts silently. If audio is not ready within 1.5 s, the player enters
recovery.

## 7. Subtitles

- **Text** subtitles (SRT, ASS/SSA, WebVTT and similar) are delivered
  externally and rendered by Media3 in a full-screen subtitle view, 24 dp above
  the bottom.
- **Bitmap** subtitles (PGS, VobSub, DVB) are carried inside Matroska (DVB also
  in TS), and rendered in a view aligned to the picture.
- When the on-screen controls appear, both views move up without changing size.
- Image subtitles in a container that cannot carry them are burned in by the
  server, for the selected stream only. Burn-in is refused for HDR video, since
  it would force an SDR encode: the player explains why and turns subtitles off
  instead. Other conversions caused by a chosen track ask for confirmation
  first and offer text alternatives where they exist.
- Subtitle mode and language preferences follow Jellyfin's
  `SubtitlePlaybackMode` (Default / Always / OnlyForced / None / Smart).

## 8. Intro and credits

Media segments come from `GET /MediaSegments/{itemId}` (Jellyfin 10.10+).
Intro and Outro segments drive *Skip Intro* and *Skip Credits*. Each can be set
to `button` (the default; a focusable pill with a countdown), `auto`, or `off`.
"Auto" on credits can go straight to the next episode. If a server or item has no
segments, nothing is shown.

## 9. Start-up work and buffering

- **Pre-warm from Detail.** When a detail page opens, or Play gains focus,
  `PlaybackPrewarm` fetches the item and PlaybackInfo, resolves a stream URL that
  contains no credentials, warms the authenticated connection, and preloads a
  backdrop. For direct files it also prefetches the first 2 MiB and last
  512 KiB into a bounded RAM cache, which covers the header and the Matroska
  Cues index. At most two pre-warm entries are kept, for 30 seconds. They are
  validated against the account, preferences, resume position and every launch
  option. No transcode is started speculatively, except for one bounded case:
  an audio-only conversion of a focused Play button, with a 16 MB RAM FIFO and a
  45 s lifetime.
- **Load control:** a 250 ms start threshold, 15–50 s buffer and an 80 MiB
  allocator target, with no back buffer.
- **Threads:** playback and renderers share an urgent-audio-priority
  `HandlerThread`. Application calls and callbacks stay on the main looper.
- **Lifecycle:** `onStop` reports the last position, then stops and releases
  ExoPlayer. Only one player instance exists at a time (`ActivePlayerOwner`).
- **Hiding latency:** the player draws a cached backdrop and progress ring
  immediately, then a stream-mode chip ("Direct play · 4K HDR10"), then the
  first frame.

## 10. Reporting

`PlaybackReports` sends `Playing`, periodic `Progress` (position, pause state,
tracks, play method) and `Stopped` to `/Sessions/Playing*`. Metadata order is
kept independent of encoder cleanup, so a late `Stopped` from an earlier
session cannot clear a newer one. Encodes are cleaned up per device and play
session. Media URLs, `Authorization` headers and tokens are never logged or
persisted.

## Known limitations

- HDR-preserving server transcodes are not possible with Jellyfin 10.11's
  encoder (section 4).
- Generic capability detection, arm64 builds and every device other than the
  reference TV are untested on hardware.
- There is no `MediaSession`, so system and voice media controls are not
  integrated.
- On the reference TV, the hardware decoder once failed to acquire resources for
  streams it should support. A TV reboot cleared it. The root cause is unknown;
  see [TV_CAPABILITIES.md](TV_CAPABILITIES.md#open-issue-on-the-reference-tv).
