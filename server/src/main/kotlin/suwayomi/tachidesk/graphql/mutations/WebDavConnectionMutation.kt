package suwayomi.tachidesk.graphql.mutations

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class WebDavConnectionMutation {
    data class TestWebDavConnectionInput(
        val clientMutationId: String? = null,
        val url: String? = null,
        val username: String? = null,
        val password: String? = null,
    )

    data class TestWebDavConnectionPayload(
        val clientMutationId: String?,
        val success: Boolean,
        val message: String,
    )

    private val probeClient: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()

    fun testWebDavConnection(input: TestWebDavConnectionInput): TestWebDavConnectionPayload {
        val url =
            input.url?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("WebDAV URL is required")

        val username = input.username ?: ""
        val password = input.password ?: ""

        val request =
            Request.Builder()
                .url(url.trimEnd('/') + "/")
                .method("PROPFIND", null)
                .header("Depth", "0")
                .apply {
                    if (username.isNotBlank() || password.isNotBlank()) {
                        header("Authorization", Credentials.basic(username, password))
                    }
                }
                .build()

        return try {
            probeClient.newCall(request).execute().use { response ->
                val code = response.code
                if (code in 200..299 || code == 207 || code == 301 || code == 302 || code == 401) {
                    // 401 = credentials needed but server is reachable
                    if (code == 401) {
                        TestWebDavConnectionPayload(
                            input.clientMutationId, false,
                            "Server is reachable but authentication failed — check username and password (HTTP $code)",
                        )
                    } else {
                        TestWebDavConnectionPayload(input.clientMutationId, true, "Connected successfully (HTTP $code)")
                    }
                } else {
                    TestWebDavConnectionPayload(input.clientMutationId, false, "Unexpected HTTP status: $code")
                }
            }
        } catch (e: IOException) {
            val msg =
                when {
                    e.message?.contains("Unable to resolve host") == true -> "Host not found — check the URL"
                    e.message?.contains("ConnectException") == true ||
                        e.message?.contains("Connection refused") == true -> "Connection refused — check URL and port"
                    e.message?.contains("timeout") == true -> "Connection timed out — check network reachability"
                    e.message?.contains("SSL") == true -> "SSL/TLS error — check protocol (HTTP vs HTTPS)"
                    else -> e.message ?: e.javaClass.simpleName
                }
            TestWebDavConnectionPayload(input.clientMutationId, false, "Connection failed: $msg")
        }
    }
}