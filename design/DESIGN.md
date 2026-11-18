# Marquee design system (10-foot UI, Android TV)

The tokens live in code at
`app/src/main/java/com/mav/marquee/ui/theme/Theme.kt` (`MarqueeColors`,
`MarqueeType`, `MarqueeDims`). The fonts are in `design/fonts/` and
`app/src/main/res/font/`. This document explains the choices behind those
tokens. If the two disagree, the code wins; please fix this file.

Related documents: [../docs/architecture/DESIGN_DIRECTION.md](../docs/architecture/DESIGN_DIRECTION.md)
(product rules) and [../docs/architecture/MOTION_ALLOWLIST.md](../docs/architecture/MOTION_ALLOWLIST.md)
(the "instant" motion policy).

## Principles

- **Flat, calm and editorial.** Colour comes from one strong blue, not from
  shadows or glows.
- **Instant.** Focus, scrolling, sheets and screen changes happen on the next
  frame after key-down, with no tweens (see "Motion" below).
- **Cheap to draw.** The design must be fast on a low-end Mali-G31 TV GPU:
  opaque surfaces, pre-baked scrims, and no runtime blur, elevation shadows or
  full-screen alpha layers.

## Provenance of the palette

The starting palette came from colour values on the public marketing website
of Maxio (formerly Chargify), read on 2026-10-12. The light brand theme there is
white, warm "clay" greys and one saturated blue `#0042FF`, with sky, lilac,
sage, red and yellow accents. Marquee used **only hex colour values** from it,
adapted to a dark TV canvas. It uses no logos, fonts, imagery or code from that
site. Marquee is not affiliated with Maxio. Some token names in `Theme.kt`
(`MaxioBlue`, `MaxioBlueHover`) record this origin. They are only identifiers.

The site's commercial typefaces (Klim's *Die Grotesk* and *Founders Grotesk*)
cannot be redistributed. Marquee uses **Hanken Grotesk** (SIL OFL 1.1) instead,
because it is an open grotesk with similar proportions.

### Dark adaptation

- The canvas is a warm near-black built from the clay ramp, not pure black.
  This avoids black crush on LCD panels.
- `#0042FF` is too dark to work as a glow on black. It is used as the fill for
  primary actions and progress bars.
- Sky `#65BFF2` is the outer focus colour, paired with a white inner ring.

## Canvas

Designed for a 1920x1080 display at density 320 (xhdpi). The layout canvas is
**960 x 540 dp**, and every size below is in dp or sp on that canvas.
Overscan-safe margins are **48 dp horizontal and 27 dp vertical**
(`MarqueeDims.SafeH`, `SafeV`). On other densities, Compose scales dp as usual.
Layouts have only been checked visually at 960x540 dp.

## Palette

| Token | Hex | Role |
|---|---|---|
| Background | `#0E0D0D` | App canvas |
| Surface | `#1A1918` | Nav, panels behind rows |
| SurfaceRaised | `#252423` | Dialogs, OSD panels, cards without an image |
| SurfaceHigh | `#32302F` | Chips, secondary button fill, skeleton base |
| SurfaceHigher | `#3E3C3B` | Focused chip fill, skeleton highlight |
| Outline | white 16% | Hairlines, unfocused secondary button border |
| TextPrimary | `#F8F7F7` | Titles, body |
| TextSecondary | `#AEA9A7` | Overview, metadata |
| TextTertiary | `#8C898A` | Runtime, year, disabled (at 13 sp or larger only) |
| **Primary blue** (`MaxioBlue`) | `#0042FF` | Primary action (Play), progress fill, played part of the seek bar |
| Primary blue pressed (`MaxioBlueHover`) | `#003BE5` | Pressed primary action |
| **Sky** | `#65BFF2` | Outer focus ring, selected chip, links |
| Purple / PurpleDeep | `#DDA2FD` / `#8000FF` | "New" badge, gradient tail |
| Sage | `#C1D6C0` | Watched check |
| Red | `#F02E4A` | Errors |
| Yellow / Amber | `#FFE246` / `#FFAB41` | Community rating star / critic rating, warnings |

White text on the primary blue has a contrast ratio of about 6.5:1.

## Type

**Hanken Grotesk** (SIL OFL 1.1). Two static weights are bundled, about 57 KB
each: Regular (400) and SemiBold (600). Headings use 600 because the extra
weight reads better from across a room.

| Style | sp / line, tracking | Weight | Use |
|---|---|---|---|
| Display | 40/44, -0.02em | 600 | Hero and detail title (logo art is used when Jellyfin has a `Logo` image) |
| Headline | 28/32, -0.02em | 600 | Screen titles, OSD title |
| Title | 20/24, -0.01em | 600 | Dialog titles, episode title on detail |
| RowTitle | 20/24, -0.01em | 600 | Row headers |
| CardTitle | 14/18 | 600 | Card captions |
| BodyLarge | 16/24 | 400 | Overview (at most 3 lines on Home, more on detail) |
| Body | 14/20 | 400 | General text |
| Label | 14/18, +0.01em | 600 | Buttons, chips |
| Meta | 14/18 | 400 | Year · runtime · rating line |
| Eyebrow | 11/16, +0.08em | 600 | Series / episode labels, badges. Card captions use sentence case. |
| Timecode | 16/20, tabular figures | 600 | OSD times |

Eyebrow (11 sp) is the only style below 12 sp. It is used for short labels
only, never for body text.


## Spacing, radii, elevation

- **Spacing:** a 4 dp grid with steps 2/4/8/12/16/24/32/40/56.
- **Cards:** posters are 128x192 dp (2:3). Landscape cards are 208x117 dp
  (16:9), used for Continue Watching, Next Up and episodes. Requested image
  sizes are listed in [../docs/architecture/IMAGE_SIZES.md](../docs/architecture/IMAGE_SIZES.md).
- **Radii:** 4 dp for buttons, badges and progress bars; 8 dp for cards;
  16 dp for panels and dialogs; full pills for chips and the top nav.
- **Elevation:** none. No drop shadows anywhere.
- **Focus:** a crisp **3 dp white ring** with a Sky outer edge, drawn in the
  draw phase on the card edge. Focused cards are **not scaled** and have no
  glow; both were removed under the instant-motion rule because they cost
  frames on the target GPU. Pills and buttons show focus with a white fill and
  black text.
- **Unfocused cards** stay at full opacity. Dimming them makes an LCD screen look
  muddy.

## Motion

Marquee's rule is **instant**: focus, scroll position, billboard show/hide,
sheets, the OSD and screen changes reach their final state on the next frame
after key-down. There are no tweens, springs, crossfades or enter/exit
transitions. Images swap from placeholder to bitmap in one step.

The only things that move are functional indicators: the loading spinner,
progress bars driven by real work, and the Skip Intro countdown. The exact
policy and the static check that enforces it are in
[../docs/architecture/MOTION_ALLOWLIST.md](../docs/architecture/MOTION_ALLOWLIST.md).

## Components

### Top navigation

A floating pill row at the top of Home, inside the safe margin: *Home*, *All
Movies*, *All Shows*, a search icon and the user/settings entry. The selected
item is marked; the focused item is a white pill with black text. The nav hides
when focus moves below the first row, and comes back on Back or when focus
returns to the top.

### Home billboard and context strip

- While focus is on the nav or the first row, a full-bleed backdrop billboard
  shows the focused item: logo or Display title, a Meta line, a 3-line overview,
  and Play/Info pill buttons.
- Below the first row the billboard hides. A compact context strip above the
  focused row shows title, metadata and a one-line synopsis. It may show a
  dimmed, pre-baked backdrop at the top right.
- The focused row sits at a fixed vertical anchor; rows move under it.
- Scrims are composited into the backdrop bitmap once, on a worker thread. Home
  draws no per-frame gradients.

### Rows and cards

- RowTitle header at the 48 dp margin, with a horizontal row and 48 dp content
  padding.
- **Poster card:** an 8 dp radius over SurfaceHigh until the image loads, then
  a BlurHash placeholder and an immediate swap to the bitmap. There is a 4 dp
  progress bar (primary blue on 24% white), a Sage watched check, and an
  unplayed-count badge.
- **Landscape card:** an Eyebrow line ("S1 · E3") and a CardTitle line under
  the image, plus the same progress bar.

### Buttons and chips

- **Pill buttons:** 44 dp high with Label text and an optional leading glyph.
  At rest, the primary button is a primary-blue fill with white text and the
  secondary is SurfaceHigh. Focused buttons have a white fill and black text.
  Async actions show an inline progress indicator inside the button.
- **Icon buttons:** 40 dp circles; white fill and black icon when focused.
- **Chips:** 28 dp pills. SurfaceHigh at rest, Sky fill when selected, white
  when focused.

### Detail page

- A full-bleed backdrop with a left-to-right scrim. The left column holds the
  logo or title, a Meta line, badges, a synopsis with a "More" sheet, and the
  actions: Play/Resume, version/audio/subtitle choices, trailers, watched and
  favourite.
- The whole header fits on the first screen, and the initial focus is on
  Play/Resume.
- Series pages show a season selector and a row of episode cards with the Next
  Up episode pre-focused. All titles then show Cast, More Like This and extras.

### Player OSD

- Native Android Views over a `SurfaceView`, not Compose.
- The bottom gradient shows the title, a thin progress bar and elapsed/remaining
  time. The control row is **icons only** (Material Symbols Rounded): replay 10,
  play/pause, forward 10, next episode, audio, subtitles, settings (quality,
  speed, diagnostics) and episodes. The focused icon gets a white circle and a
  small label underneath.
- Text pills are used only for *Skip Intro*, *Skip Credits* and *Next Episode*.
- D-pad centre toggles play/pause. Left/right seek ±10 s and accelerate when
  held, with a trickplay thumbnail above the bar. Up/Down show the controls.
  Back hides the OSD first, then exits.
- Auto-hide is a timer that hides the OSD instantly; there is no fade.

### Loading and errors

- BlurHash placeholders come first. Skeleton blocks in SurfaceHigh appear when
  no BlurHash exists.
- Nothing may look frozen: any action that can take longer than about 120 ms
  shows feedback in the next frame. Possible feedback includes a pressed state,
  an inline spinner or a status line.
- The player shows a backdrop and progress ring, then a stream-mode chip, then
  the first frame. A ring appears for rebuffering after 400 ms.
- Errors are shown in the design language with a Retry action. The app never
  shows raw exceptions.
