package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.LyricsSearchClient
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.StandardCharsets
import java.util.Base64

open class KugouExtension : ExtensionClient, LyricsClient, LyricsSearchClient {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun onExtensionSelected() {}

    private lateinit var setting: Settings
    override suspend fun getSettingItems(): List<Setting> = emptyList()

    override fun setSettings(settings: Settings) {
        setting = settings
    }

    override suspend fun searchTrackLyrics(
        clientId: String,
        track: Track
    ): Feed<Lyrics> {
        val request = Request.Builder()
            .url("$SEARCH_BASE_URL${track.title} - ${track.artists.firstOrNull()?.name ?: ""}")
            .build()

        return requestToLyrics(request)
    }

    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics {
        val request = Request.Builder()
            .url("$LOAD_BASE_URL${lyrics.id}")
            .build()
        val response = client.newCall(request).await()

        val parsed: KugouHashSearchResponse = json.decodeFromString(response.body.string())
        val candidate: KugouHashSearchResponse.Candidate? = parsed.getBestCandidate()
        if (candidate == null) {
            throw RuntimeException("Cant get lyrics!!")
        }
        val lyricsData = downloadSearchClient(candidate).getOrThrow()
        val syncedLyrics: MutableList<Lyrics.Item> = mutableListOf()

        for (line in lyricsData.lines()) {
            if (line.length < 10 || line[0] != '[' || !line[1].isDigit()) {
                continue
            }

            val split: List<String> = line.split(']', limit = 2)
            val time: Long = parseTimeString(split[0].substring(1))

            if (split[1].isNotBlank()) {
                syncedLyrics.add(
                    Lyrics.Item(
                        text = split[1].trim(),
                        startTime = time,
                        endTime = time
                    )
                )
            }

        }

        return lyrics.copy(
            lyrics = Lyrics.Timed(
                list = syncedLyrics,
                fillTimeGaps = true
            )
        )
    }

    private suspend inline fun requestToLyrics(request: Request): Feed<Lyrics> {
        val response = client.newCall(request).await()
        val parsedResponse: KugouSearchResponse = json.decodeFromString(response.body.string())

        return parsedResponse.data.info.map {
            Lyrics(
                id = it.hash,
                title = it.songname,
                subtitle = it.singername
            )
        }.toFeed()
    }

    private suspend inline fun downloadSearchClient(
        candidate: KugouHashSearchResponse.Candidate
    ): Result<String> = runCatching {
        val request = Request.Builder()
            .url("https://krcs.kugou.com/download?ver=1&man=yes&client=pc&fmt=lrc&id=${candidate.id}&accesskey=${candidate.accesskey}")
            .build()
        val response = client.newCall(request).await()
        val downloadResponse: KugouSearchCandidateDownloadResponse =
            json.decodeFromString(response.body.string().also { println(it) })

        return@runCatching decodeBASE64(downloadResponse.content)
    }

    private fun parseTimeString(string: String): Long {
        var time = 0L

        val split = string.split(':')
        for (part in split.withIndex()) {
            time += when (split.size - part.index - 1) {
                0 -> (part.value.toFloat() * 1000L).toLong()
                1 -> part.value.toLong() * 60000L
                2 -> part.value.toLong() * 3600000L

                else -> throw NotImplementedError("Time stage not implemented: ${split.size - part.index - 1}")
            }
        }

        return time
    }

    @Suppress("NewApi")
    open fun decodeBASE64(content: String): String {
        return Base64.getDecoder().decode(content).toString(StandardCharsets.UTF_8)
    }

    override suspend fun searchLyrics(query: String): Feed<Lyrics> {
        val request = Request.Builder()
            .url("$SEARCH_BASE_URL$query")
            .build()

        return requestToLyrics(request)
    }

    companion object {
        const val SEARCH_BASE_URL =
            "https://mobileservice.kugou.com/api/v3/search/song?version=9108&plat=0&pagesize=8&showtype=0&keyword="
        const val LOAD_BASE_URL = "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&hash="
    }
}