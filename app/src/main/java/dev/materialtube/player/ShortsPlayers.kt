@file:OptIn(UnstableApi::class)

package dev.materialtube.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.mutableStateMapOf
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import dev.materialtube.Graph
import dev.materialtube.streams.MediaSourceBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A small pool of players for the Shorts feed, like the official app: the current Short and its
 * neighbours each have their own player, already prepared and paused on the first frame, so
 * swiping just switches which one plays instead of loading from scratch.
 */
class ShortsPlayers(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = HashMap<String, Job>()

    /** Players by video id (Compose state, so pages pick them up as they're created/released). */
    val players = mutableStateMapOf<String, ExoPlayer>()
    /** Videos whose first frame is on screen (until then the page shows the thumbnail). */
    val firstFrame = mutableStateMapOf<String, Boolean>()
    val errors = mutableStateMapOf<String, String>()
    private var active: String? = null
    /** Automatic retries used per video (fresh streams, then 720p), before showing Retry. */
    private val attempts = HashMap<String, Int>()
    private var watchdog: Job? = null

    /** Creates and starts preparing a player for [videoId] if there isn't one yet. */
    fun ensure(videoId: String) {
        if (videoId in players) return
        val player = ExoPlayer.Builder(context.applicationContext)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(5_000, 20_000, 500, 1_000)
                    .build(),
            )
            .build()
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.playWhenReady = videoId == active
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrame[videoId] = true
            }

            override fun onPlayerError(error: PlaybackException) {
                fail(videoId, error.message ?: "Playback error")
            }
        })
        players[videoId] = player
        jobs[videoId] = scope.launch {
            try {
                val info = PlayerController.streamInfo(videoId)
                // The last automatic retry drops to 720p: some phones run out of hardware
                // decoders for several high-resolution players at once.
                val maxHeight = if ((attempts[videoId] ?: 0) >= 2) minOf(720, Graph.prefs.maxQuality) else Graph.prefs.maxQuality
                val built = withContext(Dispatchers.IO) {
                    MediaSourceBuilder.build(info, maxHeight, MediaMetadata.Builder().setTitle(info.name).build())
                }
                player.setMediaSource(built.source)
                player.prepare()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(videoId, e.message ?: "Couldn't load this Short")
            }
        }
    }

    /** Retries automatically a couple of times before showing the error. */
    private fun fail(videoId: String, message: String) {
        val tries = attempts[videoId] ?: 0
        if (tries < 2) {
            attempts[videoId] = tries + 1
            scope.launch { reload(videoId) }
        } else {
            errors[videoId] = message
        }
    }

    private fun reload(videoId: String) {
        PlayerController.forget(videoId)
        release(videoId)
        ensure(videoId)
        if (videoId == active) activate(videoId)
    }

    /** Plays [videoId] (from the start if it had been swiped away) and pauses the rest. */
    fun activate(videoId: String) {
        active = videoId
        // Nothing on screen after a few seconds (stuck loading): reload it.
        watchdog?.cancel()
        watchdog = scope.launch {
            kotlinx.coroutines.delay(7_000)
            if (active == videoId && firstFrame[videoId] != true && videoId !in errors) {
                fail(videoId, "This Short won't load")
            }
        }
        players.forEach { (id, p) ->
            if (id == videoId) {
                p.playWhenReady = true
            } else if (p.playWhenReady) {
                p.playWhenReady = false
                p.seekTo(0)
            }
        }
    }

    fun pauseAll() = players.values.forEach { it.playWhenReady = false }

    fun resume() = active?.let { players[it]?.playWhenReady = true }

    fun togglePlay(videoId: String) {
        val p = players[videoId] ?: return
        p.playWhenReady = !p.playWhenReady
    }

    fun retry(videoId: String) {
        attempts[videoId] = 2 // straight to the safest settings
        reload(videoId)
    }

    /** Releases every player except those for [videoIds]. */
    fun keepOnly(videoIds: Collection<String>) {
        players.keys.filter { it !in videoIds }.forEach(::release)
    }

    private fun release(videoId: String) {
        jobs.remove(videoId)?.cancel()
        players.remove(videoId)?.release()
        firstFrame.remove(videoId)
        errors.remove(videoId)
    }

    fun releaseAll() {
        watchdog?.cancel()
        players.keys.toList().forEach(::release)
        scope.cancel()
    }
}
