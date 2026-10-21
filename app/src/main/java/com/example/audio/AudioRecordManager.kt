package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class AudioRecordManager(
    private val coroutineScope: CoroutineScope,
    private val onAudioChunk: (base64Chunk: String, rawBytes: ByteArray) -> Unit,
    private val onLog: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "AudioRecordManager"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SIZE_SAMPLES = 1024 // 64ms at 16kHz
    }

    private var audioRecord: AudioRecord? = null

    @Volatile
    private var isRecording = false

    private var recordJob: Job? = null

    private val _inputAmplitude = MutableStateFlow(0f)
    val inputAmplitude: StateFlow<Float> = _inputAmplitude.asStateFlow()

    private val _isRecordingState = MutableStateFlow(false)
    val isRecordingState: StateFlow<Boolean> = _isRecordingState.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        if (isRecording) {
            log("Microphone already active")
            return true
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            log("Invalid AudioRecord minBufferSize: $minBufferSize")
            return false
        }
        val bufferSize = (minBufferSize * 2).coerceAtLeast(CHUNK_SIZE_SAMPLES * 2 * 2)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                log("AudioRecord failed to initialize (state=${audioRecord?.state})")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            audioRecord?.startRecording()
            isRecording = true
            _isRecordingState.value = true
            log("Microphone started successfully (16kHz, 16-bit mono)")

            recordJob = coroutineScope.launch(Dispatchers.IO) {
                val shortBuffer = ShortArray(CHUNK_SIZE_SAMPLES)
                val byteBuffer = ByteArray(CHUNK_SIZE_SAMPLES * 2)

                while (isActive && isRecording) {
                    val record = audioRecord ?: break
                    val readSamples = record.read(shortBuffer, 0, CHUNK_SIZE_SAMPLES)

                    if (readSamples > 0) {
                        // Calculate RMS amplitude for UI feedback
                        var sumSquare = 0.0
                        for (i in 0 until readSamples) {
                            val sample = shortBuffer[i].toInt()
                            sumSquare += (sample * sample).toDouble()

                            // Little-endian byte conversion
                            byteBuffer[i * 2] = (sample and 0xFF).toByte()
                            byteBuffer[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                        }
                        val rms = sqrt(sumSquare / readSamples)
                        val normalizedAmp = (rms / 9000.0).toFloat().coerceIn(0f, 1f)
                        _inputAmplitude.value = normalizedAmp

                        val validByteCount = readSamples * 2
                        val chunkBytes = if (validByteCount == byteBuffer.size) {
                            byteBuffer.clone()
                        } else {
                            byteBuffer.copyOf(validByteCount)
                        }

                        val base64Data = Base64.encodeToString(chunkBytes, Base64.NO_WRAP)
                        onAudioChunk(base64Data, chunkBytes)
                    } else if (readSamples < 0) {
                        log("AudioRecord read error: code $readSamples")
                    }
                }
                _inputAmplitude.value = 0f
                log("AudioRecord recording loop exited")
            }
            return true
        } catch (e: Exception) {
            log("AudioRecord exception: ${e.message}")
            Log.e(TAG, "Failed to start AudioRecord", e)
            stopRecording()
            return false
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        log("Stopping microphone capture...")
        isRecording = false
        _isRecordingState.value = false
        _inputAmplitude.value = 0f

        recordJob?.cancel()
        recordJob = null

        try {
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
                release()
            }
            audioRecord = null
            log("Microphone stopped and released")
        } catch (e: Exception) {
            log("Error stopping AudioRecord: ${e.message}")
            Log.e(TAG, "AudioRecord stop error", e)
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        onLog(message)
    }
}
