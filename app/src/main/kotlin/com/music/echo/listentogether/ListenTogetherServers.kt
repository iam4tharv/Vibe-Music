package com.music.echo.listentogether

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

@Serializable
data class ListenTogetherServer(
    val name: String,
    val url: String,
    val location: String,
    val operator: String
)

object ListenTogetherServers {
    private const val SERVER_JSON_URL = "https://raw.githubusercontent.com/iam4tharv/Vibe-Music/refs/heads/main/app/server.json"
    
    private val defaultServer = ListenTogetherServer(
        name = "Vibe Music Server",
        url = "wss://iad1tya-echomusic.hf.space/ws",
        location = "Global",
        operator = "ECHO"
    )

    private val _servers = MutableStateFlow(listOf(defaultServer))
        
    val serversFlow: StateFlow<List<ListenTogetherServer>> = _servers
    val servers: List<ListenTogetherServer>
        get() = _servers.value

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            try {
                val client = okhttp3.OkHttpClient()
                val request = okhttp3.Request.Builder().url(SERVER_JSON_URL).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.string()?.let { jsonString ->
                            val jsonObject = Json.parseToJsonElement(jsonString).jsonObject
                            val name = jsonObject["name"]?.jsonPrimitive?.content ?: "Hugging Face Sync"
                            val url = jsonObject["serverUrl"]?.jsonPrimitive?.content ?: "wss://devilmi-vivi-music-listen-together.hf.space"
                            val region = jsonObject["region"]?.jsonPrimitive?.content ?: "Global - VIVIDH"
                            
                            if (isUrlAllowed(url)) {
                                val fetchedServer = ListenTogetherServer(
                                    name = name,
                                    url = url,
                                    location = region,
                                    operator = ""
                                )
                                // Append instead of replacing to avoid race condition on defaultServerUrl
                                _servers.value = listOf(defaultServer, fetchedServer).distinctBy { it.url }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Failed to fetch Listen Together servers")
            }
        }
    }

    private fun isUrlAllowed(url: String): Boolean {
        return try {
            val host = java.net.URI(url).host ?: return false
            host.endsWith(".hf.space") || host.endsWith(".onrender.com") || host.endsWith(".koyeb.app")
        } catch (e: Exception) {
            false
        }
    }

    val defaultServerUrl: String
        get() = servers.first().url

    fun findByUrl(url: String): ListenTogetherServer? = servers.firstOrNull { it.url == url }
}
