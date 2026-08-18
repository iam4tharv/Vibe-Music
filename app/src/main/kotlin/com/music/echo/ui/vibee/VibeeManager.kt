package com.music.echo.ui.vibee

import androidx.annotation.Keep
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.music.echo.BuildConfig
import com.music.echo.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.core.edit
import com.music.echo.constants.*
import com.music.echo.utils.dataStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import timber.log.Timber
import java.util.Locale
import java.io.IOException

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.call.body
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable


import com.music.echo.models.toMediaMetadata
import com.music.echo.extensions.toMediaItem

enum class VibeeState {
    IDLE, LISTENING, THINKING, SPEAKING
}

class VibeeManager(
    private val context: Context,
    private val playerConnection: PlayerConnection?,
    private val coroutineScope: CoroutineScope
) {
    val state = MutableStateFlow(VibeeState.IDLE)
    val recognizedText = MutableStateFlow("")
    val spokenResponse = MutableStateFlow("")
    val rmsFlow = MutableStateFlow(0f)

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    
    private var isTtsReady = false
    private var intentJob: Job? = null
    private var wasPlayingBeforeVibee = false


    private val httpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
        }
    }


    init {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Timber.w("TTS Language US not supported or missing data")
                }
                isTtsReady = true
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        state.value = VibeeState.SPEAKING
                        coroutineScope.launch {
                            var loopCount = 0
                            while(state.value == VibeeState.SPEAKING && loopCount < 200) { // max 10 seconds timeout
                                rmsFlow.value = (Math.random() * 0.8 + 0.2).toFloat()
                                kotlinx.coroutines.delay(50)
                                loopCount++
                            }
                            rmsFlow.value = 0f
                        }
                    }
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == "vibee_tts_end") {
                            coroutineScope.launch(Dispatchers.Main) {
                                abandonAudioFocus()
                                state.value = VibeeState.IDLE; startWakeWordListeningIfNeeded()
                                if (wasPlayingBeforeVibee) {
                                    playerConnection?.player?.play()
                                    wasPlayingBeforeVibee = false
                                }
                            }
                        } else {
                            coroutineScope.launch(Dispatchers.Main) {
                                state.value = VibeeState.THINKING
                            }
                        }
                    }
                    override fun onError(utteranceId: String?) {
                        coroutineScope.launch(Dispatchers.Main) {
                            abandonAudioFocus()
                            state.value = VibeeState.IDLE; startWakeWordListeningIfNeeded()
                            if (wasPlayingBeforeVibee) {
                                playerConnection?.player?.play()
                                wasPlayingBeforeVibee = false
                            }
                        }
                    }
                })
            }
        }
    }

    fun startListening() {
        coroutineScope.launch(Dispatchers.Main) {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Timber.e("Speech recognition not available")
                state.value = VibeeState.IDLE; startWakeWordListeningIfNeeded()
                respond("Speech recognition is not available on this device.")
                return@launch
            }
            
            requestAudioFocus()
            wasPlayingBeforeVibee = playerConnection?.player?.isPlaying == true
            playerConnection?.player?.pause()
            
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        state.value = VibeeState.LISTENING
                        recognizedText.value = "Listening..."
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {
                        if (state.value == VibeeState.LISTENING) rmsFlow.value = rmsdB / 10f
                    }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        state.value = VibeeState.THINKING
                        rmsFlow.value = 0f
                    }
                    override fun onError(error: Int) {
                        coroutineScope.launch(Dispatchers.Main) {
                            abandonAudioFocus()
                            state.value = VibeeState.IDLE; startWakeWordListeningIfNeeded()
                            
                            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                                speak("I need microphone permission.")
                            }
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        recognizedText.value = text
                        if (text.isNotBlank()) {
                            processUserIntent(text)
                        } else {
                            coroutineScope.launch(Dispatchers.Main) {
                                abandonAudioFocus()
                                state.value = VibeeState.IDLE; startWakeWordListeningIfNeeded()
                            }
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        recognizedText.value = text
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 120000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 120000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 120000L)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            speechRecognizer?.startListening(intent)
        }
    }

    fun stopListening() { 
        coroutineScope.launch(Dispatchers.Main) {
            speechRecognizer?.stopListening()
        }
    }
    
    private var isWakeWordListening = false
    private var wakeWordRecognizer: SpeechRecognizer? = null

    fun startWakeWordListeningIfNeeded() {
        coroutineScope.launch {
            val enabled = context.dataStore.data.first()[HeyVibeeEnabledKey] ?: false
            if (enabled && state.value == VibeeState.IDLE && !isWakeWordListening) {
                startWakeWordRecognizer()
            }
        }
    }

    fun stopWakeWordListening() {
        isWakeWordListening = false
        coroutineScope.launch(Dispatchers.Main) {
            wakeWordRecognizer?.destroy()
            wakeWordRecognizer = null
        }
    }

    private fun startWakeWordRecognizer() {
        coroutineScope.launch(Dispatchers.Main) {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) return@launch
            isWakeWordListening = true
            
            wakeWordRecognizer?.destroy()
            wakeWordRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        if (isWakeWordListening && state.value == VibeeState.IDLE) {
                            coroutineScope.launch(Dispatchers.Main) {
                                kotlinx.coroutines.delay(1000)
                                startWakeWordRecognizer()
                            }
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        if (text.lowercase(Locale.getDefault()).contains("hey vibee") || text.lowercase(Locale.getDefault()).contains("hey vibe")) {
                            isWakeWordListening = false
                            startListening()
                        } else if (isWakeWordListening && state.value == VibeeState.IDLE) {
                            coroutineScope.launch(Dispatchers.Main) {
                                startWakeWordRecognizer()
                            }
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        if (text.lowercase(Locale.getDefault()).contains("hey vibee") || text.lowercase(Locale.getDefault()).contains("hey vibe")) {
                            isWakeWordListening = false
                            wakeWordRecognizer?.cancel()
                            startListening()
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val isMusicActive = am.isMusicActive
            // Play a beep so user knows they can speak
            try {
                val toneGen = android.media.ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
                kotlinx.coroutines.delay(200)
                toneGen.release()
            } catch (e: Exception) {
                Timber.e(e, "Error playing beep")
            }
            // Optionally try to mute to prevent beep, but we just start it
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 120000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 120000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 120000L)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            wakeWordRecognizer?.startListening(intent)
        }
    }
    fun close() {
        intentJob?.cancel()
        state.value = VibeeState.IDLE; startWakeWordListeningIfNeeded()
        abandonAudioFocus()
        textToSpeech?.stop()
        coroutineScope.launch(Dispatchers.Main) {
            if (wasPlayingBeforeVibee) {
                playerConnection?.player?.play()
                wasPlayingBeforeVibee = false
            }
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
    }

    private fun processUserIntent(text: String) {
        state.value = VibeeState.THINKING
        intentJob?.cancel()
        intentJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                var currentTrack = "None"
                var currentArtist = "None"
                withContext(Dispatchers.Main) {
                    currentTrack = playerConnection?.player?.currentMediaItem?.mediaMetadata?.title?.toString() ?: "None"
                    currentArtist = playerConnection?.player?.currentMediaItem?.mediaMetadata?.artist?.toString() ?: "None"
                }
                
                val lowerText = text.lowercase(Locale.getDefault()).trim()
                
                // Fast-path local command matching to bypass LLM unreliability
                if (lowerText.startsWith("play ") && lowerText.length > 5) {
                    val query = text.substring(5).trim()
                    executePlaySong(query)
                    return@launch
                } else if (lowerText == "pause" || lowerText == "stop" || lowerText == "stop music") {
                    executePause()
                    return@launch
                } else if (lowerText == "next" || lowerText == "skip" || lowerText == "next song") {
                    executeNext()
                    return@launch
                } else if (lowerText == "play" || lowerText == "resume") {
                    executeResume()
                    return@launch
                } else if (lowerText.contains("what song is this") || lowerText.contains("what is playing") || lowerText.contains("what's playing")) {
                    val msg = if (currentTrack != "None") "This is $currentTrack by $currentArtist." else "Nothing is currently playing."
                    respond(msg)
                    return@launch
                }
                
                val prompt = """
                    You are Vibee, a friendly, conversational AI voice assistant for a music app. You can chat with the user about anything, including music recommendations, casual conversation, jokes, or general questions.
                    Currently playing: $currentTrack by $currentArtist
                    
                    The user said: "$text"
                    
                    Determine the user's intent and output a JSON object.
                    Rules:
                    1. If the user EXPLICITLY wants to play a specific song or artist, output:
                       {"action": "play_song", "query": "song/artist name"}
                    2. If the user just says "play", "resume", or "play any song", output:
                       {"action": "play"}
                    3. If the user wants to pause or stop, output:
                       {"action": "pause"}
                    4. If the user wants to skip, play next, or go back, output:
                       {"action": "next"}
                    5. If the user wants to add a song to the queue, output:
                       {"action": "add_to_queue", "query": "song name"}
                    6. If the user wants to remove a song from the queue, output:
                       {"action": "remove_from_queue", "query": "song name"}
                    7. If the user wants to move/rearrange a song in the queue, output:
                       {"action": "move_in_queue", "query": "song name", "to": "position (like 'next', 'last', or index number)"}
                    8. If the user wants to change a setting, output:
                       {"action": "toggle_setting", "setting": "SETTING_KEY", "value": true/false}
                       Choose the most appropriate SETTING_KEY from this list (approximate matching is fine): ${SettingToggleHelper.ALL_SETTINGS.joinToString(", ")}.
                    9. For anything else (casual chat, jokes, questions, unrecognized), output:
                       {"action": "chat", "message": "your friendly, natural voice response"}
                    
                    CRITICAL: You MUST ALWAYS return a JSON object. NEVER return an empty response. Output ONLY valid JSON. Do not include markdown formatting.
                """.trimIndent()
                
                val req = OpenRouterRequest(
                    model = "meta-llama/llama-3.2-3b-instruct",
                    messages = listOf(Message(role = "user", content = prompt)),
                    response_format = OpenRouterResponseFormat(type = "json_object")
                )
                
                val openRouterKey = try {
                    val key = context.dataStore.data.first()[com.music.echo.constants.OpenRouterApiKey]
                    if (!key.isNullOrEmpty()) key else BuildConfig.VIBEE
                } catch (e: IOException) {
                    BuildConfig.VIBEE
                }

                if (openRouterKey.isEmpty()) {
                    respond("Please add your OpenRouter API key in the Secrets panel.")
                    return@launch
                }
                
                var response: OpenRouterResponse? = null
                for (attempt in 1..2) {

                    response = withTimeoutOrNull(10000) {
                        try {
                            httpClient.post("https://openrouter.ai/api/v1/chat/completions") {
                                contentType(ContentType.Application.Json)
                                header("Authorization", "Bearer $openRouterKey")
                                setBody(req)
                            }.body<OpenRouterResponse>()
                        } catch (e: Exception) {
                            null
                        }
                    }

                    if (response != null) break
                }
                
                if (response == null) {
                    respond("Connection timed out.")
                    return@launch
                }
                
                val jsonRes = response.choices?.firstOrNull()?.message?.content
                
                if (jsonRes.isNullOrBlank()) {
                    Timber.w("Vibee API returned empty content")
                    respond("I didn't get a response.")
                    return@launch
                }
                
                var action = "chat"
                var query = ""
                var msg = "I'm listening."
                try {
                    val jsonStart = jsonRes.indexOf('{')
                    val jsonEnd = jsonRes.lastIndexOf('}')
                    if (jsonStart != -1 && jsonEnd != -1 && jsonEnd >= jsonStart) {
                        val jsonString = jsonRes.substring(jsonStart, jsonEnd + 1)
                        val jsonObj = org.json.JSONObject(jsonString)
                        action = jsonObj.optString("action", "chat")
                        if (action == "chat") {
                            // If the AI forgot the "message" key, fall back to "I heard you" instead of a confusing error string
                            msg = jsonObj.optString("message", "I heard you.")
                        } else if (action == "play_song") {
                            query = jsonObj.optString("query", "")
                            if (query.isBlank() || query == "null" || query == "undefined") {
                                action = "play"
                                msg = "Playing."
                            } else {
                                msg = "Playing $query."
                            }
                        } else if (action == "play") {
                            msg = "Playing."
                        } else if (action == "pause") {
                            msg = "Paused."
                        } else if (action == "next") {
                            msg = "Skipping."
                        } else if (action == "add_to_queue") {
                            val q = jsonObj.optString("query", "")
                            if (q.isNotBlank()) executeAddToQueue(q)
                            else msg = "I couldn't add that."
                            return@launch
                        } else if (action == "remove_from_queue") {
                            val q = jsonObj.optString("query", "")
                            if (q.isNotBlank()) executeRemoveFromQueue(q)
                            else msg = "I couldn't remove that."
                            return@launch
                        } else if (action == "move_in_queue") {
                            val q = jsonObj.optString("query", "")
                            val to = jsonObj.optString("to", "next")
                            if (q.isNotBlank()) executeMoveInQueue(q, to)
                            else msg = "I couldn't move that."
                            return@launch
                        } else if (action == "toggle_setting") {
                            val settingName = jsonObj.optString("setting", "")
                            val value = jsonObj.optBoolean("value", false)
                            if (settingName.isNotBlank()) executeToggleSetting(settingName, value)
                            else msg = "I couldn't change that setting."
                            return@launch
                        } else {
                            msg = "Done."
                        }
                    } else {
                        // Fallback: If no JSON brackets were found, the small model might have just output raw text.
                        // Or it might be a weird error string.
                        val lowerRes = jsonRes.lowercase()
                        if (lowerRes.contains("\"action\": \"play_song\"") || (lowerRes.contains("play") && lowerRes.contains("song"))) {
                            action = "play_song"
                            query = text // default to the user's raw text as the query
                            msg = "Searching for your music."
                        } else if (lowerRes.contains("\"action\": \"play\"") || lowerRes == "play" || lowerRes == "resume") {
                            action = "play"
                            msg = "Playing."
                        } else if (lowerRes.contains("\"action\": \"pause\"") || lowerRes == "pause" || lowerRes == "stop") {
                            action = "pause"
                            msg = "Paused."
                        } else if (lowerRes.contains("\"action\": \"next\"") || lowerRes == "next" || lowerRes == "skip") {
                            action = "next"
                            msg = "Skipping."
                        } else {
                            // If we can't detect any known commands, treat the entire string as a chat response!
                            action = "chat"
                            msg = jsonRes.trim()
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error parsing JSON from OpenRouter")
                    // If parsing completely failed but we have text, just treat it as a chat message
                    action = "chat"
                    msg = jsonRes.trim()
                }
                
                if (action == "play_song" && query.isNotBlank() && query != "null" && query != "undefined") {
                    executePlaySong(query)
                } else if (action == "play") { 
                    executeResume()
                } else if (action == "pause") { 
                    executePause()
                } else if (action == "next") { 
                    executeNext()
                } else {
                    respond(msg)
                }
            } catch (e: io.ktor.client.plugins.ClientRequestException) {
                Timber.e(e, "HTTP Error processing Vibee intent: ${e.response.status.value} - ${e.message}")
                respond("API Error ${e.response.status.value}. Check your API key or model name.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Error processing Vibee intent")
                respond("Sorry, error.")
            }
        }
    }

    private suspend fun executePlaySong(query: String) {
        wasPlayingBeforeVibee = false
        val result = withTimeoutOrNull(10000) {
            com.music.innertube.YouTube.search(query, com.music.innertube.YouTube.SearchFilter.FILTER_SONG).getOrNull()
        }
        val topResult = result?.items?.filterIsInstance<com.music.innertube.models.SongItem>()?.firstOrNull()
        
        withContext(Dispatchers.Main) {
            if (topResult != null) {
                if (playerConnection?.player != null) {
                    playerConnection.playQueue(com.music.echo.playback.queues.YouTubeQueue.radio(topResult.toMediaMetadata()))
                    respond("Playing ${topResult.title}.", endSession = true)
                } else {
                    respond("Player is not ready.", endSession = true)
                }
            } else {
                respond("I couldn't find that song.", endSession = true)
            }
        }
    }

    private suspend fun executeResume() {
        wasPlayingBeforeVibee = false
        withContext(Dispatchers.Main) { 
            if (playerConnection?.player != null) {
                playerConnection?.player?.play() 
                respond("Playing.", endSession = true)
            } else {
                respond("Player is not ready.", endSession = true)
            }
        }
    }

    private suspend fun executePause() {
        wasPlayingBeforeVibee = false
        withContext(Dispatchers.Main) { 
            if (playerConnection?.player != null) {
                playerConnection?.player?.pause()
                respond("Paused.", endSession = true)
            } else {
                respond("Player is not ready.", endSession = true)
            }
        }
    }

    private suspend fun executeNext() {
        withContext(Dispatchers.Main) { 
            if (playerConnection?.player != null) {
                playerConnection?.player?.seekToNext()
                respond("Skipping.", endSession = true)
            } else {
                respond("Player is not ready.", endSession = true)
            }
        }
    }

    private suspend fun executeAddToQueue(query: String) {
        respond("Adding $query to queue...", endSession = false)
        val result = withTimeoutOrNull(10000) {
            com.music.innertube.YouTube.search(query, com.music.innertube.YouTube.SearchFilter.FILTER_SONG).getOrNull()
        }
        val topResult = result?.items?.filterIsInstance<com.music.innertube.models.SongItem>()?.firstOrNull()
        
        withContext(Dispatchers.Main) {
            if (topResult != null) {
                if (playerConnection?.player != null) {
                    playerConnection.addToQueue(topResult.toMediaMetadata().toMediaItem())
                    respond("Added ${topResult.title} to queue.", endSession = true)
                } else {
                    respond("Player is not ready.", endSession = true)
                }
            } else {
                respond("I couldn't find that song.", endSession = true)
            }
        }
    }

    private suspend fun executeRemoveFromQueue(query: String) {
        withContext(Dispatchers.Main) {
            val player = playerConnection?.player
            if (player != null) {
                var foundIndex = -1
                var foundTitle = ""
                for (i in 0 until player.mediaItemCount) {
                    val item = player.getMediaItemAt(i)
                    val title = item.mediaMetadata.title?.toString() ?: ""
                    if (title.equals(query, ignoreCase = true)) {
                        foundIndex = i
                        foundTitle = title
                        break
                    }
                }
                if (foundIndex == -1) {
                    for (i in 0 until player.mediaItemCount) {
                        val item = player.getMediaItemAt(i)
                        val title = item.mediaMetadata.title?.toString() ?: ""
                        if (title.contains(query, ignoreCase = true)) {
                            foundIndex = i
                            foundTitle = title
                            break
                        }
                    }
                }
                if (foundIndex != -1) {
                    player.removeMediaItem(foundIndex)
                    respond("Removed $foundTitle from queue.", endSession = true)
                } else {
                    respond("I couldn't find that song in the queue.", endSession = true)
                }
            } else {
                respond("Player is not ready.", endSession = true)
            }
        }
    }

    private suspend fun executeMoveInQueue(query: String, to: String) {
        wasPlayingBeforeVibee = false
        withContext(Dispatchers.Main) {
            val player = playerConnection?.player
            if (player == null) {
                respond("Player is not ready.", endSession = true)
                return@withContext
            }
            val windows = playerConnection?.queueWindows?.value ?: emptyList()
            val currentIndex = windows.indexOfFirst { it.mediaItem.mediaMetadata.title?.toString()?.contains(query, ignoreCase = true) == true }
            if (currentIndex != -1) {
                val currentWindowIndex = player.currentMediaItemIndex
                var newIndex = currentWindowIndex + 1
                if (to.contains("last", ignoreCase = true) || to.contains("end", ignoreCase = true)) {
                    newIndex = windows.size - 1
                } else if (to.matches(Regex(".*\\d+.*"))) {
                    val number = Regex("\\d+").find(to)?.value?.toIntOrNull() ?: 1
                    newIndex = number - 1
                }
                newIndex = newIndex.coerceIn(0, windows.size - 1)
                player.moveMediaItem(currentIndex, newIndex)
                respond("Moved song in the queue.", endSession = true)
            } else {
                respond("I couldn't find that song in the queue.", endSession = true)
            }
        }
    }

    private suspend fun executeToggleSetting(setting: String, value: Boolean) {
        val success = SettingToggleHelper.toggleSetting(context, setting, value)
        if (success) {
            val status = if (value) "on" else "off"
            respond("Turned $status $setting.", endSession = true)
        } else {
            respond("I don't know that setting.", endSession = true)
        }
    }

    private suspend fun respond(text: String, endSession: Boolean = true) {
        withContext(Dispatchers.Main) {
            spokenResponse.value = text
            speak(text, endSession)
        }
    }

    private fun speak(text: String, endSession: Boolean = true) {
        if (!isTtsReady) {
            if (endSession) {
                coroutineScope.launch(Dispatchers.Main) {
                    abandonAudioFocus()
                    state.value = VibeeState.IDLE; startWakeWordListeningIfNeeded()
                    if (wasPlayingBeforeVibee) {
                        playerConnection?.player?.play()
                        wasPlayingBeforeVibee = false
                    }
                }
            }
            return
        }
        
        // Duck the music while speaking by resuming it and re-requesting MAY_DUCK focus
        if (wasPlayingBeforeVibee) {
            playerConnection?.player?.play()
            requestAudioFocus()
        }

        val utteranceId = if (endSession) "vibee_tts_end" else "vibee_tts_continue"
        val result = textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.SUCCESS) {
            state.value = VibeeState.SPEAKING
        } else {
            if (endSession) {
                coroutineScope.launch(Dispatchers.Main) {
                    abandonAudioFocus()
                    state.value = VibeeState.IDLE; startWakeWordListeningIfNeeded()
                    if (wasPlayingBeforeVibee) {
                        playerConnection?.player?.play()
                        wasPlayingBeforeVibee = false
                    }
                }
            }
        }
    }

    private fun requestAudioFocus() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build()
            audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun abandonAudioFocus() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    fun release() {
        intentJob?.cancel()
        abandonAudioFocus()
        textToSpeech?.shutdown()
        isTtsReady = false
        coroutineScope.launch(Dispatchers.Main) {
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
    }
}

@Serializable
@Keep
data class OpenRouterResponseFormat(
    val type: String
)

@Serializable
@Keep
data class OpenRouterRequest(
    val model: String,
    val messages: List<Message>,
    val response_format: OpenRouterResponseFormat? = null
)

@Serializable
@Keep
data class Message(
    val role: String,
    val content: String
)

@Serializable
@Keep
data class OpenRouterResponse(
    val choices: List<Choice>? = null
)

@Serializable
@Keep
data class Choice(
    val message: Message? = null
)


