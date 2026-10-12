package dev.materialtube.ui.components

import androidx.compose.foundation.background
import dev.materialtube.data.ChannelItem
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Shape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.materialtube.data.VideoItem

@Composable
fun Thumbnail(item: VideoItem, modifier: Modifier = Modifier, rounded: Boolean = true) {
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(if (rounded) 20.dp else 0.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        AsyncImage(
            model = item.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        val badge = if (item.isLive) "LIVE" else item.duration
        if (badge != null) {
            Text(
                badge,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(if (item.isLive) Color(0xFFCC0000) else Color(0xCC000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        item.watchedPercent?.takeIf { it > 0 }?.let { pct ->
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(pct / 100f)
                    .height(3.dp)
                    .background(Color(0xFFFF0000)),
            )
        }
    }
}

/** Full-width card like the YouTube home feed. */
@Composable
fun VideoCard(item: VideoItem, onClick: () -> Unit, onChannel: ((String) -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .bounceClick(pressedScale = 0.97f, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Thumbnail(item, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(item.title, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val sub = listOfNotNull(item.channel, item.meta).joinToString(" • ")
        if (sub.isNotEmpty()) {
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = if (onChannel != null && item.channelId != null) {
                    Modifier.bounceClick { onChannel(item.channelId) }
                } else {
                    Modifier
                },
            )
        }
    }
}

/** Thumbnail-left row, used for "up next". */
@Composable
fun CompactVideoRow(item: VideoItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .bounceClick(pressedScale = 0.97f, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Thumbnail(item, Modifier.width(168.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            item.channel?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            item.meta?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
fun CenteredLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
}

/** Expressive "cookie" shape for avatars. */
val AvatarShape: Shape @Composable get() = MaterialShapes.Cookie9Sided.toShape()

@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry, shapes = ButtonDefaults.shapes(), modifier = Modifier.pressBounce()) { Text("Retry") }
    }
}

@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleLargeEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** Horizontal row of channel avatars (subscriptions, search results). */
@Composable
fun ChannelRow(channels: List<ChannelItem>, title: String?, onChannel: (String) -> Unit) {
    Column {
        title?.let { SectionTitle(it) }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(channels, key = { it.id }) { c ->
                Column(
                    Modifier
                        .width(84.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .bounceClick(pressedScale = 0.9f) { onChannel(c.id) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AsyncImage(
                        model = c.avatar,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(AvatarShape).background(MaterialTheme.colorScheme.primaryContainer),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        c.title,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    c.subtitle?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Horizontal row of vertical Shorts cards. */
@Composable
fun ShortsRow(title: String, items: List<VideoItem>, onClick: (Int) -> Unit) {
    Column {
        SectionTitle(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(items, key = { _, v -> v.id }) { index, v ->
                Box(
                    Modifier
                        .width(150.dp)
                        .aspectRatio(9f / 16f)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .bounceClick(pressedScale = 0.95f) { onClick(index) },
                ) {
                    AsyncImage(
                        model = v.thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xCC000000))),
                    )
                    Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                        Text(
                            v.title,
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        v.meta?.let {
                            Text(it, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
