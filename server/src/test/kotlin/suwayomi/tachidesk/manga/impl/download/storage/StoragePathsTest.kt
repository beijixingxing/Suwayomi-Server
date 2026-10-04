package suwayomi.tachidesk.manga.impl.download.storage

import kotlin.test.Test
import kotlin.test.assertEquals

class StoragePathsTest {
    @Test
    fun stripsTheDownloadRootPrefix() {
        assertEquals(
            "mangas/Source/Manga/chapter.cbz",
            StoragePaths.toStorageRelative("/comics/mangas/Source/Manga/chapter.cbz", "/comics"),
        )
    }

    @Test
    fun toleratesTrailingSlashOnTheRoot() {
        assertEquals("mangas/a.cbz", StoragePaths.toStorageRelative("/comics/mangas/a.cbz", "/comics/"))
    }

    @Test
    fun normalizesWindowsSeparators() {
        assertEquals("mangas/a.cbz", StoragePaths.toStorageRelative("\\comics\\mangas\\a.cbz", "/comics"))
    }

    @Test
    fun keepsAbsolutePathsRelativeWhenNotBelowTheDownloadRoot() {
        assertEquals(
            "home/suwayomi/downloads/mangas/a.cbz",
            StoragePaths.toStorageRelative("/home/suwayomi/downloads/mangas/a.cbz", "/comics"),
        )
    }

    @Test
    fun doesNotStripAPartiallyMatchingRoot() {
        // "/comics-old" must not be treated as being below "/comics"
        assertEquals("comics-old/mangas/a.cbz", StoragePaths.toStorageRelative("/comics-old/mangas/a.cbz", "/comics"))
    }
}
