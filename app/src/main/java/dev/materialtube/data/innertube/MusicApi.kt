package dev.materialtube.data.innertube

import dev.materialtube.data.MusicItem
import dev.materialtube.data.MusicPage
import dev.materialtube.data.MusicSearchFilter
import org.json.JSONObject

/**
 * YouTube Music. Signed in, requests go through whichever Music client accepts the account
 * (web, Android or iOS app; checked with YouTube's own "logged_in" flag), so Home is your music
 * recommendations. If none does, requests fall back to signed out so the tab still works.
 */
class MusicApi(private val api: InnerTube) {

    private val accountClients = listOf(InnerTube.MUSIC, InnerTube.MUSIC_ANDROID, InnerTube.MUSIC_IOS)

    /** The client that accepted the account, once known. */
    @Volatile private var accountClient: InnerTube.Client? = null
    /** When we last found that no client accepts the account (retried after a while). */
    @Volatile private var noAccountClientAt = 0L

    /**
     * The visitor session, client and sign-in state of the last first-page request. YouTube
     * Music only serves continuations to the same visitor, so "more" requests reuse them.
     */
    @Volatile private var visitorData: String? = null
    @Volatile private var lastClient: InnerTube.Client = InnerTube.MUSIC
    @Volatile private var lastSignedIn = false

    private fun raw(endpoint: String, body: JSONObject, client: InnerTube.Client, withAuth: Boolean): JSONObject {
        val r = api.call(endpoint, body, client, withAuth, visitorData)
        r.obj("responseContext")?.str("visitorData")?.let { visitorData = it }
        return r
    }

    /** YouTube's own flag for whether the request counted as signed in (null when absent). */
    private fun loggedIn(r: JSONObject): Boolean? =
        findFirst(r.opt("responseContext")) { it.str("key") == "logged_in" }?.str("value")?.let { it == "1" }

    /** A signed-in request through the first Music client that accepts the account, or null. */
    private fun signedIn(endpoint: String, body: () -> JSONObject, accept: (JSONObject) -> Boolean = { true }): JSONObject? {
        if (!api.isSignedIn) return null
        val known = accountClient
        val candidates = if (known != null) listOf(known) + (accountClients - known) else {
            if (System.currentTimeMillis() - noAccountClientAt < 10 * 60_000) return null
            accountClients
        }
        for (client in candidates) {
            val r = runCatching { raw(endpoint, body(), client, withAuth = true) }.getOrNull() ?: continue
            if (loggedIn(r) == false || !accept(r)) continue
            accountClient = client
            lastClient = client
            lastSignedIn = true
            return r
        }
        if (known == null) noAccountClientAt = System.currentTimeMillis()
        return null
    }

    private fun signedOut(endpoint: String, body: JSONObject): JSONObject {
        lastClient = InnerTube.MUSIC
        lastSignedIn = false
        return raw(endpoint, body, InnerTube.MUSIC, withAuth = false)
    }

    private fun call(endpoint: String, body: JSONObject): JSONObject {
        val json = body.toString()
        return signedIn(endpoint, { JSONObject(json) }) ?: signedOut(endpoint, JSONObject(json))
    }

    private fun browse(browseId: String, params: String? = null): MusicPage {
        fun body() = JSONObject().put("browseId", browseId).also { b -> params?.let { b.put("params", it) } }
        // An accepted-but-empty signed-in answer also falls back to the signed-out page.
        signedIn("browse", ::body) { MusicParser.page(it).shelves.isNotEmpty() }?.let { return MusicParser.page(it) }
        return MusicParser.page(signedOut("browse", body()))
    }

    private fun continued(endpoint: String, token: String): MusicPage {
        val viaQuery = runCatching {
            MusicParser.page(raw("$endpoint?ctoken=$token&continuation=$token&type=next", JSONObject(), lastClient, lastSignedIn))
        }.getOrNull()
        if (viaQuery != null && viaQuery.shelves.isNotEmpty()) return viaQuery
        return MusicParser.page(raw(endpoint, JSONObject().put("continuation", token), lastClient, lastSignedIn))
    }

    /** Next page of any browse result (home, explore, long playlists). */
    fun more(token: String): MusicPage = continued("browse", token)

    fun home(): MusicPage = browse("FEmusic_home")

    fun explore(): MusicPage = browse("FEmusic_explore")

    fun newReleases(): MusicPage = browse("FEmusic_new_releases_albums")

    fun charts(): MusicPage = browse("FEmusic_charts")

    fun search(query: String, filter: MusicSearchFilter = MusicSearchFilter.All): MusicPage {
        val body = JSONObject().put("query", query)
        filter.params?.let { body.put("params", it) }
        return MusicParser.page(call("search", body))
    }

    fun searchMore(token: String): MusicPage = continued("search", token)

    /** Album (MPREb_…), playlist (VL…) or artist (UC…) page, or a shelf's "more" page. */
    fun page(browseId: String, params: String? = null): MusicPage = browse(browseId, params)

    /**
     * The queue that plays after [videoId]: the rest of [playlistId] when given (an album or
     * playlist), otherwise YouTube Music's radio for the song.
     */
    fun queue(videoId: String?, playlistId: String?): Pair<List<MusicItem>, String?> {
        val body = JSONObject()
            .put("isAudioOnly", true)
            .put("enablePersistentPlaylistPanel", true)
            .put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
        videoId?.let { body.put("videoId", it) }
        body.put("playlistId", playlistId ?: "RDAMVM$videoId")
        return MusicParser.queue(call("next", body))
    }

    fun queueMore(token: String, playlistId: String): Pair<List<MusicItem>, String?> =
        MusicParser.queue(
            call(
                "next",
                JSONObject().put("continuation", token).put("playlistId", playlistId)
                    .put("isAudioOnly", true).put("enablePersistentPlaylistPanel", true),
            ),
        )

    /** Library sections (need sign-in): liked songs, playlists, albums, artists. */
    fun library(section: LibrarySection): MusicPage {
        if (!api.isSignedIn) throw InnerTubeException("Sign in to see your music library")
        signedIn("browse", { JSONObject().put("browseId", section.browseId) })?.let { r ->
            val page = MusicParser.page(r)
            if (page.shelves.isNotEmpty()) return page
        }
        // YouTube Music didn't take the account: liked music is also a regular playlist, which
        // the TV client (the account's own client) can read.
        if (section == LibrarySection.Liked) {
            val feed = api.browse("VLLM")
            val songs = feed.shelves.flatMap { it.items }.map { v ->
                MusicItem(
                    id = v.id,
                    type = dev.materialtube.data.MusicType.Song,
                    title = v.title,
                    subtitle = v.channel,
                    thumbnail = v.thumbnail,
                    artists = listOfNotNull(v.channel?.let { dev.materialtube.data.MusicArtistRef(it, v.channelId) }),
                    duration = v.duration,
                )
            }
            return MusicPage(listOf(dev.materialtube.data.MusicShelf(null, songs)), feed.continuation)
        }
        throw InnerTubeException("YouTube Music didn't accept your sign-in for this. Settings → Copy diagnostics shows what it returned.")
    }

    private fun rendererCounts(json: Any?, out: MutableMap<String, Int> = sortedMapOf()): Map<String, Int> {
        when (json) {
            is JSONObject -> json.keys().forEach { k ->
                if (k.endsWith("Renderer") || k.endsWith("ViewModel")) out[k] = (out[k] ?: 0) + 1
                rendererCounts(json.opt(k), out)
            }
            is org.json.JSONArray -> for (i in 0 until json.length()) rendererCounts(json.opt(i), out)
        }
        return out
    }

    /** What each Music client returns for this account (for Settings → Copy diagnostics). */
    fun diagnostics(): String = buildString {
        append("== Music sign-in (signed in: ${api.isSignedIn})\n")
        for (client in accountClients) {
            for ((label, id) in listOf("home" to "FEmusic_home", "liked" to "VLLM", "playlists" to "FEmusic_liked_playlists")) {
                val r = runCatching { api.call("browse", JSONObject().put("browseId", id), client, withAuth = api.isSignedIn) }
                append(client.name).append(' ').append(label).append(": ")
                r.onFailure { append("error ").append(it.message) }
                r.onSuccess { json ->
                    val page = MusicParser.page(json)
                    append("logged_in=").append(loggedIn(json)).append(", ")
                        .append(page.shelves.size).append(" shelves, ")
                        .append(page.shelves.sumOf { it.items.size }).append(" items ")
                        .append(page.shelves.take(3).map { it.title })
                    if (page.shelves.isEmpty()) append(" renderers=").append(rendererCounts(json))
                }
                append('\n')
            }
        }
        append("using: ${accountClient?.name ?: "none"}\n")
    }
    enum class LibrarySection(val label: String, val browseId: String) {
        Liked("Liked music", "VLLM"),
        Playlists("Playlists", "FEmusic_liked_playlists"),
        Albums("Albums", "FEmusic_liked_albums"),
        Artists("Artists", "FEmusic_library_corpus_track_artists"),
    }
}
