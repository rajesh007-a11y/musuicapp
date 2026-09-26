package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import com.example.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.sin

class SoundifyAudioPlayer(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null

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

    fun setOnCompletionListener(listener: () -> Unit) {
        onSongCompletionListener = listener
    }

    /**
     * Called when a song cannot be played at all (no valid local file, and streaming URL fails).
     * The ViewModel should skip to the next track.
     */
    fun setOnErrorListener(listener: (Song) -> Unit) {
        onSongErrorListener = listener
    }

    fun playSong(song: Song) {
        stop()

        // A valid local file must exist, be a real audio file (>10KB), not a placeholder
        val localFile = song.localFilePath?.let { File(it) }
        val hasValidLocalFile = localFile != null && localFile.exists() && localFile.length() > 10_000

        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )

                if (hasValidLocalFile) {
                    setDataSource(context, Uri.fromFile(localFile))
                } else {
                    setDataSource(song.audioUrl)
                }

                setOnPreparedListener { preparedMp ->
                    preparedMp.start()
                    _isPlaying.value = true
                    _durationMs.value = if (preparedMp.duration > 0) preparedMp.duration.toLong() else song.durationMs
                    startProgressTracker()
                }

                setOnCompletionListener {
                    _isPlaying.value = false
                    _currentPositionMs.value = 0L
                    onSongCompletionListener?.invoke()
                }

                setOnErrorListener { _, what, extra ->
                    Log.w("SoundifyAudioPlayer", "MediaPlayer error: $what, $extra for song: ${song.title}")
                    _isPlaying.value = false
                    _currentPositionMs.value = 0L
                    // Notify ViewModel to skip to next track instead of playing synth noise
                    onSongErrorListener?.invoke(song)
                    true
                }
            }

            mediaPlayer = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e("SoundifyAudioPlayer", "Failed to initialize MediaPlayer for: ${song.title}", e)
            _isPlaying.value = false
            // Notify ViewModel to skip to next track
            onSongErrorListener?.invoke(song)
        }
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = playerScope.launch {
            while (isActive && _isPlaying.value) {
                mediaPlayer?.let { mp ->
                    try {
                        if (mp.isPlaying) {
                            _currentPositionMs.value = mp.currentPosition.toLong()
                        }
                    } catch (_: IllegalStateException) {
                        // MediaPlayer may be in an invalid state
                    }
                }

                // Update visualizer amplitudes for animated equalizer
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
        mediaPlayer?.let { mp ->
            try {
                mp.start()
                _isPlaying.value = true
                startProgressTracker()
            } catch (e: IllegalStateException) {
                Log.e("SoundifyAudioPlayer", "Cannot resume, player in invalid state", e)
            }
        }
    }

    fun pause() {
        _isPlaying.value = false
        try {
            mediaPlayer?.pause()
        } catch (_: IllegalStateException) {
            // Ignore if already stopped
        }
        progressJob?.cancel()
        // Reset amplitudes to resting state
        _visualizerAmplitudes.value = List(16) { 0.1f }
    }

    fun togglePlayPause() {
        if (_isPlaying.value) {
            pause()
        } else {
            resume()
        }
    }

    fun seekTo(positionMs: Long) {
        _currentPositionMs.value = positionMs
        try {
            mediaPlayer?.seekTo(positionMs.toInt())
        } catch (_: IllegalStateException) {
            // Ignore
        }
    }

    fun stop() {
        progressJob?.cancel()
        _isPlaying.value = false
        mediaPlayer?.let { mp ->
            try {
                if (mp.isPlaying) mp.stop()
                mp.reset()
                mp.release()
            } catch (e: Exception) {
                Log.e("SoundifyAudioPlayer", "Error stopping player", e)
            }
        }
        mediaPlayer = null
    }

    fun release() {
        stop()
    }
}
