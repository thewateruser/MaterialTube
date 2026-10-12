package dev.materialtube.ui.screens

import dev.materialtube.ui.components.pressBounce
import dev.materialtube.ui.components.bounceClick
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import dev.materialtube.ui.components.AvatarShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.materialtube.Graph
import dev.materialtube.data.VideoItem
import dev.materialtube.data.sponsorblock.SponsorBlock

@Composable
fun SearchScreen(
    onVideo: (VideoItem) -> Unit,
    onChannel: (String) -> Unit,
    onShorts: (List<VideoItem>, Int) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Search YouTube") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { query = text.trim() }),
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (query.isNotEmpty()) {
            val q = query
            val vm = rememberFeedViewModel("search:$q", { Graph.api.search(q) }, { Graph.api.searchContinuation(it) })
            FeedList(vm, onVideo, onChannel, onShorts = onShorts, channelsTitle = "Channels", emptyText = "No results")
        }
    }
}

/** Generic browse feed used by the Library entries (history, liked, watch later). */
@Composable
fun BrowseScreen(browseId: String, title: String, onVideo: (VideoItem) -> Unit, onChannel: (String) -> Unit) {
    val vm = rememberFeedViewModel("browse:$browseId", { Graph.api.browse(browseId) }, { Graph.api.continuation(it) })
    FeedList(
        vm, onVideo, onChannel,
        header = {
            Text(title, style = MaterialTheme.typography.headlineSmallEmphasized, modifier = Modifier.padding(16.dp))
        },
    )
}

@Composable
fun LibraryScreen(onOpen: (browseId: String, title: String) -> Unit, onSettings: () -> Unit, onSignIn: () -> Unit) {
    val signedIn by Graph.signedIn.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ListItem(
            headlineContent = { Text(if (signedIn) "Signed in to YouTube" else "Not signed in") },
            supportingContent = {
                Text(if (signedIn) "Recommendations, history and subscriptions are synced" else "Sign in to get your recommendations")
            },
            leadingContent = { Icon(Icons.Filled.AccountCircle, null, Modifier.size(40.dp)) },
            trailingContent = if (!signedIn) {
                { Button(onClick = onSignIn, shapes = ButtonDefaults.shapes(), modifier = Modifier.pressBounce()) { Text("Sign in") } }
            } else {
                null
            },
        )
        HorizontalDivider()
        if (signedIn) {
            LibraryEntry(Icons.Filled.History, "History") { onOpen("FEhistory", "History") }
            LibraryEntry(Icons.Filled.ThumbUp, "Liked videos") { onOpen("VLLL", "Liked videos") }
            LibraryEntry(Icons.Filled.WatchLater, "Watch later") { onOpen("VLWL", "Watch later") }
            HorizontalDivider()
        }
        LibraryEntry(Icons.Filled.Settings, "Settings", onSettings)
    }
}

@Composable
private fun LibraryEntry(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, null) },
        modifier = Modifier.bounceClick(pressedScale = 0.97f, onClick = onClick),
    )
}

@Composable
fun SettingsScreen(onSignIn: () -> Unit) {
    val prefs = Graph.prefs
    val signedIn by Graph.signedIn.collectAsState()
    var quality by rememberSaveable { mutableStateOf(prefs.maxQuality) }
    var sb by rememberSaveable { mutableStateOf(prefs.sponsorBlockEnabled) }
    var categories by remember { mutableStateOf(prefs.sponsorBlockCategories.toList()) }
    var background by rememberSaveable { mutableStateOf(prefs.backgroundPlay) }
    var pip by rememberSaveable { mutableStateOf(prefs.autoPip) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("Playback")
        SwitchRow("Background play", "Keep playing with the screen off or in other apps", background) {
            background = it; prefs.backgroundPlay = it
        }
        SwitchRow("Picture-in-picture", "Shrink the video when you leave the app", pip) {
            pip = it; prefs.autoPip = it
        }
        Header("Preferred quality")
        listOf(2160, 1440, 1080, 720, 480, 360).forEach { h ->
            ListItem(
                headlineContent = { Text("${h}p") },
                leadingContent = { RadioButton(selected = quality == h, onClick = null) },
                modifier = Modifier.bounceClick(pressedScale = 0.97f) { quality = h; prefs.maxQuality = h },
            )
        }
        Header("SponsorBlock")
        SwitchRow("Skip segments automatically", "Community-submitted segments from sponsor.ajay.app", sb) {
            sb = it; prefs.sponsorBlockEnabled = it
        }
        if (sb) {
            SponsorBlock.CATEGORIES.forEach { (key, label) ->
                val checked = key in categories
                ListItem(
                    headlineContent = { Text(label) },
                    leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                    modifier = Modifier.bounceClick(pressedScale = 0.97f) {
                        categories = if (checked) categories - key else categories + key
                        prefs.sponsorBlockCategories = categories.toSet()
                    },
                )
            }
        }
        Header("Account")
        if (signedIn) {
            ListItem(
                headlineContent = { Text("Sign out") },
                supportingContent = { Text("You can also revoke access at myaccount.google.com → Security → Third-party connections") },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                modifier = Modifier.bounceClick(pressedScale = 0.97f) { Graph.signOut() },
            )
        } else {
            ListItem(
                headlineContent = { Text("Sign in with Google") },
                leadingContent = { Icon(Icons.Filled.AccountCircle, null) },
                modifier = Modifier.bounceClick(pressedScale = 0.97f, onClick = onSignIn),
            )
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.bounceClick(pressedScale = 0.97f) { onChange(!checked) },
    )
}
