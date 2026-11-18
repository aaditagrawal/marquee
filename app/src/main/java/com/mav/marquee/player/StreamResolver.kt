package com.mav.marquee.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import kotlinx.serialization.json.JsonObject

/** A playable stream chosen by the server for one item at one fallback level. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class Resolved(
    val mediaItem: MediaItem,
    val itemId: String,
    val playSessionId: String,
    val mediaSourceId: String,
    val mediaSource: JsonObject,
    val mode: DeviceProfile.Mode,
    /** "DirectPlay" or "Transcode" (Jellyfin PlayMethod). */
    val playMethod: String,
    val videoCopy: Boolean,
    val transcodeReasons: List<String>,
    val audioIndex: Int?,
    val subtitleIndex: Int?,
    val maxBitrate: Int,
    val progressiveStartMs: Long = 0,
) {
    val isTranscode get() = playMethod != "DirectPlay"
    /** Progressive transcoding has no byte ranges or Cues; seeks restart with StartTimeTicks; HLS is recovery only. */
    val isProgressiveRemux get() = isTranscode && mediaItem.localConfiguration?.mimeType == MimeTypes.VIDEO_MATROSKA
    val durationMs get() = mediaSource.long("RunTimeTicks")?.div(10_000)
    val streams: List<JsonObject> get() = mediaSource.arr("MediaStreams").objs()
    fun videoStream() = streams.firstOrNull { it.str("Type") == "Video" }
    fun audioStreams() = streams.filter { it.str("Type") == "Audio" }
    fun subtitleStreams() = streams.filter { it.str("Type") == "Subtitle" }

    val label: String
        get() = when {
            !isTranscode -> "Direct play"
            videoCopy -> "Remux (video copied)"
            else -> "Transcode"
        }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object StreamResolver {
    /** Resume pre-start uses a server-relative stream, with the source timeline restored by PlayerActivity. */
    internal fun speculativeProgressive(r: Resolved, startMs: Long): Resolved? {
        if (!r.isTranscode || !useProgressiveRemux(r.mode, r.videoCopy, r.transcodeReasons, 0, true)) return null
        val actualStartMs = if (r.progressiveStartMs > 0) r.progressiveStartMs else startMs
        val uri = Uri.parse(progressiveRemuxUrl(r.mediaItem.localConfiguration!!.uri, r.subtitleIndex ?: -1, actualStartMs))
        return Resolved(r.mediaItem.buildUpon().setUri(uri).setMimeType(MimeTypes.VIDEO_MATROSKA).build(),
            r.itemId, r.playSessionId, r.mediaSourceId, r.mediaSource, r.mode, r.playMethod, r.videoCopy,
            r.transcodeReasons, r.audioIndex, r.subtitleIndex, r.maxBitrate, actualStartMs)
    }


    internal fun useProgressiveRemux(mode: DeviceProfile.Mode, copy: Boolean, reasons: List<String>,
        startMs: Long, preferProgressive: Boolean): Boolean =
        preferProgressive && startMs >= 0L && mode == DeviceProfile.Mode.Direct && copy &&
            reasons.isNotEmpty() && reasons.all { it.trim().startsWith("Audio") }

    internal fun hasHdrBaseLayer(video: JsonObject?): Boolean =
        video?.str("Codec") == "hevc" && video.int("DvProfile") != 5 &&
            video.str("ColorTransfer") in setOf("smpte2084", "arib-std-b67")

    private val rangeReasons = setOf("VideoRangeTypeNotSupported", "VideoRangeNotSupported")

    /** A range mismatch can be repaired; decoder, bitrate and subtitle constraints cannot. */
    internal fun rangeOnlyHdrDowngrade(video: JsonObject?, reasons: List<String>): Boolean =
        hasHdrBaseLayer(video) && reasons.any { it.trim() in rangeReasons } &&
            reasons.all { it.trim() in rangeReasons || it.trim().startsWith("Audio") }

    internal fun hdrCopyHlsUrl(url: Uri, subtitleIndex: Int): String {
        val b = url.buildUpon().clearQuery()
        // The master unconditionally offers same-bandwidth SDR encodes in Jellyfin 10.11.
        // Opening its copy media playlist keeps every subsequent segment/seek on HDR video copy.
        if (url.lastPathSegment == "master.m3u8")
            b.path(url.path.orEmpty().substringBeforeLast('/') + "/main.m3u8")
        val removed = setOf("videorangetype", "hevc-rangetype", "hevc-videorangetype",
            "subtitlestreamindex", "starttimeticks")
        for (key in url.queryParameterNames) {
            if (key.lowercase() !in removed) b.appendQueryParameter(key, url.getQueryParameter(key))
        }
        return b.appendQueryParameter("hevc-rangetype", DeviceProfile.remuxRanges())
            .appendQueryParameter("SubtitleStreamIndex", subtitleIndex.toString()).build().toString()
    }

    /** Keep the negotiated audio/stream identity and codec caps, but remove HLS-only options. */
    internal fun progressiveRemuxUrl(hls: Uri, subtitleIndex: Int? = null, startMs: Long = 0): String {
        val clean = Uri.parse(safeUrl(hls, false))
        val b = clean.buildUpon().path(clean.path.orEmpty().substringBeforeLast('/') + "/stream.mkv").clearQuery()
        val removed = setOf("segmentcontainer", "segmentlength", "minsegments", "breakonnonkeyframes",
            "starttimeticks", "copytimestamps", "static", "context", "allowaudiostreamcopy", "subtitlemethod", "subtitlecodec", "alwaysburninsubtitlewhentranscoding")
        for (key in clean.queryParameterNames) {
            if (subtitleIndex != null && key.equals("SubtitleStreamIndex", ignoreCase = true)) continue
            if (key.lowercase() !in removed) b.appendQueryParameter(key, clean.getQueryParameter(key))
        }
        if (subtitleIndex != null) b.appendQueryParameter("SubtitleStreamIndex", subtitleIndex.toString())
        return b.appendQueryParameter("Static", "false").appendQueryParameter("Context", "Streaming")
            .appendQueryParameter("StartTimeTicks", (startMs * 10_000).toString()).appendQueryParameter("CopyTimestamps", "true")
            .appendQueryParameter("SubtitleMethod", if ((subtitleIndex ?: -1) >= 0) "Embed" else "External")
            .appendQueryParameter("AlwaysBurnInSubtitleWhenTranscoding", "false")
            .appendQueryParameter("AllowAudioStreamCopy", "false").build().toString()
    }

    /** Authentication stays in the shared OkHttp header, never in media URLs or error logs. */
    internal fun safeUrl(url: Uri, capH264: Boolean, copyCodec: String? = null, limits: StreamLimits = StreamLimits.of()): String {
        val b = url.buildUpon().clearQuery()
        val seen = mutableSetOf<String>()
        for (key in url.queryParameterNames) {
            // Uri turns an empty field in Jellyfin's '?&...' query into an empty name. Rebuilding
            // it as '=' crashes StreamingHelpers.ParseStreamOptions at param.Key[0].
            if (key.isBlank() || !seen.add(key.lowercase())) continue
            if (key.replace("_", "").lowercase() in setOf("apikey", "accesstoken", "token")) continue
            if ((capH264 || copyCodec != null) && key.lowercase() in setOf("maxwidth", "maxheight", "width", "height", "videocodec", "allowvideostreamcopy")) continue
            // Streaming options are singular. Preserve the first value, not a
            // comma-joined duplicate that changes codec/numeric option parsing.
            b.appendQueryParameter(key, url.getQueryParameter(key).orEmpty())
        }
        if (capH264) b.appendQueryParameter("VideoCodec", "h264")
            .appendQueryParameter("MaxWidth", limits.h264TranscodeWidth.toString()).appendQueryParameter("MaxHeight", limits.h264TranscodeHeight.toString())
            .appendQueryParameter("AllowVideoStreamCopy", "false")
        if (copyCodec != null) b.appendQueryParameter("VideoCodec", copyCodec)
            .appendQueryParameter("AllowVideoStreamCopy", "true")
            .appendQueryParameter("MaxWidth", (if (copyCodec == "hevc") limits.hevcMaxWidth else limits.h264CopyWidth).toString())
            .appendQueryParameter("MaxHeight", (if (copyCodec == "hevc") limits.hevcMaxHeight else limits.h264CopyHeight).toString())
        return b.build().toString()
    }

    /** Encoding bitrate never changes PlaybackInfo's direct-play eligibility. */
    internal fun transcodeUrl(url: Uri, video: JsonObject?, h264Requested: Boolean, maxBitrate: Int, limits: StreamLimits = StreamLimits.of()): String {
        // Devices without a hardware HEVC decoder always receive H.264 transcodes.
        val h264 = h264Requested || !limits.hevc
        val clean = Uri.parse(safeUrl(url, false, limits = limits))
        val b = clean.buildUpon().clearQuery()
        val replaceEac3 = clean.getQueryParameter("AudioCodec")?.split(',')?.contains("eac3") == true
        val replaced = setOf("allowaudiostreamcopy", "eac3-profile", "videocodec", "allowvideostreamcopy", "enableautostreamcopy", "maxwidth", "maxheight", "width", "height", "videobitrate", "h264-profile", "h264-level", "h264-videobitdepth", "hevc-profile", "hevc-level", "hevc-videobitdepth", "hevc-rangetype")
        for (key in clean.queryParameterNames) if (key.lowercase() !in replaced && !(replaceEac3 && key.equals("AudioCodec", true)))
            b.appendQueryParameter(key, clean.getQueryParameter(key))
        // Jellyfin 10.11 emits a null output audio codec for HEVC+EAC3 -> HEVC+EAC3 HLS.
        // AC3 preserves 5.1 and avoids its GetAudioArguments null-codec failure.
        if (replaceEac3) b.appendQueryParameter("AudioCodec", "ac3")
        b.appendQueryParameter("AllowAudioStreamCopy", "false")
        b.appendQueryParameter("VideoCodec", if (h264) "h264" else "hevc")
            .appendQueryParameter("AllowVideoStreamCopy", "false")
            .appendQueryParameter("EnableAutoStreamCopy", "false")
            .appendQueryParameter("VideoBitrate", (if (maxBitrate > 0) minOf(maxBitrate, 20_000_000) else 20_000_000).toString())
        if (h264) {
            val size = TranscodePolicy.h264Size(video?.int("Width") ?: 1920, video?.int("Height") ?: 1080,
                limits.h264TranscodeWidth, limits.h264TranscodeHeight)
            b.appendQueryParameter("Width", size.first.toString()).appendQueryParameter("Height", size.second.toString())
                .appendQueryParameter("MaxWidth", size.first.toString()).appendQueryParameter("MaxHeight", size.second.toString())
                .appendQueryParameter("h264-profile", "high").appendQueryParameter("h264-level", limits.h264Level.toString())
                .appendQueryParameter("h264-videobitdepth", "8")
        } else {
            val hdrSource = video?.str("ColorTransfer") in setOf("smpte2084", "arib-std-b67") && video?.int("DvProfile") != 5
            b.appendQueryParameter("MaxWidth", limits.hevcMaxWidth.toString()).appendQueryParameter("MaxHeight", limits.hevcMaxHeight.toString())
                .appendQueryParameter("hevc-profile", if (hdrSource && limits.hevcMain10) "main10,main" else "main").appendQueryParameter("hevc-level", limits.hevcLevel.toString())
                .appendQueryParameter("hevc-videobitdepth", if (limits.hevcMain10) "10" else "8")
                .appendQueryParameter("hevc-rangetype", DeviceProfile.remuxRanges())
            // Jellyfin's master offers SDR alternatives even for HDR-capable requests.
            if (url.lastPathSegment == "master.m3u8") b.path(url.path.orEmpty().substringBeforeLast('/') + "/main.m3u8")
        }
        return b.build().toString()
    }

    suspend fun resolve(
        item: JsonObject,
        mode: DeviceProfile.Mode,
        maxBitrate: Int,
        startMs: Long,
        audioIndex: Int?,
        subtitleIndex: Int?,
        prefs: PlayerPrefs,
        preferredSourceId: String?,
        preferProgressive: Boolean = true,
        preferNextKeyframe: Boolean = false,
    ): Resolved {
        val itemId = item.str("Id")!!
        val source = item.arr("MediaSources").objs().firstOrNull { it.str("Id") == preferredSourceId }
            ?: item.arr("MediaSources").objs().firstOrNull()
        val streams = source?.arr("MediaStreams")?.objs().orEmpty()
        val chosenAudio = AudioSelection.choose(source, audioIndex, prefs.audioLanguage)
        val subtitleStreams = streams.filter { it.str("Type") == "Subtitle" }
        val chosenSub = subtitleIndex ?: when (prefs.subtitleMode) {
            "None" -> -1
            "OnlyForced" -> subtitleStreams.firstOrNull { it.bool("IsForced") }?.int("Index") ?: -1
            "Always" -> subtitleStreams.firstOrNull { it.str("Language") == prefs.subtitleLanguage }?.int("Index")
                ?: subtitleStreams.firstOrNull()?.int("Index") ?: -1
            "Smart" -> subtitleStreams.firstOrNull { it.bool("IsForced") }?.int("Index")
                ?: if (prefs.subtitleLanguage.isNotBlank() && streams.firstOrNull { it.int("Index") ==
                    (chosenAudio ?: source?.int("DefaultAudioStreamIndex")) }?.str("Language") != prefs.subtitleLanguage)
                    subtitleStreams.firstOrNull { it.str("Language") == prefs.subtitleLanguage }?.int("Index") ?: -1 else -1
            else -> if (prefs.subtitleLanguage.isNotBlank()) subtitleStreams.firstOrNull {
                it.str("Language") == prefs.subtitleLanguage }?.int("Index") else null
        }
        val video = streams.firstOrNull { it.str("Type") == "Video" }
        val effectiveMode = effectiveMode(video, mode)
        val profile = profileFor(video, maxBitrate, effectiveMode, ac3Fallback = mode == DeviceProfile.Mode.Remux,
            audio = streams.firstOrNull { it.str("Type") == "Audio" && it.int("Index") == chosenAudio })
        val selectedAudio = streams.firstOrNull { it.str("Type") == "Audio" && it.int("Index") == chosenAudio }
        val timedStartMs = if (preferProgressive && effectiveMode == DeviceProfile.Mode.Direct && selectedAudio != null &&
            !AudioSelection.isDirectCompatible(selectedAudio)) RemuxSyncIndex.startMs(source, itemId, startMs, preferNextKeyframe) else startMs
        val info = JfApi.playbackInfo(
            itemId, profile, maxBitrate, timedStartMs * 10_000, source?.str("Id"), chosenAudio, chosenSub, effectiveMode,
        )
        return fromPlaybackInfo(item, info, effectiveMode, maxBitrate, chosenAudio, chosenSub,
            preferredSourceId, Env.serverUrl, Env.deviceId, timedStartMs, preferProgressive)
    }

    internal fun effectiveMode(video: JsonObject?, mode: DeviceProfile.Mode): DeviceProfile.Mode =
        if (video?.int("DvProfile") == 5 || (video?.str("VideoRangeType") == "DOVIInvalid" && !hasHdrBaseLayer(video)))
            DeviceProfile.Mode.H264 else mode

    internal fun profileFor(video: JsonObject?, maxBitrate: Int, mode: DeviceProfile.Mode, ac3Fallback: Boolean = false, audio: JsonObject? = null): JsonObject =
        DeviceProfile.build(maxBitrate, effectiveMode(video, mode),
            if (effectiveMode(video, mode) == DeviceProfile.Mode.H264 ||
                (mode in setOf(DeviceProfile.Mode.Direct, DeviceProfile.Mode.Remux) && canCopyH264(video))) "h264" else "hevc",
            ac3Fallback || mode in setOf(DeviceProfile.Mode.Hevc, DeviceProfile.Mode.H264), aacAudio = audio?.int("Channels")?.let { it < 6 } == true)

    private fun canCopyH264(video: JsonObject?, limits: StreamLimits = StreamLimits.of()): Boolean = video?.str("Codec") == "h264" &&
        (video.int("BitDepth") ?: 8) <= 8 && (video.int("Width") ?: 0) <= limits.h264CopyWidth &&
        (video.int("Height") ?: 0) <= limits.h264CopyHeight && (video.int("Level") ?: 0) <= limits.h264Level &&
        video.str("Profile")?.lowercase() in setOf("high", "main", "baseline", "constrained baseline", "constrained high")

    /** The production URL resolver, separated from I/O so real PlaybackInfo fixtures can be replayed on the JVM. */
    internal fun fromPlaybackInfo(
        item: JsonObject,
        info: JsonObject,
        mode: DeviceProfile.Mode,
        maxBitrate: Int,
        chosenAudio: Int?,
        chosenSub: Int?,
        preferredSourceId: String?,
        server: String,
        deviceId: String,
        startMs: Long = 0,
        preferProgressive: Boolean = true,
    ): Resolved {
        val itemId = requireNotNull(item.str("Id"))
        val source = item.arr("MediaSources").objs().firstOrNull { it.str("Id") == preferredSourceId }
            ?: item.arr("MediaSources").objs().firstOrNull()
        val video = source?.arr("MediaStreams")?.objs()?.firstOrNull { it.str("Type") == "Video" }
        val effectiveMode = effectiveMode(video, mode)
        info.str("ErrorCode")?.let { throw IllegalStateException("Server refused playback: $it") }
        val ms = info.arr("MediaSources").objs().firstOrNull { preferredSourceId == null || it.str("Id") == preferredSourceId } ?: throw IllegalStateException("No media source")
        val playSessionId = info.str("PlaySessionId") ?: ""
        val msId = ms.str("Id") ?: itemId
        val defaultSub = chosenSub ?: ms.int("DefaultSubtitleStreamIndex")
        val selectedSubtitle = ms.arr("MediaStreams").objs().firstOrNull { it.int("Index") == defaultSub && it.str("Type") == "Subtitle" }
        val bitmapCopy = TrackConversionPolicy.canEmbedBitmap(selectedSubtitle) && effectiveMode != DeviceProfile.Mode.H264
        val direct = effectiveMode == DeviceProfile.Mode.Direct && ms.bool("SupportsDirectPlay")
        val transUrl = ms.str("TranscodingUrl")
        val isAudio = item.str("MediaType") == "Audio" || item.str("Type") == "Audio"

        val uri: String
        val mime: String?
        if (direct) {
            val kind = if (isAudio) "Audio" else "Videos"
            uri = "$server/$kind/$itemId/stream?static=true&mediaSourceId=$msId&playSessionId=$playSessionId" +
                "&deviceId=$deviceId" + (ms.str("ETag")?.let { "&Tag=$it" } ?: "")
            mime = null
        } else {
            if (transUrl == null) throw IllegalStateException("Server supplied no playable stream")
            val streamUrl = Uri.parse(if (transUrl.startsWith("http")) transUrl else server + transUrl)
            val keys = streamUrl.queryParameterNames.map { key ->
                if (key.isEmpty()) "<empty>" else key.replace(Regex("[^A-Za-z0-9_.-]"), "?")
            }
            Log.i("MarqueePlayer", "STREAM_QUERY server mode=$effectiveMode keys=$keys")
            val reasons = streamUrl.getQueryParameter("TranscodeReasons").orEmpty().split(',').filter { it.isNotBlank() }
            val rangeDowngrade = effectiveMode != DeviceProfile.Mode.H264 && rangeOnlyHdrDowngrade(video, reasons)
            val copy = effectiveMode in setOf(DeviceProfile.Mode.Direct, DeviceProfile.Mode.Remux) &&
                reasons.none { (it.trim().startsWith("Video") && !(rangeDowngrade && it.trim() in rangeReasons)) || it.trim() == "ContainerBitrateExceedsLimit" || (it.trim() == "SubtitleCodecNotSupported" && !bitmapCopy) } &&
                (defaultSub == -1 || bitmapCopy || streamUrl.getQueryParameter("SubtitleMethod") != "Encode") &&
                (rangeDowngrade || bitmapCopy || streamUrl.getQueryParameter("AllowVideoStreamCopy") != "false") &&
                streamUrl.getQueryParameter("VideoCodec")?.split(',')?.contains(video?.str("Codec")) == true
            val safe = if (copy) safeUrl(streamUrl, false, video?.str("Codec"))
                else transcodeUrl(streamUrl, video, effectiveMode == DeviceProfile.Mode.H264, maxBitrate)
            val hdrCopy = copy && hasHdrBaseLayer(video)
            val hlsUri = if (hdrCopy) {
                Log.w("MarqueePlayer", "HDR_DOWNGRADE reason=${if (rangeDowngrade) "VideoRangeTypeNotSupported" else "HlsMasterSdrFallback"} " +
                    "sourceRange=${video?.str("VideoRangeType")} prefer=HEVC-copy-base-layer")
                hdrCopyHlsUrl(Uri.parse(safe), defaultSub ?: -1)
            } else safe
            val progressive = !isAudio && streamUrl.path?.endsWith("master.m3u8") == true &&
                (copy && bitmapCopy || useProgressiveRemux(effectiveMode, copy, reasons.filterNot { rangeDowngrade && it.trim() in rangeReasons }, startMs, preferProgressive))
            uri = if (progressive) progressiveRemuxUrl(Uri.parse(hlsUri), defaultSub ?: -1, startMs) else hlsUri
            val finalUrl = Uri.parse(uri)
            Log.i("MarqueePlayer", "STREAM_QUERY final mode=$effectiveMode keys=${finalUrl.queryParameterNames} " +
                "video=${finalUrl.getQueryParameter("VideoCodec")} maxWidth=${finalUrl.getQueryParameter("MaxWidth")} " +
                "maxHeight=${finalUrl.getQueryParameter("MaxHeight")} copy=$copy")
            mime = if (progressive) MimeTypes.VIDEO_MATROSKA
                else if (ms.str("TranscodingSubProtocol") == "hls" || transUrl.contains(".m3u8")) MimeTypes.APPLICATION_M3U8 else null
        }
        val q = transUrl?.let { Uri.parse(it) }
        val reasons = q?.getQueryParameter("TranscodeReasons")?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        val videoCopy = !direct && Uri.parse(uri).getQueryParameter("AllowVideoStreamCopy") == "true"

        val defaultAudio = chosenAudio ?: ms.int("DefaultAudioStreamIndex")

        val subs = ms.arr("MediaStreams").objs()
            .filter { it.str("Type") == "Subtitle" && it.str("DeliveryMethod") == "External" && it.str("DeliveryUrl") != null }
            .mapNotNull { s ->
                val codec = s.str("Codec")?.lowercase() ?: return@mapNotNull null
                val m = when (codec) {
                    "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
                    "ass", "ssa" -> MimeTypes.TEXT_SSA
                    "vtt", "webvtt" -> MimeTypes.TEXT_VTT
                    else -> return@mapNotNull null
                }
                val url = s.str("DeliveryUrl")!!.let { if (it.startsWith("http")) it else server + it }
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(safeUrl(Uri.parse(url), false)))
                    .setId("jf:${s.int("Index")}")
                    .setMimeType(m)
                    .setLanguage(s.str("Language"))
                    .setLabel(s.str("DisplayTitle"))
                    .setSelectionFlags(if (s.int("Index") == defaultSub) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            }

        val md = MediaMetadata.Builder()
            .setTitle(item.str("Name"))
            .setArtist(item.str("AlbumArtist") ?: item.str("SeriesName"))
            .setAlbumTitle(item.str("Album"))
            .build()
        val mi = MediaItem.Builder()
            .setUri(safeUrl(Uri.parse(uri), false))
            .setMediaId(itemId)
            .setMimeType(mime)
            .setMediaMetadata(md)
            .setSubtitleConfigurations(subs)
            .build()
        return Resolved(
            mediaItem = mi, itemId = itemId, playSessionId = playSessionId, mediaSourceId = msId, mediaSource = ms,
            mode = if (!direct && !videoCopy && effectiveMode != DeviceProfile.Mode.H264) DeviceProfile.Mode.Hevc else effectiveMode, playMethod = if (direct) "DirectPlay" else "Transcode",
            videoCopy = videoCopy, transcodeReasons = reasons, audioIndex = defaultAudio, subtitleIndex = defaultSub,
            maxBitrate = maxBitrate,
            progressiveStartMs = if (mime == MimeTypes.VIDEO_MATROSKA) startMs else 0,
        )
    }
}
