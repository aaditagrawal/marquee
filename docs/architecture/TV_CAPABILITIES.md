# Device capabilities

Marquee builds its Jellyfin DeviceProfile from what the device can decode and
display. This page explains how that works, and records the measured
capabilities of the one **reference device** that the app was developed and
tested on.

## How capabilities are determined

`player/PlaybackCapabilities.kt` probes once per process on a low-priority
background thread started from `MarqueeApplication`. Callers that need the
result before the probe finishes wait for that same probe; a second probe is
never started.

1. **Identity.** `Build.MANUFACTURER`, `Build.MODEL` and the list of
   non-secure decoder names from `MediaCodecList`.
2. **Reference-device quirk.** If `DeviceQuirks.isBraviaVu1` matches
   (manufacturer "Sony", model "BRAVIA VU1", and a Realtek `OMX.realtek.*`
   decoder whenever the list can be read), the measured profile below is used
   unchanged (`BraviaVu1Profile`).
3. **Otherwise, detection.** For each video codec Jellyfin knows (h264, hevc,
   vp9, av1, vp8, mpeg2video, mpeg4), the best decoder (hardware first, then
   the larger one) supplies maximum size, frame rate at the largest standard
   size, bitrate, profiles, level and bit depth. Audio formats come from the
   decoder list. HDR types come from the active `Display`. A Dolby Vision decoder
   is noted separately. Software video decoders are capped at 1080p30.
4. **Fallback.** If probing throws or finds nothing usable, Marquee uses
   `DeviceCapabilities.SAFE` (8-bit H.264 ≤ 1080p30 level 4.1 SDR, AAC/MP3).

Steps 3 and 4 are new in the public version. They have unit tests with fake
devices, but **they have not been run on real hardware other than the reference
TV**. Reports from other devices are welcome. Please include the output of
[`tools/device-capabilities.sh`](../../tools/device-capabilities.sh) with your
issue.

## Reference device: Sony BRAVIA VU1

The values below come from read-only `dumpsys` and `/vendor/etc/media_codecs*.xml`
inspection of one TV in October 2026, with Jellyfin server 10.11.11.
**Other BRAVIA models, firmware versions and SoCs may differ.**

### SoC, memory and display

- Realtek RTD287O SoC, 4x Cortex-A55, **32-bit userspace (armeabi-v7a only)**.
- ARM Mali-G31 GPU (GLES 3.2).
- 2 GB RAM, roughly 470 MB available at idle. `dalvik.vm.heapgrowthlimit=192m`.
- Display: a single mode, 1920x1080 at 60 Hz, density 320 (960x540 dp). With
  only one mode, `Surface.setFrameRate` or a preferred display mode has nothing
  to switch to. 24 fps content shows the usual 3:2 cadence on a 60 Hz panel.
- HDR: `HdrCapabilities` reports types 2 and 3 (**HDR10, HLG**), with a maximum
  luminance of 500. There is **no Dolby Vision and no HDR10+** output. HDR is
  delivered through the video plane, so Marquee renders to a `SurfaceView`.

### Video decoders

Hardware: `OMX.realtek.video.decoder` (plus a `.secure` twin).

| Codec | Max size | Profiles / levels advertised | Notes |
|---|---|---|---|
| HEVC | 4096x2176 | Main, Main10, Main10HDR10 @ L5.1 (Main/High tier) | No Main10HDR10Plus profile. 3840x2160 at 60 fps; bitrate cap 80 Mbps. Tunneling and adaptive playback supported. |
| H.264 | **1920x1088** | Baseline/CBP/Main/High/CHigh @ **L4.2** | **No High10.** 10-bit H.264 is not supported by hardware or by `c2.android.avc`. |
| VP9 | 4096x2176 | Profiles 0/1/2/2HDR/3 @ L5.1 | 4K60, 60 Mbps cap. |
| MPEG-2 | 1920x1088 | MP@HL | |
| MPEG-4 Part 2 / H.263 / VP8 | 1920x1088 | | |
| AV1 | — | — | **No AV1 decoder at all**, hardware or software. The server must transcode. |
| Dolby Vision | — | — | No `video/dolby-vision` decoder. Profile 8 is played through the HEVC base layer. |

Software (`c2.android.*`): AVC (8-bit), HEVC Main (8-bit only), VP8, VP9,
MPEG-2, MPEG-4 and H.263. These are slow on Cortex-A55 cores.

### Audio

- Hardware `OMX.realtek.audio.decoder`: AC-3, E-AC-3 and DTS (core, plus LBR),
  MPEG Layer II. **The output is limited to 2 channels (downmix)**, and the
  decoder wrongly advertises this as an input limit (see the quirk in
  [PLAYBACK_DECISIONS.md](PLAYBACK_DECISIONS.md#5-audio)).
- Software `c2.android.*`: AAC (LC/HE/HEv2/LD/ELD/xHE, up to 8 channels), MP3,
  Opus, Vorbis, FLAC, AMR, G.711 and PCM.
- **No TrueHD/MLP and no ALAC decoders.**
- Compressed offload outputs exist for AC-3, E-AC-3, E-AC-3-JOC, DTS and DTS-HD
  to the speaker, HDMI ARC and HDMI. TrueHD is not offered. On the built-in
  speakers, compressed or tunneled output was silent in testing, so Marquee
  defaults to decoded PCM with tunneling off.

### What this means for the profile

- HEVC and VP9 4K 10-bit direct play: yes (≤ 4096x2176, HEVC level ≤ 153).
- H.264: ≤ 1920x1088, level ≤ 42, 8-bit only. Full transcodes target HEVC
  first, then H.264 ≤ 1920x1080.
- HDR10 and HLG are native. HDR10+ plays as HDR10. Dolby Vision 8.x plays
  through its HDR10/HLG/SDR base layer. Profile 7 with an HDR10 base layer
  plays through that base layer. Profile 5 is tone-mapped by the server.
- AV1 and 10-bit H.264 are transcoded by the server.
- TrueHD, ALAC and DTS-HD are converted by the server (see
  [PLAYBACK_DECISIONS.md](PLAYBACK_DECISIONS.md)).

### Open issue on the reference TV

On some streams that the TV should be able to decode, the hardware video decoder
failed to initialise with a resource-acquisition error. This happened with H.264
level 4.0 at several 1080p-class sizes, and also with some HEVC streams.
Android's media resource manager showed no codecs in use. Playback worked again
after the TV was rebooted (see [../benchmarks/README.md](../benchmarks/README.md)),
which points to a transient decoder-resource state rather than a stream
property. The root cause was not identified. Marquee's conservative H.264 sizing
is policy, not a proven fix.
