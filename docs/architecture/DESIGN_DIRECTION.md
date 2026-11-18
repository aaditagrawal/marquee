# Product and UX direction

These rules came from living-room use on the reference TV. They are the reason
the UI behaves as it does. Visual tokens are in [../../design/DESIGN.md](../../design/DESIGN.md).

## 1. Instant beats animated

Focus, scrolling, sheets, the OSD and screen changes complete on the next frame
after key-down. Every held-D-pad repeat moves focus exactly once. Keys are never
queued behind work. The only motion is functional (spinners, progress bars,
the Skip countdown). See [MOTION_ALLOWLIST.md](MOTION_ALLOWLIST.md).

App work per key event should stay within a few milliseconds on a Cortex-A55,
and must not allocate heavily. The offline navigation tests enforce structural
budgets (recompositions, measures and placements per key) in
`app/src/test/resources/nav-budget.json`.

## 2. Nothing may look frozen

Any action that can take longer than about 120 ms responds within one frame and
shows progress until it finishes.

- **Navigation:** the next screen appears at once with any data already known
  (title, backdrop and logo from the focused card), plus BlurHash or skeleton
  placeholders. It is never blank, and the previous screen is never left frozen.
- **Buttons:** pressed state on key-down. Async actions (Play, toggles, sign-in,
  switching user) show an inline spinner. Watched and favourite toggles are
  optimistic and roll back on failure.
- **Player:** backdrop and progress ring, then a stream-mode chip, then the
  first frame. Rebuffering shows a ring after 400 ms. A fallback shows a
  non-blocking notice.
- **Errors:** a designed error state with Retry, never a raw exception. When
  the server is unreachable, the last cached content is still shown.

## 3. Home gets out of the way

- A billboard (backdrop, logo, metadata, overview, Play/Info) is shown only
  while focus is on the nav or the first row.
- Below that, the billboard and nav hide and a compact context strip appears.
  Back on any row returns to the first row and shows the billboard.
- The focused row sits at a fixed vertical anchor, and rows scroll under it.
- Shelves, in order: Continue Watching, Next Up, Recently Added, then
  data-driven origin/language and genre shelves built on the device from the
  library (see [DATA_API.md](DATA_API.md#home-and-catalogue)), then Top Rated
  and others. Shelves with fewer than five items are hidden.
- **Cold start** draws the real Home from a disk snapshot and disk-cached art,
  then refreshes without moving focus. No text field takes initial focus, so
  the on-screen keyboard never pops up on Home.

## 4. Detail pages fit the first screen

- The logo or title, metadata, synopsis and actions are fully visible, with
  initial focus on Play/Resume.
- A truncated synopsis opens a full sheet. Back closes it.
- Series pages show season tabs and an episode row with Next Up pre-focused,
  then Cast, More Like This and Extras.
- Moving down scrolls by whole sections. Moving back up restores the header
  completely.

## 5. Player controls are icons

- An icon-only control row (replay 10, play/pause, forward 10, next, audio,
  subtitles, settings, episodes). The focused icon shows its label.
- Text pills are used only for Skip Intro, Skip Credits and Next Episode.
- Centre toggles play/pause, Left/Right seek and accelerate when held, and
  Up/Down show the controls. Back hides the OSD first, then exits.

## 6. Built for weak hardware

Marquee was designed on a 4x Cortex-A55 / Mali-G31 / 2 GB TV. The defaults
reflect that hardware:

- opaque windows; pre-scaled RGB_565 bitmaps; scrims composited into bitmaps;
- no runtime blur, shadows or full-screen alpha layers;
- one shared OkHttp client with warm keep-alive connections;
- a hand-written baseline profile.

The debug build is labelled "Marquee DEV" and has a distinct banner, so it is
never confused with the optimised release build when comparing speed.
