# Architecture

Marquee is one Android application module (`:app`, package `com.mav.marquee`).
It has two activities with deliberately different UI stacks:

- **`MainActivity`**: a single-activity **Jetpack Compose** browse UI covering
  sign-in, Home, libraries, search, person, detail and settings.
- **`player.PlayerActivity`**: a **View-based** playback screen. It uses AndroidX
  Media3 ExoPlayer rendering to a `SurfaceView`, with a native-View on-screen
  display.

```
                 ┌──────────────────────────── MainActivity (Compose) ───────────────────────────┐
  D-pad ───────▶ │ ui.nav  MarqueeNavHost / Navigator (one composed route at a time)              │
                 │ ui.login · ui.home · ui.library · ui.detail · ui.search · ui.person · ui.settings│
                 │ ui.components / ui.library.kit  (cards, focus, instant lists, sheets)          │
                 └──────────────┬──────────────────────────────┬─────────────────────────────────┘
                                │ suspend calls                │ RemoteImage / prefetch
                 ┌──────────────▼──────────────┐   ┌──────────▼─────────────────────────┐
                 │ data  (Session, Jellyfin-   │   │ image  (Coil 3 + OkHttp, BlurHash,  │
                 │ Repository, Home/Catalog    │   │ baked hero art, startup art cache) │
                 │ repositories, Models)       │   └──────────┬─────────────────────────┘
                 └──────────────┬──────────────┘              │
                                │  shared OkHttpClient (auth interceptor, keep-alive pool)
                                ▼
                         Jellyfin server (user-configured URL)
                                ▲
                 ┌──────────────┴─────────────────────────────────────────────────────────┐
  Intent ──────▶ │ player.PlayerActivity (Views + Media3)                                  │
  (itemId,       │ PlaybackCapabilities → DeviceProfile → PlaybackInfo → StreamResolver    │
   startMs, …)   │ MarqueeRenderersFactory · PlayerOsd · SegmentPolicy · Trickplay ·       │
                 │ PlaybackReports · PlaybackPrewarm (started from Detail)                 │
                 └─────────────────────────────────────────────────────────────────────────┘
```

## Why two UI stacks

The browse UI uses Compose because rows, grids and sheets are easier to build
and test there. The player uses plain Views over a `SurfaceView` for two
reasons:

- HDR10/HLG output needs the video plane. `SurfaceView` sends frames to the
  display's video plane, whereas `TextureView` composites through the GPU.
- Keeping Compose out of the playback screen avoids recomposition work and frees
  memory on 2 GB-class TVs.

The two activities communicate only through an explicit Intent and the shared
`data.Session` object. See [PLAYER_CONTRACT.md](PLAYER_CONTRACT.md).

## Packages

| Package | Responsibility |
|---|---|
| `com.mav.marquee` | `MarqueeApplication` (initialises Session, images and capability probing), `MainActivity`. |
| `data` | `Session` (server URL, token, user and device ID; shared OkHttp client), `JellyfinRepository` (suspend REST calls), `HomeRepository` (stale-while-revalidate Home snapshot), `CatalogRepository`/`CatalogIndex` (client-side origin and genre shelves), `ImageUrls`, `Models`. See [DATA_API.md](DATA_API.md). |
| `image` | `MarqueeImages` (Coil 3 setup, memory/disk caches, prefetch), `RemoteImage` (the only image composable), `BlurHash`, `HeroArt` (Home backdrops with pre-baked scrims), `StartupArt` (disk-only first-screen art). See [IMAGE_SIZES.md](IMAGE_SIZES.md). |
| `ui.nav` | Routes, the `Navigator`, the nav host and playback launch helpers. |
| `ui.home` | Home billboard, rows, context strip, pooled native cards. |
| `ui.library`, `ui.library.kit` | Library grids with sort/filter/A–Z, plus the shared "kit" of instant lists, fields, sheets and screen-state helpers used by library, detail, search, person, settings and login. |
| `ui.detail`, `ui.person`, `ui.search`, `ui.settings`, `ui.login` | The other screens. |
| `ui.components`, `ui.theme` | Cards, focus drawing, controls and design tokens. See [../../design/DESIGN.md](../../design/DESIGN.md). |
| `player` | Everything to do with playback. See [PLAYBACK_DECISIONS.md](PLAYBACK_DECISIONS.md). |

## Threading and performance rules

- **Main thread does UI only.** Network calls, JSON parsing, catalogue
  classification, image decoding and scrim baking run on IO or worker
  dispatchers. All `JellyfinRepository` functions are main-safe `suspend`
  functions.
- **Focus is drawn, not recomposed.** Focus state is read in draw lambdas.
  Lists use stable keys. Text colours that depend on focus use lambda colour
  providers. A focus move should not remeasure the card that has focus.
- **Instant motion.** Focus moves, scrolling and screen changes finish in the
  next frame. See [MOTION_ALLOWLIST.md](MOTION_ALLOWLIST.md).
- **Bounded memory.** Opaque art is decoded as RGB_565 at its exact display
  size. The image caches have fixed budgets, and memory-trim callbacks evict
  them.
- **Stale-while-revalidate.** Home draws from a disk snapshot and disk-cached
  art first, then refreshes from the network without moving focus.

These rules came from tuning on the reference device, a low-end Realtek and
Mali-G31 TV (see [TV_CAPABILITIES.md](TV_CAPABILITIES.md)). Other hardware has
not been measured.

## Playback in one paragraph

On start, `PlayerActivity` asks the device-capability layer what the device can
decode and display. On the reference device a narrowly gated quirk returns that
TV's measured profile; on other devices capabilities come from `MediaCodecList`
and `Display`, with a conservative fallback. From the result it builds a
Jellyfin `DeviceProfile` and calls `PlaybackInfo`. `StreamResolver` then turns
the answer into one of these, in order of preference:

1. direct play of the original file,
2. a remux with the video copied and only the audio converted,
3. an HEVC encode,
4. an H.264 1080p SDR encode.

If playback fails, the same steps are retried in that order before an error
is shown. Intro and credits come from Jellyfin media segments. Progress is
reported to `/Sessions/Playing*`. Details: [PLAYBACK_DECISIONS.md](PLAYBACK_DECISIONS.md).

## Documents in this folder

| Document | Contents |
|---|---|
| [PLAYBACK_DECISIONS.md](PLAYBACK_DECISIONS.md) | Stream negotiation, the fallback ladder, HDR and Dolby Vision handling, audio policy, subtitles, recovery, and why each choice was made. |
| [PLAYER_CONTRACT.md](PLAYER_CONTRACT.md) | How the browse UI launches and pre-warms playback. |
| [DATA_API.md](DATA_API.md) | Data-layer, image and navigation APIs. |
| [TV_CAPABILITIES.md](TV_CAPABILITIES.md) | Measured capabilities of the reference device, and how other devices are detected. |
| [PREFS.md](PREFS.md) | User preference keys. |
| [IMAGE_SIZES.md](IMAGE_SIZES.md) | Image request sizes, formats and memory budgets. |
| [MOTION_ALLOWLIST.md](MOTION_ALLOWLIST.md) | The instant-motion policy. |
| [DIAGNOSTICS.md](DIAGNOSTICS.md) | In-app diagnostics overlay, log markers, and the host-side capability report. |
| [DESIGN_DIRECTION.md](DESIGN_DIRECTION.md) | Product and UX rules. |

Build and test documentation is in [../dev/BUILD_NOTES.md](../dev/BUILD_NOTES.md)
and [../dev/TESTING.md](../dev/TESTING.md).
