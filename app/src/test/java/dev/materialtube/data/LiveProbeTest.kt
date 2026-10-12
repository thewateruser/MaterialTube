package dev.materialtube.data

import dev.materialtube.data.innertube.InnerTube
import dev.materialtube.data.innertube.Parser
import dev.materialtube.data.net.FallbackDns
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Diagnostics only: prints the TV channel page structure. Never fails. */
class LiveProbeTest {
    private val api = InnerTube(FallbackDns.client(), null)

    @Before
    fun onlyWhenEnabled() = assumeTrue(System.getenv("MATERIALTUBE_LIVE_TEST") == "1")

    private fun all(json: Any?, key: String, out: MutableList<JSONObject> = mutableListOf()): MutableList<JSONObject> {
        when (json) {
            is JSONObject -> json.keys().forEach { k ->
                val v = json.opt(k)
                if (k == key && v is JSONObject) out += v
                all(v, key, out)
            }
            is JSONArray -> for (i in 0 until json.length()) all(json.opt(i), key, out)
        }
        return out
    }

    @Test
    fun probeChannel() {
        val r = api.call("browse", JSONObject().put("browseId", "UCX6OQ3DkcsbYNE6H8uQQuVA"))
        val tabs = all(r, "tabRenderer")
        println("CHANNEL tabs=${tabs.size}")
        tabs.forEach { t ->
            val ep = all(t, "browseEndpoint").firstOrNull()
            println("  tab title=${t.opt("title")} selected=${t.opt("selected")} browseId=${ep?.optString("browseId")} params=${ep?.optString("params")} hasContent=${t.has("content")}")
        }
        println("CHANNEL header=${all(r, "channelHeaderRenderer").firstOrNull()?.let { h -> h.keys().asSequence().toList() }}")
        println("CHANNEL header title=${all(r, "channelHeaderRenderer").firstOrNull()?.opt("title")}")
        println("CHANNEL header sub=${all(r, "channelHeaderRenderer").firstOrNull()?.let { h -> listOf("subscriberCountText", "subtitle", "metadata").map { k -> "$k=${h.opt(k)?.toString()?.take(200)}" } }}")
        println("CHANNEL avatar keys=${all(r, "channelHeaderRenderer").firstOrNull()?.optJSONObject("avatar")?.toString()?.take(300)}")
        val page = Parser.channel(r)
        println("CHANNEL parsed title=${page.title} avatar=${page.avatar} channels=${page.feed.channels.map { it.title }}")
        page.feed.shelves.forEach { s -> println("  shelf '${s.title}' n=${s.items.size} shorts=${s.items.count { it.isShort }}") }
        // Try each tab
        tabs.mapNotNull { all(it, "browseEndpoint").firstOrNull() }.filter { it.optString("params").isNotEmpty() }.take(6).forEach { ep ->
            runCatching {
                val p = api.browse(ep.getString("browseId"), ep.getString("params"))
                println("  TAB params=${ep.optString("params").take(20)} shelves=${p.shelves.map { "${it.title}:${it.items.size}/${it.items.count { v -> v.isShort }}" }} cont=${p.continuation != null}")
            }.onFailure { println("  TAB params=${ep.optString("params").take(20)} error=${it.message}") }
        }
    }
}
