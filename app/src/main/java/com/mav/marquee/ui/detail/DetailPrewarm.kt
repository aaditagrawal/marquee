package com.mav.marquee.ui.detail

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.mav.marquee.player.PlaybackPrewarm

/** Keep launch and prewarm hints identical so the player's scoped cache can be reused. */
@androidx.annotation.OptIn(UnstableApi::class)
internal object DetailPrewarm {
    fun leave() = PlaybackPrewarm.leaveDetail()
    fun starting() = PlaybackPrewarm.playbackStarting()
    fun failed() = PlaybackPrewarm.playbackEnded()
    fun prewarm(context: Context, model: DetailModel, focused: Boolean? = null) {
        val item = model.playableItem ?: return
        val movie = item.id == model.item?.id
        if (focused != null) PlaybackPrewarm.focus(context, item.id, focused,
            if (item.userData?.played == true) 0 else item.resumeMs,
            mediaSourceId = if (movie && (item.mediaSources?.size ?: 0) > 1) model.source?.id else null,
            audioStreamIndex = if (movie) model.audioIndex else null,
            subtitleStreamIndex = if (movie) model.subtitleIndex else null)
        if (focused == false) return
        PlaybackPrewarm.prewarm(context, item.id,
            if (item.userData?.played == true) 0 else item.resumeMs,
            mediaSourceId = if (movie && (item.mediaSources?.size ?: 0) > 1) model.source?.id else null,
            audioStreamIndex = if (movie) model.audioIndex else null,
            subtitleStreamIndex = if (movie) model.subtitleIndex else null)
    }
}
