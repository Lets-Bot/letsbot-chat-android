package net.letsbot.chat.internal

/** Small key/value store for the SDK's secrets (visitor token, user fingerprint, push token). */
internal interface SecureStore {
    fun get(key: String): String?

    fun put(key: String, value: String?)

    companion object {
        const val VISITOR_TOKEN = "visitor_token"
        const val USER_FINGERPRINT = "user_fp"
        const val PUSH_TOKEN = "push_token"
        const val PUSH_PROVIDER = "push_provider"
        const val PUSH_REGISTRATION = "push_reg"
    }
}

internal class InMemorySecureStore : SecureStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun get(key: String): String? = map[key]

    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}
