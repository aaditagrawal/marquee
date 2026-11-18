# Android TV distribution requirements

This page records the official Google requirements that apply to a TV app like
Marquee, with links to the primary sources and the date they were read. It then
maps each requirement to Marquee's current status.

All sources were accessed on **2026-11-15**. Google changes these pages. Check
them again before you submit to Google Play.

Marquee is currently distributed only as a sideloaded APK. It has not been
submitted to Google Play and has not been through Google's TV review. Nothing
on this page says that Marquee passes that review.

## Primary sources

| # | Source | What it says (quoted) |
|---|---|---|
| S1 | [Android Developers Blog: "64-bit app compatibility for Google TV and Android TV"](https://android-developers.googleblog.com/2025/08/64-bit-app-compatibility-for-google-tv-android-tv.html), posted 21 August 2025 by Fahad Durrani (Product Management, Google TV) | "Google TV and Android TV will require 64-bit app compatibility to support upcoming 64-bit TV devices starting August 2026." It adds: "Any new app or app update that includes native code is required to provide 64-bit (arm64) versions in addition to 32-bit (armeabi-v7a) versions when submitted to Google Play." For apps targeting Android 15 or higher: "your 64-bit app version must be compatible with 16 KB memory page sizes". Also: "Prepare your TV apps to comply with 64-bit requirements by August 1st, 2026", "We're not making any changes to 32-bit support", and "This requirement only impacts apps that utilize native code." |
| S2 | [TV app quality](https://developer.android.com/docs/quality-guidelines/tv-app-quality) (developer.android.com) | Criterion **TV-G6**: "From August 1, 2026, TV apps must support both 32- and 64-bit architectures and comply with 16 KB page size requirements." The change notes say TV-G6 was added in January 2026, and that in May 2026 the 16 KB rule (formerly TV-G7) was folded into TV-G6. Other Tier 3 criteria used below: TV-LM, TV-LB, TV-BN, TV-ML, TV-MT, TV-DP, TV-DM, TV-DB, TV-PS, TV-PC, TV-PP, TV-BU, TV-NP, TV-G1, TV-G4, TV-G5. |
| S3 | [Distribute to Android TV](https://developer.android.com/training/tv/publishing/distribute) | "From August 1, 2026, your TV app must support both 64-bit architectures and 16 KB page sizes." |
| S4 | [TV app checklist](https://developer.android.com/training/tv/publishing/checklist) | Use `CATEGORY_LEANBACK_LAUNCHER` for the main TV activity. The banner "measures 320x180 px" and is "in the `drawables/xhdpi` directory" with "localized text to identify the app". Landscape orientation. "Make sure every UI element works with both D-pad and game controllers." "Make sure that your UI does not require a touchscreen." Playback apps should "Register a media session" and set `FLAG_KEEP_SCREEN_ON` during video. |
| S5 | [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes) | General Play rule: "all apps targeting Android 15 (API level 35) and higher must support 16 KB memory page sizes on 64-bit devices on Google Play. Starting February 1, 2027, if your app updates don't support 16 KB memory page sizes, you won't be able to release these updates." It also documents the checks (`llvm-objdump -p … \| grep LOAD` alignment `2**14`, the RELRO rule, and `zipalign -c -P 16 -v 4`) and says AGP 8.5.1+ packages uncompressed native libraries 16 KB zip-aligned. |
| S6 | [Target API level requirements for Google Play apps](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en) (Play Console Help) | For Android TV: "When you publish a new TV app, you must target Android 14 (API level 34) or higher" and "When you update your app, you must target Android 14 (API level 34) or higher." The page is inconsistent about the date. Its table shows **August 31, 2025** for the Android TV row, but the section introduction groups Android TV under "Starting August 31, 2026:". Phone apps must target API 36 from August 31, 2026; Wear OS must target API 35. |

### The 2026-08-01 TV date, checked

The working assumption was that the 64-bit and 16 KB rule applies "from
2026-08-01 for TV". This is **confirmed** by S1, S2 and S3. The exact wording is "from August
1, 2026" (S2, S3) and "by August 1st, 2026" (S1). Two clarifications:

- The 64-bit rule is **in addition to** 32-bit. Google asks for both arm64-v8a
  and armeabi-v7a, and keeps serving 32-bit devices (S1).
- The rule only matters because Marquee ships native code. The release APK
  contains two native libraries per ABI, both from dependencies: the FFmpeg
  audio decoder's `libffmpegJNI.so` and Compose's small
  `libandroidx.graphics.path.so` (`androidx.graphics:graphics-path`). Marquee
  itself has no NDK sources.

Two related dates should not be confused with the TV deadline. February 1, 2027
is the general Play deadline for app updates that target API 35+ (S5).
November 1, 2025 is an older general 16 KB date that some third-party pages
quote. Neither was found on a primary page for TV specifically.

### Target API level as of November 2026

S6 sets the TV minimum at **API 34** for new apps and for updates. Marquee
targets **API 36** (`targetSdk = 36` in `app/build.gradle.kts`), so it is above
the TV minimum under either date reading. Because it targets API 35 or higher,
the 16 KB clause in S1 and S5 applies to its 64-bit build.

## Requirement-to-status map

Status values:

- **Met (manifest)**: you can see it in `app/src/main/AndroidManifest.xml` or
  the Gradle files.
- **Met (build inspection)**: confirmed by inspecting a built artifact. Where
  a check needs a fresh APK, see [dev/BUILD_NOTES.md](dev/BUILD_NOTES.md) for
  the results.
- **Untested on device**: not checked on hardware for the stated
  configuration.
- **Gap**: not implemented.

Marquee has been used on exactly one device: a Sony BRAVIA VU1 (Realtek RTD287O,
Mali-G31, 2 GB RAM, Android 11, 32-bit userspace), against Jellyfin server
10.11.11.

| Requirement | Source | Marquee status |
|---|---|---|
| 64-bit (arm64-v8a) **and** 32-bit (armeabi-v7a) native code from Aug 1, 2026 | S1, S2 TV-G6, S3 | The private build shipped armeabi-v7a only. The public release build type keeps `armeabi-v7a` and `arm64-v8a` (`app/build.gradle.kts`, release `abiFilters`; see [dev/BUILD_NOTES.md](dev/BUILD_NOTES.md)). Debug builds keep every ABI. The upstream decoder AAR contains `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64` copies of `libffmpegJNI.so`. **arm64 is untested on device**: the reference TV has a 32-bit userspace and cannot run it. |
| 16 KB page-size compatibility of the 64-bit build | S1, S2 TV-G6, S5 | **Partly met (build inspection)**. In `media3-ffmpeg-decoder-1.9.0+1.aar` (SHA-256 `dfc726c8…cd181`, the same as Maven Central's published checksum), every `LOAD` segment of `jni/arm64-v8a/libffmpegJNI.so` and `jni/x86_64/libffmpegJNI.so` has alignment `0x4000` (2\*\*14). In both files the `GNU_RELRO` region sits alone in its writable `LOAD` segment, and the next writable `LOAD` begins on a separate 16 KB page. That satisfies S5's RELRO condition by inspection. The other bundled library, `libandroidx.graphics.path.so`, also has `0x4000` LOAD alignment in every ABI of the built APK. APK zip alignment depends on packaging. The build keeps native libraries uncompressed (`jniLibs.useLegacyPackaging = false`), and AGP 9.3.1 is newer than the 8.5.1 that S5 requires. Before release, check each APK with `zipalign -c -P 16 -v 4`; results go in [dev/BUILD_NOTES.md](dev/BUILD_NOTES.md). **No 16 KB device or emulator test has been run.** |
| Target API ≥ 34 for TV (new apps and updates) | S6 | **Met (manifest)**: targetSdk 36. |
| minSdk ≤ 31 (TV-PS) | S2 | **Met (manifest)**: minSdk 26. |
| `ACTION_MAIN` + `CATEGORY_LEANBACK_LAUNCHER` (TV-ML, TV-LM) | S2, S4 | **Met (manifest)**: on `MainActivity`. Shown in the launcher of the reference TV. Other launchers are untested. |
| 320x180 banner containing the app name (TV-LB, TV-BN); xhdpi | S2, S4 | **Met by the packaged resource**: `res/drawable-xhdpi/banner.png` is a 320x180 bitmap with the Marquee wordmark. The generator also renders the other density buckets. The app currently ships in English. The banner still needs a stock-launcher check. |
| App icon ≥ 160x160 px at xhdpi (TV-LB) | S2 | **Met (build inspection)**: an adaptive vector icon on API 26+. The legacy `mipmap-xhdpi/ic_launcher.png` is 96x96 px, but it is only used below API 26, which is under minSdk. |
| Touchscreen not required (TV-MT) | S2, S4 | **Met (manifest)**: `android.hardware.touchscreen` `required="false"`. `android.software.leanback` is also `required="false"`. The manifest declares no other hardware features. |
| Permissions imply no unsupported hardware | S4 | **Met (manifest)**: only `INTERNET`, `ACCESS_NETWORK_STATE` and `WAKE_LOCK`. |
| Landscape | S2 TV-LO, S4 | **Met (manifest)**: both activities use `screenOrientation="landscape"`. |
| Fully navigable with a five-way D-pad (TV-DP); no Menu key dependency (TV-DM) | S2, S4 | **Met on the reference device** using its Sony remote. The UI is built around D-pad focus, and the player accepts Menu as an optional key to show controls. Game controllers are **untested**. |
| Back from the top level returns to the TV home screen (TV-DB) | S2 | Back pops `MarqueeNavHost` and leaves the app at the root. This is not formally verified against TV-DB. |
| Centre button toggles play/pause; Left/Right seek (TV-PC); media Play/Pause key toggles (TV-PP) | S2 | Implemented in `PlayerActivity` key handling. Used on the reference device. |
| Prevent Ambient Mode during video (TV-BU, S4) | S2, S4 | **Met in code**: the player sets `FLAG_KEEP_SCREEN_ON`. |
| Register a media session (S4 playback apps; Tier 2 TV-VC) | S4, S2 | **Gap**: there is no `MediaSession`. Voice and system media controls are not integrated. |
| Audio continuing in background needs Now Playing controls (TV-NP) | S2 | Not applicable: video playback stops when the player activity stops. |
| Low-RAM memory limits (TV-ME) | S2 | **Not assessed.** Whether the reference TV reports `isLowRamDevice()` (`ro.config.low_ram`) has not been verified; it is unlikely on a 2 GB device but was never read. If it does report it, `MarqueeImages.memoryBudget` halves the image memory cache from 40 to 20 MiB on that TV. Listed for on-device validation in [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md). |
| Android App Bundle for Google Play (TV-G1) | S2 | **Gap for Play**. The project builds APKs for sideloading; `bundleRelease` has not been configured, tested or uploaded. |
| Sign-in test credentials for reviewers (TV-G5) | S2 | Not applicable until a Play submission. Marquee needs a user-supplied Jellyfin server, so a reviewer would need a demo server. |
| Accurate, unaltered TV screenshots (TV-G4) | S2 | The repository screenshots are **offline renders of synthetic data**, not captures of the app on a TV. They would not meet TV-G4 as they are. |
| Baseline Profiles (Tier 2 TV-BP) | S2 | **Met (build inspection)**: `app/src/main/baseline-prof.txt` is packaged by `profileinstaller`. |

## Verifying a release APK against S5

Run these checks on each release APK. Paths assume an SDK with build-tools 35
or newer and an NDK installed.

```bash
unzip -o app-release.apk 'lib/*' -d /tmp/marquee-apk
for so in /tmp/marquee-apk/lib/*/*.so; do
  echo "$so"; llvm-readelf -lW "$so" | grep -E 'LOAD|RELRO'
done
zipalign -c -P 16 -v 4 app-release.apk
```

Every 64-bit `LOAD` line must show alignment `0x4000` or larger.
`zipalign -c -P 16` must end with "Verification successful". These are static
checks only. Runtime testing on a 16 KB device or emulator is still required
(see [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md)).
