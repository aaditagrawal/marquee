package com.mav.marquee.player

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import android.os.Handler
import android.os.SystemClock
import java.nio.ByteBuffer
import android.util.Log
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Renderers with two narrowly gated decoder workarounds (measured on the reference BRAVIA VU1).
 *
 * Dolby Vision, only when the device has no `video/dolby-vision` decoder: Media3 already maps DV P8/P4
 * to HEVC, but not P7 (dvhe.07, BL compat id 6 = HDR10 base layer + EL/RPU NAL units 62/63, which a
 * plain HEVC decoder discards). [DvToHevcSelector] answers DV queries with the HEVC decoders so P7
 * decodes its HDR10 base layer, and [BaseLayerVideoRenderer] stops Media3 from passing the DV profile
 * constant as KEY_PROFILE to the HEVC codec. Devices with a DV decoder keep Media3's normal DV path.
 *
 * Audio: the ffmpeg extension (org.jellyfin.media3:media3-ffmpeg-decoder) is ON (not PREFER), so it is
 * a last-resort renderer after platform decoding. TrueHD/ALAC/DTS-HD are never advertised for
 * direct play; the server converts their audio. Encoded output requires the opt-in preference and a
 * verified active ARC/eARC route. The Realtek stereo-input workaround applies only to that decoder.
 */
@UnstableApi
internal class MarqueeRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    @Volatile private var needsStartupAudio = false
    @Volatile private var startupAudioReady = false
    private var firstPresentableFrameMs = 0L
    private var audioReadyMs = 0L

    fun beginAudioStartup(hasAudio: Boolean) {
        needsStartupAudio = hasAudio
        startupAudioReady = false
        firstPresentableFrameMs = 0L
        audioReadyMs = 0L
    }

    private class ExactStart(val requestedMs: Long, val sourceStartMs: Long, val beganMs: Long, val exact: Boolean) {
        val localUs = ExactPlaybackStart.localMs(requestedMs, sourceStartMs) * 1000
        var firstOutputMs = 0L
        var firstPtsUs = 0L
        var targetDecoded = false
        var presented = false
    }
    @Volatile private var exactStart: ExactStart? = null

    fun beginExactStart(requestedMs: Long, sourceStartMs: Long, beganMs: Long, exact: Boolean = false) {
        exactStart = ExactStart(requestedMs, sourceStartMs, beganMs, exact)
    }

    // Invoked on the playback thread by the frame metadata listener, only for a
    // frame actually released to the Surface. This is independent of the media clock.
    fun recordExactFrame(localPtsUs: Long) {
        val start = exactStart ?: return
        if (start.presented || (start.exact && localPtsUs < start.localUs)) return
        start.presented = true
        val now = SystemClock.elapsedRealtime()
        val landedMs = start.sourceStartMs + localPtsUs / 1000
        Log.i("MarqueePlayer", "${if (start.exact) "EXACT_FRAME" else "SYNC_FRAME"} requested=${start.requestedMs} landed=$landedMs delta=${landedMs - start.requestedMs} ttff=${now - start.beganMs} prerollMs=${now - start.firstOutputMs} firstPts=${start.sourceStartMs + start.firstPtsUs / 1000} audioGateMs=${if (firstPresentableFrameMs > 0) (now - firstPresentableFrameMs).coerceAtLeast(0) else 0} audioReadyLeadMs=${if (audioReadyMs > 0) now - audioReadyMs else -1}")
    }

    var baseLayerColor: ColorInfo? = null
    val audioRoute = AudioRoutePolicy(context)

    init {
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
        setEnableDecoderFallback(true)
        setMediaCodecSelector(DvToHevcSelector)
        setAllowedVideoJoiningTimeMs(0)
    }

    @Suppress("DEPRECATION")
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink {
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setAudioTrackBufferSizeProvider(pcmBufferProvider())
            .setAudioTrackProvider { config, attributes, session, ctx ->
                DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(config, attributes, session, ctx)
                    .also { audioRoute.track = it }
            }.build()
        return object : ForwardingAudioSink(sink) {
            override fun supportsFormat(format: Format): Boolean =
                audioRoute.allows(format) && super.supportsFormat(format)
            override fun getFormatSupport(format: Format): Int =
                if (audioRoute.allows(format)) super.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED
            override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = AudioOffloadSupport.DEFAULT_UNSUPPORTED
            override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
                val hadData = buffer.hasRemaining()
                val result = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                if (hadData && audioRoute.track != null && !startupAudioReady) {
                    audioReadyMs = SystemClock.elapsedRealtime()
                    startupAudioReady = true
                }
                return result
            }
            override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
                check(audioRoute.allows(inputFormat)) { "Encoded output requires an active verified ARC route" }
                super.configure(inputFormat, specifiedBufferSize, outputChannels)
            }
        }
    }

    companion object {
        // 96,000 bytes at stereo 48 kHz/16-bit. Keep half a second queued at the speaker
        // while dexopt competes for the A55 cores. Sonic processes speed changes
        // before this sink, so PCM buffering remains half a second at every speed.
        internal fun pcmBufferProvider() = DefaultAudioTrackBufferSizeProvider.Builder()
            .setMinPcmBufferDurationUs(500_000)
            .setMaxPcmBufferDurationUs(500_000)
            .build()
    }

    override fun buildAudioRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean, audioSink: AudioSink, eventHandler: Handler,
        eventListener: AudioRendererEventListener, out: ArrayList<Renderer>,
    ) {
        val index = out.size
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            audioSink, eventHandler, eventListener, out)
        // Realtek advertises its stereo OUTPUT limit as an input limit for AC3/EAC3/DTS.
        // Ask it to decode/downmix first; init failure still falls back through the normal ladder.
        // Other decoders keep Media3's normal capability check (DeviceQuirks.realtekStereoInputLimit).
        out[index] = object : MediaCodecAudioRenderer(context, codecAdapterFactory, mediaCodecSelector,
            enableDecoderFallback, eventHandler, eventListener, audioSink) {
            private fun downmixCapability(format: Format): Format = if (format.channelCount > 2 &&
                format.sampleMimeType in setOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_DTS) &&
                DeviceQuirks.realtekStereoInputLimit(firstDecoderName(format.sampleMimeType!!)))
                format.buildUpon().setChannelCount(2).build() else format
            override fun supportsFormat(selector: MediaCodecSelector, format: Format): Int =
                super.supportsFormat(selector, downmixCapability(format))
            override fun getDecoderInfos(selector: MediaCodecSelector, format: Format, secure: Boolean): List<MediaCodecInfo> =
                super.getDecoderInfos(selector, downmixCapability(format), secure)
        }
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        val b = MediaCodecVideoRenderer.Builder(context)
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
            .setEnableDecoderFallback(enableDecoderFallback)
            .setEventHandler(eventHandler)
            .setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
        out.add(BaseLayerVideoRenderer(b))
    }

    /** Media3 caches decoder lists per MIME type, so these lookups cost nothing after the first. */
    private fun firstDecoderName(mime: String): String? =
        runCatching { MediaCodecUtil.getDecoderInfos(mime, false, false).firstOrNull()?.name }.getOrNull()

    private val hasDolbyVisionDecoder: Boolean by lazy {
        runCatching { MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, false, false).isNotEmpty() }.getOrDefault(false)
    }

    object DvToHevcSelector : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean,
        ): List<MediaCodecInfo> {
            val own = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            if (mimeType != MimeTypes.VIDEO_DOLBY_VISION || own.isNotEmpty()) return own
            return MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_H265, requiresSecureDecoder, requiresTunnelingDecoder)
        }
    }

    private inner class BaseLayerVideoRenderer(b: MediaCodecVideoRenderer.Builder) : MediaCodecVideoRenderer(b) {
        override fun processOutputBuffer(positionUs: Long, elapsedRealtimeUs: Long, codec: MediaCodecAdapter?,
            buffer: ByteBuffer?, bufferIndex: Int, bufferFlags: Int, sampleCount: Int,
            bufferPresentationTimeUs: Long, isDecodeOnlyBuffer: Boolean, isLastBuffer: Boolean, format: Format): Boolean {
            val start = exactStart
            val localPtsUs = bufferPresentationTimeUs - outputStreamOffsetUs
            if (start != null && start.firstOutputMs == 0L) {
                start.firstOutputMs = SystemClock.elapsedRealtime()
                start.firstPtsUs = localPtsUs
                Log.i("MarqueePlayer", "FIRST_OUTPUT requested=${start.requestedMs} pts=${start.sourceStartMs + localPtsUs / 1000} sinceStart=${start.firstOutputMs - start.beganMs}")
            }
            val decodeOnly = isDecodeOnlyBuffer || localPtsUs < 0 || (start != null && start.exact && localPtsUs < start.localUs)
            if (start != null && !decodeOnly && !start.targetDecoded) {
                start.targetDecoded = true
                Log.i("MarqueePlayer", "${if (start.exact) "EXACT_DECODE" else "SYNC_DECODE"} requested=${start.requestedMs} costMs=${SystemClock.elapsedRealtime() - start.firstOutputMs} Kms=${(start.localUs - start.firstPtsUs) / 1000}")
            }
            if (!decodeOnly && needsStartupAudio && !startupAudioReady) {
                val now = SystemClock.elapsedRealtime()
                if (firstPresentableFrameMs == 0L) firstPresentableFrameMs = now
                if (now - firstPresentableFrameMs >= 1_500) {
                    throw ExoPlaybackException.createForRenderer(IllegalStateException("Audio startup exceeded 1500ms"),
                        "MarqueeVideo", index, format, C.FORMAT_HANDLED, false, PlaybackException.ERROR_CODE_TIMEOUT)
                }
                return false
            }
            if (!decodeOnly) needsStartupAudio = false
            return super.processOutputBuffer(positionUs, elapsedRealtimeUs, codec, buffer, bufferIndex,
                bufferFlags, sampleCount, bufferPresentationTimeUs, decodeOnly, isLastBuffer, format)
        }

        // Media3's stock P7 alternative-MIME check rejects the HEVC codec even if the selector
        // returns it. Negotiate the same HEVC base-layer format for capability checks and init.
        // StreamResolver excludes P5 and invalid base layers before they reach this renderer.
        private fun baseLayer(format: Format): Format = if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION && !hasDolbyVisionDecoder) {
            val color = baseLayerColor?.let { base ->
                // Retain mastering display / CLL bytes extracted from the delivered base layer.
                (format.colorInfo?.buildUpon() ?: ColorInfo.Builder())
                    .setColorSpace(base.colorSpace).setColorTransfer(base.colorTransfer).setColorRange(base.colorRange)
                    .setLumaBitdepth(base.lumaBitdepth).setChromaBitdepth(base.chromaBitdepth).build()
            } ?: format.colorInfo
            format.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(null)
                .setColorInfo(color).build()
        } else format

        override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int =
            super.supportsFormat(mediaCodecSelector, baseLayer(format))

        override fun getDecoderInfos(mediaCodecSelector: MediaCodecSelector, format: Format, requiresSecureDecoder: Boolean): List<MediaCodecInfo> =
            super.getDecoderInfos(mediaCodecSelector, baseLayer(format), requiresSecureDecoder)

        override fun getMediaFormat(
            format: Format,
            codecMimeType: String,
            codecMaxValues: CodecMaxValues,
            codecOperatingRate: Float,
            deviceNeedsNoPostProcessWorkaround: Boolean,
            tunnelingAudioSessionId: Int,
        ): MediaFormat {
            val mf = super.getMediaFormat(
                baseLayer(format), codecMimeType, codecMaxValues, codecOperatingRate,
                deviceNeedsNoPostProcessWorkaround, tunnelingAudioSessionId,
            )
            if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION && codecMimeType == MimeTypes.VIDEO_H265 && !hasDolbyVisionDecoder) {
                mf.setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
                mf.setString(MediaFormat.KEY_MIME, MimeTypes.VIDEO_H265)
                baseLayerColor?.let { color ->
                    mf.setInteger(MediaFormat.KEY_COLOR_STANDARD, color.colorSpace)
                    mf.setInteger(MediaFormat.KEY_COLOR_TRANSFER, color.colorTransfer)
                    mf.setInteger(MediaFormat.KEY_COLOR_RANGE, color.colorRange)
                }
            }
            return mf
        }
    }
}
