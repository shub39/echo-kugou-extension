package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Lyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(DelicateCoroutinesApi::class)
@ExperimentalCoroutinesApi
class ExtensionUnitTest {
    private val extension = KugouExtension()

    // Test Setup
    private val mainThreadSurrogate = newSingleThreadContext("UI thread")
    @Before
    fun setUp() {
        Dispatchers.setMain(mainThreadSurrogate)
        extension.setSettings(MockedSettings())
        runBlocking {
            extension.onExtensionSelected()
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain() // reset the main dispatcher to the original Main dispatcher
        mainThreadSurrogate.close()
    }

    private fun testIn(title: String, block: suspend CoroutineScope.() -> Unit) = runBlocking {
        println("\n-- $title --")
        block.invoke(this)
        println("\n")
    }

    // Actual Tests
    @Test
    fun testSearch() = testIn("Search Test") {
        extension.searchLyrics("Satan in the Wait").loadAll().forEach {
            println(it.title)
            println(it.subtitle)
            println(it.id)
        }
    }

    @Test
    fun testLyrics() = testIn("Lyrics Test") {
        extension.loadLyrics(
            Lyrics(
                id = "a4adb02bf671258eb6f07ee523935b1d",
                title = "Satan in the wait"
            )
        ).lyrics.also {
            println(it)
        }
    }
}