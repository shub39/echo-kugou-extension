package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.LyricsSearchClient
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import java.util.zip.InflaterInputStream

open class KugouExtension : ExtensionClient, LyricsClient, LyricsSearchClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private var setting: Settings? = null

    override suspend fun onExtensionSelected() {}

    override fun setSettings(settings: Settings) {
        setting = settings
    }

    override suspend fun getSettingItems(): List<Setting> = listOf(
        SettingSwitch(
            title = "Clean Section Headers",
            key = SETTING_CLEAN_HEADERS,
            summary = "Removes bracketed section tags like [Verse], [Chorus] from lyrics.",
            defaultValue = SETTING_CLEAN_HEADERS_DEFAULT
        ),
        SettingSwitch(
            title = "Prefer KRC Decryption",
            key = SETTING_PREFER_KRC,
            summary = "Prefers Kugou proprietary KRC format for high-precision word timing.",
            defaultValue = SETTING_PREFER_KRC_DEFAULT
        )
    )

    override suspend fun searchTrackLyrics(
        clientId: String,
        track: Track
    ): Feed<Lyrics> = withContext(Dispatchers.IO) {
        val rawTitle = track.title.trim()
        val artistName = track.artists.firstOrNull()?.name?.trim().orEmpty()
        val cleanTitle = cleanTrackTitle(rawTitle)

        // Stage 1: Search using cleaned title + artist
        val primaryQuery = if (artistName.isNotBlank()) "$cleanTitle $artistName" else cleanTitle
        var hits = searchHits(primaryQuery)

        // Stage 2: Fallback to original raw title + artist if clean produced no matches
        if (hits.isEmpty() && cleanTitle != rawTitle) {
            val fallbackQuery = if (artistName.isNotBlank()) "$rawTitle $artistName" else rawTitle
            hits = searchHits(fallbackQuery)
        }

        // Stage 3: Fallback to cleaned title alone
        if (hits.isEmpty() && artistName.isNotBlank() && cleanTitle.isNotBlank()) {
            hits = searchHits(cleanTitle)
        }

        hits.map { info ->
            Lyrics(
                id = info.hash,
                title = info.songname,
                subtitle = info.singername
            )
        }.toFeed()
    }

    override suspend fun searchLyrics(query: String): Feed<Lyrics> = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) return@withContext emptyList<Lyrics>().toFeed()

        val pagedData = PagedData.Continuous<Lyrics> { continuation ->
            val page = continuation?.toIntOrNull() ?: 1
            val (items, hasNext) = fetchSearchPage(trimmedQuery, page)
            Page(
                data = items,
                continuation = if (hasNext) (page + 1).toString() else null
            )
        }

        pagedData.toFeed()
    }

    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics = withContext(Dispatchers.IO) {
        var hash = lyrics.id.trim()

        // If the ID is not a 32-character hexadecimal MD5 hash, resolve it via search
        if (!HASH_REGEX.matches(hash)) {
            val query = "${lyrics.title} ${lyrics.subtitle ?: ""}".trim()
            val hits = searchHits(query)
            hash = hits.firstOrNull()?.hash ?: hash
        }

        var candidates = fetchCandidates(hash)

        // If initial hash returned no candidates, try searching with clean title
        if (candidates.isEmpty()) {
            val cleanTitle = cleanTrackTitle(lyrics.title)
            val hits = searchHits(cleanTitle)
            val alternativeHash = hits.firstOrNull { it.hash != hash }?.hash
            if (!alternativeHash.isNullOrBlank()) {
                candidates = fetchCandidates(alternativeHash)
                if (candidates.isNotEmpty()) {
                    hash = alternativeHash
                }
            }
        }

        if (candidates.isEmpty()) {
            throw IOException("No lyric candidates found for song")
        }

        val preferKrc = setting?.getBoolean(SETTING_PREFER_KRC) ?: SETTING_PREFER_KRC_DEFAULT
        val cleanHeaders = setting?.getBoolean(SETTING_CLEAN_HEADERS) ?: SETTING_CLEAN_HEADERS_DEFAULT

        var parsedLyrics: List<Lyrics.Item>? = null

        // Sort candidates by score descending
        val sortedCandidates = candidates.sortedByDescending { it.score }

        for (candidate in sortedCandidates) {
            val items = downloadCandidateLyrics(candidate, preferKrc)
            if (!items.isNullOrEmpty()) {
                parsedLyrics = items
                break
            }
        }

        if (parsedLyrics.isNullOrEmpty()) {
            throw IOException("Failed to download or parse lyrics from candidates")
        }

        val filteredLyrics = if (cleanHeaders) {
            parsedLyrics.filterNot { item ->
                HEADER_REGEX.matches(item.text)
            }
        } else {
            parsedLyrics
        }

        lyrics.copy(
            lyrics = Lyrics.Timed(
                list = filteredLyrics,
                fillTimeGaps = true
            )
        )
    }

    private suspend fun fetchCandidates(hash: String): List<KugouHashSearchResponse.Candidate> {
        if (hash.isBlank()) return emptyList()
        val url = "${LOAD_BASE_URL}$hash"
        return try {
            val responseBody = httpGet(url)
            val parsed: KugouHashSearchResponse = json.decodeFromString(responseBody)
            parsed.candidates
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun downloadCandidateLyrics(
        candidate: KugouHashSearchResponse.Candidate,
        preferKrc: Boolean
    ): List<Lyrics.Item>? {
        val formats = if (preferKrc) listOf("krc", "lrc") else listOf("lrc", "krc")

        for (fmt in formats) {
            try {
                val url = "https://krcs.kugou.com/download?ver=1&man=yes&client=pc&fmt=$fmt&id=${candidate.id}&accesskey=${candidate.accesskey}"
                val responseBody = httpGet(url)
                val downloadResponse: KugouSearchCandidateDownloadResponse = json.decodeFromString(responseBody)

                if (downloadResponse.status != 200 || downloadResponse.content.isBlank()) {
                    continue
                }

                val decodedBytes = decodeBase64(downloadResponse.content)
                val parsedItems = if (fmt == "krc" || isKrcPayload(decodedBytes)) {
                    val decryptedText = decryptKrc(decodedBytes)
                    parseKrcLyrics(decryptedText)
                } else {
                    val lrcText = String(decodedBytes, StandardCharsets.UTF_8)
                    parseLrcLyrics(lrcText)
                }

                if (parsedItems.isNotEmpty()) {
                    return parsedItems
                }
            } catch (_: Exception) {
                // Fall through to next format or candidate
            }
        }

        return null
    }

    private fun isKrcPayload(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val header = String(bytes.copyOfRange(0, 4), StandardCharsets.ISO_8859_1)
        return header.startsWith("krc", ignoreCase = true)
    }

    private fun decryptKrc(encryptedBytes: ByteArray): String {
        if (encryptedBytes.size <= 4) return ""

        val header = String(encryptedBytes.copyOfRange(0, 4), StandardCharsets.ISO_8859_1)
        val dataOffset = if (header.startsWith("krc", ignoreCase = true)) 4 else 0

        val xorDecrypted = ByteArray(encryptedBytes.size - dataOffset)
        for (i in dataOffset until encryptedBytes.size) {
            val keyIndex = (i - dataOffset) % KRC_XOR_KEY.size
            xorDecrypted[i - dataOffset] = (encryptedBytes[i].toInt() xor KRC_XOR_KEY[keyIndex].toInt()).toByte()
        }

        return try {
            InflaterInputStream(ByteArrayInputStream(xorDecrypted)).use { inflater ->
                inflater.reader(StandardCharsets.UTF_8).readText()
            }
        } catch (_: Exception) {
            String(xorDecrypted, StandardCharsets.UTF_8)
        }
    }

    private fun parseKrcLyrics(krcText: String): List<Lyrics.Item> {
        val items = mutableListOf<Lyrics.Item>()

        for (line in krcText.lines()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("[") || METADATA_TAG_REGEX.matcher(trimmed).find()) {
                continue
            }

            val matcher = KRC_LINE_REGEX.matcher(trimmed)
            if (matcher.find()) {
                val startTime = matcher.group(1)?.toLongOrNull() ?: continue
                val duration = matcher.group(2)?.toLongOrNull() ?: 0L
                val rawText = matcher.group(3).orEmpty()
                val cleanText = KRC_WORD_TAG_REGEX.replace(rawText, "").trim()

                if (cleanText.isNotBlank()) {
                    val endTime = startTime + duration
                    items.add(Lyrics.Item(cleanText, startTime, endTime))
                }
            }
        }

        return items
    }

    private fun parseLrcLyrics(lrcText: String): List<Lyrics.Item> {
        val parsedLines = mutableListOf<Pair<Long, String>>()

        for (line in lrcText.lines()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("[") || METADATA_TAG_REGEX.matcher(trimmed).find()) {
                continue
            }

            val matcher = LRC_TIMESTAMP_REGEX.matcher(trimmed)
            val timestamps = mutableListOf<Long>()
            var lastMatchEnd = 0

            while (matcher.find()) {
                val min = matcher.group(1)?.toLongOrNull() ?: 0L
                val secString = matcher.group(2).orEmpty()
                val sec = secString.toDoubleOrNull() ?: 0.0
                val totalMs = (min * 60000L) + (sec * 1000).toLong()
                timestamps.add(totalMs)
                lastMatchEnd = matcher.end()
            }

            if (timestamps.isNotEmpty()) {
                val text = trimmed.substring(lastMatchEnd).trim()
                if (text.isNotBlank()) {
                    timestamps.forEach { time ->
                        parsedLines.add(time to text)
                    }
                }
            }
        }

        if (parsedLines.isEmpty()) return emptyList()

        val sorted = parsedLines.sortedBy { it.first }
        val items = mutableListOf<Lyrics.Item>()

        for (i in sorted.indices) {
            val current = sorted[i]
            val startTime = current.first
            val endTime = if (i < sorted.size - 1) sorted[i + 1].first else startTime + 3000L
            items.add(Lyrics.Item(current.second, startTime, endTime))
        }

        return items
    }

    private suspend fun searchHits(query: String): List<KugouSearchResponse.Info> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val url = "$SEARCH_BASE_URL$encoded"

        return try {
            val responseBody = httpGet(url)
            val parsed: KugouSearchResponse = json.decodeFromString(responseBody)
            parsed.data.info
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchSearchPage(
        query: String,
        page: Int
    ): Pair<List<Lyrics>, Boolean> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://mobileservice.kugou.com/api/v3/search/song?version=9108&plat=0&pagesize=20&page=$page&showtype=0&keyword=$encoded"

        return try {
            val responseBody = httpGet(url)
            val parsed: KugouSearchResponse = json.decodeFromString(responseBody)
            val hits = parsed.data.info
            val items = hits.map {
                Lyrics(
                    id = it.hash,
                    title = it.songname,
                    subtitle = it.singername
                )
            }
            items to (hits.size >= 20)
        } catch (_: Exception) {
            emptyList<Lyrics>() to false
        }
    }

    private suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Kugou API error: HTTP ${response.code} ${response.message}")
            }
            response.body?.string() ?: throw IOException("Empty response body from Kugou API")
        }
    }

    open fun decodeBase64(content: String): ByteArray {
        val sanitized = content.trim()
        return try {
            val clazz = Class.forName("android.util.Base64")
            val method = clazz.getMethod("decode", String::class.java, Int::class.javaPrimitiveType)
            method.invoke(null, sanitized, 0) as ByteArray
        } catch (_: Throwable) {
            java.util.Base64.getDecoder().decode(sanitized)
        }
    }

    private fun cleanTrackTitle(title: String): String {
        var cleaned = title
        cleaned = VIDEO_AUDIO_ANNOTATION_REGEX.replace(cleaned, "")
        cleaned = BRACKETED_REMASTER_REGEX.replace(cleaned, "")
        cleaned = TRAILING_REMASTER_REGEX.replace(cleaned, "")
        cleaned = TRAILING_LIVE_REGEX.replace(cleaned, "")
        cleaned = FEATURING_REGEX.replace(cleaned, "")
        return MULTI_SPACE_REGEX.replace(cleaned, " ").trim()
    }

    private companion object {
        private const val SEARCH_BASE_URL =
            "https://mobileservice.kugou.com/api/v3/search/song?version=9108&plat=0&pagesize=8&showtype=0&keyword="
        private const val LOAD_BASE_URL =
            "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&hash="

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private const val SETTING_CLEAN_HEADERS = "clean_headers"
        private const val SETTING_CLEAN_HEADERS_DEFAULT = false

        private const val SETTING_PREFER_KRC = "prefer_krc"
        private const val SETTING_PREFER_KRC_DEFAULT = false

        private val HASH_REGEX = Regex("^[a-fA-F0-9]{32}$")
        private val HEADER_REGEX = Regex("""^\[.*?\]$""")
        private val KRC_LINE_REGEX = Pattern.compile("""^\[(\d+),(\d+)\](.*)""")
        private val KRC_WORD_TAG_REGEX = Regex("""<\d+,\d+,\d+>""")
        private val LRC_TIMESTAMP_REGEX = Pattern.compile("""\[(\d{1,2}):(\d{1,2}(?:\.\d{1,3})?)\]""")
        private val METADATA_TAG_REGEX = Pattern.compile("""^\[(ti|ar|al|by|offset|id|hash|sign|qq|total|length):""", Pattern.CASE_INSENSITIVE)

        private val VIDEO_AUDIO_ANNOTATION_REGEX =
            Regex("""(?i)\s*[\[(](official\s*(music\s*)?video|video|audio|lyric\s*video|lyrics|visualizer|mv|hd|hq|4k|extended|bonus\s*track|live(\s*at\s*[^\])]+)?)[\])]""")
        private val BRACKETED_REMASTER_REGEX = Regex("""(?i)\s*[\[(]remaster(ed)?(\s*\d+)?[\])]""")
        private val TRAILING_REMASTER_REGEX = Regex("""(?i)\s*-\s*remaster(ed)?(\s*\d+)?.*$""")
        private val TRAILING_LIVE_REGEX = Regex("""(?i)\s*-\s*live(\s*at\s*.*)?$""")
        private val FEATURING_REGEX = Regex("""(?i)\s*[\[(](feat\.?|ft\.?)\s*[^\])]+[\])]""")
        private val MULTI_SPACE_REGEX = Regex("""\s+""")

        private val KRC_XOR_KEY = byteArrayOf(
            64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 0xCE.toByte(), 0xD2.toByte(), 110, 105
        )
    }
}
