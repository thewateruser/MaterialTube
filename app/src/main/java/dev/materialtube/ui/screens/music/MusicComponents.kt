package dev.materialtube.ui.screens.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import dev.materialtube.data.MusicItem
import dev.materialtube.data.MusicPage
import dev.materialtube.data.MusicShelf
import dev.materialtube.data.MusicType
import dev.materialtube.player.PlayerController
import dev.materialtube.ui.components.SectionTitle
import dev.materialtube.ui.components.bounceClick
import dev.materialtube.ui.components.pressBounce
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Loads a YouTube Music page (and its continuations). */
class MusicPageViewModel(
    private val first: () -> MusicPage,
    private val next: ((String) -> MusicPage)?,
) : ViewModel() {
    var page by mutableStateOf<MusicPage?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    private var continuation: String? = null
    private var loadingMore = false

    init { load() }

    fun load() {
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val p = withContext(Dispatchers.IO) { first() }
                page = p
                continuation = p.continuation
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Couldn't load"
            } finally {
                loading = false
            }
        }
    }

    val canLoadMore get() = continuation != null && next != null

    fun loadMore() {
        val token = continuation ?: return
        val more = next ?: return
        if (loadingMore) return
        loadingMore = true
        viewModelScope.launch {
            try {
                val p = withContext(Dispatchers.IO) { more(token) }
                val current = page ?: return@launch
                page = current.copy(shelves = mergeShelves(current.shelves, p.shelves))
                continuation = p.continuation.takeIf { it != token && p.shelves.isNotEmpty() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continuation = null
            } finally {
                loadingMore = false
            }
        }
    }

    /** A continuation of a single list (long playlist) extends the last shelf; feeds add shelves. */
    private fun mergeShelves(old: List<MusicShelf>, new: List<MusicShelf>): List<MusicShelf> {
        if (new.size == 1 && new[0].title == null && old.isNotEmpty()) {
            val last = old.last()
            val seen = last.items.map { it.id }.toHashSet()
            return old.dropLast(1) + last.copy(items = last.items + new[0].items.filter { seen.add(it.id) })
        }
        return old + new
    }
}

/** How a list of shelves is laid out. */
enum class MusicLayout {
    /** Home/explore: songs in "quick picks" columns, everything else in card rows. */
    Feed,
    /** Album/playlist/artist pages: songs as a list, releases/artists in card rows. */
    Page,
    /** Search/library: everything as a list. */
    List,
}

/** What happens when an item is tapped: songs play (with the shelf as the queue), pages open. */
class MusicActions(
    val openPage: (browseId: String, params: String?) -> Unit,
    val openPlayer: () -> Unit,
) {
    fun onItem(list: List<MusicItem>, index: Int, playlistId: String? = null) {
        val item = list[index]
        if (item.type.playable) {
            PlayerController.playMusic(list, index, playlistId)
        } else {
            openPage(item.id, null)
        }
    }
}

fun LazyListScope.musicShelves(shelves: List<MusicShelf>, layout: MusicLayout, actions: MusicActions, playlistId: String? = null) {
    shelves.forEachIndexed { i, shelf ->
        val songs = shelf.items.count { it.type.playable } >= shelf.items.size / 2 + 1
        val header: @Composable () -> Unit = {
            if (shelf.title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(shelf.title, Modifier.weight(1f))
                    if (shelf.moreBrowseId != null) {
                        TextButton(
                            onClick = { actions.openPage(shelf.moreBrowseId, shelf.moreParams) },
                            modifier = Modifier.padding(end = 8.dp, top = 12.dp).pressBounce(),
                        ) {
                            Text("More")
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.padding(start = 4.dp).size(16.dp))
                        }
                    }
                }
            }
        }
        when {
            layout == MusicLayout.Feed && songs -> item(key = "shelf$i") {
                Column {
                    header()
                    SongColumns(shelf.items) { index -> actions.onItem(shelf.items, index, playlistId) }
                }
            }
            layout == MusicLayout.List || (songs && layout == MusicLayout.Page) -> {
                item(key = "head$i") { header() }
                itemsIndexed(shelf.items, key = { j, it -> "$i-$j-${it.id}" }) { j, item ->
                    MusicRow(item) { actions.onItem(shelf.items, j, playlistId) }
                }
            }
            else -> item(key = "shelf$i") {
                Column {
                    header()
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(shelf.items, key = { j, it -> "$j-${it.id}" }) { j, item ->
                            MusicCard(item) { actions.onItem(shelf.items, j, playlistId) }
                        }
                    }
                }
            }
        }
    }
}

private fun artShape(item: MusicItem, small: Boolean): Shape =
    if (item.type == MusicType.Artist) CircleShape else if (small) androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    else androidx.compose.foundation.shape.RoundedCornerShape(16.dp)

@Composable
fun MusicArt(item: MusicItem, size: Dp, small: Boolean = true) {
    AsyncImage(
        model = item.thumbnail,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(size).clip(artShape(item, small)).background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/** One song/album/artist as a list row. */
@Composable
fun MusicRow(item: MusicItem, onClick: () -> Unit) {
    val np by PlayerController.nowPlaying.collectAsState()
    val playing = np?.music == true && np?.videoId == item.id
    Row(
        Modifier
            .fillMaxWidth()
            .bounceClick(pressedScale = 0.98f, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            MusicArt(item, 52.dp)
            if (playing) {
                Box(Modifier.size(52.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)))
                Icon(Icons.Filled.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(typeLabel(item), item.subtitle).joinToString(" • ")
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        item.duration?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** A label for list rows when the subtitle doesn't already say what it is. */
private fun typeLabel(item: MusicItem): String? {
    val sub = item.subtitle.orEmpty()
    val label = when (item.type) {
        MusicType.Artist -> "Artist"
        MusicType.Playlist -> "Playlist"
        MusicType.Video -> "Video"
        else -> return null
    }
    return label.takeUnless { sub.startsWith(it) }
}

/** An album/playlist/artist as a square (or round) card. */
@Composable
fun MusicCard(item: MusicItem, onClick: () -> Unit) {
    Column(
        Modifier
            .width(150.dp)
            .clip(MaterialTheme.shapes.large)
            .bounceClick(pressedScale = 0.95f, onClick = onClick)
            .padding(bottom = 4.dp),
        horizontalAlignment = if (item.type == MusicType.Artist) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        AsyncImage(
            model = item.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(artShape(item, small = false)).background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.height(8.dp))
        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        item.subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "Quick picks": songs in swipeable columns of four. */
@Composable
fun SongColumns(items: List<MusicItem>, onClick: (Int) -> Unit) {
    val columns = items.withIndex().chunked(4)
    LazyRow(contentPadding = PaddingValues(end = 24.dp)) {
        items(columns) { column ->
            Column(Modifier.width(320.dp)) {
                column.forEach { (index, item) -> MusicRow(item) { onClick(index) } }
            }
        }
    }
}
