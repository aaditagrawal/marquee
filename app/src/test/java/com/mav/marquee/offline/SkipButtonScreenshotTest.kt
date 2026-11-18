package com.mav.marquee.offline

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.github.takahirom.roborazzi.captureRoboImage
import com.mav.marquee.player.PlayerActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real player skip button, resting and focused; focus must keep the label's horizontal padding. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "sw540dp-w960dp-h540dp-land-television-xhdpi-notouch", application = OfflineApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkipButtonScreenshotTest {
    @Test fun skipCreditsPadding() {
        val activity = Robolectric.buildActivity(PlayerActivity::class.java, Intent().putExtra("itemId", "x").putExtra("osdLayer", false)).get()
        activity.setTheme(com.mav.marquee.R.style.Theme_Marquee_Player)
        fun call(n: String) = PlayerActivity::class.java.getDeclaredMethod(n).apply { isAccessible = true }.invoke(activity)
        fun <T> field(n: String): T = @Suppress("UNCHECKED_CAST") (PlayerActivity::class.java.getDeclaredField(n).apply { isAccessible = true }.get(activity) as T)
        call("buildSurface"); call("buildOverlays")
        val root: FrameLayout = field("root")
        (root.parent as? ViewGroup)?.removeView(root)
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        host.get().setContentView(root); host.visible(); root.dispatchWindowFocusChanged(true)
        listOf("spinner", "videoFrame", "osd").forEach { field<View>(it).visibility = View.GONE }
        field<View>("backdrop").setBackgroundColor(0xFF6B4A2A.toInt())
        val btn: TextView = field("skipBtn")
        btn.text = "Skip Credits"; btn.visibility = View.VISIBLE
        val rest = btn.paddingLeft
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 1080)
        btn.captureRoboImage(OfflinePaths.golden("skip-credits-rest"))
        btn.requestFocus()
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 1080)
        root.captureRoboImage(OfflinePaths.golden("skip-credits-focused-screen"))
        println("SKIPPAD rest=$rest focused=${btn.paddingLeft},${btn.paddingRight} width=${btn.width} textW=${btn.paint.measureText("Skip Credits")}")
        assertTrue("focused padding ${btn.paddingLeft} != resting $rest", btn.paddingLeft == rest && btn.paddingRight == rest)
    }
}
