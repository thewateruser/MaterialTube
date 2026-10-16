package dev.materialtube.ui.screens.music

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.materialtube.player.PlayerController
import dev.materialtube.ui.components.BottomPanel
import dev.materialtube.ui.components.pressBounce
import kotlinx.coroutines.delay

/** Full-screen music player: artwork, seek bar, controls and the up-next queue. */
@Composable
fun NowPlayingScreen(onClose: () -> Unit, onArtist: (String) -> Unit, onWatchVideo: (String) -> Unit) {
    val np by PlayerController.nowPlaying.collectAsState()
    val queue by PlayerController.queue.collectAsState()
    val index by PlayerController.queueIndex.collectAsState()
    val loading by PlayerController.loading.collectAsState()
    val current = np
    if (current == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val item = queue.getOrNull(index)
    val player = PlayerController.player

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(player.isPlaying) }
    var seeking by remember { mutableStateOf(false) }
    var seekValue by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            if (!seeking) position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            playing = player.playWhenReady
            delay(300)
        }
    }
    var showQueue by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.pressBounce(0.85f)) { Icon(Icons.Filled.KeyboardArrowDown, "Close") }
                Text("Now playing", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                IconButton(onClick = { onWatchVideo(current.videoId) }, modifier = Modifier.pressBounce(0.85f)) {
                    Icon(Icons.Filled.OndemandVideo, "Watch the video")
                }
            }
            Spacer(Modifier.weight(0.5f))
            AsyncImage(
                model = item?.thumbnail ?: current.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .shadow(16.dp, RoundedCornerShape(28.dp))
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.weight(0.5f))
            Text(
                item?.title ?: current.title,
                style = MaterialTheme.typography.headlineSmallEmphasized,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            val artists = item?.artists.orEmpty()
            val artistId = artists.firstOrNull { it.id != null }?.id ?: current.channelId
            TextButton(
                onClick = { artistId?.let(onArtist) },
                enabled = artistId != null,
                modifier = Modifier.pressBounce(),
            ) {
                Text(
                    artists.joinToString(", ") { it.name }.ifEmpty { current.channel.orEmpty() },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Slider(
                value = if (seeking) seekValue else if (duration > 0) position.toFloat() / duration else 0f,
                onValueChange = {
                    seeking = true
                    seekValue = it
                },
                onValueChangeFinished = {
                    player.seekTo((seekValue * duration).toLong())
                    position = (seekValue * duration).toLong()
                    seeking = false
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(time(if (seeking) (seekValue * duration).toLong() else position), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text(time(duration), style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                FilledTonalIconButton(
                    onClick = { PlayerController.previous() },
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(64.dp).pressBounce(0.85f),
                ) { Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(32.dp)) }
                FilledIconButton(
                    onClick = { if (player.playWhenReady) player.pause() else player.play() },
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(88.dp).pressBounce(0.85f),
                ) {
                    if (loading) {
                        LoadingIndicator(Modifier.size(48.dp))
                    } else {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause", Modifier.size(44.dp))
                    }
                }
                FilledTonalIconButton(
                    onClick = { PlayerController.next() },
                    enabled = index < queue.size - 1,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(64.dp).pressBounce(0.85f),
                ) { Icon(Icons.Filled.SkipNext, "Next", Modifier.size(32.dp)) }
            }
            Spacer(Modifier.weight(0.4f))
            TextButton(onClick = { showQueue = true }, modifier = Modifier.pressBounce()) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, null)
                Spacer(Modifier.size(8.dp))
                Text("Up next")
            }
            Spacer(Modifier.height(8.dp))
        }
        if (showQueue) {
            BottomPanel(onDismiss = { showQueue = false }, maxFraction = 0.7f) {
                Text(
                    "Up next",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = index.coerceAtLeast(0))
                LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
                    itemsIndexed(queue, key = { i, it -> "$i-${it.id}" }) { i, song ->
                        MusicRow(song) { PlayerController.playAt(i) }
                    }
                    item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
                }
            }
        }
    }
}

private fun time(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
