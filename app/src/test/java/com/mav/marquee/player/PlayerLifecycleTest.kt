package com.mav.marquee.player

import android.app.Application
import android.content.Intent
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class PlayerLifecycleTest {
    // Expectations below are the measured reference TV's profile (see DeviceProfileBaselineTest).
    @org.junit.Before fun referenceDevice() { PlaybackCapabilities.override = ReferenceDevices.BRAVIA_VU1 }
    @org.junit.After fun clearReferenceDevice() { PlaybackCapabilities.override = null }

    private fun inject(activity: PlayerActivity, calls: MutableList<String>) {
        val exo = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, _ ->
            when (method.name) {
                "stop", "release" -> { calls += method.name; null }
                else -> throw AssertionError("Unexpected call ${method.name}")
            }
        } as ExoPlayer
        PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }.set(activity, exo)
    }

    @Test fun stopReleasesBeforeDestroyAndDestroyDoesNotReleaseTwice() {
        // Missing item avoids network/codec startup; inject a player at the lifecycle boundary.
        val controller = Robolectric.buildActivity(PlayerActivity::class.java, Intent()).create().start().resume()
        val calls = mutableListOf<String>()
        inject(controller.get(), calls)
        controller.pause().stop()
        assertEquals(listOf("stop", "release"), calls)
        controller.destroy()
        assertEquals(listOf("stop", "release"), calls)
    }

    @Test fun replacingActivityReleasesOldPlayerBeforeNewOwnerAndOldDestroyCannotClearNewOwner() {
        val first = Any()
        val second = Any()
        val third = Any()
        val calls = mutableListOf<String>()
        ActivePlayerOwner.replace(first) { calls += "first-stop-release"; ActivePlayerOwner.clear(first) }
        ActivePlayerOwner.replace(second) { calls += "second-stop-release"; ActivePlayerOwner.clear(second) }
        assertEquals(listOf("first-stop-release"), calls)
        ActivePlayerOwner.clear(first)
        ActivePlayerOwner.replace(third) { calls += "third-stop-release" }
        assertEquals(listOf("first-stop-release", "second-stop-release"), calls)
        ActivePlayerOwner.clear(third)
    }
}
