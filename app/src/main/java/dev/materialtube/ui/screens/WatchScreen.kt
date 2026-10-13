@file:OptIn(UnstableApi::class)

package dev.materialtube.ui.screens

import androidx.media3.ui.AspectRatioFrameLayout
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.unit.Dp
import dev.materialtube.ui.components.BottomPanel
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import kotlinx.coroutines.delay
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.calculateZoom
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import dev.materialtube.Graph
import dev.materialtube.data.CommentSort
import dev.materialtube.data.LikeStatus
import dev.materialtube.data.NextInfo
import dev.materialtube.data.VideoItem
import dev.materialtube.player.PlayerController
import dev.materialtube.streams.Comment
import dev.materialtube.streams.Comments
import dev.materialtube.ui.components.bounceClick
import dev.materialtube.ui.components.pressBounce
import dev.materialtube.ui.components.CompactVideoRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page

/**
 * Watch-page state. [loadCommentsNow] = false (Shorts) defers comments until the sheet opens so
 * they don't compete with the video for bandwidth.
 */
class WatchViewModel(private val videoId: String, loadCommentsNow: Boolean = true) : ViewModel() {
    var next by mutableStateOf<NextInfo?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var like by mutableStateOf<LikeStatus?>(null)
        private set
    var subscribed by mutableStateOf<Boolean?>(null)
        private set

    // Comments
    var comments by mutableStateOf<List<Comment>>(emptyList())
        private set
    var commentCount by mutableStateOf<Int?>(null)
        private set
    var commentsState by mutableStateOf(CommentsState.Loading)
        private set
    private var commentsNext: Page? = null
    private var loadingMoreComments = false
    val canLoadMoreComments get() = commentsNext != null

    /** Replies per comment id, loaded on demand. */
    val replies = mutableStateMapOf<String, List<Comment>>()
    private val repliesNext = mutableMapOf<String, Page?>()

    enum class CommentsState { Loading, Ready, Disabled, Error }

    /** "Top comments" / "Newest first", loaded when the comments sheet opens. */
    var sorts by mutableStateOf<List<CommentSort>>(emptyList())
        private set
    var selectedSort by mutableStateOf(0)
        private set
    private var sortsRequested = false
    private var commentsRequested = false

    private var sortsLoaded = false

    fun loadSorts() {
        if (sortsRequested) return
        sortsRequested = true
        viewModelScope.launch {
            sorts = runCatching { withContext(Dispatchers.IO) { Graph.api.commentSorts(videoId) } }.getOrDefault(emptyList())
            sortsLoaded = true
            pendingSort?.let { wanted ->
                pendingSort = null
                selectedSort = -1
                selectSort(wanted)
            }
        }
    }

    /** Sort picked before the options finished loading; applied when they arrive. */
    private var pendingSort: Int? = null

    fun selectSort(index: Int) {
        if (index == selectedSort) return
        selectedSort = index
        if (index == 0 && sorts.isEmpty()) {
            pendingSort = null
            loadComments() // the default order is "Top"
            return
        }
        val sort = sorts.getOrNull(index)
        commentsState = CommentsState.Loading
        comments = emptyList()
        commentsNext = null
        replies.clear()
        if (sort == null) {
            pendingSort = index
            if (sortsLoaded) {
                // YouTube didn't offer sort options for this video.
                pendingSort = null
                selectedSort = 0
                loadComments()
            }
            return
        }
        pendingSort = null
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { Comments.fromToken(videoId, sort.token) }
                if (selectedSort != index) return@launch
                comments = page.items
                commentsNext = page.next
                commentsState = CommentsState.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (selectedSort == index) commentsState = CommentsState.Error
            }
        }
    }

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
        if (loadCommentsNow) ensureComments()
    }

    /** Loads comments and, in parallel, the sort options, so both are ready together. */
    fun ensureComments() {
        if (commentsRequested) return
        loadComments()
        loadSorts()
    }

    fun loadComments() {
        commentsRequested = true
        selectedSort = 0
        commentsState = CommentsState.Loading
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { Comments.first(videoId) }
                if (selectedSort != 0) return@launch // switched to another sort meanwhile
                comments = page.items
                commentsNext = page.next
                commentCount = page.count
                commentsState = if (page.disabled) CommentsState.Disabled else CommentsState.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                commentsState = CommentsState.Error
            }
        }
    }

    fun loadMoreComments() {
        val page = commentsNext ?: return
        if (loadingMoreComments) return
        loadingMoreComments = true
        viewModelScope.launch {
            try {
                val more = withContext(Dispatchers.IO) { Comments.more(videoId, page) }
                val seen = comments.map { it.id }.toHashSet()
                comments = comments + more.items.filter { seen.add(it.id) }
                commentsNext = more.next
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                loadingMoreComments = false
            }
        }
    }

    fun loadReplies(comment: Comment) {
        val page = if (comment.id in replies) repliesNext[comment.id] else comment.repliesPage
        page ?: return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { Comments.more(videoId, page) } }.onSuccess {
                replies[comment.id] = replies[comment.id].orEmpty() + it.items
                repliesNext[comment.id] = it.next
            }
        }
    }

    fun hasMoreReplies(comment: Comment) = comment.id in replies && repliesNext[comment.id] != null

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
    onFullscreen: (Boolean) -> Unit,
    /** Crop to fill the screen instead of showing the whole frame with bars. */
    fill: Boolean = false,
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
                    // The player reports the state it wants (it also calls this when we set the
                    // button state), so pass it on as-is rather than toggling.
                    setFullscreenButtonClickListener { wantFullscreen -> onFullscreen(wantFullscreen) }
                }
            },
            update = {
                it.useController = showControls
                it.setFullscreenButtonState(fullscreen)
                it.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        if (loading) LoadingIndicator(Modifier.align(Alignment.Center))
        error?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Playback failed: $msg", color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { PlayerController.retry() }, shapes = ButtonDefaults.shapes(), modifier = Modifier.pressBounce()) { Text("Retry") }
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
    onFullscreen: (Boolean) -> Unit,
    onVideo: (VideoItem) -> Unit,
    onChannel: (String) -> Unit,
    onMinimize: () -> Unit,
) {
    LaunchedEffect(videoId) { PlayerController.play(videoId, hint) }
    val nowPlaying by PlayerController.nowPlaying.collectAsState()
    val signedIn by Graph.signedIn.collectAsState()
    val vm: WatchViewModel = viewModel(key = "watch-$videoId") { WatchViewModel(videoId) }
    val context = LocalContext.current
    val toast: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    var showComments by remember { mutableStateOf(false) }

    // Drag the video down: minimise into the floating mini player (or leave fullscreen).
    val dragY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenHeight = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val threshold = with(density) { 120.dp.toPx() }
    val dragToMinimize = Modifier.dragDown(
        onDrag = { dy -> scope.launch { dragY.snapTo((dragY.value + dy).coerceAtLeast(0f)) } },
        onEnd = {
            scope.launch {
                if (dragY.value > threshold) {
                    if (fullscreen) {
                        onFullscreen(false)
                        dragY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                    } else {
                        dragY.animateTo(screenHeight * 0.6f, tween(160))
                        onMinimize()
                    }
                } else {
                    dragY.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow))
                }
            }
        },
    )
    val progress = (dragY.value / (screenHeight * 0.6f)).coerceIn(0f, 1f)

    if (fullscreen || inPip) {
        // Pinch out to crop the video to fill the screen, pinch in to see the whole frame.
        var fill by rememberSaveable { mutableStateOf(false) }
        var hint by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(hint) {
            if (hint != null) {
                delay(900)
                hint = null
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoPlayer(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = dragY.value
                        scaleX = 1f - progress * 0.2f
                        scaleY = 1f - progress * 0.2f
                    }
                    .then(
                        if (inPip) {
                            Modifier
                        } else {
                            Modifier
                                .pinchToFill { zoomIn ->
                                    if (zoomIn != fill) {
                                        fill = zoomIn
                                        hint = if (zoomIn) "Zoomed to fill" else "Original"
                                    }
                                }
                                .then(dragToMinimize)
                        },
                    ),
                fullscreen = true,
                showControls = !inPip,
                onFullscreen = onFullscreen,
                fill = fill && !inPip,
            )
            AnimatedVisibility(
                visible = hint != null,
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp),
            ) {
                Surface(shape = CircleShape, color = Color(0xCC000000), contentColor = Color.White) {
                    Text(hint.orEmpty(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Comments open over the area below the video, so the video stays visible.
    val belowVideo = maxHeight - WindowInsets.statusBars.asPaddingValues().calculateTopPadding() - maxWidth * 9f / 16f
    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = dragY.value
                val scale = 1f - progress * 0.25f
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0.5f, 0f)
                alpha = 1f - progress * 0.6f
            }
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        VideoPlayer(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).then(dragToMinimize),
            fullscreen = false,
            showControls = true,
            onFullscreen = onFullscreen,
        )
        val np = nowPlaying
        val next = vm.next
        LazyColumn(Modifier.fillMaxSize()) {
            // Title + description card
            item {
                var expanded by remember { mutableStateOf(false) }
                Surface(
                    onClick = { expanded = !expanded },
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp).pressBounce(0.98f),
                ) {
                    Column(Modifier.animateContentSize().padding(16.dp)) {
                        Text(
                            np?.title?.ifEmpty { null } ?: hint?.title ?: "",
                            style = MaterialTheme.typography.titleLargeEmphasized,
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val meta = listOfNotNull(
                            np?.details?.viewCount?.let { "%,d views".format(it) },
                            hint?.meta?.substringAfter(" • ", "")?.ifEmpty { null },
                        ).joinToString(" • ")
                        if (meta.isNotEmpty()) {
                            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val description = np?.details?.description
                        if (!description.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                description,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = if (expanded) Int.MAX_VALUE else 2,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!expanded) {
                                Text("more", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            // Channel row
            item {
                val channelId = next?.channelId ?: np?.channelId
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.medium)
                            .bounceClick(enabled = channelId != null) { channelId?.let(onChannel) }
                            .padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = next?.channelAvatar,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(
                            next?.channelName ?: np?.channel ?: "",
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (signedIn && channelId != null) {
                        val subscribed = vm.subscribed == true
                        ToggleButton(
                            checked = subscribed,
                            onCheckedChange = { vm.toggleSubscribe(channelId, toast) },
                            modifier = Modifier.pressBounce(),
                        ) {
                            if (subscribed) {
                                Icon(Icons.Outlined.NotificationsActive, null, Modifier.size(ToggleButtonDefaults.IconSize))
                                Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
                            }
                            Text(if (subscribed) "Subscribed" else "Subscribe")
                        }
                    }
                }
            }
            // Actions
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (signedIn) {
                        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupGap)) {
                            ToggleButton(
                                checked = vm.like == LikeStatus.LIKE,
                                onCheckedChange = { vm.toggleLike(LikeStatus.LIKE, toast) },
                                modifier = Modifier.pressBounce(0.88f),
                            ) {
                                Icon(
                                    if (vm.like == LikeStatus.LIKE) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                                    "Like",
                                    Modifier.size(ToggleButtonDefaults.IconSize),
                                )
                                next?.likeCountText?.let {
                                    Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
                                    Text(it)
                                }
                            }
                            ToggleButton(
                                checked = vm.like == LikeStatus.DISLIKE,
                                onCheckedChange = { vm.toggleLike(LikeStatus.DISLIKE, toast) },
                                modifier = Modifier.pressBounce(0.88f),
                            ) {
                                Icon(
                                    if (vm.like == LikeStatus.DISLIKE) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                                    "Dislike",
                                    Modifier.size(ToggleButtonDefaults.IconSize),
                                )
                            }
                        }
                    }
                    QualityButton(np?.height, np?.availableHeights.orEmpty())
                    FilledTonalButton(onClick = { share(context, videoId) }, shapes = ButtonDefaults.shapes(), modifier = Modifier.pressBounce()) {
                        Icon(Icons.Filled.Share, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text("Share")
                    }
                }
            }
            // Comments teaser
            item {
                CommentsTeaser(vm) { showComments = true }
            }
            item {
                Text(
                    "Up next",
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            if (vm.loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                }
            }
            items(next?.related.orEmpty(), key = { it.id }) { v ->
                CompactVideoRow(v) { onVideo(v) }
            }
        }
    }

    if (showComments) {
        // Never over the video: the panel fills, at most, the area below it.
        CommentsSheet(vm, onDismiss = { showComments = false }, collapsedHeight = belowVideo, maxHeight = belowVideo)
    }
    }
}

private val ButtonGroupGap = 2.dp

@Composable
private fun CommentsTeaser(vm: WatchViewModel, onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        enabled = vm.commentsState == WatchViewModel.CommentsState.Ready,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp).pressBounce(0.98f),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Outlined.Comment, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                Text("Comments", style = MaterialTheme.typography.titleMediumEmphasized)
                vm.commentCount?.let {
                    Spacer(Modifier.size(8.dp))
                    Text("%,d".format(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            when (vm.commentsState) {
                WatchViewModel.CommentsState.Loading -> LoadingIndicator(Modifier.size(32.dp))
                WatchViewModel.CommentsState.Disabled ->
                    Text("Comments are turned off", color = MaterialTheme.colorScheme.onSurfaceVariant)
                WatchViewModel.CommentsState.Error -> TextButton(onClick = vm::loadComments, modifier = Modifier.pressBounce()) { Text("Couldn't load comments · Retry") }
                WatchViewModel.CommentsState.Ready -> {
                    val top = vm.comments.firstOrNull()
                    if (top == null) {
                        Text("No comments yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = top.avatar,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                            )
                            Spacer(Modifier.size(10.dp))
                            Text(commentText(top), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CommentsSheet(
    vm: WatchViewModel,
    onDismiss: () -> Unit,
    collapsedHeight: Dp? = null,
    maxHeight: Dp? = null,
) {
    LaunchedEffect(Unit) { vm.ensureComments() }
    BottomPanel(onDismiss = onDismiss, collapsedHeight = collapsedHeight, maxPanelHeight = maxHeight) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Comments", style = MaterialTheme.typography.headlineSmallEmphasized)
            vm.commentCount?.let {
                Spacer(Modifier.size(8.dp))
                Text("%,d".format(it), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Shown straight away; the real options (tokens) load alongside the comments.
        val labels = vm.sorts.map { it.label }.takeIf { it.size > 1 } ?: listOf("Top", "Newest")
        SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            labels.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = vm.selectedSort == index,
                    onClick = { vm.selectSort(index) },
                    shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                    label = { Text(label) },
                    modifier = Modifier.pressBounce(0.95f),
                )
            }
        }
        if (vm.commentsState == WatchViewModel.CommentsState.Loading) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(vm.comments, key = { it.id }) { c ->
                CommentRow(c, isReply = false)
                val loaded = vm.replies[c.id]
                loaded?.forEach { r -> CommentRow(r, isReply = true) }
                val canExpand = (loaded == null && c.replyCount > 0 && c.repliesPage != null) || vm.hasMoreReplies(c)
                if (canExpand) {
                    TextButton(
                        onClick = { vm.loadReplies(c) },
                        modifier = Modifier.padding(start = 60.dp).pressBounce(),
                    ) {
                        Text(if (loaded == null) "${c.replyCount} ${if (c.replyCount == 1) "reply" else "replies"}" else "More replies")
                    }
                }
            }
            if (vm.canLoadMoreComments) {
                item {
                    LaunchedEffect(vm.comments.size) { vm.loadMoreComments() }
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                }
            }
            item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
        }
    }
}

@Composable
internal fun CommentRow(c: Comment, isReply: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (isReply) 60.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
    ) {
        AsyncImage(
            model = c.avatar,
            contentDescription = null,
            modifier = Modifier.size(if (isReply) 28.dp else 36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.pinned) {
                    Icon(Icons.Filled.PushPin, "Pinned", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(4.dp))
                }
                val authorStyle = MaterialTheme.typography.labelLarge
                if (c.byUploader) {
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(c.author, style = authorStyle, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                    }
                } else {
                    Text(c.author, style = authorStyle)
                }
                c.published?.let {
                    Text("  ·  $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(commentText(c), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ThumbUp, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                c.likes?.let {
                    Spacer(Modifier.size(4.dp))
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (c.hearted) {
                    Spacer(Modifier.size(12.dp))
                    Icon(Icons.Filled.Favorite, "Hearted by creator", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun commentText(c: Comment): AnnotatedString =
    if (c.isHtml) AnnotatedString.fromHtml(c.text) else AnnotatedString(c.text)

@Composable
private fun QualityButton(current: Int?, heights: List<Int>) {
    if (heights.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { open = true }, shapes = ButtonDefaults.shapes(), modifier = Modifier.pressBounce()) {
            Icon(Icons.Filled.HighQuality, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(current?.let { "${it}p" } ?: "Auto")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = MaterialTheme.shapes.large) {
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

internal fun share(context: Context, videoId: String) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, "https://youtu.be/$videoId")
    context.startActivity(Intent.createChooser(intent, "Share"))
}

/**
 * Detects a downward drag that starts on this element, even on top of the PlayerView (watches the
 * initial pass, then takes over the gesture once it is clearly a downward drag so taps still reach
 * the player controls).
 */
private fun Modifier.dragDown(onDrag: (Float) -> Unit, onEnd: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var dragging = false
        var total = Offset.Zero
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if (!dragging && event.changes.count { it.pressed } > 1) break // a pinch, not a drag
            val delta = change.positionChange()
            if (!dragging) {
                total += delta
                if (total.getDistance() > viewConfiguration.touchSlop) {
                    if (total.y > 0 && kotlin.math.abs(total.y) > kotlin.math.abs(total.x) * 1.5f) {
                        dragging = true
                        onDrag(total.y)
                    } else {
                        break // sideways/upward: leave it to the player
                    }
                }
            } else {
                onDrag(delta.y)
            }
            if (dragging) change.consume()
        }
        if (dragging) onEnd()
    }
}

/**
 * Two-finger pinch: reports `true` after pinching out, `false` after pinching in. Watches the
 * initial pass and only consumes events while two fingers are down, so taps and one-finger
 * gestures still reach the player.
 */
private fun Modifier.pinchToFill(onResult: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zoom = 1f
        var pinching = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.none { it.pressed }) break
            if (event.changes.count { it.pressed } >= 2) {
                pinching = true
                zoom *= event.calculateZoom()
                event.changes.forEach { it.consume() }
            }
        }
        if (pinching) {
            if (zoom > 1.15f) onResult(true) else if (zoom < 0.87f) onResult(false)
        }
    }
}
