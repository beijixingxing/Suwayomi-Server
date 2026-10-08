package suwayomi.tachidesk.manga.impl.download.fileProvider.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.impl.download.model.DownloadQueueItem
import suwayomi.tachidesk.manga.impl.download.storage.DownloadStorage
import suwayomi.tachidesk.manga.impl.download.storage.StorageFile
import suwayomi.tachidesk.manga.impl.download.storage.StoragePaths
import suwayomi.tachidesk.manga.impl.util.getChapterCachePath
import suwayomi.tachidesk.manga.impl.util.getChapterCbzPath
import suwayomi.tachidesk.manga.impl.util.getChapterDownloadPath
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.createChapters
import suwayomi.tachidesk.test.createLibraryManga
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the download upload/delete flows of the file providers against a recording
 * [DownloadStorage] fake.
 *
 * The migration scenario (chapter downloaded through a previous storage backend, re-downloaded
 * after switching) is simulated by placing the extracted pages in the final download folder —
 * exactly the state the download loop sees when it skips pages that already exist locally, so
 * only the freshly written ComicInfo.xml lands in the download cache.
 *
 * The tests drive the real download entry point; no page is fetched because every page already
 * exists on disk.
 */
class ProviderDownloadTest : ApplicationTest() {
    private val applicationDirs: ApplicationDirs by injectLazy()

    /** In-memory [DownloadStorage] recording written files and directories. */
    private class RecordingStorage : DownloadStorage {
        val files = mutableMapOf<String, ByteArray>()
        val directories = mutableSetOf<String>()

        override suspend fun exists(path: String): Boolean = files.containsKey(path)

        override suspend fun writeFile(
            path: String,
            content: InputStream,
            size: Long,
        ) {
            files[path] = content.readBytes()
        }

        override suspend fun readFile(path: String): InputStream? = files[path]?.inputStream()

        override suspend fun deleteFile(path: String): Boolean {
            files.remove(path)
            return true
        }

        override suspend fun fileSize(path: String): Long = files[path]?.size?.toLong() ?: 0L

        override suspend fun listFiles(dirPath: String): List<StorageFile> =
            files.keys
                .filter { it.startsWith("$dirPath/") }
                .map { StorageFile(it, isDirectory = false, size = files[it]!!.size.toLong()) }

        override suspend fun createDirectory(dirPath: String) {
            directories.add(dirPath)
        }

        override suspend fun deleteDirectory(dirPath: String): Boolean {
            files.keys.removeAll { it.startsWith("$dirPath/") }
            directories.remove(dirPath)
            return true
        }
    }

    private fun newMangaWithChapter(title: String): Pair<Int, Int> {
        val mangaId = createLibraryManga(title)
        createChapters(mangaId, 1, false)
        val chapterId =
            transaction {
                ChapterTable
                    .selectAll()
                    .where { ChapterTable.manga eq mangaId }
                    .map { it[ChapterTable.id].value }
                    .first()
            }
        return mangaId to chapterId
    }

    private suspend fun cbzStoragePath(
        mangaId: Int,
        chapterId: Int,
    ): String = StoragePaths.toStorageRelative(getChapterCbzPath(mangaId, chapterId), applicationDirs.downloadsRoot)

    private suspend fun chapterStoragePath(
        mangaId: Int,
        chapterId: Int,
    ): String = StoragePaths.toStorageRelative(getChapterDownloadPath(mangaId, chapterId), applicationDirs.downloadsRoot)

    /** Runs the full download flow for the chapter with the given page count. */
    private suspend fun runDownload(
        provider: ArchiveProvider,
        mangaId: Int,
        chapterId: Int,
        pageCount: Int,
    ): Boolean {
        val item =
            DownloadQueueItem(
                chapterId = chapterId,
                chapterIndex = 0,
                mangaId = mangaId,
                sourceId = 1L,
                pageCount = pageCount,
            )
        return provider.download().execute(item, CoroutineScope(Job())) { _, _ -> }
    }

    private suspend fun runDownload(
        provider: FolderProvider,
        mangaId: Int,
        chapterId: Int,
        pageCount: Int,
    ): Boolean {
        val item =
            DownloadQueueItem(
                chapterId = chapterId,
                chapterIndex = 0,
                mangaId = mangaId,
                sourceId = 1L,
                pageCount = pageCount,
            )
        return provider.download().execute(item, CoroutineScope(Job())) { _, _ -> }
    }

    private fun zipEntryNames(cbz: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipArchiveInputStream(cbz.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names.add(entry.name)
                entry = zip.nextEntry
            }
        }
        return names
    }

    private fun zipEntryContent(
        cbz: ByteArray,
        name: String,
    ): ByteArray {
        ZipArchiveInputStream(cbz.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == name) return zip.readBytes()
                entry = zip.nextEntry
            }
        }
        throw IllegalArgumentException("no entry $name in test archive")
    }

    @Test
    fun archiveUploadMergesExtractedPagesWithCache() =
        runBlocking {
            val (mangaId, chapterId) = newMangaWithChapter("ArchiveMergeManga")
            val storage = RecordingStorage()
            val provider = ArchiveProvider(mangaId, chapterId, storage)

            // migration state: extracted pages in the final folder, empty cache
            val finalFolder = File(getChapterDownloadPath(mangaId, chapterId)).apply { mkdirs() }
            File(finalFolder, "001.jpg").writeBytes("page1".toByteArray())
            File(finalFolder, "002.jpg").writeBytes("page2".toByteArray())

            assertTrue(runDownload(provider, mangaId, chapterId, 2))

            val cbz = storage.files[cbzStoragePath(mangaId, chapterId)]
            assertTrue(cbz != null, "CBZ was not uploaded")
            assertEquals(setOf("001.jpg", "002.jpg", "ComicInfo.xml"), zipEntryNames(cbz!!).toSet())
        }

    @Test
    fun archiveUploadPrefersCacheFileOnNameConflicts() =
        runBlocking {
            val (mangaId, chapterId) = newMangaWithChapter("ArchiveConflictManga")
            val storage = RecordingStorage()
            val provider = ArchiveProvider(mangaId, chapterId, storage)

            // a previous download attempt left a page in the cache; the extraction also placed
            // an (older) copy in the final folder — the cache copy must win
            val finalFolder = File(getChapterDownloadPath(mangaId, chapterId)).apply { mkdirs() }
            File(finalFolder, "001.jpg").writeBytes("extracted-page".toByteArray())
            File(finalFolder, "ComicInfo.xml").writeBytes("<old-comicinfo/>".toByteArray())
            val cacheFolder = File(getChapterCachePath(mangaId, chapterId)).apply { mkdirs() }
            File(cacheFolder, "001.jpg").writeBytes("cached-page".toByteArray())

            assertTrue(runDownload(provider, mangaId, chapterId, 1))

            val cbz = storage.files[cbzStoragePath(mangaId, chapterId)]!!
            assertEquals("cached-page", String(zipEntryContent(cbz, "001.jpg")))
            // the freshly generated ComicInfo must replace the extracted one
            assertFalse(String(zipEntryContent(cbz, "ComicInfo.xml")) == "<old-comicinfo/>")
        }

    @Test
    fun folderUploadMergesExtractedPagesWithCache() =
        runBlocking {
            val (mangaId, chapterId) = newMangaWithChapter("FolderMergeManga")
            val storage = RecordingStorage()
            val provider = FolderProvider(mangaId, chapterId, storage)

            val finalFolder = File(getChapterDownloadPath(mangaId, chapterId)).apply { mkdirs() }
            File(finalFolder, "001.jpg").writeBytes("page1".toByteArray())

            assertTrue(runDownload(provider, mangaId, chapterId, 1))

            val chapterPath = chapterStoragePath(mangaId, chapterId)
            assertEquals(
                setOf("$chapterPath/001.jpg", "$chapterPath/ComicInfo.xml"),
                storage.files.keys,
            )
        }

    @Test
    fun archiveDeleteAlsoDeletesFromReadStorage() =
        runBlocking {
            val (mangaId, chapterId) = newMangaWithChapter("ArchiveDeleteManga")
            val active = RecordingStorage()
            val read = RecordingStorage()
            val path = cbzStoragePath(mangaId, chapterId)
            read.files[path] = "old-download".toByteArray()

            val provider = ArchiveProvider(mangaId, chapterId, active, read)
            assertTrue(provider.delete())
            assertFalse(read.files.containsKey(path), "old backend copy was left behind")
        }

    @Test
    fun folderDeleteAlsoDeletesFromReadStorage() =
        runBlocking {
            val (mangaId, chapterId) = newMangaWithChapter("FolderDeleteManga")
            val active = RecordingStorage()
            val read = RecordingStorage()
            val chapterPath = chapterStoragePath(mangaId, chapterId)
            read.files["$chapterPath/001.jpg"] = "old-download".toByteArray()

            val provider = FolderProvider(mangaId, chapterId, active, read)
            assertTrue(provider.delete())
            assertTrue(read.files.isEmpty(), "old backend copy was left behind")
        }
}
