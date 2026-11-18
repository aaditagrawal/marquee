# Image request sizes and memory budgets

`data.ImageUrls.build` builds every image URL in this form:

```
{server}/Items/{id}/Images/{Type}[/{index}]?quality=Q&format=Jpg|Png&<size params>&tag={tag}
```

All requests use the same fixed pixel sizes, so client caches and Jellyfin's
server-side image cache both hit. The sizes assume a 2.0x (xhdpi) display:
cards are specified in dp and requested at twice that in pixels. Parameter order
only affects client cache keys, because Jellyfin builds its cache key from the
parameter values.

| Use | Image type | Size parameters | Decoded as |
|---|---|---|---|
| Poster card (128x192 dp) | Primary (an episode falls back to its series' Primary) | `fillWidth=256&fillHeight=384` | RGB_565 |
| Landscape card (208x117 dp) | Episode Primary, then Thumb, Backdrop/0, the parent's Thumb, the parent's Backdrop | `fillWidth=416&fillHeight=234` | RGB_565 |
| Hero / detail backdrop (full bleed) | Backdrop/0 (own, else parent) | `fillWidth=1280&fillHeight=720` | RGB_565 |
| Home context art (top right, collapsed Home) | Backdrop | `fillWidth=640&fillHeight=360` | RGB_565 |
| Logo (hero 340x72 dp, detail 360x80 dp) | Logo (own, else parent) | `quality=90&maxWidth=720&maxHeight=160` | ARGB_8888 (keeps alpha and aspect ratio) |
| Avatar (top nav) | `/Users/{id}/Images/Primary` | `fillWidth=64&fillHeight=64&quality=90` | RGB_565 |
| Login profile picture (96x96 dp) | `/Users/{id}/Images/Primary` | `fillWidth=192&fillHeight=192&quality=90` | RGB_565 |
| Person headshot | Primary | `fillWidth=192&fillHeight=192` | RGB_565 |
| Person biography portrait (140x210 dp) | Primary | `fillWidth=280&fillHeight=420` | RGB_565 |
| Square album/collection card (120x120 dp) | Primary | `fillWidth=240&fillHeight=240` | RGB_565 |

- **Quality.** The default is 85 (logos and avatars 90). The `imageQuality`
  preference changes it to 70 (`low`) or 92 (`high`). Dimensions never change,
  and each quality setting has its own URL and cache key.
- **Format.** Opaque art is requested as JPEG and alpha logos as PNG.
  Content-negotiated WebP decoded more slowly on the reference TV's Cortex-A55
  cores.
- **Decoding.** Each image is decoded at exactly the requested pixel box with
  `Precision.EXACT` and `Scale.FIT`. The 1280x720 backdrop is deliberately
  smaller than a 1080p screen, to save bandwidth and memory.
- **Tokens.** No URL contains a token. The shared OkHttp client adds the
  `Authorization` header, so URLs can be stored in the app's private disk cache
  without risk.

## Memory and disk budgets

These were tuned for a normal 192 MiB app heap on a 2 GB TV.

| Cache | Budget |
|---|---|
| Decoded bitmap LRU (`MarqueeImages`) | 40 MiB. Halved on `TRIM_MEMORY_RUNNING_LOW`; evicted on stronger trims. |
| Baked hero art (`HeroArt`) | 6 MiB: at most three backdrops with pre-composited scrims, plus the current and outgoing references. |
| Coil disk cache (`cache/img`) | 384 MiB |
| First-screen art (`cache/startup-art-v1`, `cache/hero-art-v3`) | A small, bounded set of JPEGs that let Home draw before any network request. |

At RGB_565, a poster is about 192 KiB, a landscape card about 190 KiB and a
1280x720 backdrop about 1.76 MiB. BlurHashes are decoded to tiny RGB_565
placeholders and drawn scaled up.

## Prefetch

- **Startup.** The first backdrop and logo come first, then the first four
  landscape cards or six posters. Rows below the fold and upcoming logos wait
  700 ms.
- **Focus movement.** Moving focus prefetches the next five cards and warms the
  next backdrop on disk only. The collapsed-Home context image waits for focus
  to rest for 350 ms, and a held D-pad cancels it.
- **Deduplication.** A visible load and a prefetch of the same URL share one
  decode and upload.
- **Display.** Images swap immediately, with no fade (see
  [MOTION_ALLOWLIST.md](MOTION_ALLOWLIST.md)).
