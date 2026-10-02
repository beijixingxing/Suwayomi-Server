package suwayomi.tachidesk.manga.impl.download.storage

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * [DownloadStorage] backed by a remote WebDAV server.
 *
 * Implements the WebDAV protocol (MKCOL / PUT / GET / DELETE / PROPFIND / MOVE / HEAD) on top
 * of the project's existing OkHttp client, so no extra dependencies are required.
 *
 * All [DownloadStorage] paths are relative to [remoteRoot] and are URL-encoded per path segment
 * before being appended to [baseUrl].
 */
class WebDavDownloadStorage(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
    private val remoteRoot: String,
) : DownloadStorage {
    private val logger = KotlinLogging.logger {}

    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(300, TimeUnit.SECONDS)
            .build()

    private val authHeader: String = Credentials.basic(username, password)

    private fun urlFor(path: String): String {
        val normalizedRoot = remoteRoot.trim('/')
        val normalizedPath = path.trim('/')
        val fullPath = if (normalizedRoot.isEmpty()) normalizedPath else "$normalizedRoot/$normalizedPath"
        // encode each segment to keep slashes intact
        val encoded =
            fullPath
                .split('/')
                .joinToString("/") { segment ->
                    java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
                }
        return baseUrl.trimEnd('/') + "/" + encoded
    }

    private fun newRequest(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", authHeader)

    // Use OkHttp's blocking execute() (not the project's await() extension) so that
    // non-2xx responses (e.g. 404 for missing files) are returned to the caller instead
    // of throwing — the callers handle status codes themselves.
    private suspend fun execute(request: Request): Response =
        withContext(Dispatchers.IO) {
            client.newCall(request).execute()
        }

    override suspend fun exists(path: String): Boolean {
        val url = urlFor(path)
        val request = newRequest(url).method("HEAD", null).build()
        return try {
            execute(request).use { it.code in 200..299 }
        } catch (e: Exception) {
            logger.warn(e) { "WebDAV exists failed for $path" }
            false
        }
    }

    override suspend fun writeFile(
        path: String,
        content: InputStream,
        size: Long,
    ) {
        val url = urlFor(path)
        // ensure parent directories exist
        val parentPath = path.substringBeforeLast('/', missingDelimiterValue = "")
        if (parentPath.isNotEmpty()) {
            createDirectory(parentPath)
        }

        val body: RequestBody =
            object : RequestBody() {
                override fun contentType(): MediaType? = null

                override fun contentLength(): Long {
                    require(size >= 0) { "Content length must be non-negative, got: $size" }
                    return size
                }

                override fun writeTo(sink: BufferedSink) {
                    content.use { input -> sink.writeAll(input.source()) }
                }
            }
        val request = newRequest(url).put(body).build()
        execute(request).use { response ->
            if (response.code !in 200..299) {
                throw IOException("WebDAV PUT failed for $path: HTTP ${response.code}")
            }
        }
    }

    override suspend fun readFile(path: String): InputStream? {
        val url = urlFor(path)
        val request = newRequest(url).get().build()
        return try {
            val response = execute(request)
            if (response.code == 404) {
                response.close()
                return null
            }
            if (response.code !in 200..299) {
                response.close()
                throw IOException("WebDAV GET failed for $path: HTTP ${response.code}")
            }
            // Return the raw stream; the caller is responsible for closing it (which releases the connection).
            response.body?.byteStream()
        } catch (e: Exception) {
            logger.warn(e) { "WebDAV read failed for $path" }
            null
        }
    }

    override suspend fun deleteFile(path: String): Boolean {
        val url = urlFor(path)
        val request = newRequest(url).delete().build()
        return try {
            execute(request).use { it.code in 200..299 }
        } catch (e: Exception) {
            logger.warn(e) { "WebDAV delete failed for $path" }
            false
        }
    }

    override suspend fun fileSize(path: String): Long {
        val url = urlFor(path)
        val request = newRequest(url).method("HEAD", null).build()
        return try {
            execute(request).use { response ->
                if (response.code !in 200..299) return 0L
                response.header("Content-Length")?.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            logger.warn(e) { "WebDAV fileSize failed for $path" }
            0L
        }
    }

    override suspend fun listFiles(dirPath: String): List<StorageFile> {
        val url = urlFor(dirPath)
        val propfindBody = PROPFIND_REQUEST_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
        val request = newRequest(url).method("PROPFIND", propfindBody).header("Depth", "1").build()

        return try {
            execute(request).use { response ->
                if (response.code !in 200..299) return emptyList()
                val xml = response.body?.string().orEmpty()
                parsePropfind(xml, dirPath)
            }
        } catch (e: Exception) {
            logger.warn(e) { "WebDAV list failed for $dirPath" }
            emptyList()
        }
    }

    override suspend fun createDirectory(dirPath: String) {
        // mkcol only creates a single level; create each missing parent first
        val segments = dirPath.trim('/').split('/').filter { it.isNotEmpty() }
        var current = ""
        for (segment in segments) {
            current = if (current.isEmpty()) segment else "$current/$segment"
            val url = urlFor(current)
            if (exists(current)) continue
            val request = newRequest(url).method("MKCOL", null).build()
            // 405 = directory already exists (tolerated); any other non-2xx is a real error
            execute(request).use { response ->
                if (response.code !in 200..299 && response.code != 405) {
                    throw IOException("WebDAV MKCOL failed for $current: HTTP ${response.code}")
                }
            }
        }
    }

    override suspend fun deleteDirectory(dirPath: String): Boolean {
        // Recursively delete children first — WebDAV DELETE only removes empty collections
        val children = listFiles(dirPath)
        for (child in children) {
            if (child.isDirectory) {
                deleteDirectory(child.path)
            } else {
                deleteFile(child.path)
            }
        }
        val url = urlFor(dirPath)
        val request = newRequest(url).delete().build()
        return try {
            execute(request).use { it.code in 200..299 }
        } catch (e: Exception) {
            logger.warn(e) { "WebDAV deleteDirectory failed for $dirPath" }
            false
        }
    }

    override suspend fun move(
        from: String,
        to: String,
    ): Boolean {
        val fromUrl = urlFor(from)
        val toUrl = urlFor(to)
        val request =
            newRequest(fromUrl)
                .method("MOVE", null)
                .header("Destination", toUrl)
                .build()
        return try {
            execute(request).use { it.code in 200..299 }
        } catch (e: Exception) {
            logger.warn(e) { "WebDAV move failed from $from to $to" }
            false
        }
    }

    private fun parsePropfind(
        xml: String,
        dirPath: String,
    ): List<StorageFile> {
        val files = mutableListOf<StorageFile>()
        // Regex-based parsing handles both namespaced (<D:response xmlns:D="DAV:">) and
        // plain (<response>) WebDAV responses. Future improvement: switch to a DOM parser
        // (javax.xml.parsers.DocumentBuilderFactory is already available in the project).
        val responseRegex = Regex("<(?:D:)?response>(.*?)</(?:D:)?response>", RegexOption.DOT_MATCHES_ALL)
        val hrefRegex = Regex("<(?:D:)?href>(.*?)</(?:D:)?href>", RegexOption.DOT_MATCHES_ALL)
        // <D:collection/> or <D:collection xmlns:D="DAV:"/> or <collection/>
        val collectionRegex = Regex("<(?:D:)?collection[^/>]*/>")
        val contentLengthRegex =
            Regex("<(?:D:)?getcontentlength>(.*?)</(?:D:)?getcontentlength>", RegexOption.DOT_MATCHES_ALL)

        for (responseBlock in responseRegex.findAll(xml)) {
            val block = responseBlock.groupValues[1]
            val href = hrefRegex.find(block)?.groupValues?.get(1) ?: continue
            val isCollection = collectionRegex.containsMatchIn(block)
            val size = contentLengthRegex.find(block)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

            // skip the directory itself (Depth: 1 returns it as first entry)
            val decodedHref = java.net.URLDecoder.decode(href, "UTF-8")
            val relPath = relativePathFromHref(decodedHref, dirPath) ?: continue

            files.add(
                StorageFile(
                    path = relPath,
                    isDirectory = isCollection,
                    size = if (isCollection) 0L else size,
                ),
            )
        }
        return files
    }

    /**
     * Converts a PROPFIND [href] (decoded absolute URL or path) into a storage-relative
     * path, filtering out the directory itself and entries outside [dirPath].
     *
     * Returns the storage-relative path, or `null` when the entry is the directory itself
     * or lies outside [dirPath].
     */
    private fun relativePathFromHref(
        href: String,
        dirPath: String,
    ): String? {
        // href may be an absolute URL like `http://host/remoteRoot/mangas/...`
        // or a path like `/remoteRoot/mangas/...`.  Strip both variants.
        val base = baseUrl.trimEnd('/')
        val withoutBase = if (href.startsWith(base)) href.removePrefix(base).trimStart('/') else href.trimStart('/')
        val root = remoteRoot.trim('/')
        val relativeToRoot =
            if (root.isNotEmpty() && withoutBase.startsWith(root)) {
                withoutBase.removePrefix(root).trimStart('/')
            } else {
                withoutBase
            }

        val normalizedDir = dirPath.trim('/')
        val normalizedHref = relativeToRoot.trim('/')

        // skip the directory itself
        if (normalizedHref == normalizedDir) return null

        // return only children of dirPath
        if (normalizedDir.isEmpty()) return normalizedHref
        return normalizedHref.takeIf { it.startsWith("$normalizedDir/") }
    }

    companion object {
        private val PROPFIND_REQUEST_BODY =
            """
            <?xml version="1.0"?>
            <D:propfind xmlns:D="DAV:">
              <D:prop>
                <D:displayname/>
                <D:getcontentlength/>
                <D:resourcetype/>
              </D:prop>
            </D:propfind>
            """.trimIndent()
    }
}
