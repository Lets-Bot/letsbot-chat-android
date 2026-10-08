package net.letsbot.chat.internal

import org.json.JSONArray
import org.json.JSONObject

/** Converts a user-supplied context map into JSON, keeping only JSON-safe values. */
internal fun Map<String, Any?>.toJsonObject(): JSONObject {
    val out = JSONObject()
    for ((key, value) in this) out.put(key, toJsonValue(value))
    return out
}

private fun toJsonValue(value: Any?): Any = when (value) {
    null -> JSONObject.NULL
    is String, is Boolean, is Int, is Long, is Double -> value
    is Float -> value.toDouble()
    is Short -> value.toInt()
    is Byte -> value.toInt()
    is Number -> value.toDouble()
    is Map<*, *> -> JSONObject().also { obj ->
        for ((k, v) in value) if (k != null) obj.put(k.toString(), toJsonValue(v))
    }
    is Iterable<*> -> JSONArray().also { arr -> value.forEach { arr.put(toJsonValue(it)) } }
    is Array<*> -> JSONArray().also { arr -> value.forEach { arr.put(toJsonValue(it)) } }
    else -> value.toString()
}

internal fun sha256Hex(value: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
