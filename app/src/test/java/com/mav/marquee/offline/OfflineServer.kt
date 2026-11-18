package com.mav.marquee.offline

import android.content.Context
import android.graphics.*
import com.mav.marquee.data.*
import com.mav.marquee.image.MarqueeImages
import com.mav.marquee.image.HeroArt
import com.mav.marquee.ui.home.HomeController
import com.mav.marquee.ui.library.kit.ItemCache
import com.mav.marquee.ui.detail.DetailModel
import com.mav.marquee.ui.library.LibraryModel
import com.mav.marquee.R
import androidx.core.content.res.ResourcesCompat
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/** In-process HTTP fixture API over synthetic data. It never proxies anywhere and rejects all mutations. */
class OfflineServer(private val context: Context) : AutoCloseable {
    private val server = MockWebServer()
    val requests = CopyOnWriteArrayList<String>()
    @Volatile var fail = false
    @Volatile var delayMs = 0L
    @Volatile var holdData = false
    @Volatile var failImages = false
    private val catalog = OfflineFixtures.catalog
    private val episodes = OfflineFixtures.episodes
    private val all = (catalog + episodes + OfflineFixtures.result("resume").items + OfflineFixtures.result("nextup").items + OfflineFixtures.movie + OfflineFixtures.series).associateBy { it.id }
    private val images = HashMap<String, ByteArray>()
    val url get() = server.url("/").toString().trimEnd('/')
    fun start() {
        OfflineDeterminism.resetSearch()
        kotlinx.coroutines.runBlocking { MarqueeImages.clearCache() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val u = request.requestUrl!!
                val path = u.encodedPath
                requests += path
                require(u.host in setOf("localhost", "127.0.0.1"))
                if (request.method != "GET") return MockResponse().setResponseCode(405)
                if ("/Images/" in path) {
                    if (failImages) return MockResponse().setResponseCode(404)
                    val bytes = synchronized(images) { images.getOrPut(u.toString()) { artwork(path, (u.queryParameter("fillWidth") ?: u.queryParameter("maxWidth"))?.toIntOrNull(), (u.queryParameter("fillHeight") ?: u.queryParameter("maxHeight"))?.toIntOrNull()) } }
                    return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes))
                }
                if (fail) return MockResponse().setResponseCode(503)
                if (holdData) return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
                val data = when {
                    path == "/UserViews" -> OfflineFixtures.raw("views")
                    path == "/UserItems/Resume" -> OfflineFixtures.raw("resume")
                    path == "/Shows/NextUp" -> OfflineFixtures.raw(if (u.queryParameter("seriesId") == OfflineFixtures.SERIES_ID) "series-nextup" else "nextup")
                    path.endsWith("/Seasons") -> OfflineFixtures.raw("series-seasons")
                    path.endsWith("/Episodes") -> OfflineFixtures.raw("series-episodes")
                    path.endsWith("/Similar") -> OfflineFixtures.itemsJson(catalog.filter { it.type == "Movie" }.take(8))
                    path.endsWith("/SpecialFeatures") || path.endsWith("/LocalTrailers") -> "[]"
                    path == "/System/Info/Public" -> "{\"ServerName\":\"Offline fixture\",\"Version\":\"10.11.11\"}"
                    path == "/Users/Me" -> "{\"Id\":\"offline-home-tv\",\"Name\":\"Demo\"}"
                    path == "/Users" || path == "/Users/Public" -> "[{\"Id\":\"offline-home-tv\",\"Name\":\"Demo\"}]"
                    path == "/Genres" -> "{\"Items\":[{\"Id\":\"comedy\",\"Name\":\"Comedy\"}]}"
                    path == "/Items" -> {
                        val types = u.queryParameter("includeItemTypes")?.split(',')
                        val term = u.queryParameter("searchTerm")
                        var items = if (types?.contains("Episode") == true) episodes else catalog
                        if (types != null) items = items.filter { it.type in types }
                        if (term != null) items = items.filter { (it.seriesName ?: it.name).contains(term, true) }
                        if (u.queryParameter("nameLessThan") != null) items = items.filter { it.name < u.queryParameter("nameLessThan")!! }
                        val total = items.size
                        val start = u.queryParameter("startIndex")?.toIntOrNull() ?: 0
                        val limit = u.queryParameter("limit")?.toIntOrNull() ?: total
                        MarqueeJson.encodeToString(ItemsResult.serializer(), ItemsResult(items.drop(start).take(limit), total, start))
                    }
                    path.startsWith("/Items/") -> all[path.removePrefix("/Items/")]?.let(OfflineFixtures::itemJson) ?: "{}"
                    path.contains("PlaybackInfo") -> "{\"MediaSources\":[],\"PlaySessionId\":\"offline\"}"
                    else -> "{\"Items\":[],\"TotalRecordCount\":0}"
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(data)
                    .setBodyDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 19096)
        Session.login(url, "offline-dummy-not-a-secret", "offline-home-tv", "Demo")
        HomeController.reset()
        OfflineTestState.field(HomeController, "account", "$url|${Session.userId}")
        HomeController.error.value = null
        HomeController.setContextArtEnabled(false)
        DetailModel.clear(); LibraryModel.clear(); ItemCache.clear()
    }
    /** Generated local image doubles; production layouts, shaders and type remain unchanged. */
    private fun artwork(path: String, requestedW: Int?, requestedH: Int?): ByteArray {
        val parts = path.split('/')
        val item = all[parts.getOrNull(2)]
        val logo = path.contains("/Logo")
        val landscape = path.contains("/Backdrop") || path.contains("/Thumb") || (requestedW ?: 0) > (requestedH ?: 0) || item?.type == "Episode"
        val width = if (logo) requestedW ?: 720 else if (landscape) 640 else 256
        val height = if (logo) requestedH ?: 160 else if (landscape) 360 else 384
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val hash = (item?.id ?: path).hashCode()
        if (!logo) {
            val hue = ((hash.toLong() and 0x7fffffff) % 360).toFloat()
            val colors = intArrayOf(Color.HSVToColor(floatArrayOf(hue, .4f, .40f)), Color.HSVToColor(floatArrayOf((hue+36)%360, .55f, .18f)))
            paint.shader = LinearGradient(0f,0f,width.toFloat(),height.toFloat(),colors,null,Shader.TileMode.CLAMP)
            canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
            paint.shader = null; paint.color = 0x20FFFFFF
            canvas.drawCircle(width*.78f,height*.25f,width*.35f,paint)
            paint.color = 0x20000000
            canvas.drawCircle(width*.55f,height*.9f,width*.48f,paint)
        }
        if (logo || !landscape) {
            paint.typeface = ResourcesCompat.getFont(context, R.font.hanken_grotesk_semibold)
            paint.color = 0xFFF8F7F7.toInt()
            val name = if (item?.type == "Episode") item.seriesName ?: item.name else item?.name ?: "Marquee"
            val words = name.split(' ')
            paint.textSize = if (logo) 48f else 28f
            val maxWidth = width - if (logo) 0 else 32
            var y = if (logo) 42f else height*.55f
            var line = ""
            for (word in words) {
                if (paint.measureText("$line $word") > maxWidth && line.isNotBlank()) {
                    canvas.drawText(line, if (logo) 0f else 16f, y, paint); y += paint.textSize*1.1f; line = word
                } else line = if (line.isBlank()) word else "$line $word"
            }
            canvas.drawText(line, if (logo) 0f else 16f, y, paint)
        }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); bmp.recycle() }.toByteArray()
    }
    override fun close() {
        HomeController.reset(); DetailModel.clear(); LibraryModel.clear()
        Session.httpClient.dispatcher.cancelAll()
        server.shutdown()
    }
}
