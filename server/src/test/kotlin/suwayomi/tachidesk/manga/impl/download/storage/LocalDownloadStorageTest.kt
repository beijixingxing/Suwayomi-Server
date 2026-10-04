package suwayomi.tachidesk.manga.impl.download.storage

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalDownloadStorageTest {
    private val tempDirs = mutableListOf<File>()

    private fun newTempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().also { tempDirs += it }

    private fun newStorage(): LocalDownloadStorage {
        val root = newTempDir("local-storage-test-")
        return LocalDownloadStorage(root.absolutePath)
    }

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun writesAndReadsBackFileContent() =
        runBlocking {
            val storage = newStorage()
            val payload = "hello".toByteArray()

            storage.writeFile("a/b.txt", ByteArrayInputStream(payload), payload.size.toLong())

            assertTrue(storage.exists("a/b.txt"))
            assertEquals(payload.size.toLong(), storage.fileSize("a/b.txt"))
            val read = storage.readFile("a/b.txt")?.use { it.readBytes() }
            assertEquals("hello", read?.let(::String))
        }

    @Test
    fun readFileReturnsNullForMissingFile() =
        runBlocking {
            assertNull(newStorage().readFile("nope.txt"))
        }

    @Test
    fun deleteFileIsIdempotent() =
        runBlocking {
            val storage = newStorage()

            // deleting something that never existed is a success
            assertTrue(storage.deleteFile("nope.txt"))

            storage.writeFile("a.txt", ByteArrayInputStream(byteArrayOf(1)), 1)
            assertTrue(storage.deleteFile("a.txt"))
            assertFalse(storage.exists("a.txt"))
            assertTrue(storage.deleteFile("a.txt"))
        }

    @Test
    fun deleteDirectoryIsIdempotent() =
        runBlocking {
            val storage = newStorage()

            assertTrue(storage.deleteDirectory("nope/nested"))

            storage.writeFile("dir/a.txt", ByteArrayInputStream(byteArrayOf(1)), 1)
            assertTrue(storage.deleteDirectory("dir"))
            assertFalse(storage.exists("dir"))
            assertTrue(storage.deleteDirectory("dir"))
        }

    @Test
    fun listFilesReturnsStorageRelativeChildPaths() =
        runBlocking {
            val storage = newStorage()
            storage.writeFile("dir/a.txt", ByteArrayInputStream(byteArrayOf(1)), 1)
            storage.writeFile("dir/nested/b.txt", ByteArrayInputStream(byteArrayOf(1, 2)), 2)

            val children = storage.listFiles("dir").associateBy { it.path.substringAfterLast('/') }

            assertEquals(setOf("a.txt", "nested"), children.keys)
            assertEquals(1L, children.getValue("a.txt").size)
            assertTrue(children.getValue("nested").isDirectory)
            assertEquals("dir/a.txt", children.getValue("a.txt").path)
        }

    @Test
    fun listFilesReturnsEmptyListForMissingDirectory() =
        runBlocking {
            assertTrue(newStorage().listFiles("missing").isEmpty())
        }

    @Test
    fun localPathOrNullOnlyReturnsExistingPaths() =
        runBlocking {
            val storage = newStorage()
            storage.writeFile("dir/a.txt", ByteArrayInputStream(byteArrayOf(1)), 1)

            assertTrue(storage.localPathOrNull("dir")?.isDirectory == true)
            assertTrue(storage.localPathOrNull("dir/a.txt")?.isFile == true)
            assertNull(storage.localPathOrNull("dir/missing.txt"))
        }

    @Test
    fun cleanupEmptyParentsRemovesEmptyParentsButKeepsTheCleanupRoot() =
        runBlocking {
            val root = newTempDir("local-storage-cleanup-")
            val mangaRoot = File(root, "mangas").apply { mkdirs() }
            val storage = LocalDownloadStorage(root.absolutePath, mangaRoot.absolutePath)

            storage.writeFile("mangas/Manga/Chapter/1.png", ByteArrayInputStream(byteArrayOf(1)), 1)
            assertTrue(storage.deleteDirectory("mangas/Manga/Chapter"))
            storage.cleanupEmptyParents("mangas/Manga/Chapter")

            assertTrue(mangaRoot.exists(), "the manga root must never be deleted")
            assertFalse(File(mangaRoot, "Manga").exists(), "the now empty manga folder must be removed")
        }
}
