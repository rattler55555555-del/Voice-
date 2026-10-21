package com.example.gemini

import android.util.Log
import com.example.actions.DeviceActionManager
import com.example.audio.AudioTrackPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiLiveService(
    private val coroutineScope: CoroutineScope,
    private val audioTrackPlayer: AudioTrackPlayer,
    private val deviceActionManager: DeviceActionManager,
    private val onLog: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "GeminiLiveService"
        private const val LIVE_WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
        private const val REST_MODEL_AUDIO =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-native-audio-preview-12-2025:generateContent"

        const val ARUSHI_SYSTEM_INSTRUCTION =
            "You are Arushi, a young, confident, witty, playful, and emotionally responsive virtual assistant. " +
            "Talk naturally and casually like a close friend. Be expressive, slightly teasing, funny, and smart when appropriate. " +
            "Use light sarcasm and witty responses. Never sound robotic. Adapt your tone to the user's emotions and conversation. " +
            "Automatically understand and respond in the language the user is speaking (e.g. Hindi, English, Hinglish, Marathi, etc.). " +
            "Keep responses natural, engaging, and concise enough for real-time voice conversation. " +
            "You can execute safe supported device actions through available tools (such as openWhatsApp, openApp, openWebsite, makeCall, callContact). " +
            "Never claim that an action was completed unless the application actually executed it. " +
            "Avoid explicit or inappropriate content while maintaining your charm, confidence, and personality."
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Indefinite read timeout for WebSocket
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var activeWebSocket: WebSocket? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private val _lastActionMessage = MutableStateFlow<String?>(null)
    val lastActionMessage: StateFlow<String?> = _lastActionMessage.asStateFlow()

    fun connectLiveSession(apiKey: String) {
        if (apiKey.isBlank()) {
            log("Error: Gemini API key is missing or empty")
            return
        }

        disconnect()
        val url = "$LIVE_WS_URL?key=$apiKey"
        log("Gemini session connecting to Gemini Live API...")

        val request = Request.Builder().url(url).build()
        activeWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                log("Gemini session connected! Initializing setup packet...")
                _isConnected.value = true
                sendSetupMessage(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(webSocket, text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleServerMessage(webSocket, bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                log("Gemini session closing: $code / $reason")
                _isConnected.value = false
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                log("Gemini session disconnected: $code / $reason")
                _isConnected.value = false
                _isSpeaking.value = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                log("Gemini session connection error: ${t.message} (HTTP ${response?.code})")
                Log.e(TAG, "WebSocket failure", t)
                _isConnected.value = false
                _isSpeaking.value = false
            }
        })
    }

    private fun sendSetupMessage(webSocket: WebSocket) {
        try {
            val setupObj = JSONObject()
            val setupBody = JSONObject()

            // Using models/gemini-2.0-flash-exp (or gemini-2.5-flash-native-audio-preview-12-2025)
            setupBody.put("model", "models/gemini-2.0-flash-exp")

            // Generation config with AUDIO modality and Aoede voice
            val genConfig = JSONObject()
            val responseModalities = JSONArray()
            responseModalities.put("AUDIO")
            genConfig.put("responseModalities", responseModalities)

            val speechConfig = JSONObject()
            val voiceConfig = JSONObject()
            val prebuilt = JSONObject()
            prebuilt.put("voiceName", "Aoede")
            voiceConfig.put("prebuiltVoiceConfig", prebuilt)
            speechConfig.put("voiceConfig", voiceConfig)
            genConfig.put("speechConfig", speechConfig)
            setupBody.put("generationConfig", genConfig)

            // System instruction
            val sysInst = JSONObject()
            val sysParts = JSONArray()
            val sysPart = JSONObject()
            sysPart.put("text", ARUSHI_SYSTEM_INSTRUCTION)
            sysParts.put(sysPart)
            sysInst.put("parts", sysParts)
            setupBody.put("systemInstruction", sysInst)

            // Tools / function declarations
            val toolsArray = JSONArray()
            val toolObj = JSONObject()
            val funcDeclarations = JSONArray()

            // 1. openWhatsApp
            val openWhatsAppFn = JSONObject().apply {
                put("name", "openWhatsApp")
                put("description", "Opens WhatsApp application or chat on the device. Call when user asks to open WhatsApp or message someone on WhatsApp.")
            }
            funcDeclarations.put(openWhatsAppFn)

            // 2. openApp
            val openAppFn = JSONObject().apply {
                put("name", "openApp")
                put("description", "Opens a supported app on the user's phone (e.g. YouTube, Instagram, Spotify, Chrome, Camera, Maps, Clock, Settings).")
                val params = JSONObject().apply {
                    put("type", "OBJECT")
                    val props = JSONObject().apply {
                        put("appName", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Name of the application to open")
                        })
                    }
                    put("properties", props)
                    put("required", JSONArray().apply { put("appName") })
                }
                put("parameters", params)
            }
            funcDeclarations.put(openAppFn)

            // 3. openWebsite
            val openWebsiteFn = JSONObject().apply {
                put("name", "openWebsite")
                put("description", "Opens a website URL in the browser.")
                val params = JSONObject().apply {
                    put("type", "OBJECT")
                    val props = JSONObject().apply {
                        put("url", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "The website URL to navigate to")
                        })
                    }
                    put("properties", props)
                    put("required", JSONArray().apply { put("url") })
                }
                put("parameters", params)
            }
            funcDeclarations.put(openWebsiteFn)

            // 4. makeCall
            val makeCallFn = JSONObject().apply {
                put("name", "makeCall")
                put("description", "Dials a phone number by opening the Android dialer.")
                val params = JSONObject().apply {
                    put("type", "OBJECT")
                    val props = JSONObject().apply {
                        put("phoneNumber", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Phone number to call")
                        })
                    }
                    put("properties", props)
                    put("required", JSONArray().apply { put("phoneNumber") })
                }
                put("parameters", params)
            }
            funcDeclarations.put(makeCallFn)

            // 5. callContact
            val callContactFn = JSONObject().apply {
                put("name", "callContact")
                put("description", "Finds a contact by name in the user's phone contacts (e.g. Mom, Mummy, Dad, Rahul) and starts a call.")
                val params = JSONObject().apply {
                    put("type", "OBJECT")
                    val props = JSONObject().apply {
                        put("contactName", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Name of the contact to call")
                        })
                    }
                    put("properties", props)
                    put("required", JSONArray().apply { put("contactName") })
                }
                put("parameters", params)
            }
            funcDeclarations.put(callContactFn)

            toolObj.put("functionDeclarations", funcDeclarations)
            toolsArray.put(toolObj)
            setupBody.put("tools", toolsArray)

            setupObj.put("setup", setupBody)

            val jsonStr = setupObj.toString()
            webSocket.send(jsonStr)
            log("Setup packet sent with Aoede voice, tools & Arushi personality")
        } catch (e: Exception) {
            log("Failed to send setup message: ${e.message}")
            Log.e(TAG, "sendSetupMessage error", e)
        }
    }

    fun sendPcmAudioChunk(base64Chunk: String) {
        val ws = activeWebSocket ?: return
        if (!_isConnected.value) return

        try {
            val realtimeInput = JSONObject()
            val mediaChunks = JSONArray()
            val chunkObj = JSONObject()
            chunkObj.put("mimeType", "audio/pcm;rate=16000")
            chunkObj.put("data", base64Chunk)
            mediaChunks.put(chunkObj)

            realtimeInput.put("mediaChunks", mediaChunks)
            val root = JSONObject()
            root.put("realtimeInput", realtimeInput)

            ws.send(root.toString())
        } catch (e: Exception) {
            log("Failed to send audio chunk: ${e.message}")
        }
    }

    private fun handleServerMessage(webSocket: WebSocket, jsonText: String) {
        try {
            val root = JSONObject(jsonText)

            if (root.has("setupComplete")) {
                log("Gemini Live session ready for conversation!")
                return
            }

            // Handle serverContent
            if (root.has("serverContent")) {
                val serverContent = root.getJSONObject("serverContent")

                if (serverContent.optBoolean("interrupted", false)) {
                    log("User interrupted - canceling current Arushi playback")
                    audioTrackPlayer.stopAndClearQueue()
                    _isSpeaking.value = false
                    return
                }

                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)

                            // Check text transcript
                            if (part.has("text")) {
                                val txt = part.getString("text")
                                _transcript.value = txt
                                log("Arushi transcript: $txt")
                            }

                            // Check inline audio data
                            if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                val mimeType = inlineData.optString("mimeType", "audio/pcm;rate=24000")
                                val data = inlineData.optString("data", "")
                                if (data.isNotEmpty()) {
                                    val rate = parseSampleRate(mimeType)
                                    log("Response contains audio: mime=$mimeType, rate=$rate, len=${data.length}")
                                    _isSpeaking.value = true
                                    audioTrackPlayer.queueBase64Pcm(data, rate)
                                }
                            }
                        }
                    }
                }

                if (serverContent.optBoolean("turnComplete", false)) {
                    log("Gemini model turn complete")
                }
            }

            // Handle toolCall
            if (root.has("toolCall")) {
                val toolCall = root.getJSONObject("toolCall")
                val functionCalls = toolCall.optJSONArray("functionCalls")
                if (functionCalls != null) {
                    handleFunctionCalls(webSocket, functionCalls)
                }
            }

        } catch (e: Exception) {
            log("Error parsing server message: ${e.message}")
            Log.e(TAG, "handleServerMessage error", e)
        }
    }

    private fun handleFunctionCalls(webSocket: WebSocket, functionCalls: JSONArray) {
        coroutineScope.launch(Dispatchers.Main) {
            val functionResponses = JSONArray()
            for (i in 0 until functionCalls.length()) {
                val call = functionCalls.getJSONObject(i)
                val callId = call.optString("id", "call_$i")
                val fnName = call.optString("name", "")
                val fnArgs = call.optJSONObject("args") ?: JSONObject()

                log("Executing tool call: $fnName (id=$callId)")
                _lastActionMessage.value = "Action: $fnName"

                val result = withContext(Dispatchers.IO) {
                    deviceActionManager.executeTool(fnName, fnArgs)
                }

                val fnResp = JSONObject()
                fnResp.put("id", callId)
                fnResp.put("name", fnName)
                val respPayload = JSONObject()
                respPayload.put("output", result)
                fnResp.put("response", respPayload)
                functionResponses.put(fnResp)
            }

            val toolResponseObj = JSONObject()
            val toolRespWrapper = JSONObject()
            toolRespWrapper.put("functionResponses", functionResponses)
            toolResponseObj.put("toolResponse", toolRespWrapper)

            withContext(Dispatchers.IO) {
                webSocket.send(toolResponseObj.toString())
                log("Tool responses sent back to Gemini Live")
            }
        }
    }

    private fun parseSampleRate(mimeType: String): Int {
        val rateRegex = Regex("rate=(\\d+)")
        val match = rateRegex.find(mimeType)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 24000
    }

    /**
     * Fallback Direct REST invocation with native Audio output
     */
    suspend fun sendRestPromptWithAudio(prompt: String, apiKey: String): Boolean = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            log("Error: API key missing for REST fallback")
            return@withContext false
        }
        try {
            log("Sending REST audio request with prompt: '$prompt'...")
            val requestJson = JSONObject()

            val contents = JSONArray()
            val contentObj = JSONObject()
            val parts = JSONArray()
            val part = JSONObject()
            part.put("text", prompt)
            parts.put(part)
            contentObj.put("parts", parts)
            contents.put(contentObj)
            requestJson.put("contents", contents)

            // System instruction
            val sys = JSONObject()
            val sysParts = JSONArray()
            val sp = JSONObject()
            sp.put("text", ARUSHI_SYSTEM_INSTRUCTION)
            sysParts.put(sp)
            sys.put("parts", sysParts)
            requestJson.put("systemInstruction", sys)

            // Generation config with AUDIO modality and Aoede voice
            val genConfig = JSONObject()
            val modalArray = JSONArray()
            modalArray.put("AUDIO")
            genConfig.put("responseModalities", modalArray)

            val speechConfig = JSONObject()
            val voiceConfig = JSONObject()
            val prebuilt = JSONObject()
            prebuilt.put("voiceName", "Aoede")
            voiceConfig.put("prebuiltVoiceConfig", prebuilt)
            speechConfig.put("voiceConfig", voiceConfig)
            genConfig.put("speechConfig", speechConfig)
            requestJson.put("generationConfig", genConfig)

            val body = requestJson.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$REST_MODEL_AUDIO?key=$apiKey")
                .post(body)
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                log("REST request error: ${response.code} $errBody")
                return@withContext false
            }

            val responseString = response.body?.string() ?: ""
            val resObj = JSONObject(responseString)
            val candidates = resObj.optJSONArray("candidates")
            val candidate = candidates?.optJSONObject(0)
            val resContent = candidate?.optJSONObject("content")
            val resParts = resContent?.optJSONArray("parts")

            if (resParts != null) {
                for (i in 0 until resParts.length()) {
                    val p = resParts.getJSONObject(i)
                    if (p.has("text")) {
                        val txt = p.getString("text")
                        _transcript.value = txt
                        log("Arushi response text: $txt")
                    }
                    if (p.has("inlineData")) {
                        val inline = p.getJSONObject("inlineData")
                        val mime = inline.optString("mimeType", "audio/pcm;rate=24000")
                        val b64 = inline.optString("data", "")
                        if (b64.isNotEmpty()) {
                            val rate = parseSampleRate(mime)
                            log("Received REST audio response: len=${b64.length}, rate=$rate")
                            _isSpeaking.value = true
                            audioTrackPlayer.queueBase64Pcm(b64, rate)
                        }
                    }
                }
                return@withContext true
            }
            return@withContext false
        } catch (e: Exception) {
            log("REST audio call exception: ${e.message}")
            Log.e(TAG, "sendRestPromptWithAudio failed", e)
            return@withContext false
        }
    }

    fun interruptPlayback() {
        log("Manual interruption triggered")
        audioTrackPlayer.stopAndClearQueue()
        _isSpeaking.value = false
    }

    fun disconnect() {
        try {
            activeWebSocket?.close(1000, "User disconnected")
            activeWebSocket = null
            _isConnected.value = false
            _isSpeaking.value = false
            log("Live session disconnected cleanly")
        } catch (e: Exception) {
            Log.e(TAG, "disconnect error", e)
        }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLog(msg)
    }
}
