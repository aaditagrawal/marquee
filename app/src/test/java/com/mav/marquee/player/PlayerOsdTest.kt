package com.mav.marquee.player

import java.io.File
import java.lang.management.ManagementFactory
import android.os.SystemClock
import org.robolectric.shadows.ShadowLooper
import kotlinx.serialization.json.*
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.cancel
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.ui.SubtitleView
import com.mav.marquee.R
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-xhdpi")
class PlayerOsdTest {
    // Expectations below are the measured reference TV's profile (see DeviceProfileBaselineTest).
    @org.junit.Before fun referenceDevice() { PlaybackCapabilities.override = ReferenceDevices.BRAVIA_VU1 }
    @org.junit.After fun clearReferenceDevice() { PlaybackCapabilities.override = null }

    private fun fixture(onAction: (OsdAction) -> Unit = {}): Pair<FrameLayout, PlayerOsd> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val osd = PlayerOsd(activity, onAction).apply { visibility = View.VISIBLE }
        root.addView(osd, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        activity.setContentView(root)
        layout(root)
        return root to osd
    }
    private fun layout(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 1080)
    }
    @Test fun movieHasNoEpisodeControlsAndLastEpisodeHasNoNext() {
        val (root, osd) = fixture()
        osd.bindItem(buildJsonObject { put("Type", "Movie"); put("Name", "Signal Over Tidewater"); put("ProductionYear", 2026) })
        layout(root)
        assertEquals(View.GONE, osd.episodes.visibility)
        assertEquals("Signal Over Tidewater", osd.title.text.toString())
        assertEquals(3, (0 until osd.options.childCount).count { osd.options.getChildAt(it).visibility == View.VISIBLE })
        osd.setNextEpisodeAvailable(true)
        assertEquals(View.GONE, osd.next.visibility)
        osd.bindItem(buildJsonObject { put("Type", "Episode"); put("SeriesId", "orchard"); put("SeriesName", "The Midnight Orchard Society"); put("Name", "Episode 11"); put("ParentIndexNumber", 1); put("IndexNumber", 11) })
        layout(root)
        assertEquals(View.VISIBLE, osd.episodes.visibility)
        assertEquals(4, (0 until osd.options.childCount).count { osd.options.getChildAt(it).visibility == View.VISIBLE })
        assertEquals("The Midnight Orchard Society", osd.title.text.toString())
        assertEquals("S1 · E11", osd.subtitle.text.toString())
        osd.bindItem(buildJsonObject { put("Type", "Episode"); put("SeriesId", ""); put("Name", "An orphan episode") })
        assertEquals(View.GONE, osd.episodes.visibility)
    }
    @Test fun episodeSubtitleOmitsOnlyTrimmedCaseInsensitiveGenericNames() {
        val (_, osd) = fixture()
        for ((name, expected) in listOf(
            "Episode 1" to "S1 · E1", "  ePiSoDe 123  " to "S1 · E1",
            "  The Beginning  " to "S1 · E1 · The Beginning",
            "Episode 1: The Beginning" to "S1 · E1 · Episode 1: The Beginning",
            "Episode One" to "S1 · E1 · Episode One", " " to "S1 · E1"
        )) {
            osd.bindItem(buildJsonObject { put("Type", "Episode"); put("Name", name); put("ParentIndexNumber", 1); put("IndexNumber", 1) })
            assertEquals(name, expected, osd.subtitle.text.toString())
        }
    }
    @Test fun horizontalFocusTraversesBothClustersAndSkipsConditionalControls() {
        val actions = mutableListOf<OsdAction>()
        val (root, osd) = fixture(actions::add)
        val episode = buildJsonObject { put("Type", "Episode"); put("SeriesId", "orchard") }
        for (hasNext in listOf(true, false)) {
            osd.bindItem(episode)
            osd.setNextEpisodeAvailable(hasNext)
            layout(root)
            val controls = listOf(osd.transport, osd.options).flatMap { group ->
                (0 until group.childCount).map { group.getChildAt(it) }
            }.filter { it.visibility == View.VISIBLE }
            assertEquals(if (hasNext) 8 else 7, controls.size)
            for ((left, right) in controls.zipWithNext()) {
                assertTrue(left.requestFocus())
                assertSame(right, left.focusSearch(View.FOCUS_RIGHT))
                assertTrue(right.requestFocus())
                assertSame(left, right.focusSearch(View.FOCUS_LEFT))
            }
        }
        osd.setNextEpisodeAvailable(true)
        osd.next.requestFocus()
        assertEquals(View.VISIBLE, (osd.next.getChildAt(1) as TextView).visibility)
        osd.next.performClick()
        assertEquals(listOf(OsdAction.NextEpisode), actions)
        osd.setNextEpisodeAvailable(false)
        assertTrue(osd.play.isFocused)
        osd.bindItem(buildJsonObject { put("Type", "Movie") })
        layout(root)
        val settings = osd.options.getChildAt(2)
        assertSame(settings, osd.options.getChildAt(1).focusSearch(View.FOCUS_RIGHT))
        assertEquals(View.GONE, osd.next.visibility)
        assertEquals(View.GONE, osd.episodes.visibility)
    }
    @Test fun controlsFormTwoClustersAndTitleIsAboveProgress() {
        val (root, osd) = fixture()
        osd.bindItem(buildJsonObject { put("Type", "Episode"); put("SeriesId", "orchard") })
        layout(root)
        osd.setNextEpisodeAvailable(true)
        layout(root)
        val ctx = root.context
        assertEquals(listOf("Replay 10", "Pause", "Forward 10", "Next Episode"), (0 until osd.transport.childCount).map { osd.transport.getChildAt(it).contentDescription.toString() })
        assertEquals(listOf("Audio", "Subtitles", "Settings", "Episodes"), (0 until osd.options.childCount).map { osd.options.getChildAt(it).contentDescription.toString() })
        assertTrue(osd.title.bottom <= osd.subtitle.top)
        assertTrue(osd.subtitle.bottom <= osd.seek.top)
        assertEquals(ctx.dp(72), osd.buttons.height)
        assertEquals(0, osd.transport.left)
        assertEquals(osd.buttons.width, osd.options.right)
        assertTrue(osd.options.left - osd.transport.right >= ctx.dp(72))
        for (group in listOf(osd.transport, osd.options)) for (i in 0 until group.childCount) {
            val button = group.getChildAt(i) as OsdIconButton
            val glyph = button.getChildAt(0) as ImageView
            assertEquals(ctx.dp(72), button.height)
            assertEquals(ctx.dp(48), glyph.width)
            assertEquals(ctx.dp(48), glyph.height)
            assertEquals(ctx.dp(28), glyph.width - glyph.paddingLeft - glyph.paddingRight)
            assertEquals(ctx.dp(28), glyph.height - glyph.paddingTop - glyph.paddingBottom)
        }
        assertEquals(ctx.dp(48), osd.paddingLeft)
        assertEquals(ctx.dp(48), osd.paddingRight)
    }
    @Test fun focusAndKeyDownGiveSynchronousFeedbackWithoutWaitingForAFrame() {
        val actions = mutableListOf<OsdAction>()
        val (_, osd) = fixture(actions::add)
        osd.play.requestFocus()
        val tooltip = osd.play.getChildAt(1) as TextView
        assertEquals(View.VISIBLE, tooltip.visibility)
        assertEquals(View.INVISIBLE, (osd.transport.getChildAt(0) as OsdIconButton).getChildAt(1).visibility)
        val glyph = osd.play.getChildAt(0) as ImageView
        osd.play.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(listOf(OsdAction.PlayPause), actions)
        assertTrue(osd.play.isPressed)
        assertEquals(1f, glyph.scaleX)
        osd.play.dispatchKeyEvent(KeyEvent(0, 1, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1))
        assertEquals(1, actions.size)
        osd.play.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        assertFalse(osd.play.isPressed)
        assertEquals(1f, glyph.scaleX)
        osd.transport.getChildAt(0).requestFocus()
        assertEquals(View.INVISIBLE, tooltip.visibility)
    }
    @Test fun skipAndSheetPillsActOnKeyDownAndIgnoreHeldCenter() {
        val (_, osd) = fixture()
        val button = OsdButton(osd.context, "Skip Intro")
        var clicks = 0
        button.setOnClickListener { clicks++ }
        button.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(1, clicks)
        assertTrue(button.isPressed)
        assertEquals(1f, button.scaleX)
        button.dispatchKeyEvent(KeyEvent(0, 1, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 3))
        assertEquals(1, clicks)
        button.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(1, clicks)
        assertFalse(button.isPressed)
        assertEquals(1f, button.scaleX)
    }
    private class TransportDouble {
        var playing = true
        val calls = mutableListOf<String>()
        fun exo(): ExoPlayer = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, _ ->
            when (method.name) {
                "getPlaybackState" -> Player.STATE_READY
                "getPlayWhenReady", "isPlaying" -> playing
                "getDuration" -> 180_000L
                "getCurrentPosition" -> 10_000L
                "getBufferedPosition" -> 20_000L
                "play" -> { calls += "play"; playing = true; Unit }
                "pause" -> { calls += "pause"; playing = false; Unit }
                else -> throw AssertionError("Unexpected player call ${method.name}")
            }
        } as ExoPlayer
    }
    private fun withActivityKeys(test: (PlayerActivity, PlayerOsd, TransportDouble) -> Unit) {
        // Only construct the native view tree. Never call PlayerActivity.onCreate or start codecs/I/O.
        val activity = Robolectric.buildActivity(PlayerActivity::class.java,
            Intent().putExtra("itemId", "offline-key-fixture").putExtra("osdLayer", false)).get()
        activity.setTheme(R.style.Theme_Marquee_Player)
        fun field(name: String) = PlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun call(name: String) = PlayerActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
        val transport = TransportDouble()
        field("prefs").set(activity, PlayerPrefs(activity))
        field("player").set(activity, transport.exo())
        call("buildSurface"); call("buildOverlays")
        val root = field("root").get(activity) as FrameLayout
        (root.parent as? ViewGroup)?.removeView(root)
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        host.get().setContentView(root); host.visible()
        root.dispatchWindowFocusChanged(true)
        layout(root)
        try { test(activity, field("osd").get(activity) as PlayerOsd, transport) }
        finally { com.mav.marquee.ui.library.kit.ScreenKeyCpuTrace.clear(); com.mav.marquee.ui.components.NavWorkTrace.observer = null; activity.lifecycleScope.cancel(); host.pause().stop().destroy() }
    }


    @Test fun mixedCuesRouteTextToScreenAndBitmapToPictureAndClearBoth() = withActivityKeys { activity, osd, _ ->
        fun get(name: String) = PlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
        val text = androidx.media3.common.text.Cue.Builder().setText("Caption in the black bar").build()
        val bitmap = androidx.media3.common.text.Cue.Builder().setBitmap(
            android.graphics.Bitmap.createBitmap(12, 6, android.graphics.Bitmap.Config.ARGB_8888)).build()
        val listener = get("playerListener") as androidx.media3.common.Player.Listener
        listener.onCues(androidx.media3.common.text.CueGroup(listOf(text, bitmap), 0))
        val textView = get("captions") as androidx.media3.ui.SubtitleView
        val bitmapView = get("bitmapCaptions") as androidx.media3.ui.SubtitleView
        val cues = androidx.media3.ui.SubtitleView::class.java.getDeclaredField("cues").apply { isAccessible = true }
        assertEquals(listOf(text), cues.get(textView))
        assertEquals(listOf(bitmap), cues.get(bitmapView))
        assertEquals(textView.context.dp(24), textView.paddingBottom)
        val fraction = SubtitleView::class.java.getDeclaredField("bottomPaddingFraction").apply { isAccessible = true }
        assertEquals(0f, fraction.getFloat(textView))
        assertSame(get("root"), textView.parent)
        assertSame(get("videoFrame"), bitmapView.parent)
        osd.visibility = View.VISIBLE
        layout(get("root") as FrameLayout)
        PlayerActivity::class.java.getDeclaredMethod("updateCaptionInsets").apply { isAccessible = true }.invoke(activity)
        assertTrue(textView.translationY < 0)
        listener.onCues(androidx.media3.common.text.CueGroup(emptyList(), 0))
        assertEquals(emptyList<androidx.media3.common.text.Cue>(), cues.get(textView))
        assertEquals(emptyList<androidx.media3.common.text.Cue>(), cues.get(bitmapView))
    }

    @Test fun detailSelectedBurnInWarningCanCancelBeforeStartingPlayback() = withActivityKeys { activity, _, transport ->
        val fixture = kotlinx.serialization.json.Json.parseToJsonElement(
            com.mav.marquee.offline.OfflinePaths.resource("player-audio-remux.json")).let {
                it as kotlinx.serialization.json.JsonObject
            }
        val resolved = StreamResolver.fromPlaybackInfo(fixture.obj("item")!!, fixture.obj("info")!!,
            DeviceProfile.Mode.Direct, 80_000_000, 0, 1, null, "http://server", "offline")
        PlayerActivity::class.java.getDeclaredField("resolved").apply { isAccessible = true }.set(activity, resolved)
        PlayerActivity::class.java.getDeclaredMethod("confirmVideoConversion", Integer::class.java,
            Integer::class.java, Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, 0, 1, true, 120_000L)
        val list = PlayerActivity::class.java.getDeclaredField("sheetList").apply { isAccessible = true }
            .get(activity) as android.widget.LinearLayout
        assertTrue((list.getChildAt(0) as TextView).text.toString().contains("SDR, up to 1080p"))
        val cancel = list.getChildAt(1) as TextView
        assertTrue(cancel.text.toString().contains("Cancel playback"))
        assertTrue(transport.calls.isEmpty())
        cancel.performClick()
        assertTrue(activity.isFinishing)
        assertTrue(transport.calls.isEmpty())
    }
    @Test fun actualActivityCenterTogglesOnceOnKeyDownAndNeverWaitsForPlayerCallback() = withActivityKeys { activity, osd, transport ->
        assertEquals(View.GONE, osd.visibility)
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        // No clock advance or player listener callback occurs before these assertions.
        assertFalse(transport.playing)
        assertEquals("Play", osd.play.label)
        assertEquals(View.VISIBLE, osd.visibility)
        assertTrue(osd.play.isFocused)
        assertTrue(osd.play.isPressed)
        activity.dispatchKeyEvent(KeyEvent(0, 1, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 2))
        assertEquals(listOf("pause"), transport.calls)
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        assertFalse(osd.play.isPressed)
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(transport.playing)
        assertEquals("Pause", osd.play.label)
        assertEquals(listOf("pause", "play"), transport.calls)
    }
    @Test fun actualActivityDedicatedTransportWorksWhileSheetsAndCountdownOwnFocus() = withActivityKeys { activity, osd, transport ->
        fun view(name: String) = PlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as View
        view("sheet").visibility = View.VISIBLE
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertFalse(transport.playing); assertEquals("Play", osd.play.label)
        assertEquals("Paused", (view("banner") as TextView).text.toString())
        activity.dispatchKeyEvent(KeyEvent(0, 1, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 1))
        assertEquals(listOf("pause"), transport.calls)
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        assertTrue(transport.playing); assertEquals("Pause", osd.play.label)
        view("sheet").visibility = View.GONE; view("upNext").visibility = View.VISIBLE
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertFalse(transport.playing); assertEquals("Play", osd.play.label)
        assertEquals(listOf("pause", "play", "pause"), transport.calls)
    }
    @Test fun instantOsdNavigationScrubAndSheets() = withActivityKeys { activity, osd, transport ->
        val events = mutableListOf<JsonObject>()
        fun field(name: String) = PlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        val root = field("root").get(activity) as FrameLayout
        fun call(name: String) = PlayerActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
        // Prime the native view/formatter classes before timing, like the screen harness's
        // Down/Up warmup. The fixture skips real onCreate/playback startup on purpose.
        PlayerActivity::class.java.getDeclaredMethod("showOsd", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
        layout(root)
        call("hideOsd")
        var appWork = 0L
        var appCpu = 0L
        val cpuClock = ManagementFactory.getThreadMXBean()
        assertTrue(cpuClock.isCurrentThreadCpuTimeSupported)
        if (!cpuClock.isThreadCpuTimeEnabled) cpuClock.isThreadCpuTimeEnabled = true
        com.mav.marquee.ui.library.kit.ScreenKeyCpuTrace.clock = { cpuClock.currentThreadCpuTime }
        com.mav.marquee.ui.library.kit.ScreenKeyCpuTrace.work = { appCpu += it }
        com.mav.marquee.ui.components.NavWorkTrace.observer = object : com.mav.marquee.ui.components.NavWorkTrace.Observer {
            override fun composed(scope: String) = Unit
            override fun measured(scope: String) = Unit
            override fun placed(scope: String) = Unit
            override fun keyWork(nanos: Long) { appWork += nanos }
        }
        fun press(code: Int, repeat: Int = 0) {
            appWork = 0L; appCpu = 0L
            val now = SystemClock.uptimeMillis()
            val bean = ManagementFactory.getThreadMXBean()
            val cpu = bean.currentThreadCpuTime
            val start = System.nanoTime()
            val event = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, repeat)
            if (!activity.dispatchKeyEvent(event)) root.dispatchKeyEvent(event)
            val dispatch = System.nanoTime() - start
            val keyWall = appWork
            val keyWork = appCpu
            val cpuNanos = bean.currentThreadCpuTime - cpu
            val focused = root.findFocus()
            val target = osd.seek.scrubMs
            val elapsed = osd.timeLeft.text.toString()
            // Native UI has no Compose animation clock. One native frame must change nothing further.
            ShadowLooper.idleMainLooper(16, java.util.concurrent.TimeUnit.MILLISECONDS)
            layout(root)
            assertSame("Focus must already be final on key-down", focused, root.findFocus())
            assertEquals(target, osd.seek.scrubMs)
            assertEquals(elapsed, osd.timeLeft.text.toString())
            assertNull(osd.animation)
            assertEquals(1f, osd.alpha)
            events += buildJsonObject {
                put("event", events.size); put("keyCode", code); put("repeat", repeat)
                put("appKeyWorkNanos", keyWork); put("appKeyWallNanos", keyWall)
                put("dispatchNanos", dispatch); put("dispatchCpuNanos", cpuNanos)
                put("focusTicks", 1); put("scrollTicks", 1); put("pendingAnimations", 0)
                put("focused", root.findFocus()?.contentDescription?.toString() ?: root.findFocus()?.javaClass?.simpleName)
                put("scrubMs", target); put("time", elapsed)
            }
            val release = KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0)
            if (!activity.dispatchKeyEvent(release)) root.dispatchKeyEvent(release)
        }
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(View.VISIBLE, osd.visibility)
        assertTrue(osd.play.isFocused)
        val forward = osd.transport.getChildAt(2)
        val audio = osd.options.getChildAt(0)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, 1); assertTrue(forward.isFocused)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, 2); assertTrue(audio.isFocused)
        press(KeyEvent.KEYCODE_DPAD_LEFT, 3); assertTrue(forward.isFocused)
        osd.seek.requestFocus()
        var expected = 10_000L
        for (repeat in 0..7) {
            expected += if (repeat < 4) 10_000 else 30_000
            expected = expected.coerceAtMost(179_000)
            press(KeyEvent.KEYCODE_DPAD_RIGHT, repeat)
            assertEquals("Each held repeat updates the marker exactly once", expected, osd.seek.scrubMs)
            assertEquals(fmtTime(expected), osd.timeLeft.text.toString())
            assertEquals("-" + fmtTime(180_000 - expected), osd.timeRight.text.toString())
        }
        press(KeyEvent.KEYCODE_DPAD_LEFT, 2)
        assertEquals(expected - 10_000, osd.seek.scrubMs)
        call("cancelScrub")
        call("openSettingsSheet")
        val sheet = field("sheet").get(activity) as android.widget.ScrollView
        assertEquals(View.GONE, osd.visibility)
        assertEquals(View.VISIBLE, sheet.visibility)
        assertFalse(sheet.isSmoothScrollingEnabled)
        val sheetList = field("sheetList").get(activity) as android.widget.LinearLayout
        assertTrue(sheetList.getChildAt(1).isFocused)
        layout(root)
        press(KeyEvent.KEYCODE_DPAD_DOWN, 1)
        assertTrue(sheetList.getChildAt(2).isFocused)
        press(KeyEvent.KEYCODE_BACK)
        assertEquals(View.GONE, sheet.visibility)
        PlayerActivity::class.java.getDeclaredMethod("showOsd", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
        call("hideOsd")
        assertEquals(View.GONE, osd.visibility)
        PlayerActivity::class.java.getDeclaredMethod("showOsd", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
        // Timer expiry is a visibility swap. There is no auto-hide fade to finish.
        ShadowLooper.idleMainLooper(4100, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(View.GONE, osd.visibility)
        assertEquals(1f, osd.alpha)
        com.mav.marquee.ui.library.kit.ScreenKeyCpuTrace.clear()
        com.mav.marquee.ui.components.NavWorkTrace.observer = null
        val output = com.mav.marquee.offline.OfflinePaths.evidence
        val capture = System.getProperty("marquee.navCapture", "verify")
        if (capture == "verify") {
            val budget = Json.parseToJsonElement(com.mav.marquee.offline.OfflinePaths.budget()).jsonObject
            val limit = (budget.getValue("mainThreadKeyBudgetMs").jsonPrimitive.double * 1_000_000).toLong()
            events.forEach { event -> assertTrue("Native OSD key work must stay under $limit ns: $event", event.getValue("appKeyWorkNanos").jsonPrimitive.long < limit) }
        }
        File(output, "player-osd-$capture.json").writeText(Json { prettyPrint = true }.encodeToString(
            JsonObject.serializer(), buildJsonObject { put("screen", "playerOsd"); put("frameMs", 16); put("events", JsonArray(events)) }) + "\n")
    }

    @Test fun captionsKeepTheirSizeAndClearControlsInFullScreenAndLetterboxedVideo() {
        val (root, osd) = fixture()
        val captions = SubtitleView(root.context)
        root.addView(captions, 0, FrameLayout.LayoutParams(-1, -1))
        layout(root)
        val dimensions = captions.width to captions.height
        for (videoBottom in listOf(1080, 900)) {
            captions.translationY = captionTranslation(videoBottom, osd.top, true, root.context.dp(12))
            assertTrue(videoBottom + captions.translationY <= osd.top - root.context.dp(12))
            assertEquals(dimensions, captions.width to captions.height)
            captions.translationY = captionTranslation(videoBottom, osd.top, false, root.context.dp(12))
            assertEquals(0f, captions.translationY)
        }
        assertEquals(0f, captionTranslation(osd.top - 10, osd.top, true, 24))
    }
    @Test fun playPausePaintsRequestedStateBeforePlayerCallbacksEvenDuringBuffering() {
        val (_, osd) = fixture()
        var requested = true
        var state = Player.STATE_BUFFERING
        val order = mutableListOf<String>()
        val fake = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getPlaybackState" -> state
                "getPlayWhenReady" -> requested
                "play" -> { order += "play"; requested = true; Unit }
                "pause" -> { order += "pause"; requested = false; Unit }
                "seekTo" -> { assertEquals(0L, args!![0]); order += "seek"; Unit }
                else -> throw AssertionError("Unexpected player call ${method.name}")
            }
        } as Player
        fun feedback(playing: Boolean) { order += "feedback:$playing"; osd.showPlaying(playing) }
        PlayerTransport.toggle(fake, ::feedback)
        assertEquals(listOf("feedback:false", "pause"), order)
        assertEquals("Play", osd.play.label); assertEquals(R.drawable.player_play_arrow, osd.play.iconRes)
        order.clear()
        PlayerTransport.toggle(fake, ::feedback)
        assertEquals(listOf("feedback:true", "play"), order)
        assertEquals("Pause", osd.play.label); assertEquals(R.drawable.player_pause, osd.play.iconRes)
        state = Player.STATE_ENDED; order.clear()
        PlayerTransport.toggle(fake, ::feedback)
        assertEquals(listOf("feedback:true", "seek", "play"), order)
    }
}
