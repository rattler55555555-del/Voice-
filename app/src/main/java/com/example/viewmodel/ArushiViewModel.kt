package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.actions.DeviceActionManager
import com.example.audio.AudioRecordManager
import com.example.audio.AudioTrackPlayer
import com.example.gemini.GeminiLiveService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AssistantState {
    IDLE,
    CONNECTING,
    LISTENING,
    SPEAKING,
    ERROR
}

class ArushiViewModel(application: Application) : AndroidViewModel(application) {

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    // Diagnostics logs
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private fun addLog(message: String) {
        val timestamp = timeFormat.format(Date())
        val entry = "[$timestamp] $message"
        val current = _logs.value.toMutableList()
        if (current.size >= 120) {
            current.removeAt(0)
        }
        current.add(entry)
        _logs.value = current
    }

    val audioTrackPlayer = AudioTrackPlayer(
        coroutineScope = viewModelScope,
        onLog = { addLog("[AudioOut] $it") }
    )

    val deviceActionManager = DeviceActionManager(
        context = application.applicationContext,
        onLog = { addLog("[Action] $it") }
    )

    val geminiLiveService = GeminiLiveService(
        coroutineScope = viewModelScope,
        audioTrackPlayer = audioTrackPlayer,
        deviceActionManager = deviceActionManager,
        onLog = { addLog("[Gemini] $it") }
    )

    val audioRecordManager = AudioRecordManager(
        coroutineScope = viewModelScope,
        onAudioChunk = { base64Chunk, _ ->
            // Send captured audio chunk directly to active Gemini Live session
            geminiLiveService.sendPcmAudioChunk(base64Chunk)
        },
        onLog = { addLog("[AudioIn] $it") }
    )

    private val _assistantState = MutableStateFlow(AssistantState.IDLE)
    val assistantState: StateFlow<AssistantState> = _assistantState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _customApiKey = MutableStateFlow("")
    val customApiKey: StateFlow<String> = _customApiKey.asStateFlow()

    init {
        // Collect audio playback status to automatically switch between SPEAKING and LISTENING
        viewModelScope.launch {
            audioTrackPlayer.isPlayingState.collect { isPlaying ->
                if (isPlaying) {
                    _assistantState.value = AssistantState.SPEAKING
                } else if (_assistantState.value == AssistantState.SPEAKING) {
                    if (audioRecordManager.isRecordingState.value) {
                        _assistantState.value = AssistantState.LISTENING
                    } else {
                        _assistantState.value = AssistantState.IDLE
                    }
                }
            }
        }

        // Collect WebSocket connection state
        viewModelScope.launch {
            geminiLiveService.isConnected.collect { connected ->
                if (connected && _assistantState.value == AssistantState.CONNECTING) {
                    _assistantState.value = AssistantState.LISTENING
                    addLog("Assistant ready! State transitioned to LISTENING")
                } else if (!connected && (_assistantState.value == AssistantState.LISTENING || _assistantState.value == AssistantState.SPEAKING)) {
                    // Disconnected
                    if (_assistantState.value != AssistantState.IDLE) {
                        addLog("Live session connection lost")
                    }
                }
            }
        }
    }

    fun getEffectiveApiKey(): String {
        val custom = _customApiKey.value.trim()
        if (custom.isNotEmpty()) return custom

        return try {
            val key = BuildConfig.GEMINI_API_KEY
            if (key == "MY_GEMINI_API_KEY" || key.isBlank()) "" else key
        } catch (e: Exception) {
            ""
        }
    }

    fun setCustomApiKey(key: String) {
        _customApiKey.value = key.trim()
        addLog("Updated custom API key in session")
    }

    fun toggleAssistant(hasMicPermission: Boolean) {
        if (!hasMicPermission) {
            _errorMessage.value = "Microphone permission is required to talk to Arushi."
            _assistantState.value = AssistantState.ERROR
            addLog("Microphone permission denied by user")
            return
        }

        val currentState = _assistantState.value
        if (currentState == AssistantState.IDLE || currentState == AssistantState.ERROR) {
            startSession()
        } else {
            stopSession()
        }
    }

    fun startSession() {
        val apiKey = getEffectiveApiKey()
        if (apiKey.isEmpty()) {
            _errorMessage.value = "Please configure your Gemini API Key in the Settings dialog or Secrets panel."
            _assistantState.value = AssistantState.ERROR
            addLog("Error: Missing Gemini API key")
            return
        }

        _errorMessage.value = null
        _assistantState.value = AssistantState.CONNECTING
        addLog("Starting Arushi session...")

        // 1. Initialize hardware AudioTrack
        audioTrackPlayer.initAudioTrack()

        // 2. Start Gemini Live WebSocket session
        geminiLiveService.connectLiveSession(apiKey)

        // 3. Start microphone recording
        val micStarted = audioRecordManager.startRecording()
        if (!micStarted) {
            _errorMessage.value = "Failed to open device microphone."
            _assistantState.value = AssistantState.ERROR
            addLog("Failed to start audio recording")
            stopSession()
        }
    }

    fun stopSession() {
        addLog("Stopping Arushi session...")
        audioRecordManager.stopRecording()
        geminiLiveService.disconnect()
        audioTrackPlayer.stopAndClearQueue()
        _assistantState.value = AssistantState.IDLE
    }

    fun interruptSpeaking() {
        addLog("User interrupted speaking")
        geminiLiveService.interruptPlayback()
        if (audioRecordManager.isRecordingState.value) {
            _assistantState.value = AssistantState.LISTENING
        } else {
            _assistantState.value = AssistantState.IDLE
        }
    }

    fun runSpeakerTest() {
        addLog("Running Speaker Diagnostic Test...")
        audioTrackPlayer.playDiagnosticTone(440f, 1000)
    }

    fun sendQuickPrompt(promptText: String) {
        val apiKey = getEffectiveApiKey()
        if (apiKey.isEmpty()) {
            _errorMessage.value = "Please set your Gemini API key first."
            return
        }
        addLog("Sending quick prompt: '$promptText'")
        _assistantState.value = AssistantState.CONNECTING
        viewModelScope.launch(Dispatchers.IO) {
            val success = geminiLiveService.sendRestPromptWithAudio(promptText, apiKey)
            if (!success) {
                addLog("REST audio generation was not successful")
                _assistantState.value = AssistantState.IDLE
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
        if (_assistantState.value == AssistantState.ERROR) {
            _assistantState.value = AssistantState.IDLE
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        stopSession()
        audioTrackPlayer.release()
    }
}
