package dev.materialtube.data

import dev.materialtube.data.innertube.InnerTube
import dev.materialtube.data.innertube.Parser
import dev.materialtube.data.net.FallbackDns
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Diagnostics only: which TV-client browse ids return music. Never fails. */
class LiveTvMusicProbeTest {
    private val api = InnerTube(FallbackDns.client(), null)

    @Before
    fun onlyWhenEnabled() = assumeTrue(System.getenv("MATERIALTUBE_LIVE_TEST") == "1")

    @Test
    fun probe() {
        val ids = listOf(
            "FEmusic_home", "FEmusic_explore", "FEmusic", "FEtopics_music", "UC-9-kyTW8ZkZNDHQJ6FgpwQ",
            "FEmusic_liked_videos", "VLLM", "FElibrary", "FEplaylist_aggregation", "FEmusic_library_landing",
            "VLRDCLAK5uy_kmPRjHDECIcuVwnKsx2Ng7fyNgFKWNJFs", "FEtrending",
        )
        for (id in ids) {
            val r = runCatching { api.call("browse", JSONObject().put("browseId", id), InnerTube.TV, withAuth = false) }
            r.onFailure { println("TVPROBE $id: error ${it.message}") }
            r.onSuccess { json ->
                val feed = Parser.feed(json)
                val text = json.toString()
                println(
                    "TVPROBE $id: shelves=${feed.shelves.map { "${it.title}:${it.items.size}" }.take(8)} " +
                        "first=${feed.shelves.firstOrNull()?.items?.firstOrNull()?.title} " +
                        "playlists=${Regex("\\"contentType\\":\\"[A-Z_]*PLAYLIST[A-Z_]*\\"").findAll(text).count()} " +
                        "len=${text.length}",
                )
            }
        }
        // Radio for a song through the TV client (would be personal signed in).
        val radio = runCatching {
            api.call("next", JSONObject().put("videoId", "5NV6Rdv1a3I").put("playlistId", "RDAMVM5NV6Rdv1a3I"), InnerTube.TV, withAuth = false)
        }
        println("TVPROBE radio: ${radio.exceptionOrNull()?.message ?: Parser.videos(radio.getOrNull()).take(5).map { it.title }} n=${radio.getOrNull()?.let { Parser.videos(it).size }}")
    }
}
