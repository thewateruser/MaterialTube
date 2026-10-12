package dev.materialtube.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import dev.materialtube.Graph
import dev.materialtube.data.ChannelAbout
import dev.materialtube.data.Post
import dev.materialtube.data.VideoItem
import dev.materialtube.ui.components.CenteredLoading
import dev.materialtube.ui.components.CompactVideoRow
import dev.materialtube.ui.components.ErrorState
import dev.materialtube.ui.components.bounceClick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PostsViewModel(private val channelId: String) : ViewModel() {
    var posts by mutableStateOf<List<Post>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var continuation: String? = null
    private var loadingMore = false

    init { load() }

    fun load() {
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { Graph.api.channelPosts(channelId) }
                posts = page.posts
                continuation = page.continuation
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Couldn't load posts"
            } finally {
                loading = false
            }
        }
    }

    fun loadMore() {
        val token = continuation ?: return
        if (loadingMore) return
        loadingMore = true
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { Graph.api.channelPostsContinuation(token) }
                val seen = posts.map { it.id }.toHashSet()
                val fresh = page.posts.filter { seen.add(it.id) }
                posts = posts + fresh
                continuation = page.continuation.takeIf { it != token && fresh.isNotEmpty() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Scrolling again retries.
            } finally {
                loadingMore = false
            }
        }
    }

    val canLoadMore get() = continuation != null
}

class AboutViewModel(private val channelId: String) : ViewModel() {
    var about by mutableStateOf<ChannelAbout?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init { load() }

    fun load() {
        error = null
        viewModelScope.launch {
            try {
                about = withContext(Dispatchers.IO) { Graph.api.channelAbout(channelId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Couldn't load channel info"
            }
        }
    }
}

@Composable
fun PostsList(vm: PostsViewModel, header: @Composable () -> Unit, onVideo: (VideoItem) -> Unit) {
    val state = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(state) { snapshotFlow { nearEnd }.collect { if (it) vm.loadMore() } }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
        item { header() }
        when {
            vm.loading && vm.posts.isEmpty() -> item { CenteredLoading(Modifier.height(280.dp)) }
            vm.error != null && vm.posts.isEmpty() -> item { ErrorState(vm.error!!, { vm.load() }, Modifier.height(280.dp)) }
            vm.posts.isEmpty() -> item {
                Text(
                    "No posts here",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            else -> {
                items(vm.posts, key = { it.id }) { PostCard(it, onVideo) }
                if (vm.canLoadMore) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                    }
                }
            }
        }
    }
}

@Composable
private fun PostCard(post: Post, onVideo: (VideoItem) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(vertical = 14.dp)) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = post.authorAvatar,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                )
                Spacer(Modifier.width(10.dp))
                Text(post.author.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                post.published?.let {
                    Text(
                        "  •  $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (post.text.isNotBlank()) {
                Text(
                    post.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = 14.dp)
                        .padding(top = 10.dp)
                        .bounceClick(pressedScale = 0.99f) { expanded = !expanded },
                )
            }
            when {
                post.images.size == 1 -> AsyncImage(
                    model = post.images[0],
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .padding(horizontal = 14.dp)
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .clip(MaterialTheme.shapes.medium),
                )
                post.images.size > 1 -> LazyRow(
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    items(post.images) { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(260.dp).clip(MaterialTheme.shapes.medium),
                        )
                    }
                }
            }
            post.video?.let { v ->
                Box(Modifier.padding(top = 8.dp)) { CompactVideoRow(v) { onVideo(v) } }
            }
            if (post.poll.isNotEmpty()) {
                Column(
                    Modifier.padding(horizontal = 14.dp).padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    post.poll.forEach { choice ->
                        OutlinedCard(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                            Text(choice, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                        }
                    }
                }
            }
            if (post.likes != null || post.comments != null) {
                Row(
                    Modifier.padding(horizontal = 14.dp).padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    post.likes?.let {
                        Icon(Icons.Outlined.ThumbUp, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(" $it", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(20.dp))
                    }
                    post.comments?.let {
                        Icon(Icons.AutoMirrored.Outlined.Comment, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(" $it", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun AboutList(vm: AboutViewModel, header: @Composable () -> Unit) {
    val uri = LocalUriHandler.current
    LazyColumn(Modifier.fillMaxSize()) {
        item { header() }
        val about = vm.about
        when {
            about == null && vm.error != null -> item { ErrorState(vm.error!!, { vm.load() }, Modifier.height(280.dp)) }
            about == null -> item { CenteredLoading(Modifier.height(280.dp)) }
            else -> {
                about.description?.let { d ->
                    item {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Description", style = MaterialTheme.typography.titleMediumEmphasized)
                                Spacer(Modifier.height(8.dp))
                                SelectionContainer { Text(d, style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                    }
                }
                if (about.stats.isNotEmpty()) {
                    item {
                        Text(
                            "Channel details",
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(about.stats) { stat ->
                        ListItem(
                            headlineContent = { Text(stat) },
                            leadingContent = { Icon(Icons.Outlined.Info, null) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        )
                    }
                }
                if (about.links.isNotEmpty()) {
                    item {
                        Text(
                            "Links",
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(about.links) { link ->
                        ListItem(
                            headlineContent = { Text(link.title) },
                            supportingContent = { Text(link.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(Icons.Outlined.Link, null) },
                            modifier = Modifier.bounceClick(pressedScale = 0.97f) { runCatching { uri.openUri(link.url) } },
                        )
                    }
                }
                if (about.description == null && about.stats.isEmpty() && about.links.isEmpty()) {
                    item {
                        Text("No channel info", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
