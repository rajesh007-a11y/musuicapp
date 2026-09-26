package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
    private var synthTrack: AudioTrack? = null
    private var isUsingSynth = false

    private val playerScope = CoroutineScope(Dispatchers.Main + Job())
    private var progressJob: Job? = null
    private var synthJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _visualizerAmplitudes = MutableStateFlow(List(16) { 0.2f })
    val visualizerAmplitudes: StateFlow<List<Float>> = _visualizerAmplitudes.asStateFlow()

    private var onSongCompletionListener: (() -> Unit)? = null

    fun setOnCompletionListener(listener: () -> Unit) {
        onSongCompletionListener = listener
    }

    fun playSong(song: Song) {
        stop()

        val localFile = song.localFilePath?.let { File(it) }
        val hasValidLocalFile = localFile != null && localFile.exists() && localFile.length() > 500

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
                    isUsingSynth = false
                    startProgressTracker()
                }

                setOnCompletionListener {
                    _isPlaying.value = false
                    _currentPositionMs.value = 0L
                    onSongCompletionListener?.invoke()
                }

                setOnErrorListener { _, what, extra ->
                    Log.w("SoundifyAudioPlayer", "MediaPlayer error: $what, $extra. Falling back to harmonic synth.")
                    startHarmonicSynthesizer(song)
                    true
                }
            }

            mediaPlayer = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e("SoundifyAudioPlayer", "Failed to initialize MediaPlayer, using harmonic synth", e)
            startHarmonicSynthesizer(song)
        }
    }

    private fun startHarmonicSynthesizer(song: Song) {
        stopSynth()
        isUsingSynth = true
        _isPlaying.value = true
        _durationMs.value = song.durationMs
        _currentPositionMs.value = 0L

        startProgressTracker()

        // Generate gentle rhythmic musical chords matching the song's key & bpm
        synthJob = playerScope.launch(Dispatchers.Default) {
            val sampleRate = 22050
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()

            synthTrack = track
            track.play()

            val baseFrequencies = when (song.genre) {
                "Synthwave" -> listOf(220.0, 277.18, 329.63, 440.0) // A minor / synth chords
                "Indie Pop" -> listOf(261.63, 329.63, 392.00, 523.25) // C major
                "Lo-Fi / Chillhop" -> listOf(174.61, 220.0, 261.63, 329.63) // Fmaj7 mellow
                else -> listOf(196.00, 246.94, 293.66, 392.00) // G major
            }

            val buffer = ShortArray(1024)
            var sampleIndex = 0
            while (isActive && _isPlaying.value) {
                val chordIndex = (sampleIndex / (sampleRate * 2)) % baseFrequencies.size
                val baseFreq = baseFrequencies[chordIndex]
                val bassFreq = baseFreq / 2.0

                for (i in buffer.indices) {
                    val time = sampleIndex.toDouble() / sampleRate
                    // Harmonic overtone blend: fundamental + sub-bass + fifth + subtle rhythmic pulse
                    val beatPulse = (sin(2.0 * Math.PI * (song.bpm / 60.0) * time) * 0.5 + 0.5)
                    val sampleVal = (
                        sin(2.0 * Math.PI * baseFreq * time) * 0.35 +
                        sin(2.0 * Math.PI * bassFreq * time) * 0.45 * beatPulse +
                        sin(2.0 * Math.PI * (baseFreq * 1.5) * time) * 0.20
                    ) * 0.35 // comfortable listening volume

                    buffer[i] = (sampleVal * Short.MAX_VALUE).toInt().toShort()
                    sampleIndex++
                }
                track.write(buffer, 0, buffer.size)
            }
        }
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = playerScope.launch {
            while (isActive && _isPlaying.value) {
                if (isUsingSynth) {
                    val nextPos = _currentPositionMs.value + 200L
                    if (nextPos >= _durationMs.value) {
                        _isPlaying.value = false
                        _currentPositionMs.value = 0L
                        onSongCompletionListener?.invoke()
                        break
                    } else {
                        _currentPositionMs.value = nextPos
                    }
                } else {
                    mediaPlayer?.let { mp ->
                        if (mp.isPlaying) {
                            _currentPositionMs.value = mp.currentPosition.toLong()
                        }
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
        if (isUsingSynth) {
            _isPlaying.value = true
            startProgressTracker()
        } else {
            mediaPlayer?.let { mp ->
                mp.start()
                _isPlaying.value = true
                startProgressTracker()
            }
        }
    }

    fun pause() {
        _isPlaying.value = false
        if (!isUsingSynth) {
            mediaPlayer?.pause()
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
        if (!isUsingSynth) {
            mediaPlayer?.seekTo(positionMs.toInt())
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
        stopSynth()
    }

    private fun stopSynth() {
        synthJob?.cancel()
        synthJob = null
        synthTrack?.let {
            try {
                it.stop()
                it.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
        synthTrack = null
        isUsingSynth = false
    }

    fun release() {
        stop()
    }
}
