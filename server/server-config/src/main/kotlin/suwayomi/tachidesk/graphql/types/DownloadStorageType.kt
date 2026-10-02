package suwayomi.tachidesk.graphql.types

/**
 * Selects the storage backend used for the manga download directory.
 *
 * - [LOCAL]: downloads are written to the local filesystem (default, legacy behaviour).
 * - [WEBDAV]: downloads are written to a remote WebDAV server.
 */
enum class DownloadStorageType {
    LOCAL,
    WEBDAV,
}
