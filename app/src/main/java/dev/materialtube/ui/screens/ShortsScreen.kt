@file:OptIn(UnstableApi::class)

package dev.materialtube.ui.screens

import androidx.compose.foundation.shape.CircleShape
import dev.materialtube.ui.components.pressBounce
import dev.materialtube.ui.components.bounceClick
import android.widget.Toast
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.key
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.materialtube.data.innertube.Parser
import dev.materialtube.player.ShortsPlayers
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import dev.materialtube.Graph
import dev.materialtube.data.LikeStatus
import dev.materialtube.data.VideoItem
import dev.materialtube.player.PlayerController

/** The list the Shorts player swipes through (set right before navigating to it). */
object ShortsQueue {
    var items: List<VideoItem> = emptyList()
}

/** Full-screen vertical Shorts player: swipe up/down to move between Shorts. */
@Composable
fun ShortsScreen(startIndex: Int, onChannel: (String) -> Unit, onBack: () -> Unit) {
    val items = remember { ShortsQueue.items }
    if (items.isEmpty()) {
        onBack()
        return
    }
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, items.lastIndex)) { items.size }

    val context = LocalContext.current
    val pool = remember { ShortsPlayers(context) }
    DisposableEffect(Unit) {
        // Shorts have their own players; stop whatever video was playing.
        PlayerController.player.pause()
        onDispose { pool.releaseAll() }
    }
    // Pause when the app goes to the background, resume when it comes back.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> pool.pauseAll()
                Lifecycle.Event.ON_RESUME -> pool.resume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(pager.settledPage) {
        val page = pager.settledPage
        // Keep players for the previous and next Short, prepared and paused on their first
        // frame, so swiping is instant; resolve the two after that ahead of time. (More players
        // would need more hardware video decoders than some phones have.)
        val keep = (page - 1..page + 1).mapNotNull { items.getOrNull(it)?.id }
        pool.keepOnly(keep)
        items[page].id.let { pool.ensure(it); pool.activate(it) }
        keep.forEach(pool::ensure)
        listOf(page + 2, page + 3).forEach { i -> items.getOrNull(i)?.let { PlayerController.prefetch(it.id) } }
    }
    // Fast flings: start on the page being swiped to before the swipe settles.
    LaunchedEffect(pager.targetPage) { items.getOrNull(pager.targetPage)?.let { pool.ensure(it.id) } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(
            state = pager,
            key = { items[it].id },
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            ShortPage(items[page], pool, onChannel = onChannel)
        }
        FilledTonalIconButton(
            onClick = onBack,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.statusBarsPadding().padding(8.dp).pressBounce(),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    }
}

@Composable
private fun ShortPage(item: VideoItem, pool: ShortsPlayers, onChannel: (String) -> Unit) {
    val context = LocalContext.current
    val signedIn by Graph.signedIn.collectAsState()
    var showComments by remember { mutableStateOf(false) }
    // Like state, channel info and comments come from the same model as the watch page.
    val vm: WatchViewModel = viewModel(key = "watch-${item.id}") { WatchViewModel(item.id, loadCommentsNow = false) }
    val toast: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }

    val player = pool.players[item.id]
    Box(Modifier.fillMaxSize()) {
        if (player != null) {
            key(player) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            keepScreenOn = true
                            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        }
                    },
                    onRelease = { it.player = null },
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { pool.togglePlay(item.id) },
                )
            }
        }
        // Thumbnail until the first frame is on screen, so there's never a black page.
        if (pool.firstFrame[item.id] != true) {
            AsyncImage(
                model = item.thumbnail ?: Parser.thumbFor(item.id),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clickable { pool.togglePlay(item.id) },
            )
        }
        pool.errors[item.id]?.let { message ->
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(message, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = { pool.retry(item.id) }, modifier = Modifier.pressBounce()) { Text("Retry") }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0xB3000000))),
        )
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                val next = vm.next
                val channelId = next?.channelId ?: item.channelId
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .bounceClick(enabled = channelId != null) { channelId?.let(onChannel) },
                ) {
                    AsyncImage(
                        model = next?.channelAvatar,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        next?.channelName ?: item.channel ?: "",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    item.title,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (signedIn) {
                    val liked = vm.like == LikeStatus.LIKE
                    FilledTonalIconToggleButton(
                        checked = liked,
                        onCheckedChange = { vm.toggleLike(LikeStatus.LIKE, toast) },
                        shapes = IconButtonDefaults.toggleableShapes(),
                        modifier = Modifier.pressBounce(0.85f),
                    ) { Icon(if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, "Like") }
                }
                FilledTonalIconButton(onClick = { showComments = true }, shapes = IconButtonDefaults.shapes(), modifier = Modifier.pressBounce(0.85f)) {
                    Icon(Icons.AutoMirrored.Outlined.Comment, "Comments")
                }
                FilledTonalIconButton(onClick = { share(context, item.id) }, shapes = IconButtonDefaults.shapes(), modifier = Modifier.pressBounce(0.85f)) {
                    Icon(Icons.Filled.Share, "Share")
                }
            }
        }
        // Over the lower part of the Short, which keeps playing above it.
        // Never over the top part: the panel stays at 60% so the Short is still visible.
        if (showComments) CommentsSheet(vm, onDismiss = { showComments = false }, maxFraction = 0.6f)
    }
}
