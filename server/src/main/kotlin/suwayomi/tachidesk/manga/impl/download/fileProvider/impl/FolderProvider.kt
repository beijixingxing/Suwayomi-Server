package suwayomi.tachidesk.manga.impl.download.fileProvider.impl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.impl.download.fileProvider.ChaptersFilesProvider
import suwayomi.tachidesk.manga.impl.download.fileProvider.FileType.RegularFile
import suwayomi.tachidesk.manga.impl.download.storage.DownloadStorage
import suwayomi.tachidesk.manga.impl.download.storage.RemoteCopyCache
import suwayomi.tachidesk.manga.impl.download.storage.StoragePaths
import suwayomi.tachidesk.manga.impl.util.getChapterCachePath
import suwayomi.tachidesk.manga.impl.util.getChapterDownloadPath
import suwayomi.tachidesk.manga.model.table.ChapterUserTable
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.injectLazy
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.Deflater

private val applicationDirs: ApplicationDirs by injectLazy()

/*
* Provides downloaded files when pages were downloaded into folders
* */
class FolderProvider(
    mangaId: Int,
    chapterId: Int,
    private val storage: DownloadStorage,
    /**
     * Backend the chapter content is actually read from. Defaults to [storage]; differs when a
     * chapter was downloaded through another backend (e.g. before the storage type was switched).
     */
    private val readStorage: DownloadStorage = storage,
) : ChaptersFilesProvider<RegularFile>(mangaId, chapterId) {
    private suspend fun chapterStoragePath(): String =
        StoragePaths.toStorageRelative(getChapterDownloadPath(mangaId, chapterId), applicationDirs.downloadsRoot)

    override suspend fun getImageFiles(): List<RegularFile> {
        val localDir = downloadFolderToLocal()
        if (localDir == null || !localDir.isDirectory) {
            throw NoSuchElementException("download folder does not exist")
        }
        return localDir
            .listFiles()
            .orEmpty()
            .toList()
            .map(::RegularFile)
    }

    override suspend fun getImageInputStream(image: RegularFile): FileInputStream = FileInputStream(image.file)

    override suspend fun extractExistingDownload() {
        // nothing to do
    }

    override suspend fun existsInActiveBackend(): Boolean = storage.listFiles(chapterStoragePath()).any { !it.isDirectory }

    override suspend fun handleSuccessfulDownload() {
        val chapterCacheFolder = File(getChapterCachePath(mangaId, chapterId))
        val chapterDownloadFolder = File(getChapterDownloadPath(mangaId, chapterId))

        // The download queue skips pages that already exist in the final download folder (see
        // [downloadImpl]), so re-downloading a chapter whose previous content was extracted there
        // (see [extractExistingDownload]) leaves those pages outside the cache folder. Copy them
        // into the cache first so the upload sees the complete chapter; files already present in
        // the cache (the newer downloads and the freshly written ComicInfo.xml) are kept.
        val cacheNames = chapterCacheFolder.listFiles().orEmpty().mapTo(mutableSetOf()) { it.name }
        chapterDownloadFolder
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.name !in cacheNames }
            .forEach { file -> file.copyTo(File(chapterCacheFolder, file.name)) }

        uploadFolderToStorage(chapterCacheFolder, chapterStoragePath())
    }

    override suspend fun delete(): Boolean {
        val path = chapterStoragePath()
        // Delete from the backend the chapter is actually read from as well — it differs from
        // [storage] for chapters downloaded through a previous storage type, and deleting only
        // from the active backend would leave the old copy behind as an orphan.
        val deletedFromActive = storage.deleteDirectory(path)
        val deleted =
            if (readStorage === storage) {
                deletedFromActive
            } else {
                deletedFromActive && readStorage.deleteDirectory(path)
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
        val localDir =
            downloadFolderToLocal()
                ?: throw IllegalArgumentException("Invalid folder to create CBZ for chapter ID: $chapterId")
        if (!localDir.isDirectory || localDir.listFiles().isNullOrEmpty()) {
            throw IllegalArgumentException("Invalid folder to create CBZ for chapter ID: $chapterId")
        }

        val byteArrayOutputStream = ByteArrayOutputStream()
        ZipArchiveOutputStream(BufferedOutputStream(byteArrayOutputStream)).use { zipOutputStream ->
            zipOutputStream.setMethod(ZipArchiveOutputStream.DEFLATED)
            zipOutputStream.setLevel(Deflater.DEFAULT_COMPRESSION)

            localDir
                .listFiles()
                ?.filter { it.isFile }
                ?.sortedBy { it.name }
                ?.forEach { imageFile ->
                    FileInputStream(imageFile).use { fileInputStream ->
                        val zipEntry = ZipArchiveEntry(imageFile.name)
                        zipEntry.time = 0L
                        zipOutputStream.putArchiveEntry(zipEntry)
                        fileInputStream.copyTo(zipOutputStream)
                        zipOutputStream.closeArchiveEntry()
                    }
                }
        }

        val zipData = byteArrayOutputStream.toByteArray()
        return ByteArrayInputStream(zipData) to zipData.size.toLong()
    }

    override suspend fun getArchiveSize(): Long {
        val files = readStorage.listFiles(chapterStoragePath())
        return files.filter { !it.isDirectory }.sumOf { it.size }
    }

    /**
     * Makes the chapter folder available as a local directory.
     *
     * A local backend is used in place — no copy is made at all, which is what the legacy
     * filesystem-only code did. Remote backends are materialised into a bounded cache so that
     * reading a page does not re-download every page of the chapter, without leaking temp
     * directories over time.
     */
    private suspend fun downloadFolderToLocal(): File? {
        val storagePath = chapterStoragePath()

        // Fast path: local backend, read the folder in place.
        readStorage.localPathOrNull(storagePath)?.takeIf { it.isDirectory }?.let { return it }

        val files = readStorage.listFiles(storagePath).filter { !it.isDirectory }
        if (files.isEmpty()) return null

        // Cheap change detection: a different page count or total size means the remote content
        // changed and the cached copy has to be refreshed.
        val signature = files.size to files.sumOf { it.size }

        return folderCacheMutex.withLock {
            folderCache.getIfValid(storagePath, signature)?.let { return@withLock it }

            val localDir = withContext(Dispatchers.IO) { Files.createTempDirectory("suwayomi-folder-").toFile() }
            for (file in files) {
                val stream = readStorage.readFile(file.path) ?: continue
                val localFile = File(localDir, file.path.substringAfterLast('/'))
                stream.use { input -> localFile.outputStream().use { output -> input.copyTo(output) } }
            }
            folderCache.put(storagePath, localDir, signature)
            localDir
        }
    }

    private suspend fun uploadFolderToStorage(
        localFolder: File,
        storagePath: String,
    ) {
        storage.createDirectory(storagePath)
        localFolder
            .listFiles()
            .orEmpty()
            .forEach { file ->
                val childPath = "$storagePath/${file.name}"
                if (file.isDirectory) {
                    uploadFolderToStorage(file, childPath)
                } else {
                    file.inputStream().use { input ->
                        storage.writeFile(childPath, input, file.length())
                    }
                }
            }
    }

    companion object {
        // Bounded cache of chapter folders downloaded from a remote backend, keyed by storage path
        // and validated by page count + total size. Local backends never touch it.
        private val folderCache = RemoteCopyCache()
        private val folderCacheMutex = Mutex()
    }
}
