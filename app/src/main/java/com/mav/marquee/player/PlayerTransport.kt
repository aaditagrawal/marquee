package com.mav.marquee.player

import androidx.media3.common.Player

/** Paint the requested state synchronously, without waiting for an asynchronous isPlaying callback. */
internal object PlayerTransport {
    fun toggle(player: Player, feedback: (Boolean) -> Unit) {
        val ended = player.playbackState == Player.STATE_ENDED
        val playing = ended || !player.playWhenReady
        feedback(playing)
        if (ended) player.seekTo(0)
        if (playing) player.play() else player.pause()
    }
    fun setPlaying(player: Player, playing: Boolean, feedback: (Boolean) -> Unit) {
        feedback(playing)
        if (playing) player.play() else player.pause()
    }
}
