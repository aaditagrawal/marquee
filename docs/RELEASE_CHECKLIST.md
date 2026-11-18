# Release checklist

These are the steps that remain before the repository is published and before
the first public APK. Items marked **Blocker** must be done first. The others
should be done before calling a release stable.

## A. Before the repository goes public

- [ ] **Blocker: owner licence review.** Confirm GPL-3.0-or-later (or change to
      GPL-3.0-only *before the first public commit*; after that, changing it
      needs every contributor's consent). Confirm the copyright line "the
      Marquee authors" and the decision to license the icon and banner under
      the GPL (see [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)).
- [ ] **Blocker: decide the AI-provenance wording** for the icon in
      THIRD_PARTY_NOTICES.md section 5 and `design/icon/CREDITS.txt`. It is
      currently stated factually. Keep it or reword it, but do not remove it
      silently.
- [ ] **Blocker: final private-data sweep on the exact tree being pushed.** For
      example, search for private IP ranges, home-directory paths, user names,
      e-mail addresses, Jellyfin IDs, `api_key=`, `Token=` and real titles. Make
      sure `gradle.properties` contains no machine-specific
      `org.gradle.java.home`, and that no `local.properties`, keystore or
      `keystore.properties` is present. Start a **fresh** git history; do not
      push the private development history. Run
      `uv run --no-project python tools/publish_manifest.py --check` on that
      tree and commit exactly the files listed in
      [PUBLISH_MANIFEST.md](PUBLISH_MANIFEST.md) (`git status` after `git add -A`
      in the fresh repository should show the same list).
- [ ] Enable **GitHub private vulnerability reporting** (Settings → Security
      → Private vulnerability reporting). [SECURITY.md](../SECURITY.md) relies
      on it.
- [ ] Enable Dependabot alerts and security updates. Turn on branch protection
      for `main`: require CI, and require signed-off commits if you use a DCO
      app.
- [ ] Create the protected `release` environment with required reviewers. Add
      signing secrets only there (section C).
- [ ] Run CI on GitHub and check the screenshot tests and APK uploads on
      `ubuntu-24.04`. The workflow installs Android platform 36 and build-tools
      36.0.0 explicitly.

### Changing the in-app source URL

The GPL source URL in `app/src/main/res/values/strings.xml` points to
`https://github.com/aaditagrawal/marquee`. If the repository moves, update the
URL there and in the public docs and issue template. Re-record
`SettingsLicencesTest`, verify the goldens, and refresh the docs images:

```sh
./gradlew :app:recordRoborazziDebug --tests '*SettingsLicencesTest'
./gradlew :app:verifyRoborazziDebug
cp app/src/test/screenshots/settings-end.png          docs/media/settings-licences-after.png
cp app/src/test/screenshots/settings-licences.png     docs/media/settings-licences-sheet.png
cp app/src/test/screenshots/settings-licence-text.png docs/media/settings-licences-text.png
cp app/src/test/screenshots/settings-licences.png     docs/media/screens/settings-licences.png
uv run --no-project --with pillow python tools/licences_closeup.py --write
uv run --no-project python tools/publish_manifest.py --write
```

### Known gaps to decide or record before publishing

- [ ] **Dependency verification metadata and locking were deliberately not
      generated** (see [dev/BUILD_NOTES.md](dev/BUILD_NOTES.md#dependency-integrity)).
      Once the dependency set is final, run
      `./gradlew --write-verification-metadata sha256 :app:assembleRelease :app:lintRelease :app:testDebugUnitTest`,
      review `gradle/verification-metadata.xml`, then re-run the same tasks to
      confirm nothing is missing (also run `:app:assembleProfile` and the
      Roborazzi tasks, which resolve extra configurations).
- [x] **Icon provenance: `qc` path in `ui/library/kit/Icons.kt`.** Its phone
      outline could not be matched to a specific upstream Material icon file.
      It was never referenced, so it was deleted (no visual change; every
      golden is byte-identical). The 7 other paths that differ slightly from
      current upstream numbers are listed in THIRD_PARTY_NOTICES.md section 4;
      re-check them if exact provenance matters.
- [ ] The `profile` build is signed with each build machine's own debug key and
      must never be distributed; release APKs are unsigned unless the four
      signing values are set.

## B. On-device validation (before calling any release stable)

Only one device has ever run Marquee: a Sony BRAVIA VU1 (Android 11,
armeabi-v7a) with Jellyfin 10.11.11. The public code adds behaviour that has
never run on hardware.

- [ ] **Blocker for a public APK: smoke-test the public build on the reference
      TV.** Run sign-in (Quick Connect and password, on a server with no
      pre-filled address), Home, library, detail, search, and settings, plus
      playback of direct play, remux (audio conversion) and a transcode. Open
      Settings → Open-source licences and scroll the GPL text and the
      third-party notices with the remote (no stall when opening). Check
      that the reference-device gate still selects the measured profile
      (diagnostics overlay → play method and reasons).
- [ ] **arm64 device test.** For example, an NVIDIA Shield (which offers both
      32- and 64-bit userspace), a Chromecast with Google TV / Google TV
      Streamer class device, or the Google TV emulator with a 64-bit image.
      Confirm that `lib/arm64-v8a/libffmpegJNI.so` loads (FFmpeg version in the
      `init` log line) and that a TrueHD/DTS-HD fallback still plays.
- [ ] **16 KB page-size test.** Use an Android 15+ 16 KB emulator image (Google
      TV images may not exist; a phone image is acceptable for the native
      library check), or a device booted in 16 KB mode. `adb shell getconf
      PAGE_SIZE` must print `16384`. Then launch, play a file that uses the
      FFmpeg renderer, and check that no page-size compatibility warning
      appears. Also run the static checks in
      [RELEASE_REQUIREMENTS.md](RELEASE_REQUIREMENTS.md#verifying-a-release-apk-against-s5).
- [ ] **Non-Sony capability detection.** On at least two other vendors' TVs or
      boxes, attach `tools/device-capabilities.sh` output and the diagnostics
      overlay for direct-play HEVC/HDR10, H.264 High 1080p, AV1 (expect direct
      play only if a decoder exists), E-AC-3 5.1 and TrueHD sources. Verify the
      server's play method matches the device's real decoders.
- [ ] **Real Skip Credits check.** Play an episode that has a real Outro media
      segment. Focus the *Skip Credits* pill and confirm the label is not
      clipped. Check `button` and `auto` modes and next-episode autoplay. The
      padding fix was only verified by a screenshot test.
- [ ] **Realtek audio decoder ordering (AC3/E-AC3 downmix gate).** The
      "stereo input limit" downmix workaround now runs only when the first
      platform decoder that `MediaCodecUtil` lists for `audio/ac3` and
      `audio/eac3` is `OMX.realtek.audio*`. On the reference TV, confirm that
      ordering (`tools/device-capabilities.sh` output, or a debug log of the
      decoder list) and that 5.1 AC3/E-AC3 sources still play with sound. If
      another decoder is listed first, the workaround silently stops applying.
- [ ] **Low-RAM flag on the reference TV.** Read `adb shell getprop
      ro.config.low_ram` (also in `tools/device-capabilities.sh` output). It
      has never been read. If it is `true`, `ActivityManager.isLowRamDevice()`
      is true and `MarqueeImages.memoryBudget` halves the image memory cache
      from 40 to 20 MiB on that TV, a change from the private build that would
      need re-measuring Home/library scrolling.
- [ ] **Device name on the TV.** Confirm that the name set in the TV's Android
      settings (`Settings.Global.DEVICE_NAME`) appears in Settings and in the
      Jellyfin dashboard's device list, and that a non-ASCII name falls back
      as documented (`Session.sanitizeDeviceName`).
- [ ] **https → http behaviour with real servers.** With addresses typed
      **without** a scheme, check: (a) a plain-HTTP LAN server on `host:8096`
      and on a bare host name (falls back to `http://`, sign-in step shows
      "Unencrypted connection (http)"); (b) an HTTPS reverse proxy with a
      publicly trusted certificate (stays on `https://`, no unencrypted note);
      (c) a self-signed or expired certificate (stops with the certificate
      message; no `http://` attempt); (d) explicitly typed `http://` (works
      unchanged). Only host-side MockWebServer tests cover this today
      (`ServerProbeTest`), on the JDK's TLS stack rather than Android's
      Conscrypt.
- [ ] **Capability probe on non-reference hardware** is covered by the
      *Non-Sony capability detection* item above. Also confirm the probe falls
      back to the safe H.264/AAC profile rather than crashing if
      `MediaCodecList` throws.
- [ ] Check the banner and icon in the stock Google TV / Android TV launcher,
      not only the reference TV's launcher.
- [ ] Optional: re-run the benchmark protocol on the public build
      ([benchmarks/README.md](benchmarks/README.md)), so published numbers
      describe code that actually exists in this repository.

## C. Signing and the first APK

- [ ] **Blocker: decide the release key.** The owner's currently installed
      private build is signed with a **private key that is not in this
      repository**. Android only installs an update over an existing app if
      both are signed with the same key. The options are:
  - **Reuse the existing key** for public releases. Existing installs then
    upgrade in place. The key must then be stored as `release` environment
    secrets, and anyone with access to that environment can sign "official"
    builds.
  - **Generate a new release key** (for example `keytool -genkeypair -v
    -keystore marquee-release.jks -keyalg RSA -keysize 4096 -validity 10000
    -alias marquee`). The owner's current install must be uninstalled once,
    which loses its sign-in and settings. This keeps the private key separate
    from CI.
  - Whichever you choose: keep an **offline backup** of the keystore and its
    passwords. A lost key means users can never receive updates again.
- [ ] Add the signing secrets to the `release` environment
      (`MARQUEE_KEYSTORE_BASE64`, `MARQUEE_KEYSTORE_PASSWORD`,
      `MARQUEE_KEY_ALIAS`, `MARQUEE_KEY_PASSWORD`). They must match the
      variables that `app/build.gradle.kts` reads; see
      [dev/BUILD_NOTES.md](dev/BUILD_NOTES.md).
- [ ] Set `versionCode` and `versionName` for the release, update
      [CHANGELOG.md](../CHANGELOG.md) (move *Unreleased* to the version) and
      [RELEASE_NOTES.md](RELEASE_NOTES.md), commit, and tag `vX.Y.Z`.
- [ ] Run the **Release** workflow (manual) for the tag. It builds, verifies
      (`apksigner`, `zipalign -P 16`), writes checksums, attests provenance and
      creates a **draft**. Review the draft, download the APK, compare its
      certificate digest, and then publish by hand.
- [ ] **GPL source availability.** The tag is the corresponding source. Archive
      the upstream sources listed in
      [THIRD_PARTY_NOTICES.md §3](../THIRD_PARTY_NOTICES.md#3-corresponding-source-gpl-30-6)
      next to the release, or somewhere you control: `jellyfin-androidx-media`
      at `v1.9.0+1` with its submodules, and FFmpeg at `37c90f55…`. That way
      the written offer can be honoured even if upstream disappears.
- [ ] Publish the certificate digest in the release notes. Later, consider
      Obtainium-compatible release naming and an F-Droid metadata check.

## D. Google Play (not planned yet, recorded for completeness)

Each of these is a gap today; see [RELEASE_REQUIREMENTS.md](RELEASE_REQUIREMENTS.md).

- [ ] Build an Android App Bundle (`bundleRelease`) and check it with
      `bundletool` (16 KB alignment `PAGE_ALIGNMENT_16K`).
- [ ] Store listing assets: a 1280x720 TV banner, at least one **real** TV
      screenshot (the offline renders in `docs/media/` are not captures of the
      app on a TV), a description that mentions Android TV, and a privacy
      policy URL ([PRIVACY.md](../PRIVACY.md)).
- [ ] A demo Jellyfin server and credentials for reviewers (TV-G5).
- [ ] Consider adding a `MediaSession` (checklist item and Tier 2 TV-VC).
- [ ] A trademark review of the store listing. "for Jellyfin" wording must not
      suggest endorsement; Jellyfin's own guidance applies.
