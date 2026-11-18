# Data, image and navigation APIs

This page covers the APIs in `com.mav.marquee.data`, `.image`, `.ui.nav` and
`.ui.components`.

## Session (`data.Session`, object)

| Member | Notes |
|---|---|
| `serverUrl` | Base URL with no trailing slash. `normalizeUrl` adds `http://` when the scheme is missing, and `:8096` when the port is missing. |
| `accessToken` | The user's token. **Never log it.** |
| `userId`, `userName` | The signed-in user. |
| `deviceId` | A stable random per-install ID, sent in the `Authorization` header. |
| `isLoggedIn` | True when the server URL, token and user are all set. |
| `httpClient` | The shared OkHttp client (keep-alive pool). It adds the MediaBrowser `Authorization` header to every request whose URL starts with `serverUrl` and has no header yet. |
| `authorizationHeader()` | `MediaBrowser Client="Marquee", Device=…, DeviceId=…, Version=…, Token=…` |
| `init(context)`, `setServer(url)`, `login(url, token, userId, name)`, `logout()` | Persistence is described in [../../PRIVACY.md](../../PRIVACY.md). |

`Session.init` runs in `MarqueeApplication.onCreate`, before any Activity starts.

## JellyfinRepository (`data.JellyfinRepository`, object)

All functions are main-safe `suspend` functions; IO and JSON work happen off the
main thread. Failures throw `IOException` or `HttpException(code)`. List calls
request `enableImageTypes=Primary,Backdrop,Logo,Thumb&imageTypeLimit=1`, and the
results carry BlurHashes in `Item.imageBlurHashes`.

| Call | Returns | Notes |
|---|---|---|
| `publicInfo(serverUrl)` | `PublicSystemInfo` | Unauthenticated probe used by the server step. |
| `publicUsers()` | `List<UserDto>` | Can be empty when the server hides users. Sign-in then needs a typed username or Quick Connect. |
| `authenticateByName(user, pw)` | `AuthResult` | Persists the session. The password is never stored. |
| `quickConnectEnabled()`, `quickConnectInitiate()`, `quickConnectState(secret)`, `quickConnectAuthenticate(secret)` | | Show the `code` large, keep the `secret` private, and poll about every 1.5 s. |
| `currentUser()` | `UserDto` | `/Users/Me` (avatar tag). |
| `views()` | `List<Item>` | The user's libraries (`collectionType` movies, tvshows, boxsets, music, …). |
| `items(ItemQuery)` / `itemIds(ItemQuery)` | `ItemsResult` | Generic `/Items` with parent, types, sort, paging, filters (played, unplayed, favourite, resumable), genres, years, person, search term and name prefix or A–Z bounds. `itemIds` returns IDs only, which is used for stable shuffles. |
| `resume(limit, parentId?)`, `nextUp(limit, seriesId?, includeResumable)`, `latest(parentId, limit, types?)` | `List<Item>` | Home shelves. |
| `item(id, fields)` | `Item` | Detail: people, media sources and streams, chapters, taglines, trailers. |
| `seasons(seriesId)`, `episodes(seriesId, seasonId?, startItemId?)`, `similar(id, limit)`, `specialFeatures(id)`, `localTrailers(id)`, `children(parentId, …)` | `List<Item>` | |
| `person(id)`, `itemsWithPerson(personId)`, `search(term, limit, types)`, `searchPeople(term)`, `genres(parentId, types?)`, `yearsInLibrary(parentId, types?)` | | |
| `setFavorite(id, bool)`, `setPlayed(id, bool)` | `UserData` | Detail applies these optimistically and rolls back on failure. |
| `logout()` | | Fire-and-forget server logout. The local session is always cleared. |

`Item` helpers: `isPlayable`, `resumeMs`, `runtimeMs`, `progress` (0..1) and
`episodeLabel` ("S1 · E3").

### Home and catalogue

- `HomeRepository`: `cached(ctx)` returns the disk snapshot; `fetch(ctx)` loads
  Continue Watching, Next Up and Latest per library in parallel, and persists
  the result. A lean first-screen snapshot is stored separately, so startup can
  draw without parsing everything.
- `CatalogIndex.classify/rows` are pure functions that are safe to call on a
  worker thread. They build origin and language shelves (for example "Korean
  Shows & Movies", "Hindi & Indian Cinema", "British", "Japanese & Anime") and
  genre shelves from audio languages, original-title script, production
  locations and studios. Rows with fewer than five items are hidden.
- `CatalogRepository` persists the complete index for each account
  (server + user), with lazily loaded first-episode audio hints.

## Images (`data.ImageUrls`, `image.*`)

- URL builders take pixel sizes: `ImageUrls.poster`, `landscape`, `backdrop`,
  `logo`, `person` and `user`. The standard sizes are listed in
  [IMAGE_SIZES.md](IMAGE_SIZES.md). Use them, so server-side cache pre-warming
  hits.
- `image.RemoteImage(url, modifier, blurHash, opaque, fit, onDrawn)` is the only
  image composable. Its state lives in the draw phase only: a finished load
  triggers a redraw, never a recomposition. Memory hits draw on the first
  frame, there is a BlurHash placeholder, and the bitmap swaps in immediately.
  Callers clip it themselves.
- `image.MarqueeImages`: `prefetch(urls, opaque)`, `warmDisk(urls)`,
  `cached(url)`, `load(url)` and the suspend `clearCache()`.
- `image.HeroArt`: Home-only backdrops with the scrims composited once on a
  worker thread.

## Shared components (`ui.components`, `ui.library.kit`)

- `PosterCard`, `LandscapeCard`, `PillButton`, `IconCircleButton`, `Chip`,
  `RowHeader`, `SkeletonBlock`, `ProgressBar` and `GlyphIcon`.
- `Modifier.focusCard(...)` draws a crisp ring without scaling.
- The kit provides `InstantList`, `TvField`, `OptionSheet`, `TextSheet`,
  `NetImage` and the screen-state and focus-memory helpers.

Rules for these components:

- no shadows, elevation or blur;
- read binary focus state in draw lambdas;
- give lazy lists stable keys;
- pass lambda colours for text that changes with focus.

## Navigation (`ui.nav`)

- `Route`: `Login`, `Home`, `Library(viewId, title, collectionType)`,
  `Detail(itemId)`, `Person(personId)`, `Search`, `Settings`.
- `LocalNavigator.current` provides `push`, `pop`, `reset`, `home` and
  `open(item)`. `open` sends folders to Library, people to Person and
  everything else to Detail.
- `MarqueeNavHost` handles Back and composes only the top route.
- Playback is started with `ui.nav.Playback.launch(ctx, itemId, startPositionMs)`
  or `Playback.playOrResume(ctx, item)`. `playOrResume` resolves a Series or
  Season to its next-up episode. See [PLAYER_CONTRACT.md](PLAYER_CONTRACT.md).
