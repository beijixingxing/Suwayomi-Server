package suwayomi.tachidesk.manga.impl.download.storage

import suwayomi.tachidesk.server.ApplicationDirs

/**
 * Converts between absolute local download paths and storage-relative paths.
 *
 * The file providers compute absolute paths via [ApplicationDirs] (e.g.
 * `/comics/mangas/Source/Manga/chapter.cbz`). The [DownloadStorage] interface works with
 * paths relative to the download root (e.g. `mangas/Source/Manga/chapter.cbz`). This helper
 * performs that conversion.
 */
object StoragePaths {
    /**
     * Converts an absolute local download path into a path relative to the download root.
     * If [absolutePath] is already relative, it is returned unchanged.
     */
    fun toStorageRelative(
        absolutePath: String,
        downloadsRoot: String,
    ): String {
        val root = downloadsRoot.trimEnd('/')
        val normalized = absolutePath.replace('\\', '/')
        val prefix = "$root/"
        return if (normalized.startsWith(prefix)) {
            normalized.removePrefix(prefix)
        } else {
            normalized.trimStart('/')
        }
    }
}
