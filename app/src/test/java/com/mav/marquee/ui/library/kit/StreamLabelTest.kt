package com.mav.marquee.ui.library.kit

import com.mav.marquee.data.MediaStream
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamLabelTest {
    @Test fun readableAudioAndSubtitleLabels() {
        assertEquals("Korean · Dolby Digital+ · Stereo", streamLabel(MediaStream(type = "Audio", codec = "eac3",
            language = "kor", channels = 2, displayTitle = "Korean - Dolby Digital+ - Stereo - Default")))
        assertEquals("English (US) · SRT", streamLabel(MediaStream(type = "Subtitle", codec = "SUBRIP",
            language = "eng", displayTitle = "English (United States) - SUBRIP")))
        for (codec in listOf("ass", "SSA")) assertEquals("English · ASS", streamLabel(MediaStream(type = "Subtitle", codec = codec, language = "en")))
        assertEquals("English · PGS · Forced", streamLabel(MediaStream(type = "Subtitle", codec = "PGSSUB", language = "en", isForced = true)))
        assertEquals("English · Dolby Digital · 5.1", streamLabel(MediaStream(type = "Audio", codec = "ac3", language = "en", channels = 6)))
        assertEquals("English · Opus", streamLabel(MediaStream(type = "Audio", codec = "OPUS", language = "en")))
        assertEquals("English · Dolby TrueHD · 7.1", streamLabel(MediaStream(type = "Audio", codec = "truehd",
            channels = 8, displayTitle = "English - TrueHD - 7.1")))
    }
}
