package com.mav.marquee.player

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.mav.marquee.R
import kotlinx.serialization.json.JsonObject

internal enum class OsdAction { Replay, PlayPause, Forward, NextEpisode, Audio, Subtitles, Settings, Episodes }

/** Native OSD over SurfaceView. Fixed control geometry keeps focus independent of playback/network work. */
internal class PlayerOsd(ctx: Context, onAction: (OsdAction) -> Unit) : LinearLayout(ctx) {
    val title = ctx.text(20f, Tok.TextPrimary, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    val subtitle = ctx.text(13f, Tok.TextSecondary).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, ctx.dp(6), 0, ctx.dp(14)) }
    val seek = SeekBarView(ctx).apply { setPadding(ctx.dp(2), 0, ctx.dp(2), 0) }
    val timeLeft = ctx.text(12f, Tok.TextPrimary, true)
    val timeRight = ctx.text(12f, Tok.TextSecondary, true).apply { gravity = Gravity.END }
    val buttons = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
    val transport = LinearLayout(ctx).apply { orientation = HORIZONTAL; clipChildren = false }
    val options = LinearLayout(ctx).apply { orientation = HORIZONTAL; clipChildren = false }
    val play = OsdIconButton(ctx, "Pause", R.drawable.player_pause)
    private var isEpisode = false
    val next = OsdIconButton(ctx, "Next Episode", R.drawable.player_skip_next).apply { visibility = GONE }
    val episodes = OsdIconButton(ctx, "Episodes", R.drawable.player_video_library).apply { visibility = GONE }
    init {
        orientation = VERTICAL
        background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0xE6000000.toInt(), 0x99000000.toInt(), 0x00000000))
        setPadding(ctx.dp(48), ctx.dp(56), ctx.dp(48), ctx.dp(27))
        visibility = GONE
        addView(title); addView(subtitle)
        addView(seek, LayoutParams(-1, ctx.dp(20)))
        val times = LinearLayout(ctx).apply { orientation = HORIZONTAL; setPadding(0, ctx.dp(6), 0, ctx.dp(14)) }
        times.addView(timeLeft, LayoutParams(0, -2, 1f)); times.addView(timeRight, LayoutParams(0, -2, 1f))
        addView(times)
        fun control(group: LinearLayout, button: OsdIconButton, action: OsdAction) {
            button.id = View.generateViewId()
            button.setOnClickListener { onAction(action) }
            group.addView(button, LayoutParams(ctx.dp(72), ctx.dp(72)))
        }
        control(transport, OsdIconButton(ctx, "Replay 10", R.drawable.player_replay_10), OsdAction.Replay)
        control(transport, play, OsdAction.PlayPause)
        control(transport, OsdIconButton(ctx, "Forward 10", R.drawable.player_forward_10), OsdAction.Forward)
        control(transport, next, OsdAction.NextEpisode)
        control(options, OsdIconButton(ctx, "Audio", R.drawable.player_volume_up), OsdAction.Audio)
        control(options, OsdIconButton(ctx, "Subtitles", R.drawable.player_closed_caption), OsdAction.Subtitles)
        control(options, OsdIconButton(ctx, "Settings", R.drawable.player_settings), OsdAction.Settings)
        control(options, episodes, OsdAction.Episodes)
        buttons.addView(transport, LayoutParams(-2, ctx.dp(72)))
        buttons.addView(View(ctx), LayoutParams(0, 1, 1f))
        buttons.addView(options, LayoutParams(-2, ctx.dp(72)))
        addView(buttons, LayoutParams(-1, ctx.dp(72)))
        updateHorizontalFocus()
    }
    fun bindItem(item: JsonObject) {
        val episode = item.str("Type") == "Episode"
        isEpisode = episode
        setNextEpisodeAvailable(false)
        setControlVisibility(episodes, episode && !item.str("SeriesId").isNullOrBlank())
        updateHorizontalFocus()
        title.text = if (episode) item.str("SeriesName") ?: item.str("Name") else item.str("Name")
        subtitle.text = if (episode) {
            val name = item.str("Name")?.trim()?.takeIf { it.isNotBlank() && !GENERIC_EPISODE_NAME.matches(it) }
            listOfNotNull("S${item.int("ParentIndexNumber") ?: "?"} · E${item.int("IndexNumber") ?: "?"}", name).joinToString(" · ")
        } else listOfNotNull(item.int("ProductionYear")?.toString(), item.str("OfficialRating")).joinToString(" · ")
    }
    fun setNextEpisodeAvailable(available: Boolean) {
        setControlVisibility(next, isEpisode && available)
        updateHorizontalFocus()
    }
    private fun setControlVisibility(button: OsdIconButton, visible: Boolean) {
        val restoreFocus = button.hasFocus() && !visible
        button.visibility = if (visible) VISIBLE else GONE
        if (restoreFocus) play.requestFocus()
    }
    private fun updateHorizontalFocus() {
        val controls = listOf(transport, options).flatMap { group ->
            (0 until group.childCount).map { group.getChildAt(it) }
        }.filter { it.visibility == VISIBLE }
        controls.forEachIndexed { index, button ->
            button.nextFocusLeftId = controls.getOrElse(index - 1) { button }.id
            button.nextFocusRightId = controls.getOrElse(index + 1) { button }.id
        }
    }
    private companion object {
        val GENERIC_EPISODE_NAME = Regex("Episode \\d+", RegexOption.IGNORE_CASE)
    }
    fun showPlaying(playing: Boolean) {
        play.label = if (playing) "Pause" else "Play"
        play.iconRes = if (playing) R.drawable.player_pause else R.drawable.player_play_arrow
    }
}

/** Coordinates are in root space. Translation retains fractional text sizing and PGS bitmap size. */
internal fun captionTranslation(videoBottom: Int, panelTop: Int, visible: Boolean, gap: Int): Float =
    if (visible && videoBottom > panelTop) -(videoBottom - panelTop + gap).toFloat() else 0f
