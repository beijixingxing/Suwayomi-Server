package suwayomi.tachidesk.manga.impl.download.storage

import io.github.oshai.kotlinlogging.KotlinLogging
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
    private val logger = KotlinLogging.logger {}

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
        if (normalized.startsWith(prefix)) {
            return normalized.removePrefix(prefix)
        }
        // Not below the download root — fall back to a root-relative path, but make the
        // misconfiguration visible rather than silently leaking an absolute host path.
        logger.warn {
            "Download path '$absolutePath' is not inside the download root '$root'; " +
                "using a root-relative path instead"
        }
        return normalized.trimStart('/')
    }
}
