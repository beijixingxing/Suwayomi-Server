package suwayomi.tachidesk.manga.impl

import kotlinx.coroutines.CoroutineScope
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.impl.chapter.getChapterDownloadReady
import suwayomi.tachidesk.manga.impl.download.fileProvider.ChaptersFilesProvider
import suwayomi.tachidesk.manga.impl.download.fileProvider.impl.ArchiveProvider
import suwayomi.tachidesk.manga.impl.download.fileProvider.impl.FolderProvider
import suwayomi.tachidesk.manga.impl.download.model.DownloadQueueItem
import suwayomi.tachidesk.manga.impl.download.storage.DownloadStorageFactory
import suwayomi.tachidesk.manga.impl.download.storage.LocalDownloadStorage
import suwayomi.tachidesk.manga.impl.download.storage.StoragePaths
import suwayomi.tachidesk.manga.impl.util.getChapterCbzPath
import suwayomi.tachidesk.manga.impl.util.getChapterDownloadPath
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.serverConfig
import uy.kohesive.injekt.injectLazy
import xyz.nulldev.androidcompat.util.SafePath
import java.io.InputStream

object ChapterDownloadHelper {
    private val applicationDirs: ApplicationDirs by injectLazy()

    suspend fun getImage(
        mangaId: Int,
        chapterId: Int,
        index: Int,
    ): Pair<InputStream, String> = provider(mangaId, chapterId).getImage().execute(index)

    suspend fun getImageCount(
        mangaId: Int,
        chapterId: Int,
    ): Int = provider(mangaId, chapterId).getImageCount()

    suspend fun delete(
        mangaId: Int,
        chapterId: Int,
    ): Boolean = provider(mangaId, chapterId).delete()

    /**
     * This function should never be called without calling [getChapterDownloadReady] beforehand.
     */
    suspend fun download(
        mangaId: Int,
        chapterId: Int,
        download: DownloadQueueItem,
        scope: CoroutineScope,
        step: suspend (DownloadQueueItem?, Boolean) -> Unit,
    ): Boolean = provider(mangaId, chapterId).download().execute(download, scope, step)

    // return the appropriate provider based on how the download was saved
    private suspend fun provider(
        mangaId: Int,
        chapterId: Int,
    ): ChaptersFilesProvider<*> {
        val storage = DownloadStorageFactory.create()

        val cbzPath = StoragePaths.toStorageRelative(getChapterCbzPath(mangaId, chapterId), applicationDirs.downloadsRoot)
        val folderPath = StoragePaths.toStorageRelative(getChapterDownloadPath(mangaId, chapterId), applicationDirs.downloadsRoot)

        // 1) content stored by the active backend
        if (storage.exists(cbzPath)) return ArchiveProvider(mangaId, chapterId, storage, storage)
        if (storage.exists(folderPath)) return FolderProvider(mangaId, chapterId, storage, storage)

        // 2) content left behind by a previously selected backend — switching the storage type must
        //    not make already downloaded chapters unreadable. Reads come from the local files, while
        //    writes (re-downloads) keep targeting the active backend.
        if (storage !is LocalDownloadStorage) {
            val local = DownloadStorageFactory.localStorage()
            if (local.exists(cbzPath)) return ArchiveProvider(mangaId, chapterId, storage, local)
            if (local.exists(folderPath)) return FolderProvider(mangaId, chapterId, storage, local)
        }

        // 3) nothing downloaded yet — pick the provider matching the configured target format
        return if (serverConfig.downloadAsCbz.value) {
            ArchiveProvider(mangaId, chapterId, storage, storage)
        } else {
            FolderProvider(mangaId, chapterId, storage, storage)
        }
    }

    suspend fun getArchiveStreamWithSize(
        mangaId: Int,
        chapterId: Int,
    ): Pair<InputStream, Long> = provider(mangaId, chapterId).getAsArchiveStream()

    suspend fun getChapterArchiveSize(
        mangaId: Int,
        chapterId: Int,
    ): Long = provider(mangaId, chapterId).getArchiveSize()

    private fun getChapterWithCbzFileName(chapterId: Int): Pair<ChapterDataClass, String> =
        transaction {
            val row =
                (ChapterTable innerJoin MangaTable)
                    .select(ChapterTable.columns + MangaTable.columns)
                    .where { ChapterTable.id eq chapterId }
                    .firstOrNull() ?: throw IllegalArgumentException("ChapterId $chapterId not found")

            val chapter = ChapterTable.toDataClass(row)
            val mangaTitle = row[MangaTable.title].trim()

            val scanlatorName = chapter.scanlator?.trim()?.takeIf { it.isNotEmpty() }
            val chapterName = chapter.name.trim().takeIf { it.isNotEmpty() }

            val fileName =
                buildString {
                    append(mangaTitle)
                    append(" - ")

                    if (chapterName != null) {
                        append(chapterName)
                    } else if (chapter.chapterNumber >= 0f) {
                        // chapterNumber is stored as Float, drop .0 for whole numbers.
                        val formatNumber =
                            if (chapter.chapterNumber % 1 == 0f) {
                                chapter.chapterNumber.toInt().toString()
                            } else {
                                chapter.chapterNumber.toString()
                            }
                        append("#$formatNumber")
                    } else {
                        // Fallback when neither name nor valid chapter number exists
                        append("#${chapter.index}")
                    }

                    if (scanlatorName != null) {
                        append(" [")
                        append(scanlatorName)
                        append("]")
                    }
                    append(".cbz")
                }

            // Sanitize filename for OS compatibility
            val safeFileName = SafePath.buildValidFilename(fileName)

            Pair(chapter, safeFileName)
        }

    suspend fun getCbzForDownload(
        userId: Int,
        chapterId: Int,
        markAsRead: Boolean?,
    ): Triple<InputStream, String, Long> {
        val (chapterData, fileName) = getChapterWithCbzFileName(chapterId)

        val cbzFile = provider(chapterData.mangaId, chapterData.id).getAsArchiveStream()

        if (markAsRead == true) {
            Chapter.modifyChapter(
                userId = userId,
                chapterData.mangaId,
                chapterData.index,
                isRead = true,
                isBookmarked = null,
                markPrevRead = null,
                lastPageRead = null,
            )
        }

        return Triple(cbzFile.first, fileName, cbzFile.second)
    }

    suspend fun getCbzMetadataForDownload(chapterId: Int): Pair<String, Long> { // fileName, fileSize
        val (chapterData, fileName) = getChapterWithCbzFileName(chapterId)

        val fileSize = provider(chapterData.mangaId, chapterData.id).getArchiveSize()

        return Pair(fileName, fileSize)
    }
}
