# Build notes (app/build engineering)

Technical notes behind the public build: toolchain, ABIs and 16 KB pages, signing, versioning,
lint policy, device-capability detection, networking/security review and the login change.
Everything here was measured on a Linux build host; nothing was installed on or run against a
device or a Jellyfin server while preparing it. "Reference device" means the Sony BRAVIA VU1
(Realtek RTD287O, 4x Cortex-A55, Mali-G31, 32-bit Android 11 userspace) the app was tuned on.

## Toolchain

| Item | Value |
|---|---|
| Gradle | 9.5.1 via the wrapper. Wrapper regenerated with the 9.5.1 distribution; `gradle-wrapper.jar` SHA-256 `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7` equals Gradle's published `gradle-9.5.1-wrapper.jar.sha256`. `distributionSha256Sum=bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f` pins the distribution. |
| JDK to run Gradle | 17 or newer. Tested with JDK 21 (JetBrains Runtime) and JDK 17 (Temurin). No `org.gradle.java.home` and no toolchain auto-provisioning: set `JAVA_HOME` (CI: `actions/setup-java` with 21, or 17). |
| Bytecode level | Java and Kotlin target 17 (`compileOptions`, `jvmTarget`). |
| AGP / Kotlin | 9.3.1 / 2.4.10, compileSdk/targetSdk 36, minSdk 26 |
| Memory | `org.gradle.jvmargs=-Xmx1536m`, Kotlin in-process. No host paths in `gradle.properties`. |

The previous wrapper jar (SHA-256 `e996d452…c9e637`) matched none of the published wrapper checksums
checked (Gradle 8.1x/9.x), so it was replaced rather than trusted.

Dependency verification metadata / locking: see "Dependency integrity" below.

## Native code, ABIs and 16 KB page size

The shipped release APK contains two native libraries per ABI: `libffmpegJNI.so` from
`org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1` (software audio decoders used as a last resort)
and the small `libandroidx.graphics.path.so` from Compose's `androidx.graphics:graphics-path:1.0.1`
(details below). The `profile` build type additionally contains the AndroidX Perfetto tracing binary;
it is never distributed.

Measured from the AAR in the Gradle cache with `readelf -lW` (all LOAD segments):

| ABI in AAR | `libffmpegJNI.so` size | LOAD p_align |
|---|---|---|
| armeabi-v7a | 1,360,588 B | 0x4000 (x3) |
| arm64-v8a | 1,462,200 B | 0x4000 (x3) |
| x86 | 1,468,228 B | 0x4000 (x3) |
| x86_64 | 1,554,928 B | 0x4000 (x3) |

All LOAD segments are 16 KB aligned (>= 0x4000) for every ABI, so the pinned 1.9.0+1 is 16 KB
compatible; no newer release was needed. (Maven Central lists 1.9.0+1 as the newest
`org.jellyfin.media3:media3-ffmpeg-decoder` on 2026-11-15.)

ABI decision:

- **Release: `armeabi-v7a` + `arm64-v8a` in one universal APK.** 32-bit is required for TVs with
  32-bit userspace (the reference TV is one); 64-bit is required by Google TV / Android TV from
  2026-08-01 for apps with native code (see docs/RELEASE_REQUIREMENTS.md). x86/x86_64 are left out
  of release: no shipping Android TV hardware uses them, and they would add ~3 MB.
- **Debug: all four ABIs**, so x86_64 Android TV emulator images work.
- **Optional per-ABI APKs:** `-Pmarquee.abiSplits=true` adds `app-armeabi-v7a-release-unsigned.apk`
  and `app-arm64-v8a-release-unsigned.apk` next to the universal APK.
- `packaging.jniLibs.useLegacyPackaging = false` (the AGP default for minSdk >= 23, now explicit):
  native libraries are stored uncompressed and page-aligned in the APK.

APK measurements (build host, 2026-11-16, unsigned unless noted; R8 minify + resource shrinking on
for release). `zipalign -c -P 16 -v 4` (build-tools 36.0.0) reported "Verification successful" for
every APK below, and every native library is stored uncompressed.

| Artifact | Bytes | SHA-256 | Native ABIs |
|---|---|---|---|
| `app-universal-release-unsigned.apk` (`-Pmarquee.abiSplits=true`) | 5,326,309 | `e5bda15aee1693315c4af4e772e5b43c4d38b53c2663ab15b9e2327e8a4382ed` | arm64-v8a, armeabi-v7a |
| `app-arm64-v8a-release-unsigned.apk` | 3,936,810 | `d2ceb63c4afde169d3dc1e465159ae4fa262403a1fc0ca775e2dc1377a153114` | arm64-v8a |
| `app-armeabi-v7a-release-unsigned.apk` | 3,835,202 | `b10d3332f624b66bb0995ef5bc6784cbb09f96a56f7cdec5d6fdc447938ba734` | armeabi-v7a |
| `app-debug.apk` (not minified) | 23,279,627 | `56542877de7e585e9eb158456334bc6f59199812d465b7aee3886082320738a0` | all four |

Without `-Pmarquee.abiSplits` the single release output is
`app-release-unsigned.apk`. The 2026-11-18 JDK 21 build measured **5,363,129
bytes** with SHA-256
`7385f14f5c3e640cdbfc2913333e9b6b9415909bd78625beffec7978fb387b36`.
It includes `arm64-v8a` and `armeabi-v7a` and passed
`zipalign -c -P 16` with build-tools 36.0.0. The table above contains earlier
build measurements. The hashes identify the measured files.

`libandroidx.graphics.path.so` (from Compose's `androidx.graphics:graphics-path:1.0.1`, 7–11 KB per
ABI) is in both release ABIs and in all four debug ABIs; its LOAD segments are also 0x4000 for all four
ABIs (checked in the built APK).

`aapt2 dump badging` on the release APK: `versionCode='2' versionName='0.2.0'`,
`native-code: 'arm64-v8a' 'armeabi-v7a'`, leanback and touchscreen "not required", and an implied
required `android.hardware.screen.landscape` (from `screenOrientation="landscape"`).

## Signing

- `assembleRelease` produces **`app-release-unsigned.apk`** unless signing is configured. The debug
  key is never used for release, and nothing outside the project directory is read.
- Opt in with all four values from any one source per value (precedence: environment variable, then
  Gradle property, then `keystore.properties` in the project root):

  | Environment | Gradle property | keystore.properties |
  |---|---|---|
  | `MARQUEE_KEYSTORE_FILE` | `marquee.keystore.file` | `storeFile` |
  | `MARQUEE_KEYSTORE_PASSWORD` | `marquee.keystore.password` | `storePassword` |
  | `MARQUEE_KEY_ALIAS` | `marquee.key.alias` | `keyAlias` |
  | `MARQUEE_KEY_PASSWORD` | `marquee.key.password` | `keyPassword` |

  `storeFile` (from any of the three sources) is passed to `rootProject.file()`: a relative path
  resolves against the project root (the directory with `settings.gradle.kts`), **not** against
  `app/`, and `~` is not expanded. An absolute path is recommended. This differs from the private
  build, which resolved a relative `storeFile` against `app/`; a relative path reused from an old
  `keystore.properties` would now point somewhere else, so convert it to an absolute path.
  `keystore.properties` is gitignored; `keystore.properties.example` documents it.
- **Partial configuration fails the build** at configuration time and names the missing values.
- The `profile` build type (local diagnostics) is signed with the debug key, as before. Verified on
  2026-11-16: `:app:assembleProfile` builds `app-profile.apk` (11,608,029 bytes; `armeabi-v7a` +
  `arm64-v8a`, each with `libffmpegJNI.so`, `libandroidx.graphics.path.so` and `libtracing_perfetto.so`),
  `apksigner` reports signer `C=US, O=Android, CN=Android Debug` (the build machine's
  `~/.android/debug.keystore`, so the certificate differs per machine), versionCode 2 / 0.2.0, package
  `com.mav.marquee`, `<profileable android:shell="true">`. Its R8 configuration has no
  `-assumenosideeffects class android.util.Log`, and its dex still contains `MARQUEE_HOME_READY`,
  `MARQUEE_STARTUP`, `TTFF ms=`, `AUDIO_TTFF`, `SEEK first_frame_ms`, `SYNC_FRAME`/`EXACT_FRAME`; the
  release dex contains none of them. The `MainActivity` launch extras (`hardwareBitmaps`,
  `lazyPrefetch`, …) are read in every build type, release included.
- Verified on the build host:
  - No signing configuration: `apksigner verify` on `app-release-unsigned.apk` -> "DOES NOT VERIFY /
    Missing META-INF/MANIFEST.MF".
  - All four environment variables pointing at a **throwaway** keystore generated for the test
    (`CN=Throwaway Test, O=Example`, 2-day validity, deleted afterwards) -> `app-release.apk`,
    `apksigner verify` -> "Verifies", APK Signature Scheme v2, signer DN `CN=Throwaway Test, O=Example`.
  - Only `MARQUEE_KEY_ALIAS` set -> configuration fails: "Release signing is partially configured.
    Missing: MARQUEE_KEYSTORE_FILE / ...; MARQUEE_KEYSTORE_PASSWORD / ...; MARQUEE_KEY_PASSWORD / ...".

## Versioning and upgrades

- Default `versionCode = 2`, `versionName = "0.2.0"`. CI overrides with
  `-Pmarquee.versionCode=<n> -Pmarquee.versionName=<x.y.z>`.
- The owner's existing install is versionCode 1, signed with the owner's private key. Android only
  upgrades in place when the signing certificate matches **and** the versionCode is not lower:
  - A public build signed with a different key cannot replace that install; Android refuses the
    update (uninstall first, which deletes the stored server address and token).
  - The owner's own release builds (signed with their key via the opt-in above) upgrade cleanly from
    code 1 to 2+.
  - Never publish a versionCode lower than one already distributed under the same key.
- Debug builds use `com.mav.marquee.debug` and install alongside release.

## Release shrinking and logging

- R8 minify + resource shrinking stay on for release (and `profile`).
- New `app/proguard-release-logs.pro` (release only, not `profile`) removes `Log.v/d/i` calls. Those
  carry playback/startup timing markers and Jellyfin item ids used for on-device diagnostics. `Log.w`
  and `Log.e` remain; a review of every `Log.*` call found none that includes the access token, an
  `api_key`, or a full stream/image URL (stream logs print query parameter *names* only;
  `StreamResolver.safeUrl` strips `api_key`/`token` parameters from media URLs).
- `profileable android:shell="true"` moved from the main manifest to the `profile` source set only,
  so public release APKs are not shell-profileable.

## Lint policy

Before (baseline project): `checkReleaseBuilds = false`, `abortOnError = false`. The baseline's last
release lint report (`lint-results-release.txt`, dated 2026-10-19) listed **29 errors, 118 warnings,
1 hint**. The errors were:

| Errors | Issue | Resolution |
|---|---|---|
| 12 | `NewApi`: `ImageDecoder` (API 28) in HeroArt/StartupArt | **Fixed** (real crash risk on Android 8.x): hardware-bitmap decoding now also requires API 28; `MarqueeImages.setHardwareBitmaps` ignores the flag below 28. |
| 3 | `NewApi`: `Trace.isEnabled()` (API 29) | **Fixed**: guarded by `SDK_INT >= 29` (profile-build tracing only). |
| 6 | `UnsafeOptInUsageError` in StreamResolver | **Fixed**: `@androidx.annotation.OptIn(UnstableApi::class)` on `Resolved`/`StreamResolver`. |
| 6 | `RestrictedApi` on `super.dispatchKeyEvent` | Suppressed at the two overrides with a comment: lint false positive on overriding the public `Activity.dispatchKeyEvent` through androidx `ComponentActivity`. |
| 2 | `GestureBackNavigation` | Suppressed at the two key handlers with a comment: TV remotes deliver Back as a key event; MainActivity routes it through `OnBackPressedDispatcher`. |

After: `abortOnError = true`, `checkReleaseBuilds = true` (so `lintVitalRelease` runs with
`assembleRelease` unless full lint runs in the same invocation), baseline `app/lint-baseline.xml`, and
`app/lint.xml` disables five checks with reasons (network-dependent dependency-version checks, the
deliberate cleartext base config, ChromeOS ABI). `./gradlew :app:lintRelease` on the 2026-11-16 tree:
**"No errors or warnings (and 93 warnings, 1 hint filtered by baseline)"**, i.e. 0 errors, 0 new
warnings. The baseline holds 100 warning-level entries (it was generated from the debug variant;
5 of them only occur there): mostly `UseKtx` (49), `StaticFieldLeak` (9, application-context
singletons), `ViewConstructor` (7, programmatic views), `SetTextI18n` (7, English-only UI),
`VectorPath`/`VectorRaster` (banner), `UnclosedTrace`, `FrequentlyChangingValue`, `DiscouragedApi`
(fixed landscape on TV). No error-severity issue is baselined. Two new warnings introduced during this
work were fixed instead (`DataExtractionRules` -> `fullBackupContent="false"`; `StaticFieldLeak` on
the two new application-context fields -> suppressed with a comment). Counts are not directly
comparable with the 118 above because the network-based version checks are now disabled.

## Device capabilities (playback profile)

Previously `DeviceProfile` and `MarqueeRenderersFactory` hardcoded the reference TV's decoder facts
for every device. Now:

- `player/DeviceCapabilities.kt`: plain-data model (codec -> max size, frame rate, bitrate,
  Jellyfin profile names, level, bit depth, hardware flag; audio codecs; display HDR types; DV decoder
  presence). `DeviceCapabilities.SAFE` is the fallback: 8-bit H.264 up to 1080p30 level 4.1 SDR,
  AAC/MP3 stereo; the server transcodes everything else.
- `player/PlaybackCapabilities.kt`: probes **once per process** on a low-priority background thread
  started from `Application.onCreate` (`MediaCodecList(REGULAR_CODECS)`, `CodecCapabilities` profile
  levels, `VideoCapabilities` size/frame-rate/bitrate, `Display.Mode.getSupportedHdrTypes()` on API
  34+ else `Display.getHdrCapabilities()`). Secure-only codecs and aliases are skipped; hardware
  decoders win; software video decoders are capped at 1080p30. Any exception, an empty codec list or
  no video decoder yields `SAFE`. A caller that arrives before the probe finished waits for the same
  probe (no second probe). Playback start does not add work: the result is cached before the first
  PlaybackInfo request in normal use.
- **Reference-TV quirk** (`DeviceQuirks.isBraviaVu1`): manufacturer `Sony` (case-insensitive) and
  model exactly `BRAVIA VU1`, plus at least one `OMX.realtek.*` decoder whenever the decoder list can
  be read. When it matches, only decoder *names* are listed (no capability queries) and
  `BraviaVu1Profile` returns the exact previous JSON. `DeviceProfileBaselineTest` pins it
  byte-for-byte against a frozen copy of the old builder for all 4 modes x 2 codecs x AC3 fallback x
  AAC x 3 bitrates (96 cases). Stream URL ceilings (`StreamLimits.BRAVIA_VU1`: HEVC 4096x2176 L5.1
  Main10, H.264 copy 1920x1088 L4.2, H.264 transcode 1920x1080) and `hevc-rangetype=SDR,HDR10,HLG`
  are unchanged for it.
- Other workarounds are gated: the DV P7 -> HEVC base-layer mapping only runs when the device has no
  `video/dolby-vision` decoder (devices with one keep Media3's DV path), and the Realtek "stereo
  input limit" downmix only applies when the first platform decoder for that MIME type is
  `OMX.realtek.audio*`.
- Generic devices: direct-play lists contain only decodable codecs; HEVC/VP9/AV1 range types follow
  the display's HDR types (HDR10+ plays as HDR10, DV with an HDR10/HLG/SDR base layer is advertised
  only when there is no DV decoder or the display supports DV); devices without a hardware HEVC
  decoder never receive HEVC transcodes (Mode.Hevc then also uses H.264); transcode audio is E-AC3 or
  AC3 only if a platform decoder exists, else AAC stereo.
- The fallback ladder Direct -> Remux -> Hevc -> H264 is unchanged.
- Tests: `DeviceCapabilityProfileTest` (fake generic arm64 4K HDR10 box, 1080p SDR-only stick,
  detection failure, DV gating, codec-constant mapping), `DeviceProfileBaselineTest`.
- Not validated on hardware: the probe itself (Robolectric has no codecs). Needs on-device checks on
  at least one non-reference TV (e.g. a 64-bit Google TV device) comparing the sent profile with
  `adb shell dumpsys media.player` / codec XML, and DV P7/P8 playback on a DV-capable device.

## Other single-device assumptions removed

| Was | Now |
|---|---|
| `Session.DEVICE_NAME = "BRAVIA VU1"` | `Session.deviceName`: `Settings.Global.DEVICE_NAME`, else `Build.MODEL`; printable ASCII only, no `" \ , =`, whitespace collapsed, 64 chars max, `Android TV` if empty (OkHttp rejects non-ASCII header values). |
| User agent `Marquee/BRAVIA` | `Marquee/<versionName>` |
| Settings "Sony BRAVIA VU1" | The actual device name |
| `Cards.kt DENSITY = 2` for image px | Callers still pass reference pixels (2 px/dp); `ImageUrls.build` scales them by `min(density, 2) / 2`. Unchanged at 320 dpi; smaller requests on tvdpi/720p panels; capped (not larger) on 4K-UI (640 dpi) devices to keep the bitmap budget. |
| Image LRU fixed at 40 MiB | `MarqueeImages.memoryBudget`: 40 MiB at memory class >= 192 MB, proportionally smaller below, halved when `ActivityManager.isLowRamDevice()` is true, minimum 8 MiB. The reference TV's heap growth limit (memory class) is documented as 192 MB, but whether it reports `isLowRamDevice()` (`ro.config.low_ram`) was never read; if it does, its image memory cache drops from 40 to 20 MiB. Needs on-device validation (see the end of this file). |
| DeviceId | Already a random per-install UUID in app-private prefs; unchanged. |

Layout canvas: all Dp values assume a 960x540 dp canvas. Android TV devices report that for 1080p at
320 dpi, 4K UI at 640 dpi and 720p at tvdpi (213), so those render the same layouts. A device that
reports a larger dp canvas (e.g. 1080p at 240 dpi = 1280x720 dp) gets the same layouts with extra
space; nothing clips. Not fixed (would be a redesign): proportional scaling for such canvases.

## Networking and security review

- **Server address:** no default server. `data/ServerAddress.kt` canonicalizes input; with a scheme
  it is used as typed; without one, HTTPS is tried first, then HTTP (with port 8096, then 80, when no
  port is given). The HTTP candidates are tried only when HTTPS is unreachable at the transport level
  (unknown host, refused, timeout, or no TLS on that port: a plain-HTTP server answering a ClientHello
  with plaintext surfaces as a non-certificate `SSLException`). If TLS reaches certificate validation
  and fails (a `CertificateException`/`CertPathValidatorException` among the causes, or OkHttp's
  `SSLPeerUnverifiedException` for a host-name mismatch), `ServerProbe.resolve` stops with "This
  server's certificate isn't trusted. Fix the certificate, or type the address with http:// to connect
  without encryption." The private build fell through to `http://` on any failure. An attacker who
  blocks HTTPS entirely can still cause the fallback for scheme-less input; that is inherent to
  probing and is documented in PRIVACY.md and SECURITY.md. Base paths are kept (`https://host/jellyfin`), pasted web-client URLs are trimmed
  at `/web`, IPv6 literals are handled (`[fd00::1]:8096`; a bare `fd00::1` is bracketed), query and
  fragment are dropped, `user:pass@` is rejected. `data/ServerProbe.kt` validates each candidate with
  `GET /System/Info/Public` (8 s call timeout) and requires a Jellyfin `Id`; the first that answers is
  stored with its scheme. Errors name the problem (unknown host, refused/timeout, untrusted
  certificate, other TLS failure, not Jellyfin / wrong base path, HTTP status). `ServerProbeTest`
  covers each branch against local sockets: explicit `http://`, scheme-less input on a plain-HTTP
  port (falls back), a trusted TLS MockWebServer (stays on `https://`), a self-signed certificate and a
  host-name mismatch (both stop, no `http://` attempt), plus classification of synthetic exceptions.
  These run on the JDK's TLS stack, not Android's Conscrypt. The old `count { it == ':' } == 1 -> append :8096`
  heuristic (wrong for IPv6 and for https default ports) is gone.
- **Unencrypted sign-in is labelled:** when the resolved address is `http://`, the sign-in steps
  (users, password, Quick Connect) show "Unencrypted connection (http)" under the server address in
  the left column, in the existing `Meta` text style (amber, the theme's warning colour). No new
  component. The first-run server step is unchanged.
- **One login flow:** `ui/login/LoginScreen.kt` (server -> users -> password / Quick Connect). The
  unused `ui/nav/BootstrapLogin.kt` (with the hardcoded owner server) and `ui/nav/Placeholders.kt`
  were deleted.
- **Dev Quick Connect hook removed:** the DEBUG-only `files/dev_qc_code` writer and the
  `PlayerActivity` `bootstrapServer` extra / `QuickConnectBootstrap` (which logged the Quick Connect
  code) were deleted. Nothing writes Quick Connect codes to disk or logcat in any build.
- **Token storage:** app-private SharedPreferences `marquee_session` (`MODE_PRIVATE`).
  `allowBackup="false"` plus new `data_extraction_rules.xml` excluding every domain from cloud backup
  **and device-to-device transfer** (Android 12+ ignores `allowBackup` for D2D transfer).
  EncryptedSharedPreferences was not added: `androidx.security:security-crypto` is deprecated, and its
  keystore-backed encryption would not protect against the threat that matters here (root or a
  compromised app process can read the token either way). Revisit if a maintained replacement exists.
- **Token only to the configured origin:** the shared OkHttp client's interceptor now compares
  scheme, host (case-insensitive), port and base path (`Session.isServerUrl`), replacing a string
  `startsWith` that also matched `http://host:80960` or `http://host.evil.example`. All server
  traffic (repository, Coil images, Media3 `OkHttpDataSource`, prewarm) uses that client. The
  interceptor runs once per call; OkHttp drops `Authorization` when a redirect changes
  scheme/host/port. Covered by `AuthOriginTest` (MockWebServer: same origin gets the header, other
  port and other base path do not, cross-origin redirect drops it).
- **Logs:** see "Release shrinking and logging".
- **Sign-out:** `POST /Sessions/Logout` (best effort), then the local token/user are cleared; the
  Settings flow now also clears the cached Home snapshot, baked hero art, startup art and any
  prepared playback session. The server address is kept to make signing back in easier.
- **PlayerActivity** is `exported` only in debug (manifest placeholder), unchanged.
- **Cleartext HTTP:** deliberately permitted (`network_security_config.xml`, commented). Jellyfin's
  common setup is `http://` on a LAN, often by IP, which no public CA can certify. HTTPS servers are
  verified against system CAs only. A stricter policy (cleartext off, or a domain allowlist) would
  break every `http://` LAN server and any server reached by IP; user-added CAs (self-signed home
  servers) are also not trusted by default, which is a known limitation.

## Login screen change (visual)

Before/after renders (offline Robolectric, 960x540 dp at xhdpi, synthetic state, no server):
`docs/media/login-before.png` and `docs/media/login-after.png`, produced by
`./gradlew :app:testDebugUnitTest --tests com.mav.marquee.login.LoginRenderTest -Pmarquee.loginRender=<phase>`.

What changed, and only this:
- The address field starts empty on first run (it used to be pre-filled with the owner's LAN
  address). The "before" render substitutes `http://jellyfin.example:8096` for that private address.
- The helper line reads "Enter your server's address, like jellyfin.local:8096 or
  https://media.example.com." (was "Enter your server's address, including its port.").
- Error messages after Connect are more specific (not visible in the first-run render).
- Later: the sign-in steps show "Unencrypted connection (http)" for an `http://` server. That line is
  not on the first-run server step, so the render is unchanged: re-running
  `LoginRenderTest -Pmarquee.loginRender=after` on 2026-11-16 rewrote `docs/media/login-after.png`
  byte-identically.

## Android TV manifest and store-listing gaps

Present: `LEANBACK_LAUNCHER` + `LAUNCHER`, `android:banner`, `android.software.leanback`
(required=false, so TV devices that do not report leanback can still sideload),
`android.hardware.touchscreen` required=false, landscape, `FLAG_KEEP_SCREEN_ON` during playback.

The release includes a 320x180 raster banner in `drawable-xhdpi`. A Play Store
TV listing still needs a playback `MediaSession`, store graphics and screenshots,
a public privacy policy URL, Play signing setup, and TV review. Decide whether a
TV-only listing should require `android.software.leanback`.

## Comments that point to public docs

Main-code comments reference these, published under `docs/architecture/`: `PLAYBACK_DECISIONS.md`, `PLAYER_CONTRACT.md`,
`TV_CAPABILITIES.md`, `IMAGE_SIZES.md`, `PREFS.md`. Also relevant to the code but not currently
referenced by path: `DATA_API.md`, `MOTION_ALLOWLIST.md`, `DIAGNOSTICS.md`. The baseline
`docs/device-profile.json` is read by `StreamResolverTest.publishedDeviceProfileMatchesCode`: it is the
static rendering of `DeviceProfile.build()` with default options for the reference device, published
from the baseline after checking it holds no server, account or library data, and the test proves it
equals the code's output.

## Dependency integrity

Not added yet. Gradle dependency verification (`gradle/verification-metadata.xml`) and
dependency locking both require resolving every configuration (including lint, Roborazzi and the
profile variant) and, once committed, make any unlisted artifact fail the build. Generating them
before the dependency set is final would mean regenerating them for every dependency change. What is
in place: pinned versions for every dependency and plugin, a
checksum-verified wrapper jar, and `distributionSha256Sum`. Recommended follow-up, once the
dependency set is final:
`./gradlew --write-verification-metadata sha256 :app:assembleRelease :app:lintRelease :app:testDebugUnitTest`,
review the file, then re-run the same tasks to confirm it is complete.

## Verification log

All commands ran from the project root with `JAVA_HOME` = JDK 21 and `ANDROID_HOME` set, through
`--console=plain --max-workers=2 --no-daemon`, on 2026-11-15/16.

| Command | Result |
|---|---|
| `:app:assembleDebug` | OK; all four ABIs. |
| `:app:assembleRelease` (no signing config) | OK; `app-release-unsigned.apk`; apksigner: does not verify (unsigned). |
| `:app:assembleRelease` with the four `MARQUEE_*` variables -> throwaway keystore | OK; `app-release.apk`, verifies (v2). |
| `help` with only `MARQUEE_KEY_ALIAS` | Fails at configuration with the partial-signing message (expected). |
| `:app:lintRelease :app:assembleRelease -Pmarquee.abiSplits=true` | OK; lint "No errors or warnings (93 warnings, 1 hint filtered by baseline)"; split + universal APKs. |
| `:app:testDebugUnitTest --tests player.* data.* image.* login.*` | 95 tests; all new tests pass (`DeviceProfileBaselineTest` 2, `DeviceCapabilityProfileTest` 5, `ServerAddressTest` 7, `AuthOriginTest` 3 after fixing a test typo, `DeviceSizingTest` 2, `LoginRenderTest` 1). |
| `:app:testDebugUnitTest` (everything) | 155 tests, 1 failed, 5 skipped. The failure is `StreamResolverTest.publishedDeviceProfileMatchesCode`, which needed `docs/device-profile.json`; fixed by publishing it (see the final run below). |
| `LoginRenderTest -Pmarquee.loginRender=before` (on the unmodified login code) / `=after` | Wrote `docs/media/login-before.png` / `login-after.png` (1920x1080). |
| `:app:assembleProfile` (2026-11-16) | OK; `app-profile.apk`, debug-key signed, markers present (see "Signing"). |
| `:app:clean`, then `:app:lintRelease :app:testDebugUnitTest :app:verifyRoborazziDebug :app:assembleDebug :app:assembleRelease` (2026-11-16, final tree) | BUILD SUCCESSFUL. 158 tests run, 153 passed, 0 failed, 5 skipped (the opt-in tests). All 30 goldens match. Lint: "No errors or warnings (and 93 warnings, 1 hint filtered by baseline)". `app-release-unsigned.apk` as above. |
| `:app:lintRelease :app:testDebugUnitTest :app:verifyRoborazziDebug :app:assembleDebug :app:assembleRelease` (2026-11-16, after the review fixes; one invocation) | BUILD SUCCESSFUL. 166 tests run, 161 passed, 0 failed, 5 skipped (the opt-in tests); the 8 new `ServerProbeTest` cases pass. All goldens match. Lint: "No new issues (and 93 warnings and 1 hint filtered by baseline)". `app-release-unsigned.apk` 5,327,305 bytes as above. |
| `LoginRenderTest -Pmarquee.loginRender=after` (2026-11-16, after the unencrypted-connection label) | Rewrote `docs/media/login-after.png` byte-identically (the label is not on the first-run step). |
| `:app:lintDebug` (CI's lint task) | "Lint found no new issues (and 98 warnings and 1 hint filtered by baseline)". |

The 2026-11-18 publish tree passed `:app:verifyRoborazziDebug` with 161 tests
passed, five opt-in tests skipped, and all 30 goldens matched. Debug and
release lint found zero new issues. The earlier baseline still filters
90 debug warnings and 89 release warnings, plus one hint per variant.

Not verified (needs on-device validation; each is also in `docs/RELEASE_CHECKLIST.md` section B):
anything on a device (no TV, emulator or server was used); the capability probe on real hardware;
whether `MediaCodecUtil` lists `OMX.realtek.audio.decoder` first for AC3/E-AC3 on the reference TV
(the downmix workaround depends on it); whether the reference TV reports `isLowRamDevice()`
(`ro.config.low_ram`), which would halve its image memory cache from 40 to 20 MiB; the device name
from `Settings.Global.DEVICE_NAME` on the TV; playback; the HTTPS-to-HTTP rule against real servers
(only host-side MockWebServer tests), and the first CI run on GitHub runners.
An exported publish tree passed `:app:assembleDebug` on Temurin JDK 17.
