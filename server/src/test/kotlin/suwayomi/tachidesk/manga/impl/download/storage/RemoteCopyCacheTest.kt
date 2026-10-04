package suwayomi.tachidesk.manga.impl.download.storage

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteCopyCacheTest {
    private val tempDirs = mutableListOf<File>()

    private fun newTempDir(): File = Files.createTempDirectory("remote-copy-cache-test-").toFile().also { tempDirs += it }

    private fun newFile(
        dir: File,
        name: String,
    ): File = File(dir, name).apply { writeText(name) }

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun returnsNullForUnknownKey() {
        assertNull(RemoteCopyCache().getIfValid("missing", 1L))
    }

    @Test
    fun returnsTheStoredFileWhileTheSignatureMatches() {
        val dir = newTempDir()
        val cache = RemoteCopyCache()
        val file = newFile(dir, "a")

        cache.put("a", file, 10L)

        assertEquals(file, cache.getIfValid("a", 10L))
    }

    @Test
    fun changedSignatureInvalidatesTheEntry() {
        val dir = newTempDir()
        val cache = RemoteCopyCache()
        cache.put("a", newFile(dir, "a"), 10L)

        assertNull(cache.getIfValid("a", 11L))
    }

    @Test
    fun removedFileInvalidatesTheEntry() {
        val dir = newTempDir()
        val cache = RemoteCopyCache()
        val file = newFile(dir, "a")
        cache.put("a", file, 10L)
        assertTrue(file.delete())

        assertNull(cache.getIfValid("a", 10L))
    }

    @Test
    fun evictsTheEldestEntryAndDeletesItsFile() {
        val dir = newTempDir()
        val cache = RemoteCopyCache(maxEntries = 2)
        val first = newFile(dir, "first")
        val second = newFile(dir, "second")
        val third = newFile(dir, "third")

        cache.put("first", first, 1L)
        cache.put("second", second, 1L)
        cache.put("third", third, 1L)

        assertNull(cache.getIfValid("first", 1L), "the eldest entry must be evicted")
        assertFalse(first.exists(), "an evicted entry's file must be deleted")
        assertEquals(second, cache.getIfValid("second", 1L))
        assertEquals(third, cache.getIfValid("third", 1L))
    }

    @Test
    fun keepsTheCacheBoundedAcrossManyInserts() {
        val dir = newTempDir()
        val cache = RemoteCopyCache(maxEntries = 3)

        repeat(10) { index ->
            cache.put("key-$index", newFile(dir, "file-$index"), 1L)
        }

        val liveEntries = (0 until 10).count { cache.getIfValid("key-$it", 1L) != null }

        assertEquals(3, liveEntries)
    }
}
