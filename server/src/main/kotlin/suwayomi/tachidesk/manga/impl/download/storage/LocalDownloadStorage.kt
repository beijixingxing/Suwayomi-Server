package suwayomi.tachidesk.manga.impl.download.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import suwayomi.tachidesk.manga.impl.util.storage.FileDeletionHelper
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * [DownloadStorage] backed by the local filesystem.
 *
 * All [DownloadStorage] paths are relative to [rootPath]. This implementation maps them
 * onto real files under [rootPath] and mirrors the behaviour of the legacy `File`-based
 * download logic.
 *
 * @param rootPath download root the storage-relative paths are resolved against
 * @param cleanupRootPath upper bound for [cleanupEmptyParents]; empty parent directories are
 *   removed up to (but excluding) this directory
 */
class LocalDownloadStorage(
    private val rootPath: String,
    private val cleanupRootPath: String = rootPath,
) : DownloadStorage {
    private fun resolve(path: String): File {
        val normalized = path.trimStart('/')
        return File(rootPath, normalized)
    }

    override fun localPathOrNull(path: String): File? = resolve(path).takeIf { it.exists() }

    override suspend fun exists(path: String): Boolean =
        withContext(Dispatchers.IO) {
            resolve(path).exists()
        }

    override suspend fun writeFile(
        path: String,
        content: InputStream,
        size: Long,
    ) {
        withContext(Dispatchers.IO) {
            val file = resolve(path)
            file.parentFile?.mkdirs()
            content.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        }
    }

    override suspend fun readFile(path: String): InputStream? =
        withContext(Dispatchers.IO) {
            val file = resolve(path)
            if (file.exists() && file.isFile) FileInputStream(file) else null
        }

    override suspend fun deleteFile(path: String): Boolean =
        withContext(Dispatchers.IO) {
            val file = resolve(path)
            // idempotent: deleting something that is not there is a success
            if (!file.exists()) true else !file.isFile || file.delete()
        }

    override suspend fun fileSize(path: String): Long =
        withContext(Dispatchers.IO) {
            val file = resolve(path)
            if (file.exists() && file.isFile) file.length() else 0L
        }

    override suspend fun listFiles(dirPath: String): List<StorageFile> =
        withContext(Dispatchers.IO) {
            val dir = resolve(dirPath)
            if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()

            dir
                .listFiles()
                .orEmpty()
                .map { file ->
                    StorageFile(
                        path = "$dirPath/${file.name}",
                        isDirectory = file.isDirectory,
                        size = if (file.isFile) file.length() else 0L,
                    )
                }
        }

    override suspend fun createDirectory(dirPath: String) {
        withContext(Dispatchers.IO) {
            resolve(dirPath).mkdirs()
        }
    }

    override suspend fun deleteDirectory(dirPath: String): Boolean =
        withContext(Dispatchers.IO) {
            val dir = resolve(dirPath)
            // idempotent: deleting something that is not there is a success
            if (!dir.exists()) true else !dir.isDirectory || dir.deleteRecursively()
        }

    override suspend fun cleanupEmptyParents(path: String) {
        withContext(Dispatchers.IO) {
            FileDeletionHelper.cleanupParentFoldersFor(resolve(path), cleanupRootPath)
        }
    }
}
