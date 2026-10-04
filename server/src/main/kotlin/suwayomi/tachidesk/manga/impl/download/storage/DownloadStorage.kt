package suwayomi.tachidesk.manga.impl.download.storage

import java.io.File
import java.io.InputStream

/**
 * Represents a file or directory entry within a [DownloadStorage] backend.
 *
 * @property path relative path of the entry (e.g. `mangas/Source/Manga/chapter.cbz`)
 * @property isDirectory whether this entry is a directory
 * @property size size of the entry in bytes (0 for directories)
 */
data class StorageFile(
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
)

/**
 * Abstraction over the "download directory" storage backend.
 *
 * Suwayomi can store downloaded chapters either as a single CBZ archive or as a folder
 * of page images. This interface abstracts both cases so the download logic can target
 * either the local filesystem or a remote WebDAV server without depending on the concrete
 * backend.
 *
 * All [path] arguments are relative to the configured download root (e.g. `mangas/...`).
 */
interface DownloadStorage {
    // ---- single file operations (CBZ mode) ----

    /** Returns true if a file or directory exists at [path]. */
    suspend fun exists(path: String): Boolean

    /** Writes [content] to [path], overwriting any existing entry. */
    suspend fun writeFile(
        path: String,
        content: InputStream,
        size: Long,
    )

    /** Returns the content of the file at [path], or null if it does not exist. */
    suspend fun readFile(path: String): InputStream?

    /** Deletes the file at [path]. Returns true when the file is gone (including if it never existed). */
    suspend fun deleteFile(path: String): Boolean

    /** Returns the size in bytes of the file at [path], or 0 if it does not exist. */
    suspend fun fileSize(path: String): Long

    // ---- directory operations (folder mode) ----

    /** Lists the immediate children of the directory at [dirPath]. */
    suspend fun listFiles(dirPath: String): List<StorageFile>

    /** Creates the directory at [dirPath] (including any missing parents). */
    suspend fun createDirectory(dirPath: String)

    /** Deletes the directory at [dirPath] recursively. Returns true when it is gone. */
    suspend fun deleteDirectory(dirPath: String): Boolean

    // ---- optional backend capabilities ----

    /**
     * Returns the local filesystem [File] backing [path] when this backend stores data on the
     * local filesystem, or `null` for remote backends.
     *
     * Callers use this to read local content in place instead of copying it through a temporary
     * file, which keeps local downloads on the same zero-copy path they had before remote
     * backends existed.
     */
    fun localPathOrNull(path: String): File? = null

    /**
     * Called after [path] has been removed. Backends may use this to clean up left-over empty
     * parent directories (the local backend does; remote backends generally cannot).
     */
    suspend fun cleanupEmptyParents(path: String) = Unit
}
