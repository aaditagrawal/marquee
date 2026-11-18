# Synthetic test fixtures

`generate.py` writes every JSON fixture under `app/src/test/resources/` (except `nav-budget.json`,
which holds hand-maintained budget limits). The data describes an invented Jellyfin library: no
real films, shows, episodes, people, studios, servers, users or devices. Any resemblance of a
generated title or name to a real one is coincidental.

```sh
uv run --no-project python tools/fixtures/generate.py           # rewrite app/src/test/resources
uv run --no-project python tools/fixtures/generate.py --check   # fail if committed files differ
uv run --no-project python tools/fixtures/generate.py --seed 7 --out /tmp/fixtures   # explore another library
```

Python 3.10+, standard library only. Output is byte-for-byte deterministic for a given seed
(default `20261115`). The tests address named roles (below) and will fail with another seed only
where a test relies on a specific generated value; the committed files always use the default.

## What the tests rely on

| File | Used by | Shape kept |
| --- | --- | --- |
| `offline/catalog.json` | Offline server, screenshots, library/search navigation, `CatalogIndexTest`, large Home | 217 titles (172 movies, 45 series) in `ItemsResult` form with the same DTO fields as the server's `/Items` response |
| `offline/first-episode-audio.json` | `CatalogIndexTest`, large Home | series id → first-episode audio streams (CatalogIndex evidence for series) |
| `offline/home.json`, `resume.json`, `nextup.json`, `views.json` | Offline server, Home screenshots | cached `HomeSnapshot`: Continue Watching (7: 5 episodes, 2 movies), Next Up (5), Recently Added (20); 3 views |
| `offline/movie.json` | movie detail and player OSD screenshots | 27 people, 3 studios, tagline, Dolby Vision P8.1 + E-AC-3 Atmos + 3 SubRip (forced/default) |
| `offline/series*.json` | series detail, episodes, More dialog, detail layout/navigation tests | one season, 11 episodes, Next Up = last episode, 10 people, logo/thumb/primary art |
| `series-segments.json`, `series-next-episodes.json` | `SegmentPolicyTest` | 11 episodes, 11 intros, 10 credits ranges, next-episode chain ending at episode 11 |
| `home-navigation.json` | `HomeNavigationTest` (budget journey) | 8 rows × 12 cards, card fields only, no image tags (no image requests) |
| `player-audio-remux.json` | `StreamResolverTest`, `PlayerOsdTest` | item + PlaybackInfo for a TrueHD 7.1 Atmos / Dolby Vision 7.6 (EL) source whose audio is converted while video is copied |
| `player-direct-regression.json` | `StreamResolverTest` | 3 direct-play cases (DV P8.1 HDR10+ E-AC-3; H.264 High DTS 5.1 Hindi; H.264 E-AC-3 stereo Korean episode) |
| `player-playback-info.json` | `StreamResolverTest` | 12 cases = 4 sources × Direct/Remux/H264 requests (DV P8.1; DV P7.6 EL + TrueHD + 9 PGS; 4K HEVC SDR MOV; AV1 HDR10 + Opus + 9 SubRip + 22 attachments) |

## Named roles

Tests refer to these generated names. Changing them requires updating the tests.

- Detail series: **The Midnight Orchard Society** (`한밤의 과수원 모임`), 2026, rating 9.2; search term
  `orchard` matches only it and its episodes.
- Detail movie: **The Brass Cartographer**, 2025, rating 6.4.
- Home hero: an episode of **Driftwood** (episode rating 5.24, shown as ★ 5.2; single-line fallback title).
- CatalogIndex paths: Hangul original titles (*The Midnight Orchard Society*, *Paper Moon Laundry*,
  *Second Floor Romance*); kana original title (*Snowlight Express*); Japanese-audio film (*Ember
  Hound: The Movie*); Devanagari title + India with only an English-tagged track (*Dust and Thunder*,
  the mislabeled-encoder case); first-episode audio only (*Ledger of Small Fortunes* → Indian,
  *Driftwood* → English); production country (*Inspector Wren* → United Kingdom, *Ink Between
  Pages* → China).

## Catalog shape

Classification and shelf sizes match the library the Home shelves were designed against, so
`CatalogIndex.rows()` produces the same 14 catalog shelves with the same card counts: Recently Added
30, Korean 12, Indian 39, English 151 (incl. British 8), British 8, Top Rated 104, Action & Adventure
72, Sci-Fi & Fantasy 65, Comedy 56, Thriller & Mystery 51, Animation & Family 38, Crime 26, Romance
17, Documentary 14. Japanese (3), Chinese (2), French (2), Spanish (2), German (1) and Scandinavian
(1) stay below the five-title shelf minimum; 4 series have no origin evidence.

Stress features: 50 titles with invented Hangul/Devanagari/Tamil/kana original titles, 25 of them
also with non-Latin display names; two titles of 80+ characters; one movie without Primary art, 15 without logos, 20 without thumbs; 5 without
overviews; 10 unrated; 2 to 55 streams per source (sources with 30 and 52 subtitle tracks); HEVC/H.264/VP9/AV1 video in SDR, HDR10, HDR10+, HLG and Dolby Vision profiles 5, 7.6 and
8.1 plus `DOVIInvalid`; TrueHD Atmos, DTS-HD MA, DTS, E-AC-3 (Atmos), AC-3, AAC/HE-AAC, Opus and FLAC
audio; SubRip/PGS/ASS/DVD subtitles with forced, SDH, default and external flags; commentary tracks.

### Sizing

The private captures these replace totalled about 20.7 MB. The generated set is about 1.9 MB,
plus 0.26 MB of specs:

- Catalog titles carry 0–30 actors plus crew (mean ≈ 7 people, 30 % with portrait tags) instead of
  ≈ 22, and 0–6 chapters. Cards never show cast; the detail fixtures (`movie.json`, `series.json`, episodes) keep
  full casts.
- The former 12.9 MB "real cached Home" file is no longer stored. `OfflineFixtures.largeHome()` builds
  the same structure on demand: Continue Watching + Next Up + all catalog shelves (16 rows, 695 cards
  with the default seed, matching the capture's row and card counts), then round-trips it through the
  `HomeSnapshot` JSON format like a cache read. `HomeNavigationTest.largeCachedHome…` uses it.
- The former 1.9 MB catalog duplicate used by `CatalogIndexTest` is replaced by `offline/catalog.json`
  plus `offline/first-episode-audio.json`.
- `player-playback-info.json` omits the request's echoed `DeviceProfile` (not read by any test; the
  profile is built and checked from code).

## Field-level provenance

**Invented by the generator (seeded RNG):** `Name`, `OriginalTitle`, `SeriesName`, `SeasonName`,
episode names, `Overview`, `Taglines`, `People[].Name/Role`, `Studios[].Name`, `Genres` assignment,
`CommunityRating`, `CriticRating`, `OfficialRating`, `ProductionYear`, `PremiereDate`, `EndDate`,
`DateCreated`, `UserData`, `ChildCount`, `Status`, `ProductionLocations` (country names only),
catalog stream layouts, `Chapters` (`Chapter N`), `Size`, `Bitrate` of catalog sources.

**Derived from seed + counter (SHA-256, truncated):** every 32-hex `Id`, `SeriesId`, `SeasonId`,
`ParentId`, `MediaSource.Id`, `PlaySessionId`, `ETag`/`Tag`, image tags and person/studio ids.
Dashed GUID spellings in stream URLs are the same synthetic ids.

**Fixed obvious placeholders:** server `https://jellyfin.example`, `ServerId` `fixture-server`,
user `offline-home-tv`, device `marquee-fixture-device`, paths `/media/synthetic/<id>.<ext>`,
attachment names `attachment-<index>.ttf`.

**Generated artwork:** image tags are synthetic; `ImageBlurHashes` are valid BlurHash strings built
from random components (soft gradients, no source image). `OfflineServer` renders placeholder
bitmaps locally for every image request; tests never fetch artwork.

**Technical structure retained from real playback regressions** (`specs/*.spec.json`): for the three
player fixtures and the segment table, these values come from responses that originally exposed bugs,
with all identity removed by an allow-list extractor that is not part of this repository:

- Stream fields: `Codec`, `CodecTag`, `Profile`, `Level`, `BitDepth`, `PixelFormat`, `Width`,
  `Height`, `AspectRatio`, frame rates, `Color*`, `VideoRange`, `VideoRangeType`, `Dv*`, `Rpu/El/Bl`
  flags, `VideoDoViTitle`, `Channels`, `ChannelLayout`, `SampleRate`, `AudioSpatialFormat`,
  `Language`, `Index`, `IsDefault/IsForced/IsExternal/IsHearingImpaired/IsTextSubtitleStream`,
  `SupportsExternalStream`, `DeliveryMethod`, Jellyfin's localized flag labels, `TimeBase`, `RefFrames`.
- Source fields: container, `Supports*` flags, `Default*StreamIndex`, `TranscodingSubProtocol`,
  `TranscodingContainer`, and every `TranscodingUrl` query parameter except identity (DeviceId,
  MediaSourceId, PlaySessionId, Tag), which are re-substituted.
- Rounded: `RunTimeTicks` to the nearest minute; `Bitrate`/`BitRate` to three significant figures;
  `Size` recomputed from them.
- Dropped and regenerated: `Name`, `Path`, `ETag`, stream `Title`, and `DisplayTitle` (rebuilt from
  the technical fields in Jellyfin's format, because release names appeared in some originals).
- Segment table (`SEGMENTS` in `generate.py`): intro/credits start/end ticks and runtimes of an
  11-episode season.

## Changing fixtures

1. Edit `generate.py` (or a spec), run it, then `--check`.
2. Run `./gradlew :app:testDebugUnitTest`. Screenshot goldens change whenever visible text or
   art changes; re-record them as described in `docs/dev/TESTING.md` and review every diff image.
3. Never paste server responses, logs or captures from a personal library into this directory or
   into `app/src/test/resources`.
