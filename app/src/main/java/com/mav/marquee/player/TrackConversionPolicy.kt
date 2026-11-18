package com.mav.marquee.player

import kotlinx.serialization.json.JsonObject

/** PGS and VobSub are copied into progressive Matroska, including audio conversion. */
internal object TrackConversionPolicy {
    fun isExternalText(sub: JsonObject): Boolean = sub.str("DeliveryMethod") == "External" &&
        sub.str("Codec")?.lowercase() in setOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt")

    fun canEmbedBitmap(sub: JsonObject?): Boolean = sub != null &&
        sub.str("Codec")?.lowercase() in setOf("pgssub", "pgs", "dvdsub", "vobsub") && !sub.bool("IsExternal")

    fun needsVideoConversion(audio: JsonObject?, sub: JsonObject?): Boolean =
        audio != null && sub != null && !canEmbedBitmap(sub) && !AudioSelection.isDirectCompatible(audio) &&
            sub.str("Codec")?.lowercase() in setOf("pgssub", "pgs", "dvdsub", "dvbsub", "vobsub")
}
