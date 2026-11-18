# Player contract (browse UI ↔ playback)

The browse UI (`com.mav.marquee.ui`, `data`) and the player
(`com.mav.marquee.player`) communicate only through:

- an explicit Intent,
- the shared `data.Session`,
- the pre-warm API below.

## Launching playback

```kotlin
val intent = Intent().setClassName(context, "com.mav.marquee.player.PlayerActivity")
    .putExtra("itemId", item.id)            // String, required: a playable Movie / Episode / Video id
    .putExtra("startPositionMs", resumeMs)  // Long, optional: resume position (omit or 0 = start)
    // Optional per-title choices, only when they differ from preferences (see PREFS.md):
    .putExtra("mediaSourceId", sourceId)
    .putExtra("audioStreamIndex", audioIndex)
    .putExtra("subtitleStreamIndex", subtitleIndex)  // -1 = subtitles off
context.startActivity(intent)
```

Use `ui.nav.Playback.launch(...)` or `Playback.playOrResume(ctx, item)` rather
than building the Intent by hand.

- Never pass a Series or Season ID. The browse UI resolves it to an episode
  first (`playOrResume` does this).
- "Resume" passes `UserData.PlaybackPositionTicks / 10_000`. "Play from start"
  omits the extra or passes 0.
- `setClassName(context, …)` uses the context's package, so the debug build
  (`com.mav.marquee.debug`) and the release build (`com.mav.marquee`) both
  work.
- The release `PlayerActivity` is not exported. Debug builds export it so that
  tests on a development device can launch it.
- When the player finishes, the browse UI refreshes Continue Watching, Next Up
  and the item's UserData in `onResume`. The player reports
  Playing/Progress/Stopped, so those rows are correct.

## Shared session

`PlayerActivity` uses `Session.httpClient` for every request, including
`OkHttpDataSource.Factory(Session.httpClient)` for media. The auth interceptor
and connection pool carry over, and no credential is ever added to a URL. See
[DATA_API.md](DATA_API.md#session-datasession-object).

## Pre-warm API

Call this from Detail, with the same options the launch Intent will use:

```kotlin
PlaybackPrewarm.prewarm(context, itemId, startPositionMs = resumeMs,
    mediaSourceId = chosenSource, audioStreamIndex = chosenAudio,
    subtitleStreamIndex = chosenSubtitle)
```

It returns immediately and works in the background. It fetches the item and
PlaybackInfo, resolves a stream URL without credentials, warms the connection,
preloads a 960x540 RGB_565 backdrop and, for direct files, prefetches the
header and Cues ranges into a bounded RAM cache. It never starts a transcode.
Entries are matched against the account, preferences, position and all launch
options, and expire after 30 s.

Focus tracking:

```kotlin
PlaybackPrewarm.focus(context, itemId, focused = hasFocus,
    startPositionMs = resumeMs, mediaSourceId = chosenSource,
    audioStreamIndex = chosenAudio, subtitleStreamIndex = chosenSubtitle)
```

- Forward both focus gain and focus loss of Play/Resume.
- Re-send a gain when the playable episode or the options change while Play
  still has focus.
- After focus has rested for 600 ms, an audio-only conversion may open **one**
  speculative progressive stream: a 16,000,000-byte RAM FIFO with a 45 s
  maximum lifetime. Any mismatch cancels it.

Lifecycle calls:

- `PlaybackPrewarm.playbackStarting()` runs synchronously when Play is
  accepted, before `startActivity`. It reserves the warm stream through the
  activity transition.
- `PlaybackPrewarm.leaveDetail()` runs when Detail is disposed and on
  `ON_STOP`.
- A failed launch must release the reservation. `ui.detail.DetailPrewarm`
  handles all of this for Movie, Episode and Series.

## Lifecycle guarantees

- `onStop` cancels pending work, reports the last position, then calls
  `ExoPlayer.stop()` and `release()`. `onDestroy` is an idempotent fallback.
- Only one ExoPlayer exists at a time. `ActivePlayerOwner` releases the
  previous activity's player before a new one is built.
- Pre-warm creates no ExoPlayer and no `MediaCodec`, only HTTP buffers,
  Matroska extraction and seek maps.
- Media URLs, `Authorization` headers and tokens are never logged or persisted.
