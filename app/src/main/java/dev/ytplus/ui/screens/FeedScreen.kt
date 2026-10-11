package dev.ytplus.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
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
import dev.ytplus.data.FeedPage
import dev.ytplus.data.Shelf
import dev.ytplus.data.VideoItem
import dev.ytplus.ui.components.CenteredLoading
import dev.ytplus.ui.components.ErrorState
import dev.ytplus.ui.components.VideoCard
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
    modifier: Modifier = Modifier,
    emptyText: String = "Nothing here yet",
    header: (@Composable () -> Unit)? = null,
    state: LazyListState = rememberLazyListState(),
) {
    when {
        vm.loading && vm.shelves.isEmpty() -> CenteredLoading(modifier)
        vm.error != null && vm.shelves.isEmpty() -> ErrorState(vm.error!!, { vm.load() }, modifier)
        else -> PullToRefreshBox(
            isRefreshing = vm.refreshing,
            onRefresh = { vm.load(refresh = true) },
            modifier = modifier.fillMaxSize(),
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
                if (vm.shelves.isEmpty()) {
                    item {
                        Text(
                            emptyText,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
                vm.shelves.forEachIndexed { i, shelf ->
                    shelf.title?.let { title ->
                        item(key = "h$i") {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                    }
                    items(shelf.items, key = { "$i-${it.id}" }) { v ->
                        VideoCard(v, onClick = { onVideo(v) }, onChannel = onChannel)
                    }
                }
                if (vm.canLoadMore) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}
