package com.music.vibe.playback

import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.music.vibe.data.listentogether.ListenTogether
import com.music.vibe.data.model.QueueTier
import com.music.vibe.playback.cast.CastController
import com.music.vibe.playback.cast.CastPlayback
import com.music.vibe.playback.cast.CastSink
import java.util.concurrent.CopyOnWriteArraySet

/**
 * What the MediaSession, and so every control surface, actually talks to.
 *
 * Extracted from [PlaybackService] into its own single-responsibility wrapper:
 * - Redirects external `seekToPreviousMediaItem` to restart-then-skip (`seekToPrevious`).
 * - Cancels active crossfades on explicit skip/jump commands.
 * - Routes playback and volume controls to a Google Cast receiver when active.
 * - Enforces Listen Together party host lock and user-intent notifications.
 */
@UnstableApi
internal class SessionPlayer(
    player: Player,
    private val crossfade: CrossfadeController,
    private val onUserIntent: () -> Unit,
    private val deferPlayToParty: () -> Boolean,
    private val lockedTransport: (Boolean) -> Boolean,
    private val cast: CastPlayback,
    private val getSubtitle: () -> String?,
) : ForwardingPlayer(player), CastSink {

    private val listeners = CopyOnWriteArraySet<Player.Listener>()
    private var reportedPlayWhenReady = false
    private var reportedPlaybackState = Player.STATE_IDLE
    private var reportedIsPlaying = false

    init {
        cast.sink = this
    }

    private companion object {
        val CAST_DEVICE: DeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
            .setMinVolume(0)
            .setMaxVolume(100)
            .build()

        /** A hardware key press, as a share of the receiver's range. */
        const val VOLUME_STEP = 0.05f
    }

    override fun addListener(listener: Player.Listener) {
        listeners += listener
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners -= listener
        super.removeListener(listener)
    }

    override fun getPlayWhenReady() = if (cast.active) cast.playWhenReady else super.getPlayWhenReady()

    override fun isPlaying() = if (cast.active) cast.isPlaying else super.isPlaying()

    override fun getPlaybackState() = if (cast.active) cast.playbackState else super.getPlaybackState()

    override fun getCurrentPosition() = if (cast.active) cast.positionMs else super.getCurrentPosition()

    override fun getContentPosition() = if (cast.active) cast.positionMs else super.getContentPosition()

    override fun getBufferedPosition() = if (cast.active) cast.positionMs else super.getBufferedPosition()

    override fun getContentBufferedPosition() =
        if (cast.active) cast.positionMs else super.getContentBufferedPosition()

    override fun getDuration(): Long =
        if (cast.active && cast.durationMs != C.TIME_UNSET) cast.durationMs else super.getDuration()

    override fun getContentDuration(): Long =
        if (cast.active && cast.durationMs != C.TIME_UNSET) cast.durationMs else super.getContentDuration()

    override fun getDeviceInfo(): DeviceInfo = if (cast.active) CAST_DEVICE else super.getDeviceInfo()

    override fun getDeviceVolume(): Int = if (cast.active) cast.deviceVolume else super.getDeviceVolume()

    override fun isDeviceMuted(): Boolean = if (cast.active) false else super.isDeviceMuted()

    override fun setDeviceVolume(volume: Int, flags: Int) {
        if (cast.active) CastController.setVolume(volume / 100f) else super.setDeviceVolume(volume, flags)
    }

    @Suppress("DEPRECATION")
    override fun setDeviceVolume(volume: Int) {
        if (cast.active) CastController.setVolume(volume / 100f) else super.setDeviceVolume(volume)
    }

    override fun increaseDeviceVolume(flags: Int) {
        if (cast.active) nudgeReceiverVolume(+VOLUME_STEP) else super.increaseDeviceVolume(flags)
    }

    @Suppress("DEPRECATION")
    override fun increaseDeviceVolume() {
        if (cast.active) nudgeReceiverVolume(+VOLUME_STEP) else super.increaseDeviceVolume()
    }

    override fun decreaseDeviceVolume(flags: Int) {
        if (cast.active) nudgeReceiverVolume(-VOLUME_STEP) else super.decreaseDeviceVolume(flags)
    }

    @Suppress("DEPRECATION")
    override fun decreaseDeviceVolume() {
        if (cast.active) nudgeReceiverVolume(-VOLUME_STEP) else super.decreaseDeviceVolume()
    }

    override fun setDeviceMuted(muted: Boolean, flags: Int) {
        if (!cast.active) super.setDeviceMuted(muted, flags)
    }

    @Suppress("DEPRECATION")
    override fun setDeviceMuted(muted: Boolean) {
        if (!cast.active) super.setDeviceMuted(muted)
    }

    private fun nudgeReceiverVolume(delta: Float) =
        CastController.setVolume(CastController.volume.value + delta)

    override fun onRemotePlaybackChanged() {
        val flags = FlagSet.Builder()
        val playWhenReady = getPlayWhenReady()
        val state = getPlaybackState()
        val playing = isPlaying()
        if (playWhenReady != reportedPlayWhenReady) {
            reportedPlayWhenReady = playWhenReady
            flags.add(Player.EVENT_PLAY_WHEN_READY_CHANGED)
            listeners.forEach {
                it.onPlayWhenReadyChanged(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            }
        }
        if (state != reportedPlaybackState) {
            reportedPlaybackState = state
            flags.add(Player.EVENT_PLAYBACK_STATE_CHANGED)
            listeners.forEach { it.onPlaybackStateChanged(state) }
        }
        if (playing != reportedIsPlaying) {
            reportedIsPlaying = playing
            flags.add(Player.EVENT_IS_PLAYING_CHANGED)
            listeners.forEach { it.onIsPlayingChanged(playing) }
        }
        flags.add(Player.EVENT_POSITION_DISCONTINUITY)
        val position = Player.PositionInfo(
            null,
            currentMediaItemIndex,
            currentMediaItem,
            null,
            currentPeriodIndex,
            currentPosition,
            contentPosition,
            C.INDEX_UNSET,
            C.INDEX_UNSET,
        )
        listeners.forEach {
            it.onPositionDiscontinuity(position, position, Player.DISCONTINUITY_REASON_INTERNAL)
        }
        val events = Player.Events(flags.build())
        listeners.forEach { it.onEvents(this, events) }
    }

    override fun onRemoteRouteChanged() {
        val info = getDeviceInfo()
        listeners.forEach { it.onDeviceInfoChanged(info) }
        val events = Player.Events(FlagSet.Builder().add(Player.EVENT_DEVICE_INFO_CHANGED).build())
        listeners.forEach { it.onEvents(this, events) }
        onRemoteVolumeChanged()
    }

    override fun onRemoteVolumeChanged() {
        val volume = getDeviceVolume()
        val muted = isDeviceMuted()
        listeners.forEach { it.onDeviceVolumeChanged(volume, muted) }
        val events = Player.Events(FlagSet.Builder().add(Player.EVENT_DEVICE_VOLUME_CHANGED).build())
        listeners.forEach { it.onEvents(this, events) }
    }

    private fun castPrevious() {
        if (cast.positionMs > wrappedPlayer.maxSeekToPreviousPosition || !wrappedPlayer.hasPreviousMediaItem()) {
            cast.seekTo(0L)
        } else {
            wrappedPlayer.seekToPreviousMediaItem()
        }
    }

    private fun locked(): Boolean = ListenTogether.state.value.controlsLocked

    override fun play() {
        if (lockedTransport(true)) return
        onUserIntent()
        if (deferPlayToParty()) return
        if (cast.active) {
            cast.play()
            return
        }
        super.play()
    }

    override fun pause() {
        if (lockedTransport(false)) return
        onUserIntent()
        if (cast.active) {
            cast.pause()
            return
        }
        super.pause()
    }

    override fun stop() {
        if (locked()) { super.stop(); return }
        onUserIntent()
        super.stop()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (lockedTransport(playWhenReady)) return
        onUserIntent()
        if (playWhenReady && deferPlayToParty()) return
        if (cast.active) {
            if (playWhenReady) cast.play() else cast.pause()
            return
        }
        super.setPlayWhenReady(playWhenReady)
    }

    override fun seekTo(positionMs: Long) {
        if (locked()) return
        onUserIntent()
        if (cast.active) {
            cast.seekTo(positionMs)
            return
        }
        super.seekTo(positionMs)
    }

    override fun seekBack() {
        if (locked()) return
        onUserIntent()
        if (cast.active) {
            cast.seekTo(cast.positionMs - seekBackIncrement)
            return
        }
        super.seekBack()
    }

    override fun seekForward() {
        if (locked()) return
        onUserIntent()
        if (cast.active) {
            cast.seekTo(cast.positionMs + seekForwardIncrement)
            return
        }
        super.seekForward()
    }

    override fun seekToPrevious() {
        if (locked()) return
        onUserIntent()
        if (cast.active) {
            castPrevious()
            return
        }
        super.seekToPrevious()
    }

    override fun seekToDefaultPosition() {
        if (locked()) return
        onUserIntent()
        if (cast.active) {
            cast.seekTo(0L)
            return
        }
        super.seekToDefaultPosition()
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        if (locked()) return
        onUserIntent()
        super.seekToDefaultPosition(mediaItemIndex)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        if (locked()) return
        onUserIntent()
        super.setMediaItems(mediaItems, resetPosition)
    }

    override fun setMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ) {
        if (locked()) return
        onUserIntent()
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }

    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) {
        if (locked()) return
        onUserIntent()
        super.addMediaItems(index, mediaItems)
    }

    override fun addMediaItems(mediaItems: List<MediaItem>) {
        if (locked()) return
        onUserIntent()
        super.addMediaItems(mediaItems)
    }

    override fun removeMediaItem(index: Int) {
        if (locked()) return
        onUserIntent()
        super.removeMediaItem(index)
    }

    override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
        if (locked()) return
        onUserIntent()
        super.removeMediaItems(fromIndex, toIndex)
    }

    override fun moveMediaItem(currentIndex: Int, newIndex: Int) {
        if (locked()) return
        onUserIntent()
        super.moveMediaItem(currentIndex, newIndex)
    }

    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) {
        if (locked()) return
        onUserIntent()
        super.moveMediaItems(fromIndex, toIndex, newIndex)
    }

    override fun replaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: List<MediaItem>,
    ) {
        if (locked()) return
        onUserIntent()
        super.replaceMediaItems(fromIndex, toIndex, mediaItems)
    }

    override fun clearMediaItems() {
        if (locked()) return
        onUserIntent()
        super.clearMediaItems()
    }

    override fun getMediaMetadata(): MediaMetadata {
        val base = wrappedPlayer.mediaMetadata
        val subtitle = getSubtitle()
        return if (!subtitle.isNullOrBlank()) {
            base.buildUpon().setSubtitle(subtitle).build()
        } else {
            base
        }
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (locked()) return
        onUserIntent()
        crossfade.onSkipRequested()
        if (mediaItemIndex !in 0 until wrappedPlayer.mediaItemCount) return
        if (cast.active && mediaItemIndex == wrappedPlayer.currentMediaItemIndex) {
            cast.seekTo(positionMs)
            return
        }
        val skipped = skippedByQueueJump(currentMediaItemIndex, mediaItemIndex)
        if (skipped == null) {
            wrappedPlayer.seekTo(mediaItemIndex, positionMs)
            return
        }

        val targetTier = wrappedPlayer.getMediaItemAt(mediaItemIndex).queueTier
        val removableIndices = mutableListOf<Int>()
        for (i in skipped) {
            val tier = wrappedPlayer.getMediaItemAt(i).queueTier
            val isRemovable = if (targetTier == QueueTier.USER_QUEUE) {
                tier == QueueTier.USER_QUEUE
            } else {
                tier != QueueTier.USER_QUEUE
            }
            if (isRemovable) {
                removableIndices.add(i)
            }
        }

        for (i in removableIndices.asReversed()) {
            wrappedPlayer.removeMediaItem(i)
        }
        var newTargetIndex = mediaItemIndex - removableIndices.count { it < mediaItemIndex }
        val nextIndex = wrappedPlayer.currentMediaItemIndex + 1
        if (newTargetIndex > nextIndex) {
            wrappedPlayer.moveMediaItem(newTargetIndex, nextIndex)
            newTargetIndex = nextIndex
        }
        wrappedPlayer.seekTo(newTargetIndex, positionMs)
    }

    override fun seekToPreviousMediaItem() {
        if (locked()) return
        onUserIntent()
        crossfade.onSkipRequested()
        if (cast.active) {
            castPrevious()
            return
        }
        wrappedPlayer.seekToPrevious()
    }

    override fun seekToNextMediaItem() {
        if (locked()) return
        onUserIntent()
        crossfade.onSkipRequested()
        wrappedPlayer.seekToNextMediaItem()
    }

    override fun seekToNext() {
        if (locked()) return
        onUserIntent()
        crossfade.onSkipRequested()
        wrappedPlayer.seekToNext()
    }
}
