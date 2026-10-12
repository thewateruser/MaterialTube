package dev.materialtube.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.materialtube.data.ChannelItem
import dev.materialtube.data.FeedPage
import dev.materialtube.data.Shelf
import dev.materialtube.data.VideoItem
import dev.materialtube.ui.components.CenteredLoading
import dev.materialtube.ui.components.ErrorState
import dev.materialtube.ui.components.ChannelRow
import dev.materialtube.ui.components.SectionTitle
import dev.materialtube.ui.components.ShortCard
import dev.materialtube.ui.components.ShortsRow
import dev.materialtube.ui.components.VideoCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Loads a paged InnerTube feed (home, subscriptions, history, search, channel...). */
class FeedViewModel(
    private val first: () -> FeedPage,
    private val next: (String) -> FeedPage,
) : ViewModel() {
    var shelves by mutableStateOf<List<Shelf>>(emptyList())
        private set
    var channels by mutableStateOf<List<ChannelItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var continuation: String? = null
    private var loadingMore = false

    init { load() }

    fun load(refresh: Boolean = false) {
        if (loading) return
        loading = !refresh
        refreshing = refresh
        error = null
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { first() }
                shelves = page.shelves
                channels = page.channels
                continuation = page.continuation
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Couldn't load"
            } finally {
                loading = false
                refreshing = false
            }
        }
    }

    fun loadMore() {
        val token = continuation ?: return
        if (loadingMore) return
        loadingMore = true
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { next(token) }
                val seen = shelves.flatMap { s -> s.items.map { it.id } }.toHashSet()
                val fresh = page.shelves
                    .map { s -> s.copy(items = s.items.filter { seen.add(it.id) }) }
                    .filter { it.items.isNotEmpty() }
                shelves = shelves + fresh
                continuation = page.continuation.takeIf { it != token && fresh.isNotEmpty() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep what we have; scrolling again retries.
            } finally {
                loadingMore = false
            }
        }
    }

    val canLoadMore get() = continuation != null
}

@Composable
fun rememberFeedViewModel(key: String, first: () -> FeedPage, next: (String) -> FeedPage): FeedViewModel =
    viewModel(key = key) { FeedViewModel(first, next) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedList(
    vm: FeedViewModel,
    onVideo: (VideoItem) -> Unit,
    onChannel: (String) -> Unit,
    onShorts: (List<VideoItem>, Int) -> Unit = { list, i -> onVideo(list[i]) },
    channelsTitle: String? = null,
    modifier: Modifier = Modifier,
    emptyText: String = "Nothing here yet",
    header: (@Composable () -> Unit)? = null,
    state: LazyListState = rememberLazyListState(),
    showChannels: Boolean = true,
    /** Lay Shorts out as a 3-column grid instead of horizontal rows (a channel's Shorts tab). */
    shortsGrid: Boolean = false,
) {
    when {
        // With a header (channel page), loading/errors show below it so the header and tabs stay put.
        header == null && vm.loading && vm.shelves.isEmpty() -> CenteredLoading(modifier)
        header == null && vm.error != null && vm.shelves.isEmpty() -> ErrorState(vm.error!!, { vm.load() }, modifier)
        else -> {
            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
            isRefreshing = vm.refreshing,
            onRefresh = { vm.load(refresh = true) },
            modifier = modifier.fillMaxSize(),
            state = pullState,
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = pullState,
                    isRefreshing = vm.refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
        ) {
            val nearEnd by remember {
                derivedStateOf {
                    val info = state.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                    info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
                }
            }
            LaunchedEffect(state) {
                snapshotFlow { nearEnd }.collect { if (it) vm.loadMore() }
            }
            LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                header?.let { h -> item { h() } }
                if (vm.loading && vm.shelves.isEmpty()) {
                    item { CenteredLoading(Modifier.height(280.dp)) }
                } else if (vm.error != null && vm.shelves.isEmpty()) {
                    item { ErrorState(vm.error!!, { vm.load() }, Modifier.height(280.dp)) }
                } else if (vm.shelves.isEmpty()) {
                    item {
                        Text(
                            emptyText,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
                if (showChannels && vm.channels.isNotEmpty()) {
                    item(key = "channels") {
                        ChannelRow(vm.channels, channelsTitle, onChannel)
                    }
                }
                vm.shelves.forEachIndexed { i, shelf ->
                    val shorts = shelf.items.filter { it.isShort }
                    val videos = shelf.items.filter { !it.isShort }
                    if (videos.isNotEmpty()) {
                        shelf.title?.let { title ->
                            item(key = "h$i") { SectionTitle(title) }
                        }
                        items(videos, key = { "$i-${it.id}" }) { v ->
                            VideoCard(v, onClick = { onVideo(v) }, onChannel = onChannel)
                        }
                    }
                    if (shorts.isNotEmpty() && shortsGrid) {
                        val all = vm.shelves.flatMap { s -> s.items.filter { it.isShort } }
                        val offset = vm.shelves.take(i).sumOf { s -> s.items.count { it.isShort } }
                        items(shorts.chunked(3), key = { "g$i-${it.first().id}" }) { row ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                row.forEach { v ->
                                    ShortCard(v, Modifier.weight(1f)) {
                                        onShorts(all, offset + shorts.indexOf(v))
                                    }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    } else if (shorts.isNotEmpty()) {
                        item(key = "s$i") {
                            ShortsRow(
                                title = if (videos.isEmpty()) shelf.title ?: "Shorts" else "Shorts",
                                items = shorts,
                                onClick = { index -> onShorts(shorts, index) },
                            )
                        }
                    }
                }
                if (vm.canLoadMore) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            LoadingIndicator()
                        }
                    }
                }
            }
        }
        }
    }
}
