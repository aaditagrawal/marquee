package com.mav.marquee.ui.nav

import android.content.Context
import android.content.Intent
import android.util.Log
import com.mav.marquee.data.Item
import com.mav.marquee.data.JellyfinRepository

/** Launches PlayerActivity per docs/architecture/PLAYER_CONTRACT.md (by class name, so we compile without the player). */
object Playback {
    fun launch(context: Context, itemId: String, startPositionMs: Long = 0L): Boolean {
        val intent = Intent().setClassName(context, "com.mav.marquee.player.PlayerActivity").putExtra("itemId", itemId)
        if (startPositionMs > 0) intent.putExtra("startPositionMs", startPositionMs)
        return try { context.startActivity(intent); true } catch (e: Exception) {
            Log.w("Marquee", "PlayerActivity unavailable: ${e.javaClass.simpleName}"); false
        }
    }

    /** Resolves what "Play/Continue" means for [item]: Series/Season -> next-up (or first) episode. Returns false if nothing playable. */
    suspend fun playOrResume(context: Context, item: Item): Boolean {
        if (item.isPlayable) return launch(context, item.id, item.resumeMs)
        val seriesId = when (item.type) { "Series" -> item.id; "Season" -> item.seriesId; else -> null } ?: return false
        val ep = runCatching { JellyfinRepository.nextUp(1, seriesId, includeResumable = true).firstOrNull() }.getOrNull()
            ?: runCatching { JellyfinRepository.episodes(seriesId, if (item.type == "Season") item.id else null).firstOrNull() }.getOrNull()
            ?: return false
        return launch(context, ep.id, ep.resumeMs)
    }
}
