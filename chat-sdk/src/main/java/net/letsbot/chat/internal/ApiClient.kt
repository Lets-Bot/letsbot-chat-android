package net.letsbot.chat.internal

import net.letsbot.chat.LetsBotErrorCode
import net.letsbot.chat.LetsBotException
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Static identity of the app + SDK sent with every request (API.md §2). */
internal data class ClientInfo(
    val appId: String,
    val appVersion: String?,
    val osVersion: String,
    val sdk: String,
    val platform: String = "android",
)

internal class ApiResponse(val status: Int, val body: JSONObject?)

/**
 * Minimal blocking JSON client for `api/sdk/v1/{appKey}` built on [HttpURLConnection].
 *
 * HttpURLConnection is part of the platform, so the SDK adds no networking dependency to the host app.
 * Call from a background thread only.
 */
internal class ApiClient(
    baseUrl: String,
    appKey: String,
    private val client: ClientInfo,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000,
) {
    val apiBase: String = "${normalizeBaseUrl(baseUrl)}/api/sdk/v1/${urlEncode(appKey)}"

    fun url(route: String, query: Map<String, String> = emptyMap()): String {
        val q = if (query.isEmpty()) "" else query.entries.joinToString("&", prefix = "?") {
            "${urlEncode(it.key)}=${urlEncode(it.value)}"
        }
        return "$apiBase/$route$q"
    }

    @Throws(LetsBotException::class)
    fun request(
        method: String,
        route: String,
        visitorToken: String? = null,
        body: JSONObject? = null,
        query: Map<String, String> = emptyMap(),
    ): ApiResponse {
        val connection = try {
            (URL(url(route, query)).openConnection() as HttpURLConnection)
        } catch (e: IOException) {
            throw LetsBotException(LetsBotErrorCode.NETWORK, "Could not open connection", cause = e)
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-LB-App-Id", client.appId)
            connection.setRequestProperty("X-LB-Platform", client.platform)
            connection.setRequestProperty("X-LB-SDK", client.sdk)
            if (visitorToken != null) connection.setRequestProperty("X-LB-Visitor", visitorToken)
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                // No streaming mode: bodies are tiny, and buffering keeps 401 error bodies readable on every JVM.
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val text = readBody(if (status >= 400) connection.errorStream else connection.inputStream)
            if (status in 200..299) {
                return ApiResponse(status, parseObject(text, strict = false))
            }
            throw errorFor(status, text, connection.getHeaderField("Retry-After"))
        } catch (e: LetsBotException) {
            throw e
        } catch (e: IOException) {
            throw LetsBotException(LetsBotErrorCode.NETWORK, "Network request failed", cause = e)
        } finally {
            connection.disconnect()
        }
    }

    internal companion object {
        fun normalizeBaseUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/')

        fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        private fun readBody(stream: InputStream?): String =
            stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""

        private fun parseObject(text: String, strict: Boolean): JSONObject? {
            if (text.isBlank()) return null
            return try {
                JSONObject(text)
            } catch (e: JSONException) {
                if (strict) throw LetsBotException(LetsBotErrorCode.INVALID_RESPONSE, cause = e) else null
            }
        }

        /** Maps an HTTP error response to a typed exception (API.md §3). */
        fun errorFor(status: Int, text: String, retryAfter: String?): LetsBotException {
            val raw = try {
                parseObject(text, strict = false)?.optString("error")?.takeIf { it.isNotEmpty() }
            } catch (_: LetsBotException) {
                null
            }
            val code = when {
                raw != null -> LetsBotErrorCode.fromWire(raw)
                status >= 500 -> LetsBotErrorCode.SERVER
                status == 404 -> LetsBotErrorCode.NOT_FOUND
                status == 429 -> LetsBotErrorCode.SLOW_DOWN
                else -> LetsBotErrorCode.UNKNOWN
            }
            return LetsBotException(
                code = code,
                message = "HTTP $status ${raw ?: code.wireValue}",
                httpStatus = status,
                retryAfterSeconds = retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 },
                rawCode = raw ?: code.wireValue,
            )
        }
    }
}
