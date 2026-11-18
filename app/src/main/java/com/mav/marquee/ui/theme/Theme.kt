package com.mav.marquee.ui.theme

// Marquee design tokens. Source: maxio.com/chargify (chargify.com 301-redirects there), adapted to a
// dark 10-foot UI. See design/DESIGN.md for roles and component specs.
//
// Canvas: designed for 960 x 540 dp, which is what Android TV devices report for 1080p at 320 dpi
// (reference: Sony BRAVIA VU1), 4K UI at 640 dpi and 720p at tvdpi. Devices reporting a larger dp
// canvas (e.g. 1080p at 240 dpi) get extra space around the same layouts; see docs/dev/BUILD_NOTES.md.
// Depends only on compose ui / foundation (+ animation-core, which foundation pulls in transitively).

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.mav.marquee.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------------------------
// Font. The site uses Klim's "Die Grotesk B" (body/buttons) + "Founders Grotesk" (display); both
// are commercial. Closest open substitute: Hanken Grotesk (SIL OFL 1.1), two static weights only.
// To wire: copy design/fonts/hanken_grotesk_regular.ttf and hanken_grotesk_semibold.ttf to
// app/src/main/res/font/ (res names must be lowercase a-z, 0-9, underscore), then uncomment:
//
// (wired below)
// ---------------------------------------------------------------------------------------------
val MarqueeFont: FontFamily = FontFamily(
    Font(R.font.hanken_grotesk_regular, FontWeight.Normal),
    Font(R.font.hanken_grotesk_semibold, FontWeight.SemiBold),
)

object MarqueeColors {
    // Brand accents (exact site values)
    val MaxioBlue = Color(0xFF0042FF)        // --maxio-blue / --blue-dark: primary CTA fill, progress
    val MaxioBlueHover = Color(0xFF003BE5)   // --blue-dark-hover: pressed CTA
    val Sky = Color(0xFF65BFF2)              // .theme-blue: focus ring/glow on dark, links, selected chip
    val Purple = Color(0xFFDDA2FD)           // --purple: secondary accent (gradient tail, "new" badge)
    val PurpleDeep = Color(0xFF8000FF)       // hero radial gradient stop rgba(128,0,255)
    val Sage = Color(0xFFC1D6C0)             // --green: "played"/success
    val Red = Color(0xFFF02E4A)              // --red: errors, live, recording
    val Yellow = Color(0xFFFFE246)           // --yellow: community rating star
    val Amber = Color(0xFFFFAB41)            // --yellow-dark: warnings, critic rating

    // Dark surfaces derived from the site's warm "clay" neutral ramp
    val Background = Color(0xFF0E0D0D)       // app canvas (warm near-black, clay-12 lifted for LCD)
    val Surface = Color(0xFF1A1918)          // rows backdrop panels, nav rail
    val SurfaceRaised = Color(0xFF252423)    // clay-11: cards w/o image, dialogs, OSD panels
    val SurfaceHigh = Color(0xFF32302F)      // clay-10: chips, secondary button fill, skeleton base
    val SurfaceHigher = Color(0xFF3E3C3B)    // clay-9: skeleton shimmer highlight, focused chip fill
    val Outline = Color(0x29FFFFFF)          // --border-on-dark (white 16%)
    val OutlineSubtle = Color(0x1FFFFFFF)    // white 12%

    // Text (clay ramp)
    val TextPrimary = Color(0xFFF8F7F7)      // clay-2
    val TextSecondary = Color(0xFFAEA9A7)    // clay-5
    val TextTertiary = Color(0xFF8C898A)     // clay-6 (metadata, disabled >= 16sp only)
    val TextOnAccent = Color(0xFFFFFFFF)     // on MaxioBlue (~6.5:1)
    val TextOnLight = Color(0xFF000000)      // on white focused button / Sky chips

    // Focus
    val FocusRing = Color(0xFFFFFFFF)        // INSTANT: 3 dp white plus 2 dp outer Sky
    val FocusGlow = Sky                      // ... outer: brand blue (lightened to Sky for dark bg)
    val FocusGlowAlpha = 0.55f

    // Scrims
    val ScrimStrong = Color(0xE60E0D0D)
    val ScrimMedium = Color(0x990E0D0D)
    val OsdScrim = Color(0xB3000000)         // site overlay: linear-gradient(rgba(0,0,0,.7), ...)

    /** Hero backdrop: left-to-right fade so title/overview stay legible. */
    val HeroScrimHorizontal = Brush.horizontalGradient(
        0f to Background, 0.38f to Background.copy(alpha = 0.85f), 0.7f to Color.Transparent,
    )
    /** Hero backdrop: bottom fade into the first row. */
    val HeroScrimVertical = Brush.verticalGradient(
        0f to Color.Transparent, 0.6f to Background.copy(alpha = 0.6f), 1f to Background,
    )
    /** Ambient brand glow (site: radial rgba(0,66,255,.5) -> rgba(128,0,255,.5) -> transparent). */
    val BrandGlow = Brush.radialGradient(
        0f to MaxioBlue.copy(alpha = 0.35f), 0.5f to PurpleDeep.copy(alpha = 0.22f), 1f to Color.Transparent,
    )
    /** OSD bottom bar fade. */
    val OsdBottom = Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0xD9000000))
    val OsdTop = Brush.verticalGradient(0f to Color(0xB3000000), 1f to Color.Transparent)
}

private val Trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

object MarqueeType {
    // Site: headings weight 500, tracking -0.02em; eyebrows uppercase +0.08em. On TV we use 600 for
    // headings (the only bold file shipped) and keep body >= 14sp for 3 m viewing.
    val Display = TextStyle(fontFamily = MarqueeFont, fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.02).em, lineHeightStyle = Trim)
    val Headline = TextStyle(fontFamily = MarqueeFont, fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.02).em, lineHeightStyle = Trim)
    val Title = TextStyle(fontFamily = MarqueeFont, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 24.sp, letterSpacing = (-0.01).em)
    val RowTitle = TextStyle(fontFamily = MarqueeFont, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 24.sp, letterSpacing = (-0.01).em)
    val CardTitle = TextStyle(fontFamily = MarqueeFont, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 18.sp)
    val BodyLarge = TextStyle(fontFamily = MarqueeFont, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp)
    val Body = TextStyle(fontFamily = MarqueeFont, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp)
    val Label = TextStyle(fontFamily = MarqueeFont, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.01.em)
    val Meta = TextStyle(fontFamily = MarqueeFont, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 18.sp)
    val Eyebrow = TextStyle(fontFamily = MarqueeFont, fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.08.em) // render uppercase
    val Timecode = TextStyle(fontFamily = MarqueeFont, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 20.sp, fontFeatureSettings = "tnum")
}

object MarqueeDims {
    // Overscan-safe margins (5% of 960x540)
    val SafeH = 48.dp
    val SafeV = 27.dp

    // Spacing rhythm (site uses a 4/8 Tailwind grid; gutters 24/40)
    val Space2 = 2.dp
    val Space4 = 4.dp
    val Space8 = 8.dp
    val Space12 = 12.dp
    val Space16 = 16.dp
    val Space24 = 24.dp
    val Space32 = 32.dp
    val Space40 = 40.dp
    val Space56 = 56.dp

    // Radii (site: buttons/tags 4px, cards 1.5rem=24px, pills 9999px; scaled down for small TV cards)
    val RadiusSmall = 4.dp     // buttons, badges, progress bars - matches site control radius
    val RadiusCard = 8.dp      // posters / thumbs
    val RadiusPanel = 16.dp    // dialogs, OSD panels, detail info panel (site's rounded-3xl feel)
    val ShapeSmall = RoundedCornerShape(RadiusSmall)
    val ShapeCard = RoundedCornerShape(RadiusCard)
    val ShapePanel = RoundedCornerShape(RadiusPanel)
    val ShapePill = RoundedCornerShape(50)

    // Cards
    val PosterWidth = 128.dp           // 2:3 -> 180dp tall (240x360 px)
    val PosterHeight = 192.dp
    val LandscapeWidth = 208.dp        // 16:9 -> 117dp (continue watching, episodes)
    val LandscapeHeight = 117.dp
    val SquareSize = 132.dp            // music / collections
    val CardGap = 12.dp
    val RowGap = 32.dp                 // between rows (title-to-title rhythm)
    val RowTitleGap = 12.dp            // row title -> cards
    val ProgressBarHeight = 4.dp

    // Focus
    const val FocusScale = 1f
    val FocusRingWidth = 3.dp
    val FocusOuterRingWidth = 2.dp
    val FocusRingGap = 0.dp            // ring hugs card; no glow
    val FocusGlowRadius = 0.dp

    // Hero
    val HeroHeight = 300.dp            // ~56% of the 540dp screen on Home
    val HeroTextMaxWidth = 480.dp
    val DetailBackdropHeight = 540.dp  // full-bleed on detail page

    // Buttons
    val ButtonHeight = 44.dp
    val ButtonPaddingH = 20.dp
    val IconButtonSize = 44.dp
    val ChipHeight = 28.dp

    // OSD
    val OsdSeekbarHeight = 4.dp
    val OsdSeekbarFocusedHeight = 8.dp
    val OsdThumb = 14.dp
}

object MarqueeMotion {
    // Functional timers only. Focus, scrolling and artwork have no motion.
    const val OsdAutoHideMs = 4000
    const val HeroAutoAdvanceMs = 8000
    const val BackdropFocusDebounceMs = 300 // wait before swapping hero backdrop on fast scrolling
}
