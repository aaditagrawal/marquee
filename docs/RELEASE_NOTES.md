# Release notes: draft for the first public release

> Draft. Replace `vX.Y.Z`, the certificate digest and the checksums when the
> release is cut, and remove this note. See [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md).

## Marquee vX.Y.Z

Marquee is an unofficial native Android TV client for Jellyfin. This is its
first public release, published under GPL-3.0-or-later. It is not affiliated
with or endorsed by the Jellyfin project.

### What it does

- Browse Home (Continue Watching, Next Up, Recently Added, plus origin/language
  and genre shelves built from your library), libraries with sort, filter and
  A–Z, series and seasons, people and search.
- Sign in with **Quick Connect** or username and password.
- Play with AndroidX Media3. Playback tries direct play first, then a remux
  with the video copied, then HEVC and finally H.264 transcodes, and moves down
  automatically if a step fails.
- HDR10/HLG output on displays that support it, and Dolby Vision profile 7/8
  played through its HDR10/HLG base layer.
- Skip Intro / Skip Credits from Jellyfin media segments, with button, auto or
  off modes.
- Audio and subtitle track selection, text and bitmap (PGS) subtitles,
  trickplay thumbnails, autoplay of the next episode, and playback reporting
  back to your server.

### Tested on

- **One device only:** Sony BRAVIA VU1 (Android 11, armeabi-v7a), with Jellyfin
  server 10.11.x.
- **Not tested:** any other TV or streaming box, arm64 devices, 16 KB page-size
  devices, Android versions other than 11, and other Jellyfin versions. Please
  report what works and what does not.

### New since the private builds

- arm64-v8a libraries and runtime device-capability detection (**untested on
  device**).
- Neutral first-run server setup.
- The Skip Credits button's focused padding fix (`OsdButton.refresh()`).
- Settings → Open-source licences: the GPL notice, source location and every
  bundled licence text, readable with the remote.

### Install

1. Download `marquee-X.Y.Z.apk` and its `.sha256` file from this release.
2. Verify it: `sha256sum -c marquee-X.Y.Z.apk.sha256`, and optionally
   `gh attestation verify marquee-X.Y.Z.apk --repo aaditagrawal/marquee`.
3. Install it: `adb -s <device> install -r marquee-X.Y.Z.apk`, or use
   `tools/install-apk.sh --serial <device> --apk marquee-X.Y.Z.apk`.

Signing certificate SHA-256: `REPLACE_WITH_DIGEST` (`apksigner verify --print-certs`).

If you previously installed a build signed with a different key, Android will
refuse to update over it. Uninstall the old app first; this removes its sign-in
and settings.

### Known limitations

- There is no MediaSession or voice-control integration.
- Full video transcodes from Jellyfin 10.11 are SDR.
- The app is distributed for sideloading only; it is not on Google Play.
