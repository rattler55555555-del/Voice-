package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class AudioTrackPlayer(
    private val coroutineScope: CoroutineScope,
    private val onLog: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "AudioTrackPlayer"
        const val DEFAULT_SAMPLE_RATE = 24000
    }

    private var audioTrack: AudioTrack? = null
    private var currentSampleRate = DEFAULT_SAMPLE_RATE
    private val audioQueue = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var isPlaying = false

    @Volatile
    private var playbackJob: Job? = null

    private val _outputAmplitude = MutableStateFlow(0f)
    val outputAmplitude: StateFlow<Float> = _outputAmplitude.asStateFlow()

    private val _isPlayingState = MutableStateFlow(false)
    val isPlayingState: StateFlow<Boolean> = _isPlayingState.asStateFlow()

    fun initAudioTrack(sampleRate: Int = DEFAULT_SAMPLE_RATE) {
        if (audioTrack != null && currentSampleRate == sampleRate) {
            return
        }
        release()
        currentSampleRate = sampleRate
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

        try {
            audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
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
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                )
            }
            audioTrack?.play()
            log("AudioTrack initialized at ${sampleRate}Hz (buffer=$bufferSize bytes)")
        } catch (e: Exception) {
            log("AudioTrack initialization error: ${e.message}")
            Log.e(TAG, "Failed to init AudioTrack", e)
        }
    }

    fun queueBase64Pcm(base64Data: String, sampleRate: Int = DEFAULT_SAMPLE_RATE) {
        try {
            log("Base64 decoding started (${base64Data.length} chars)")
            val pcmBytes = Base64.decode(base64Data, Base64.DEFAULT)
            log("Base64 decoding successful: ${pcmBytes.size} bytes PCM")
            queuePcmChunk(pcmBytes, sampleRate)
        } catch (e: Exception) {
            log("PCM Base64 decoding failed: ${e.message}")
            Log.e(TAG, "PCM decode error", e)
        }
    }

    @Synchronized
    fun queuePcmChunk(pcmData: ByteArray, sampleRate: Int = DEFAULT_SAMPLE_RATE) {
        if (pcmData.isEmpty()) return
        if (audioTrack == null || currentSampleRate != sampleRate) {
            initAudioTrack(sampleRate)
        }
        audioQueue.offer(pcmData)
        log("Queued PCM chunk: ${pcmData.size} bytes. Queue depth: ${audioQueue.size}")

        if (!isPlaying) {
            startPlaybackLoop()
        }
    }

    private fun startPlaybackLoop() {
        isPlaying = true
        _isPlayingState.value = true
        playbackJob?.cancel()
        playbackJob = coroutineScope.launch(Dispatchers.IO) {
            log("Audio playback queue started")
            while (isPlaying) {
                val chunk = audioQueue.poll()
                if (chunk == null) {
                    // Check if more chunks arrive shortly
                    var waited = 0
                    var nextChunk: ByteArray? = null
                    while (waited < 150 && isPlaying) {
                        Thread.sleep(20)
                        waited += 20
                        nextChunk = audioQueue.poll()
                        if (nextChunk != null) break
                    }
                    if (nextChunk != null) {
                        playChunk(nextChunk)
                    } else {
                        break
                    }
                } else {
                    playChunk(chunk)
                }
            }
            _outputAmplitude.value = 0f
            isPlaying = false
            _isPlayingState.value = false
            log("Audio playback queue idle/finished")
        }
    }

    private fun playChunk(chunk: ByteArray) {
        try {
            val track = audioTrack ?: return
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
                log("AudioTrack resumed to PLAYING")
            }
            // Calculate RMS amplitude for UI audio visualizer
            val amp = calculateRms(chunk)
            _outputAmplitude.value = amp

            var offset = 0
            while (offset < chunk.size && isPlaying) {
                val written = track.write(chunk, offset, chunk.size - offset)
                if (written > 0) {
                    offset += written
                } else {
                    log("AudioTrack write warning: code $written")
                    break
                }
            }
        } catch (e: Exception) {
            log("Audio playback error: ${e.message}")
            Log.e(TAG, "Chunk playback failed", e)
        }
    }

    private fun calculateRms(pcmBytes: ByteArray): Float {
        if (pcmBytes.size < 2) return 0f
        var sumSquare = 0.0
        val sampleCount = pcmBytes.size / 2
        for (i in 0 until sampleCount) {
            val low = pcmBytes[i * 2].toInt() and 0xFF
            val high = pcmBytes[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            sumSquare += (sample * sample).toDouble()
        }
        val meanSquare = sumSquare / sampleCount
        val rms = sqrt(meanSquare)
        // Normalize 0..32767 to 0..1
        return (rms / 12000.0).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Instantly stops audio playback, flushes the hardware AudioTrack,
     * and clears the pending audio queue when an interruption occurs.
     */
    @Synchronized
    fun stopAndClearQueue() {
        log("Audio playback interrupted - clearing queue")
        isPlaying = false
        audioQueue.clear()
        playbackJob?.cancel()
        playbackJob = null
        _outputAmplitude.value = 0f
        _isPlayingState.value = false

        try {
            audioTrack?.apply {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                    pause()
                    flush()
                }
            }
            log("AudioTrack flushed and paused")
        } catch (e: Exception) {
            log("AudioTrack flush error: ${e.message}")
        }
    }

    /**
     * Speaker Diagnostic Test:
     * Generates a pure 440Hz sine wave tone for 1000ms using the exact same AudioTrack
     * and plays it directly through the speaker.
     */
    fun playDiagnosticTone(frequency: Float = 440f, durationMs: Int = 1000) {
        coroutineScope.launch(Dispatchers.IO) {
            log("Testing Speaker: Generating ${frequency.toInt()}Hz tone (${durationMs}ms)...")
            stopAndClearQueue()
            initAudioTrack(16000)

            val sampleRate = 16000
            val numSamples = (durationMs * sampleRate) / 1000
            val pcmData = ByteArray(numSamples * 2)

            for (i in 0 until numSamples) {
                val angle = 2.0 * PI * i / (sampleRate / frequency)
                // Fade in / fade out envelope to avoid click sounds
                val envelope = when {
                    i < 800 -> i / 800.0
                    i > numSamples - 800 -> (numSamples - i) / 800.0
                    else -> 1.0
                }
                val sampleValue = (sin(angle) * 20000.0 * envelope).toInt().coerceIn(-32767, 32767)
                pcmData[i * 2] = (sampleValue and 0xFF).toByte()
                pcmData[i * 2 + 1] = ((sampleValue shr 8) and 0xFF).toByte()
            }

            log("Speaker test tone generated (${pcmData.size} bytes). Playing via AudioTrack...")
            queuePcmChunk(pcmData, 16000)
        }
    }

    fun release() {
        isPlaying = false
        audioQueue.clear()
        playbackJob?.cancel()
        playbackJob = null
        try {
            audioTrack?.apply {
                stop()
                release()
            }
            audioTrack = null
            log("AudioTrack released")
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack release error", e)
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        onLog(message)
    }
}
