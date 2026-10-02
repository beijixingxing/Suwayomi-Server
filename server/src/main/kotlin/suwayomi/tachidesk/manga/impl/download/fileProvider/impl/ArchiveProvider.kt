package suwayomi.tachidesk.manga.impl.download.fileProvider.impl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.impl.download.fileProvider.ChaptersFilesProvider
import suwayomi.tachidesk.manga.impl.download.fileProvider.FileType
import suwayomi.tachidesk.manga.impl.download.storage.DownloadStorage
import suwayomi.tachidesk.manga.impl.download.storage.StoragePaths
import suwayomi.tachidesk.manga.impl.util.getChapterCachePath
import suwayomi.tachidesk.manga.impl.util.getChapterCbzPath
import suwayomi.tachidesk.manga.impl.util.getChapterDownloadPath
import suwayomi.tachidesk.manga.impl.util.getMangaDownloadDir
import suwayomi.tachidesk.manga.model.table.ChapterUserTable
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Deflater

private val applicationDirs: ApplicationDirs by injectLazy()

class ArchiveProvider(
    mangaId: Int,
    chapterId: Int,
    private val storage: DownloadStorage,
) : ChaptersFilesProvider<FileType.ZipFile>(mangaId, chapterId) {
    private suspend fun cbzStoragePath(): String =
        StoragePaths.toStorageRelative(getChapterCbzPath(mangaId, chapterId), applicationDirs.downloadsRoot)

    override suspend fun getImageFiles(): List<FileType.ZipFile> {
        val localCbz = downloadCbzToLocal() ?: throw NoSuchElementException("CBZ file does not exist")
        return ZipFile.builder().setFile(localCbz).get().use { zipFile ->
            zipFile.entries.toList().map { FileType.ZipFile(it) }
        }
    }

    override suspend fun getImageInputStream(image: FileType.ZipFile): InputStream {
        val localCbz = downloadCbzToLocal() ?: throw NoSuchElementException("CBZ file does not exist")
        return ZipFile.builder().setFile(localCbz).get().use { zipFile ->
            zipFile.getInputStream(image.entry).use { input ->
                input.readBytes().inputStream()
            }
        }
    }

    override suspend fun extractExistingDownload() {
        val chapterDownloadFolder = File(getChapterDownloadPath(mangaId, chapterId))
        val localCbz = downloadCbzToLocal() ?: return
        extractCbzFile(localCbz, chapterDownloadFolder)
    }

    override suspend fun handleSuccessfulDownload() {
        val mangaDownloadFolder = File(getMangaDownloadDir(mangaId))
        val chapterCacheFolder = File(getChapterCachePath(mangaId, chapterId))
        val sourceFolder = resolveSourceFolder()

        // build the CBZ in a local temp file, then upload to the storage backend
        val tempCbz = File.createTempFile("suwayomi-cbz-", ".cbz")
        try {
            withContext(Dispatchers.IO) {
                mangaDownloadFolder.mkdirs()
                ZipArchiveOutputStream(tempCbz.outputStream()).use { zipOut ->
                    zipOut.setMethod(ZipArchiveOutputStream.DEFLATED)
                    zipOut.setLevel(Deflater.DEFAULT_COMPRESSION)
                    if (sourceFolder != null) {
                        sourceFolder.listFiles()?.sortedBy { it.name }?.forEach {
                            val entry = ZipArchiveEntry(it.name)
                            entry.time = 0L
                            try {
                                zipOut.putArchiveEntry(entry)
                                it.inputStream().use { inputStream ->
                                    inputStream.copyTo(zipOut)
                                }
                            } finally {
                                zipOut.closeArchiveEntry()
                            }
                        }
                    }
                }
            }

            // upload the CBZ to the storage backend
            tempCbz.inputStream().use { input ->
                storage.writeFile(cbzStoragePath(), input, tempCbz.length())
            }
        } finally {
            tempCbz.delete()
        }

        if (chapterCacheFolder.exists() && chapterCacheFolder.isDirectory) {
            chapterCacheFolder.deleteRecursively()
        }
    }

    override suspend fun delete(): Boolean {
        val deleted = storage.deleteFile(cbzStoragePath())
        if (deleted) {
            transaction {
                ChapterUserTable.update({ ChapterUserTable.chapter eq chapterId }) {
                    it[koreaderHash] = null
                }
            }
        }
        return deleted
    }

    override suspend fun getAsArchiveStream(): Pair<InputStream, Long> {
        val localCbz = downloadCbzToLocal()
            ?: throw IllegalArgumentException("CBZ file not found for chapter ID: $chapterId (Manga ID: $mangaId)")
        val size = localCbz.length()
        return localCbz.inputStream() to size
    }

    override suspend fun getArchiveSize(): Long = storage.fileSize(cbzStoragePath())

    /**
     * Downloads the CBZ to a local cache file, reusing a previous copy when the remote
     * size is unchanged. Reading a chapter page otherwise re-downloads the entire archive
     * for every page.
     */
    private suspend fun downloadCbzToLocal(): File? {
        val path = cbzStoragePath()
        val remoteSize = storage.fileSize(path)
        if (remoteSize <= 0) return null

        cbzCache[path]?.let { (file, size) ->
            if (size == remoteSize && file.exists()) return file
        }

        return cacheMutex.withLock {
            // double-check after acquiring the lock
            cbzCache[path]?.let { (file, size) ->
                if (size == remoteSize && file.exists()) return@withLock file
            }

            val stream = storage.readFile(path) ?: return@withLock null
            val cacheFile = File(cbzCacheDir, "${path.hashCode().toUInt()}.cbz")
            withContext(Dispatchers.IO) {
                stream.use { input -> cacheFile.outputStream().use { output -> input.copyTo(output) } }
            }
            cacheFile.deleteOnExit()
            cbzCache[path] = cacheFile to remoteSize
            cacheFile
        }
    }

    companion object {
        // Short-lived cache of downloaded CBZ files keyed by storage path. Invalidated when
        // the remote size changes; entries are cleaned up on JVM exit via deleteOnExit().
        private val cbzCache = ConcurrentHashMap<String, Pair<File, Long>>()
        private val cbzCacheDir = File(System.getProperty("java.io.tmpdir"), "suwayomi-cbz-cache").apply { mkdirs() }
        private val cacheMutex = Mutex()
    }

    private fun extractCbzFile(
        cbzFile: File,
        chapterFolder: File,
    ) {
        if (!chapterFolder.exists()) chapterFolder.mkdirs()
        ZipArchiveInputStream(cbzFile.inputStream()).use { zipInputStream ->
            var zipEntry = zipInputStream.nextEntry
            while (zipEntry != null) {
                val file = File(chapterFolder, zipEntry.name)
                if (!file.exists()) {
                    file.parentFile.mkdirs()
                    file.createNewFile()
                }
                file.outputStream().use { outputStream ->
                    zipInputStream.copyTo(outputStream)
                }
                zipEntry = zipInputStream.nextEntry
            }
        }
    }
}
