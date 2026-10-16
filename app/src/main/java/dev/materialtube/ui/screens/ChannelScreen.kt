package dev.materialtube.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import dev.materialtube.ui.components.CenteredLoading
import dev.materialtube.ui.components.ErrorState
import dev.materialtube.ui.components.bounceClick
import dev.materialtube.ui.screens.music.MusicActions
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import dev.materialtube.Graph
import dev.materialtube.data.ChannelPage
import dev.materialtube.data.ChannelTab
import dev.materialtube.data.VideoItem
import dev.materialtube.ui.components.pressBounce
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Channel header state: filled in by the Home tab's first load, plus the subscribe toggle. */
class ChannelHeaderViewModel(private val channelId: String) : ViewModel() {
    var page by mutableStateOf<ChannelPage?>(null)
    var subscribed by mutableStateOf(false)
        private set

    /** Whether the channel has a Releases tab (a musician), checked in the background. */
    var hasReleases by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            hasReleases = runCatching {
                withContext(Dispatchers.IO) { Graph.api.channelTabTitles(channelId) }
            }.getOrDefault(emptySet()).any { it.contains("release", ignoreCase = true) }
        }
    }

    fun loaded(p: ChannelPage) {
        page = p
        subscribed = p.subscribed == true
    }

    fun toggleSubscribe(toast: (String) -> Unit) {
        val want = !subscribed
        subscribed = want
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (want) Graph.api.subscribe(channelId) else Graph.api.unsubscribe(channelId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                subscribed = !want
                toast("Couldn't update subscription")
            }
        }
    }
}

private fun tabsFor(releases: Boolean) = listOfNotNull(
    ChannelTab.Home, ChannelTab.Videos, ChannelTab.Shorts,
    ChannelTab.Releases.takeIf { releases },
    ChannelTab.Posts, ChannelTab.About,
)

/** A musician's albums and singles. */
class ReleasesViewModel(private val channelId: String) : ViewModel() {
    var releases by mutableStateOf<List<dev.materialtube.data.MusicItem>?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init { load() }

    fun load() {
        error = null
        viewModelScope.launch {
            try {
                releases = withContext(Dispatchers.IO) { Graph.api.channelReleases(channelId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Couldn't load releases"
            }
        }
    }
}

@Composable
fun ChannelScreen(
    channelId: String,
    onVideo: (VideoItem) -> Unit,
    onChannel: (String) -> Unit,
    onShorts: (List<VideoItem>, Int) -> Unit,
    musicActions: MusicActions,
) {
    val header: ChannelHeaderViewModel = viewModel(key = "channel-header:$channelId") { ChannelHeaderViewModel(channelId) }
    val tabs = tabsFor(header.hasReleases)
    var selectedTab by rememberSaveable { mutableStateOf(ChannelTab.Home) }
    val selected = selectedTab.takeIf { it in tabs } ?: ChannelTab.Home
    val tab = tabs.indexOf(selected)

    // Home loads the channel page itself (which also fills the header); the other tabs are
    // separate browse calls. Each tab keeps its own model, so switching back is instant.
    val home = rememberFeedViewModel(
        "channel:$channelId:Home",
        {
            val p = Graph.api.channel(channelId)
            header.loaded(p)
            p.feed
        },
        { Graph.api.continuation(it) },
    )
    val vm = if (selected.params == null) {
        home
    } else {
        rememberFeedViewModel(
            "channel:$channelId:${selected.name}",
            { Graph.api.channelTab(channelId, selected) },
            { Graph.api.channelTabContinuation(it) },
        )
    }

    val headerContent: @Composable () -> Unit = {
        Column {
            ChannelHeader(header)
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, t ->
                    Tab(
                        selected = tab == i,
                        onClick = { selectedTab = t },
                        text = { Text(t.label) },
                        modifier = Modifier.pressBounce(0.94f),
                    )
                }
            }
        }
    }

    when (selected) {
        ChannelTab.Posts -> {
            val posts: PostsViewModel = viewModel(key = "channel:$channelId:Posts") { PostsViewModel(channelId) }
            PostsList(posts, headerContent, onVideo)
        }
        ChannelTab.About -> {
            val about: AboutViewModel = viewModel(key = "channel:$channelId:About") { AboutViewModel(channelId) }
            AboutList(about, headerContent)
        }
        ChannelTab.Releases -> {
            val releases: ReleasesViewModel = viewModel(key = "channel:$channelId:Releases") { ReleasesViewModel(channelId) }
            ReleasesList(releases, headerContent, musicActions)
        }
        else -> FeedList(
            vm, onVideo, onChannel,
            onShorts = onShorts,
            showChannels = false,
            shortsGrid = selected == ChannelTab.Shorts,
            emptyText = "No ${selected.label.lowercase()} here",
            header = headerContent,
        )
    }
}

@Composable
private fun ChannelHeader(vm: ChannelHeaderViewModel) {
    val page = vm.page
    val signedIn by Graph.signedIn.collectAsState()
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .aspectRatio(16f / 5f)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.secondaryContainer),
        ) {
            page?.banner?.let {
                AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .offset(y = (-28).dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            AsyncImage(
                model = page?.avatar,
                contentDescription = null,
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
                Text(
                    page?.title ?: "",
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                page?.subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (signedIn && page != null) {
            ToggleButton(
                checked = vm.subscribed,
                onCheckedChange = { vm.toggleSubscribe { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } },
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .offset(y = (-16).dp)
                    .fillMaxWidth()
                    .pressBounce(),
            ) {
                if (vm.subscribed) {
                    Icon(Icons.Outlined.NotificationsActive, null, Modifier.size(ToggleButtonDefaults.IconSize))
                    Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
                }
                Text(if (vm.subscribed) "Subscribed" else "Subscribe")
            }
        }
    }
}

@Composable
private fun ReleasesList(vm: ReleasesViewModel, header: @Composable () -> Unit, actions: MusicActions) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { header() }
        val list = vm.releases
        when {
            list == null && vm.error != null -> item { ErrorState(vm.error!!, { vm.load() }, Modifier.height(280.dp)) }
            list == null -> item { CenteredLoading(Modifier.height(280.dp)) }
            list.isEmpty() -> item {
                Text("No releases", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            }
            else -> items(list.chunked(2)) { row ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    row.forEach { release ->
                        Box(Modifier.weight(1f)) {
                            ReleaseCard(release) { actions.openPage(release.id, null) }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
private fun ReleaseCard(item: dev.materialtube.data.MusicItem, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().bounceClick(pressedScale = 0.95f, onClick = onClick)) {
        AsyncImage(
            model = item.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.height(8.dp))
        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        item.subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
