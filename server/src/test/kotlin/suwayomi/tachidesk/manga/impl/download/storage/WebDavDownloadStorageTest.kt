package suwayomi.tachidesk.manga.impl.download.storage

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebDavDownloadStorageTest {
    private val server = MockWebServer()

    @BeforeTest
    fun setup() {
        server.start()
    }

    @AfterTest
    fun cleanup() {
        server.close()
    }

    private fun newStorage(): WebDavDownloadStorage =
        WebDavDownloadStorage(
            baseUrl = server.url("/dav").toString(),
            username = "user",
            password = "pass",
            remoteRoot = "comics",
        )

    private fun respond(
        code: Int,
        body: String = "",
    ) {
        server.enqueue(
            MockResponse
                .Builder()
                .code(code)
                .body(body)
                .build(),
        )
    }

    /** Collects the requests made so far, draining the recorder. */
    private fun recordedRequests(): List<RecordedRequest> {
        val requests = mutableListOf<RecordedRequest>()
        while (true) {
            val request = server.takeRequest(100, TimeUnit.MILLISECONDS) ?: break
            requests.add(request)
        }
        return requests
    }

    private fun requestLines(requests: List<RecordedRequest>): List<String> = requests.map { "${it.method} ${it.target}" }

    @Test
    fun listFilesParsesLowercaseNamespaceResponses() =
        runBlocking {
            // Nextcloud/ownCloud style: lowercase `d:` namespace prefix
            respond(
                207,
                """
                <?xml version="1.0" encoding="utf-8"?>
                <d:multistatus xmlns:d="DAV:">
                  <d:response>
                    <d:href>/comics/mangas/</d:href>
                    <d:propstat>
                      <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                    </d:propstat>
                  </d:response>
                  <d:response>
                    <d:href>/comics/mangas/001.jpg</d:href>
                    <d:propstat>
                      <d:prop>
                        <d:resourcetype/>
                        <d:getcontentlength>1234</d:getcontentlength>
                      </d:prop>
                    </d:propstat>
                  </d:response>
                </d:multistatus>
                """.trimIndent(),
            )

            val files = newStorage().listFiles("mangas")

            assertEquals(1, files.size)
            assertEquals("mangas/001.jpg", files[0].path)
            assertFalse(files[0].isDirectory)
            assertEquals(1234L, files[0].size)
        }

    @Test
    fun listFilesParsesPlainNamespaceResponses() =
        runBlocking {
            // no namespace prefix at all
            respond(
                207,
                """
                <?xml version="1.0" encoding="utf-8"?>
                <multistatus>
                  <response>
                    <href>/comics/mangas/</href>
                    <propstat>
                      <prop><resourcetype><collection/></resourcetype></prop>
                    </propstat>
                  </response>
                  <response>
                    <href>/comics/mangas/001.jpg</href>
                    <propstat>
                      <prop>
                        <resourcetype/>
                        <getcontentlength>1234</getcontentlength>
                      </prop>
                    </propstat>
                  </response>
                </multistatus>
                """.trimIndent(),
            )

            val files = newStorage().listFiles("mangas")

            assertEquals(1, files.size)
            assertEquals("mangas/001.jpg", files[0].path)
            assertFalse(files[0].isDirectory)
            assertEquals(1234L, files[0].size)
        }

    @Test
    fun listFilesKeepsLiteralPlusInFilenames() =
        runBlocking {
            respond(
                207,
                """
                <?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:">
                  <D:response>
                    <D:href>/comics/mangas/</D:href>
                    <D:propstat>
                      <D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>
                    </D:propstat>
                  </D:response>
                  <D:response>
                    <D:href>/comics/mangas/chapter+extra.cbz</D:href>
                    <D:propstat>
                      <D:prop>
                        <D:resourcetype/>
                        <D:getcontentlength>10</D:getcontentlength>
                      </D:prop>
                    </D:propstat>
                  </D:response>
                </D:multistatus>
                """.trimIndent(),
            )

            val files = newStorage().listFiles("mangas")

            assertEquals(listOf("mangas/chapter+extra.cbz"), files.map { it.path })
        }

    @Test
    fun createDirectoryRecreatesDirectoryAfterDelete() =
        runBlocking {
            val storage = newStorage()

            // first create: exists() probe (404) + MKCOL (201)
            respond(404)
            respond(201)
            storage.createDirectory("mangas")

            // delete: listFiles (404 -> no children) + DELETE (204)
            respond(404)
            respond(204)
            assertTrue(storage.deleteDirectory("mangas"))

            // re-create after delete: must probe + MKCOL again instead of trusting the memo
            respond(404)
            respond(201)
            storage.createDirectory("mangas")

            assertEquals(
                listOf(
                    "HEAD /dav/comics/mangas",
                    "MKCOL /dav/comics/mangas",
                    "PROPFIND /dav/comics/mangas",
                    "DELETE /dav/comics/mangas",
                    "HEAD /dav/comics/mangas",
                    "MKCOL /dav/comics/mangas",
                ),
                requestLines(recordedRequests()),
            )
        }

    @Test
    fun writeFileOn409ResetsDirectoryCacheAndNextAttemptRecreates() =
        runBlocking {
            val storage = newStorage()

            // prime the directory memo: exists() probe (404) + MKCOL (201)
            respond(404)
            respond(201)
            storage.createDirectory("mangas")

            // the parent directory was deleted externally: PUT fails with 409
            respond(409)
            val payload = "x".toByteArray()
            val error =
                assertFailsWith<IOException> {
                    storage.writeFile("mangas/a.cbz", ByteArrayInputStream(payload), payload.size.toLong())
                }
            assertTrue(error.message!!.contains("409"))

            // the failed PUT reset the directory cache: the next attempt re-creates the
            // directory (probe + MKCOL) before the PUT succeeds
            respond(404)
            respond(201)
            respond(201)
            storage.writeFile("mangas/a.cbz", ByteArrayInputStream(payload), payload.size.toLong())

            assertEquals(
                listOf(
                    "HEAD /dav/comics/mangas",
                    "MKCOL /dav/comics/mangas",
                    "PUT /dav/comics/mangas/a.cbz",
                    "HEAD /dav/comics/mangas",
                    "MKCOL /dav/comics/mangas",
                    "PUT /dav/comics/mangas/a.cbz",
                ),
                requestLines(recordedRequests()),
            )
        }

    @Test
    fun writeFileUploadsContent() =
        runBlocking {
            val storage = newStorage()

            // exists() probe (404) + MKCOL (201) + PUT (201)
            respond(404)
            respond(201)
            respond(201)
            val payload = "hello".toByteArray()
            storage.writeFile("mangas/a.txt", ByteArrayInputStream(payload), payload.size.toLong())

            val requests = recordedRequests()
            assertEquals("PUT", requests.last().method)
            assertEquals("hello", requests.last().body?.utf8())
        }
}
