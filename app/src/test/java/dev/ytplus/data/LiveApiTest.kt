package dev.ytplus.data

import dev.ytplus.data.auth.InMemoryTokenStore
import dev.ytplus.data.auth.TvOAuth
import dev.ytplus.data.innertube.InnerTube
import okhttp3.MediaType.Companion.toMediaType
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
    fun dumpDetails() {
        val search = api.call("search", JSONObject().put("query", "lofi hip hop"))
        val types = sortedMapOf<String, Int>()
        var printed = 0
        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> {
                    n.optJSONObject("lockupViewModel")?.let { l ->
                        val key = "${l.optString("contentType")}|id=${l.has("contentId")}|parsed=${dev.ytplus.data.innertube.Parser.video(n) != null}"
                        types[key] = (types[key] ?: 0) + 1
                        if (printed < 2 && l.optString("contentType").contains("VIDEO")) {
                            printed++
                            println("lockup.contentImage=${l.optJSONObject("contentImage")?.toString()?.take(2500)}")
                            println("lockup.keys=${l.keys().asSequence().toList()}")
                        }
                    }
                    n.keys().forEach { walk(n.opt(it)) }
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(search)
        println("search lockups: $types")
        println("search parsed shelves: " + dev.ytplus.data.innertube.Parser.feed(search).shelves.map { "${it.title}:${it.items.size}" })

        val channel = api.call("browse", JSONObject().put("browseId", "UCuAXFkgsw1L7xaCfnd5JJOw"))
        sample("channel", channel, "channelHeaderRenderer")

        // Player variants: find what TVHTML5 needs to return a playable response + tracking URLs.
        val tvHtml = http.newCall(okhttp3.Request.Builder().url("https://www.youtube.com/tv")
            .header("User-Agent", TvOAuth.TV_USER_AGENT).build()).execute().use { it.body!!.string() }
        val cver = Regex("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"").find(tvHtml)?.groupValues?.get(1)
        val cverAlt = Regex("clientVersion\\?\":\\?\"(7\\.[0-9.]+)").find(tvHtml)?.groupValues?.get(1)
        val baseJs = Regex("src=\"([^\"]*base\\.js)\"").find(tvHtml)?.groupValues?.get(1)
            ?: Regex("\"jsUrl\":\"([^\"]+)\"").find(tvHtml)?.groupValues?.get(1)
        println("tv html: len=${tvHtml.length} cver=$cver cverAlt=$cverAlt baseJs=$baseJs scripts=${Regex("<script[^>]*src=\"([^\"]+)\"").findAll(tvHtml).map { it.groupValues[1] }.toList().take(8)}")
        var sts: Int? = null
        if (baseJs != null) {
            val js = http.newCall(okhttp3.Request.Builder().url(if (baseJs.startsWith("http")) baseJs else "https://www.youtube.com$baseJs")
                .header("User-Agent", TvOAuth.TV_USER_AGENT).build()).execute().use { it.body!!.string() }
            sts = Regex("(?:signatureTimestamp|sts)[:=](\\d{5})").find(js)?.groupValues?.get(1)?.toInt()
            println("base.js len=${js.length} sts=$sts clientId=${Regex("clientId:\"([^\"]+)\"").find(js)?.groupValues?.get(1)}")
        }
        fun tryPlayer(label: String, version: String?, withSts: Boolean) {
            val body = JSONObject().put("videoId", "dQw4w9WgXcQ")
            if (withSts && sts != null) body.put("playbackContext", JSONObject().put("contentPlaybackContext", JSONObject().put("signatureTimestamp", sts)))
            val ctx = api.context()
            if (version != null) ctx.getJSONObject("client").put("clientVersion", version)
            body.put("context", ctx)
            val req = okhttp3.Request.Builder().url("${InnerTube.BASE_URL}/player?prettyPrint=false")
                .post(okhttp3.RequestBody.create("application/json".toMediaType(), body.toString()))
                .header("User-Agent", TvOAuth.TV_USER_AGENT)
                .header("Origin", "https://www.youtube.com")
                .header("X-YouTube-Client-Name", "7")
                .header("X-YouTube-Client-Version", version ?: InnerTube.CLIENT_VERSION)
                .build()
            val r = http.newCall(req).execute().use { JSONObject(it.body!!.string()) }
            val ps = r.optJSONObject("playabilityStatus")
            println("player[$label] status=${ps?.optString("status")} reason=${ps?.optString("reason")} title=${r.optJSONObject("videoDetails")?.optString("title")} tracking=${r.has("playbackTracking")} streaming=${r.has("streamingData")}")
        }
        tryPlayer("default", null, false)
        tryPlayer("sts", null, true)
        tryPlayer("liveVersion", cver ?: cverAlt, false)
        tryPlayer("liveVersion+sts", cver ?: cverAlt, true)
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
        // Signed out, YouTube only returns a "sign in" nudge here; just check the call works.
        println("home (signed out): ${page.shelves.map { "${it.title}(${it.items.size})" }} cont=${page.continuation != null}")
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
