package suwayomi.tachidesk.manga.impl.download.fileProvider.impl

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.impl.download.fileProvider.ChaptersFilesProvider
import suwayomi.tachidesk.manga.impl.download.fileProvider.FileType.RegularFile
import suwayomi.tachidesk.manga.impl.download.storage.DownloadStorage
import suwayomi.tachidesk.manga.impl.download.storage.StoragePaths
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
import java.util.zip.Deflater

private val applicationDirs: ApplicationDirs by injectLazy()

/*
* Provides downloaded files when pages were downloaded into folders
* */
class FolderProvider(
    mangaId: Int,
    chapterId: Int,
    private val storage: DownloadStorage,
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

    override suspend fun handleSuccessfulDownload() {
        val sourceFolder = resolveSourceFolder() ?: return
        uploadFolderToStorage(sourceFolder, chapterStoragePath())
    }

    override suspend fun delete(): Boolean {
        val deleted = storage.deleteDirectory(chapterStoragePath())
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
        val localDir = downloadFolderToLocal()
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
        val files = storage.listFiles(chapterStoragePath())
        return files.filter { !it.isDirectory }.sumOf { it.size }
    }

    private suspend fun downloadFolderToLocal(): File? {
        val files = storage.listFiles(chapterStoragePath())
        if (files.isEmpty()) return null

        val localDir = File.createTempFile("suwayomi-folder-", "").apply { delete(); mkdirs() }
        for (file in files) {
            if (file.isDirectory) continue
            val stream = storage.readFile(file.path) ?: continue
            val localFile = File(localDir, file.path.substringAfterLast('/'))
            stream.use { input -> localFile.outputStream().use { output -> input.copyTo(output) } }
        }
        return localDir
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
}
