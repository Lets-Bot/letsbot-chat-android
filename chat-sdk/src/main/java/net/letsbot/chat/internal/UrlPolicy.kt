package net.letsbot.chat.internal

import java.net.URI

/** Navigation and bridge-origin rules for the hosted chat WebView (RULES.md §9, API.md §5). */
internal class UrlPolicy(baseUrl: String, private val uiPath: String) {
    private val origin: Origin = requireNotNull(Origin.of(ApiClient.normalizeBaseUrl(baseUrl))) {
        "Invalid LetsBot baseUrl"
    }

    /** True when [url] is on the same scheme/host/port as the configured base URL. */
    fun isTrustedOrigin(url: String?): Boolean = url != null && Origin.of(url) == origin

    /** True only for the hosted `ui` page itself (any query string). */
    fun isChatUi(url: String?): Boolean {
        if (!isTrustedOrigin(url)) return false
        val path = parse(url)?.rawPath ?: return false
        return path.trimEnd('/') == uiPath.trimEnd('/')
    }

    internal data class Origin(val scheme: String, val host: String, val port: Int) {
        companion object {
            fun of(url: String): Origin? {
                val uri = parse(url) ?: return null
                val scheme = uri.scheme?.lowercase() ?: return null
                val host = uri.host?.lowercase() ?: return null
                if (scheme != "https" && scheme != "http") return null
                val port = when {
                    uri.port != -1 -> uri.port
                    scheme == "https" -> 443
                    else -> 80
                }
                return Origin(scheme, host, port)
            }
        }
    }

    companion object {
        fun parse(url: String?): URI? = try {
            url?.let { URI(it) }
        } catch (_: Exception) {
            null
        }

        /** Only http(s) links may be handed to the system browser. */
        fun isExternalWebUrl(url: String?): Boolean {
            val uri = parse(url) ?: return false
            val scheme = uri.scheme?.lowercase()
            return (scheme == "https" || scheme == "http") && !uri.host.isNullOrEmpty()
        }
    }
}
