package dev.materialtube.data

import dev.materialtube.data.innertube.InnerTube
import dev.materialtube.data.innertube.MusicApi
import dev.materialtube.data.net.FallbackDns
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Hits real YouTube Music (signed out). */
class LiveMusicTest {
    private val api = InnerTube(FallbackDns.client(), null)
    private val music = MusicApi(api)

    @Before
    fun onlyWhenEnabled() = assumeTrue(System.getenv("MATERIALTUBE_LIVE_TEST") == "1")

    private fun MusicPage.describe() = shelves.joinToString { "${it.title}:${it.items.size}(${it.items.firstOrNull()?.let { i -> "${i.type}/${i.title}" }})" }

    @Test
    fun home() {
        val page = music.home()
        println("MUSIC HOME ${page.describe()} next=${page.continuation != null}")
        assertTrue(page.shelves.size >= 2)
        page.continuation?.let {
            val more = music.more(it)
            println("MUSIC HOME MORE ${more.describe()}")
            assertTrue(more.shelves.isNotEmpty())
        }
    }

    @Test
    fun explore() {
        val page = music.explore()
        println("MUSIC EXPLORE ${page.describe()}")
        assertTrue(page.shelves.isNotEmpty())
    }

    @Test
    fun searchAlbumArtistAndRadio() {
        val all = music.search("daft punk")
        println("MUSIC SEARCH ALL ${all.describe()}")
        assertTrue(all.shelves.size >= 3)

        val songs = music.search("daft punk get lucky", MusicSearchFilter.Songs).shelves.flatMap { it.items }
        println("MUSIC SONGS ${songs.take(3).map { "${it.type} ${it.title} / ${it.artists.map { a -> a.name }} ${it.duration}" }}")
        assertTrue(songs.isNotEmpty() && songs.first().type.playable)

        val album = music.search("random access memories", MusicSearchFilter.Albums).shelves.flatMap { it.items }.first { it.type.isRelease }
        val albumPage = music.page(album.id)
        println("MUSIC ALBUM ${albumPage.title} | ${albumPage.subtitle} | playlist=${albumPage.playlistId} | ${albumPage.describe()}")
        assertNotNull(albumPage.title)
        assertTrue(albumPage.shelves.first().items.size >= 5)

        val artist = music.search("daft punk", MusicSearchFilter.Artists).shelves.flatMap { it.items }.first { it.type == MusicType.Artist }
        val artistPage = music.page(artist.id)
        println("MUSIC ARTIST ${artistPage.title} thumb=${artistPage.thumbnail != null} ${artistPage.describe()}")
        assertTrue(artistPage.shelves.isNotEmpty())

        val (radio, next) = music.queue(songs.first().id, null)
        println("MUSIC RADIO n=${radio.size} next=${next != null} ${radio.take(3).map { it.title }}")
        assertTrue(radio.size >= 5)

        albumPage.playlistId?.let { pl ->
            val (albumQueue, _) = music.queue(null, pl)
            println("MUSIC ALBUM QUEUE n=${albumQueue.size}")
            assertTrue(albumQueue.isNotEmpty())
        }
    }

    @Test
    fun channelReleases() {
        // Taylor Swift's channel has a Releases tab.
        val id = "UCqECaJ8Gagnn7YCbPEzWH6g"
        println("RELEASES tabs=${api.channelTabTitles(id)}")
        val releases = api.channelReleases(id)
        println("RELEASES n=${releases.size} ${releases.take(3).map { "${it.title} ${it.playlistId} ${it.thumbnail != null}" }}")
        assertTrue(releases.isNotEmpty())
        val page = music.page(releases.first().id)
        println("RELEASE PAGE ${page.title} tracks=${page.shelves.firstOrNull()?.items?.size}")
        assertTrue(page.shelves.flatMap { it.items }.isNotEmpty())
    }

    /** The Android/iOS Music clients must parse too (they're used when they accept the account). */
    @Test
    fun otherMusicClientsParse() {
        for (client in listOf(InnerTube.MUSIC_ANDROID, InnerTube.MUSIC_IOS)) {
            val r = runCatching { api.call("browse", org.json.JSONObject().put("browseId", "FEmusic_home"), client, withAuth = false) }
            val page = r.getOrNull()?.let { dev.materialtube.data.innertube.MusicParser.page(it) }
            println("MUSIC CLIENT ${client.name}: ${r.exceptionOrNull()?.message ?: page?.describe()}")
        }
        println(music.diagnostics())
    }
}
