package com.mav.marquee.image

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.net.Uri
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.request.bitmapConfig
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap
import com.mav.marquee.data.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath

/**
 * Image pipeline sized for low-memory TVs (reference: BRAVIA VU1, Mali-G31, 192 MB heap growth limit).
 *
 * - Network + disk cache: Coil 3 core (no coil-compose; the composable is [RemoteImage]).
 * - Memory cache: our own byte-bounded LRU keyed by the full URL. URLs already encode the exact pixel size
 *   (see docs/architecture/IMAGE_SIZES.md), so a hit can be drawn synchronously on the first frame.
 * - Opaque art decodes as RGB_565 (half the memory of ARGB_8888); logos keep alpha.
 */
object MarqueeImages {
    private lateinit var app: Context
    /** 40 MiB at the reference 192 MB memory class; smaller on smaller heaps, never larger. */
    private var memBytes = REFERENCE_MEM_BYTES
    private const val REFERENCE_MEM_BYTES = 40 * 1024 * 1024

    internal fun memoryBudget(memoryClassMb: Int, lowRam: Boolean): Int {
        val scaled = if (memoryClassMb in 1 until 192) (REFERENCE_MEM_BYTES.toLong() * memoryClassMb / 192).toInt() else REFERENCE_MEM_BYTES
        return (if (lowRam) scaled / 2 else scaled).coerceAtLeast(8 * 1024 * 1024)
    }
    @Volatile var hardwareBitmaps: Boolean = false
        private set

    /** Release measurement control; changes only for a newly created Activity/process. */
    fun setHardwareBitmaps(enabled: Boolean) {
        // Hardware bitmaps are decoded with ImageDecoder (API 28+).
        hardwareBitmaps = enabled && android.os.Build.VERSION.SDK_INT >= 28
        android.util.Log.i("Marquee", "MARQUEE_BITMAP_MODE hardware=$enabled")
    }
    private fun key(url: String, hardware: Boolean) = if (hardware) "$url#hardware" else url

    private val memory = object : LruCache<String, Bitmap>(REFERENCE_MEM_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val blur = LruCache<String, Bitmap>(256)
    private val inFlight = HashMap<String, Deferred<Bitmap?>>()

    @OptIn(ExperimentalCoroutinesApi::class)
    val loader: ImageLoader by lazy {
        ImageLoader.Builder(app)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { Session.httpClient })) }
            .memoryCache(null as coil3.memory.MemoryCache?)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCache {
                DiskCache.Builder().directory(app.cacheDir.resolve("img").toOkioPath()).maxSizeBytes(384L * 1024 * 1024).build()
            }
            .decoderCoroutineContext(Dispatchers.Default.limitedParallelism(2))
            .fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(8))
            .allowHardware(false)
            .build()
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefetchGate = Semaphore(3)

    fun init(context: Context) {
        app = context.applicationContext
        val am = app.getSystemService(android.app.ActivityManager::class.java)
        memBytes = memoryBudget(am?.memoryClass ?: 192, am?.isLowRamDevice == true)
        // Compare with the cache's current limit, not the reference value: init can run again in the same
        // process (tests), and a limit shrunk by an earlier call must grow back. No-op on the reference device.
        if (memory.maxSize() != memBytes) memory.resize(memBytes)
        HeroArt.init(app); StartupArt.init(app)
    }

    fun cached(url: String): Bitmap? = memory.get(key(url, hardwareBitmaps))

    /** Counts and dimensions only. Never include URLs, headers, or user tokens in diagnostics. */
    fun stats(): String {
        val bitmaps = memory.snapshot().values
        return "bytes=${memory.size()} limit=$memBytes count=${bitmaps.size} " +
            "rgb565=${bitmaps.count { it.config == Bitmap.Config.RGB_565 }} " +
            "argb8888=${bitmaps.count { it.config == Bitmap.Config.ARGB_8888 }} " +
            "hardware=${bitmaps.count { it.config == Bitmap.Config.HARDWARE }} " +
            "maxWidth=${bitmaps.maxOfOrNull { it.width } ?: 0} maxHeight=${bitmaps.maxOfOrNull { it.height } ?: 0}"
    }

    fun blurHash(hash: String?): Bitmap? {
        if (hash == null) return null
        blur.get(hash)?.let { return it }
        return BlurHash.decode(hash)?.also { blur.put(hash, it) }
    }

    /** Loads (disk/network + decode) and stores in the memory LRU. Returns null on failure. */
    suspend fun load(url: String, opaque: Boolean = true, diskOnly: Boolean = false, software: Boolean = false): Bitmap? {
        val hardware = hardwareBitmaps && !software
        val cacheKey = key(url, hardware)
        memory.get(cacheKey)?.let { return it }
        // Visible nodes and prefetch often ask for the same image at the same time. Share the decode
        // and upload, rather than holding two identical bitmaps while requests race to populate LRU.
        val task = synchronized(inFlight) {
            inFlight[cacheKey] ?: scope.async(start = CoroutineStart.LAZY) { decode(url, opaque, diskOnly, hardware) }.also { job ->
                inFlight[cacheKey] = job
                job.invokeOnCompletion { synchronized(inFlight) { if (inFlight[cacheKey] === job) inFlight.remove(cacheKey) } }
            }
        }
        task.start()
        return task.await()
    }

    private suspend fun decode(url: String, opaque: Boolean, diskOnly: Boolean, hardware: Boolean): Bitmap? {
        val cacheKey = key(url, hardware)
        memory.get(cacheKey)?.let { return it }
        if (diskOnly) StartupArt.read(url, opaque, hardware && opaque)?.let { memory.put(cacheKey, it); return it }
        val uri = Uri.parse(url)
        val width = (uri.getQueryParameter("fillWidth") ?: uri.getQueryParameter("maxWidth"))?.toIntOrNull()
        val height = (uri.getQueryParameter("fillHeight") ?: uri.getQueryParameter("maxHeight"))?.toIntOrNull()
        val req = ImageRequest.Builder(app).data(url)
            .networkCachePolicy(if (diskOnly) CachePolicy.DISABLED else CachePolicy.ENABLED)
            .apply { if (width != null && height != null) size(width, height) }
            .precision(Precision.EXACT)
            .scale(Scale.FIT)
            .bitmapConfig(if (hardware && opaque) Bitmap.Config.HARDWARE else if (opaque) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
            .allowHardware(hardware && opaque)
            .allowRgb565(opaque && !hardware)
            .build()
        val res = loader.execute(req) as? SuccessResult ?: return null
        val bmp = res.image.toBitmap()
        if (bmp.config != Bitmap.Config.HARDWARE) bmp.prepareToDraw()
        memory.put(cacheKey, bmp)
        return bmp
    }

    /** Focus-dwell artwork belongs to its caller: cancel its fetch/decode when focus moves. */
    suspend fun loadFocused(url: String): Bitmap? = memory.get(url) ?: decode(url, opaque = true, diskOnly = false, hardware = false)

    /** Settings can call this from a coroutine. No IO or eviction work happens on the main thread. */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        synchronized(inFlight) { inFlight.values.toList() }.forEach { it.cancel() }
        memory.evictAll()
        blur.evictAll()
        HeroArt.clear()
        StartupArt.clear()
        loader.diskCache?.clear()
    }

    /** Warm the memory cache for upcoming cards. Bounded concurrency so it never starves visible loads. */
    fun prefetch(urls: Iterable<String?>, opaque: Boolean = true) {
        for (u in urls) {
            if (u == null || cached(u) != null) continue
            scope.launch { prefetchGate.withPermit { if (cached(u) == null) runCatching { load(u, opaque) } } }
        }
    }

    /** Persist only the four/six startup cards and logo, never the whole scrolling library. */
    fun pinStartup(urls: Iterable<String?>, opaque: Boolean = true) {
        for (url in urls.filterNotNull()) scope.launch {
            runCatching { load(url, opaque, software = true)?.let { StartupArt.write(url, opaque, it) } }
        }
    }

    /** Fetch into the disk cache only (big backdrops): decodes a tiny sample so no RAM is held. */
    fun warmDisk(urls: Iterable<String?>) {
        for (u in urls) {
            if (u == null || cached(u) != null) continue
            scope.launch {
                prefetchGate.withPermit {
                    runCatching {
                        loader.execute(ImageRequest.Builder(app).data(u).size(32, 18).bitmapConfig(Bitmap.Config.RGB_565).build())
                    }
                }
            }
        }
    }

    fun trim(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) memory.trimToSize(memBytes / 2)
        HeroArt.trim(level)
    }

    suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
