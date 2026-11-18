package com.mav.marquee.player

import com.mav.marquee.R
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.ColorInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import android.view.SurfaceView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Media3 player. Contract: docs/architecture/PLAYER_CONTRACT.md (extras `itemId`, `startPositionMs`).
 * Decisions and measurements (reference device: Sony BRAVIA VU1): docs/architecture/PLAYBACK_DECISIONS.md.
 *
 * Log tag "MarqueePlayer" carries the measurement markers: `TTFF`, `SEEK`, `FALLBACK`, `STATS`.
 */
@UnstableApi
class PlayerActivity : ComponentActivity() {

    private companion object {
        const val TAG = "MarqueePlayer"
        private val USER_AGENT = "Marquee/${com.mav.marquee.BuildConfig.VERSION_NAME}"
        const val OSD_HIDE_MS = 4_000L
        const val STALL_MS = 8_000L
        const val STARTUP_STALL_MS = 8_000L
        const val TRANSCODE_FALLBACK_BITRATE = 12_000_000
        const val BUFFER_BYTES = 80 * 1024 * 1024
    }

    // --- playback state ---------------------------------------------------------------------
    private lateinit var prefs: PlayerPrefs
    private lateinit var player: ExoPlayer
    private var playerReleased = false
    private var stopped = false
    private var startupJob: Job? = null
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var allocator: DefaultAllocator
    private lateinit var renderers: MarqueeRenderersFactory
    private var playbackThread: HandlerThread? = null
    private var item: JsonObject? = null
    private var resolved: Resolved? = null
    private var mode = DeviceProfile.Mode.Direct
    private var preferProgressiveRemux = true
    private var confirmedVideoConversion: Triple<String, Int?, Int?>? = null
    private var transcodeBitrate = 0
    private var retriedSameLevel = false
    private var isMusic = false
    private var musicQueue: List<JsonObject> = emptyList()
    private var musicReportedId: String? = null
    private var musicLastPositionMs = 0L
    private var segments: List<MediaSegment> = emptyList()
    private var trickplay: Trickplay? = null
    private var nextEpisode: JsonObject? = null
    private var reportedStart = false
    private var loadJob: Job? = null
    private var progressJob: Job? = null

    // measurements
    private var tIntent = 0L
    private var tPrepare = 0L
    private var ttffMs = -1L
    private var tSeek = 0L
    private var seekReadyRecorded = false
    private var lastSeekMs = -1L
    private var stallStart = 0L
    private var videoDecoder = "-"
    private var audioDecoder = "-"
    private var audioOut = "-"
    private var bandwidth = 0L
    private var dropped = 0
    private var audioUnderruns = 0
    private val frameRate = PlaybackFrameRate()
    private var recovering = false
    private var lastPositionMs = -1L
    private var noProgressSince = 0L
    private var terminalFailure = false
    private var bufferingJob: Job? = null
    private val surfaceReady = kotlinx.coroutines.CompletableDeferred<Unit>()
    private var captionShift = 0

    // --- views ------------------------------------------------------------------------------
    private lateinit var root: FrameLayout
    private lateinit var playerView: SurfaceView
    private lateinit var videoFrame: AspectRatioFrameLayout
    private lateinit var captions: SubtitleView        // text cues, full screen (may use letterbox bars)
    private lateinit var bitmapCaptions: SubtitleView  // bitmap cues (PGS), aligned to the picture
    private lateinit var spinner: ProgressBar
    private lateinit var osd: PlayerOsd
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var seek: SeekBarView
    private lateinit var timeLeft: TextView
    private lateinit var timeRight: TextView
    private lateinit var buttons: LinearLayout
    private lateinit var playBtn: OsdIconButton
    private lateinit var episodesBtn: OsdIconButton
    private lateinit var backdrop: ImageView
    private var warmStream: Resolved? = null
    private var qualityBitrate: Int? = null
    private lateinit var thumb: ImageView
    private lateinit var skipBtn: OsdButton
    private lateinit var upNext: LinearLayout
    private lateinit var upNextText: TextView
    private lateinit var stats: TextView
    private lateinit var banner: TextView
    private var sheetGeneration = 0
    private lateinit var sheet: ScrollView
    private lateinit var sheetList: LinearLayout
    private lateinit var nowPlaying: LinearLayout
    private lateinit var npArt: ImageView
    private lateinit var npTitle: TextView
    private lateinit var npArtist: TextView

    private val commitScrubTask = Runnable { commitScrub() }
    private var thumbJob: Job? = null
    private val hideOsdTask = Runnable { if (scrubTarget < 0) hideOsd() }
    private var statsJob: Job? = null
    private var upNextJob: Job? = null
    private var upNextDismissed = false
    private var skippedSegments = mutableSetOf<MediaSegment>()
    private var verificationJob: Job? = null


    // ========================================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlaybackPrewarm.playbackStarting()
        tIntent = SystemClock.elapsedRealtime()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setWindowAnimations(0)
        prefs = PlayerPrefs(this)
        buildSurface()
        Log.i(TAG, "MARQUEE_PLAYER_VIEWS ${SystemClock.elapsedRealtime() - tIntent}")
        val itemId = intent.getStringExtra("itemId")
        if (itemId.isNullOrBlank()) {
            lifecycleScope.launch { surfaceReady.await(); buildOverlays(); fail("No item to play") }
            return
        }
        val startMs = intent.getLongExtra("startPositionMs", 0L)
        startupJob = lifecycleScope.launch {
            surfaceReady.await()
            buildOverlays()
            try { buildPlayer() } catch (e: Exception) {
                if (e is CancellationException) throw e
                fail("Couldn't start the player")
                return@launch
            }
            if (!Env.isLoggedIn) {
                fail("Not signed in")
                return@launch
            }
            load(itemId, startMs)
        }
    }

    private suspend fun buildPlayer() {
        check(!stopped)
        var constructed: ExoPlayer? = null
        val tunnel = intent.getBooleanExtra("tunnel", prefs.tunneling)
        try {
        withContext(kotlinx.coroutines.Dispatchers.Default) {
        allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        // ~470 MB free on the TV: cap the media buffer at 80 MB (≈ 21 s of a 30 Mbps 4K remux) and start
        // after 250 ms of media. Audio readiness still gates the first frame. No back buffer.
        val load = DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(15_000, 50_000, 250, 250)
            .setTargetBufferBytes(BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(0, false)
            .build()
        trackSelector = DefaultTrackSelector(this@PlayerActivity)
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setTunnelingEnabled(false)
                .setExceedRendererCapabilitiesIfNecessary(false)
                .setPreferredAudioLanguage(prefs.audioLanguage.ifBlank { null })
                .setPreferredTextLanguage(prefs.subtitleLanguage.ifBlank { null })
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, prefs.subtitleMode == "None"),
        )
        val dsf = OkHttpDataSource.Factory(Env.http.newBuilder().readTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()).setUserAgent(USER_AGENT)
        renderers = MarqueeRenderersFactory(this@PlayerActivity)
        val thread = HandlerThread("MarqueeAudioPlayback", Process.THREAD_PRIORITY_URGENT_AUDIO)
        playbackThread = thread
        thread.start()
        // Return codec-free configuration to Main before constructing the sole player.
        ExoPlayer.Builder(this@PlayerActivity, renderers)
            .setLooper(android.os.Looper.getMainLooper())
            // Media3 invokes both audio and video renderers on this playback looper.
            .setPlaybackLooper(thread.looper)
            .setLoadControl(load)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(SpeculativeDataSource.wrap(PrewarmBytes.wrap(dsf)), PrewarmExtractors))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
        }.let { builder ->
            check(!stopped)
            ActivePlayerOwner.replace(this@PlayerActivity) { stopPlayback() }
            constructed = builder.build()
        }
        } catch (e: Exception) {
            withContext(NonCancellable + kotlinx.coroutines.Dispatchers.Main.immediate) { constructed?.release() }
            playbackThread?.quitSafely()
            playbackThread = null
            ActivePlayerOwner.clear(this@PlayerActivity)
            throw e
        }
        player = checkNotNull(constructed)
        playerReleased = false
        player.setPlaybackSpeed(PlaybackSessionSpeed.speed)
        player.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        player.setVideoSurfaceView(playerView)
        player.addListener(playerListener)
        player.addAnalyticsListener(analytics)
        player.setVideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
            renderers.recordExactFrame(presentationTimeUs)
        }
        if (com.mav.marquee.BuildConfig.DEBUG) {
            java.io.File(filesDir, "device-profile.json").writeText(DeviceProfile.build().toString())
        }
        if (intent.getBooleanExtra("diagnostics", prefs.diagnostics)) toggleStats()
        Log.i(TAG, "init tunnel=$tunnel ffmpeg=${runCatching { FfmpegLibrary.getVersion() }.getOrNull()}")
    }

    // ========================================================================================
    // Loading / fallback

    private fun load(itemId: String, startMs: Long) {
        loadJob?.cancel()
        confirmedVideoConversion = null
        preferProgressiveRemux = true
        backdrop.visibility = View.VISIBLE
        item = null
        loadJob = lifecycleScope.launch {
            spinner.visibility = View.VISIBLE
            terminalFailure = false
            try {
                val warm = if (intent.getStringExtra("playbackMode") == null) PlaybackPrewarm.take(
                    this@PlayerActivity, itemId, startMs, intent.getStringExtra("mediaSourceId"),
                    if (intent.hasExtra("audioStreamIndex")) intent.getIntExtra("audioStreamIndex", -1) else null,
                    if (intent.hasExtra("subtitleStreamIndex")) intent.getIntExtra("subtitleStreamIndex", -1) else null,
                ) else { PlaybackPrewarm.discardPending(); null }
                val it = warm?.item ?: JfApi.item(itemId)
                warmStream = warm?.resolved
                if (warm?.backdrop != null) backdrop.setImageBitmap(warm.backdrop)
                else launch(kotlinx.coroutines.Dispatchers.IO) {
                    val art = PlaybackPrewarm.loadBackdrop(it)
                    withContext(kotlinx.coroutines.Dispatchers.Main) { if (item?.str("Id") == itemId) backdrop.setImageBitmap(art) }
                }
                item = it
                val type = it.str("Type")
                if (type in setOf("MusicAlbum", "Playlist", "MusicArtist", "Audio") || it.str("MediaType") == "Audio") {
                    startMusic(it)
                    return@launch
                }
                isMusic = false
                nowPlaying.visibility = View.GONE
                mode = if (com.mav.marquee.BuildConfig.DEBUG) when (intent.getStringExtra("playbackMode")) {
                    "Remux" -> DeviceProfile.Mode.Remux
                    "Hevc" -> DeviceProfile.Mode.Hevc
                    "H264" -> DeviceProfile.Mode.H264
                    else -> DeviceProfile.Mode.Direct
                } else DeviceProfile.Mode.Direct
                transcodeBitrate = 0
                retriedSameLevel = false
                bindTitles(it)
                segments = emptyList(); skippedSegments.clear(); nextEpisode = null; upNextDismissed = false
                hideUpNext()
                val audio = if (itemId == intent.getStringExtra("itemId") && intent.hasExtra("audioStreamIndex")) intent.getIntExtra("audioStreamIndex", -1) else null
                val sub = if (itemId == intent.getStringExtra("itemId") && intent.hasExtra("subtitleStreamIndex")) intent.getIntExtra("subtitleStreamIndex", -1) else null
                startStream(startMs, audio, sub)
                launch { loadExtras(it) }
            } catch (e: Exception) {
                if (e is CancellationException && !isActive) throw e
                Log.e(TAG, "load failed ${e.javaClass.simpleName}")
                if (item != null) fallback("Couldn't start the stream", stall = false) else fail("Couldn't load this title")
            }
        }
    }

    private suspend fun startStream(startMs: Long, audioIndex: Int?, subtitleIndex: Int?, playWhenReady: Boolean = true) {
        val it = item ?: return
        val old = resolved
        Log.i(TAG, "QUALITY stored=${prefs.storedMaxBitrate} explicit=${qualityBitrate ?: 0} defaultUnlimited=${prefs.maxBitrate == 0} bandwidthEstimate=$bandwidth")
        val maxBr = qualityBitrate ?: if (mode == DeviceProfile.Mode.H264 && transcodeBitrate > 0) transcodeBitrate else prefs.maxBitrate
        var r = warmStream?.also { warmStream = null; Log.i(TAG, "PREWARM hit item=${it.itemId}") } ?: withTimeout(12_000) { StreamResolver.resolve(it, mode, maxBr, startMs, audioIndex, subtitleIndex, prefs, intent.getStringExtra("mediaSourceId").takeIf { it != null && item?.str("Id") == intent.getStringExtra("itemId") }, preferProgressiveRemux, preferNextKeyframe = tSeek > 0) }
        if (com.mav.marquee.BuildConfig.DEBUG && r.mode == DeviceProfile.Mode.H264 && intent.hasExtra("decoderTestWidth")) {
            val width = intent.getIntExtra("decoderTestWidth", 1920)
            val height = intent.getIntExtra("decoderTestHeight", 1080)
            require(width in 16..1920 && width % 2 == 0 && height in 2..1080 && height % 2 == 0)
            val original = r.mediaItem.localConfiguration!!.uri
            val b = original.buildUpon().clearQuery()
            for (key in original.queryParameterNames) if (key.lowercase() !in setOf("width", "height", "maxwidth", "maxheight", "h264-level"))
                b.appendQueryParameter(key, original.getQueryParameter(key))
            b.appendQueryParameter("Width", width.toString()).appendQueryParameter("Height", height.toString())
                .appendQueryParameter("MaxWidth", width.toString()).appendQueryParameter("MaxHeight", height.toString())
                .appendQueryParameter("h264-level", "40")
            r = Resolved(r.mediaItem.buildUpon().setUri(b.build()).build(), r.itemId, r.playSessionId, r.mediaSourceId,
                r.mediaSource, r.mode, r.playMethod, r.videoCopy, r.transcodeReasons, r.audioIndex, r.subtitleIndex, r.maxBitrate, r.progressiveStartMs)
        }
        if (com.mav.marquee.BuildConfig.DEBUG && intent.hasExtra("decoderTestFile")) {
            val name = requireNotNull(intent.getStringExtra("decoderTestFile"))
            require(name.matches(Regex("r13-[0-9]+\\.mp4")))
            val local = android.net.Uri.fromFile(java.io.File(filesDir, name))
            r = Resolved(r.mediaItem.buildUpon().setUri(local).setMimeType(androidx.media3.common.MimeTypes.VIDEO_MP4).build(), r.itemId,
                r.playSessionId, r.mediaSourceId, r.mediaSource, r.mode, r.playMethod, false,
                r.transcodeReasons, r.audioIndex, r.subtitleIndex, r.maxBitrate)
        }
        val selectedAudio = r.audioStreams().firstOrNull { s -> s.int("Index") == r.audioIndex }
        val selectedSubtitle = r.subtitleStreams().firstOrNull { s -> s.int("Index") == r.subtitleIndex }
        if (r.isTranscode && !r.videoCopy && StreamResolver.hasHdrBaseLayer(r.videoStream()) &&
            selectedSubtitle?.str("Codec")?.lowercase() in setOf("pgssub", "pgs", "dvdsub", "vobsub", "dvbsub")) {
            JfApi.stopEncoding(r.playSessionId)
            showBanner("Subtitles need conversion · picture will be SDR", 6_000)
            startStream(startMs, audioIndex, -1, playWhenReady)
            return
        }
        if (r.isTranscode && !r.videoCopy && TrackConversionPolicy.needsVideoConversion(selectedAudio, selectedSubtitle) &&
            confirmedVideoConversion != Triple(r.itemId, r.audioIndex, r.subtitleIndex)) {
            // Detail launch hints/default tracks must receive the same warning as in-player changes.
            // Negotiation starts no media request; an external text choice can still retain video copy.
            resolved = r
            spinner.visibility = View.GONE
            confirmVideoConversion(r.audioIndex, r.subtitleIndex, initial = !reportedStart, startMs = startMs)
            return
        }
        if (old != null && old.isTranscode && old.playSessionId != r.playSessionId) {
            lifecycleScope.launch { JfApi.stopEncoding(old.playSessionId) }
        }
        resolved = r
        // FFmpeg declares tunneling support in this extension, but the reference TV cannot open tunneled
        // multichannel PCM. Restrict HW AV sync (debug-only experiment) to platform AC3/EAC3 decoding.
        val codec = r.audioStreams().firstOrNull { it.int("Index") == r.audioIndex }?.str("Codec")?.lowercase()
        val tunnel = intent.getBooleanExtra("tunnel", prefs.tunneling) && !r.isTranscode &&
            codec in setOf("ac3", "eac3")
        trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingEnabled(tunnel))
        Log.i(TAG, "TUNNEL requested=${intent.getBooleanExtra("tunnel", prefs.tunneling)} enabled=$tunnel codec=$codec")
        mode = r.mode
        val video = r.videoStream()
        val sizeLabel = if (r.mode == DeviceProfile.Mode.H264) "1080p" else if ((video?.int("Width") ?: 0) > 1920) "4K" else "1080p"
        val hdr = if (r.isTranscode && !r.videoCopy) "" else if (video?.str("ColorTransfer") == "smpte2084") " HDR10" else if (video?.str("ColorTransfer") == "arib-std-b67") " HLG" else ""
        showBanner("Starting $sizeLabel$hdr · ${r.label}", 12_000)
        renderers.beginAudioStartup(r.audioIndex != null)
        renderers.baseLayerColor = if (!r.isTranscode || r.videoCopy) when (video?.str("ColorTransfer")) {
            "smpte2084" -> ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020)
                .setColorRange(C.COLOR_RANGE_LIMITED).setColorTransfer(C.COLOR_TRANSFER_ST2084)
                .setLumaBitdepth(video.int("BitDepth") ?: 10).setChromaBitdepth(video.int("BitDepth") ?: 10).build()
            "arib-std-b67" -> ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020)
                .setColorRange(C.COLOR_RANGE_LIMITED).setColorTransfer(C.COLOR_TRANSFER_HLG)
                .setLumaBitdepth(video.int("BitDepth") ?: 10).setChromaBitdepth(video.int("BitDepth") ?: 10).build()
            else -> null
        } else null
        reportedStart = false
        dropped = 0
        audioUnderruns = 0
        frameRate.reset(player.isPlaying, SystemClock.elapsedRealtime())
        lastPositionMs = -1
        noProgressSince = 0
        ttffMs = -1
        tPrepare = SystemClock.elapsedRealtime()
        if (tSeek > 0) Log.i(TAG, "SEEK restart_prepare_ms=${tPrepare - tSeek}")
        Log.i(TAG, "PREPARE item=${r.itemId} method=${r.playMethod} copy=${r.videoCopy} mode=$mode reasons=${r.transcodeReasons} start=${if (r.isProgressiveRemux) r.progressiveStartMs else startMs} requested=$startMs")
        if (r.isTranscode) Log.i(TAG, "ENCODING item=${r.itemId} device=${Env.deviceId} session=${r.playSessionId}")
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO).clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, r.subtitleIndex == null || r.subtitleIndex < 0).build()
        backdrop.visibility = View.VISIBLE
        spinner.visibility = View.VISIBLE
        trickplay = Trickplay.from(it, r.mediaSourceId)
        if (startMs > 0) showThumb(startMs)
        // Timed MKV starts on the selected source GOP and plays at local zero.
        // Initial track selection does not apply ExoPlayer's user-seek tolerance.
        val uri = checkNotNull(r.mediaItem.localConfiguration).uri
        val localStartMs = if (r.isProgressiveRemux) 0L else if (!r.isTranscode && startMs > 0) {
            PrewarmExtractors.resumeMs(uri, startMs)
                ?: RemuxSyncIndex.startMs(r.mediaSource, r.itemId, startMs, remux = false)
        } else startMs
        val exactFallback = intent.getBooleanExtra("exactPlaybackFallback", false) &&
            r.isProgressiveRemux && !RemuxSyncIndex.hasIndex(r.mediaSource, r.itemId) &&
            startMs - r.progressiveStartMs > 8_000
        val initialMs = if (exactFallback) ExactPlaybackStart.localMs(startMs, r.progressiveStartMs) else localStartMs
        player.setSeekParameters(if (exactFallback) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        renderers.beginExactStart(startMs, r.progressiveStartMs, if (tSeek > 0) tSeek else tIntent, exactFallback)
        PrewarmExtractors.exactTarget(uri, if (exactFallback) initialMs else 0, r.audioIndex != null,
            r.durationMs?.let { (it - r.progressiveStartMs).coerceAtLeast(0) })
        Log.i(TAG, "SYNC_START requested=$startMs local=$initialMs sourceStart=${r.progressiveStartMs} exact=$exactFallback")
        if (com.mav.marquee.BuildConfig.DEBUG && intent.hasExtra("decoderTestFile")) {
            player.setMediaSource(androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
                androidx.media3.datasource.FileDataSource.Factory()).createMediaSource(r.mediaItem), initialMs)
        } else player.setMediaItem(r.mediaItem, initialMs)
        player.prepare()
        player.playWhenReady = playWhenReady
        stallStart = 0
        trickplay = Trickplay.from(it, r.mediaSourceId)
    }

    /** Progressive recovery, video-copy remux, HEVC, then aligned H.264 SDR. */
    private fun fallback(reason: String, stall: Boolean) {
        if (recovering || terminalFailure) return
        verificationJob?.cancel()
        recovering = true
        val pos = playbackPositionMs().coerceAtLeast(0)
        val r = resolved
        val next = when {
            r?.isProgressiveRemux == true -> DeviceProfile.Mode.Direct.also { preferProgressiveRemux = false }
            else -> TranscodePolicy.next(r?.mode ?: mode)
        }
        val msg: String
        if (next != null) {
            mode = next
            if (next == DeviceProfile.Mode.H264) transcodeBitrate = if (stall) 10_000_000 else TRANSCODE_FALLBACK_BITRATE
            msg = when (next) {
                DeviceProfile.Mode.Direct, DeviceProfile.Mode.Remux -> "Switching to a compatible stream…"
                DeviceProfile.Mode.Hevc -> "Switching to HEVC…"
                DeviceProfile.Mode.H264 -> "Switching to 1080p…"
            }
        } else {
            // A decoder error is unchanged by halving the bitrate of the same codec/size.
            if (!stall) {
                recovering = false
                fail("$reason. Playback can't continue with this decoder.")
                return
            }
            val br = (if (transcodeBitrate > 0) transcodeBitrate else TRANSCODE_FALLBACK_BITRATE) / 2
            if (br < 3_000_000) {
                recovering = false
                fail("$reason. Playback can't continue on this connection.")
                return
            }
            transcodeBitrate = br
            msg = "Trying a lower streaming quality…"
        }
        Log.w(TAG, "FALLBACK -> $mode bitrate=$transcodeBitrate at=$pos reason=$reason")
        showBanner(msg, 6000)
        reportStopped()
        player.stop()
        spinner.visibility = View.VISIBLE
        retriedSameLevel = false
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try {
                val subtitleIndex = if (!preferProgressiveRemux && r?.isProgressiveRemux == true &&
                    TrackConversionPolicy.canEmbedBitmap(r.subtitleStreams().firstOrNull { it.int("Index") == r.subtitleIndex })) {
                    showBanner("Subtitles unavailable on the recovery stream", 6_000)
                    -1
                } else r?.subtitleIndex
                startStream(pos, r?.audioIndex, subtitleIndex)
            } catch (e: Exception) {
                recovering = false
                if (e is CancellationException && !isActive) throw e
                Log.e(TAG, "fallback failed ${e.javaClass.simpleName}")
                if (mode != DeviceProfile.Mode.H264 || transcodeBitrate > 3_000_000) fallback("Server error", stall = false)
                else fail("Couldn't start playback")
            } finally { recovering = false }
        }
    }

    private suspend fun loadExtras(it: JsonObject) {
        val id = it.str("Id") ?: return
        val runtimeMs = (it.long("RunTimeTicks") ?: 0) / 10_000
        val segs = parseMediaSegments(id, JfApi.mediaSegments(id)).toMutableList()
        // Coverage is per episode. Use named chapters only when real segments are absent.
        if (segs.isEmpty()) {
            val ch = it.arr("Chapters").objs()
            ch.forEachIndexed { i, c ->
                val name = c.str("Name")?.lowercase() ?: return@forEachIndexed
                val start = (c.long("StartPositionTicks") ?: 0) / 10_000
                val end = ch.getOrNull(i + 1)?.long("StartPositionTicks")?.div(10_000) ?: runtimeMs
                val type = when {
                    Regex("\\b(intro|opening|op)\\b").containsMatchIn(name) -> "Intro"
                    Regex("\\b(recap|previously)\\b").containsMatchIn(name) -> "Recap"
                    Regex("credits|outro|ending|\\bed\\b").containsMatchIn(name) -> "Outro"
                    else -> null
                }
                if (type != null && end > start) segs += MediaSegment(type, start, end)
            }
        }
        segments = segs
        Log.i(TAG, "SEGMENTS item=$id ranges=${segs.map { "${it.type}:${it.startMs}-${it.endMs}" }} intro=${prefs.introMode} credits=${prefs.creditsMode} autoplay=${prefs.autoPlayNext}")
        seek.chapterMs = it.arr("Chapters").objs().map { c -> (c.long("StartPositionTicks") ?: 0) / 10_000 }.toLongArray()
        seek.segments = segs.map { s -> s.startMs to s.endMs }
        if (it.str("Type") == "Episode") {
            val series = it.str("SeriesId")
            val successor = if (series != null) runCatching { JfApi.nextEpisode(series, id) }.getOrNull() else null
            if (item?.str("Id") != id) return
            nextEpisode = successor
            osd.setNextEpisodeAvailable(successor != null)
        }
        Log.i(TAG, "NEXT_EPISODE item=$id next=${nextEpisode?.str("Id")}")
    }

    // ========================================================================================
    // Music

    private suspend fun startMusic(it: JsonObject) {
        isMusic = true
        val type = it.str("Type")
        musicQueue = when (type) {
            "Audio" -> listOf(it)
            "MusicAlbum" -> JfApi.children(it.str("Id")!!, "ParentIndexNumber,IndexNumber,SortName").objs()
            "Playlist" -> JfApi.children(it.str("Id")!!, "ListItemOrder").objs()
            else -> JfApi.instantMix(it.str("Id")!!).objs()
        }
        if (musicQueue.isEmpty()) {
            fail("Nothing to play here")
            return
        }
        nowPlaying.visibility = View.VISIBLE
        val br = prefs.maxBitrate
        val items = musicQueue.map { a ->
            val id = a.str("Id")!!
            val url = "${Env.serverUrl}/Audio/$id/universal?userId=${Env.userId}&deviceId=${Env.deviceId}" +
                "&maxStreamingBitrate=$br&container=mp3,flac,m4a|aac,m4a|alac,ogg|opus,ogg|vorbis,webm|opus,mka,wav" +
                "&transcodingContainer=mp4&transcodingProtocol=hls&audioCodec=aac"
            androidx.media3.common.MediaItem.Builder().setUri(url).setMediaId(id)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(a.str("Name")).setArtist(a.str("AlbumArtist") ?: a.arr("Artists").firstOrNull()?.toString()?.trim('"'))
                        .setAlbumTitle(a.str("Album")).build(),
                ).build()
        }
        resolved = null
        tPrepare = SystemClock.elapsedRealtime()
        player.setMediaItems(items, 0, 0)
        player.prepare()
        player.playWhenReady = true
    }

    private fun bindMusic(index: Int) {
        val a = musicQueue.getOrNull(index) ?: return
        musicReportedId?.let { oldId ->
            val body = musicReportBody(oldId, musicLastPositionMs)
            reports.submit("/Sessions/Playing/Stopped", body)
        }
        musicReportedId = a.str("Id")
        musicLastPositionMs = 0
        retriedSameLevel = false
        npTitle.text = a.str("Name")
        npArtist.text = listOfNotNull(a.str("AlbumArtist"), a.str("Album")).joinToString(" · ")
        title.text = a.str("Name")
        subtitle.text = "${index + 1} / ${musicQueue.size}"
        val albumId = a.str("AlbumId") ?: a.str("Id")
        lifecycleScope.launch {
            val bmp = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    Env.http.newCall(
                        okhttp3.Request.Builder()
                            .url("${Env.serverUrl}/Items/$albumId/Images/Primary?fillWidth=560&fillHeight=560&quality=85").build(),
                    ).execute().use { r -> r.body?.bytes()?.let { b -> android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size) } }
                }.getOrNull()
            }
            npArt.setImageBitmap(bmp)
        }
        reportMusicStart(a)
    }

    private fun musicReportBody(id: String, positionMs: Long) = buildJsonObject {
        put("ItemId", id); put("PlayMethod", "DirectStream"); put("CanSeek", true)
        put("PositionTicks", positionMs.coerceAtLeast(0) * 10_000)
        put("IsPaused", !player.playWhenReady)
    }

    private fun reportMusicStart(a: JsonObject) {
        val id = a.str("Id") ?: return
        val body = musicReportBody(id, 0)
        reports.submit("/Sessions/Playing", body)
    }

    // ========================================================================================
    // Player callbacks

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            Log.i(TAG, "PLAYBACK_STATE state=$state position=${player.currentPosition} buffered=${player.bufferedPosition} loading=${player.isLoading} play=${player.playWhenReady}")
            bufferingJob?.cancel()
            if (state == Player.STATE_BUFFERING && ttffMs >= 0) {
                bufferingJob = lifecycleScope.launch { delay(400); spinner.visibility = View.VISIBLE }
            } else spinner.visibility = if (!terminalFailure && (state == Player.STATE_BUFFERING || ttffMs < 0 || tSeek > 0)) View.VISIBLE else View.GONE
            if (state == Player.STATE_READY) {
                stallStart = 0
                applyInitialTracks()
                if (tSeek > 0 && !seekReadyRecorded) {
                    Log.i(TAG, "SEEK ready_ms=${SystemClock.elapsedRealtime() - tSeek}")
                    seekReadyRecorded = true
                }
                if (!reportedStart && !isMusic) reportStart()
            }
            if (state == Player.STATE_BUFFERING && stallStart == 0L) stallStart = SystemClock.elapsedRealtime()
            if (state == Player.STATE_ENDED) onEnded()
            updatePlayButton()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            videoFrame.setAspectRatio(if (videoSize.height > 0) videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height else 0f)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            frameRate.playing(isPlaying, SystemClock.elapsedRealtime())
            updatePlayButton()
            if (osd.visibility == View.VISIBLE) scheduleHide()
            reportProgress()
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            if (isMusic) bindMusic(player.currentMediaItemIndex)
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "error ${error.errorCodeName} cause=${error.cause?.javaClass?.simpleName}")
            if (isMusic) {
                showBanner("Skipping a track that failed to play", 4000)
                if (player.hasNextMediaItem()) { player.seekToNextMediaItem(); player.prepare() } else finish()
                return
            }
            val cause = error.cause
            val http5xx = (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode?.let { it >= 500 } == true
            val decoder = error.errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED ||
                (error as? ExoPlaybackException)?.type == ExoPlaybackException.TYPE_RENDERER ||
                cause is MediaCodecRenderer.DecoderInitializationException
            val network = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            when {
                network && resolved?.isProgressiveRemux == true -> fallback("Reconnecting with a seekable stream", stall = false)
                network && !retriedSameLevel -> {
                    retriedSameLevel = true
                    showBanner("Network hiccup — reconnecting", 4000)
                    player.prepare()
                }
                decoder -> fallback("The TV couldn't decode this stream", stall = false)
                http5xx -> fallback("The server hit an error", stall = false)
                else -> fallback("Playback error (${error.errorCodeName})", stall = false)
            }
        }

        override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
            val (bitmapCues, textCues) = cueGroup.cues.partition { it.bitmap != null }
            captions.setCues(textCues)
            bitmapCaptions.setCues(bitmapCues)
            if ((com.mav.marquee.BuildConfig.DEBUG || stats.visibility == View.VISIBLE) && cueGroup.cues.isNotEmpty()) {
                val images = cueGroup.cues.count { it.bitmap != null }
                Log.i(TAG, "SUBTITLE cues=${cueGroup.cues.size} images=$images")
                Log.i(TAG, "CAPTION_GEOMETRY screen=${captions.width}x${captions.height} bottomPx=${captions.paddingBottom} textSizePx=${captions.height * 0.0533f * 0.85f} pictureTop=${videoFrame.top} pictureBottom=${videoFrame.bottom} translation=${captions.translationY} lines=${textCues.maxOfOrNull { it.text?.count { c -> c == '\n' }?.plus(1) ?: 0 } ?: 0}")
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            tracks.groups.filter { it.isSelected }.forEach { g ->
                Log.i(TAG, "TRACK type=${g.type} mime=${g.mediaTrackGroup.getFormat(0).sampleMimeType} id=${g.mediaTrackGroup.getFormat(0).id}")
            }
            if (stats.visibility == View.VISIBLE) updateStats()
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo, mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData) {
            Log.i(TAG, "LOAD type=${mediaLoadData.dataType} duration=${loadEventInfo.loadDurationMs} bytes=${loadEventInfo.bytesLoaded} position=${loadEventInfo.dataSpec.position}")
        }

        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            if (ttffMs < 0) {
                val now = SystemClock.elapsedRealtime()
                ttffMs = now - tPrepare
                backdrop.visibility = View.GONE
                spinner.visibility = View.GONE
                thumbJob?.cancel(); thumb.visibility = View.GONE
                bannerJob?.cancel(); banner.visibility = View.GONE
                Log.i(TAG, "MARQUEE_TTFF ${now - tIntent} prepare=$ttffMs item=${resolved?.itemId}")
                if (tSeek > 0) {
                    Log.i(TAG, "SEEK first_frame_ms=${now - tSeek}")
                    tSeek = 0
                }
                Log.i(TAG, "TTFF ms=$ttffMs sinceIntent=${now - tIntent} method=${resolved?.playMethod} mode=$mode")
                // Debug-only deterministic ladder exercise, through the production
                // recovery function. R8 removes this on release builds.
                if (com.mav.marquee.BuildConfig.DEBUG && intent.getBooleanExtra("verifyFallbackChain", false) &&
                    (mode != DeviceProfile.Mode.H264 || transcodeBitrate > 3_000_000)) {
                    verificationJob?.cancel()
                    verificationJob = lifecycleScope.launch {
                        delay(6_000)
                        Log.i(TAG, "VERIFY_FALLBACK mode=$mode bitrate=$transcodeBitrate position=${playbackPositionMs()} output=$audioOut")
                        fallback("Verification forced step", stall = false)
                    }
                }
            } else if (tSeek > 0) {
                spinner.visibility = View.GONE
                thumbJob?.cancel(); thumb.visibility = View.GONE
                Log.i(TAG, "SEEK first_frame_ms=${SystemClock.elapsedRealtime() - tSeek}")
                tSeek = 0
            }
        }

        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            videoDecoder = decoderName
            Log.i(TAG, "video decoder $decoderName init=${initializationDurationMs}ms")
        }

        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            audioDecoder = decoderName
            Log.i(TAG, "audio decoder $decoderName")
        }

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            val enc = audioTrackConfig.encoding
            audioOut = when (enc) {
                C.ENCODING_AC3 -> "AC3 passthrough"
                C.ENCODING_E_AC3 -> "E-AC3 passthrough"
                C.ENCODING_E_AC3_JOC -> "E-AC3-JOC passthrough"
                C.ENCODING_DTS -> "DTS passthrough"
                C.ENCODING_DTS_HD -> "DTS-HD passthrough"
                C.ENCODING_DOLBY_TRUEHD -> "TrueHD passthrough"
                else -> "PCM ${Integer.bitCount(audioTrackConfig.channelConfig)}ch ${audioTrackConfig.sampleRate}Hz" +
                    (if (audioTrackConfig.tunneling) " tunneled" else "")
            }
            Log.i(TAG, "AUDIO_TTFF sinceIntent=${SystemClock.elapsedRealtime() - tIntent} prepare=${SystemClock.elapsedRealtime() - tPrepare}")
            Log.i(TAG, "audio out $audioOut encoding=$enc session=${player.audioSessionId} route=${renderers.audioRoute.route} tunnel=${audioTrackConfig.tunneling} bufferBytes=${audioTrackConfig.bufferSize}")
        }

        override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
            audioUnderruns++
            Log.w(TAG, "AUDIO_UNDERRUN count=$audioUnderruns bytes=$bufferSize bufferMs=$bufferSizeMs lastFeedMs=$elapsedSinceLastFeedMs")
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            dropped += droppedFrames
        }

        override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
            bandwidth = bitrateEstimate
        }

        override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
            Log.i(TAG, "video format ${format.width}x${format.height} codecs=${format.codecs} color=${format.colorInfo} baseColor=${renderers.baseLayerColor}")
        }

        override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) {
            Log.i(TAG, "video size ${videoSize.width}x${videoSize.height}")
        }
    }

    private fun onEnded() {
        reportStopped()
        if (isMusic) { finish(); return }
        val next = nextEpisode
        if (next != null && prefs.autoPlayNext && !upNextDismissed) playNext(next) else finish()
    }

    // ========================================================================================
    // Ticker: progress, stall watchdog, segments, up next

    override fun onStart() {
        super.onStart()
        progressJob = lifecycleScope.launch {
            var tick = 0
            while (isActive) {
                delay(500)
                tick++
                if (!::player.isInitialized) continue
                updateTimes()
                checkStall()
                checkSegments()
                if (tick % 10 == 0) Log.i(TAG, "AUDIO_PROGRESS item=${resolved?.itemId} position=${playbackPositionMs()} output=$audioOut decoder=$audioDecoder route=${renderers.audioRoute.route}")
                if (tick % 20 == 0) reportProgress()
            }
        }
    }

    private fun checkStall() {
        if ((!isMusic && resolved == null) || recovering || terminalFailure) return
        val now = SystemClock.elapsedRealtime()
        if (player.isPlaying) {
            val pos = playbackPositionMs()
            if (pos != lastPositionMs) { lastPositionMs = pos; noProgressSince = now }
            else if (noProgressSince > 0 && now - noProgressSince > STALL_MS) {
                noProgressSince = now
                if (isMusic) recoverMusic() else fallback("Playback stopped advancing", stall = true)
                return
            }
        } else noProgressSince = 0
        if (player.playbackState != Player.STATE_BUFFERING || !player.playWhenReady || stallStart == 0L) return
        val limit = if (ttffMs < 0) STARTUP_STALL_MS else STALL_MS
        val waited = SystemClock.elapsedRealtime() - stallStart
        if (waited > limit) {
            stallStart = 0
            if (isMusic) recoverMusic() else fallback(if (ttffMs < 0) "Stream is taking too long to start" else "Stream stalled for ${waited / 1000} s", stall = true)
        } else if (waited > 3000) {
            showBanner("Buffering… ${(waited / 1000)} s (bandwidth ${bandwidth / 1_000_000} Mbps)", 1500)
        }
    }

    private fun recoverMusic() {
        if (!retriedSameLevel) {
            retriedSameLevel = true
            showBanner("Reconnecting music", 3000)
            player.stop(); player.prepare(); player.play()
            stallStart = SystemClock.elapsedRealtime()
        } else if (player.hasNextMediaItem()) {
            showBanner("Skipping a track that stalled", 3000)
            player.seekToNextMediaItem(); player.prepare(); player.play()
        } else fail("Music could not continue on this connection")
    }

    private fun checkSegments() {
        if (isMusic || terminalFailure || recovering || player.playbackState != Player.STATE_READY) return
        val decision = SegmentPolicy.evaluate(playbackPositionMs(), playbackDurationMs(), segments,
            prefs.introMode, prefs.creditsMode, prefs.autoPlayNext, nextEpisode != null,
            upNextDismissed, skippedSegments, player.isPlaying)
        if (decision.next == NextEpisodeAction.None) hideUpNext()
        val segment = decision.skip
        if (segment == null) {
            skipBtn.visibility = View.GONE
            skipBtn.tag = null
        } else if (skipBtn.tag != segment || skipBtn.visibility != View.VISIBLE) {
            skipBtn.text = if (segment.type == "Outro") "Skip Credits" else "Skip ${segment.type}"
            skipBtn.tag = segment
            skipBtn.visibility = View.VISIBLE
            Log.i(TAG, "SEGMENT_BUTTON item=${resolved?.itemId} type=${segment.type} position=${playbackPositionMs()} start=${segment.startMs} end=${segment.endMs}")
            if (osd.visibility != View.VISIBLE && sheet.visibility != View.VISIBLE) skipBtn.requestFocus()
        }
        decision.autoSeek?.let { segmentToSkip ->
            skippedSegments += segmentToSkip
            Log.i(TAG, "SEGMENT_AUTO item=${resolved?.itemId} type=${segmentToSkip.type} position=${playbackPositionMs()} end=${segmentToSkip.endMs}")
            seekTo(segmentToSkip.endMs)
            return
        }
        when (decision.next) {
            NextEpisodeAction.Immediate -> nextEpisode?.let { playNext(it) }
            NextEpisodeAction.Countdown -> showUpNext()
            NextEpisodeAction.None -> Unit
        }
    }

    // ========================================================================================
    // Up next

    private fun showUpNext() {
        val next = nextEpisode ?: return
        if (upNextDismissed || upNext.visibility == View.VISIBLE || !prefs.autoPlayNext) return
        upNext.visibility = View.VISIBLE
        skipBtn.visibility = View.GONE
        Log.i(TAG, "UP_NEXT item=${resolved?.itemId} next=${next.str("Id")} position=${playbackPositionMs()} countdown=${prefs.upNextSeconds}")
        (upNext.getChildAt(1) as? ViewGroup)?.getChildAt(0)?.requestFocus()
        val label = "S${next.int("ParentIndexNumber") ?: "?"} · E${next.int("IndexNumber") ?: "?"}  ${next.str("Name") ?: ""}"
        upNextJob?.cancel()
        upNextJob = lifecycleScope.launch {
            val countdown = NextEpisodeCountdown(prefs.upNextSeconds)
            var previous = SystemClock.elapsedRealtime()
            var displayedSeconds = -1
            while (!countdown.finished) {
                if (displayedSeconds != countdown.secondsLeft) {
                    displayedSeconds = countdown.secondsLeft
                    upNextText.text = "Up next in $displayedSeconds s\n$label"
                }
                val wasPlaying = player.isPlaying
                delay(100)
                val now = SystemClock.elapsedRealtime()
                countdown.advance(now - previous, wasPlaying && player.isPlaying)
                previous = now
            }
            playNext(next)
        }
    }

    private fun hideUpNext() {
        upNextJob?.cancel()
        if (::upNext.isInitialized) upNext.visibility = View.GONE
    }

    private fun playNext(next: JsonObject) {
        hideUpNext()
        reportStopped()
        resolved?.let { r -> if (r.isTranscode) lifecycleScope.launch { JfApi.stopEncoding(r.playSessionId) } }
        resolved = null
        val id = next.str("Id") ?: return finish()
        PlaybackPrewarm.playbackStarting()
        tIntent = SystemClock.elapsedRealtime()
        load(id, 0)
    }

    // ========================================================================================
    // Reporting

    private val reports by lazy { PlaybackReports(lifecycleScope, JfApi::report) }

    private fun reportBody(r: Resolved) = buildJsonObject {
        put("ItemId", r.itemId)
        put("MediaSourceId", r.mediaSourceId)
        put("PlaySessionId", r.playSessionId)
        put("PlayMethod", r.playMethod)
        put("PositionTicks", playbackPositionMs().coerceAtLeast(0) * 10_000)
        put("IsPaused", !player.playWhenReady)
        put("CanSeek", true)
        r.audioIndex?.let { put("AudioStreamIndex", it) }
        put("SubtitleStreamIndex", r.subtitleIndex ?: -1)
    }

    private fun reportStart() {
        val r = resolved ?: return
        reportedStart = true
        val body = reportBody(r)
        reports.submit("/Sessions/Playing", body)
    }

    private fun reportProgress() {
        if (isMusic) {
            val id = musicReportedId ?: return
            musicLastPositionMs = playbackPositionMs()
            val body = musicReportBody(id, musicLastPositionMs)
            reports.submit("/Sessions/Playing/Progress", body)
            return
        }
        val r = resolved ?: return
        if (!reportedStart) return
        val body = reportBody(r)
        reports.submit("/Sessions/Playing/Progress", body)
    }

    private fun reportStopped() {
        if (isMusic) {
            val id = musicReportedId ?: return
            musicReportedId = null
            val body = musicReportBody(id, playbackPositionMs())
            reports.submit("/Sessions/Playing/Stopped", body)
            return
        }
        val r = resolved ?: return
        PlaybackPrewarm.releaseAdopted(r.playSessionId)
        if (!reportedStart) {
            if (r.isTranscode) lifecycleScope.launch(NonCancellable) { JfApi.stopEncoding(r.playSessionId) }
            return
        }
        reportedStart = false
        val body = reportBody(r)
        val stopReport = reports.submit("/Sessions/Playing/Stopped", body)
        if (r.isTranscode) lifecycleScope.launch(NonCancellable) {
            stopReport.join()
            JfApi.stopEncoding(r.playSessionId)
        }
    }

    private fun stopPlayback() {
        stopped = true
        startupJob?.cancel()
        loadJob?.cancel()
        progressJob?.cancel()
        statsJob?.cancel()
        bufferingJob?.cancel()
        verificationJob?.cancel()
        thumbJob?.cancel()
        upNextJob?.cancel()
        if (::root.isInitialized) {
            root.removeCallbacks(hideOsdTask)
            root.removeCallbacks(commitScrubTask)
        }
        PlaybackPrewarm.playbackEnded()
        if (::player.isInitialized && !playerReleased) {
            reportStopped()
            // Stop while the Surface and playback looper still exist, then synchronously release.
            playerReleased = true
            try { player.stop() } finally { player.release() }
            Log.i(TAG, "CODEC_RELEASED onStop=true")
        }
        playbackThread?.quitSafely()
        playbackThread = null
        ActivePlayerOwner.clear(this)
    }

    override fun onStop() {
        stopPlayback()
        super.onStop()
        // A stopped TV player finishes; a new launch gets a new, sole player.
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    // ========================================================================================
    // Keys

    // RestrictedApi: lint false positive on overriding the public Activity.dispatchKeyEvent via androidx.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val cpu = com.mav.marquee.ui.library.kit.ScreenKeyCpuTrace.start()
        val start = com.mav.marquee.ui.components.NavWorkTrace.keyStart()
        try {
            if (handleKey(event)) return true
            return super.dispatchKeyEvent(event)
        } finally {
            com.mav.marquee.ui.components.NavWorkTrace.keyEnd(start)
            com.mav.marquee.ui.library.kit.ScreenKeyCpuTrace.end(cpu)
        }
    }

    // TV remotes deliver Back as a key event; predictive back gestures do not apply here.
    @android.annotation.SuppressLint("GestureBackNavigation")
    private fun handleKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN
        if (!::sheet.isInitialized) return code != KeyEvent.KEYCODE_BACK
        // Dedicated transport keys stay live while a track sheet or Up Next has focus.
        if (::player.isInitialized && down && (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE)) {
            if (e.repeatCount == 0) {
                val feedback: (Boolean) -> Unit = { playing ->
                    osd.showPlaying(playing)
                    if (sheet.visibility == View.VISIBLE || upNext.visibility == View.VISIBLE)
                        showBanner(if (playing) "Playing" else "Paused", 1500)
                }
                when (code) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> PlayerTransport.setPlaying(player, true, feedback)
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> PlayerTransport.setPlaying(player, false, feedback)
                    else -> PlayerTransport.toggle(player, feedback)
                }
            }
            if (sheet.visibility != View.VISIBLE && upNext.visibility != View.VISIBLE) showOsd()
            return true
        }
        if (sheet.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK && down) { closeSheet(); return true }
            if (down && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
                val index = sheetList.indexOfChild(root.findFocus())
                val next = index + if (code == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                val target = sheetList.getChildAt(next)
                if (target?.isFocusable == true) {
                    target.requestFocus()
                    if (target.height > 0) {
                        val top = target.top - sheet.scrollY
                        val bottom = target.bottom - sheet.scrollY - sheet.height
                        val delta = when { top < 0 -> top; bottom > 0 -> bottom; else -> 0 }
                        if (delta != 0) sheet.scrollTo(0, sheet.scrollY + delta)
                    }
                }
                return true
            }
            return false
        }
        if (upNext.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK && down) { upNextDismissed = true; hideUpNext(); return true }
            return false
        }
        if (!::player.isInitialized) return code != KeyEvent.KEYCODE_BACK
        if (!down) {
            if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && playBtn.isFocused) {
                playBtn.setKeyPressed(false)
                return true
            }
            return false
        }
        val osdOn = osd.visibility == View.VISIBLE
        when (code) {
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { scrub(+1, e.repeatCount); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { scrub(-1, e.repeatCount); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (isMusic) player.seekToNextMediaItem() else nextEpisode?.let { playNext(it) }; return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (isMusic) player.seekToPreviousMediaItem() else seekTo(0); return true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { finish(); return true }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_PROG_BLUE -> { toggleStats(); return true }
            KeyEvent.KEYCODE_CAPTIONS -> { openSubtitleSheet(); return true }
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> { openAudioSheet(); return true }
            KeyEvent.KEYCODE_BACK -> {
                if (scrubTarget >= 0) { cancelScrub(); hideOsd(); return true }
                if (osdOn) { hideOsd(); return true }
                return false
            }
        }
        if (!osdOn) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { scrub(-1, e.repeatCount); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { scrub(+1, e.repeatCount); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (e.repeatCount != 0) return true
                    if (skipBtn.visibility == View.VISIBLE) { skipBtn.performClick(); return true }
                    togglePlay(); showOsd(); playBtn.setKeyPressed(true); return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> { showOsd(); return true }
            }
            return false
        }
        scheduleHide()
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && playBtn.isFocused) {
            playBtn.setKeyPressed(true)
            if (e.repeatCount == 0) togglePlay()
            return true
        }
        if (seek.isFocused) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { scrub(-1, e.repeatCount); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { scrub(+1, e.repeatCount); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (e.repeatCount == 0) {
                        if (scrubTarget >= 0) commitScrub() else togglePlay()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> return true
            }
        }
        val direction = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            else -> return false
        }
        // Move exactly once on key-down, including native held repeats. Do not wait for
        // the framework's later unhandled-key traversal or let it move a second time.
        val focused = root.findFocus()
        val targetId = when (direction) {
            View.FOCUS_LEFT -> focused?.nextFocusLeftId ?: View.NO_ID
            View.FOCUS_RIGHT -> focused?.nextFocusRightId ?: View.NO_ID
            else -> View.NO_ID
        }
        if (targetId != View.NO_ID) root.findViewById<View>(targetId)?.requestFocus()
        else if (direction == View.FOCUS_UP && buttons.hasFocus()) seek.requestFocus()
        else if (direction == View.FOCUS_DOWN && seek.isFocused) playBtn.requestFocus()
        else focused?.focusSearch(direction)?.requestFocus()
        return true
    }

    // --- scrubbing: D-pad left/right ±10 s, accelerating while held; one seek when the key settles ---

    private var scrubTarget = -1L

    /** Live progressive MKV has no final duration/Cues; Jellyfin already knows the source runtime. */
    private fun playbackPositionMs(): Long = player.currentPosition + (resolved?.progressiveStartMs ?: 0)

    private fun playbackDurationMs(): Long = if (resolved?.isProgressiveRemux == true)
        resolved?.durationMs ?: player.duration else player.duration

    private fun scrub(dir: Int, repeat: Int) {
        val dur = playbackDurationMs().takeIf { it > 0 } ?: return
        val step = when {
            repeat < 4 -> 10_000L
            repeat < 12 -> 30_000L
            repeat < 24 -> 60_000L
            else -> 120_000L
        }
        if (scrubTarget < 0) scrubTarget = playbackPositionMs()
        scrubTarget = (scrubTarget + dir * step).coerceIn(0, dur - 1000)
        seek.scrubMs = scrubTarget
        if (osd.visibility != View.VISIBLE) showOsd(focusSeek = true)
        seek.invalidate()
        updateTimes()
        showThumb(scrubTarget)
        root.removeCallbacks(commitScrubTask)
        root.postDelayed(commitScrubTask, 650)
    }

    private fun commitScrub() {
        root.removeCallbacks(commitScrubTask)
        val t = scrubTarget
        scrubTarget = -1
        seek.scrubMs = -1
        thumbJob?.cancel()
        if (t >= 0) seekTo(t)
        scheduleHide()
    }

    private fun cancelScrub() {
        root.removeCallbacks(commitScrubTask)
        scrubTarget = -1
        seek.scrubMs = -1
        thumbJob?.cancel(); thumb.visibility = View.GONE
        seek.invalidate()
    }

    private fun seekTo(ms: Long) {
        tSeek = SystemClock.elapsedRealtime()
        stallStart = tSeek
        seekReadyRecorded = false
        lastSeekMs = ms
        val r = resolved
        if (r?.isProgressiveRemux == true) {
            // Jellyfin returns Accept-Ranges:none. Never send a byte seek to a growing MKV,
            // or treat a StartTimeTicks restart as if it still had the full source timeline.
            preferProgressiveRemux = true
            val playing = player.playWhenReady
            val seekStart = tSeek
            reportStopped()
            player.stop()
            spinner.visibility = View.VISIBLE
            loadJob?.cancel()
            loadJob = lifecycleScope.launch {
                try {
                    // startStream already stops this exact old session asynchronously.
                    // Overlap its HTTP cleanup with negotiation instead of adding it to TTFF.
                    startStream(ms, r.audioIndex, r.subtitleIndex, playing)
                    tSeek = seekStart
                } catch (e: Exception) {
                    if (e is CancellationException && !isActive) throw e
                    fallback("Couldn't seek this stream", stall = false)
                }
            }
        } else {
            renderers.beginAudioStartup(r?.audioIndex != null)
            renderers.beginExactStart(ms, 0, tSeek, false)
            player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
            spinner.visibility = View.VISIBLE
            showThumb(ms)
            player.seekTo(ms)
            reportProgress()
        }
    }

    private fun showThumb(ms: Long) {
        val tp = trickplay ?: return
        thumbJob?.cancel()
        thumbJob = lifecycleScope.launch {
            delay(60)
            val bmp = tp.thumbnail(ms) ?: return@launch
            thumb.setImageBitmap(bmp)
            val w = thumb.layoutParams.width
            val x = (seek.left + osd.left + seek.xFor(ms) - w / 2f).coerceIn(dp(24).toFloat(), (root.width - w - dp(24)).toFloat())
            thumb.translationX = x
            thumb.visibility = View.VISIBLE
        }
    }

    private fun togglePlay() {
        PlayerTransport.toggle(player, osd::showPlaying)
    }

    // ========================================================================================
    // Track sheets

    private fun openAudioSheet() {
        if (isMusic) return
        val r = resolved ?: return
        val streams = r.audioStreams()
        openSheet("Audio", streams.map { s ->
            val idx = s.int("Index")
            val language = s.str("Language")?.takeUnless { it.isBlank() || it == "und" }
            val label = s.str("DisplayTitle") ?: "${language ?: "Unknown"} · ${s.str("Codec") ?: "audio"} · Track $idx"
            Triple(if (language == null) "$label · Unknown · Track $idx" else label, idx == r.audioIndex) { selectAudio(s) }
        })
    }

    private fun openSubtitleSheet() {
        if (isMusic) return
        val r = resolved ?: return
        val opts = mutableListOf(Triple("Off", r.subtitleIndex == null || r.subtitleIndex < 0) { selectSubtitle(null) })
        r.subtitleStreams().sortedBy { if (TrackConversionPolicy.isExternalText(it)) 0 else 1 }.forEach { s ->
            val idx = s.int("Index")
            opts += Triple(s.str("DisplayTitle") ?: "Track $idx", idx == r.subtitleIndex) { selectSubtitle(s) }
        }
        openSheet("Subtitles", opts)
    }

    private fun speedLabel(speed: Float, sourceFps: Float): String {
        return PlaybackCadence.label(speed, sourceFps)
    }

    private fun sourceFrameRate(): Float {
        val source = resolved?.videoStream()
        return source?.str("RealFrameRate")?.toFloatOrNull()?.takeIf { it > 0 && it.isFinite() }
            ?: source?.str("AverageFrameRate")?.toFloatOrNull()?.takeIf { it > 0 && it.isFinite() }
            ?: player.videoFormat?.frameRate ?: -1f
    }

    private fun openSettingsSheet() {
        openSheet("Playback", listOf(
            Triple("Quality", false) { openSheet("Quality", listOf(0, 20_000_000, 12_000_000, 8_000_000).map { br ->
                Triple(if (br == 0) "Auto" else "${br / 1_000_000} Mbps", (qualityBitrate ?: 0) == br) {
                    qualityBitrate = br.takeIf { it > 0 }; mode = DeviceProfile.Mode.Direct
                    restartWith(resolved?.audioIndex, resolved?.subtitleIndex)
                }
            }) },
            Triple("Speed", false) { openSheet("Speed · 60 Hz", PlaybackCadence.speeds(sourceFrameRate()).map { speed ->
                Triple(speedLabel(speed, sourceFrameRate()), player.playbackParameters.speed == speed) {
                    trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingEnabled(false))
                    player.setPlaybackSpeed(speed)
                    PlaybackSessionSpeed.speed = speed
                    Log.i(TAG, "CADENCE sourceFps=${sourceFrameRate()} speed=$speed ${speedLabel(speed, sourceFrameRate())} vsync=Media3")
                }
            }) },
            Triple("Stats", stats.visibility == View.VISIBLE) { toggleStats() },
            Triple("HDMI ARC passthrough", prefs.passthrough) {
                prefs.setPassthrough(!prefs.passthrough)
                restartWith(resolved?.audioIndex, resolved?.subtitleIndex)
            },
        ))
    }

    private fun openEpisodesSheet() {
        val series = item?.str("SeriesId") ?: return showBanner("This title has no episodes", 2000)
        openSheet("Episodes", emptyList())
        val generation = sheetGeneration
        sheetList.addView(ProgressBar(this).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(28), dp(28)))
        sheetList.addView(text(14f, Tok.TextSecondary).apply { text = "Loading episodes…"; setPadding(0, dp(12), 0, 0) })
        lifecycleScope.launch {
            try {
                val episodes = JfApi.get("/Shows/$series/Episodes?userId=${Env.userId}&fields=Overview").arr("Items").objs()
                if (item?.str("SeriesId") != series || isFinishing || generation != sheetGeneration || sheet.visibility != View.VISIBLE) return@launch
                openSheet("Episodes", episodes.map { episode ->
                    Triple("S${episode.int("ParentIndexNumber")} · E${episode.int("IndexNumber")}  ${episode.str("Name")}",
                        episode.str("Id") == item?.str("Id")) { playNext(episode) }
                })
            } catch (_: Exception) {
                if (generation == sheetGeneration && sheet.visibility == View.VISIBLE) {
                    closeSheet(); showBanner("Couldn't load episodes", 2500)
                }
            }
        }
    }

    private fun openSheet(heading: String, options: List<Triple<String, Boolean, () -> Unit>>) {
        sheetGeneration++
        hideOsd()
        sheetList.removeAllViews()
        sheetList.addView(text(20f, Tok.TextPrimary, true).apply { text = heading; setPadding(0, 0, 0, dp(12)) })
        var focus: View? = null
        options.forEach { (label, selected, action) ->
            val b = OsdButton(this, (if (selected) "✓  " else "    ") + label)
            b.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            b.setOnClickListener { closeSheet(); action() }
            sheetList.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply { bottomMargin = dp(6) })
            if (selected || focus == null) if (selected || sheetList.childCount == 2) focus = b
        }
        sheet.visibility = View.VISIBLE
        (focus ?: sheetList.getChildAt(1))?.requestFocus()
    }

    private fun closeSheet() {
        sheetGeneration++
        sheet.visibility = View.GONE
        playerView.requestFocus()
    }

    private fun applyInitialTracks() {
        val r = resolved ?: return
        if (!r.isTranscode) {
            val audioOrdinal = r.audioStreams().indexOfFirst { it.int("Index") == r.audioIndex }
            player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.getOrNull(audioOrdinal)?.let { g ->
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
            }
        }
        val idx = r.subtitleIndex ?: -1
        if (idx < 0) return
        val stream = r.subtitleStreams().firstOrNull { it.int("Index") == idx } ?: return
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val g = groups.firstOrNull { it.mediaTrackGroup.getFormat(0).id == "jf:$idx" }
            ?: if (r.isProgressiveRemux && TrackConversionPolicy.canEmbedBitmap(stream)) groups.firstOrNull {
                it.mediaTrackGroup.getFormat(0).sampleMimeType in setOf("application/pgs", "application/vobsub")
            } else if (!r.isTranscode) groups.filter { it.mediaTrackGroup.getFormat(0).id?.startsWith("jf:") != true }
                .getOrNull(r.subtitleStreams().filter { it.str("DeliveryMethod") == "Embed" }.indexOf(stream)) else null
        if (g != null) player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
    }

    private fun selectAudio(s: JsonObject) {
        val r = resolved ?: return
        val idx = s.int("Index") ?: return
        val sub = r.subtitleStreams().firstOrNull { it.int("Index") == r.subtitleIndex }
        if (TrackConversionPolicy.needsVideoConversion(s, sub)) {
            confirmVideoConversion(idx, sub?.int("Index"))
            return
        }
        if (!r.isTranscode && AudioSelection.isDirectCompatible(s)) {
            val ordinal = r.audioStreams().indexOf(s)
            val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            val g = groups.getOrNull(ordinal)
            if (g != null) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
                replaceIndices(audio = idx, sub = r.subtitleIndex)
                return
            }
        }
        restartWith(idx, r.subtitleIndex)
    }

    private fun selectSubtitle(s: JsonObject?) {
        val r = resolved ?: return
        val idx = s?.int("Index")
        if (s == null) {
            if (r.isTranscode && android.net.Uri.parse(r.mediaItem.localConfiguration?.uri.toString())
                    .getQueryParameter("SubtitleMethod") == "Encode" && (r.subtitleIndex ?: -1) >= 0) {
                restartWith(r.audioIndex, -1)
                return
            }
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            replaceIndices(r.audioIndex, -1)
            return
        }
        val method = s.str("DeliveryMethod")
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val g = if (method == "External") {
            groups.firstOrNull { it.mediaTrackGroup.length > 0 && it.mediaTrackGroup.getFormat(0).id?.contains("jf:$idx") == true }
        } else if (r.isProgressiveRemux && idx == r.subtitleIndex && TrackConversionPolicy.canEmbedBitmap(s)) {
            groups.firstOrNull { it.mediaTrackGroup.getFormat(0).sampleMimeType in setOf("application/pgs", "application/vobsub") }
        } else if (method == "Embed" && !r.isTranscode) {
            val embedded = r.subtitleStreams().filter { it.str("DeliveryMethod") == "Embed" }
            val ord = embedded.indexOf(s)
            groups.filter { it.mediaTrackGroup.getFormat(0).id?.contains("jf:") != true }.getOrNull(ord)
        } else null
        if (g != null) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
            replaceIndices(r.audioIndex, idx)
        } else {
            // Needs the server (burn-in of an image subtitle in a transcode, or not exposed yet).
            val audio = r.audioStreams().firstOrNull { it.int("Index") == r.audioIndex }
            if (!TrackConversionPolicy.canEmbedBitmap(s) && (TrackConversionPolicy.needsVideoConversion(audio, s) || s.str("DeliveryMethod") == "Encode"))
                confirmVideoConversion(r.audioIndex, idx)
            else restartWith(r.audioIndex, idx)
        }
    }

    private fun confirmVideoConversion(audio: Int?, sub: Int?, initial: Boolean = false, startMs: Long = 0) {
        val r = resolved ?: return
        val hdr = StreamResolver.hasHdrBaseLayer(r.videoStream())
        val heading = if (hdr) "These tracks convert video to SDR, up to 1080p" else "These tracks convert video, up to 1080p"
        fun resumeWith(subtitle: Int?) {
            if (initial) {
                loadJob = lifecycleScope.launch {
                    try { startStream(startMs, audio, subtitle) }
                    catch (e: Exception) {
                        if (e is CancellationException && !isActive) throw e
                        fallback("Track switch failed", stall = false)
                    }
                }
            } else restartWith(audio, subtitle)
        }
        val choices = mutableListOf(Triple(if (initial) "Cancel playback" else "Keep current tracks", true) {
            if (initial) finish()
        })
        r.subtitleStreams().filter(TrackConversionPolicy::isExternalText).forEach { text ->
            choices += Triple("Use ${text.str("DisplayTitle") ?: "text subtitles"} instead", false) {
                resumeWith(text.int("Index"))
            }
        }
        choices += Triple("Continue with video conversion", false) {
            confirmedVideoConversion = Triple(r.itemId, audio, sub)
            resumeWith(sub)
        }
        openSheet(heading, choices)
    }

    private fun replaceIndices(audio: Int?, sub: Int?) {
        val r = resolved ?: return
        resolved = Resolved(r.mediaItem, r.itemId, r.playSessionId, r.mediaSourceId, r.mediaSource, r.mode, r.playMethod,
            r.videoCopy, r.transcodeReasons, audio, sub, r.maxBitrate, r.progressiveStartMs)
        reportProgress()
    }

    private fun restartWith(audio: Int?, sub: Int?) {
        val pos = playbackPositionMs()
        if (resolved?.isProgressiveRemux == true) preferProgressiveRemux = false
        reportStopped()
        showBanner("Switching track…", 2500)
        spinner.visibility = View.VISIBLE
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try { startStream(pos, audio, sub ?: -1) } catch (e: Exception) { fallback("Track switch failed", stall = false) }
        }
    }

    // ========================================================================================
    // OSD

    private fun updateCaptionInsets() {
        // A GONE panel has no first-show bounds yet. Its layout callback runs before that frame
        // draws; avoid translating cues off the screen using an unmeasured top of zero.
        val osdShown = osd.visibility == View.VISIBLE && osd.height > 0
        // Text captions span the full screen; bitmap captions span the picture. Each lifts only by its own overlap.
        val translation = captionTranslation(captions.bottom, osd.top, osdShown, dp(12))
        val bitmapTranslation = captionTranslation(videoFrame.bottom, osd.top, osdShown, dp(12))
        val bottom = -translation.toInt()
        if (captionShift != bottom) Log.i(TAG, "CAPTION_INSET osd=${osd.visibility == View.VISIBLE} bottom=$bottom")
        captionShift = bottom
        captions.translationY = translation
        bitmapCaptions.translationY = bitmapTranslation
    }

    private fun showOsd(focusSeek: Boolean = false) {
        if (osd.visibility != View.VISIBLE) {
            osd.visibility = View.VISIBLE
            updateTimes()
        }
        if (!seek.isFocused && buttons.findFocus() == null) { if (focusSeek) seek.requestFocus() else playBtn.requestFocus() }
        // Use known geometry on this key frame; the layout listener updates first-show geometry.
        updateCaptionInsets()
        scheduleHide()
    }

    private fun hideOsd() {
        root.removeCallbacks(hideOsdTask)
        osd.visibility = View.GONE
        updateCaptionInsets()
        if (tSeek == 0L) { thumbJob?.cancel(); thumb.visibility = View.GONE }
        playerView.requestFocus()
    }

    private fun scheduleHide() {
        root.removeCallbacks(hideOsdTask)
        root.postDelayed(hideOsdTask, OSD_HIDE_MS)
    }

    private fun updatePlayButton() {
        val playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        playBtn.label = if (playing) "Pause" else "Play"
        playBtn.iconRes = if (playing) R.drawable.player_pause else R.drawable.player_play_arrow
    }

    private fun updateTimes() {
        if (osd.visibility != View.VISIBLE) return
        val dur = playbackDurationMs().coerceAtLeast(0)
        val pos = if (seek.scrubMs >= 0) seek.scrubMs else playbackPositionMs()
        seek.durationMs = dur
        seek.positionMs = playbackPositionMs()
        seek.bufferedMs = player.bufferedPosition + (resolved?.progressiveStartMs ?: 0)
        seek.invalidate()
        // Sony's SurfaceView readback can omit unchanged overlay regions after partial damage.
        // Refresh the UI buffer at 2 Hz only while controls are visible; SurfaceView stays external.
        root.invalidate()
        timeLeft.text = fmtTime(pos)
        timeRight.text = if (dur > 0) "-" + fmtTime(dur - pos) else ""
    }

    private fun bindTitles(it: JsonObject) { osd.bindItem(it) }

    private var bannerJob: Job? = null

    private fun showBanner(msg: String, ms: Long) {
        banner.text = msg
        banner.visibility = View.VISIBLE
        bannerJob?.cancel()
        bannerJob = lifecycleScope.launch { delay(ms); banner.visibility = View.GONE }
    }

    private fun fail(msg: String) {
        Log.e(TAG, "FAIL $msg")
        terminalFailure = true
        reportStopped()
        spinner.visibility = View.GONE
        if (::player.isInitialized) player.stop()
        sheetList.removeAllViews()
        sheetList.addView(text(20f, Tok.TextPrimary, true).apply { text = "Can't play"; setPadding(0, 0, 0, dp(8)) })
        sheetList.addView(text(14f, Tok.TextSecondary).apply { text = msg; setPadding(0, 0, 0, dp(16)) })
        val back = OsdButton(this, "Back", primary = true).apply { setOnClickListener { finish() } }
        sheetList.addView(back, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        sheet.visibility = View.VISIBLE
        back.requestFocus()
    }

    // --- diagnostics -----------------------------------------------------------------------

    private fun toggleStats() {
        if (stats.visibility == View.VISIBLE) {
            stats.visibility = View.GONE; statsJob?.cancel(); return
        }
        stats.visibility = View.VISIBLE
        statsJob = lifecycleScope.launch { while (isActive) { updateStats(); delay(1000) } }
    }

    private fun hdrName(f: Format?): String {
        val ci = f?.colorInfo ?: renderers.baseLayerColor ?: return "SDR"
        return when (ci.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> "HDR10 (PQ)"
            C.COLOR_TRANSFER_HLG -> "HLG"
            else -> "SDR"
        }
    }

    private fun updateStats() {
        val r = resolved
        val v = player.videoFormat
        val a = player.audioFormat
        val vs = r?.videoStream()
        val counters = player.videoDecoderCounters
        val sb = StringBuilder()
        sb.append("Play method   ").append(r?.label ?: if (isMusic) "Music (universal)" else "-")
        if (r != null && r.mode != DeviceProfile.Mode.Direct) sb.append("  [fallback ${r.mode}]")
        sb.append('\n')
        if (r?.transcodeReasons?.isNotEmpty() == true) sb.append("Reasons       ").append(r.transcodeReasons.joinToString(", ")).append('\n')
        if (vs != null) {
            sb.append("Source        ").append(vs.str("Codec")).append(' ').append(vs.str("Profile") ?: "")
                .append(" L").append(vs.int("Level") ?: 0).append(' ').append(vs.int("Width")).append('x').append(vs.int("Height"))
                .append(' ').append(vs.str("VideoRangeType") ?: "")
            vs.int("DvProfile")?.let { sb.append(" DV P").append(it).append('.').append(vs.int("DvBlSignalCompatibilityId") ?: 0) }
            sb.append('\n')
        }
        if (v != null) {
            sb.append("Video         ").append(v.sampleMimeType).append(' ').append(v.codecs ?: "").append(' ')
                .append(v.width).append('x').append(v.height).append(" @").append("%.3f".format(v.frameRate)).append('\n')
            sb.append("Decoder       ").append(videoDecoder).append("  out ").append(hdrName(v)).append('\n')
        }
        if (a != null) {
            sb.append("Audio         ").append(a.sampleMimeType).append(' ').append(a.channelCount).append("ch ")
                .append(a.sampleRate).append("Hz").append('\n')
            sb.append("Audio path    ").append(audioOut).append(if (audioOut.startsWith("PCM")) "  dec $audioDecoder" else "").append('\n')
        }
        val srcBr = r?.mediaSource?.int("Bitrate")
        sb.append("Bitrate       src ").append(srcBr?.let { "%.1f".format(it / 1e6) } ?: "-")
            .append(" Mbps  est ").append("%.1f".format(bandwidth / 1e6)).append(" Mbps\n")
        sb.append("Buffer        ").append("%.1f".format(player.totalBufferedDuration / 1000.0)).append(" s  ")
            .append(allocator.totalBytesAllocated / (1024 * 1024)).append(" MB\n")
        sb.append("Dropped       ").append(counters?.droppedBufferCount ?: dropped).append("  rendered ")
            .append(counters?.renderedOutputBufferCount ?: 0).append("  ")
            .append("%.1f".format(frameRate.droppedPerMinute(counters?.droppedBufferCount ?: dropped, SystemClock.elapsedRealtime())))
            .append("/min\n")
        sb.append("Audio underruns ").append(audioUnderruns).append("  PCM buffer 500 ms\n")
        sb.append("Cadence       ").append(speedLabel(player.playbackParameters.speed, sourceFrameRate()))
            .append(" at 60 Hz; fractional rates drift\n")
        sb.append("Position      ").append(playbackPositionMs()).append(" ms state ").append(player.playbackState).append('\n')
        sb.append("TTFF          ").append(if (ttffMs >= 0) "$ttffMs ms" else "-").append('\n')
        val rt = Runtime.getRuntime()
        sb.append("Heap          ").append((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).append(" MB")
        stats.text = sb
        if (SystemClock.elapsedRealtime() % 10_000 < 1000) Log.i(TAG, "STATS " + sb.toString().replace('\n', '|'))
    }

    // ========================================================================================
    // View tree (programmatic: no XML so the package stays self-contained)

    private fun buildSurface() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); clipChildren = false; isFocusable = true; isFocusableInTouchMode = true }
        backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.BLACK)
            intent.getStringExtra("itemId")?.let { setImageBitmap(PlaybackPrewarm.backdrop(it)) }
        }
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))

        spinner = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(spinner, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))

        setContentView(root)
        var recorded = false
        val draw = object : android.view.ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (recorded) return
                recorded = true
                Log.i(TAG, "MARQUEE_PLAYER_SURFACE ${SystemClock.elapsedRealtime() - tIntent}")
                root.post {
                    if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnDrawListener(this)
                    surfaceReady.complete(Unit)
                }
            }
        }
        root.viewTreeObserver.addOnDrawListener(draw)
        root.requestFocus()
    }

    private fun buildOverlays() {
        // Avoid inflating Media3's full controller hierarchy when controls are our own native Views.
        videoFrame = AspectRatioFrameLayout(this).apply { resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT }
        playerView = SurfaceView(this).apply { isFocusable = true; isFocusableInTouchMode = true }
        videoFrame.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        captions = SubtitleView(this).apply {
            setApplyEmbeddedStyles(true)
            setFractionalTextSize(0.0533f * 0.85f)
            setStyle(CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, android.graphics.Typeface.DEFAULT_BOLD))
        }
        captions.setPadding(dp(48), 0, dp(48), 0)
        // Text cues use the whole screen, so on letterboxed films they sit in the black bar below the picture
        // instead of over it. Bitmap cues (PGS/DVB) are positioned relative to the picture and stay in the video frame.
        captions.setBottomPaddingFraction(0f)
        captions.setPadding(dp(48), 0, dp(48), dp(24))
        bitmapCaptions = SubtitleView(this)
        videoFrame.addView(bitmapCaptions, FrameLayout.LayoutParams(-1, -1))
        videoFrame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (::osd.isInitialized) updateCaptionInsets() }
        root.addView(videoFrame, 0, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        root.addView(captions, 1, FrameLayout.LayoutParams(-1, -1))
        captions.setStyle(CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, Tok.font(this, true)))
        // Music "now playing": art + text, opaque background so the empty video plane never shows.
        nowPlaying = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF0E0D0D.toInt())
            setPadding(dp(96), 0, dp(96), dp(120))
            visibility = View.GONE
        }
        npArt = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Tok.SurfaceHigh) }
        nowPlaying.addView(npArt, LinearLayout.LayoutParams(dp(260), dp(260)))
        val npText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(40), 0, 0, 0) }
        npText.addView(text(12f, Tok.TextTertiary, true).apply { text = "NOW PLAYING"; letterSpacing = 0.08f })
        npTitle = text(32f, Tok.TextPrimary, true).apply { setPadding(0, dp(8), 0, dp(8)) }
        npArtist = text(16f, Tok.TextSecondary)
        npText.addView(npTitle); npText.addView(npArtist)
        nowPlaying.addView(npText, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(nowPlaying, FrameLayout.LayoutParams(-1, -1))



        // OSD: one bottom panel over a pre-baked vertical scrim (no blur, no animated alpha).
        osd = PlayerOsd(this) { action ->
            when (action) {
                OsdAction.Replay -> scrub(-1, 0)
                OsdAction.PlayPause -> { togglePlay(); scheduleHide() }
                OsdAction.Forward -> scrub(1, 0)
                OsdAction.NextEpisode -> nextEpisode?.let { playNext(it) }
                OsdAction.Audio -> openAudioSheet()
                OsdAction.Subtitles -> openSubtitleSheet()
                OsdAction.Settings -> openSettingsSheet()
                OsdAction.Episodes -> openEpisodesSheet()
            }
        }.apply { if (intent.getBooleanExtra("osdLayer", true)) setLayerType(View.LAYER_TYPE_HARDWARE, null) }
        title = osd.title; subtitle = osd.subtitle; seek = osd.seek
        timeLeft = osd.timeLeft; timeRight = osd.timeRight; buttons = osd.buttons
        playBtn = osd.play; episodesBtn = osd.episodes
        root.addView(osd, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        osd.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateCaptionInsets() }

        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            background = roundRect(Color.BLACK, dp(4).toFloat(), dp(2), Color.WHITE)
            setPadding(dp(2), dp(2), dp(2), dp(2))
            visibility = View.GONE
        }
        root.addView(thumb, FrameLayout.LayoutParams(dp(192), dp(108), Gravity.BOTTOM or Gravity.START).apply { bottomMargin = dp(210) })

        skipBtn = OsdButton(this, "Skip Intro").apply {
            visibility = View.GONE
            setOnClickListener {
                (tag as? MediaSegment)?.let { s -> skippedSegments += s; seekTo(s.endMs) }
                visibility = View.GONE
                tag = null
            }
        }
        root.addView(skipBtn, FrameLayout.LayoutParams(-2, dp(40), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(48), dp(160)) })

        upNext = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            background = roundRect(Tok.SurfaceRaised, dp(16).toFloat())
            setPadding(dp(20), dp(16), dp(20), dp(16))
            visibility = View.GONE
        }
        upNextText = text(16f, Tok.TextPrimary, true).apply { setPadding(0, 0, 0, dp(12)) }
        upNext.addView(upNextText)
        val unButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        unButtons.addView(OsdButton(this, "Next Episode", primary = true).apply { setOnClickListener { nextEpisode?.let { playNext(it) } } },
            LinearLayout.LayoutParams(-2, dp(44)).apply { rightMargin = dp(10) })
        unButtons.addView(OsdIconButton(this, "Dismiss", R.drawable.player_close).apply { setOnClickListener { upNextDismissed = true; hideUpNext() } },
            LinearLayout.LayoutParams(-2, dp(74)))
        upNext.addView(unButtons)
        root.addView(upNext, FrameLayout.LayoutParams(dp(360), -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(48), dp(48)) })

        stats = text(12f, Tok.TextPrimary).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            background = roundRect(0xD9000000.toInt(), dp(8).toFloat())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
        }
        root.addView(stats, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(40), dp(24), 0, 0) })

        banner = text(14f, Tok.TextPrimary, true).apply {
            background = roundRect(Tok.SurfaceRaised, dp(20).toFloat())
            setPadding(dp(20), dp(10), dp(20), dp(10))
            visibility = View.GONE
        }
        root.addView(banner, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(27) })

        sheetList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        sheet = ScrollView(this).apply {
            isSmoothScrollingEnabled = false
            background = roundRect(Tok.SurfaceRaised, dp(16).toFloat())
            visibility = View.GONE
            addView(sheetList)
        }
        root.addView(sheet, FrameLayout.LayoutParams(dp(380), -2, Gravity.CENTER_VERTICAL or Gravity.END).apply { setMargins(0, dp(27), dp(48), dp(27)) })
        spinner.bringToFront()
        playerView.requestFocus()

    }
}
