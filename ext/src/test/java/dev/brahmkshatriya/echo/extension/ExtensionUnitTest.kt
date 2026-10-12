package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Feed.Companion.pagedDataOfFirst
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(DelicateCoroutinesApi::class)
@ExperimentalCoroutinesApi
class ExtensionUnitTest {

    private val extension = KugouExtension()
    private val mainThreadSurrogate = newSingleThreadContext("UI thread")
    private val settings = MockedSettings()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainThreadSurrogate)
        extension.setSettings(settings)
        runBlocking {
            extension.onInitialize()
            extension.onExtensionSelected()
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        mainThreadSurrogate.close()
    }

    @Test
    fun testSettings() = runBlocking {
        val items = extension.getSettingItems()
        println("Settings count: ${items.size}")
        items.forEach { println("  - ${it.title} (${it.key})") }
        assertTrue("Settings should have items", items.isNotEmpty())
    }

    @Test
    fun testSearchTrackLyricsEnglish() = runBlocking {
        val track = Track(
            id = "1",
            title = "Bohemian Rhapsody",
            artists = listOf(Artist("1", "Queen"))
        )
        val feed = extension.searchTrackLyrics("test", track)
        val items = feed.pagedDataOfFirst().loadPage(null).data
        println("Found ${items.size} matches for Bohemian Rhapsody:")
        items.take(3).forEach { println("  - ${it.title} by ${it.subtitle} (hash: ${it.id})") }

        assertTrue("Search results should not be empty", items.isNotEmpty())
        assertTrue(
            "First result should be Bohemian Rhapsody",
            items.first().title.contains("Bohemian Rhapsody", ignoreCase = true)
        )
    }

    @Test
    fun testSearchTrackLyricsWithDirtyTitle() = runBlocking {
        val track = Track(
            id = "2",
            title = "Bohemian Rhapsody (Official Video) [Remastered 2011]",
            artists = listOf(Artist("1", "Queen"))
        )
        val feed = extension.searchTrackLyrics("test", track)
        val items = feed.pagedDataOfFirst().loadPage(null).data
        println("Found ${items.size} matches for dirty title:")
        items.take(3).forEach { println("  - ${it.title} (hash: ${it.id})") }

        assertTrue("Dirty title should be cleaned and match", items.isNotEmpty())
        assertTrue(
            "Cleaned title should match Bohemian Rhapsody",
            items.first().title.contains("Bohemian Rhapsody", ignoreCase = true)
        )
    }

    @Test
    fun testLoadLyricsStandardLrc() = runBlocking {
        // Bohemian Rhapsody hash
        val lyricItem = Lyrics(id = "d84dcc66440d6213b058f765b30d27d9", title = "Bohemian Rhapsody", subtitle = "Queen")
        val loaded = extension.loadLyrics(lyricItem)
        val timed = loaded.lyrics as? Lyrics.Timed
        assertNotNull("Lyrics should be loaded as Timed", timed)

        val list = timed!!.list
        println("Loaded ${list.size} timed lines for Bohemian Rhapsody. Sample:")
        list.take(5).forEach { println("  [${it.startTime}ms -> ${it.endTime}ms] ${it.text}") }

        assertTrue("Timed lyrics list should not be empty", list.isNotEmpty())
        val hasIconicLine = list.any { it.text.contains("real life", ignoreCase = true) }
        assertTrue("Lyrics should contain iconic verse", hasIconicLine)
    }

    @Test
    fun testLoadLyricsPreferKrcDecryption() = runBlocking {
        settings.putBoolean("prefer_krc", true)
        val lyricItem = Lyrics(id = "d84dcc66440d6213b058f765b30d27d9", title = "Bohemian Rhapsody", subtitle = "Queen")
        val loaded = extension.loadLyrics(lyricItem)
        val timed = loaded.lyrics as? Lyrics.Timed
        assertNotNull("Lyrics should be loaded as Timed", timed)

        val list = timed!!.list
        println("Loaded ${list.size} KRC decrypted lines. Sample:")
        list.take(5).forEach { println("  [${it.startTime}ms -> ${it.endTime}ms] ${it.text}") }

        assertTrue("KRC timed lyrics list should not be empty", list.isNotEmpty())
        val hasIconicLine = list.any { it.text.contains("real life", ignoreCase = true) }
        assertTrue("KRC lyrics should contain iconic verse", hasIconicLine)
    }

    @Test
    fun testContinuousSearchPagination() = runBlocking {
        val feed = extension.searchLyrics("Coldplay")
        val pagedData = feed.pagedDataOfFirst()

        val page1 = pagedData.loadPage(null)
        println("Page 1 items count: ${page1.data.size}, continuation: ${page1.continuation}")
        assertTrue("Page 1 should have results", page1.data.isNotEmpty())
        assertNotNull("Page 1 should have continuation for next page", page1.continuation)

        val page2 = pagedData.loadPage(page1.continuation)
        println("Page 2 items count: ${page2.data.size}, continuation: ${page2.continuation}")
        assertTrue("Page 2 should have results", page2.data.isNotEmpty())
    }

    @Test
    fun testCleanHeadersSetting() = runBlocking {
        settings.putBoolean("clean_headers", true)
        val lyricItem = Lyrics(id = "d84dcc66440d6213b058f765b30d27d9", title = "Bohemian Rhapsody", subtitle = "Queen")
        val loaded = extension.loadLyrics(lyricItem)
        val timed = loaded.lyrics as? Lyrics.Timed
        assertNotNull("Lyrics should be loaded as Timed", timed)

        val list = timed!!.list
        assertFalse("Section tags like [Chorus] should be filtered out", list.any { it.text.matches(Regex("""^\[.*?\]$""")) })
        assertTrue("Lyrics body text should remain", list.isNotEmpty())
    }
}
