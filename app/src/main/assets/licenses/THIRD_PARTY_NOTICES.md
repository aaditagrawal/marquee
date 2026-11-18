# Third-party notices

This file lists everything in a Marquee release APK that Marquee's authors did
not write: Java/Kotlin libraries, native code and bundled assets. It also covers
the GPL source-code offer.

Marquee's own source code is licensed under **GPL-3.0-or-later** (see
[LICENSE](LICENSE) and [NOTICE](NOTICE)). A copy of this file ships in the APK
at `assets/licenses/THIRD_PARTY_NOTICES.md`, next to the full licence texts:

| File in `app/src/main/assets/licenses/` | Licence |
|---|---|
| `GPL-3.0.txt` | GNU General Public License v3 (identical to [LICENSE](LICENSE) and <https://www.gnu.org/licenses/gpl-3.0.txt>) |
| `Apache-2.0.txt` | Apache License 2.0 (from <https://www.apache.org/licenses/LICENSE-2.0.txt>) |
| `material-symbols.txt` | Apache License 2.0 (as shipped with Material Symbols) |
| `OFL-1.1-HankenGrotesk.txt` | SIL Open Font License 1.1, with the Hanken Grotesk copyright line (same as [design/fonts/OFL.txt](design/fonts/OFL.txt)) |
| `THIRD_PARTY_NOTICES.md` | This file |

## 1. Licence decision and evidence

**Decision:** Marquee is GPL-3.0-or-later.

**Why GPL at all.** The release build depends on
`org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1` (software audio decoders
used as a last-resort renderer). Evidence that it is GPL-3.0:

- Its Maven Central POM
  ([media3-ffmpeg-decoder-1.9.0+1.pom](https://repo1.maven.org/maven2/org/jellyfin/media3/media3-ffmpeg-decoder/1.9.0%2B1/media3-ffmpeg-decoder-1.9.0%2B1.pom))
  declares `<license><name>GNU General Public License v3.0</name><url>https://www.gnu.org/licenses/gpl-3.0.txt</url></license>`.
  The POM in the local Gradle cache, resolved by this build, says the same.
- The upstream repository
  [jellyfin/jellyfin-androidx-media](https://github.com/jellyfin/jellyfin-androidx-media)
  has a `LICENSE` file whose text is byte-identical to the official GPLv3
  (SHA-256 `3972dc97…b36986`, the same as gnu.org's `gpl-3.0.txt`).
- Upstream does not say "only" or "or later". GPLv3 §14 says that if a program
  does not specify a version number, you may choose any version ever published
  by the FSF. Marquee treats the artifact as GPL-3.0.

Distributing an APK that contains this library means distributing a combined
work. GPLv3 requires that combined work, including Marquee's own code, to be
offered under GPLv3-compatible terms with corresponding source.

**Why "-or-later".** Every other component is under a licence that is
compatible with GPLv3 (Apache-2.0 for most libraries, OFL-1.1 for the font).
FFmpeg's own code is offered by its authors under LGPL-2.1-or-later with
optional GPL parts. Upstream's build script does not pass `--enable-gpl` or
`--enable-nonfree`, but the licence configuration of the prebuilt binary
Marquee ships could not be established from the binary, and it was not
rebuilt (see section 2). It is distributed here as part of the GPL-3.0 decoder
module. "-or-later" matches the GPL's own default when
no version is named (see above). It also lets the project combine with future
GPL-licensed components without having to ask every past contributor to
relicense. The drawback is trust in future FSF licence versions. If the owner
prefers `GPL-3.0-only`, change it before the first public commit; after that,
it needs every contributor's agreement (see
[docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md)).

**Copyright holder line:** "Copyright (C) 2026 the Marquee authors".
Contributors keep their copyright and license their contributions under the
same terms (see [CONTRIBUTING.md](CONTRIBUTING.md)).

**Could the GPL be avoided?** Only by removing the Jellyfin artifact, for
example by building the AndroidX Media3 FFmpeg extension (Apache-2.0 glue)
against an LGPL-configured FFmpeg ourselves. That is not done today. Even then,
FFmpeg's LGPL obligations would still apply.

## 2. Native code: Jellyfin Media3 FFmpeg decoder and FFmpeg

| | |
|---|---|
| Artifact | `org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1` (AAR, Maven Central) |
| AAR SHA-256 | `dfc726c8bc9d9db02e15b160f886a03bdd6dd51e88ba2de89aa74caf5e8cd181`, identical to the `.sha256` file published on Maven Central |
| Declared licence | GPL-3.0 (POM) |
| Contents | `classes.jar` with `androidx.media3.decoder.ffmpeg.*` (FfmpegAudioRenderer, FfmpegAudioDecoder, FfmpegLibrary, ExperimentalFfmpegVideoRenderer). One `libffmpegJNI.so` per ABI: `armeabi-v7a`, `arm64-v8a`, `x86` and `x86_64`. Marquee's ABI filter decides which copies end up in the APK. |
| Exported JNI | Audio decoder and library-info functions only. No video-decoder JNI functions are exported. |
| Upstream source | <https://github.com/jellyfin/jellyfin-androidx-media>, tag `v1.9.0+1`, commit `af9ee4e26b2045e3ea6f2ebf4a18ac8ebfeae396` (2025-12-29) |
| Submodule: AndroidX Media | <https://github.com/androidx/media> at `7cc1056f840ce226598d3b990d4a6f7cd17e2831`. The FFmpeg JNI glue and `build_ffmpeg.sh` come from here, under Apache-2.0. |
| Submodule: FFmpeg | <https://git.ffmpeg.org/ffmpeg.git> (branch `release/6.0`) at `37c90f55d8b6c3948c79449b04755527b4ae1351` (committed 2025-12-03). The binary's identification string is `Lavc60.3.100`, which is libavcodec from the FFmpeg 6.0 series. |
| Build recipe (upstream) | Upstream's `build.sh` (NDK 26.1.10909125, Android API 23) calls Media3's `libraries/decoder_ffmpeg/src/main/jni/build_ffmpeg.sh` with `ENABLED_DECODERS=(flac alac pcm_mulaw pcm_alaw mp3 aac ac3 eac3 dca mlp truehd)`. |

### What could and could not be established about the FFmpeg build

Established from upstream sources at the pinned commits:

- The pinned `build_ffmpeg.sh` configures FFmpeg with
  `--disable-everything --enable-static --disable-shared --disable-doc
  --disable-programs --disable-avdevice --disable-avformat --disable-swscale
  --disable-postproc --disable-avfilter --disable-symver --enable-swresample
  --disable-v4l2-m2m --disable-vulkan` plus one `--enable-decoder=` for each
  decoder listed above. The result is libavcodec, libavutil and libswresample,
  linked statically into `libffmpegJNI.so`.
- That script passes **no** `--enable-gpl`, `--enable-version3` or
  `--enable-nonfree` and enables no external libraries. Under FFmpeg's
  documented defaults, such a configuration is LGPL-2.1-or-later.
- Upstream's publish workflow (`.github/workflows/extension-publish.yaml`) runs
  that `build.sh` before publishing.

Observed in the shipped binary (`jni/*/libffmpegJNI.so` from the AAR above):

- Decoder name strings for AAC, AC-3, E-AC-3, DCA (DTS), MLP/TrueHD, FLAC,
  ALAC, MP3/MP2 and PCM variants are present. Names of other codecs such as
  H.264, HEVC, AV1 and Opus also appear. They are consistent with libavcodec's
  codec-descriptor and profile tables, but static string inspection cannot tell
  which decoders are compiled in.
- The binary does **not** contain FFmpeg's configuration string or licence
  string (`avcodec_configuration()` / `avcodec_license()` text). Those strings
  can be removed when unused code is stripped from a static link.

**Not established:** we could not verify from the binary itself that the
published AAR was built with exactly the configuration above. We have not
reproduced the build or compared hashes. Because the configuration and licence
strings are missing, the FFmpeg licence of this particular binary cannot be read
from the binary. **Marquee does not assume it is an LGPL build.** The
redistributable unit we ship is the Jellyfin artifact, which declares GPL-3.0,
and Marquee is licensed to satisfy GPL-3.0 whichever FFmpeg configuration was
used.

FFmpeg is © the FFmpeg developers (<https://ffmpeg.org/>). FFmpeg is a
trademark of Fabrice Bellard.

## 3. Corresponding source (GPL-3.0 §6)

If you received a Marquee APK, you are entitled to its complete corresponding
source under GPL-3.0. It consists of:

1. **Marquee:** this repository at the tag that matches the APK's version name
   (Settings → About Marquee). Release tags are named `v<versionName>`. The tag
   includes the Gradle wrapper (pinned Gradle version), `build.gradle.kts` files
   with pinned plugin and library versions, and the build instructions in
   [README.md](README.md#build-from-source). Dependencies are fetched from
   Maven Central and Google's Maven repository at those exact versions.
2. **The Jellyfin FFmpeg decoder:** the `-sources.jar` published next to the AAR
   on Maven Central (Java sources and JNI glue, without FFmpeg), and the
   upstream repository at tag `v1.9.0+1`. Its submodules pin AndroidX Media and
   FFmpeg to the commits listed in section 2.
3. **FFmpeg:** <https://git.ffmpeg.org/ffmpeg.git> at commit
   `37c90f55d8b6c3948c79449b04755527b4ae1351`, also mirrored at
   <https://github.com/FFmpeg/FFmpeg>.

**Fallback written offer.** If any of the upstream locations above stops
providing the source, the Marquee maintainers will provide the corresponding
source for any Marquee release they distributed. Make the request through the
repository's issue tracker or private vulnerability reporting channel. The offer
is valid for three years after the last distribution of that release, at no more
than the cost of physically performing the source distribution. To keep that
promise without relying on third parties, maintainers should archive the three
source trees above with every release (see
[docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md)).

## 4. Bundled assets

Every file under `app/src/main/res`, `app/src/debug/res` and
`app/src/main/assets` was audited:

| Asset | Origin | Licence |
|---|---|---|
| `res/font/hanken_grotesk_regular.ttf`, `res/font/hanken_grotesk_semibold.ttf` | Hanken Grotesk v3.013 static instances, downloaded from Google Fonts. The name table says: "Copyright 2021 The Hanken Grotesk Project Authors (https://github.com/marcologous/hanken-grotesk)", licence URL `https://scripts.sil.org/OFL`. No Reserved Font Name is declared. They are byte-identical to `design/fonts/*.ttf` (SHA-256 `451376ab…691da0` and `b7a67283…c3b823`). | SIL OFL 1.1 ([design/fonts/OFL.txt](design/fonts/OFL.txt)) |
| `res/drawable/player_{replay_10,play_arrow,pause,forward_10,skip_next,volume_up,closed_caption,settings,video_library,close}.xml` | Material Symbols **Rounded**, 24 px, by Google. All 10 path strings were compared with `google/material-design-icons` (`symbols/web/<name>/materialsymbolsrounded/`) on 2026-11-15 and match exactly. `replay_10`, `forward_10` and `close` match the `fill1` variants; the others match the default variants. They are wrapped in a VectorDrawable group that translates the viewport by +960. | Apache-2.0 (`assets/licenses/material-symbols.txt`) |
| Icon path data in `ui/library/kit/Icons.kt` (Kotlin source, not a resource) | Material Icons (classic, filled) path data by Google, as the source comment says. On 2026-11-15, 10 of 18 icons matched the current upstream `src/*/*/materialicons/24px.svg` exactly. 7 have the same path commands with slightly different numbers, probably from older upstream revisions. The 18th (`qc`, a phone outline) could not be matched to a specific upstream file; it was never referenced by the app and was removed on 2026-11-16, so 17 remain. | Apache-2.0 (treated as Material Icons) |
| Glyphs in `ui/components/Icons.kt` | Drawn in code from simple geometry (triangle, circles, chevrons) by Marquee's authors. | GPL-3.0-or-later (Marquee) |
| `res/drawable/banner.xml`, `res/drawable/ic_launcher_background.xml`, `res/drawable/ic_launcher_foreground.xml`, `res/mipmap-anydpi-v26/ic_launcher.xml`, `res/mipmap-xhdpi/ic_launcher.png`, `debug/res/drawable-xhdpi/banner.xml` | The Marquee "reel lattice" identity, generated by [design/brand/build_brand.py](design/brand/build_brand.py) from [design/brand/mark.py](design/brand/mark.py) (see [design/icon/CREDITS.txt](design/icon/CREDITS.txt)). The mark is original geometry in one colour. The "Marquee" and "DEV" wordmarks are vector outlines of Hanken Grotesk SemiBold glyphs. | The artwork: GPL-3.0-or-later as part of Marquee (see section 5). Embedded glyph outlines: OFL-1.1. |
| `res/values/themes.xml`, `res/xml/network_security_config.xml`, `AndroidManifest.xml` | Marquee configuration. | GPL-3.0-or-later |
| `assets/licenses/*` | Licence texts and this file. | Each file is its own licence text. |
| `baseline-prof.txt` (packaged as a baseline profile) | Hand-written list of Marquee classes and methods. | GPL-3.0-or-later |

**Resolved flag:** the `qc` path formerly in `ui/library/kit/Icons.kt` could not
be traced to a specific upstream file. It was unused, so it was deleted rather
than replaced; no screen changed.

No images or audio of copyrighted media, and no artwork from any Jellyfin
server, are bundled. Screenshots under `docs/media/` are offline renders of
synthetic fixture data and are not packaged in the APK.

## 5. The Marquee name and "reel lattice" mark

The icon, banner and wordmark are the project's own identity. They are
generated programmatically by `design/brand/build_brand.py` from the geometry
in `design/brand/mark.py`: overlapping film reels drawn in the manner of the
traditional seigaiha wave pattern, an empty reel as the sun, and an arc of sign
bulbs, in a single colour. The design and scripts were produced with an AI
assistant (Anthropic Claude) at the project owner's request in November 2026,
then reviewed and adopted by the owner. It replaces the earlier "folded light"
M of October 2026. Seigaiha is a traditional motif in the public domain. No
third-party artwork, stock imagery or trademarks were used. The only
third-party material is the Hanken Grotesk glyph outlines (OFL-1.1).

The artwork files are distributed under the same GPL-3.0-or-later terms as the
code, so forks can rebuild and modify them. The GPL covers copyright only. It
does not grant rights to the "Marquee" name or identity as a mark. Forks that
publish modified builds should use a different name and icon so users can tell
them apart.

## 6. Java/Kotlin dependencies resolved for the release build

Generated from `./gradlew :app:dependencies --configuration releaseRuntimeClasspath`
by [tools/third_party_notices.py](tools/third_party_notices.py). Every licence
was read from the artifact's own POM, or its parent POM where noted, in the
Gradle cache. None was filled in from memory. Test-only, debug-only and
`profile`-variant dependencies are not in the release APK and are not listed.

<!-- THIRD-PARTY-TABLE:START -->
_144 resolved artifacts — Apache-2.0: 143, GPL-3.0: 1._

| Group | Artifact | Version | Licence (from POM) | Project URL |
|---|---|---|---|---|
| `androidx.activity` | `activity` | 1.13.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/activity#1.13.0 |
| `androidx.activity` | `activity-compose` | 1.13.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/activity#1.13.0 |
| `androidx.activity` | `activity-ktx` | 1.13.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/activity#1.13.0 |
| `androidx.annotation` | `annotation` | 1.9.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/annotation#1.9.1 |
| `androidx.annotation` | `annotation-experimental` | 1.4.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/annotation#1.4.1 |
| `androidx.annotation` | `annotation-jvm` | 1.9.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/annotation#1.9.1 |
| `androidx.appcompat` | `appcompat-resources` | 1.7.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/appcompat#1.7.0 |
| `androidx.arch.core` | `core-common` | 2.2.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/arch-core#2.2.0 |
| `androidx.arch.core` | `core-runtime` | 2.2.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/arch-core#2.2.0 |
| `androidx.autofill` | `autofill` | 1.0.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.collection` | `collection` | 1.5.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/collection#1.5.0 |
| `androidx.collection` | `collection-jvm` | 1.5.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/collection#1.5.0 |
| `androidx.collection` | `collection-ktx` | 1.5.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/collection#1.5.0 |
| `androidx.compose` | `compose-bom` (version platform, no code) | 2026.06.01 | Apache-2.0 | https://developer.android.com/jetpack |
| `androidx.compose.animation` | `animation` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-animation#1.11.4 |
| `androidx.compose.animation` | `animation-android` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-animation#1.11.4 |
| `androidx.compose.animation` | `animation-core` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-animation#1.11.4 |
| `androidx.compose.animation` | `animation-core-android` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-animation#1.11.4 |
| `androidx.compose.foundation` | `foundation` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-foundation#1.11.4 |
| `androidx.compose.foundation` | `foundation-android` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-foundation#1.11.4 |
| `androidx.compose.foundation` | `foundation-layout` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-foundation#1.11.4 |
| `androidx.compose.foundation` | `foundation-layout-android` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-foundation#1.11.4 |
| `androidx.compose.material` | `material-ripple` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-material#1.11.4 |
| `androidx.compose.material` | `material-ripple-android` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-material#1.11.4 |
| `androidx.compose.material3` | `material3` | 1.4.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-material3#1.4.0 |
| `androidx.compose.material3` | `material3-android` | 1.4.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-material3#1.4.0 |
| `androidx.compose.runtime` | `runtime` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-runtime#1.11.4 |
| `androidx.compose.runtime` | `runtime-android` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-runtime#1.11.4 |
| `androidx.compose.runtime` | `runtime-annotation` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-runtime#1.11.4 |
| `androidx.compose.runtime` | `runtime-annotation-android` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-runtime#1.11.4 |
| `androidx.compose.runtime` | `runtime-retain` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-runtime#1.11.4 |
| `androidx.compose.runtime` | `runtime-retain-android` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-runtime#1.11.4 |
| `androidx.compose.runtime` | `runtime-saveable` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-runtime#1.11.4 |
| `androidx.compose.runtime` | `runtime-saveable-android` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-runtime#1.11.4 |
| `androidx.compose.ui` | `ui` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-ui#1.11.4 |
| `androidx.compose.ui` | `ui-android` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-ui#1.11.4 |
| `androidx.compose.ui` | `ui-geometry` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-ui#1.11.4 |
| `androidx.compose.ui` | `ui-geometry-android` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-ui#1.11.4 |
| `androidx.compose.ui` | `ui-graphics` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-ui#1.11.4 |
| `androidx.compose.ui` | `ui-graphics-android` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-ui#1.11.4 |
| `androidx.compose.ui` | `ui-text` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-ui#1.11.4 |
| `androidx.compose.ui` | `ui-text-android` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-ui#1.11.4 |
| `androidx.compose.ui` | `ui-unit` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-ui#1.11.4 |
| `androidx.compose.ui` | `ui-unit-android` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-ui#1.11.4 |
| `androidx.compose.ui` | `ui-util` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-ui#1.11.4 |
| `androidx.compose.ui` | `ui-util-android` | 1.11.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-ui#1.11.4 |
| `androidx.concurrent` | `concurrent-futures` | 1.1.0 | Apache-2.0 | https://developer.android.com/topic/libraries/architecture/index.html |
| `androidx.core` | `core` | 1.18.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/core#1.18.0 |
| `androidx.core` | `core-ktx` | 1.18.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/core#1.18.0 |
| `androidx.core` | `core-viewtree` | 1.0.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/core#1.0.0 |
| `androidx.customview` | `customview` | 1.0.0 | Apache-2.0 | http://developer.android.com/tools/extras/support-library.html |
| `androidx.customview` | `customview-poolingcontainer` | 1.0.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/customview#1.0.0 |
| `androidx.documentfile` | `documentfile` | 1.0.0 | Apache-2.0 | http://developer.android.com/tools/extras/support-library.html |
| `androidx.dynamicanimation` | `dynamicanimation` | 1.0.0 | Apache-2.0 | http://developer.android.com/tools/extras/support-library.html |
| `androidx.emoji2` | `emoji2` | 1.4.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/emoji2#1.4.0 |
| `androidx.exifinterface` | `exifinterface` | 1.3.7 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/exifinterface#1.3.7 |
| `androidx.graphics` | `graphics-path` | 1.0.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/graphics#1.0.1 |
| `androidx.interpolator` | `interpolator` | 1.0.0 | Apache-2.0 | http://developer.android.com/tools/extras/support-library.html |
| `androidx.legacy` | `legacy-support-core-utils` | 1.0.0 | Apache-2.0 | http://developer.android.com/tools/extras/support-library.html |
| `androidx.lifecycle` | `lifecycle-common` | 2.10.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0 |
| `androidx.lifecycle` | `lifecycle-common-java8` | 2.10.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0 |
| `androidx.lifecycle` | `lifecycle-common-jvm` | 2.10.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0 |
| `androidx.lifecycle` | `lifecycle-livedata` | 2.10.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0 |
| `androidx.lifecycle` | `lifecycle-livedata-core` | 2.10.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0 |
| `androidx.lifecycle` | `lifecycle-livedata-core-ktx` | 2.10.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0 |
| `androidx.lifecycle` | `lifecycle-process` | 2.10.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0 |
| `androidx.lifecycle` | `lifecycle-runtime` | 2.10.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0 |
| `androidx.lifecycle` | `lifecycle-runtime-android` | 2.10.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0 |
| `androidx.lifecycle` | `lifecycle-runtime-compose` | 2.10.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0 |
| `androidx.lifecycle` | `lifecycle-runtime-compose-android` | 2.10.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0 |
| `androidx.lifecycle` | `lifecycle-runtime-ktx` | 2.10.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0 |
| `androidx.lifecycle` | `lifecycle-runtime-ktx-android` | 2.10.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0 |
| `androidx.lifecycle` | `lifecycle-viewmodel` | 2.10.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0 |
| `androidx.lifecycle` | `lifecycle-viewmodel-android` | 2.10.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0 |
| `androidx.lifecycle` | `lifecycle-viewmodel-compose` | 2.10.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0 |
| `androidx.lifecycle` | `lifecycle-viewmodel-compose-android` | 2.10.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0 |
| `androidx.lifecycle` | `lifecycle-viewmodel-ktx` | 2.10.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0 |
| `androidx.lifecycle` | `lifecycle-viewmodel-savedstate` | 2.10.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0 |
| `androidx.lifecycle` | `lifecycle-viewmodel-savedstate-android` | 2.10.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0 |
| `androidx.loader` | `loader` | 1.0.0 | Apache-2.0 | http://developer.android.com/tools/extras/support-library.html |
| `androidx.localbroadcastmanager` | `localbroadcastmanager` | 1.0.0 | Apache-2.0 | http://developer.android.com/tools/extras/support-library.html |
| `androidx.media3` | `media3-common` | 1.9.0 | Apache-2.0 | — |
| `androidx.media3` | `media3-container` | 1.9.0 | Apache-2.0 | — |
| `androidx.media3` | `media3-database` | 1.9.0 | Apache-2.0 | — |
| `androidx.media3` | `media3-datasource` | 1.9.0 | Apache-2.0 | — |
| `androidx.media3` | `media3-datasource-okhttp` | 1.9.0 | Apache-2.0 | — |
| `androidx.media3` | `media3-decoder` | 1.9.0 | Apache-2.0 | — |
| `androidx.media3` | `media3-exoplayer` | 1.9.0 | Apache-2.0 | — |
| `androidx.media3` | `media3-exoplayer-hls` | 1.9.0 | Apache-2.0 | — |
| `androidx.media3` | `media3-extractor` | 1.9.0 | Apache-2.0 | — |
| `androidx.media3` | `media3-ui` | 1.9.0 | Apache-2.0 | — |
| `androidx.navigationevent` | `navigationevent` | 1.0.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/navigationevent#1.0.0 |
| `androidx.navigationevent` | `navigationevent-android` | 1.0.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/navigationevent#1.0.0 |
| `androidx.navigationevent` | `navigationevent-compose` | 1.0.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/navigationevent#1.0.0 |
| `androidx.navigationevent` | `navigationevent-compose-android` | 1.0.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/navigationevent#1.0.0 |
| `androidx.print` | `print` | 1.0.0 | Apache-2.0 | http://developer.android.com/tools/extras/support-library.html |
| `androidx.profileinstaller` | `profileinstaller` | 1.4.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/profileinstaller#1.4.1 |
| `androidx.recyclerview` | `recyclerview` | 1.3.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/recyclerview#1.3.0 |
| `androidx.savedstate` | `savedstate` | 1.4.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/savedstate#1.4.0 |
| `androidx.savedstate` | `savedstate-android` | 1.4.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/savedstate#1.4.0 |
| `androidx.savedstate` | `savedstate-compose` | 1.4.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/savedstate#1.4.0 |
| `androidx.savedstate` | `savedstate-compose-android` | 1.4.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/savedstate#1.4.0 |
| `androidx.savedstate` | `savedstate-ktx` | 1.4.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/savedstate#1.4.0 |
| `androidx.startup` | `startup-runtime` | 1.1.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/startup#1.1.1 |
| `androidx.tracing` | `tracing` | 1.2.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/tracing#1.2.0 |
| `androidx.transition` | `transition` | 1.6.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/transition#1.6.0 |
| `androidx.vectordrawable` | `vectordrawable` | 1.1.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.vectordrawable` | `vectordrawable-animated` | 1.1.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| `androidx.versionedparcelable` | `versionedparcelable` | 1.1.1 | Apache-2.0 | http://developer.android.com/tools/extras/support-library.html |
| `androidx.window` | `window` | 1.5.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/window#1.5.0 |
| `androidx.window` | `window-core` | 1.5.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/window#1.5.0 |
| `androidx.window` | `window-core-android` | 1.5.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/window#1.5.0 |
| `com.google.guava` | `failureaccess` | 1.0.2 | Apache-2.0 <sub>parent POM com.google.guava:guava-parent:26.0-android</sub> | https://github.com/google/guava |
| `com.google.guava` | `guava` | 33.3.1-android | Apache-2.0 <sub>parent POM com.google.guava:guava-parent:33.3.1-android</sub> | https://github.com/google/guava |
| `com.google.guava` | `listenablefuture` | 9999.0-empty-to-avoid-conflict-with-guava | Apache-2.0 <sub>parent POM com.google.guava:guava-parent:26.0-android</sub> | https://github.com/google/guava |
| `com.squareup.okhttp3` | `okhttp` | 4.12.0 | Apache-2.0 | https://square.github.io/okhttp/ |
| `com.squareup.okio` | `okio` | 3.9.1 | Apache-2.0 | https://github.com/square/okio/ |
| `com.squareup.okio` | `okio-jvm` | 3.9.1 | Apache-2.0 | https://github.com/square/okio/ |
| `io.coil-kt.coil3` | `coil` | 3.0.4 | Apache-2.0 | https://github.com/coil-kt/coil |
| `io.coil-kt.coil3` | `coil-android` | 3.0.4 | Apache-2.0 | https://github.com/coil-kt/coil |
| `io.coil-kt.coil3` | `coil-core` | 3.0.4 | Apache-2.0 | https://github.com/coil-kt/coil |
| `io.coil-kt.coil3` | `coil-core-android` | 3.0.4 | Apache-2.0 | https://github.com/coil-kt/coil |
| `io.coil-kt.coil3` | `coil-network-core` | 3.0.4 | Apache-2.0 | https://github.com/coil-kt/coil |
| `io.coil-kt.coil3` | `coil-network-core-android` | 3.0.4 | Apache-2.0 | https://github.com/coil-kt/coil |
| `io.coil-kt.coil3` | `coil-network-okhttp` | 3.0.4 | Apache-2.0 | https://github.com/coil-kt/coil |
| `io.coil-kt.coil3` | `coil-network-okhttp-jvm` | 3.0.4 | Apache-2.0 | https://github.com/coil-kt/coil |
| `org.jellyfin.media3` | `media3-ffmpeg-decoder` | 1.9.0+1 | GPL-3.0 | https://github.com/jellyfin/jellyfin-androidx-media |
| `org.jetbrains` | `annotations` | 23.0.0 | Apache-2.0 | https://github.com/JetBrains/java-annotations |
| `org.jetbrains.kotlin` | `kotlin-bom` (version platform, no code) | 1.8.0 | Apache-2.0 | https://kotlinlang.org/ |
| `org.jetbrains.kotlin` | `kotlin-stdlib` | 2.4.10 | Apache-2.0 | https://kotlinlang.org/ |
| `org.jetbrains.kotlin` | `kotlin-stdlib-jdk7` | 1.8.21 | Apache-2.0 | https://kotlinlang.org/ |
| `org.jetbrains.kotlin` | `kotlin-stdlib-jdk8` | 1.8.21 | Apache-2.0 | https://kotlinlang.org/ |
| `org.jetbrains.kotlinx` | `kotlinx-collections-immutable` | 0.4.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx.collections.immutable |
| `org.jetbrains.kotlinx` | `kotlinx-collections-immutable-jvm` | 0.4.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx.collections.immutable |
| `org.jetbrains.kotlinx` | `kotlinx-coroutines-android` | 1.10.2 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| `org.jetbrains.kotlinx` | `kotlinx-coroutines-bom` (version platform, no code) | 1.10.2 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| `org.jetbrains.kotlinx` | `kotlinx-coroutines-core` | 1.10.2 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| `org.jetbrains.kotlinx` | `kotlinx-coroutines-core-jvm` | 1.10.2 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| `org.jetbrains.kotlinx` | `kotlinx-serialization-bom` (version platform, no code) | 1.8.1 | Apache-2.0 | https://github.com/Kotlin/kotlinx.serialization |
| `org.jetbrains.kotlinx` | `kotlinx-serialization-core` | 1.8.1 | Apache-2.0 | https://github.com/Kotlin/kotlinx.serialization |
| `org.jetbrains.kotlinx` | `kotlinx-serialization-core-jvm` | 1.8.1 | Apache-2.0 | https://github.com/Kotlin/kotlinx.serialization |
| `org.jetbrains.kotlinx` | `kotlinx-serialization-json` | 1.8.1 | Apache-2.0 | https://github.com/Kotlin/kotlinx.serialization |
| `org.jetbrains.kotlinx` | `kotlinx-serialization-json-jvm` | 1.8.1 | Apache-2.0 | https://github.com/Kotlin/kotlinx.serialization |
| `org.jspecify` | `jspecify` | 1.0.0 | Apache-2.0 | http://jspecify.org/ |
<!-- THIRD-PARTY-TABLE:END -->

### Copyright holders (summary)

- `androidx.*` (including Compose and Media3): The Android Open Source Project.
- `org.jetbrains.kotlin*`, `org.jetbrains.kotlinx*`, `org.jetbrains:annotations`:
  JetBrains s.r.o. and Kotlin contributors.
- `com.squareup.okhttp3`, `com.squareup.okio`: Square, Inc.
- `io.coil-kt.coil3`: Coil Contributors.
- `com.google.guava`: The Guava Authors.
- `org.jspecify`: The JSpecify Authors.
- `org.jellyfin.media3`: the Jellyfin project, plus The Android Open Source
  Project (Media3 glue) and the FFmpeg developers (FFmpeg).

Apache-2.0 §4(d) requires redistributing any `NOTICE` files that these
artifacts contain. The release build's packaging rules exclude only
`META-INF/{AL2.0,LGPL2.1}`, `DebugProbesKt.bin` and
`kotlin-tooling-metadata.json`. Other `META-INF` licence and notice files from
dependencies are kept in the APK.
