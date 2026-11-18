package com.mav.marquee

import android.app.Application
import com.mav.marquee.data.Session
import com.mav.marquee.data.ImageUrls
import com.mav.marquee.image.MarqueeImages
import com.mav.marquee.ui.home.HomeController
import com.mav.marquee.ui.home.HomePerf
import com.mav.marquee.ui.components.CardCaptionLayouts

class MarqueeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        HomePerf.stage("application")
        Session.init(this)
        // Low-priority background probe; cached for the first PlaybackInfo request.
        com.mav.marquee.player.PlaybackCapabilities.warm(this)
        CardCaptionLayouts.init(this)
        com.mav.marquee.ui.home.ContextTextLayouts.init(this)
        com.mav.marquee.ui.home.HeroTextLayouts.init(this)
        ImageUrls.init(this)
        MarqueeImages.init(this)
        HomePerf.stage("session-ready")
        // Start reading the Home snapshot + the network refresh before the Activity is even created.
        HomeController.preload(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        MarqueeImages.trim(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            CardCaptionLayouts.trim()
            com.mav.marquee.ui.home.ContextTextLayouts.trim()
            com.mav.marquee.ui.home.HeroTextLayouts.trim()
        }
    }
}
