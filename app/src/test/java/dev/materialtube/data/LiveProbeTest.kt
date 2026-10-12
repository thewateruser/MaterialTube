package dev.materialtube.data

import dev.materialtube.data.innertube.InnerTube
import dev.materialtube.data.net.FallbackDns
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Prints response shapes of candidate endpoints (diagnostics only, never fails). */
class LiveProbeTest {
    private val http = FallbackDns.client()
    private val api = InnerTube(http, null)

    @Before
    fun onlyWhenEnabled() = assumeTrue(System.getenv("MATERIALTUBE_LIVE_TEST") == "1")

    private fun shape(json: JSONObject): String {
        val counts = sortedMapOf<String, Int>()
        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> n.keys().forEach { k ->
                    if (k.endsWith("Renderer") || k.endsWith("ViewModel") || k.endsWith("Endpoint")) counts[k] = (counts[k] ?: 0) + 1
                    walk(n.opt(k))
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(json)
        return counts.toString()
    }

    private fun sample(json: JSONObject, key: String, max: Int = 1500): String? {
        var found: Any? = null
        fun walk(n: Any?) {
            if (found != null) return
            when (n) {
                is JSONObject -> if (n.has(key)) found = n.get(key) else n.keys().forEach { walk(n.opt(it)) }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(json)
        return found?.toString()?.take(max)
    }

    private fun probe(label: String, f: () -> JSONObject) {
        val r = runCatching(f)
        r.onSuccess { println("PROBE[$label] ok shape=${shape(it)}") }
        r.onFailure { println("PROBE[$label] error=${it.message}") }
    }

    private val web = InnerTube.Client(
        "WEB", "1", "2.20251010.00.00",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
    )

    @Test
    fun probeShorts() {
        probe("tv FEshorts") { api.call("browse", JSONObject().put("browseId", "FEshorts")) }
        probe("web FEshorts") { api.call("browse", JSONObject().put("browseId", "FEshorts"), web, false) }
        probe("web reel_item_watch") {
            api.call("reel/reel_item_watch", JSONObject().put("params", "CA8%3D").put("disablePlayerResponse", true), web, false)
        }
        val reel = runCatching {
            api.call("reel/reel_item_watch", JSONObject().put("params", "CA8%3D").put("disablePlayerResponse", true), web, false)
        }.getOrNull()
        val seqParams = reel?.optString("sequenceContinuation")?.ifEmpty { null } ?: reel?.let { sample(it, "sequenceParams") }
        println("reel keys=${reel?.keys()?.asSequence()?.toList()} seq=${seqParams?.take(60)} replacement=${reel?.let { sample(it, "replacementEndpoint", 600) }}")
        if (seqParams != null) {
            probe("web reel_watch_sequence") {
                api.call("reel/reel_watch_sequence", JSONObject().put("sequenceParams", seqParams), web, false).also {
                    println("sequence sample=${sample(it, "entries", 1200)}")
                }
            }
        }
        val tvSearch = api.call("search", JSONObject().put("query", "mrbeast"))
        println("tv search mrbeast shape=${shape(tvSearch)}")
        println("tv search avatarLockup=${sample(tvSearch, "avatarLockupRenderer", 1500)}")
        println("tv search channel tile=${sample(tvSearch, "tileRenderer", 300)}")
        probe("tv FEchannels") { api.call("browse", JSONObject().put("browseId", "FEchannels")) }
    }
}
