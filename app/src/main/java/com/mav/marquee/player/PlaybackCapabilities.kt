package com.mav.marquee.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel as PL
import android.media.MediaCodecList
import android.os.Build
import android.os.Process
import android.view.Display

/**
 * Process-wide, probe-once device capabilities.
 *
 * [warm] starts the probe on a low-priority background thread from Application.onCreate, so neither
 * startup nor playback start waits for it. [get] returns the cached result; if a caller arrives before
 * the probe finished, it waits for that same probe instead of starting a second one. On the reference
 * BRAVIA VU1 only decoder names are listed (no per-codec capability queries), because its profile is a
 * fixed, measured quirk ([DeviceQuirks.isBraviaVu1]).
 */
internal object PlaybackCapabilities {
    // Application context only (never an Activity), so holding it statically does not leak.
    @android.annotation.SuppressLint("StaticFieldLeak")
    @Volatile private var app: Context? = null
    @Volatile private var cached: DeviceCapabilities? = null
    private val lock = Any()

    /** Test hook: fake devices for unit tests. Null in production. */
    @Volatile internal var override: DeviceCapabilities? = null

    fun warm(context: Context) {
        app = context.applicationContext
        if (cached != null) return
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            get()
        }, "MarqueeCapabilities").apply { isDaemon = true }.start()
    }

    fun get(): DeviceCapabilities {
        override?.let { return it }
        cached?.takeIf { sameDevice(it) }?.let { return it }
        return synchronized(lock) { cached?.takeIf { sameDevice(it) } ?: probe(app).also { cached = it } }
    }

    /** Build identity never changes in production; Robolectric tests swap it between test classes. */
    private fun sameDevice(caps: DeviceCapabilities): Boolean = runCatching {
        caps.manufacturer == Build.MANUFACTURER.orEmpty() && caps.model == Build.MODEL.orEmpty()
    }.getOrDefault(true)

    /** Runs [detect]; any failure degrades to [DeviceCapabilities.SAFE] instead of failing playback. */
    internal fun detectSafely(detect: () -> DeviceCapabilities): DeviceCapabilities = try {
        detect()
    } catch (t: Throwable) {
        // Robolectric/JVM stubs, OEM MediaCodecList bugs: never fail playback because of probing.
        DeviceCapabilities.SAFE.copy(
            manufacturer = runCatching { Build.MANUFACTURER.orEmpty() }.getOrDefault(""),
            model = runCatching { Build.MODEL.orEmpty() }.getOrDefault(""),
        )
    }

    private fun probe(context: Context?): DeviceCapabilities = detectSafely {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && !(Build.VERSION.SDK_INT >= 29 && it.isAlias) }
        val names = codecs.map { it.name }.filterNot { it.endsWith(".secure") }
        val identity = DeviceCapabilities(manufacturer, model, names)
        if (DeviceQuirks.isBraviaVu1(identity)) identity
        else if (codecs.isEmpty()) DeviceCapabilities.SAFE.copy(manufacturer = manufacturer, model = model)
        else probeCodecs(identity, codecs, context)
    }

    private fun probeCodecs(identity: DeviceCapabilities, codecs: List<MediaCodecInfo>, context: Context?): DeviceCapabilities {
        val video = HashMap<String, DeviceCapabilities.VideoDecoder>()
        val audio = HashSet<String>()
        var dolbyVision = false
        for (info in codecs) {
            for (mime in info.supportedTypes) {
                val type = mime.lowercase()
                val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
                if (caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)) continue
                if (type == "video/dolby-vision") { dolbyVision = true; continue }
                VIDEO_MIMES[type]?.let { codec ->
                    val decoder = videoDecoder(codec, info, caps) ?: return@let
                    video[codec] = better(video[codec], decoder)
                }
                AUDIO_MIMES[type]?.let { audio.addAll(it) }
            }
        }
        // Android's platform software decoders always cover these; the ffmpeg extension renderer adds
        // nothing we advertise, since its codecs are not guaranteed on every ABI build.
        audio.addAll(listOf("aac", "mp3", "opus", "vorbis", "flac", "pcm_s16le", "pcm_s24le"))
        val result = identity.copy(video = video, audio = audio, displayHdr = displayHdr(context), dolbyVisionDecoder = dolbyVision)
        return if (video["h264"] == null) result.copy(video = video + DeviceCapabilities.SAFE.video, probeFailed = video.isEmpty())
        else result
    }

    private fun videoDecoder(codec: String, info: MediaCodecInfo, caps: MediaCodecInfo.CodecCapabilities): DeviceCapabilities.VideoDecoder? {
        val v = caps.videoCapabilities ?: return null
        val hardware = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
            else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
        var maxW = v.supportedWidths.upper
        var maxH = v.supportedHeights.upper
        val (rateW, rateH) = when {
            v.isSizeSupported(3840, 2160) -> 3840 to 2160
            v.isSizeSupported(1920, 1080) -> 1920 to 1080
            else -> minOf(maxW, 1280) to minOf(maxH, 720)
        }
        var fps = runCatching { v.getSupportedFrameRatesFor(rateW, rateH).upper.toInt() }.getOrDefault(30)
        if (!hardware) {
            // Software decoders on TV-class CPUs are not real-time beyond 1080p30.
            maxW = minOf(maxW, 1920); maxH = minOf(maxH, 1080); fps = minOf(fps, 30)
        }
        val levels = caps.profileLevels.orEmpty()
        val profiles = levels.mapNotNull { CodecMapping.profile(codec, it.profile) }.toSet()
        if (profiles.isEmpty() && codec in setOf("h264", "hevc", "vp9", "av1")) return null
        return DeviceCapabilities.VideoDecoder(
            maxWidth = maxW, maxHeight = maxH, maxFrameRate = fps.coerceAtLeast(1),
            maxBitrate = v.bitrateRange.upper,
            profiles = profiles,
            maxLevel = levels.maxOfOrNull { CodecMapping.level(codec, it.level) } ?: 0,
            maxBitDepth = levels.maxOfOrNull { CodecMapping.bitDepth(codec, it.profile) } ?: 8,
            hardware = hardware,
        )
    }

    /** Hardware first, then the larger decoder. Profiles are not merged: Media3 uses one decoder. */
    private fun better(a: DeviceCapabilities.VideoDecoder?, b: DeviceCapabilities.VideoDecoder) = when {
        a == null -> b
        a.hardware != b.hardware -> if (a.hardware) a else b
        b.maxWidth.toLong() * b.maxHeight > a.maxWidth.toLong() * a.maxHeight -> b
        else -> a
    }

    private fun displayHdr(context: Context?): Set<DeviceCapabilities.Hdr> {
        val display = context?.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptySet()
        val types: IntArray = runCatching { hdrTypes(display) }.getOrNull() ?: return emptySet()
        return types.toList().mapNotNull {
            when (it) {
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> DeviceCapabilities.Hdr.HDR10
                Display.HdrCapabilities.HDR_TYPE_HLG -> DeviceCapabilities.Hdr.HLG
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> DeviceCapabilities.Hdr.HDR10_PLUS
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> DeviceCapabilities.Hdr.DOLBY_VISION
                else -> null
            }
        }.toSet()
    }

    private fun hdrTypes(display: Display): IntArray? =
        if (Build.VERSION.SDK_INT >= 34) display.mode.supportedHdrTypes
        else hdrTypesLegacy(display)

    @Suppress("DEPRECATION")
    private fun hdrTypesLegacy(display: Display): IntArray? = display.hdrCapabilities?.supportedHdrTypes

    private val VIDEO_MIMES = mapOf(
        "video/avc" to "h264", "video/hevc" to "hevc", "video/x-vnd.on2.vp9" to "vp9",
        "video/av01" to "av1", "video/x-vnd.on2.vp8" to "vp8", "video/mpeg2" to "mpeg2video",
        "video/mp4v-es" to "mpeg4",
    )
    private val AUDIO_MIMES = mapOf(
        "audio/mp4a-latm" to listOf("aac"), "audio/mpeg" to listOf("mp3"), "audio/ac3" to listOf("ac3"),
        "audio/eac3" to listOf("eac3"), "audio/vnd.dts" to listOf("dts"), "audio/opus" to listOf("opus"),
        "audio/vorbis" to listOf("vorbis"), "audio/flac" to listOf("flac"), "audio/mpeg-l2" to listOf("mp2"),
        "audio/raw" to listOf("pcm_s16le", "pcm_s24le"),
    )
}

/** Android MediaCodec profile/level constants to Jellyfin VideoProfile / VideoLevel values. Pure JVM. */
internal object CodecMapping {
    fun profile(codec: String, p: Int): String? = when (codec) {
        "h264" -> when (p) {
            PL.AVCProfileBaseline -> "baseline"
            PL.AVCProfileConstrainedBaseline -> "constrained baseline"
            PL.AVCProfileMain -> "main"
            PL.AVCProfileHigh -> "high"
            PL.AVCProfileConstrainedHigh -> "constrained high"
            PL.AVCProfileHigh10 -> "high 10"
            else -> null
        }
        "hevc" -> when (p) {
            PL.HEVCProfileMain -> "main"
            PL.HEVCProfileMain10, PL.HEVCProfileMain10HDR10, PL.HEVCProfileMain10HDR10Plus -> "main 10"
            else -> null
        }
        "vp9" -> when (p) {
            PL.VP9Profile0 -> "profile 0"
            PL.VP9Profile1 -> "profile 1"
            PL.VP9Profile2, PL.VP9Profile2HDR, PL.VP9Profile2HDR10Plus -> "profile 2"
            PL.VP9Profile3, PL.VP9Profile3HDR, PL.VP9Profile3HDR10Plus -> "profile 3"
            else -> null
        }
        "av1" -> when (p) {
            PL.AV1ProfileMain8 -> "main"
            PL.AV1ProfileMain10, PL.AV1ProfileMain10HDR10, PL.AV1ProfileMain10HDR10Plus -> "main"
            else -> null
        }
        else -> null
    }

    fun bitDepth(codec: String, p: Int): Int = when (codec) {
        "h264" -> if (p == PL.AVCProfileHigh10) 10 else 8
        "hevc" -> if (p == PL.HEVCProfileMain) 8 else 10
        "vp9" -> if (p == PL.VP9Profile0 || p == PL.VP9Profile1) 8 else 10
        "av1" -> if (p == PL.AV1ProfileMain8) 8 else 10
        else -> 8
    }

    /** Jellyfin levels: H.264 uses level*10 (4.1 -> 41), HEVC uses level*30 (5.1 -> 153). */
    fun level(codec: String, l: Int): Int = when (codec) {
        "h264" -> when (l) {
            PL.AVCLevel1, PL.AVCLevel1b -> 10; PL.AVCLevel11 -> 11; PL.AVCLevel12 -> 12; PL.AVCLevel13 -> 13
            PL.AVCLevel2 -> 20; PL.AVCLevel21 -> 21; PL.AVCLevel22 -> 22
            PL.AVCLevel3 -> 30; PL.AVCLevel31 -> 31; PL.AVCLevel32 -> 32
            PL.AVCLevel4 -> 40; PL.AVCLevel41 -> 41; PL.AVCLevel42 -> 42
            PL.AVCLevel5 -> 50; PL.AVCLevel51 -> 51; PL.AVCLevel52 -> 52
            PL.AVCLevel6 -> 60; PL.AVCLevel61 -> 61; PL.AVCLevel62 -> 62
            else -> 0
        }
        "hevc" -> when (l) {
            PL.HEVCMainTierLevel1, PL.HEVCHighTierLevel1 -> 30
            PL.HEVCMainTierLevel2, PL.HEVCHighTierLevel2 -> 60
            PL.HEVCMainTierLevel21, PL.HEVCHighTierLevel21 -> 63
            PL.HEVCMainTierLevel3, PL.HEVCHighTierLevel3 -> 90
            PL.HEVCMainTierLevel31, PL.HEVCHighTierLevel31 -> 93
            PL.HEVCMainTierLevel4, PL.HEVCHighTierLevel4 -> 120
            PL.HEVCMainTierLevel41, PL.HEVCHighTierLevel41 -> 123
            PL.HEVCMainTierLevel5, PL.HEVCHighTierLevel5 -> 150
            PL.HEVCMainTierLevel51, PL.HEVCHighTierLevel51 -> 153
            PL.HEVCMainTierLevel52, PL.HEVCHighTierLevel52 -> 156
            PL.HEVCMainTierLevel6, PL.HEVCHighTierLevel6 -> 180
            PL.HEVCMainTierLevel61, PL.HEVCHighTierLevel61 -> 183
            PL.HEVCMainTierLevel62, PL.HEVCHighTierLevel62 -> 186
            else -> 0
        }
        else -> 0
    }
}
