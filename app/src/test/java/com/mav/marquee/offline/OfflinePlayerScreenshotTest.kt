package com.mav.marquee.offline

import android.content.Intent
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import org.robolectric.android.controller.ActivityController
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ImageView
import com.github.takahirom.roborazzi.captureRoboImage
import com.mav.marquee.player.PlayerActivity
import com.mav.marquee.player.SeekBarView
import com.mav.marquee.player.PlayerOsd
import com.mav.marquee.player.fmtTime
import com.mav.marquee.data.MarqueeJson
import kotlinx.serialization.json.jsonObject
import com.mav.marquee.data.Item
import com.mav.marquee.ui.theme.MarqueeColors
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual native player view tree, without onCreate, media codecs, stream/session APIs, or playback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "sw540dp-w960dp-h540dp-land-television-xhdpi-notouch", application = OfflineApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflinePlayerScreenshotTest {
    private lateinit var activity: PlayerActivity
    private lateinit var root: FrameLayout
    private lateinit var host: ActivityController<ComponentActivity>
    private fun call(name: String) = PlayerActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    @Suppress("UNCHECKED_CAST")
    private fun <T> view(name: String): T = PlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T
    private fun build(item: Item, episode: Boolean = false) {
        activity = Robolectric.buildActivity(PlayerActivity::class.java, Intent().putExtra("itemId",item.id).putExtra("osdLayer",false)).get()
        activity.setTheme(com.mav.marquee.R.style.Theme_Marquee_Player)
        call("buildSurface")
        call("buildOverlays")
        root = view("root")
        (root.parent as? ViewGroup)?.removeView(root)
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        host.get().setContentView(root)
        host.visible()
        root.dispatchWindowFocusChanged(true)
        view<View>("spinner").visibility = View.GONE
        view<View>("videoFrame").visibility = View.GONE
        view<View>("osd").visibility = View.VISIBLE
        view<ImageView>("backdrop").setBackgroundColor(0xFF162029.toInt())
        view<PlayerOsd>("osd").bindItem(MarqueeJson.parseToJsonElement(OfflineFixtures.itemJson(item)).jsonObject)
        val duration = item.runtimeMs.takeIf { it > 0 } ?: 3_600_000L
        val position = if (episode) 90_000L else duration/3
        view<SeekBarView>("seek").apply { durationMs = duration; positionMs = position; bufferedMs = (position + 30_000).coerceAtMost(duration); if (episode) segments = listOf(0L to 120_000L) }
        view<TextView>("timeLeft").text = fmtTime(position)
        view<TextView>("timeRight").text = "-" + fmtTime(duration - position)
        view<PlayerOsd>("osd").setNextEpisodeAvailable(episode && OfflineFixtures.episodes.indexOfFirst { it.id == item.id }
            .let { it >= 0 && it < OfflineFixtures.episodes.lastIndex })
        view<View>("playBtn").requestFocus()
        if (episode) view<View>("skipBtn").visibility = View.VISIBLE
    }
    private fun capture(name: String) {
        val exact = View.MeasureSpec.EXACTLY
        root.measure(View.MeasureSpec.makeMeasureSpec(1920,exact),View.MeasureSpec.makeMeasureSpec(1080,exact))
        root.layout(0,0,1920,1080)
        if (view<View>("osd").visibility == View.VISIBLE) {
            Assert.assertTrue(view<View>("playBtn").requestFocus())
            Assert.assertTrue(view<View>("playBtn").hasFocus())
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        val spinner = view<android.widget.ProgressBar>("spinner")
        if (spinner.visibility == View.VISIBLE) {
            println("OFFLINE_SPINNER width=${spinner.width} height=${spinner.height} attached=${spinner.isAttachedToWindow} visible=${spinner.isShown} drawable=${spinner.indeterminateDrawable?.javaClass?.simpleName}")
        }
        Assert.assertEquals(1920,root.width); Assert.assertEquals(1080,root.height)
        root.captureRoboImage(OfflinePaths.golden(name))
    }
    @After fun cleanup() {
        android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, 1f)
        if (::host.isInitialized) host.pause().stop().destroy()
    }
    @Test fun movieOsd() { build(OfflineFixtures.movie); capture("player-movie-osd") }
    @Test fun episodeOsdSkipIntro() { build(OfflineFixtures.episodes.first(), true); capture("player-episode-skip-intro") }
    @Test fun episodeOsdLastEpisode() { build(OfflineFixtures.nextEpisode, true); view<View>("skipBtn").visibility = View.GONE; capture("player-last-episode-osd") }
    @Test fun playerLoading() {
        // Native RenderThread animated vectors do not advance in software capture. Use Android
        // animation-scale fallback for a deterministic framework progress drawable, never a mock ring.
        android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, 0f)
        build(OfflineFixtures.movie)
        view<View>("osd").visibility = View.GONE
        view<View>("spinner").visibility = View.VISIBLE
        view<TextView>("banner").apply { visibility = View.VISIBLE; text = "Starting 4K HDR10 · Direct Play" }
        capture("player-loading")
    }
    @Test fun playerError() {
        build(OfflineFixtures.movie)
        view<View>("osd").visibility = View.GONE
        PlayerActivity::class.java.getDeclaredMethod("fail", String::class.java).apply { isAccessible = true }.invoke(activity, "Couldn't start this title. Check your connection and try again.")
        capture("player-error")
    }
}
