package com.music.echo.ui.vibee

import androidx.annotation.Keep
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import com.music.echo.constants.*
import com.music.echo.utils.dataStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import timber.log.Timber
import java.util.Locale
import java.io.IOException
import java.util.Calendar

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
import com.music.echo.playback.queues.ListQueue
import com.music.echo.playback.queues.YouTubeQueue
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.WatchEndpoint
import com.music.echo.eq.data.EQProfileRepository
import com.music.echo.eq.data.FilterType
import com.music.echo.eq.data.ParametricEQBand
import com.music.echo.eq.data.SavedEQProfile

enum class VibeeState {
    IDLE, LISTENING, THINKING, SPEAKING
}

data class PlaybackContext(
    val currentTrack: String,
    val currentArtist: String,
    val currentAlbum: String,
    val isPlaying: Boolean,
    val isLiked: Boolean,
    val positionSeconds: Long,
    val durationSeconds: Long,
    val queueSummary: List<String>,
    val timeOfDay: String,
    val topFavoritesSummary: List<String>
)

class VibeeManager(
    private val context: Context,
    private val playerConnection: PlayerConnection?,
    private val coroutineScope: CoroutineScope
) {
    val state = MutableStateFlow(VibeeState.IDLE)
    val recognizedText = MutableStateFlow("")
    val spokenResponse = MutableStateFlow("")
    val rmsFlow = MutableStateFlow(0f)
    val activeToolFeedback = MutableStateFlow<String?>(null)

    private var speechRecognizer: SpeechRecognizer? = null
    private var wakeWordRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    
    private var isTtsReady = false
    private var intentJob: Job? = null
    private var wasPlayingBeforeVibee = false
    private var isWakeWordListening = false

    private val bandFrequencies = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)

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
                            while(state.value == VibeeState.SPEAKING && loopCount < 200) {
                                rmsFlow.value = (Math.random() * 0.7 + 0.3).toFloat()
                                kotlinx.coroutines.delay(60)
                                loopCount++
                            }
                            rmsFlow.value = 0f
                        }
                    }
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == "vibee_tts_end") {
                            coroutineScope.launch(Dispatchers.Main) {
                                abandonAudioFocus()
                                state.value = VibeeState.IDLE
                                startWakeWordListeningIfNeeded()
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
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        coroutineScope.launch(Dispatchers.Main) {
                            abandonAudioFocus()
                            state.value = VibeeState.IDLE
                            startWakeWordListeningIfNeeded()
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
            stopWakeWordListening()
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Timber.e("Speech recognition not available")
                state.value = VibeeState.IDLE
                startWakeWordListeningIfNeeded()
                respond("Speech recognition is not available on this device.")
                return@launch
            }
            
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Timber.e("Microphone permission not granted")
                state.value = VibeeState.IDLE
                respond("I need microphone permission to hear you.")
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
                        activeToolFeedback.value = null
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {
                        if (state.value == VibeeState.LISTENING) {
                            rmsFlow.value = (rmsdB / 10f).coerceIn(0f, 1.5f)
                        }
                    }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        state.value = VibeeState.THINKING
                        rmsFlow.value = 0f
                    }
                    override fun onError(error: Int) {
                        coroutineScope.launch(Dispatchers.Main) {
                            abandonAudioFocus()
                            state.value = VibeeState.IDLE
                            startWakeWordListeningIfNeeded()
                            
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
                            abandonAudioFocus()
                            state.value = VibeeState.IDLE
                            startWakeWordListeningIfNeeded()
                            if (wasPlayingBeforeVibee) {
                                playerConnection?.player?.play()
                                wasPlayingBeforeVibee = false
                            }
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        if (text.isNotBlank()) {
                            recognizedText.value = text
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer?.startListening(intent)
        }
    }

    fun startWakeWordListeningIfNeeded() {
        coroutineScope.launch(Dispatchers.Main) {
            val enabled = try {
                context.dataStore.data.first()[HeyVibeeEnabledKey] ?: false
            } catch (e: Exception) {
                false
            }
            if (enabled && state.value == VibeeState.IDLE && !isWakeWordListening) {
                startContinuousWakeWordListening()
            }
        }
    }

    private fun startContinuousWakeWordListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
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
                            kotlinx.coroutines.delay(500)
                            startContinuousWakeWordListening()
                        }
                    }
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    if (text.lowercase(Locale.getDefault()).contains("hey vibee") || text.lowercase(Locale.getDefault()).contains("hey vibe")) {
                        stopWakeWordListening()
                        startListening()
                    } else if (isWakeWordListening && state.value == VibeeState.IDLE) {
                        startContinuousWakeWordListening()
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    if (text.lowercase(Locale.getDefault()).contains("hey vibee") || text.lowercase(Locale.getDefault()).contains("hey vibe")) {
                        stopWakeWordListening()
                        startListening()
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        wakeWordRecognizer?.startListening(intent)
    }

    private fun stopWakeWordListening() {
        isWakeWordListening = false
        wakeWordRecognizer?.destroy()
        wakeWordRecognizer = null
    }

    fun close() {
        intentJob?.cancel()
        state.value = VibeeState.IDLE
        startWakeWordListeningIfNeeded()
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

    private fun isOnline(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = connectivityManager?.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            true
        }
    }

    private suspend fun fetchPlaybackContext(): PlaybackContext {
        return withContext(Dispatchers.IO) {
            var track = "None"
            var artist = "None"
            var album = "None"
            var isPlaying = false
            var isLiked = false
            var pos = 0L
            var dur = 0L
            val queueSummary = mutableListOf<String>()

            withContext(Dispatchers.Main) {
                val player = playerConnection?.player
                val currentMedia = player?.currentMediaItem
                track = currentMedia?.mediaMetadata?.title?.toString() ?: "None"
                artist = currentMedia?.mediaMetadata?.artist?.toString() ?: "None"
                album = currentMedia?.mediaMetadata?.albumTitle?.toString() ?: "None"
                isPlaying = player?.isPlaying == true
                pos = (player?.currentPosition ?: 0L) / 1000L
                dur = (player?.duration?.takeIf { it > 0 } ?: 0L) / 1000L

                val windows = playerConnection?.queueWindows?.value ?: emptyList()
                val currentIdx = player?.currentMediaItemIndex ?: 0
                windows.drop(currentIdx + 1).take(4).forEach { win ->
                    win.mediaItem.mediaMetadata.title?.toString()?.let { queueSummary.add(it) }
                }
            }

            // Database favorites & recent info
            val favorites = try {
                playerConnection?.database?.likedSongsByNameAsc()?.first()?.take(5)?.map { it.song.title } ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }

            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val timeOfDay = when (hour) {
                in 5..11 -> "Morning"
                in 12..16 -> "Afternoon"
                in 17..21 -> "Evening"
                else -> "Late Night"
            }

            PlaybackContext(
                currentTrack = track,
                currentArtist = artist,
                currentAlbum = album,
                isPlaying = isPlaying,
                isLiked = isLiked,
                positionSeconds = pos,
                durationSeconds = dur,
                queueSummary = queueSummary,
                timeOfDay = timeOfDay,
                topFavoritesSummary = favorites
            )
        }
    }

    fun submitPrompt(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        stopWakeWordListening()
        requestAudioFocus()
        wasPlayingBeforeVibee = playerConnection?.player?.isPlaying == true
        playerConnection?.player?.pause()
        recognizedText.value = trimmed
        spokenResponse.value = ""
        processUserIntent(trimmed)
    }

    private fun processUserIntent(text: String) {
        state.value = VibeeState.THINKING
        intentJob?.cancel()
        intentJob = coroutineScope.launch(Dispatchers.IO) {
            val playbackContext = fetchPlaybackContext()
            val lowerText = text.lowercase(Locale.getDefault()).trim()
            val online = isOnline()

            // 1. FAST-PATH LOCAL INTENT ROUTING (Zero-latency & Offline Fallback)
            val handledLocally = tryHandleLocalIntent(lowerText, text, playbackContext, online)
            if (handledLocally) {
                return@launch
            }

            // If offline and couldn't match basic command, search offline library songs directly!
            if (!online) {
                val playedOffline = executeOfflineSearchAndPlay(text)
                if (!playedOffline) {
                    respond("You're offline right now. I can play songs already downloaded to your library, change volume, adjust EQ, or manage your sleep timer.")
                }
                return@launch
            }

            // 2. REMOTE LLM TOOL CALLING (OpenRouter / Gemini)
            try {
                val openRouterKey = try {
                    val key = context.dataStore.data.first()[OpenRouterApiKey]
                    if (!key.isNullOrEmpty()) key else BuildConfig.VIBEE
                } catch (e: IOException) {
                    BuildConfig.VIBEE
                }

                if (openRouterKey.isEmpty()) {
                    // Fallback to local offline song search if no API key
                    val playedOffline = executeOfflineSearchAndPlay(text)
                    if (!playedOffline) {
                        respond("Please add your OpenRouter API key in settings, or ask me for playback, volume, sleep timer, or EQ controls.")
                    }
                    return@launch
                }

                val prompt = """
                    You are Vibee, an intelligent, conversational music DJ and assistant.
                    
                    CURRENT CONTEXT:
                    - Now Playing: "${playbackContext.currentTrack}" by ${playbackContext.currentArtist} (Album: ${playbackContext.currentAlbum})
                    - Playback State: ${if (playbackContext.isPlaying) "Playing" else "Paused"} (${playbackContext.positionSeconds}s / ${playbackContext.durationSeconds}s)
                    - Upcoming Queue: ${if (playbackContext.queueSummary.isNotEmpty()) playbackContext.queueSummary.joinToString(", ") else "Empty"}
                    - User Top Favorites: ${if (playbackContext.topFavoritesSummary.isNotEmpty()) playbackContext.topFavoritesSummary.joinToString(", ") else "None"}
                    - Time of Day: ${playbackContext.timeOfDay}
                    
                    USER QUERY: "$text"
                    
                    Determine the user's intent and choose the right tool action. Output a single JSON object.
                    
                    AVAILABLE TOOLS & ACTIONS:
                    1. Play a specific song or artist:
                       {"action": "play_song", "query": "song or artist name"}
                    2. Smart DJ recommendations:
                       {"action": "smart_dj", "mode": "similar_vibe" | "favorites" | "chill" | "energy", "query": "optional description or genre"}
                    3. Sleep Timer:
                       {"action": "sleep_timer", "minutes": 15, "cancel": false}
                    4. Like / Favorite:
                       {"action": "like_song", "like": true | false | null}
                    5. Equalizer Presets:
                       {"action": "equalizer", "preset": "bass" | "treble" | "vocal" | "rock" | "edm" | "reset"}
                    6. Direct Playback Control:
                       {"action": "playback_control", "command": "play" | "pause" | "next" | "prev" | "restart" | "seek_fwd" | "seek_back" | "shuffle" | "repeat", "seconds": 30}
                    7. Queue Management:
                       {"action": "add_to_queue", "query": "song name"}
                       {"action": "remove_from_queue", "query": "song name"}
                       {"action": "move_in_queue", "query": "song name", "to": "next" | "last" | "1"}
                    8. Settings Toggle:
                       {"action": "toggle_setting", "setting": "SETTING_KEY", "value": true | false}
                       Settings options: ${SettingToggleHelper.ALL_SETTINGS.take(30).joinToString(", ")}.
                    9. Conversational Chat & Music Explanations:
                       {"action": "chat", "message": "friendly, concise voice response"}
                    
                    OUTPUT ONLY RAW JSON. Do not include markdown codeblocks.
                """.trimIndent()

                val req = OpenRouterRequest(
                    model = "meta-llama/llama-3.2-3b-instruct",
                    messages = listOf(Message(role = "user", content = prompt)),
                    response_format = OpenRouterResponseFormat(type = "json_object")
                )

                var response: OpenRouterResponse? = null
                for (attempt in 1..2) {
                    response = withTimeoutOrNull(8000) {
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
                    // Fallback to local parsing
                    val localHandled = executeFallbackIntent(lowerText, text)
                    if (!localHandled) respond("Network timed out. Try asking again.")
                    return@launch
                }

                val jsonRes = response.choices?.firstOrNull()?.message?.content
                if (jsonRes.isNullOrBlank()) {
                    val localHandled = executeFallbackIntent(lowerText, text)
                    if (!localHandled) respond("I didn't catch that.")
                    return@launch
                }

                parseAndExecuteLLMAction(jsonRes, text)

            } catch (e: io.ktor.client.plugins.ClientRequestException) {
                Timber.e(e, "HTTP Error in Vibee: ${e.response.status.value}")
                val localHandled = executeFallbackIntent(lowerText, text)
                if (!localHandled) respond("API Error ${e.response.status.value}. Switched to local controls.")
            } catch (e: Exception) {
                Timber.e(e, "Error processing Vibee intent")
                val localHandled = executeFallbackIntent(lowerText, text)
                if (!localHandled) respond("Something went wrong.")
            }
        }
    }

    private suspend fun tryHandleLocalIntent(
        lowerText: String,
        rawText: String,
        context: PlaybackContext,
        isOnline: Boolean
    ): Boolean {
        // Direct Playback controls
        when {
            lowerText == "pause" || lowerText == "stop" || lowerText == "stop music" || lowerText == "halt" -> {
                executePause()
                return true
            }
            lowerText == "play" || lowerText == "resume" || lowerText == "continue" || lowerText == "start music" -> {
                executeResume()
                return true
            }
            lowerText == "next" || lowerText == "skip" || lowerText == "next song" || lowerText == "skip song" -> {
                executeNext()
                return true
            }
            lowerText == "previous" || lowerText == "previous song" || lowerText == "go back" || lowerText == "last track" || lowerText == "back" -> {
                executePrevious()
                return true
            }
            lowerText == "restart" || lowerText == "restart song" || lowerText == "play from beginning" || lowerText == "replay" -> {
                executeRestart()
                return true
            }
            lowerText.startsWith("seek forward") || lowerText.startsWith("fast forward") || lowerText.contains("skip 30 seconds") -> {
                executeSeek(30)
                return true
            }
            lowerText.startsWith("rewind") || lowerText.startsWith("seek back") || lowerText.contains("go back 15 seconds") -> {
                executeSeek(-15)
                return true
            }
            lowerText.contains("shuffle") -> {
                executeToggleShuffle()
                return true
            }
            lowerText.contains("repeat") || lowerText.contains("loop this song") -> {
                executeToggleRepeat()
                return true
            }

            // Volume controls
            lowerText.contains("volume up") || lowerText.contains("turn it up") || lowerText.contains("louder") || lowerText.contains("increase volume") -> {
                audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                respond("Volume up.", endSession = true)
                return true
            }
            lowerText.contains("volume down") || lowerText.contains("turn it down") || lowerText.contains("quieter") || lowerText.contains("lower volume") -> {
                audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                respond("Volume down.", endSession = true)
                return true
            }
            lowerText == "mute" || lowerText == "silence" || lowerText == "be quiet" -> {
                playerConnection?.setMuted(true)
                respond("Muted.", endSession = true)
                return true
            }
            lowerText == "unmute" -> {
                playerConnection?.setMuted(false)
                respond("Unmuted.", endSession = true)
                return true
            }

            // Like / Favorites
            lowerText.contains("like this song") || lowerText.contains("add to favorites") || lowerText.contains("i love this song") || lowerText.contains("thumbs up") -> {
                executeLikeSong(true)
                return true
            }
            lowerText.contains("unlike") || lowerText.contains("remove from favorites") || lowerText.contains("dislike") || lowerText.contains("thumbs down") -> {
                executeLikeSong(false)
                return true
            }
            lowerText.contains("shuffle my favorites") || lowerText.contains("play my liked songs") || lowerText.contains("play favorites") || lowerText.contains("play my favorites") -> {
                executeSmartDJ("favorites")
                return true
            }

            // Sleep Timer
            lowerText.contains("sleep timer") || lowerText.contains("turn off music in") || lowerText.contains("stop music in") -> {
                if (lowerText.contains("cancel") || lowerText.contains("stop") || lowerText.contains("off") || lowerText.contains("disable")) {
                    executeCancelSleepTimer()
                } else {
                    val minutes = Regex("\\d+").find(lowerText)?.value?.toIntOrNull() ?: 30
                    executeSetSleepTimer(minutes)
                }
                return true
            }

            // Equalizer Presets
            lowerText.contains("bass boost") || lowerText.contains("boost bass") || lowerText.contains("heavy bass") -> {
                executeEqualizerPreset("bass")
                return true
            }
            lowerText.contains("treble boost") || lowerText.contains("more treble") -> {
                executeEqualizerPreset("treble")
                return true
            }
            lowerText.contains("vocal boost") || lowerText.contains("clear vocals") -> {
                executeEqualizerPreset("vocal")
                return true
            }
            lowerText.contains("rock equalizer") || lowerText.contains("rock preset") -> {
                executeEqualizerPreset("rock")
                return true
            }
            lowerText.contains("edm") || lowerText.contains("electronic preset") || lowerText.contains("dance equalizer") -> {
                executeEqualizerPreset("edm")
                return true
            }
            lowerText.contains("reset equalizer") || lowerText.contains("flat eq") || lowerText.contains("reset eq") -> {
                executeEqualizerPreset("reset")
                return true
            }

            // Smart DJ
            lowerText.contains("keep this vibe going") || lowerText.contains("more like this") || lowerText.contains("similar vibe") || lowerText.contains("vibe like this") || lowerText.contains("start radio") -> {
                executeSmartDJ("similar_vibe")
                return true
            }
            lowerText.contains("play something chill") || lowerText.contains("chill vibe") || lowerText.contains("relaxing music") -> {
                executeSmartDJ("chill")
                return true
            }
            lowerText.contains("workout music") || lowerText.contains("high energy") || lowerText.contains("pump up") -> {
                executeSmartDJ("energy")
                return true
            }

            // Track info
            lowerText.contains("what song is this") || lowerText.contains("what is playing") || lowerText.contains("what's playing") || lowerText.contains("who is this") || lowerText.contains("who sings this") -> {
                val msg = if (context.currentTrack != "None") {
                    "This is ${context.currentTrack} by ${context.currentArtist}."
                } else {
                    "Nothing is currently playing."
                }
                respond(msg, endSession = true)
                return true
            }

            // Direct Play
            lowerText.startsWith("play ") && lowerText.length > 5 -> {
                val query = rawText.substring(5).trim()
                if (isOnline) {
                    executePlaySong(query)
                } else {
                    val offlineFound = executeOfflineSearchAndPlay(query)
                    if (!offlineFound) {
                        respond("Couldn't find $query offline.")
                    }
                }
                return true
            }
        }
        return false
    }

    private suspend fun executeFallbackIntent(lowerText: String, rawText: String): Boolean {
        val playbackContext = fetchPlaybackContext()
        return tryHandleLocalIntent(lowerText, rawText, playbackContext, isOnline())
    }

    private suspend fun parseAndExecuteLLMAction(jsonRes: String, rawText: String) {
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

                when (action) {
                    "play_song" -> {
                        query = jsonObj.optString("query", "").trim()
                        if (query.isNotBlank() && query != "null") {
                            executePlaySong(query)
                        } else {
                            executeResume()
                        }
                    }
                    "smart_dj" -> {
                        val mode = jsonObj.optString("mode", "similar_vibe")
                        val q = jsonObj.optString("query", "")
                        activeToolFeedback.value = "⚡ Smart DJ: $mode"
                        executeSmartDJ(mode, q)
                    }
                    "sleep_timer" -> {
                        val cancel = jsonObj.optBoolean("cancel", false)
                        val minutes = jsonObj.optInt("minutes", 30)
                        if (cancel) {
                            executeCancelSleepTimer()
                        } else {
                            executeSetSleepTimer(minutes)
                        }
                    }
                    "like_song" -> {
                        val like = if (jsonObj.has("like")) jsonObj.optBoolean("like") else null
                        executeLikeSong(like)
                    }
                    "equalizer" -> {
                        val preset = jsonObj.optString("preset", "bass")
                        activeToolFeedback.value = "🎚️ EQ: $preset"
                        executeEqualizerPreset(preset)
                    }
                    "playback_control" -> {
                        val cmd = jsonObj.optString("command", "play")
                        val seconds = jsonObj.optInt("seconds", 30)
                        when (cmd) {
                            "pause" -> executePause()
                            "next" -> executeNext()
                            "prev" -> executePrevious()
                            "restart" -> executeRestart()
                            "seek_fwd" -> executeSeek(seconds)
                            "seek_back" -> executeSeek(-seconds)
                            "shuffle" -> executeToggleShuffle()
                            "repeat" -> executeToggleRepeat()
                            else -> executeResume()
                        }
                    }
                    "add_to_queue" -> {
                        val q = jsonObj.optString("query", "")
                        if (q.isNotBlank()) executeAddToQueue(q)
                        else respond("I couldn't add that to the queue.")
                    }
                    "remove_from_queue" -> {
                        val q = jsonObj.optString("query", "")
                        if (q.isNotBlank()) executeRemoveFromQueue(q)
                        else respond("I couldn't remove that from the queue.")
                    }
                    "move_in_queue" -> {
                        val q = jsonObj.optString("query", "")
                        val to = jsonObj.optString("to", "next")
                        if (q.isNotBlank()) executeMoveInQueue(q, to)
                        else respond("I couldn't move that in the queue.")
                    }
                    "toggle_setting" -> {
                        val settingName = jsonObj.optString("setting", "")
                        val value = jsonObj.optBoolean("value", false)
                        if (settingName.isNotBlank()) executeToggleSetting(settingName, value)
                        else respond("I couldn't change that setting.")
                    }
                    else -> {
                        msg = jsonObj.optString("message", "I heard you.")
                        respond(msg, endSession = true)
                    }
                }
            } else {
                respond(jsonRes.trim(), endSession = true)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error parsing JSON from LLM")
            respond(jsonRes.trim(), endSession = true)
        }
    }

    private suspend fun executePlaySong(query: String) {
        wasPlayingBeforeVibee = false
        activeToolFeedback.value = "🎵 Playing: $query"
        val result = withTimeoutOrNull(9000) {
            YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
        }
        val topResult = result?.items?.filterIsInstance<SongItem>()?.firstOrNull()

        withContext(Dispatchers.Main) {
            if (topResult != null) {
                if (playerConnection?.player != null) {
                    playerConnection?.playQueue(YouTubeQueue.radio(topResult.toMediaMetadata()))
                    respond("Playing ${topResult.title}.", endSession = true)
                } else {
                    respond("Player is not ready.", endSession = true)
                }
            } else {
                // Try offline search as fallback
                val offlineFound = executeOfflineSearchAndPlay(query)
                if (!offlineFound) {
                    respond("I couldn't find $query.", endSession = true)
                }
            }
        }
    }

    private suspend fun executeOfflineSearchAndPlay(query: String): Boolean {
        return try {
            val db = playerConnection?.database ?: return false
            val matchingSongs = db.searchSongs(query).first()
            if (matchingSongs.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    val song = matchingSongs.first()
                    playerConnection?.playQueue(
                        ListQueue(
                            title = "Offline: ${song.song.title}",
                            items = listOf(song.toMediaMetadata().toMediaItem())
                        )
                    )
                    respond("Playing ${song.song.title} from offline library.", endSession = true)
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun executeSmartDJ(mode: String, query: String? = null) {
        wasPlayingBeforeVibee = false
        when (mode.lowercase()) {
            "favorites", "shuffle_favorites", "liked" -> {
                val likedSongs = try {
                    playerConnection?.database?.likedSongsByNameAsc()?.first() ?: emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
                if (likedSongs.isNotEmpty()) {
                    val shuffled = likedSongs.shuffled()
                    withContext(Dispatchers.Main) {
                        playerConnection?.playQueue(
                            ListQueue(
                                title = "Liked Favorites Mix",
                                items = shuffled.map { it.toMediaMetadata().toMediaItem() }
                            )
                        )
                        respond("Shuffling ${shuffled.size} favorite songs.", endSession = true)
                    }
                } else {
                    respond("You don't have any liked songs yet.", endSession = true)
                }
            }
            "chill", "relaxing" -> {
                val chillQuery = if (!query.isNullOrBlank()) "$query chill acoustic" else "chill acoustic lo-fi vibes mix"
                executePlaySong(chillQuery)
            }
            "energy", "workout" -> {
                val energyQuery = if (!query.isNullOrBlank()) "$query high energy workout" else "high energy electronic upbeat workout mix"
                executePlaySong(energyQuery)
            }
            else -> { // similar_vibe
                val currentMedia = playerConnection?.player?.currentMediaItem
                val currentTitle = currentMedia?.mediaMetadata?.title?.toString()
                val currentArtist = currentMedia?.mediaMetadata?.artist?.toString()
                if (!currentTitle.isNullOrBlank()) {
                    val djSeed = if (!currentArtist.isNullOrBlank()) "$currentTitle $currentArtist radio mix" else "$currentTitle radio mix"
                    executePlaySong(djSeed)
                } else {
                    executePlaySong("popular top hits radio mix")
                }
            }
        }
    }

    private suspend fun executeSetSleepTimer(minutes: Int) {
        withContext(Dispatchers.Main) {
            activeToolFeedback.value = "⏱️ Sleep Timer: ${minutes}m"
            playerConnection?.service?.sleepTimer?.start(minutes)
            respond("Sleep timer set for $minutes minutes.", endSession = true)
        }
    }

    private suspend fun executeCancelSleepTimer() {
        withContext(Dispatchers.Main) {
            activeToolFeedback.value = "⏱️ Sleep Timer: Off"
            playerConnection?.service?.sleepTimer?.clear()
            respond("Sleep timer turned off.", endSession = true)
        }
    }

    private suspend fun executeLikeSong(like: Boolean?) {
        withContext(Dispatchers.Main) {
            val service = playerConnection?.service
            if (service != null) {
                service.toggleLike()
                val msg = if (like == true) "Added to your favorites." else if (like == false) "Removed from favorites." else "Toggled favorite."
                activeToolFeedback.value = "❤️ Favorites Updated"
                respond(msg, endSession = true)
            } else {
                respond("Player is not ready.", endSession = true)
            }
        }
    }

    suspend fun executeEqualizerPreset(preset: String) {
        val gains = when (preset.lowercase()) {
            "bass", "bass_boost" -> floatArrayOf(15f, 18f, 14f, 8f, 2f, 0f, 0f, 0f, 0f, 0f)
            "treble", "treble_boost" -> floatArrayOf(0f, 0f, 0f, 0f, 2f, 6f, 10f, 14f, 16f, 18f)
            "vocal", "vocal_boost" -> floatArrayOf(-2f, -2f, 0f, 6f, 12f, 14f, 10f, 6f, 2f, 0f)
            "rock" -> floatArrayOf(12f, 8f, 4f, -2f, -4f, -2f, 4f, 8f, 12f, 14f)
            "edm", "electronic" -> floatArrayOf(16f, 14f, 10f, 2f, -2f, 2f, 6f, 10f, 14f, 16f)
            else -> floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }

        val bands = gains.mapIndexed { index, f ->
            ParametricEQBand(
                frequency = bandFrequencies[index],
                gain = f.toDouble() / 50.0,
                q = 1.41,
                filterType = FilterType.PK,
                enabled = true
            )
        }

        val presetName = when(preset.lowercase()) {
            "bass", "bass_boost" -> "Bass Boost"
            "treble", "treble_boost" -> "Treble Boost"
            "vocal", "vocal_boost" -> "Vocal Boost"
            "rock" -> "Rock"
            "edm", "electronic" -> "Electronic / EDM"
            else -> "Flat / Reset"
        }

        val profile = SavedEQProfile(
            id = "echo_tuning",
            name = "Echo Tuning ($presetName)",
            deviceModel = "Equalizer",
            bands = bands,
            preamp = 0.0,
            isCustom = false,
            isActive = true
        )

        withContext(Dispatchers.IO) {
            val eqRepo = EQProfileRepository(context)
            eqRepo.saveProfile(profile)
            eqRepo.setActiveProfile(profile.id)
            playerConnection?.service?.equalizerService?.applyProfile(profile)
        }

        withContext(Dispatchers.Main) {
            activeToolFeedback.value = "🎚️ $presetName"
            respond("Set equalizer to $presetName.", endSession = true)
        }
    }

    private suspend fun executeSeek(seconds: Int) {
        withContext(Dispatchers.Main) {
            val player = playerConnection?.player
            if (player != null) {
                val newPos = (player.currentPosition + seconds * 1000L).coerceIn(0L, player.duration.coerceAtLeast(0L))
                player.seekTo(newPos)
                val dir = if (seconds > 0) "forward" else "back"
                respond("Skipped $dir ${kotlin.math.abs(seconds)} seconds.", endSession = true)
            } else {
                respond("Player is not ready.", endSession = true)
            }
        }
    }

    private suspend fun executeToggleShuffle() {
        withContext(Dispatchers.Main) {
            val player = playerConnection?.player
            if (player != null) {
                val newMode = !player.shuffleModeEnabled
                player.shuffleModeEnabled = newMode
                respond(if (newMode) "Shuffle on." else "Shuffle off.", endSession = true)
            } else {
                respond("Player is not ready.", endSession = true)
            }
        }
    }

    private suspend fun executeToggleRepeat() {
        withContext(Dispatchers.Main) {
            val player = playerConnection?.player
            if (player != null) {
                val newMode = if (player.repeatMode == androidx.media3.common.Player.REPEAT_MODE_OFF) {
                    androidx.media3.common.Player.REPEAT_MODE_ALL
                } else {
                    androidx.media3.common.Player.REPEAT_MODE_OFF
                }
                player.repeatMode = newMode
                respond(if (newMode != androidx.media3.common.Player.REPEAT_MODE_OFF) "Repeat on." else "Repeat off.", endSession = true)
            } else {
                respond("Player is not ready.", endSession = true)
            }
        }
    }

    private suspend fun executeResume() {
        wasPlayingBeforeVibee = false
        withContext(Dispatchers.Main) { 
            if (playerConnection?.player != null) {
                playerConnection.player.play() 
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
                playerConnection.player.pause()
                respond("Paused.", endSession = true)
            } else {
                respond("Player is not ready.", endSession = true)
            }
        }
    }

    private suspend fun executeNext() {
        withContext(Dispatchers.Main) { 
            if (playerConnection?.player != null) {
                playerConnection.player.seekToNext()
                respond("Skipping.", endSession = true)
            } else {
                respond("Player is not ready.", endSession = true)
            }
        }
    }

    private suspend fun executePrevious() {
        withContext(Dispatchers.Main) { 
            if (playerConnection?.player != null) {
                playerConnection.player.seekToPrevious()
                respond("Previous track.", endSession = true)
            } else {
                respond("Player is not ready.", endSession = true)
            }
        }
    }

    private suspend fun executeRestart() {
        withContext(Dispatchers.Main) { 
            if (playerConnection?.player != null) {
                playerConnection.player.seekTo(0)
                playerConnection.player.play()
                respond("Restarting track.", endSession = true)
            } else {
                respond("Player is not ready.", endSession = true)
            }
        }
    }

    private suspend fun executeAddToQueue(query: String) {
        activeToolFeedback.value = "➕ Queueing $query"
        respond("Adding $query to queue...", endSession = false)
        val result = withTimeoutOrNull(8000) {
            YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
        }
        val topResult = result?.items?.filterIsInstance<SongItem>()?.firstOrNull()

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
                    state.value = VibeeState.IDLE
                    startWakeWordListeningIfNeeded()
                    if (wasPlayingBeforeVibee) {
                        playerConnection?.player?.play()
                        wasPlayingBeforeVibee = false
                    }
                }
            }
            return
        }

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
                    state.value = VibeeState.IDLE
                    startWakeWordListeningIfNeeded()
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
        stopWakeWordListening()
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
