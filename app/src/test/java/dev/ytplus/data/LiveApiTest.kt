package dev.ytplus.data

import dev.ytplus.data.auth.InMemoryTokenStore
import dev.ytplus.data.auth.TvOAuth
import dev.ytplus.data.innertube.InnerTube
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Hits the real YouTube endpoints anonymously to catch API/layout changes. Runs only when
 * YTPLUS_LIVE_TEST=1 (CI sets it in a separate, non-blocking step).
 */
class LiveApiTest {
    private val http = OkHttpClient()
    private val auth = TvOAuth(http, InMemoryTokenStore())
    private val api = InnerTube(http, auth)

    @Before
    fun onlyWhenEnabled() = assumeTrue(System.getenv("YTPLUS_LIVE_TEST") == "1")

    /** Prints which renderers a response contains, to adapt the parser when YouTube changes layouts. */
    private fun shape(label: String, json: JSONObject) {
        val counts = sortedMapOf<String, Int>()
        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> n.keys().forEach { k ->
                    if (k.endsWith("Renderer") || k.endsWith("ViewModel") || k.endsWith("Continuation")) counts[k] = (counts[k] ?: 0) + 1
                    walk(n.opt(k))
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(json)
        println("shape[$label] top=${json.keys().asSequence().toList()} renderers=$counts")
    }

    private fun sample(label: String, json: JSONObject, key: String) {
        var found: JSONObject? = null
        fun walk(n: Any?) {
            if (found != null) return
            when (n) {
                is JSONObject -> { if (n.has(key)) found = n.getJSONObject(key) else n.keys().forEach { walk(n.opt(it)) } }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(json)
        println("sample[$label/$key]=${found?.toString()?.take(3000)}")
    }

    @Test
    fun dumpShapes() {
        val home = api.call("browse", JSONObject().put("browseId", "default"))
        shape("home-default", home)
        shape("home-FEwhat_to_watch", api.call("browse", JSONObject().put("browseId", "FEwhat_to_watch")))
        val search = api.call("search", JSONObject().put("query", "lofi hip hop"))
        shape("search", search)
        sample("search", search, "tileRenderer")
        sample("search", search, "lockupViewModel")
        val player = api.call("player", JSONObject().put("videoId", "dQw4w9WgXcQ"))
        println("player playability=${player.optJSONObject("playabilityStatus")} keys=${player.keys().asSequence().toList()}")
        val channel = api.call("browse", JSONObject().put("browseId", "UCuAXFkgsw1L7xaCfnd5JJOw"))
        shape("channel", channel)
        println("channel parsed: " + dev.ytplus.data.innertube.Parser.channel(channel).let { "${it.title} ${it.feed.shelves.map { s -> s.items.size }}" })
    }

    @Test
    fun deviceCodeFlowStarts() {
        val code = auth.startDeviceFlow()
        println("device flow ok: user_code=${code.userCode} url=${code.verificationUrl}")
        assertTrue(code.userCode.isNotBlank())
    }

    @Test
    fun searchReturnsVideos() {
        val page = api.search("lofi hip hop")
        val items = page.shelves.flatMap { it.items }
        println("search: ${items.size} videos, first=${items.firstOrNull()}")
        assertTrue(items.isNotEmpty())
    }

    @Test
    fun homeReturnsShelves() {
        val page = api.home()
        println("home: ${page.shelves.map { "${it.title}(${it.items.size})" }} cont=${page.continuation != null}")
        assertTrue(page.shelves.isNotEmpty())
    }

    @Test
    fun nextReturnsRelated() {
        val next = api.next("dQw4w9WgXcQ")
        println("next: related=${next.related.size} channel=${next.channelName}/${next.channelId}")
        assertTrue(next.related.isNotEmpty())
    }

    @Test
    fun playerReturnsDetails() {
        val info = api.player("dQw4w9WgXcQ")
        println("player: status=${info.playabilityStatus} title=${info.details?.title} tracking=${info.tracking != null}")
        assertTrue(info.details != null)
    }
}
