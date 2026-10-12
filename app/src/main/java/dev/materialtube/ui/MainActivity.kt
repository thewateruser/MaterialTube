package dev.materialtube.ui

import android.Manifest
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavBackStackEntry
import dev.materialtube.ui.components.pressBounce
import kotlin.math.roundToInt
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.common.util.concurrent.ListenableFuture
import dev.materialtube.Graph
import dev.materialtube.data.VideoItem
import dev.materialtube.player.PlaybackService
import dev.materialtube.player.PlayerController
import dev.materialtube.ui.screens.BrowseScreen
import dev.materialtube.ui.screens.ChannelScreen
import dev.materialtube.ui.screens.FeedList
import dev.materialtube.ui.screens.LibraryScreen
import dev.materialtube.ui.screens.SearchScreen
import dev.materialtube.ui.screens.ShortsQueue
import dev.materialtube.ui.screens.ShortsScreen
import dev.materialtube.ui.screens.SettingsScreen
import dev.materialtube.ui.screens.SignInScreen
import dev.materialtube.ui.screens.WatchScreen
import dev.materialtube.ui.screens.rememberFeedViewModel
import dev.materialtube.ui.theme.MaterialTubeTheme
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var inPip by mutableStateOf(false)
    private var pendingVideo by mutableStateOf<String?>(null)
    private var currentRoute: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setFullscreen(false)
        pendingVideo = videoIdFrom(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // Binding a controller starts the MediaSessionService, which owns the notification.
        controllerFuture = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()

        lifecycleScope.launch {
            PlayerController.messages.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() }
        }

        setContent {
            MaterialTubeTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        videoIdFrom(intent)?.let { pendingVideo = it }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Graph.prefs.autoPip && currentRoute?.startsWith("watch") == true && PlayerController.player.isPlaying) {
            runCatching {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        if (!Graph.prefs.backgroundPlay && !isInPictureInPictureMode && !isChangingConfigurations) {
            PlayerController.player.pause()
        }
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }

    /**
     * Fullscreen is an explicit switch, not a guess from the sensor: the button (or drag/back)
     * turns it on or off, and the screen is locked to landscape or portrait to match. Turning
     * the phone never changes it.
     */
    private var fullscreenOn by mutableStateOf(false)

    private fun setFullscreen(on: Boolean) {
        if (on == fullscreenOn && requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) return
        fullscreenOn = on
        requestedOrientation = if (on) {
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun setSystemBarsHidden(on: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    @Composable
    private fun AppRoot() {
        val nav = rememberNavController()
        val signedIn by Graph.signedIn.collectAsState()
        val start = remember { if (signedIn || Graph.prefs.skippedSignIn) "home" else "signin" }
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route
        currentRoute = route
        val onWatch = route?.startsWith("watch") == true
        val fullscreen = onWatch && fullscreenOn

        LaunchedEffect(fullscreen) { setSystemBarsHidden(fullscreen) }
        LaunchedEffect(onWatch) {
            // Leaving the player always leaves fullscreen.
            if (!onWatch && fullscreenOn) setFullscreen(false)
        }
        LaunchedEffect(pendingVideo) {
            pendingVideo?.let { id ->
                pendingVideo = null
                nav.navigate("watch/$id") { launchSingleTop = true }
            }
        }
        BackHandler(enabled = fullscreen) { setFullscreen(false) }

        val openVideo: (VideoItem) -> Unit = { v ->
            Hints.put(v)
            nav.navigate("watch/${v.id}") {
                // Keep a single watch page on the stack; "up next" replaces the current one.
                popUpTo("watch/{id}") { inclusive = true }
                launchSingleTop = true
            }
        }
        val openChannel: (String) -> Unit = { nav.navigate("channel/$it") }
        val openShorts: (List<VideoItem>, Int) -> Unit = { list, index ->
            ShortsQueue.items = list
            nav.navigate("shorts/$index")
        }
        val showChrome = !onWatch && !inPip && route != "signin" && route?.startsWith("shorts") != true

        val miniVisible = !onWatch && !inPip && route != "signin" && route?.startsWith("shorts") != true

        Box(Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                AnimatedVisibility(
                    visible = showChrome,
                    enter = slideInVertically(tween(260, easing = EmphasizedDecelerate)) { it } + fadeIn(),
                    exit = slideOutVertically(tween(200)) { it } + fadeOut(),
                ) { BottomBar(nav, route) }
            },
        ) { padding ->
            NavHost(
                nav,
                startDestination = start,
                modifier = Modifier.padding(padding),
                enterTransition = {
                    if (isTabSwitch()) {
                        fadeIn(tween(220, delayMillis = 70)) + scaleIn(tween(260, delayMillis = 70, easing = EmphasizedDecelerate), initialScale = 0.94f)
                    } else {
                        slideInHorizontally(tween(320, easing = EmphasizedDecelerate)) { it / 5 } + fadeIn(tween(220))
                    }
                },
                exitTransition = {
                    if (isTabSwitch()) fadeOut(tween(90)) else slideOutHorizontally(tween(320, easing = EmphasizedDecelerate)) { -it / 10 } + fadeOut(tween(200))
                },
                popEnterTransition = {
                    if (isTabSwitch()) fadeIn(tween(220, delayMillis = 70)) else slideInHorizontally(tween(320, easing = EmphasizedDecelerate)) { -it / 10 } + fadeIn(tween(220))
                },
                popExitTransition = {
                    if (isTabSwitch()) fadeOut(tween(90)) else slideOutHorizontally(tween(280, easing = EmphasizedDecelerate)) { it / 5 } + fadeOut(tween(180))
                },
            ) {
                composable("signin") {
                    SignInScreen(
                        onDone = { nav.navigate("home") { popUpTo(0) } },
                        onSkip = {
                            Graph.prefs.skippedSignIn = true
                            if (!nav.popBackStack()) nav.navigate("home") { popUpTo(0) }
                        },
                    )
                }
                composable("home") {
                    TopLevel("MaterialTube") {
                        val vm = rememberFeedViewModel("home:$signedIn", { Graph.api.homeWithShorts() }, { Graph.api.homeContinuation(it) })
                        FeedList(
                            vm, openVideo, openChannel,
                            onShorts = openShorts,
                            showChannels = false,
                            emptyText = if (signedIn) "No recommendations yet. Watch a few videos and pull to refresh." else "Sign in (Library → Sign in) to get your personalized recommendations.",
                        )
                    }
                }
                composable("subs") {
                    TopLevel("Subscriptions") {
                        if (signedIn) {
                            val vm = rememberFeedViewModel("subs", { Graph.api.subscriptions() }, { Graph.api.continuation(it) })
                            FeedList(
                                vm, openVideo, openChannel,
                                onShorts = openShorts,
                                channelsTitle = "Your channels",
                                emptyText = "No uploads from your subscriptions",
                            )
                        } else {
                            Text("Sign in to see your subscriptions", Modifier.padding(16.dp))
                        }
                    }
                }
                composable("search") { TopLevel("Search") { SearchScreen(openVideo, openChannel, openShorts) } }
                composable("library") {
                    TopLevel("Library") {
                        LibraryScreen(
                            onOpen = { id, title -> nav.navigate("browse/$id/${URLEncoder.encode(title, "UTF-8")}") },
                            onSettings = { nav.navigate("settings") },
                            onSignIn = { nav.navigate("signin") },
                        )
                    }
                }
                composable("settings") {
                    Sub("Settings", nav) { SettingsScreen(onSignIn = { nav.navigate("signin") }) }
                }
                composable("channel/{id}", listOf(navArgument("id") { type = NavType.StringType })) { e ->
                    Sub("", nav) { ChannelScreen(e.arguments!!.getString("id")!!, openVideo, openChannel, openShorts) }
                }
                composable(
                    "browse/{id}/{title}",
                    listOf(navArgument("id") { type = NavType.StringType }, navArgument("title") { type = NavType.StringType }),
                ) { e ->
                    val title = URLDecoder.decode(e.arguments!!.getString("title")!!, "UTF-8")
                    Sub(title, nav) { BrowseScreen(e.arguments!!.getString("id")!!, title, openVideo, openChannel) }
                }
                composable(
                    "shorts/{index}",
                    listOf(navArgument("index") { type = NavType.IntType }),
                    enterTransition = { fadeIn(tween(220)) + scaleIn(tween(300, easing = EmphasizedDecelerate), initialScale = 0.9f) },
                    popExitTransition = { fadeOut(tween(180)) + scaleOut(tween(220), targetScale = 0.9f) },
                ) { e ->
                    ShortsScreen(
                        startIndex = e.arguments!!.getInt("index"),
                        onChannel = openChannel,
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(
                    "watch/{id}",
                    listOf(navArgument("id") { type = NavType.StringType }),
                    enterTransition = { slideInVertically(tween(340, easing = EmphasizedDecelerate)) { it / 3 } + fadeIn(tween(220)) },
                    popExitTransition = { fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 0.9f) },
                ) { e ->
                    val id = e.arguments!!.getString("id")!!
                    WatchScreen(
                        videoId = id,
                        hint = Hints.get(id),
                        fullscreen = fullscreen,
                        inPip = inPip,
                        onFullscreen = { setFullscreen(it) },
                        onVideo = openVideo,
                        onChannel = openChannel,
                        onMinimize = { nav.popBackStack() },
                    )
                }
            }
        }
        FloatingMiniPlayer(
            visible = miniVisible,
            bottomInset = if (showChrome) 80.dp else 0.dp,
            onOpen = { id -> nav.navigate("watch/$id") { launchSingleTop = true } },
        )
        }
    }

    companion object {
        private val VIDEO_ID = Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})""")

        fun videoIdFrom(intent: Intent?): String? {
            val text = when (intent?.action) {
                Intent.ACTION_VIEW -> intent.dataString
                Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                else -> null
            } ?: return null
            return VIDEO_ID.find(text)?.groupValues?.get(1)
        }
    }
}

/** Passes the tapped item's title/thumbnail to the watch page so it renders instantly. */
private object Hints {
    private val map = LinkedHashMap<String, VideoItem>()
    fun put(v: VideoItem) {
        map[v.id] = v
        if (map.size > 50) map.remove(map.keys.first())
    }
    fun get(id: String) = map[id]
}

@Composable
private fun TopLevel(title: String?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        title?.let {
            Text(
                it,
                style = MaterialTheme.typography.headlineMediumEmphasized,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun Sub(title: String, nav: NavHostController, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLargeEmphasized) },
            navigationIcon = {
                FilledTonalIconButton(onClick = { nav.popBackStack() }, shapes = IconButtonDefaults.shapes(), modifier = Modifier.pressBounce(0.85f)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            },
        )
        Box(Modifier.weight(1f)) { content() }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("home", "Home", Icons.Filled.Home),
    Tab("subs", "Subscriptions", Icons.Filled.Subscriptions),
    Tab("search", "Search", Icons.Filled.Search),
    Tab("library", "Library", Icons.Filled.VideoLibrary),
)

@Composable
private fun BottomBar(nav: NavHostController, route: String?) {
    ShortNavigationBar {
        TABS.forEach { tab ->
            ShortNavigationBarItem(
                selected = route == tab.route,
                onClick = {
                    nav.navigate(tab.route) {
                        popUpTo("home") { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(tab.icon, tab.label) },
                label = { Text(tab.label, maxLines = 1) },
                modifier = Modifier.pressBounce(0.86f),
            )
        }
    }
}

private val TAB_ROUTES = setOf("home", "subs", "search", "library")

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch() =
    initialState.destination.route in TAB_ROUTES && targetState.destination.route in TAB_ROUTES

/** Material 3 "emphasized decelerate" easing. */
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** Remembers where you left the mini player, across screens. */
private object MiniPlayerPosition {
    var offset: Offset? = null
    var widthDp: Float = 208f
}

/**
 * Floating video window shown while browsing. Tap to reopen, drag it anywhere, pinch with two
 * fingers to resize. Position and size are remembered.
 */
@OptIn(UnstableApi::class)
@Composable
private fun FloatingMiniPlayer(visible: Boolean, bottomInset: Dp, onOpen: (String) -> Unit) {
    val np by PlayerController.nowPlaying.collectAsState()
    val current = np
    AnimatedVisibility(
        visible = visible && current != null,
        enter = fadeIn(tween(200)) + scaleIn(tween(280, easing = EmphasizedDecelerate), initialScale = 0.6f),
        exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.6f),
    ) {
        val videoId = current?.videoId ?: return@AnimatedVisibility
        var playing by remember { mutableStateOf(PlayerController.player.isPlaying) }
        DisposableEffect(Unit) {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            }
            PlayerController.player.addListener(listener)
            onDispose { PlayerController.player.removeListener(listener) }
        }
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = bottomInset + 12.dp),
        ) {
            val density = LocalDensity.current
            val boundsW = constraints.maxWidth.toFloat()
            val boundsH = constraints.maxHeight.toFloat()
            val minW = with(density) { 140.dp.toPx() }
            val maxW = boundsW.coerceAtLeast(minW)
            var widthPx by remember { mutableStateOf(with(density) { MiniPlayerPosition.widthDp.dp.toPx() }.coerceIn(minW, maxW)) }
            val heightPx = widthPx * 9f / 16f
            fun clamp(o: Offset, w: Float = widthPx): Offset {
                val maxX = (boundsW - w).coerceAtLeast(0f)
                val maxY = (boundsH - w * 9f / 16f).coerceAtLeast(0f)
                return Offset(o.x.coerceIn(0f, maxX), o.y.coerceIn(0f, maxY))
            }
            var offset by remember { mutableStateOf(clamp(MiniPlayerPosition.offset ?: Offset(boundsW, boundsH))) }
            LaunchedEffect(boundsW, boundsH) {
                widthPx = widthPx.coerceIn(minW, maxW)
                offset = clamp(offset)
            }
            val shape = MaterialTheme.shapes.large
            Box(
                Modifier
                    .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                    .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
                    .pressBounce(0.95f)
                    .shadow(12.dp, shape)
                    .clip(shape)
                    .background(Color.Black)
                    .pointerInput(boundsW, boundsH) {
                        // One finger drags, two fingers pinch to resize (growing from the centre).
                        detectTransformGestures { _, pan, zoom, _ ->
                            val oldW = widthPx
                            val newW = (oldW * zoom).coerceIn(minW, maxW)
                            val grow = Offset((newW - oldW) / 2f, (newW - oldW) * 9f / 32f)
                            widthPx = newW
                            offset = clamp(offset - grow + pan, newW)
                            MiniPlayerPosition.offset = offset
                            MiniPlayerPosition.widthDp = with(density) { newW.toDp().value }
                        }
                    }
                    .pointerInput(videoId) { detectTapGestures(onTap = { onOpen(videoId) }) },
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = PlayerController.player
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(0f to Color(0x66000000), 0.35f to Color.Transparent, 0.65f to Color.Transparent, 1f to Color(0x99000000))),
                )
                FilledTonalIconButton(
                    onClick = { if (playing) PlayerController.player.pause() else PlayerController.player.play() },
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(36.dp).pressBounce(0.82f),
                ) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause", Modifier.size(20.dp))
                }
                FilledTonalIconButton(
                    onClick = { PlayerController.close() },
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(32.dp).pressBounce(0.82f),
                ) { Icon(Icons.Filled.Close, "Close", Modifier.size(18.dp)) }
            }
        }
    }
}
