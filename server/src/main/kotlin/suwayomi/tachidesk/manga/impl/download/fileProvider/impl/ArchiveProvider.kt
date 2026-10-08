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
import suwayomi.tachidesk.manga.impl.download.storage.RemoteCopyCache
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
import java.util.zip.Deflater

private val applicationDirs: ApplicationDirs by injectLazy()

class ArchiveProvider(
    mangaId: Int,
    chapterId: Int,
    private val storage: DownloadStorage,
    /**
     * Backend the chapter content is actually read from. Defaults to [storage]; differs when a
     * chapter was downloaded through another backend (e.g. before the storage type was switched).
     */
    private val readStorage: DownloadStorage = storage,
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

    override suspend fun existsInActiveBackend(): Boolean = storage.fileSize(cbzStoragePath()) > 0

    override suspend fun handleSuccessfulDownload() {
        val mangaDownloadFolder = File(getMangaDownloadDir(mangaId))
        val chapterCacheFolder = File(getChapterCachePath(mangaId, chapterId))
        val chapterDownloadFolder = File(getChapterDownloadPath(mangaId, chapterId))

        // Chapter files can live in two places at this point: freshly downloaded pages and the
        // freshly written ComicInfo.xml in the cache folder, and pages extracted from a previous
        // download (see [extractExistingDownload]) or left over from a folder-mode download in
        // the final download folder. Both must end up in the archive; on name conflicts the
        // cache copy wins because it is the newer file.
        val cacheFiles = chapterCacheFolder.listFiles().orEmpty().associateBy { it.name }
        val downloadFiles = chapterDownloadFolder.listFiles().orEmpty().associateBy { it.name }
        val sourceFiles = (downloadFiles + cacheFiles).values.sortedBy { it.name }

        // build the CBZ in a local temp file, then upload to the storage backend
        val tempCbz = File.createTempFile("suwayomi-cbz-", ".cbz")
        try {
            withContext(Dispatchers.IO) {
                mangaDownloadFolder.mkdirs()
                ZipArchiveOutputStream(tempCbz.outputStream()).use { zipOut ->
                    zipOut.setMethod(ZipArchiveOutputStream.DEFLATED)
                    zipOut.setLevel(Deflater.DEFAULT_COMPRESSION)
                    sourceFiles.forEach {
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
        val path = cbzStoragePath()
        // Delete from the backend the chapter is actually read from as well — it differs from
        // [storage] for chapters downloaded through a previous storage type, and deleting only
        // from the active backend would leave the old copy behind as an orphan.
        val deletedFromActive = storage.deleteFile(path)
        val deleted =
            if (readStorage === storage) {
                deletedFromActive
            } else {
                deletedFromActive && readStorage.deleteFile(path)
            }
        if (deleted) {
            storage.cleanupEmptyParents(path)
            if (readStorage !== storage) {
                readStorage.cleanupEmptyParents(path)
            }
            transaction {
                ChapterUserTable.update({ ChapterUserTable.chapter eq chapterId }) {
                    it[koreaderHash] = null
                }
            }
        }
        return deleted
    }

    override suspend fun getAsArchiveStream(): Pair<InputStream, Long> {
        val localCbz =
            downloadCbzToLocal()
                ?: throw IllegalArgumentException("CBZ file not found for chapter ID: $chapterId (Manga ID: $mangaId)")
        val size = localCbz.length()
        return localCbz.inputStream() to size
    }

    override suspend fun getArchiveSize(): Long = readStorage.fileSize(cbzStoragePath())

    /**
     * Makes the CBZ available as a local file.
     *
     * A local backend is used in place — no copy is made at all. For remote backends the archive is
     * downloaded into a bounded cache so that reading a chapter does not re-download the whole
     * archive for every page while still not filling the disk over time.
     */
    private suspend fun downloadCbzToLocal(): File? {
        val path = cbzStoragePath()

        // Fast path: local backend, read the file in place.
        readStorage.localPathOrNull(path)?.takeIf { it.isFile }?.let { return it }

        val remoteSize = readStorage.fileSize(path)
        if (remoteSize <= 0) return null

        return cacheMutex.withLock {
            // double-check after acquiring the lock
            cbzCache.getIfValid(path, remoteSize)?.let { return@withLock it }

            val stream = readStorage.readFile(path) ?: return@withLock null
            val cacheFile = File(cbzCacheDir, "${path.hashCode().toUInt()}.cbz")
            withContext(Dispatchers.IO) {
                stream.use { input -> cacheFile.outputStream().use { output -> input.copyTo(output) } }
            }
            cbzCache.put(path, cacheFile, remoteSize)
            cacheFile
        }
    }

    companion object {
        // Bounded cache of CBZ copies downloaded from a remote backend, keyed by storage path and
        // validated by the remote size. Local backends never touch it.
        private val cbzCache = RemoteCopyCache()
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
