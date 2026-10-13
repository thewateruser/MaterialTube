package dev.materialtube.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.materialtube.data.PostComment
import dev.materialtube.streams.Comment
import dev.materialtube.ui.components.pressBounce
import dev.materialtube.ui.components.BottomPanel
import androidx.compose.runtime.key
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

    /** Like state per post, as toggled here (posts load signed out, so they start unliked). */
    val liked = mutableStateMapOf<String, Boolean>()

    fun toggleLike(post: Post, onError: (String) -> Unit) {
        val want = !(liked[post.id] ?: post.liked)
        liked[post.id] = want
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { Graph.api.likePost(post, want) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                liked[post.id] = !want
                onError(e.message ?: "Couldn't like the post")
            }
        }
    }
}

/** Comments (and replies) of one community post. */
class PostCommentsViewModel(private val post: Post) : ViewModel() {
    var comments by mutableStateOf<List<Comment>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var next: String? = null
    private var loadingMore = false
    val replies = mutableStateMapOf<String, List<Comment>>()
    private val repliesToken = HashMap<String, String?>()

    init { load() }

    fun load() {
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { Graph.api.postComments(post) }
                comments = page.comments.map(::toComment)
                next = page.next
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Couldn't load comments"
            } finally {
                loading = false
            }
        }
    }

    val canLoadMore get() = next != null

    fun loadMore() {
        val token = next ?: return
        if (loadingMore) return
        loadingMore = true
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { Graph.api.postCommentsMore(token) }
                val seen = comments.map { it.id }.toHashSet()
                comments = comments + page.comments.map(::toComment).filter { seen.add(it.id) }
                next = page.next.takeIf { it != token && page.comments.isNotEmpty() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                loadingMore = false
            }
        }
    }

    fun canLoadReplies(c: Comment) = if (c.id in replies) repliesToken[c.id] != null else repliesToken.containsKey(c.id)

    fun loadReplies(c: Comment) {
        val token = repliesToken[c.id] ?: return
        repliesToken[c.id] = null
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { Graph.api.postCommentsMore(token) }
                replies[c.id] = replies[c.id].orEmpty() + page.comments.map(::toComment)
                repliesToken[c.id] = page.next
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                repliesToken[c.id] = token
            }
        }
    }

    private fun toComment(p: PostComment): Comment {
        if (p.repliesToken != null && p.id !in repliesToken) repliesToken[p.id] = p.repliesToken
        return Comment(
            id = p.id, author = p.author, avatar = p.avatar, text = p.text, isHtml = false,
            likes = p.likes, published = p.published, replyCount = p.replyCount, repliesPage = null,
            pinned = false, hearted = false, byUploader = p.byUploader,
        )
    }
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
    var commentsFor by remember { mutableStateOf<Post?>(null) }
    Box(Modifier.fillMaxSize()) {
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
                items(vm.posts, key = { it.id }) { PostCard(it, vm, onVideo) { commentsFor = it } }
                if (vm.canLoadMore) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                    }
                }
            }
        }
    }
    commentsFor?.let { post -> key(post.id) { PostCommentsSheet(post) { commentsFor = null } } }
    }
}

@Composable
private fun PostCard(post: Post, vm: PostsViewModel, onVideo: (VideoItem) -> Unit, onComments: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val signedIn by Graph.signedIn.collectAsState()
    val context = LocalContext.current
    val liked = vm.liked[post.id] ?: post.liked
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
            Row(
                Modifier.padding(horizontal = 8.dp).padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (signedIn && post.likeAction != null) {
                    FilledTonalIconToggleButton(
                        checked = liked,
                        onCheckedChange = {
                            vm.toggleLike(post) { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                        },
                        shapes = IconButtonDefaults.toggleableShapes(),
                        modifier = Modifier.pressBounce(0.82f),
                    ) { Icon(if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, "Like") }
                } else {
                    Icon(
                        Icons.Outlined.ThumbUp, null,
                        Modifier.padding(start = 6.dp, end = 4.dp).size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                post.likes?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                if (post.detailBrowseId != null) {
                    TextButton(onClick = onComments, modifier = Modifier.pressBounce()) {
                        Icon(Icons.AutoMirrored.Outlined.Comment, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(post.comments?.let { "$it comments" } ?: "Comments")
                    }
                } else {
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
private fun PostCommentsSheet(post: Post, onDismiss: () -> Unit) {
    val vm: PostCommentsViewModel = viewModel(key = "post-comments:${post.id}") { PostCommentsViewModel(post) }
    // Same panel as video comments; it covers at most the lower 65% of the page.
    BottomPanel(onDismiss = onDismiss, maxFraction = 0.65f) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Comments", style = MaterialTheme.typography.headlineSmallEmphasized)
            post.comments?.let {
                Spacer(Modifier.size(8.dp))
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            vm.loading && vm.comments.isEmpty() ->
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
            vm.error != null && vm.comments.isEmpty() -> ErrorState(vm.error!!, { vm.load() }, Modifier.height(200.dp))
            vm.comments.isEmpty() -> Text(
                "No comments yet",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
            else -> LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(vm.comments, key = { it.id }) { c ->
                    CommentRow(c, isReply = false)
                    val loaded = vm.replies[c.id]
                    loaded?.forEach { r -> CommentRow(r, isReply = true) }
                    if (vm.canLoadReplies(c)) {
                        TextButton(
                            onClick = { vm.loadReplies(c) },
                            modifier = Modifier.padding(start = 60.dp).pressBounce(),
                        ) {
                            Text(
                                if (loaded == null) "${c.replyCount} ${if (c.replyCount == 1) "reply" else "replies"}" else "More replies",
                            )
                        }
                    }
                }
                if (vm.canLoadMore) {
                    item {
                        LaunchedEffect(vm.comments.size) { vm.loadMore() }
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                    }
                }
                item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
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
