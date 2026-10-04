package suwayomi.tachidesk.manga.impl.download.storage

import io.github.oshai.kotlinlogging.KotlinLogging
import suwayomi.tachidesk.graphql.types.DownloadStorageType
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.serverConfig
import uy.kohesive.injekt.injectLazy

/**
 * Resolves the active [DownloadStorage] backend based on the configured
 * [DownloadStorageType].
 *
 * The storage is created lazily so that WebDAV connection settings are only
 * read when a WebDAV backend is actually selected.
 */
object DownloadStorageFactory {
    private val logger = KotlinLogging.logger {}
    private val applicationDirs: ApplicationDirs by injectLazy()

    private data class WebDavConfig(
        val url: String,
        val username: String,
        val password: String,
        val remotePath: String,
    )

    @Volatile
    private var cachedWebDav: WebDavDownloadStorage? = null

    @Volatile
    private var cachedWebDavConfig: WebDavConfig? = null

    /**
     * Returns the backend downloads should be written to.
     *
     * When WebDAV is selected but not configured yet (no URL), the local backend is used instead of
     * failing: a half-finished configuration must not break every download and read on the server.
     */
    fun create(): DownloadStorage =
        when (serverConfig.downloadStorageType.value) {
            DownloadStorageType.LOCAL -> localStorage()
            DownloadStorageType.WEBDAV -> getWebDav() ?: localStorage()
        }

    /**
     * Always returns a local filesystem backend resolving paths against the effective download
     * root ([ApplicationDirs.downloadsRoot], which honours the blank-`downloadsPath` default).
     *
     * Used for LOCAL mode and as the read fallback for content left behind by a previous backend.
     */
    fun localStorage(): LocalDownloadStorage =
        LocalDownloadStorage(
            rootPath = applicationDirs.downloadsRoot,
            cleanupRootPath = applicationDirs.mangaDownloadsRoot,
        )

    private fun getWebDav(): WebDavDownloadStorage? {
        val config =
            WebDavConfig(
                url = serverConfig.webdavUrl.value,
                username = serverConfig.webdavUsername.value,
                password = serverConfig.webdavPassword.value,
                remotePath = serverConfig.webdavRemotePath.value,
            )

        cachedWebDav
            ?.takeIf { cachedWebDavConfig == config }
            ?.let { return it }

        if (config.url.isBlank()) {
            logger.warn {
                "downloadStorageType is WEBDAV but webdavUrl is not set - falling back to local storage"
            }
            return null
        }

        return WebDavDownloadStorage(
            baseUrl = config.url,
            username = config.username,
            password = config.password,
            remoteRoot = config.remotePath,
        ).also {
            cachedWebDav = it
            cachedWebDavConfig = config
        }
    }
}
