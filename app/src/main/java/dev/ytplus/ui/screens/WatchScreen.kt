@file:OptIn(UnstableApi::class)

package dev.ytplus.ui.screens

import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import dev.ytplus.Graph
import dev.ytplus.data.LikeStatus
import dev.ytplus.data.NextInfo
import dev.ytplus.data.VideoItem
import dev.ytplus.player.PlayerController
import dev.ytplus.ui.components.CompactVideoRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WatchViewModel(private val videoId: String) : ViewModel() {
    var next by mutableStateOf<NextInfo?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var like by mutableStateOf<LikeStatus?>(null)
        private set
    var subscribed by mutableStateOf<Boolean?>(null)
        private set

    init {
        viewModelScope.launch {
            try {
                val n = withContext(Dispatchers.IO) { Graph.api.next(videoId) }
                next = n
                like = n.likeStatus
                subscribed = n.subscribed
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                loading = false
            }
        }
    }

    fun toggleLike(target: LikeStatus, onError: (String) -> Unit) {
        val previous = like
        val newStatus = if (previous == target) LikeStatus.INDIFFERENT else target
        like = newStatus
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    when (newStatus) {
                        LikeStatus.LIKE -> Graph.api.like(videoId)
                        LikeStatus.DISLIKE -> Graph.api.dislike(videoId)
                        LikeStatus.INDIFFERENT -> Graph.api.removeLike(videoId)
                    }
                }
            }.onFailure { like = previous; onError(it.message ?: "Failed") }
        }
    }

    fun toggleSubscribe(channelId: String, onError: (String) -> Unit) {
        val previous = subscribed
        val newValue = previous != true
        subscribed = newValue
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (newValue) Graph.api.subscribe(channelId) else Graph.api.unsubscribe(channelId)
                }
            }.onFailure { subscribed = previous; onError(it.message ?: "Failed") }
        }
    }
}

/** The video surface. Shared by the watch page, fullscreen and picture-in-picture. */
@Composable
fun VideoPlayer(
    modifier: Modifier,
    fullscreen: Boolean,
    showControls: Boolean,
    onToggleFullscreen: () -> Unit,
) {
    val error by PlayerController.error.collectAsState()
    val loading by PlayerController.loading.collectAsState()
    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = PlayerController.player
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    keepScreenOn = true
                    setFullscreenButtonClickListener { onToggleFullscreen() }
                }
            },
            update = {
                it.useController = showControls
                it.setFullscreenButtonState(fullscreen)
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        error?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Playback failed: $msg", color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { PlayerController.retry() }) { Text("Retry") }
            }
        }
    }
}

@Composable
fun WatchScreen(
    videoId: String,
    hint: VideoItem?,
    fullscreen: Boolean,
    inPip: Boolean,
    onToggleFullscreen: () -> Unit,
    onVideo: (VideoItem) -> Unit,
    onChannel: (String) -> Unit,
) {
    LaunchedEffect(videoId) { PlayerController.play(videoId, hint) }
    val nowPlaying by PlayerController.nowPlaying.collectAsState()
    val signedIn by Graph.signedIn.collectAsState()
    val vm: WatchViewModel = viewModel(key = "watch-$videoId") { WatchViewModel(videoId) }
    val context = LocalContext.current
    val toast: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }

    if (fullscreen || inPip) {
        VideoPlayer(Modifier.fillMaxSize(), fullscreen = true, showControls = !inPip, onToggleFullscreen = onToggleFullscreen)
        return
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        VideoPlayer(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            fullscreen = false,
            showControls = true,
            onToggleFullscreen = onToggleFullscreen,
        )
        val np = nowPlaying
        val next = vm.next
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                var expanded by remember { mutableStateOf(false) }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .animateContentSize()
                        .padding(12.dp),
                ) {
                    Text(
                        np?.title?.ifEmpty { null } ?: hint?.title ?: "",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    np?.details?.viewCount?.let {
                        Text(
                            "%,d views".format(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val description = np?.details?.description
                    if (expanded && !description.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(description, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                val channelId = next?.channelId ?: np?.channelId
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = channelId != null) { channelId?.let(onChannel) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = next?.channelAvatar,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            next?.channelName ?: np?.channel ?: "",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (signedIn && channelId != null) {
                        val subscribed = vm.subscribed == true
                        Button(
                            onClick = { vm.toggleSubscribe(channelId, toast) },
                            colors = if (subscribed) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors(),
                        ) { Text(if (subscribed) "Subscribed" else "Subscribe") }
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (signedIn) {
                        FilledTonalButton(onClick = { vm.toggleLike(LikeStatus.LIKE, toast) }) {
                            Icon(if (vm.like == LikeStatus.LIKE) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, "Like")
                            next?.likeCountText?.let { Text("  $it") }
                        }
                        FilledTonalButton(onClick = { vm.toggleLike(LikeStatus.DISLIKE, toast) }) {
                            Icon(if (vm.like == LikeStatus.DISLIKE) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown, "Dislike")
                        }
                    }
                    QualityChip(np?.height, np?.availableHeights.orEmpty())
                    AssistChip(
                        onClick = { share(context, videoId) },
                        label = { Text("Share") },
                        leadingIcon = { Icon(Icons.Filled.Share, null) },
                    )
                }
            }
            if (vm.loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
            items(next?.related.orEmpty(), key = { it.id }) { v ->
                CompactVideoRow(v) { onVideo(v) }
            }
        }
    }
}

@Composable
private fun QualityChip(current: Int?, heights: List<Int>) {
    if (heights.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(current?.let { "${it}p" } ?: "Auto") },
            leadingIcon = { Icon(Icons.Filled.HighQuality, null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            heights.forEach { h ->
                DropdownMenuItem(
                    text = { Text("${h}p" + if (h == current) "  ✓" else "") },
                    onClick = {
                        open = false
                        PlayerController.setQuality(h)
                    },
                )
            }
        }
    }
}

private fun share(context: android.content.Context, videoId: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_TEXT, "https://youtu.be/$videoId")
    context.startActivity(android.content.Intent.createChooser(intent, "Share"))
}
