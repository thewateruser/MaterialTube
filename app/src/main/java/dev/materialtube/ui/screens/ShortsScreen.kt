@file:OptIn(UnstableApi::class)

package dev.materialtube.ui.screens

import android.widget.Toast
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
import dev.materialtube.ui.components.AvatarShape

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

    DisposableEffect(Unit) {
        PlayerController.player.repeatMode = Player.REPEAT_MODE_ONE
        onDispose { PlayerController.player.repeatMode = Player.REPEAT_MODE_OFF }
    }
    LaunchedEffect(pager.settledPage) {
        val item = items[pager.settledPage]
        PlayerController.play(item.id, item)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(state = pager, key = { items[it].id }, modifier = Modifier.fillMaxSize()) { page ->
            ShortPage(items[page], active = page == pager.settledPage, onChannel = onChannel)
        }
        FilledTonalIconButton(
            onClick = onBack,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.statusBarsPadding().padding(8.dp),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    }
}

@Composable
private fun ShortPage(item: VideoItem, active: Boolean, onChannel: (String) -> Unit) {
    val context = LocalContext.current
    val signedIn by Graph.signedIn.collectAsState()
    var showComments by remember { mutableStateOf(false) }
    // Like state, channel info and comments come from the same model as the watch page.
    val vm: WatchViewModel = viewModel(key = "watch-${item.id}") { WatchViewModel(item.id) }
    val toast: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }

    Box(Modifier.fillMaxSize()) {
        if (active) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = PlayerController.player
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        keepScreenOn = true
                    }
                },
                onRelease = { it.player = null },
                modifier = Modifier
                    .fillMaxSize()
                    .clickable {
                        val p = PlayerController.player
                        if (p.isPlaying) p.pause() else p.play()
                    },
            )
        } else {
            AsyncImage(
                model = item.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
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
                        .clickable(enabled = channelId != null) { channelId?.let(onChannel) },
                ) {
                    AsyncImage(
                        model = next?.channelAvatar,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp).clip(AvatarShape).background(MaterialTheme.colorScheme.primaryContainer),
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
                    ) { Icon(if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, "Like") }
                }
                FilledTonalIconButton(onClick = { showComments = true }, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.AutoMirrored.Outlined.Comment, "Comments")
                }
                FilledTonalIconButton(onClick = { share(context, item.id) }, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Filled.Share, "Share")
                }
            }
        }
    }
    if (showComments) CommentsSheet(vm, onDismiss = { showComments = false })
}
