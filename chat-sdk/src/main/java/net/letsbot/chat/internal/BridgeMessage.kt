package net.letsbot.chat.internal

import org.json.JSONException
import org.json.JSONObject

/** A page → native event (API.md §5). Unknown events are ignored for forward compatibility. */
internal sealed class BridgeMessage {
    object Ready : BridgeMessage()
    object TokenInvalid : BridgeMessage()
    object Close : BridgeMessage()
    data class OpenUrl(val url: String) : BridgeMessage()
    data class Unread(val count: Int) : BridgeMessage()
    data class Message(val text: String) : BridgeMessage()
    data class Error(val code: String) : BridgeMessage()

    companion object {
        private const val MAX_LENGTH = 64 * 1024

        fun parse(raw: String?): BridgeMessage? {
            if (raw == null || raw.length > MAX_LENGTH) return null
            val json = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return null
            }
            return when (json.optString("lb")) {
                "ready" -> Ready
                "token_invalid" -> TokenInvalid
                "close" -> Close
                "open_url" -> json.optString("url").takeIf { it.isNotEmpty() }?.let { OpenUrl(it) }
                "unread" -> Unread(json.optInt("count", 0).coerceAtLeast(0))
                "message" -> Message(json.optString("t"))
                "error" -> Error(json.optString("code").ifEmpty { "unknown" })
                else -> null
            }
        }
    }
}
