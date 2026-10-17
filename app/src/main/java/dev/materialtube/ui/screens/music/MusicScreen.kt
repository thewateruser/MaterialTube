package dev.materialtube.ui.screens.music

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import dev.materialtube.Graph
import dev.materialtube.data.MusicSearchFilter
import dev.materialtube.data.innertube.MusicApi
import dev.materialtube.player.PlayerController
import dev.materialtube.ui.components.CenteredLoading
import dev.materialtube.ui.components.ErrorState
import dev.materialtube.ui.components.pressBounce

private val MUSIC_TABS = listOf("Home", "Explore", "Search", "Library")

/** The Music tab: YouTube Music home (your recommendations), explore, search and library. */
@Composable
fun MusicScreen(actions: MusicActions, onSignIn: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            MUSIC_TABS.forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) }, modifier = Modifier.pressBounce(0.94f))
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> {
                    val signedIn by Graph.signedIn.collectAsState()
                    val vm: MusicPageViewModel = viewModel(key = "music-home:$signedIn") {
                        MusicPageViewModel(
                            { Graph.music.home() },
                            { Graph.music.more(it) },
                            extra = if (signedIn) ({ Graph.music.personalHomeShelves() }) else null,
                        )
                    }
                    MusicPageList(vm, MusicLayout.Feed, actions, header = { PersonalStatus(vm) })
                }
                1 -> {
                    val vm: MusicPageViewModel = viewModel(key = "music-explore") {
                        MusicPageViewModel(
                            { Graph.music.explore() },
                            { Graph.music.more(it) },
                            extra = if (Graph.signedIn.value) ({ Graph.music.personalExploreShelves() }) else null,
                        )
                    }
                    MusicPageList(vm, MusicLayout.Feed, actions, header = { PersonalStatus(vm) })
                }
                2 -> MusicSearch(actions)
                else -> MusicLibrary(actions, onSignIn)
            }
        }
    }
}

/** A loaded music page as a scrolling list, with infinite scroll and an optional header. */
@Composable
fun MusicPageList(
    vm: MusicPageViewModel,
    layout: MusicLayout,
    actions: MusicActions,
    header: (@Composable () -> Unit)? = null,
) {
    val page = vm.page
    when {
        page == null && vm.error != null -> ErrorState(vm.error!!, { vm.load() })
        page == null -> CenteredLoading()
        else -> {
            val state = rememberLazyListState()
            val nearEnd by remember {
                derivedStateOf {
                    val info = state.layoutInfo
                    info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 4
                }
            }
            LaunchedEffect(state) { snapshotFlow { nearEnd }.collect { if (it) vm.loadMore() } }
            LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                header?.let { h -> item { h() } }
                if (page.shelves.isEmpty()) {
                    item { Text("Nothing here yet", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp)) }
                }
                musicShelves(page.shelves, layout, actions, page.playlistId)
                if (vm.canLoadMore) {
                    item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { LoadingIndicator() } }
                }
                item { Spacer(Modifier.height(96.dp)) } // room for the mini player
            }
        }
    }
}

@Composable
private fun MusicSearch(actions: MusicActions) {
    var text by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(MusicSearchFilter.All) }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Songs, albums, artists") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            singleLine = true,
            shape = CircleShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { query = text.trim() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MusicSearchFilter.entries.forEach { f ->
                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }, modifier = Modifier.pressBounce(0.94f))
            }
        }
        Box(Modifier.weight(1f)) {
            if (query.isBlank()) {
                Text(
                    "Search YouTube Music",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            } else {
                val q = query
                val f = filter
                val vm: MusicPageViewModel = viewModel(key = "music-search:$q:${f.name}") {
                    MusicPageViewModel({ Graph.music.search(q, f) }, { Graph.music.searchMore(it) })
                }
                MusicPageList(vm, MusicLayout.List, actions)
            }
        }
    }
}

@Composable
private fun MusicLibrary(actions: MusicActions, onSignIn: () -> Unit) {
    val signedIn by Graph.signedIn.collectAsState()
    if (!signedIn) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Sign in to see your liked music, playlists, albums and artists", textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onSignIn, shapes = ButtonDefaults.shapes(), modifier = Modifier.pressBounce()) { Text("Sign in") }
        }
        return
    }
    var section by rememberSaveable { mutableStateOf(MusicApi.LibrarySection.Liked) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MusicApi.LibrarySection.entries.forEach { s ->
                FilterChip(selected = section == s, onClick = { section = s }, label = { Text(s.label) }, modifier = Modifier.pressBounce(0.94f))
            }
        }
        Box(Modifier.weight(1f)) {
            val s = section
            val vm: MusicPageViewModel = viewModel(key = "music-library:${s.name}") {
                MusicPageViewModel({ Graph.music.library(s) }, { Graph.music.more(it) })
            }
            MusicPageList(vm, MusicLayout.List, actions)
        }
    }
}

/** Album, playlist, artist or "more" page. */
@Composable
fun MusicCollectionScreen(browseId: String, params: String?, actions: MusicActions) {
    val vm: MusicPageViewModel = viewModel(key = "music-page:$browseId:$params") {
        MusicPageViewModel({ Graph.music.page(browseId, params) }, { Graph.music.more(it) })
    }
    val artist = browseId.startsWith("UC")
    MusicPageList(
        vm,
        MusicLayout.Page,
        actions,
        header = {
            vm.page?.let { p ->
                // Some pages (e.g. releases opened from a channel) come without a header.
                val hint = MusicHints.get(browseId)
                CollectionHeader(
                    p.copy(
                        title = p.title ?: hint?.title,
                        subtitle = p.subtitle ?: hint?.subtitle,
                        thumbnail = p.thumbnail ?: hint?.thumbnail,
                        playlistId = p.playlistId ?: hint?.playlistId,
                    ),
                    artist,
                    actions,
                )
            }
        },
    )
}

@Composable
private fun CollectionHeader(page: dev.materialtube.data.MusicPage, artist: Boolean, actions: MusicActions) {
    val tracks = page.shelves.firstOrNull { s -> s.items.any { it.type.playable } }?.items.orEmpty()
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = page.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth(if (artist) 0.55f else 0.7f)
                .aspectRatio(1f)
                .clip(if (artist) CircleShape else RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.height(16.dp))
        page.title?.let {
            Text(it, style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        page.subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2)
        }
        page.description?.let { d ->
            var expanded by remember { mutableStateOf(false) }
            Text(
                d,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
            androidx.compose.material3.TextButton(onClick = { expanded = !expanded }, modifier = Modifier.pressBounce()) {
                Text(if (expanded) "Less" else "More")
            }
        }
        if (tracks.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val pl = page.playlistId
                        if (pl != null && !artist) PlayerController.playPlaylist(pl) else PlayerController.playMusic(tracks, 0, pl)
                        actions.openPlayer()
                    },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.pressBounce(),
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Play")
                }
                FilledTonalButton(
                    onClick = {
                        val pl = page.playlistId
                        if (pl != null && !artist) PlayerController.playPlaylist(pl, shuffle = true) else PlayerController.playMusic(tracks.shuffled(), 0)
                        actions.openPlayer()
                    },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.pressBounce(),
                ) {
                    Icon(Icons.Filled.Shuffle, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Shuffle")
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(text: String) {
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
}

/** "Loading your music…" while the personal shelves load, or why they didn't. */
@Composable
private fun PersonalStatus(vm: MusicPageViewModel) {
    when {
        vm.extraLoading -> Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoadingIndicator(Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text("Loading your music…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        vm.extraNotice != null -> NoticeCard(vm.extraNotice!!)
    }
}
