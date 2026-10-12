package dev.materialtube.ui.screens

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
import androidx.compose.material3.PrimaryTabRow
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
import dev.materialtube.data.FeedPage
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

private val TABS = listOf(ChannelTab.Home, ChannelTab.Videos, ChannelTab.Shorts)

@Composable
fun ChannelScreen(
    channelId: String,
    onVideo: (VideoItem) -> Unit,
    onChannel: (String) -> Unit,
    onShorts: (List<VideoItem>, Int) -> Unit,
) {
    val header: ChannelHeaderViewModel = viewModel(key = "channel-header:$channelId") { ChannelHeaderViewModel(channelId) }
    var tab by rememberSaveable { mutableStateOf(0) }
    val selected = TABS[tab]

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
    val vm = if (selected == ChannelTab.Home) {
        home
    } else {
        rememberFeedViewModel(
            "channel:$channelId:${selected.name}",
            { Graph.api.channelTab(channelId, selected) },
            ::tabContinuation,
        )
    }

    FeedList(
        vm, onVideo, onChannel,
        onShorts = onShorts,
        showChannels = false,
        shortsGrid = selected == ChannelTab.Shorts,
        emptyText = "No ${selected.label.lowercase()} here",
        header = {
            Column {
                ChannelHeader(header)
                PrimaryTabRow(selectedTabIndex = tab) {
                    TABS.forEachIndexed { i, t ->
                        Tab(
                            selected = tab == i,
                            onClick = { tab = i },
                            text = { Text(t.label) },
                            modifier = Modifier.pressBounce(0.94f),
                        )
                    }
                }
            }
        },
    )
}

/** A tab's next page may come from either the TV or the web client, so try both. */
private fun tabContinuation(token: String): FeedPage =
    runCatching { Graph.api.channelTabContinuation(token, web = false) }.getOrNull()
        ?.takeIf { p -> p.shelves.any { it.items.isNotEmpty() } }
        ?: Graph.api.channelTabContinuation(token, web = true)

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
