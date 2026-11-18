package com.mav.marquee.player

import kotlinx.serialization.json.JsonObject

/** Source-side decoding budget, independent of whether the FFmpeg extension is installed. */
internal object AudioSelection {
    fun isDirectCompatible(stream: JsonObject): Boolean = when (stream.str("Codec")?.lowercase()) {
        "aac", "ac3", "eac3", "mp3", "mp2", "opus", "vorbis", "pcm_s16le", "pcm_s24le" -> true
        "dts" -> stream.str("Profile").equals("DTS", ignoreCase = true)
        "flac" -> (stream.int("Channels") ?: Int.MAX_VALUE) <= 2 &&
            (stream.int("SampleRate") ?: Int.MAX_VALUE) <= 48_000 &&
            (stream.int("BitDepth") ?: Int.MAX_VALUE) <= 24
        else -> false
    }

    fun choose(source: JsonObject?, explicitIndex: Int?, language: String): Int? {
        val audio = source?.arr("MediaStreams")?.objs().orEmpty().filter { it.str("Type") == "Audio" }
        // An explicit track choice is preserved, but heavy formats still negotiate server conversion.
        explicitIndex?.let { index -> if (audio.any { it.int("Index") == index }) return index }
        val preferred = audio.filter { language.isNotBlank() && it.str("Language").equals(language, true) }
        val default = audio.firstOrNull { it.int("Index") == source?.int("DefaultAudioStreamIndex") }
            ?: audio.firstOrNull { it.bool("IsDefault") } ?: audio.firstOrNull()
        val wanted = preferred.firstOrNull() ?: default ?: return null
        if (isDirectCompatible(wanted)) return wanted.int("Index")
        // Keep the wanted language where possible, and avoid silently selecting commentary.
        val compatible = audio.filter { isDirectCompatible(it) && !it.bool("IsCommentary") }
        return (preferred.firstOrNull { it in compatible }
            ?: compatible.firstOrNull { it.str("Language") == wanted.str("Language") }
            ?: compatible.firstOrNull() ?: wanted).int("Index")
    }
}
