package com.music.echo.eq.autoeq

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class AutoEqEntry(
    val name: String,
    val path: String,
    val source: String,
    val rig: String
)

@Singleton
class AutoEqApi @Inject constructor() {
    private val client = HttpClient(OkHttp)
    private val baseUrl = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results"

    suspend fun getIndex(): List<AutoEqEntry> = withContext(Dispatchers.IO) {
        try {
            val markdown = client.get("$baseUrl/INDEX.md").bodyAsText()
            val entries = mutableListOf<AutoEqEntry>()
            
            val regex = Regex("""-\s+\[(.*?)\]\(\./(.*?)\)\s+by\s+(.*?)(?:\s+on\s+(.*))?$""")
            
            markdown.lines().forEach { line ->
                val match = regex.find(line.trim())
                if (match != null) {
                    entries.add(
                        AutoEqEntry(
                            name = match.groupValues[1],
                            path = match.groupValues[2],
                            source = match.groupValues[3],
                            rig = match.groupValues.getOrNull(4) ?: ""
                        )
                    )
                }
            }
            return@withContext entries
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    suspend fun getParametricEq(entry: AutoEqEntry): String? = withContext(Dispatchers.IO) {
        try {
            val folderName = entry.path.substringAfterLast("/")
            val url = "$baseUrl/${entry.path}/${folderName}%20ParametricEQ.txt"
            
            val response = client.get(url)
            if (response.status.value in 200..299) {
                return@withContext response.bodyAsText()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }
}
