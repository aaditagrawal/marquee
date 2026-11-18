package com.mav.marquee.ui.settings

import com.mav.marquee.ui.library.kit.instantDpad
import com.mav.marquee.ui.library.kit.onInstantPreviewKeyEvent

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mav.marquee.BuildConfig
import com.mav.marquee.data.*
import com.mav.marquee.image.MarqueeImages
import com.mav.marquee.ui.components.*
import com.mav.marquee.ui.library.kit.*
import com.mav.marquee.ui.nav.*
import com.mav.marquee.ui.theme.*
import kotlinx.coroutines.*

private data class PreferenceChoice(val title: String, val labels: List<String>, val values: List<String>, val selected: String, val save: (String) -> Unit)
private object SettingsMemory {
    val list = LazyListState()
    val focus = FocusMemory()
    val keys = listOf("account", "switch", "playback", "bitrate", "audio", "subLang", "subMode", "intro",
        "credits", "next", "diag", "images", "quality", "cache", "about", "licences")
    val controls = keys.filterNot { it == "account" || it == "playback" || it == "images" }
}
private val LANG_CODES = listOf("", "eng", "hin", "jpn", "kor", "tam", "tel", "mal", "fra", "spa", "deu", "ita", "zho")
private val LANG_NAMES = listOf("File default", "English", "Hindi", "Japanese", "Korean", "Tamil", "Telugu", "Malayalam", "French", "Spanish", "German", "Italian", "Chinese")
private val BITRATES = listOf(0, 120_000_000, 80_000_000, 60_000_000, 40_000_000, 20_000_000, 15_000_000, 10_000_000, 8_000_000, 4_000_000, 2_000_000)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val testPolicy = remember { ScreenTestPolicy.from(ctx) }
    val p = remember { PlaybackPrefs.get(ctx) }
    var revision by remember { mutableIntStateOf(0) }
    var choice by remember { mutableStateOf<PreferenceChoice?>(null) }
    var server by remember { mutableStateOf<PublicSystemInfo?>(null) }
    var user by remember { mutableStateOf<UserDto?>(null) }
    var serverLoading by remember { mutableStateOf(true) }
    var serverError by remember { mutableStateOf(false) }
    var serverRetry by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var switchConfirm by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    // Open-source licences: the file list (null = closed) with its focused entry, then the open text.
    var licenceFiles by remember { mutableStateOf<List<LicenceFile>>(emptyList()) }
    var licenceList by remember { mutableStateOf<Int?>(null) }
    var licenceOpen by remember { mutableStateOf<Int?>(null) }
    val licenceNotice = androidx.compose.ui.res.stringResource(com.mav.marquee.R.string.licence_notice,
        androidx.compose.ui.res.stringResource(com.mav.marquee.R.string.source_repository_url))
    var busy by remember { mutableStateOf(false) }
    var accountBusy by remember { mutableStateOf(false) }
    val focus = SettingsMemory.focus
    LaunchedEffect(Unit) { focus.arm("switch") }
    LaunchedEffect(serverRetry) {
        serverLoading = true; serverError = false
        try {
            testPolicy.before("settings")
            coroutineScope {
                val serverRequest = async { JellyfinRepository.publicInfo() }
                val userRequest = async { JellyfinRepository.currentUser() }
                server = serverRequest.await(); user = userRequest.await()
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { serverError = true }
        finally { serverLoading = false }
    }
    fun options(title: String, labels: List<String>, values: List<String>, selected: String, save: (String) -> Unit) {
        choice = PreferenceChoice(title, labels, values, selected, save)
    }
    fun languageName(code: String) = LANG_NAMES.getOrNull(LANG_CODES.indexOf(code)) ?: code.uppercase()
    // Snapshot values once per edit; lazy item lambdas capture the revision as well.
    val values = remember(revision) {
        listOf(p.maxBitrateBps.toString(), p.audioLanguage, p.subtitleLanguage, p.subtitleMode,
            p.skipIntro, p.skipCredits, p.autoplayNext.toString(), p.diagnosticsOverlay.toString(), p.imageQuality)
    }
    if (BuildConfig.DEBUG) ScreenStateMarker("settings", when {
        accountBusy -> "switch"
        busy -> "cache"
        serverLoading -> "loading"
        serverError -> "error"
        else -> "content"
    })
    Box(Modifier.fillMaxSize().instantDpad().background(MarqueeColors.Background)) {
        Row(Modifier.fillMaxSize().padding(horizontal = MarqueeDims.SafeH, vertical = MarqueeDims.SafeV)) {
            Column(Modifier.width(256.dp).padding(top = 12.dp)) {
                KText("MARQUEE", MarqueeType.Eyebrow, color = MarqueeColors.Sky)
                Spacer(Modifier.height(12.dp)); KText("Settings", MarqueeType.Display)
                Spacer(Modifier.height(24.dp))
                Box(Modifier.size(32.dp).background(MarqueeColors.SurfaceHigh, MarqueeDims.ShapePill).clip(MarqueeDims.ShapePill), contentAlignment = Alignment.Center) {
                    val avatar = ImageUrls.user(Session.userId, user?.primaryImageTag, 64)
                    if (avatar != null) com.mav.marquee.image.RemoteImage(avatar, Modifier.fillMaxSize())
                    else KText(Session.userName.take(1).uppercase(), MarqueeType.Label)
                }
                Spacer(Modifier.height(12.dp)); KText(Session.userName, MarqueeType.Title)
                Spacer(Modifier.height(8.dp)); KText(server?.serverName ?: "Jellyfin", MarqueeType.Body, color = MarqueeColors.TextSecondary)
                KText(Session.serverUrl, MarqueeType.Meta, color = MarqueeColors.TextTertiary, maxLines = 2)
                Spacer(Modifier.height(8.dp)); server?.version?.let { KText("Jellyfin $it", MarqueeType.Meta, color = MarqueeColors.TextTertiary) }
                Box(Modifier.fillMaxWidth().height(64.dp)) {
                    if (serverLoading) Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(12.dp), color = MarqueeColors.Sky, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); KText("Checking server…", MarqueeType.Meta, color = MarqueeColors.TextSecondary)
                    } else if (serverError) TvChip("Server unavailable · Retry", { serverRetry++ })
                }
                Spacer(Modifier.weight(1f))
                status?.let { KText(it, MarqueeType.Body, color = MarqueeColors.Sky, maxLines = 3) }
                KText("Marquee ${BuildConfig.VERSION_NAME}\n${Session.deviceName}", MarqueeType.Meta, color = MarqueeColors.TextTertiary)
            }
            Spacer(Modifier.width(40.dp))
            CompositionLocalProvider(LocalBringIntoViewSpec provides InstantBringIntoView) {
                LazyColumn(Modifier.weight(1f).fillMaxHeight().onInstantPreviewKeyEvent { event ->
                    if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) false else {
                        val delta = when (event.nativeKeyEvent.keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_UP -> -1
                            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> 1
                            else -> 0
                        }
                        if (delta == 0) false else {
                            val current = SettingsMemory.controls.indexOf(focus.pending ?: focus.last).coerceAtLeast(0)
                            val next = SettingsMemory.controls[(current + delta).coerceIn(0, SettingsMemory.controls.lastIndex)]
                            focus.focus(next)
                            if (focus.pending != null) SettingsMemory.list.requestScrollToItem(SettingsMemory.keys.indexOf(next))
                            true
                        }
                    }
                }, state = SettingsMemory.list, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "account", contentType = "section") { Section("Account") }
                    item(key = "switch", contentType = "section") { TvListRow("Switch user", { switchConfirm = true }, Modifier.snapListFocus(SettingsMemory.list, "switch", SettingsMemory.keys.indexOf("switch")).restorable(focus, "switch"), subtitle = if (accountBusy) "Signing out…" else "Choose another profile on this server", busy = accountBusy) }
                    item(key = "playback", contentType = "section") { Section("Playback") }
                    item(key = "bitrate", contentType = "section") { TvListRow("Maximum bitrate", {
                        options("Maximum bitrate", BITRATES.map { if (it == 0) "Auto" else "${it / 1_000_000} Mbps" }, BITRATES.map { it.toString() }, p.maxBitrateBps.toString()) { p.maxBitrateBps = it.toInt() }
                    }, Modifier.snapListFocus(SettingsMemory.list, "bitrate", SettingsMemory.keys.indexOf("bitrate")).restorable(focus, "bitrate"), value = if (values[0] == "0") "Auto" else "${values[0].toInt() / 1_000_000} Mbps") }
                    item(key = "audio", contentType = "section") { TvListRow("Audio language", { options("Audio language", LANG_NAMES, LANG_CODES, p.audioLanguage) { p.audioLanguage = it } }, Modifier.snapListFocus(SettingsMemory.list, "audio", SettingsMemory.keys.indexOf("audio")).restorable(focus, "audio"), value = languageName(values[1])) }
                    item(key = "subLang", contentType = "section") { TvListRow("Subtitle language", { options("Subtitle language", listOf("No preference") + LANG_NAMES.drop(1), LANG_CODES, p.subtitleLanguage) { p.subtitleLanguage = it } }, Modifier.snapListFocus(SettingsMemory.list, "subLang", SettingsMemory.keys.indexOf("subLang")).restorable(focus, "subLang"), value = if (values[2].isEmpty()) "No preference" else languageName(values[2])) }
                    item(key = "subMode", contentType = "section") { TvListRow("Subtitles", { options("Subtitles", listOf("File default", "Always on", "Forced only", "Off", "Smart"), listOf("Default", "Always", "OnlyForced", "None", "Smart"), p.subtitleMode) { p.subtitleMode = it } }, Modifier.snapListFocus(SettingsMemory.list, "subMode", SettingsMemory.keys.indexOf("subMode")).restorable(focus, "subMode"), value = values[3]) }
                    item(key = "intro", contentType = "section") { TvListRow("Skip intro", { options("Skip intro", listOf("Automatic", "Show button", "Off"), listOf("auto", "button", "off"), p.skipIntro) { p.skipIntro = it } }, Modifier.snapListFocus(SettingsMemory.list, "intro", SettingsMemory.keys.indexOf("intro")).restorable(focus, "intro"), value = skipLabel(values[4])) }
                    item(key = "credits", contentType = "section") { TvListRow("Skip credits", { options("Skip credits", listOf("Automatic", "Show button", "Off"), listOf("auto", "button", "off"), p.skipCredits) { p.skipCredits = it } }, Modifier.snapListFocus(SettingsMemory.list, "credits", SettingsMemory.keys.indexOf("credits")).restorable(focus, "credits"), value = skipLabel(values[5])) }
                    item(key = "next", contentType = "section") { TvListRow("Autoplay next episode", { p.autoplayNext = !p.autoplayNext; revision++ }, Modifier.snapListFocus(SettingsMemory.list, "next", SettingsMemory.keys.indexOf("next")).restorable(focus, "next"), value = if (values[6] == "true") "On" else "Off") }
                    item(key = "diag", contentType = "section") { TvListRow("Playback diagnostics", { p.diagnosticsOverlay = !p.diagnosticsOverlay; revision++ }, Modifier.snapListFocus(SettingsMemory.list, "diag", SettingsMemory.keys.indexOf("diag")).restorable(focus, "diag"), value = if (values[7] == "true") "On" else "Off") }
                    item(key = "images", contentType = "section") { Section("Images and app") }
                    item(key = "quality", contentType = "section") { TvListRow("Image quality", { options("Image quality", listOf("Low", "Standard", "High"), listOf("low", "standard", "high"), p.imageQuality) { p.imageQuality = it } }, Modifier.snapListFocus(SettingsMemory.list, "quality", SettingsMemory.keys.indexOf("quality")).restorable(focus, "quality"), value = values[8].replaceFirstChar { it.uppercase() }) }
                    item(key = "cache", contentType = "section") { TvListRow("Clear image cache", {
                        if (!busy) { busy = true; scope.launch {
                            runCatching { testPolicy.before("cache"); clearImages() }.onSuccess { status = "Image cache cleared" }.onFailure { status = "Couldn't clear image cache" }
                            busy = false
                        } }
                    }, Modifier.snapListFocus(SettingsMemory.list, "cache", SettingsMemory.keys.indexOf("cache")).restorable(focus, "cache"), value = if (busy) "Clearing…" else null, busy = busy) }
                    item(key = "about", contentType = "section") { TvListRow("About Marquee", { about = true }, Modifier.snapListFocus(SettingsMemory.list, "about", SettingsMemory.keys.indexOf("about")).restorable(focus, "about"), value = BuildConfig.VERSION_NAME) }
                    item(key = "licences", contentType = "section") { TvListRow("Open-source licences", {
                        licenceFiles = Licences.list(ctx.assets); licenceList = 0
                    // Last row: always scroll by index (clamps to the list end, keeping the bottom padding), so a
                    // partly visible row is not aligned flush with the viewport edge, which clips its focus ring.
                    }, Modifier.snapListFocus(SettingsMemory.list, Unit, SettingsMemory.keys.indexOf("licences")).restorable(focus, "licences"), subtitle = licenceNotice) }
                }
            }
        }
        choice?.let { c -> OptionSheet(c.title, c.labels, c.values.indexOf(c.selected),
            onSelect = { c.save(c.values[it]); revision++ }, onDismiss = { choice = null; focus.arm("switch") }) }
        if (switchConfirm) OptionSheet("Switch user?", listOf("Cancel", "Sign out and choose a user"), 0,
            onSelect = { if (it == 1) { accountBusy = true; scope.launch {
                try {
                testPolicy.before("switch")
                JellyfinRepository.logout()
                // Drop the previous account's cached Home snapshot, startup art and prepared streams.
                com.mav.marquee.ui.home.HomeController.reset()
                discardPreparedPlayback()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.mav.marquee.data.HomeRepository.clear(ctx)
                    com.mav.marquee.image.StartupArt.clear()
                    com.mav.marquee.image.HeroArt.clear()
                }
                com.mav.marquee.ui.library.LibraryModel.clear()
                com.mav.marquee.ui.detail.DetailModel.clear()
                com.mav.marquee.ui.person.PersonModel.clear()
                ItemCache.clear()
                SettingsMemory.focus.last = null
                SettingsMemory.list.scrollToItem(0)
                nav.reset(Route.Login)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { status = "Couldn't switch users. Try again." }
                finally { accountBusy = false }
            } } },
            onDismiss = { switchConfirm = false; focus.arm("switch") })
        licenceList?.let { start ->
            OptionSheet("Open-source licences", licenceFiles.map { it.title }, -1,
                onSelect = { licenceOpen = it },
                // Explicit target: while sheets swap, focus can pass through another row and become `last`.
                onDismiss = { licenceList = null; if (licenceOpen == null) focus.focus("licences") },
                subtitles = licenceFiles.map { it.detail }, initialFocus = start)
        }
        licenceOpen?.let { index ->
            val file = licenceFiles[index]
            var chunks by remember(file) { mutableStateOf<List<com.mav.marquee.ui.library.kit.TextChunk>?>(null) }
            LaunchedEffect(file) {
                chunks = runCatching { withContext(Dispatchers.IO) { Licences.read(ctx.assets, file) } }
                    .getOrElse { if (it is CancellationException) throw it else listOf(com.mav.marquee.ui.library.kit.TextChunk("Couldn't open ${file.path}.", true)) }
            }
            TextSheet(file.title, chunks, onDismiss = { licenceOpen = null; licenceList = index })
        }
        if (about) Box(Modifier.fillMaxSize().background(MarqueeColors.ScrimStrong), contentAlignment = Alignment.Center) {
            androidx.activity.compose.BackHandler { about = false; focus.arm("about") }
            Column(Modifier.width(480.dp).background(MarqueeColors.SurfaceRaised, MarqueeDims.ShapePanel).padding(32.dp)) {
                KText("Marquee", MarqueeType.Display); Spacer(Modifier.height(12.dp))
                KText("Your library, made for the living room.", MarqueeType.BodyLarge, color = MarqueeColors.TextSecondary)
                Spacer(Modifier.height(16.dp)); KText("Version ${BuildConfig.VERSION_NAME}\nNative Android TV · Jellyfin · Media3\nHanken Grotesk, SIL Open Font License", MarqueeType.Body, color = MarqueeColors.TextSecondary)
                Spacer(Modifier.height(24.dp)); PillButton("Done", { about = false; focus.arm("about") }, Modifier.restorable(focus, "about_done"))
            }
            LaunchedEffect(Unit) { focus.focus("about_done") }
        }
    }
}

@Composable
private fun Section(title: String) = KText(title.uppercase(), MarqueeType.Eyebrow, Modifier.padding(start = 16.dp, top = 24.dp, bottom = 12.dp), color = MarqueeColors.TextTertiary)
private fun skipLabel(v: String) = when(v) { "auto" -> "Automatic"; "off" -> "Off"; else -> "Show button" }

private suspend fun clearImages() { MarqueeImages.clearCache() }

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun discardPreparedPlayback() = com.mav.marquee.player.PlaybackPrewarm.discardPending()
