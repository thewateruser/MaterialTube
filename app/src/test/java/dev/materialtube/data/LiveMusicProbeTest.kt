package dev.materialtube.data

import dev.materialtube.data.innertube.InnerTube
import dev.materialtube.data.innertube.Parser
import dev.materialtube.data.net.FallbackDns
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Diagnostics only: prints the structure of a few music responses. Never fails. */
class LiveMusicProbeTest {
    private val api = InnerTube(FallbackDns.client(), null)

    @Before
    fun onlyWhenEnabled() = assumeTrue(System.getenv("MATERIALTUBE_LIVE_TEST") == "1")

    private fun counts(json: Any?, out: MutableMap<String, Int> = sortedMapOf()): Map<String, Int> {
        when (json) {
            is JSONObject -> json.keys().forEach { k ->
                if (k.endsWith("Renderer") || k.endsWith("ViewModel") || k.endsWith("Continuation") || k == "continuationContents" || k == "onResponseReceivedActions") {
                    out[k] = (out[k] ?: 0) + 1
                }
                counts(json.opt(k), out)
            }
            is JSONArray -> for (i in 0 until json.length()) counts(json.opt(i), out)
        }
        return out
    }

    private fun path(json: Any?, key: String, at: String = "", out: MutableList<String> = mutableListOf()): List<String> {
        when (json) {
            is JSONObject -> json.keys().forEach { k ->
                if (k == key && out.size < 3) out += "$at/$k"
                path(json.opt(k), key, "$at/$k", out)
            }
            is JSONArray -> for (i in 0 until json.length()) path(json.opt(i), key, "$at[$i]", out)
        }
        return out
    }

    @Test
    fun probe() {
        runCatching {
            val id = "UCqECaJ8Gagnn7YCbPEzWH6g"
            val page = api.call("browse", JSONObject().put("browseId", id), InnerTube.WEB, withAuth = false)
            val params = Parser.channelTabs(page)["Releases"]
            val r = api.call("browse", JSONObject().put("browseId", id).put("params", params), InnerTube.WEB, withAuth = false)
            println("PROBE RELEASES ${counts(r)}")
            println("PROBE RELEASES lockup paths ${path(r, "lockupViewModel")} ${path(r, "contentType")}")
            val lockup = Regex("\"contentType\":\"[A-Z_]+\"").findAll(r.toString()).map { it.value }.toSet()
            println("PROBE RELEASES types $lockup")
        }.onFailure { println("PROBE RELEASES failed $it") }

        runCatching {
            val home = api.call("browse", JSONObject().put("browseId", "FEmusic_home"), InnerTube.MUSIC, withAuth = false)
            println("PROBE HOME continuation paths ${path(home, "nextContinuationData")} ${path(home, "continuationCommand")}")
            val token = dev.materialtube.data.innertube.MusicParser.continuation(home)
            val more = api.call("browse?ctoken=$token&continuation=$token&type=next", JSONObject(), InnerTube.MUSIC, withAuth = false)
            println("PROBE HOME MORE (ctoken) keys=${more.keys().asSequence().toList()} ${counts(more)}")
            val more2 = api.call("browse", JSONObject().put("continuation", token), InnerTube.MUSIC, withAuth = false)
            println("PROBE HOME MORE (body) keys=${more2.keys().asSequence().toList()} ${counts(more2)}")
        }.onFailure { println("PROBE HOME failed $it") }

        runCatching {
            val s = api.call("search", JSONObject().put("query", "daft punk"), InnerTube.MUSIC, withAuth = false)
            println("PROBE SEARCH ${counts(s)}")
            println("PROBE SEARCH shelf paths ${path(s, "musicShelfRenderer")} ${path(s, "musicCardShelfRenderer")}")
        }.onFailure { println("PROBE SEARCH failed $it") }
    }
}
