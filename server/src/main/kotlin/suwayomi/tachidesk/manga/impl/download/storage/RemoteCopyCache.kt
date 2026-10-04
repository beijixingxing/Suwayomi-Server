package suwayomi.tachidesk.manga.impl.download.storage

import java.io.File

/**
 * Bounded cache of locally materialized copies of remote content.
 *
 * Remote backends cannot be read in place, so their content is copied into the local temp
 * directory before use. This cache keeps the most recently used copies and evicts the least
 * recently used ones — deleting their files — so a long-running server does not fill the disk.
 *
 * Entries are validated by a backend-supplied [signature] (e.g. the remote size), so changed
 * content is downloaded again. Callers must serialise access (for example with a `Mutex`).
 */
internal class RemoteCopyCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val entries = LinkedHashMap<String, Pair<File, Any>>(16, 0.75f, true)

    /** Returns the cached copy for [key] when it still exists and matches [signature]. */
    fun getIfValid(
        key: String,
        signature: Any,
    ): File? =
        entries[key]
            ?.takeIf { it.second == signature && it.first.exists() }
            ?.first

    /** Stores [file] for [key], evicting and deleting the least recently used entries. */
    fun put(
        key: String,
        file: File,
        signature: Any,
    ) {
        entries[key] = file to signature
        while (entries.size > maxEntries) {
            val iterator = entries.entries.iterator()
            if (!iterator.hasNext()) break
            val eldest = iterator.next()
            iterator.remove()
            // On POSIX systems removing a file that is still being read is safe: readers keep
            // their file descriptor. On Windows the delete simply fails and the file is retried
            // on the next eviction.
            eldest.value.first.deleteRecursively()
        }
    }

    companion object {
        private const val DEFAULT_MAX_ENTRIES = 8
    }
}
