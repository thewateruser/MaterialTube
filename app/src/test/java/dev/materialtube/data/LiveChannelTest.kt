package dev.materialtube.data

import dev.materialtube.data.innertube.InnerTube
import dev.materialtube.data.net.FallbackDns
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Hits real YouTube: the channel page header and its Videos / Shorts tabs. */
class LiveChannelTest {
    private val api = InnerTube(FallbackDns.client(), null)
    private val channelId = "UCX6OQ3DkcsbYNE6H8uQQuVA"

    @Before
    fun onlyWhenEnabled() = assumeTrue(System.getenv("MATERIALTUBE_LIVE_TEST") == "1")

    @Test
    fun channelHeader() {
        val page = api.channel(channelId)
        println("CHANNEL title=${page.title} subtitle=${page.subtitle} banner=${page.banner != null} avatar=${page.avatar}")
        println("CHANNEL shelves=${page.feed.shelves.map { "${it.title}:${it.items.size}" }} channels=${page.feed.channels.size}")
        assertNotNull(page.title)
        assertTrue(page.feed.shelves.any { it.items.isNotEmpty() })
    }

    @Test
    fun videosTab() {
        val page = api.channelTab(channelId, ChannelTab.Videos)
        val items = page.shelves.flatMap { it.items }
        println("VIDEOS items=${items.size} continuation=${page.continuation != null} first=${items.firstOrNull()?.title}")
        assertTrue(items.size >= 10)
    }

    @Test
    fun shortsTab() {
        val page = api.channelTab(channelId, ChannelTab.Shorts)
        val items = page.shelves.flatMap { it.items }
        println("SHORTS items=${items.size} continuation=${page.continuation != null} first=${items.firstOrNull()?.title}")
        assertTrue(items.size >= 6)
        assertTrue(items.all { it.isShort })
    }
}
