package suwayomi.tachidesk.manga.impl.download.storage

import suwayomi.tachidesk.graphql.types.DownloadStorageType
import suwayomi.tachidesk.server.serverConfig

/**
 * Resolves the active [DownloadStorage] backend based on the configured
 * [DownloadStorageType].
 *
 * The storage is created lazily so that WebDAV connection settings are only
 * read when a WebDAV backend is actually selected.
 */
object DownloadStorageFactory {
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

    fun create(): DownloadStorage =
        when (serverConfig.downloadStorageType.value) {
            DownloadStorageType.LOCAL -> LocalDownloadStorage(serverConfig.downloadsPath.value)
            DownloadStorageType.WEBDAV -> getWebDav()
        }

    private fun getWebDav(): WebDavDownloadStorage {
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

        require(config.url.isNotBlank()) { "webdavUrl must be set when downloadStorageType is WEBDAV" }

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
