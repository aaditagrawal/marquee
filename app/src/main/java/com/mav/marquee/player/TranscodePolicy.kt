package com.mav.marquee.player

import kotlin.math.floor
import kotlin.math.min

internal object TranscodePolicy {
    fun next(mode: DeviceProfile.Mode): DeviceProfile.Mode? = when (mode) {
        DeviceProfile.Mode.Direct -> DeviceProfile.Mode.Remux
        DeviceProfile.Mode.Remux -> DeviceProfile.Mode.Hevc
        DeviceProfile.Mode.Hevc -> DeviceProfile.Mode.H264
        DeviceProfile.Mode.H264 -> null
    }

    /** Never upscale; widths align to macroblocks, visible heights remain even. */
    fun h264Size(width: Int, height: Int, maxWidth: Int = 1920, maxHeight: Int = 1080): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = min(1.0, min(maxWidth.toDouble() / width, maxHeight.toDouble() / height))
        return (floor(width * scale / 16).toInt().coerceAtLeast(1) * 16) to
            (floor(height * scale / 2).toInt().coerceAtLeast(1) * 2)
    }
}
