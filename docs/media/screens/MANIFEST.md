# Screens

These images are **offline Robolectric renders of synthetic data**, copied unchanged from the
screenshot goldens in `app/src/test/screenshots/`. They are not captures from a TV.

- Rendered by `OfflineScreenshotTest` / `OfflinePlayerScreenshotTest` with Robolectric 4.17 native
  graphics at 1920×1080 (960×540 dp, xhdpi, television UI mode), using the production Compose
  screens and native player views.
- All titles, descriptions, people and ratings are invented by `tools/fixtures/generate.py`.
  Artwork is generated placeholder gradients drawn by the test fixture server, not real posters.
- They show layout and typography, not colour accuracy, HDR, video playback, or performance on a
  device.
- The device line in Settings reads "robolectric" (the simulated device name), and the version shown
  is the app version at the time the goldens were recorded.

| File | Screen | Golden |
| --- | --- | --- |
| `home.png` | Home with the Continue Watching shelf | `home-top.png` |
| `home-shelves.png` | Home scrolled to the Korean shelf (Hangul titles) | `home-nonlatin-row.png` |
| `movie-detail.png` | Movie detail with stream badges and track pickers | `detail-movie.png` |
| `series-detail.png` | Series detail, title fallback without logo art | `detail-series.png` |
| `series-episodes.png` | Episode rail, Next Up focus and cast | `detail-series-episodes.png` |
| `library.png` | Movies library, A–Z grid | `library-az.png` |
| `search.png` | Search results | `search-results.png` |
| `player-osd.png` | Native player controls (no video) | `player-movie-osd.png` |
| `settings.png` | Settings | `settings.png` |
| `settings-licences.png` | Settings > Open-source licences (bundled licence texts) | `settings-licences.png` |

To refresh: re-record goldens (see `docs/dev/TESTING.md`), then copy the files listed above.

Before/after evidence for the open-source licences row (also offline renders, not TV captures) is one
level up in `docs/media/`: `settings-licences-before.png` (end of the Settings list on the previous code,
recorded with the same test procedure before the change), `settings-licences-after.png` (golden
`settings-end.png`), `settings-licences-sheet.png` (golden `settings-licences.png`),
`settings-licences-text.png` (golden `settings-licence-text.png`) and `settings-licences-closeup.png`
(the same 1160×420 px region cropped from before and after at 1:1; the cyan outline marks the crop
boundary, not a UI element).
