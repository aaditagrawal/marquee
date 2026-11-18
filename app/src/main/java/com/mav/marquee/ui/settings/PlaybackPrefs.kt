package com.mav.marquee.ui.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * User preferences written by Settings and read by the player / image code. Plain SharedPreferences
 * file [FILE] so readers need no dependency on this class (keys are documented in docs/architecture/PREFS.md).
 * All getters are cheap (SharedPreferences keeps an in-memory map after the first load).
 */
class PlaybackPrefs private constructor(private val sp: SharedPreferences) {

    /** Max streaming bitrate in bits/s; 0 = Auto (let the player/device profile decide). */
    var maxBitrateBps: Int
        get() = sp.getInt(K_MAX_BITRATE, 0)
        set(v) = sp.edit().putInt(K_MAX_BITRATE, v).apply()

    /** ISO 639-2 (3-letter, e.g. "eng", "hin"); "" = use the file's default audio track. */
    var audioLanguage: String
        get() = sp.getString(K_AUDIO_LANG, "") ?: ""
        set(v) = sp.edit().putString(K_AUDIO_LANG, v).apply()

    /** ISO 639-2; "" = no preference. */
    var subtitleLanguage: String
        get() = sp.getString(K_SUB_LANG, "") ?: ""
        set(v) = sp.edit().putString(K_SUB_LANG, v).apply()

    /** Jellyfin SubtitlePlaybackMode names: Default | Always | OnlyForced | None | Smart. */
    var subtitleMode: String
        get() = sp.getString(K_SUB_MODE, "Default") ?: "Default"
        set(v) = sp.edit().putString(K_SUB_MODE, v).apply()

    /** auto | button | off */
    var skipIntro: String
        get() = sp.getString(K_SKIP_INTRO, "button") ?: "button"
        set(v) = sp.edit().putString(K_SKIP_INTRO, v).apply()

    /** auto | button | off */
    var skipCredits: String
        get() = sp.getString(K_SKIP_CREDITS, "button") ?: "button"
        set(v) = sp.edit().putString(K_SKIP_CREDITS, v).apply()

    var autoplayNext: Boolean
        get() = sp.getBoolean(K_AUTOPLAY, true)
        set(v) = sp.edit().putBoolean(K_AUTOPLAY, v).apply()

    var diagnosticsOverlay: Boolean
        get() = sp.getBoolean(K_DIAG, false)
        set(v) = sp.edit().putBoolean(K_DIAG, v).apply()

    /** low | standard | high — image request quality hint for the image code. */
    var imageQuality: String
        get() = sp.getString(K_IMG_QUALITY, "standard") ?: "standard"
        set(v) = sp.edit().putString(K_IMG_QUALITY, v).apply()

    companion object {
        const val FILE = "marquee_prefs"
        const val K_MAX_BITRATE = "maxBitrateBps"
        const val K_AUDIO_LANG = "audioLanguage"
        const val K_SUB_LANG = "subtitleLanguage"
        const val K_SUB_MODE = "subtitleMode"
        const val K_SKIP_INTRO = "skipIntro"
        const val K_SKIP_CREDITS = "skipCredits"
        const val K_AUTOPLAY = "autoplayNext"
        const val K_DIAG = "diagnosticsOverlay"
        const val K_IMG_QUALITY = "imageQuality"

        @Volatile private var instance: PlaybackPrefs? = null
        fun get(context: Context): PlaybackPrefs = instance ?: synchronized(this) {
            instance ?: PlaybackPrefs(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}
