package com.mav.marquee.ui.login

import com.mav.marquee.ui.library.kit.instantDpad
import com.mav.marquee.ui.library.kit.onInstantPreviewKeyEvent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mav.marquee.BuildConfig
import com.mav.marquee.data.*
import com.mav.marquee.ui.components.*
import com.mav.marquee.ui.library.kit.*
import com.mav.marquee.ui.nav.*
import com.mav.marquee.ui.theme.*
import kotlinx.coroutines.*

private enum class LoginStep { Server, Users, Password, QuickConnect }

/** Server -> visible profiles or hidden username -> password / cancellable Quick Connect. No password is persisted. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LoginScreen() {
    val ctx = LocalContext.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val testPolicy = remember { ScreenTestPolicy.from(ctx) }
    val keyboard = LocalSoftwareKeyboardController.current
    // First run starts empty; after sign-out the previous server is offered again.
    var address by remember { mutableStateOf(Session.serverUrl) }
    var step by remember { mutableStateOf(LoginStep.Server) }
    var info by remember { mutableStateOf<PublicSystemInfo?>(null) }
    var users by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var selectedUser by remember { mutableStateOf<UserDto?>(null) }
    var qcEnabled by remember { mutableStateOf(false) }
    var qcCode by remember { mutableStateOf<String?>(null) }
    var qcGeneration by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val initialFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }

    fun finish() { password = ""; keyboard?.hide(); nav.reset(Route.Home) }
    fun connect() {
        if (busy) return
        busy = true; error = null; keyboard?.hide()
        scope.launch {
            try {
                testPolicy.before("login")
                // No scheme typed: https:// first; http:// only if https is unreachable, never after an untrusted
                // certificate (see ServerProbe). Rejects user:password@ addresses.
                val (base, publicInfo) = ServerProbe.resolve(address)
                info = publicInfo
                Session.setServer(base); address = base
                coroutineScope {
                    val us = async { JellyfinRepository.publicUsers() }
                    val enabled = async { JellyfinRepository.quickConnectEnabled() }
                    users = us.await(); qcEnabled = enabled.await()
                }
                step = LoginStep.Users
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = if (e is ServerAddress.Invalid || e is ServerProbe.Failure) e.message else "Couldn't reach this Jellyfin server. Check the address and connection." }
            finally { busy = false }
        }
    }
    fun signIn() {
        if (busy || username.isBlank()) return
        busy = true; error = null; keyboard?.hide()
        scope.launch {
            try { testPolicy.before("signin"); JellyfinRepository.authenticateByName(username.trim(), password); finish() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = if (e is HttpException && e.code == 401) "Username or password wasn't accepted." else "Sign-in failed. Try again." }
            finally { busy = false }
        }
    }
    fun choose(user: UserDto) {
        selectedUser = user; username = user.name; password = ""; error = null
        if (user.hasPassword) step = LoginStep.Password else { step = LoginStep.Password; signIn() }
    }
    BackHandler(step != LoginStep.Server) {
        keyboard?.hide(); password = ""; error = null
        step = if (step == LoginStep.Password || step == LoginStep.QuickConnect) LoginStep.Users else LoginStep.Server
    }
    LaunchedEffect(step) {
        runCatching { initialFocus.requestFocus() }
    }
    LaunchedEffect(step, qcGeneration) {
        if (step != LoginStep.QuickConnect) return@LaunchedEffect
        qcCode = null; error = null
        try {
            testPolicy.before("quickconnect")
            val qc = JellyfinRepository.quickConnectInitiate()
            qcCode = qc.code
            val start = android.os.SystemClock.elapsedRealtime()
            while (android.os.SystemClock.elapsedRealtime() - start < 9 * 60_000) {
                delay(1500)
                if (JellyfinRepository.quickConnectState(qc.secret).authenticated) {
                    JellyfinRepository.quickConnectAuthenticate(qc.secret)
                    finish(); return@LaunchedEffect
                }
            }
            error = "This code expired. Get a new code to continue."
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "Quick Connect couldn't connect. Try a new code or use a password." }
    }

    if (BuildConfig.DEBUG) ScreenStateMarker("login", when {
        busy -> "busy"
        error != null -> "error"
        step == LoginStep.QuickConnect -> "quickconnect"
        else -> step.name
    })
    Box(Modifier.fillMaxSize().instantDpad().background(MarqueeColors.Background)) {
        Row(Modifier.fillMaxSize().padding(horizontal = MarqueeDims.SafeH, vertical = MarqueeDims.SafeV), horizontalArrangement = Arrangement.spacedBy(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(280.dp)) {
                KText("MARQUEE", MarqueeType.Eyebrow.copy(letterSpacing = 4.sp), color = MarqueeColors.Sky)
                Spacer(Modifier.height(24.dp)); KText("A good night\nstarts here.", MarqueeType.Display)
                Spacer(Modifier.height(24.dp)); KText("Your movies and shows,\nright where you left them.", MarqueeType.BodyLarge, color = MarqueeColors.TextSecondary)
                if (info != null) {
                    Spacer(Modifier.height(32.dp)); KText(info?.serverName ?: "Jellyfin", MarqueeType.Title)
                    Spacer(Modifier.height(8.dp)); KText(address, MarqueeType.Meta, color = MarqueeColors.TextTertiary, maxLines = 2)
                    // Sign-in over a resolved http:// address sends the password unencrypted; say so plainly.
                    if (step != LoginStep.Server && address.startsWith("http://", ignoreCase = true)) {
                        Spacer(Modifier.height(4.dp)); KText("Unencrypted connection (http)", MarqueeType.Meta, color = MarqueeColors.Amber, maxLines = 1)
                    }
                    Spacer(Modifier.height(16.dp)); Chip("Change server", { step = LoginStep.Server; error = null })
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (step) {
                    LoginStep.Server -> {
                        KText("Connect to Jellyfin", MarqueeType.Headline)
                        KText("Enter your server’s address, like jellyfin.local:8096 or https://media.example.com.", MarqueeType.Body, color = MarqueeColors.TextSecondary, maxLines = 2)
                        TvField(address, { address = it }, "Server address", Modifier, onDone = { keyboard?.hide(); confirmFocus.requestFocus(); connect() })
                        TvButton(if (busy) "Connecting…" else if (error != null) "Retry" else "Connect", { connect() }, Modifier.focusRequester(initialFocus).focusRequester(confirmFocus), primary = true, busy = busy)
                    }
                    LoginStep.Users -> {
                        KText("Who’s watching?", MarqueeType.Headline)
                        if (users.isNotEmpty()) {
                            val profiles = androidx.compose.foundation.lazy.rememberLazyListState()
                            val profileScroll = remember { ScreenScroll() }
                            CompositionLocalProvider(LocalBringIntoViewSpec provides InstantBringIntoView) {
                                LazyRow(state = profiles, horizontalArrangement = Arrangement.spacedBy(MarqueeDims.CardGap), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)) {
                                    itemsIndexed(users, key = { _, u -> u.id }, contentType = { _, _ -> "profile" }) { index, u ->
                                        PersonCard(u.name, null, ImageUrls.user(u.id, u.primaryImageTag, 192), null, { choose(u) }, Modifier.onFocusChanged { if (it.hasFocus) profileScroll.row(scope, profiles, index) }, size = 96.dp)
                                    }
                                }
                            }
                        } else KText("Profiles on this server are private. Use Quick Connect or enter your username.", MarqueeType.BodyLarge, color = MarqueeColors.TextSecondary, maxLines = 3)
                        if (qcEnabled) PillButton("Quick Connect", { step = LoginStep.QuickConnect }, Modifier.focusRequester(initialFocus), primary = true)
                        PillButton("Use username and password", {
                            selectedUser = null; username = ""; password = ""; step = LoginStep.Password
                        }, if (!qcEnabled) Modifier.focusRequester(initialFocus) else Modifier)
                    }
                    LoginStep.Password -> {
                        KText(selectedUser?.name?.let { "Welcome, $it" } ?: "Sign in", MarqueeType.Headline, maxLines = 1)
                        if (selectedUser == null) TvField(username, { username = it }, "Username", Modifier, onDone = { keyboard?.hide(); confirmFocus.requestFocus() })
                        TvField(password, { password = it }, "Password", Modifier, password = true,
                            onDone = { keyboard?.hide(); confirmFocus.requestFocus(); signIn() })
                        TvButton(if (busy) "Signing in…" else "Sign in", { signIn() }, Modifier.focusRequester(initialFocus).focusRequester(confirmFocus), primary = true, busy = busy)
                        Chip("Choose another user", { password = ""; step = LoginStep.Users; error = null })
                    }
                    LoginStep.QuickConnect -> {
                        KText("Sign in with Quick Connect", MarqueeType.Headline)
                        KText(qcCode ?: "······", MarqueeType.Display.copy(fontSize = 64.sp, lineHeight = 76.sp, letterSpacing = 8.sp))
                        KText("Open Jellyfin on your phone or browser. In Quick Connect, enter this code.", MarqueeType.BodyLarge, color = MarqueeColors.TextSecondary, maxLines = 3)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (error == null) androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), color = MarqueeColors.Sky, strokeWidth = 2.dp)
                            KText(if (error == null) (if (qcCode == null) "Getting a sign-in code…" else "Waiting for approval…") else "", MarqueeType.Meta, color = MarqueeColors.Sky)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PillButton("New code", { qcGeneration++ }, Modifier.focusRequester(initialFocus))
                            PillButton("Use a password", { selectedUser = null; step = LoginStep.Password; error = null })
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(44.dp)) {
                    error?.let { KText(it, MarqueeType.Body, color = MarqueeColors.Red, maxLines = 2) }
                }
            }
        }
    }
}
