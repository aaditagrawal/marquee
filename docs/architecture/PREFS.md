# User preferences

Settings writes these preferences; the player and the image code read them.

- **Storage:** Android SharedPreferences file **`marquee_prefs`** (private
  mode).
- **Typed accessor:** `com.mav.marquee.ui.settings.PlaybackPrefs.get(context)`.
  The key constants are on its companion, for example `PlaybackPrefs.K_MAX_BITRATE`.
- **Player view:** `player.PlayerPrefs` reads the same file.
- **Caching:** code that caches values should register an
  `OnSharedPreferenceChangeListener`.

| Key | Type | Default | Values and meaning |
|---|---|---|---|
| `maxBitrateBps` | Int | `0` | Maximum streaming bitrate in bits/s. `0` means Auto (no limit; the app sends an explicit maximum, see [PLAYBACK_DECISIONS.md](PLAYBACK_DECISIONS.md#3-bitrate)). Settings offers 0, 120M, 80M, 60M, 40M, 20M, 15M, 10M, 8M, 4M and 2M. |
| `audioLanguage` | String | `""` | ISO 639-2 code (`eng`, `hin`, `jpn`, …). `""` means the file's default track. |
| `subtitleLanguage` | String | `""` | ISO 639-2 code. `""` means no preference. |
| `subtitleMode` | String | `Default` | Jellyfin `SubtitlePlaybackMode`: `Default`, `Always`, `OnlyForced`, `None` or `Smart`. |
| `skipIntro` | String | `button` | `auto` (skip silently), `button` (show "Skip intro") or `off`. Uses Jellyfin Intro media segments. |
| `skipCredits` | String | `button` | `auto`, `button` or `off`. Uses the Outro segment; `auto` can go straight to the next episode. |
| `autoplayNext` | Boolean | `true` | Start the next episode when one ends, after a countdown. |
| `diagnosticsOverlay` | Boolean | `false` | Show the player's statistics overlay. See [DIAGNOSTICS.md](DIAGNOSTICS.md). |
| `imageQuality` | String | `standard` | `low`, `standard` or `high`: JPEG quality 70, 85 or 92 for image requests. Request dimensions do not change. |
| `audioPassthrough` | Boolean | `false` | Set from the player's settings menu. Allows compressed passthrough, but only on a verified HDMI ARC/eARC route. See [PLAYBACK_DECISIONS.md](PLAYBACK_DECISIONS.md#5-audio). |

Settings also has **Clear image cache**. It calls the suspend function
`MarqueeImages.clearCache()`, which clears the shared bitmap, BlurHash, hero and
Coil disk caches.

Other preference files are documented in [../../PRIVACY.md](../../PRIVACY.md):
`marquee_session` holds the session and `marquee_search` holds recent searches.

## Player launch extras from Detail

Besides `itemId` and `startPositionMs`, Detail passes the user's per-title
choices when they differ from the defaults (see [PLAYER_CONTRACT.md](PLAYER_CONTRACT.md)):

| Extra | Type | Meaning |
|---|---|---|
| `mediaSourceId` | String | The chosen version, when an item has several media sources. |
| `audioStreamIndex` | Int | `MediaStream.Index` of the chosen audio track. |
| `subtitleStreamIndex` | Int | `MediaStream.Index` of the chosen subtitle track; `-1` means subtitles off. |

When an extra is absent, the player falls back to the preferences above.
