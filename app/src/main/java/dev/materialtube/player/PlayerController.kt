@file:OptIn(UnstableApi::class)

package dev.materialtube.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.materialtube.Graph
import dev.materialtube.data.VideoDetails
import dev.materialtube.data.VideoItem
import dev.materialtube.data.innertube.Parser
import dev.materialtube.data.sponsorblock.Segment
import dev.materialtube.data.sponsorblock.SponsorBlock
import dev.materialtube.data.stats.WatchStats
import dev.materialtube.streams.MediaSourceBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo

data class NowPlaying(
    val videoId: String,
    val title: String,
    val channel: String?,
    val channelId: String?,
    val thumbnail: String?,
    val details: VideoDetails? = null,
    val height: Int? = null,
    val availableHeights: List<Int> = emptyList(),
)

/**
 * App-wide player. Owns the single ExoPlayer instance (shared with [PlaybackService] for
 * background playback / notification), resolves streams, skips SponsorBlock segments and reports
 * watch history to the signed-in account.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object PlayerController {

    lateinit var player: ExoPlayer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Stats pings must stay ordered, so they go through a single-lane dispatcher. */
    private val statsDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private var info: StreamInfo? = null
    private var segments: List<Segment> = emptyList()
    private val skipped = mutableSetOf<String>()
    private var stats: WatchStats.Session? = null
    private var loadJob: Job? = null
    private var sideJobs = mutableListOf<Job>()
    private var lastStatsReport = 0L
    private var autoRetried = false

    fun init(context: Context) {
        if (::player.isInitialized) return
        player = ExoPlayer.Builder(context.applicationContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.addListener(listener)
        scope.launch { tick() }
    }

    fun play(videoId: String, hint: VideoItem? = null) {
        if (_nowPlaying.value?.videoId == videoId && player.mediaItemCount > 0 && _error.value == null) return
        finishStats("paused")
        loadJob?.cancel()
        sideJobs.forEach { it.cancel() }
        sideJobs.clear()
        player.stop()
        player.clearMediaItems()
        info = null
        segments = emptyList()
        skipped.clear()
        stats = null
        autoRetried = false
        _error.value = null
        _nowPlaying.value = NowPlaying(
            videoId = videoId,
            title = hint?.title.orEmpty(),
            channel = hint?.channel,
            channelId = hint?.channelId,
            thumbnail = hint?.thumbnail ?: Parser.thumbFor(videoId),
        )

        // Signed-in player call: account-bound history tracking + clean details.
        sideJobs += scope.launch {
            val playerInfo = runCatching { withContext(Dispatchers.IO) { Graph.api.player(videoId) } }.getOrNull()
                ?: return@launch
            playerInfo.details?.let { d ->
                update { it.copy(details = d, title = it.title.ifEmpty { d.title }, channel = it.channel ?: d.author, channelId = it.channelId ?: d.channelId) }
            }
            playerInfo.tracking?.let { tracking ->
                val session = Graph.stats.session(tracking, playerInfo.details?.lengthSeconds)
                stats = session
                if (player.isPlaying) {
                    val pos = positionSeconds()
                    scope.launch(statsDispatcher) { session.onStart(pos) }
                }
            }
        }
        if (Graph.prefs.sponsorBlockEnabled) {
            sideJobs += scope.launch {
                segments = runCatching {
                    withContext(Dispatchers.IO) { Graph.sponsorBlock.segments(videoId, Graph.prefs.sponsorBlockCategories) }
                }.getOrDefault(emptyList())
            }
        }
        loadJob = scope.launch { load(videoId, startPositionMs = 0, maxHeight = Graph.prefs.maxQuality) }
    }

    /** Switches quality, keeping the current position. */
    fun setQuality(height: Int) {
        val current = _nowPlaying.value ?: return
        val pos = player.currentPosition
        loadJob?.cancel()
        loadJob = scope.launch { load(current.videoId, pos, height) }
    }

    fun retry() {
        val current = _nowPlaying.value ?: return
        _error.value = null
        loadJob?.cancel()
        loadJob = scope.launch { load(current.videoId, player.currentPosition, Graph.prefs.maxQuality) }
    }

    fun close() {
        finishStats("paused")
        loadJob?.cancel()
        sideJobs.forEach { it.cancel() }
        player.stop()
        player.clearMediaItems()
        _nowPlaying.value = null
        _error.value = null
    }

    private suspend fun load(videoId: String, startPositionMs: Long, maxHeight: Int) {
        _loading.value = true
        try {
            val streamInfo = info?.takeIf { it.id == videoId } ?: withContext(Dispatchers.IO) {
                StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            }
            info = streamInfo
            update {
                it.copy(
                    title = it.title.ifEmpty { streamInfo.name },
                    channel = it.channel ?: streamInfo.uploaderName,
                )
            }
            val np = _nowPlaying.value
            val metadata = MediaMetadata.Builder()
                .setTitle(np?.title ?: streamInfo.name)
                .setArtist(np?.channel ?: streamInfo.uploaderName)
                .setArtworkUri(Uri.parse(Parser.thumbFor(videoId)))
                .build()
            val built = withContext(Dispatchers.IO) {
                MediaSourceBuilder.build(streamInfo, maxHeight, metadata)
            }
            update { it.copy(height = built.height, availableHeights = built.availableHeights) }
            player.setMediaSource(built.source, startPositionMs)
            player.prepare()
            player.playWhenReady = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = e.message ?: e.javaClass.simpleName
        } finally {
            _loading.value = false
        }
    }

    private inline fun update(f: (NowPlaying) -> NowPlaying) {
        _nowPlaying.value?.let { _nowPlaying.value = f(it) }
    }

    private fun positionSeconds() = player.currentPosition / 1000.0

    private fun finishStats(state: String) {
        val session = stats ?: return
        val pos = positionSeconds()
        scope.launch(statsDispatcher) { session.onProgress(pos, state) }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val session = stats ?: return
            val pos = positionSeconds()
            scope.launch(statsDispatcher) {
                if (isPlaying) {
                    session.onStart(pos)
                    session.onSeek(pos) // resume a new segment after a pause
                } else {
                    session.onProgress(pos, "paused")
                }
            }
            lastStatsReport = System.currentTimeMillis()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) finishStats("ended")
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            val session = stats ?: return
            val from = old.positionMs / 1000.0
            val to = new.positionMs / 1000.0
            scope.launch(statsDispatcher) {
                session.onProgress(from, "playing")
                session.onSeek(to)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // Stream URLs expire after a few hours; a fresh extraction usually fixes playback.
            val expired = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
            if (expired && info != null && !autoRetried) {
                autoRetried = true
                info = null
                retry()
            } else {
                _error.value = error.message ?: "Playback error"
            }
        }
    }

    /** Runs for the app's lifetime: SponsorBlock skipping and periodic watch-time reports. */
    private suspend fun tick() {
        while (scope.isActive) {
            delay(400)
            if (!player.isPlaying) continue
            val posMs = player.currentPosition
            segments.firstOrNull { posMs >= it.startSeconds * 1000 && posMs < it.endSeconds * 1000 - 500 && it.uuid !in skipped }
                ?.let { seg ->
                    skipped += seg.uuid
                    player.seekTo((seg.endSeconds * 1000).toLong())
                    _messages.tryEmit("Skipped ${SponsorBlock.CATEGORIES[seg.category] ?: seg.category}")
                }
            val now = System.currentTimeMillis()
            if (now - lastStatsReport > 20_000) {
                lastStatsReport = now
                val session = stats ?: continue
                val pos = positionSeconds()
                scope.launch(statsDispatcher) { session.onProgress(pos, "playing") }
            }
        }
    }
}
