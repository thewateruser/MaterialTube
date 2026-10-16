package dev.materialtube.data.innertube

import dev.materialtube.data.Shelf
import dev.materialtube.data.ChannelPage
import dev.materialtube.data.FeedPage
import dev.materialtube.data.NextInfo
import dev.materialtube.data.PlayerInfo
import dev.materialtube.data.auth.TvOAuth
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import dev.materialtube.data.net.isUnreachable
import java.io.IOException
import java.util.Locale

class InnerTubeException(message: String, val code: Int = 0) : IOException(message)

/**
 * Client for YouTube's private "InnerTube" API, speaking as the TV app (TVHTML5) so that the
 * OAuth tokens from [TvOAuth] are accepted. Everything that feeds the recommendation algorithm
 * (home, up next, subscriptions, history, likes, subscriptions) goes through here, signed in as
 * the user.
 */
class InnerTube(
    private val http: OkHttpClient,
    private val auth: TvOAuth?,
    private val locale: () -> Locale = { Locale.getDefault() },
    /** InnerTube hosts, tried in order when one is unreachable. */
    private val hosts: List<String> = HOSTS,
) {
    @Volatile private var workingHost = 0

    val isSignedIn get() = auth?.isSignedIn == true

    fun home(): FeedPage {
        // The TV app calls its home "default"; fall back to the web id if that ever goes away.
        val page = Parser.feed(call("browse", JSONObject().put("browseId", "default")))
        if (page.shelves.isNotEmpty()) return page
        return Parser.feed(call("browse", JSONObject().put("browseId", "FEwhat_to_watch")))
    }

    /** Subscription uploads plus the list of subscribed channels (for the channel row). */
    private val homeShorts = ShortsMixer(
        search = { shortsSearch(it) },
        more = { shortsSearchMore(it) },
        personal = { personalShorts() },
        personalMore = { reelSequenceMore(it, withAuth = true) },
    )

    /**
     * Shorts recommended to the signed-in account: those in the web home feed, else YouTube's
     * personal Shorts sequence (what the Shorts tab plays). Empty when signed out or when
     * neither works, so the mixer falls back to Shorts searches.
     */
    fun personalShorts(): FeedPage {
        if (auth?.isSignedIn != true) return FeedPage(emptyList(), null)
        val webHome = runCatching { Parser.feed(call("browse", JSONObject().put("browseId", "FEwhat_to_watch"), WEB)) }
            .getOrNull()?.shelves.orEmpty().flatMap { it.items }.filter { it.isShort }.distinctBy { it.id }
        val sequence = runCatching { reelSequence(withAuth = true) }.getOrNull()
        if (webHome.size >= 3) {
            // Keep the sequence's token so later rows continue with personal Shorts.
            return FeedPage(listOf(Shelf("Shorts", webHome)), sequence?.continuation)
        }
        return sequence?.takeIf { p -> p.shelves.sumOf { it.items.size } >= 3 } ?: FeedPage(emptyList(), null)
    }

    /** First page of the seedless Shorts sequence (the Shorts tab's feed). */
    fun reelSequence(withAuth: Boolean): FeedPage {
        val start = call(
            "reel/reel_item_watch",
            JSONObject()
                .put("params", "CA8=")
                .put("disablePlayerResponse", true)
                .put("inputType", "REEL_WATCH_INPUT_TYPE_SEEDLESS"),
            WEB, withAuth,
        )
        val token = findFirst(start) { it.has("sequenceContinuation") }?.str("sequenceContinuation")
            ?: throw InnerTubeException("No Shorts sequence")
        return reelSequenceMore(token, withAuth)
    }

    fun reelSequenceMore(token: String, withAuth: Boolean): FeedPage =
        Parser.reelSequence(call("reel/reel_watch_sequence", JSONObject().put("sequenceParams", token), WEB, withAuth))

    /**
     * Search used to find Shorts. The signed-in TV search doesn't always mark results as Shorts,
     * so fall back to the signed-out TV and web searches; the continuation token remembers which
     * one answered.
     */
    fun shortsSearch(query: String): FeedPage {
        val sources = listOf(TV to true, TV to false, WEB to false)
        for ((index, source) in sources.withIndex()) {
            val (client, withAuth) = source
            if (withAuth && auth?.isSignedIn != true) continue
            val page = runCatching { Parser.feed(call("search", JSONObject().put("query", query), client, withAuth)) }
                .getOrNull() ?: continue
            if (page.shelves.sumOf { s -> s.items.count { it.isShort } } >= 3) {
                return page.copy(continuation = page.continuation?.let { "$index|$it" })
            }
        }
        return FeedPage(emptyList(), null)
    }

    fun shortsSearchMore(token: String): FeedPage {
        val index = token.substringBefore('|').toIntOrNull() ?: return FeedPage(emptyList(), null)
        val (client, withAuth) = listOf(TV to true, TV to false, WEB to false)[index]
        val page = Parser.feed(call("search", JSONObject().put("continuation", token.substringAfter('|')), client, withAuth))
        return page.copy(continuation = page.continuation?.let { "$index|$it" })
    }

    /** Home feed with Shorts rows mixed in regularly (see [ShortsMixer]). */
    fun homeWithShorts(): FeedPage {
        homeShorts.reset()
        return homeShorts.mix(home())
    }

    /** Home feed with no Shorts at all (they weren't personal enough to be worth showing). */
    fun homeWithoutShorts(): FeedPage = home().withoutShorts()

    fun homeContinuationWithoutShorts(token: String): FeedPage = continuation(token).withoutShorts()

    private fun FeedPage.withoutShorts() =
        copy(shelves = shelves.map { s -> s.copy(items = s.items.filter { !it.isShort }) }.filter { it.items.isNotEmpty() })

    /** Next home page, with more Shorts rows mixed in. */
    fun homeContinuation(token: String): FeedPage = homeShorts.mix(continuation(token))

    /** A single row of Shorts (from the Shorts search), e.g. for testing. */
    fun shortsShelf(): dev.materialtube.data.Shelf? {
        val items = shortsSearch("shorts").shelves.flatMap { it.items }.filter { it.isShort }.distinctBy { it.id }
        return if (items.size >= 3) dev.materialtube.data.Shelf("Shorts", items.take(30)) else null
    }

    fun subscriptions(): FeedPage {
        val page = browse("FEsubscriptions")
        return page.copy(channels = subscribedChannels(page.channels))
    }

    /**
     * The channels you're subscribed to. The TV subscriptions page doesn't always list them, so
     * try, in order: that page, the TV and web channel lists, and the web sidebar.
     */
    fun subscribedChannels(fromFeed: List<dev.materialtube.data.ChannelItem> = emptyList()): List<dev.materialtube.data.ChannelItem> {
        if (fromFeed.size >= 2) return fromFeed
        val sources: List<() -> List<dev.materialtube.data.ChannelItem>> = listOf(
            { Parser.channels(call("browse", JSONObject().put("browseId", "FEchannels"))) },
            { Parser.channels(call("browse", JSONObject().put("browseId", "FEchannels"), WEB)) },
            { Parser.channels(call("guide", JSONObject(), WEB)) },
        )
        for (source in sources) {
            val channels = runCatching(source).getOrDefault(emptyList())
            if (channels.size >= 2) return channels
        }
        return fromFeed
    }

    /** Plain-text summary of what each subscriptions source returns, for bug reports. */
    fun subscriptionsDiagnostics(): String = buildString {
        fun section(name: String, fetch: () -> JSONObject) {
            append("== ").append(name).append('\n')
            val root = try {
                fetch()
            } catch (e: Exception) {
                append("error: ").append(e.message).append("\n\n")
                return
            }
            val channels = Parser.channels(root)
            append("channels: ").append(channels.size).append(' ')
                .append(channels.take(5).map { "${it.title}(${it.id.take(6)})" }).append('\n')
            val counts = sortedMapOf<String, Int>()
            fun countKeys(node: Any?) {
                when (node) {
                    is JSONObject -> node.keys().forEach { k ->
                        if (k.endsWith("Renderer") || k.endsWith("ViewModel")) counts[k] = (counts[k] ?: 0) + 1
                        countKeys(node.opt(k))
                    }
                    is org.json.JSONArray -> for (i in 0 until node.length()) countKeys(node.opt(i))
                }
            }
            countKeys(root)
            append("renderers: ").append(counts).append('\n')
            val tabs = mutableListOf<String>()
            fun tabsIn(node: Any?) {
                when (node) {
                    is JSONObject -> node.keys().forEach { k ->
                        val v = node.opt(k)
                        if (k == "tabRenderer" && v is JSONObject) {
                            val keys = v.keys().asSequence().filter { it != "content" }.toList()
                            val ids = mutableListOf<String>()
                            fun ids(n: Any?) {
                                when (n) {
                                    is JSONObject -> n.keys().forEach { kk ->
                                        if (kk != "content") {
                                            if (kk == "browseId") ids += n.optString(kk)
                                            ids(n.opt(kk))
                                        }
                                    }
                                    is org.json.JSONArray -> for (i in 0 until n.length()) ids(n.opt(i))
                                }
                            }
                            ids(v)
                            tabs += "${text(v.opt("title"))} keys=$keys ids=$ids"
                        }
                        tabsIn(v)
                    }
                    is org.json.JSONArray -> for (i in 0 until node.length()) tabsIn(node.opt(i))
                }
            }
            tabsIn(root)
            if (tabs.isNotEmpty()) append("tabs:\n").append(tabs.take(15).joinToString("\n") { "  $it" }).append('\n')
            append('\n')
        }
        append("signed in: ").append(auth?.isSignedIn == true).append("\n\n")
        section("TV FEsubscriptions") { call("browse", JSONObject().put("browseId", "FEsubscriptions")) }
        section("TV FEchannels") { call("browse", JSONObject().put("browseId", "FEchannels")) }
        section("WEB FEchannels") { call("browse", JSONObject().put("browseId", "FEchannels"), WEB) }
        section("WEB guide") { call("guide", JSONObject(), WEB) }
        // Personal Shorts sources used for the home Shorts rows.
        append("== Shorts sources\n")
        val webHome = runCatching { Parser.feed(call("browse", JSONObject().put("browseId", "FEwhat_to_watch"), WEB)) }
        append("web home: ").append(
            webHome.exceptionOrNull()?.message ?: "${webHome.getOrNull()?.shelves?.sumOf { it.items.size }} items, " +
                "${webHome.getOrNull()?.shelves?.sumOf { s -> s.items.count { it.isShort } }} shorts",
        ).append('\n')
        val seq = runCatching { reelSequence(withAuth = true) }
        append("reel sequence: ").append(
            seq.exceptionOrNull()?.message ?: "${seq.getOrNull()?.shelves?.sumOf { it.items.size }} shorts, next=${seq.getOrNull()?.continuation != null}",
        ).append('\n')
    }

    fun history(): FeedPage = browse("FEhistory")

    fun likedVideos(): FeedPage = browse("VLLL")

    fun watchLater(): FeedPage = browse("VLWL")

    fun browse(browseId: String, params: String? = null): FeedPage =
        Parser.feed(call("browse", JSONObject().put("browseId", browseId).apply { params?.let { put("params", it) } }))

    fun channel(channelId: String): ChannelPage =
        Parser.channel(call("browse", JSONObject().put("browseId", channelId)))

    /**
     * A channel tab (Videos / Shorts / Live). The TV app has no tabs, so this uses the standard
     * tab params, first as the TV client and then as the web client if TV returns nothing.
     */
    /**
     * A channel tab (Videos / Shorts / Live). The TV client ignores tab params and returns the
     * channel home, so tabs come from the web client; its continuations need the web client too.
     */
    fun channelTab(channelId: String, tab: dev.materialtube.data.ChannelTab): FeedPage {
        val params = tab.params ?: return channel(channelId).feed
        val body = JSONObject().put("browseId", channelId).put("params", java.net.URLDecoder.decode(params, "UTF-8"))
        return Parser.feed(call("browse", body, WEB, withAuth = false)).withShortsFlag(tab)
    }

    /** The web channel page (tab list + About token), cached briefly since Posts/About both need it. */
    private val webChannelPages = object : LinkedHashMap<String, JSONObject>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JSONObject>?) = size > 8
    }

    private fun webChannelPage(channelId: String): JSONObject {
        synchronized(webChannelPages) { webChannelPages[channelId] }?.let { return it }
        val page = call("browse", JSONObject().put("browseId", channelId), WEB, withAuth = false)
        synchronized(webChannelPages) { webChannelPages[channelId] = page }
        return page
    }

    /** Titles of the channel's tabs on the web (e.g. "Releases" for musicians). */
    fun channelTabTitles(channelId: String): Set<String> = Parser.channelTabs(webChannelPage(channelId)).keys

    /** A musician channel's Releases tab: albums, singles and EPs (as playlists). */
    fun channelReleases(channelId: String): List<dev.materialtube.data.MusicItem> {
        val tabs = Parser.channelTabs(webChannelPage(channelId))
        val params = tabs["Releases"] ?: tabs.entries.firstOrNull { it.key.contains("release", true) }?.value
            ?: return emptyList()
        return Parser.releases(call("browse", JSONObject().put("browseId", channelId).put("params", params), WEB, withAuth = false))
    }

    /** Community posts. Empty when the channel has no Posts tab. */
    fun channelPosts(channelId: String): dev.materialtube.data.PostsPage {
        val tabs = Parser.channelTabs(webChannelPage(channelId))
        val params = tabs["Posts"] ?: tabs["Community"]
            ?: tabs.entries.firstOrNull { (title, _) -> title.contains("post", true) || title.contains("community", true) }?.value
            ?: return dev.materialtube.data.PostsPage(emptyList(), null)
        val body = { JSONObject().put("browseId", channelId).put("params", params) }
        // Signed in, the posts carry the like actions (signed out they only offer "sign in").
        if (auth?.isSignedIn == true) {
            runCatching { Parser.posts(call("browse", body(), WEB, withAuth = true)) }
                .getOrNull()?.takeIf { it.posts.isNotEmpty() }?.let { return it }
        }
        return Parser.posts(call("browse", body(), WEB, withAuth = false))
    }

    fun channelPostsContinuation(token: String): dev.materialtube.data.PostsPage {
        if (auth?.isSignedIn == true) {
            runCatching { Parser.posts(call("browse", JSONObject().put("continuation", token), WEB, withAuth = true)) }
                .getOrNull()?.takeIf { it.posts.isNotEmpty() }?.let { return it }
        }
        return Parser.posts(call("browse", JSONObject().put("continuation", token), WEB, withAuth = false))
    }

    /** First page of a community post's comments. */
    fun postComments(post: dev.materialtube.data.Post): dev.materialtube.data.PostCommentsPage {
        val browseId = post.detailBrowseId ?: throw InnerTubeException("This post has no comments")
        val body = JSONObject().put("browseId", browseId)
        post.detailParams?.let { body.put("params", it) }
        val page = call("browse", body, WEB, withAuth = false)
        val token = Parser.postCommentsToken(page) ?: return dev.materialtube.data.PostCommentsPage(emptyList(), null)
        return postCommentsMore(token)
    }

    /** More comments, or a comment's replies (both are continuation tokens). */
    fun postCommentsMore(token: String): dev.materialtube.data.PostCommentsPage =
        Parser.postComments(call("browse", JSONObject().put("continuation", token), WEB, withAuth = false))

    /** Likes (or removes the like from) a community post. Needs sign-in. */
    fun likePost(post: dev.materialtube.data.Post, like: Boolean) {
        val action = (if (like) post.likeAction else post.unlikeAction)
            ?: throw InnerTubeException("This post can't be liked here")
        action("comment/perform_comment_action", JSONObject().put("actions", JSONArray().put(action)), WEB)
    }

    /** Description, stats and links from the channel's About panel. */
    fun channelAbout(channelId: String): dev.materialtube.data.ChannelAbout {
        val page = webChannelPage(channelId)
        val fallback = page.obj("metadata", "channelMetadataRenderer")?.str("description")
        // The header's "more" link opens the About panel via a continuation token.
        val token = findFirst(page.opt("header")) { it.has("continuationCommand") }
            ?.obj("continuationCommand")?.str("token")
        val about = token?.let { t ->
            runCatching { call("browse", JSONObject().put("continuation", t), WEB, withAuth = false) }.getOrNull()
        }
        return Parser.about(about, fallback)
    }

    fun channelTabContinuation(token: String): FeedPage =
        Parser.feed(call("browse", JSONObject().put("continuation", token), WEB, withAuth = false))

    private fun FeedPage.withShortsFlag(tab: dev.materialtube.data.ChannelTab) =
        if (tab == dev.materialtube.data.ChannelTab.Shorts) {
            copy(shelves = shelves.map { s -> s.copy(items = s.items.map { it.copy(isShort = true) }) })
        } else {
            this
        }

    /** Next page of any feed returned by this client. */
    fun continuation(token: String, endpoint: String = "browse"): FeedPage =
        Parser.feed(call(endpoint, JSONObject().put("continuation", token)))

    fun search(query: String): FeedPage = Parser.feed(call("search", JSONObject().put("query", query)))

    fun searchContinuation(token: String): FeedPage = continuation(token, "search")

    /**
     * Comment sort options ("Top comments" / "Newest first") as continuation tokens. Comes from the
     * web client's comments header; the tokens are then paged through NewPipeExtractor.
     */
    fun commentSorts(videoId: String): List<dev.materialtube.data.CommentSort> {
        val next = call("next", JSONObject().put("videoId", videoId), WEB, withAuth = false)
        val token = Parser.commentsToken(next) ?: return emptyList()
        val header = call("next", JSONObject().put("continuation", token), WEB, withAuth = false)
        return Parser.commentSorts(header)
    }

    fun next(videoId: String): NextInfo =
        Parser.next(call("next", JSONObject().put("videoId", videoId)), videoId)

    /**
     * Player request. Streams come from NewPipeExtractor; this call is for the video details and,
     * more importantly, the watch-history tracking URLs.
     *
     * The TV client's player endpoint currently answers "The page needs to be reloaded", so we
     * try a few clients (signed in first, then anonymously) until one returns tracking URLs.
     * Watch-time pings are then sent signed in, so they still count for the account.
     */
    fun player(videoId: String): PlayerInfo {
        var fallback: PlayerInfo? = null
        val signedIn = auth?.isSignedIn == true
        val attempts = PLAYER_CLIENTS.flatMap { c -> if (signedIn) listOf(c to true, c to false) else listOf(c to false) }
        for ((client, withAuth) in attempts) {
            val body = JSONObject()
                .put("videoId", videoId)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true)
            val info = runCatching {
                Parser.player(call("player", body, client, withAuth))
            }.getOrNull()?.let { it.copy(tracking = it.tracking?.copy(clientName = client.name, clientVersion = client.version)) }
                ?: continue
            if (info.tracking?.playbackUrl != null && info.details != null) return info
            if (fallback == null || fallback.details == null) fallback = info
        }
        return fallback ?: PlayerInfo(null, null, null)
    }

    fun like(videoId: String) = action("like/like", JSONObject().put("target", JSONObject().put("videoId", videoId)))

    fun dislike(videoId: String) = action("like/dislike", JSONObject().put("target", JSONObject().put("videoId", videoId)))

    fun removeLike(videoId: String) = action("like/removelike", JSONObject().put("target", JSONObject().put("videoId", videoId)))

    fun subscribe(channelId: String) = action("subscription/subscribe", JSONObject().put("channelIds", JSONArray().put(channelId)))

    fun unsubscribe(channelId: String) = action("subscription/unsubscribe", JSONObject().put("channelIds", JSONArray().put(channelId)))

    private fun action(endpoint: String, body: JSONObject, client: Client = TV) {
        if (auth?.isSignedIn != true) throw InnerTubeException("Sign in first")
        call(endpoint, body, client)
    }

    fun context(client: Client = TV): JSONObject {
        val l = locale()
        val c = JSONObject()
            .put("clientName", client.name)
            .put("clientVersion", client.version)
            .put("hl", l.language.ifEmpty { "en" })
            .put("gl", l.country.ifEmpty { "US" })
            .put("userAgent", client.userAgent)
        client.extra.forEach { (k, v) -> c.put(k, v) }
        return JSONObject().put("client", c).put("user", JSONObject().put("lockedSafetyMode", false))
    }

    /**
     * [visitorData] ties a request to an earlier response's session (YouTube Music only serves
     * continuations to the visitor that got the first page).
     */
    fun call(
        endpoint: String,
        body: JSONObject,
        client: Client = TV,
        withAuth: Boolean = true,
        visitorData: String? = null,
    ): JSONObject {
        body.put("context", context(client).also { c -> visitorData?.let { c.getJSONObject("client").put("visitorData", it) } })
        val token = if (withAuth) auth?.accessToken() else null
        return try {
            execute(endpoint, body, client, token)
        } catch (e: InnerTubeException) {
            // Access token revoked/expired early: refresh once and retry.
            if (e.code != 401 || token == null || auth == null) throw e
            execute(endpoint, body, client, auth.accessToken(forceRefresh = true))
        }
    }

    private fun execute(endpoint: String, body: JSONObject, client: Client, token: String?): JSONObject {
        client.hosts?.let { own ->
            var lastError: java.io.IOException? = null
            for (host in own) {
                try {
                    return execute(host, endpoint, body, client, token)
                } catch (e: java.io.IOException) {
                    if (e is InnerTubeException || !e.isUnreachable()) throw e
                    lastError = e
                }
            }
            throw lastError ?: InnerTubeException("No host reachable for ${client.name}")
        }
        var lastError: java.io.IOException? = null
        for (i in hosts.indices) {
            val index = (workingHost + i) % hosts.size
            try {
                return execute(hosts[index], endpoint, body, client, token).also { workingHost = index }
            } catch (e: java.io.IOException) {
                if (e is InnerTubeException || !e.isUnreachable()) throw e
                lastError = e
            }
        }
        throw lastError ?: InnerTubeException("No InnerTube host reachable")
    }

    private fun execute(host: String, endpoint: String, body: JSONObject, client: Client, token: String?): JSONObject {
        val request = Request.Builder()
            .url("https://$host/youtubei/v1/$endpoint${if ('?' in endpoint) "&" else "?"}prettyPrint=false")
            .post(body.toString().toRequestBody(JSON))
            .header("User-Agent", client.userAgent)
            .header("Origin", client.origin)
            .header("Referer", client.origin + "/")
            .header("X-YouTube-Client-Name", client.id)
            .header("X-YouTube-Client-Version", client.version)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val msg = runCatching { JSONObject(text).obj("error")?.str("message") }.getOrNull()
                throw InnerTubeException("$endpoint: HTTP ${response.code}${msg?.let { " – $it" } ?: ""}", response.code)
            }
            return JSONObject(text)
        }
    }

    /** An InnerTube client identity. */
    data class Client(
        val name: String,
        val id: String,
        val version: String,
        val userAgent: String,
        val extra: Map<String, Any> = emptyMap(),
        /** Hosts for this client (null: the default InnerTube hosts). */
        val hosts: List<String>? = null,
        val origin: String = "https://www.youtube.com",
    )

    companion object {
        /** youtubei.googleapis.com serves the same API (it's what the official Android app uses). */
        val HOSTS = listOf("www.youtube.com", "youtubei.googleapis.com")
        const val CLIENT_NAME = "TVHTML5"
        const val CLIENT_VERSION = "7.20261007.13.00"
        private val JSON = "application/json".toMediaType()

        /** The TV app: the client the OAuth token belongs to; used for feeds and actions. */
        val TV = Client(CLIENT_NAME, "7", CLIENT_VERSION, TvOAuth.TV_USER_AGENT)

        val WEB = Client(
            "WEB", "1", "2.20251010.00.00",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
        )

        /** YouTube Music's web client. */
        val MUSIC = Client(
            "WEB_REMIX", "67", "1.20261006.01.00",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
            hosts = listOf("music.youtube.com", "youtubei.googleapis.com"),
            origin = "https://music.youtube.com",
        )

        /** Clients that currently return playback tracking from the player endpoint. */
        val PLAYER_CLIENTS = listOf(
            TV,
            Client(
                "MWEB", "2", "2.20251010.01.00",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36",
            ),
            Client(
                "IOS", "5", "20.10.4",
                "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
                mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iPhone", "osVersion" to "18.3.2.22D82"),
            ),
            Client(
                "ANDROID_VR", "28", "1.65.10",
                "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                mapOf("androidSdkVersion" to 32, "osName" to "Android", "osVersion" to "12L"),
            ),
        )
    }
}
