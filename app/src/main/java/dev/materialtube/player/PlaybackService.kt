package dev.materialtube.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.materialtube.ui.MainActivity

/** Keeps playback alive in the background and provides the media notification / lockscreen controls. */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        PlayerController.init(this)
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, QueuePlayer(PlayerController.player))
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // The player is app-wide (owned by PlayerController); only the session is released here.
        session?.release()
        session = null
        super.onDestroy()
    }
}

/**
 * The session's view of the player: next/previous (notification, lock screen, headphones) move
 * through PlayerController's music queue, since its items are resolved one at a time rather
 * than being an ExoPlayer playlist.
 */
@OptIn(UnstableApi::class)
private class QueuePlayer(player: Player) : ForwardingPlayer(player) {
    private val queueCommands = listOf(
        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    )

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().addAll(*queueCommands.toIntArray()).build()

    override fun isCommandAvailable(command: Int): Boolean = command in queueCommands || super.isCommandAvailable(command)

    override fun hasNextMediaItem(): Boolean = PlayerController.hasNext || super.hasNextMediaItem()

    override fun hasPreviousMediaItem(): Boolean = PlayerController.queue.value.isNotEmpty() || super.hasPreviousMediaItem()

    override fun seekToNext() = if (PlayerController.queue.value.isNotEmpty()) PlayerController.next() else super.seekToNext()

    override fun seekToNextMediaItem() = seekToNext()

    override fun seekToPrevious() = if (PlayerController.queue.value.isNotEmpty()) PlayerController.previous() else super.seekToPrevious()

    override fun seekToPreviousMediaItem() = seekToPrevious()
}
