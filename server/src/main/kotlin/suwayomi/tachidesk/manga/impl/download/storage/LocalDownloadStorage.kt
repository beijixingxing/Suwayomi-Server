package suwayomi.tachidesk.manga.impl.download.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * [DownloadStorage] backed by the local filesystem.
 *
 * All [DownloadStorage] paths are relative to [rootPath]. This implementation maps them
 * onto real files under [rootPath] and mirrors the behaviour of the legacy `File`-based
 * download logic.
 */
class LocalDownloadStorage(
    private val rootPath: String,
) : DownloadStorage {
    private fun resolve(path: String): File {
        val normalized = path.trimStart('/')
        return File(rootPath, normalized)
    }

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
            if (file.exists() && file.isFile) file.delete() else false
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

            dir.listFiles()
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
            if (!dir.exists() || !dir.isDirectory) return@withContext false
            dir.deleteRecursively()
        }

    override suspend fun move(
        from: String,
        to: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val source = resolve(from)
            val target = resolve(to)
            if (!source.exists()) return@withContext false
            target.parentFile?.mkdirs()
            source.renameTo(target)
        }
}
