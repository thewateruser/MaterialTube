package dev.ytplus.ui

import android.Manifest
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import dev.ytplus.Graph
import dev.ytplus.data.VideoItem
import dev.ytplus.player.PlaybackService
import dev.ytplus.player.PlayerController
import dev.ytplus.ui.components.Thumbnail
import dev.ytplus.ui.screens.BrowseScreen
import dev.ytplus.ui.screens.ChannelScreen
import dev.ytplus.ui.screens.FeedList
import dev.ytplus.ui.screens.LibraryScreen
import dev.ytplus.ui.screens.SearchScreen
import dev.ytplus.ui.screens.SettingsScreen
import dev.ytplus.ui.screens.SignInScreen
import dev.ytplus.ui.screens.WatchScreen
import dev.ytplus.ui.screens.rememberFeedViewModel
import dev.ytplus.ui.theme.YtPlusTheme
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
            YtPlusTheme {
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
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val fullscreen = onWatch && landscape

        LaunchedEffect(fullscreen) { setSystemBarsHidden(fullscreen) }
        LaunchedEffect(onWatch) {
            if (!onWatch) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        LaunchedEffect(pendingVideo) {
            pendingVideo?.let { id ->
                pendingVideo = null
                nav.navigate("watch/$id") { launchSingleTop = true }
            }
        }
        BackHandler(enabled = fullscreen) { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT }

        val openVideo: (VideoItem) -> Unit = { v ->
            Hints.put(v)
            nav.navigate("watch/${v.id}") {
                // Keep a single watch page on the stack; "up next" replaces the current one.
                popUpTo("watch/{id}") { inclusive = true }
                launchSingleTop = true
            }
        }
        val openChannel: (String) -> Unit = { nav.navigate("channel/$it") }
        val toggleFullscreen = {
            requestedOrientation = if (landscape) {
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
        val showChrome = !onWatch && !inPip && route != "signin"

        Scaffold(
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                if (showChrome) {
                    Column {
                        MiniPlayer(onOpen = { id -> nav.navigate("watch/$id") { launchSingleTop = true } })
                        BottomBar(nav, route)
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = start, modifier = Modifier.padding(padding)) {
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
                    TopLevel("YT Plus") {
                        val vm = rememberFeedViewModel("home:$signedIn", { Graph.api.home() }, { Graph.api.continuation(it) })
                        FeedList(
                            vm, openVideo, openChannel,
                            emptyText = if (signedIn) "No recommendations yet. Watch a few videos and pull to refresh." else "Sign in (Library → Sign in) to get your personalized recommendations.",
                        )
                    }
                }
                composable("subs") {
                    TopLevel("Subscriptions") {
                        if (signedIn) {
                            val vm = rememberFeedViewModel("subs", { Graph.api.subscriptions() }, { Graph.api.continuation(it) })
                            FeedList(vm, openVideo, openChannel, emptyText = "No uploads from your subscriptions")
                        } else {
                            Text("Sign in to see your subscriptions", Modifier.padding(16.dp))
                        }
                    }
                }
                composable("search") { TopLevel(null) { SearchScreen(openVideo, openChannel) } }
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
                    Sub("Channel", nav) { ChannelScreen(e.arguments!!.getString("id")!!, openVideo, openChannel) }
                }
                composable(
                    "browse/{id}/{title}",
                    listOf(navArgument("id") { type = NavType.StringType }, navArgument("title") { type = NavType.StringType }),
                ) { e ->
                    val title = URLDecoder.decode(e.arguments!!.getString("title")!!, "UTF-8")
                    Sub(title, nav) { BrowseScreen(e.arguments!!.getString("id")!!, title, openVideo, openChannel) }
                }
                composable("watch/{id}", listOf(navArgument("id") { type = NavType.StringType })) { e ->
                    val id = e.arguments!!.getString("id")!!
                    WatchScreen(
                        videoId = id,
                        hint = Hints.get(id),
                        fullscreen = fullscreen,
                        inPip = inPip,
                        onToggleFullscreen = toggleFullscreen,
                        onVideo = openVideo,
                        onChannel = openChannel,
                    )
                }
            }
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
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun Sub(title: String, nav: NavHostController, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
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
    NavigationBar {
        TABS.forEach { tab ->
            NavigationBarItem(
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
            )
        }
    }
}

@Composable
private fun MiniPlayer(onOpen: (String) -> Unit) {
    val np by PlayerController.nowPlaying.collectAsState()
    val current = np ?: return
    var playing by remember { mutableStateOf(PlayerController.player.isPlaying) }
    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        PlayerController.player.addListener(listener)
        onDispose { PlayerController.player.removeListener(listener) }
    }
    HorizontalDivider()
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { onOpen(current.videoId) }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(
            VideoItem(current.videoId, current.title, thumbnail = current.thumbnail),
            Modifier.width(88.dp),
            rounded = true,
        )
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            current.channel?.let {
                Text(it, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = { if (playing) PlayerController.player.pause() else PlayerController.player.play() }) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause")
        }
        IconButton(onClick = { PlayerController.close() }) { Icon(Icons.Filled.Close, "Close") }
    }
}
