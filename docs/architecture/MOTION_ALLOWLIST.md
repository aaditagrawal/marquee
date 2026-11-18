# Motion allowlist

Marquee's UI is **instant**. Focus and appearance change on key-down. Scroll
anchors reach their final position on the next layout frame. Sheets, menus, the
OSD, the Home billboard and screen changes appear and disappear in a single
frame. Images swap from placeholder to bitmap without a fade. No decorative
motion is permitted.

This rule came from user testing on a low-end TV. Tweened focus and scrolling
felt slow under a held D-pad, because every repeat restarted the animation.

Only these functional indicators may move:

- **Loading spinner:** the framework `CircularProgressIndicator` or a native
  `ProgressBar`.
- **Progress bars** driven by real work: playback position, buffering or
  request progress.
- **Skip Intro / Skip Credits countdown:** the remaining time, driven by the
  playback clock.

Auto-hide timers, artwork and detail dwell delays, and network deadlines are
timers, not transitions. When a timer fires, the displayed state swaps
immediately.

## Enforcement

[`tools/check-motion.sh`](../../tools/check-motion.sh) runs in CI. It scans
`app/src/main` for `animate*AsState`, `tween(`, `animateScroll`,
`AnimatedVisibility`, `Crossfade`, `Animatable(` and `animateTo(`. **There are
currently no exceptions**, including no whole-file or whole-package exceptions.
The existing functional indicators use framework components or clock updates
and do not need these APIs.

If a future functional indicator needs an exception:

1. list the exact expression here,
2. add a narrowly scoped exception to the script, and
3. get it reviewed.

A comment or a spinner's file name cannot exempt an unrelated animation.

The offline navigation tests (`HomeNavigationTest`, `ScreenNavigationTest`,
`PlayerOsdTest`) also check that each D-pad key leaves no animation clock
running. See [../dev/TESTING.md](../dev/TESTING.md).
