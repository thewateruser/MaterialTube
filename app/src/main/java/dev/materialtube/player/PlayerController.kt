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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import dev.materialtube.Graph
import dev.materialtube.data.VideoDetails
import dev.materialtube.data.MusicItem
import dev.materialtube.data.VideoItem
import dev.materialtube.data.innertube.Parser
import dev.materialtube.data.sponsorblock.Segment
import dev.materialtube.data.sponsorblock.SponsorBlock
import dev.materialtube.data.stats.WatchStats
import dev.materialtube.streams.MediaSourceBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
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
    /** Playing from the music queue (audio only, square artwork, now-playing screen). */
    val music: Boolean = false,
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
            // Start playing after ~1s of buffer instead of the default 2.5s.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 50_000, 1_000, 2_000)
                    .build(),
            )
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.addListener(listener)
        scope.launch { tick() }
    }

    fun play(videoId: String, hint: VideoItem? = null) = play(videoId, hint, fromQueue = false)

    private fun play(videoId: String, hint: VideoItem?, fromQueue: Boolean) {
        if (!fromQueue) {
            // Watching a video replaces the music queue (if the same song isn't already playing).
            if (_nowPlaying.value?.videoId == videoId && player.mediaItemCount > 0 && _error.value == null) {
                if (_nowPlaying.value?.music == true) {
                    clearQueue()
                    update { it.copy(music = false) }
                    loadJob?.cancel()
                    loadJob = scope.launch { load(videoId, player.currentPosition, Graph.prefs.maxQuality) }
                }
                return
            }
            clearQueue()
        } else if (_nowPlaying.value?.videoId == videoId && player.mediaItemCount > 0 && _error.value == null) {
            return
        }
        audioOnly = fromQueue
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
            music = fromQueue,
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

    // --- Music queue ---------------------------------------------------------------------

    private var audioOnly = false
    private val _queue = MutableStateFlow<List<MusicItem>>(emptyList())
    /** Songs queued in the music player (empty when watching a video). */
    val queue: StateFlow<List<MusicItem>> = _queue
    private val _queueIndex = MutableStateFlow(-1)
    val queueIndex: StateFlow<Int> = _queueIndex
    /** Continuation for more queue items (radio or the rest of a long playlist). */
    private var queueToken: String? = null
    private var queuePlaylistId: String? = null
    private var queueJob: Job? = null

    /**
     * Plays [items] as a music queue from [start]. When [extend] is set, the queue keeps going
     * with YouTube Music's radio for the first song (or the rest of [playlistId]).
     */
    fun playMusic(items: List<MusicItem>, start: Int = 0, playlistId: String? = null, extend: Boolean = true) {
        val songs = items.filter { it.type.playable }
        if (songs.isEmpty()) return
        val first = items.getOrNull(start)?.takeIf { it.type.playable } ?: songs.first()
        _queue.value = songs
        _queueIndex.value = songs.indexOf(first).coerceAtLeast(0)
        queueToken = null
        queuePlaylistId = null
        queueJob?.cancel()
        playQueueItem()
        if (extend) {
            queueJob = scope.launch {
                val (more, token) = runCatching {
                    withContext(Dispatchers.IO) { Graph.music.queue(first.id, playlistId) }
                }.getOrNull() ?: return@launch
                appendToQueue(more)
                queueToken = token
                queuePlaylistId = playlistId ?: "RDAMVM${first.id}"
            }
        }
    }

    /** A playlist/album: fetch its tracks via the queue endpoint and play them. */
    fun playPlaylist(playlistId: String, shuffle: Boolean = false) {
        queueJob?.cancel()
        queueJob = scope.launch {
            val (items, token) = runCatching {
                withContext(Dispatchers.IO) { Graph.music.queue(null, playlistId) }
            }.getOrNull() ?: run {
                _messages.tryEmit("Couldn't load this playlist")
                return@launch
            }
            val list = if (shuffle) items.shuffled() else items
            playMusic(list, 0, extend = false)
            queueToken = if (shuffle) null else token
            queuePlaylistId = playlistId
        }
    }

    private fun appendToQueue(more: List<MusicItem>) {
        val have = _queue.value.map { it.id }.toHashSet()
        _queue.value = _queue.value + more.filter { it.type.playable && have.add(it.id) }
    }

    private fun playQueueItem() {
        val item = _queue.value.getOrNull(_queueIndex.value) ?: return
        play(item.id, item.toVideo(), fromQueue = true)
        // Running low: fetch more radio / playlist items.
        val token = queueToken
        val pl = queuePlaylistId
        if (token != null && pl != null && _queueIndex.value >= _queue.value.size - 3 && queueJob?.isActive != true) {
            queueJob = scope.launch {
                val (more, next) = runCatching { withContext(Dispatchers.IO) { Graph.music.queueMore(token, pl) } }
                    .getOrNull() ?: return@launch
                appendToQueue(more)
                queueToken = next.takeIf { it != token }
            }
        }
    }

    val hasNext get() = _queueIndex.value in 0 until _queue.value.size - 1

    fun next() {
        if (!hasNext) return
        _queueIndex.value += 1
        playQueueItem()
    }

    /** Restarts the song, or goes to the previous one when near its start. */
    fun previous() {
        if (player.currentPosition > 3_000 || _queueIndex.value <= 0) {
            player.seekTo(0)
            return
        }
        _queueIndex.value -= 1
        playQueueItem()
    }

    fun playAt(index: Int) {
        if (index !in _queue.value.indices) return
        _queueIndex.value = index
        playQueueItem()
    }

    private fun clearQueue() {
        queueJob?.cancel()
        _queue.value = emptyList()
        _queueIndex.value = -1
        queueToken = null
        queuePlaylistId = null
        audioOnly = false
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
        clearQueue()
        finishStats("paused")
        loadJob?.cancel()
        sideJobs.forEach { it.cancel() }
        player.stop()
        player.clearMediaItems()
        _nowPlaying.value = null
        _error.value = null
    }

    /**
     * Resolved streams, kept for a while so videos we prefetched (the next Shorts) start
     * immediately. Stream URLs stay valid for hours; entries are dropped after 2h to be safe.
     * Only touched from the main thread.
     */
    private class CachedInfo(val info: StreamInfo, val at: Long)
    private val infoCache = object : LinkedHashMap<String, CachedInfo>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedInfo>?) = size > 20
    }
    private val infoInFlight = HashMap<String, Deferred<StreamInfo>>()

    private fun fetchInfo(videoId: String): Deferred<StreamInfo> {
        infoInFlight[videoId]?.let { return it }
        // Runs in the controller scope so cancelling a load (fast scrolling) doesn't throw the work away.
        val job = scope.async(Dispatchers.IO) {
            StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        }
        infoInFlight[videoId] = job
        job.invokeOnCompletion { error ->
            scope.launch {
                infoInFlight.remove(videoId)
                if (error == null) infoCache[videoId] = CachedInfo(job.getCompleted(), System.currentTimeMillis())
            }
        }
        return job
    }

    /** Resolved streams for [videoId], from the cache when available. */
    suspend fun streamInfo(videoId: String): StreamInfo {
        infoCache[videoId]?.let { cached ->
            if (System.currentTimeMillis() - cached.at < 2 * 60 * 60 * 1000L) return cached.info
            infoCache.remove(videoId)
        }
        return fetchInfo(videoId).await()
    }

    /** Drops cached streams for [videoId] (e.g. after its URLs expired). */
    fun forget(videoId: String) {
        infoCache.remove(videoId)
    }

    /** Resolves a video's streams in the background so playing it later starts at once. */
    fun prefetch(videoId: String) {
        val cached = infoCache[videoId]
        if (cached != null && System.currentTimeMillis() - cached.at < 2 * 60 * 60 * 1000L) return
        fetchInfo(videoId)
    }

    private suspend fun load(videoId: String, startPositionMs: Long, maxHeight: Int) {
        _loading.value = true
        try {
            val streamInfo = info?.takeIf { it.id == videoId } ?: streamInfo(videoId)
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
                .setArtworkUri(Uri.parse(np?.thumbnail ?: Parser.thumbFor(videoId)))
                .build()
            val built = withContext(Dispatchers.IO) {
                MediaSourceBuilder.build(streamInfo, maxHeight, metadata, audioOnly)
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
            if (state == Player.STATE_ENDED) {
                finishStats("ended")
                if (_nowPlaying.value?.music == true && hasNext) next()
            }
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
                info?.id?.let { infoCache.remove(it) }
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
