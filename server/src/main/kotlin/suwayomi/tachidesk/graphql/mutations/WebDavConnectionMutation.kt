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
        OkHttpClient
            .Builder()
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
            Request
                .Builder()
                .url(url.trimEnd('/') + "/")
                .method("PROPFIND", null)
                .header("Depth", "0")
                .apply {
                    if (username.isNotBlank() || password.isNotBlank()) {
                        header("Authorization", Credentials.basic(username, password))
                    }
                }.build()

        return try {
            probeClient.newCall(request).execute().use { response ->
                val code = response.code
                when {
                    // credentials needed, but the server itself is reachable
                    code == 401 -> {
                        TestWebDavConnectionPayload(
                            input.clientMutationId,
                            false,
                            "Server is reachable but authentication failed — check username and password (HTTP $code)",
                        )
                    }

                    code in 200..299 -> {
                        TestWebDavConnectionPayload(input.clientMutationId, true, "Connected successfully (HTTP $code)")
                    }

                    // redirects are not followed (followRedirects=false), so the request never
                    // reached a WebDAV collection — that is not a successful connection
                    code in 300..399 -> {
                        TestWebDavConnectionPayload(
                            input.clientMutationId,
                            false,
                            "Server redirected the request (HTTP $code) — make sure the URL points directly at the WebDAV root",
                        )
                    }

                    else -> {
                        TestWebDavConnectionPayload(input.clientMutationId, false, "Unexpected HTTP status: $code")
                    }
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
