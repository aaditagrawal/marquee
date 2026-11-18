package com.mav.marquee.offline

import android.app.Application
import com.mav.marquee.data.ImageUrls
import com.mav.marquee.data.Session
import com.mav.marquee.image.MarqueeImages
import com.mav.marquee.ui.components.CardCaptionLayouts

/** Production startup with network/cache refresh deliberately excluded. No emulator or TV involved. */
class OfflineApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Session::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(null, null)
        Session.init(this)
        org.robolectric.shadows.ShadowBuild.setManufacturer("Sony")
        org.robolectric.shadows.ShadowBuild.setModel("BRAVIA VU1")
        // Session.deviceName is computed once per process. Pin its first source so the Settings device line
        // reads "robolectric" whichever test evaluates it first (otherwise it follows test order).
        android.provider.Settings.Global.putString(contentResolver, android.provider.Settings.Global.DEVICE_NAME, "robolectric")
        // Reference TV memory class (192 MB). Robolectric reports 16 MB, which shrinks the bitmap LRU so far
        // that warmed screenshot art is evicted and then races the capture against an async reload.
        org.robolectric.Shadows.shadowOf(getSystemService(android.app.ActivityManager::class.java)).setMemoryClass(192)
        ImageUrls.init(this)
        MarqueeImages.init(this)
        CardCaptionLayouts.init(this)
    }
}
