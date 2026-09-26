package com.example.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.data.model.Song
import com.example.service.MusicPlaybackService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.sin

class SoundifyAudioPlayer(private val context: Context) {

    companion object {
        var exoPlayer: ExoPlayer? = null
            private set

        fun getOrCreatePlayer(context: Context): ExoPlayer {
            if (exoPlayer == null) {
                exoPlayer = ExoPlayer.Builder(context.applicationContext).build()
            }
            return exoPlayer!!
        }
    }

    private val player = getOrCreatePlayer(context)

    private val playerScope = CoroutineScope(Dispatchers.Main + Job())
    private var progressJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _visualizerAmplitudes = MutableStateFlow(List(16) { 0.2f })
    val visualizerAmplitudes: StateFlow<List<Float>> = _visualizerAmplitudes.asStateFlow()

    private var onSongCompletionListener: (() -> Unit)? = null
    private var onSongErrorListener: ((Song) -> Unit)? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                _isPlaying.value = false
                _currentPositionMs.value = 0L
                onSongCompletionListener?.invoke()
            }
            if (playbackState == Player.STATE_READY) {
                _durationMs.value = player.duration.coerceAtLeast(0L)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) startProgressTracker() else progressJob?.cancel()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e("SoundifyAudioPlayer", "ExoPlayer error", error)
            _isPlaying.value = false
        }
    }

    init {
        player.addListener(playerListener)
    }

    fun setOnCompletionListener(listener: () -> Unit) {
        onSongCompletionListener = listener
    }

    fun setOnErrorListener(listener: (Song) -> Unit) {
        onSongErrorListener = listener
    }

    fun playSong(song: Song) {
        val localFile = song.localFilePath?.let { File(it) }
        val hasValidLocalFile = localFile != null && localFile.exists() && localFile.length() > 10_000

        try {
            val metadata = MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artist)
                .setArtworkUri(Uri.parse(song.albumArtUrl))
                .build()

            val uri = if (hasValidLocalFile) Uri.fromFile(localFile) else Uri.parse(song.audioUrl)
            
            val mediaItem = MediaItem.Builder()
                .setMediaId(song.id)
                .setUri(uri)
                .setMediaMetadata(metadata)
                .build()

            player.setMediaItem(mediaItem)
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.prepare()
            player.play()

            val intent = Intent(context, MusicPlaybackService::class.java)
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Log.e("SoundifyAudioPlayer", "Failed to start ExoPlayer for: ${song.title}", e)
            onSongErrorListener?.invoke(song)
        }
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = playerScope.launch {
            while (isActive && player.isPlaying) {
                _currentPositionMs.value = player.currentPosition
                
                val pos = _currentPositionMs.value
                val newAmps = List(16) { index ->
                    val wave = (sin((pos / 150.0) + index * 0.6) * 0.5 + 0.5).toFloat()
                    0.15f + (wave * 0.75f)
                }
                _visualizerAmplitudes.value = newAmps
                
                delay(200)
            }
        }
    }

    fun resume() {
        player.play()
    }

    fun pause() {
        player.pause()
        _visualizerAmplitudes.value = List(16) { 0.1f }
    }

    fun togglePlayPause() {
        if (player.isPlaying) pause() else resume()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        _currentPositionMs.value = positionMs
    }

    fun stop() {
        player.stop()
        player.clearMediaItems()
        _isPlaying.value = false
        progressJob?.cancel()
    }

    fun release() {
        player.removeListener(playerListener)
        stop()
    }
}
