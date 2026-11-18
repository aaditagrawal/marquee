package com.mav.marquee.player

import android.content.Context

/** Live, read-only access to the exact keys in docs/architecture/PREFS.md. */
internal class PlayerPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("marquee_prefs", Context.MODE_PRIVATE)
    val storedMaxBitrate: Int get() = sp.getInt("maxBitrateBps", 0)
    val maxBitrate: Int get() = storedMaxBitrate.let {
        if (it <= 0) 0 else it.coerceIn(1_000_000, 120_000_000)
    }
    val audioLanguage: String get() = sp.getString("audioLanguage", "").orEmpty()
    val subtitleLanguage: String get() = sp.getString("subtitleLanguage", "").orEmpty()
    val subtitleMode: String get() = sp.getString("subtitleMode", "Default") ?: "Default"
    val introMode: String get() = sp.getString("skipIntro", "button") ?: "button"
    val creditsMode: String get() = sp.getString("skipCredits", "button") ?: "button"
    val autoPlayNext: Boolean get() = sp.getBoolean("autoplayNext", true)
    val diagnostics: Boolean get() = sp.getBoolean("diagnosticsOverlay", false)
    val upNextSeconds = 10
    // Experimental tunneling is tested with an Intent extra; never changes TV system settings.
    // OFF: on the reference Sony BRAVIA VU1, tunneled playback with its Realtek audio decoder was silent
    // (HW_AV_SYNC output ran, speakers muted). Tunneling stays off on every device.
    val tunneling = false
    val passthrough: Boolean get() = sp.getBoolean("audioPassthrough", false)
    fun setPassthrough(enabled: Boolean) { sp.edit().putBoolean("audioPassthrough", enabled).apply() }
}
