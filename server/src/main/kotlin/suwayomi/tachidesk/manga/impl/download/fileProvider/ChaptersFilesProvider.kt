package suwayomi.tachidesk.manga.impl.download.fileProvider

import eu.kanade.tachiyomi.source.local.metadata.COMIC_INFO_FILE
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import libcore.net.MimeUtils
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.impl.Page
import suwayomi.tachidesk.manga.impl.chapter.getChapterDownloadReady
import suwayomi.tachidesk.manga.impl.download.model.DownloadQueueItem
import suwayomi.tachidesk.manga.impl.util.KoreaderHelper
import suwayomi.tachidesk.manga.impl.util.createComicInfoFile
import suwayomi.tachidesk.manga.impl.util.getChapterCachePath
import suwayomi.tachidesk.manga.impl.util.getChapterCbzPath
import suwayomi.tachidesk.manga.impl.util.getChapterDownloadPath
import suwayomi.tachidesk.manga.impl.util.storage.ImageResponse
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.ChapterUserTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import java.io.File
import java.io.InputStream

sealed class FileType {
    data class RegularFile(
        val file: File,
    ) : FileType()

    data class ZipFile(
        val entry: ZipArchiveEntry,
    ) : FileType()

    fun getName(): String =
        when (this) {
            is RegularFile -> {
                this.file.name
            }

            is ZipFile -> {
                this.entry.name
            }
        }

    fun getExtension(): String =
        when (this) {
            is RegularFile -> {
                this.file.extension
            }

            is ZipFile -> {
                this.entry.name.substringAfterLast(".")
            }
        }
}

/*
* Base class for downloaded chapter files provider, example: Folder, Archive
*/
abstract class ChaptersFilesProvider<Type : FileType>(
    val mangaId: Int,
    val chapterId: Int,
) : DownloadedFilesProvider {
    protected val logger = KotlinLogging.logger {}

    protected abstract suspend fun getImageFiles(): List<Type>

    protected abstract suspend fun getImageInputStream(image: Type): InputStream

    suspend fun getImageImpl(index: Int): Pair<InputStream, String> {
        val images = getImageFiles().filter { it.getName() != COMIC_INFO_FILE }.sortedBy { it.getName() }

        if (images.isEmpty()) {
            throw NoSuchElementException("no downloaded images found")
        }

        val image = images[index]
        val imageFileType = image.getExtension()

        return Pair(getImageInputStream(image).buffered(), MimeUtils.guessMimeTypeFromExtension(imageFileType) ?: "image/$imageFileType")
    }

    suspend fun getImageCount(): Int = getImageFiles().filter { it.getName() != COMIC_INFO_FILE }.size

    override suspend fun getImage(): RetrieveFile1Args<Int> = RetrieveFile1Args(::getImageImpl)

    /**
     * Extract the existing download to the base download folder (see [getChapterDownloadPath])
     */
    protected abstract suspend fun extractExistingDownload()

    protected abstract suspend fun handleSuccessfulDownload()

    /**
     * Whether the backend downloads are written to already holds this chapter.
     *
     * Deliberately ignores any read fallback: content that only exists in a previously used backend
     * must not make the chapter look downloaded for the active backend, otherwise switching the
     * storage type and re-downloading would never upload the chapter to the new backend.
     */
    protected abstract suspend fun existsInActiveBackend(): Boolean

    /**
     * Resolves the folder containing chapter pages, preferring the download cache and falling back
     * to the local download directory.
     *
     * The download queue skips pages that already exist in the local final-download folder
     * (see [downloadImpl]), so re-downloading a chapter that was previously stored locally may
     * leave the cache folder empty. This method returns [getChapterDownloadPath] when the cache is
     * empty but the local download directory still holds the pages, so the storage backend still
     * receives the files.
     */
    protected suspend fun resolveSourceFolder(): File? {
        val cacheFolder = File(getChapterCachePath(mangaId, chapterId))
        if (cacheFolder.isDirectory && cacheFolder.listFiles()?.isNotEmpty() == true) {
            return cacheFolder
        }
        return File(getChapterDownloadPath(mangaId, chapterId))
            .takeIf { it.isDirectory && it.listFiles()?.isNotEmpty() == true }
    }

    @OptIn(FlowPreview::class)
    private suspend fun downloadImpl(
        download: DownloadQueueItem,
        scope: CoroutineScope,
        step: suspend (DownloadQueueItem?, Boolean) -> Unit,
    ): Boolean {
        val existingDownloadPageCount =
            if (!existsInActiveBackend()) {
                // The chapter may still exist in a previously used backend; treat it as not
                // downloaded here so the download runs and uploads it to the active backend.
                0
            } else {
                try {
                    getImageCount()
                } catch (_: Exception) {
                    0
                }
            }
        val pageCount = download.pageCount

        check(pageCount > 0) { "pageCount must be greater than 0 - ChapterForDownload#getChapterDownloadReady not called" }
        check(existingDownloadPageCount == 0 || existingDownloadPageCount == pageCount) {
            "existingDownloadPageCount must be 0 or equal to pageCount - ChapterForDownload#getChapterDownloadReady not called"
        }

        val doesUnrecognizedDownloadExist = existingDownloadPageCount == pageCount
        if (doesUnrecognizedDownloadExist) {
            download.progress = 1f
            step(download, false)

            return true
        }

        extractExistingDownload()

        val finalDownloadFolder = getChapterDownloadPath(mangaId, chapterId)

        val cacheChapterDir = getChapterCachePath(mangaId, chapterId)
        val downloadCacheFolder = File(cacheChapterDir)
        downloadCacheFolder.mkdirs()

        for (pageNum in 0 until pageCount) {
            var pageProgressJob: Job? = null
            val fileName = Page.getPageName(pageNum, pageCount) // might have to change this to index stored in database

            val pageExistsInFinalDownloadFolder = ImageResponse.findFileNameStartingWith(finalDownloadFolder, fileName) != null
            val pageExistsInCacheDownloadFolder = ImageResponse.findFileNameStartingWith(cacheChapterDir, fileName) != null

            val doesPageAlreadyExist = pageExistsInFinalDownloadFolder || pageExistsInCacheDownloadFolder
            if (doesPageAlreadyExist) {
                continue
            }

            try {
                Page
                    .getPageImageDownload(
                        mangaId = download.mangaId,
                        chapterId = download.chapterId,
                        index = pageNum,
                        downloadCacheFolder,
                        fileName,
                    ) { flow ->
                        pageProgressJob =
                            flow
                                .sample(100)
                                .distinctUntilChanged()
                                .onEach {
                                    download.progress = (pageNum.toFloat() + (it.toFloat() * 0.01f)) / pageCount
                                    step(
                                        null,
                                        false,
                                    ) // don't throw on canceled download here since we can't do anything
                                }.launchIn(scope)
                    }
            } finally {
                // always cancel the page progress job even if it throws an exception to avoid memory leaks
                pageProgressJob?.cancel()
            }
            // TODO: retry on error with 2,4,8 seconds of wait
            download.progress = ((pageNum + 1).toFloat()) / pageCount
            step(download, false)
        }

        createComicInfoFile(
            downloadCacheFolder.toPath(),
            transaction {
                MangaTable.selectAll().where { MangaTable.id eq mangaId }.first()
            },
            transaction {
                ChapterTable.selectAll().where { ChapterTable.id eq chapterId }.first()
            },
        )

        handleSuccessfulDownload()

        // Calculate and save Koreader hash for CBZ files
        val chapterFile = File(getChapterCbzPath(mangaId, chapterId))
        if (chapterFile.exists()) {
            val koreaderHash = KoreaderHelper.hashContents(chapterFile)
            if (koreaderHash != null) {
                transaction {
                    ChapterUserTable.update({ ChapterUserTable.chapter eq chapterId }) {
                        it[ChapterUserTable.koreaderHash] = koreaderHash
                    }
                }
            }
        }

        File(cacheChapterDir).deleteRecursively()

        return true
    }

    /**
     * This function should never be called without calling [getChapterDownloadReady] beforehand.
     */
    override fun download(): FileDownload3Args<DownloadQueueItem, CoroutineScope, suspend (DownloadQueueItem?, Boolean) -> Unit> =
        FileDownload3Args(::downloadImpl)

    abstract override suspend fun delete(): Boolean

    abstract suspend fun getAsArchiveStream(): Pair<InputStream, Long>

    abstract suspend fun getArchiveSize(): Long
}
