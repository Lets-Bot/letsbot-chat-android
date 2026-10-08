package net.letsbot.chat.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.letsbot.chat.LetsBotErrorCode
import net.letsbot.chat.LetsBotException
import net.letsbot.chat.LetsBotTheme
import net.letsbot.chat.PushProvider
import org.json.JSONObject
import java.net.URI

/**
 * Session, identity, push and unread logic for one configured App Key. Platform-free so it runs in JVM unit tests.
 */
internal class ChatCore(
    val appKey: String,
    val baseUrl: String,
    val client: ClientInfo,
    private val api: ApiClient,
    private val store: SecureStore,
    val events: EventHub,
    val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    locale: String?,
    theme: LetsBotTheme,
    val color: String?,
    private val deviceLocale: () -> String,
) {
    private val sessionMutex = Mutex()

    @Volatile
    var locale: String? = locale?.trim()?.takeIf { it.isNotEmpty() }

    @Volatile
    var theme: LetsBotTheme = theme

    @Volatile
    private var context: Map<String, Any?> = emptyMap()

    val effectiveLocale: String get() = locale ?: deviceLocale()

    private val uiPath: String = URI(api.apiBase).rawPath + "/ui"

    val urlPolicy: UrlPolicy = UrlPolicy(baseUrl, uiPath)

    /** URL of the hosted chat screen for the given resolved theme (`light` / `dark` / `auto`). */
    fun uiUrl(resolvedTheme: String): String =
        api.url("ui", linkedMapOf("l" to effectiveLocale, "theme" to resolvedTheme, "p" to client.platform))

    // region Session

    /** Returns the stored visitor token, creating a session first when there is none. */
    suspend fun visitorToken(): String {
        var created = false
        val token = sessionMutex.withLock {
            store.get(SecureStore.VISITOR_TOKEN) ?: createSession().also { created = true }
        }
        if (created) registerStoredPushQuietly(token)
        return token
    }

    /** Discards [staleToken] (unless another caller already replaced it) and starts a new session. */
    suspend fun renewSession(staleToken: String?): String {
        var created = false
        val token = sessionMutex.withLock {
            val current = store.get(SecureStore.VISITOR_TOKEN)
            if (current != null && current != staleToken) {
                current
            } else {
                store.put(SecureStore.VISITOR_TOKEN, null)
                createSession().also { created = true }
            }
        }
        if (created) registerStoredPushQuietly(token)
        return token
    }

    private suspend fun createSession(): String = withContext(io) {
        val body = JSONObject()
            .put("locale", effectiveLocale)
            .put("device", deviceJson())
        val ctx = context
        if (ctx.isNotEmpty()) body.put("ctx", ctx.toJsonObject())
        val response = api.request("POST", "session", body = body)
        val token = response.body?.optString("token").orEmpty()
        if (token.isEmpty()) {
            throw LetsBotException(LetsBotErrorCode.INVALID_RESPONSE, "session: missing token", response.status)
        }
        store.put(SecureStore.VISITOR_TOKEN, token)
        store.put(SecureStore.PUSH_REGISTRATION, null)
        token
    }

    /** Runs [call] with a visitor token, renewing the session once on `invalid_visitor`. */
    private suspend fun <T> authed(call: (String) -> T): T {
        val token = visitorToken()
        return try {
            withContext(io) { call(token) }
        } catch (e: LetsBotException) {
            if (e.code != LetsBotErrorCode.INVALID_VISITOR) throw e
            val fresh = renewSession(token)
            withContext(io) { call(fresh) }
        }
    }

    private fun deviceJson(): JSONObject = JSONObject()
        .put("platform", client.platform)
        .put("app_id", client.appId)
        .put("app_version", client.appVersion ?: JSONObject.NULL)
        .put("sdk", client.sdk)
        .put("os_version", client.osVersion)

    // endregion

    // region Identity

    suspend fun identify(userId: String, identityToken: String, name: String?, email: String?, phone: String?) {
        if (userId.isBlank() || identityToken.isBlank()) {
            throw LetsBotException(LetsBotErrorCode.INVALID, "userId and identityToken must not be blank")
        }
        val fingerprint = sha256Hex("$appKey|$userId")
        val previous = store.get(SecureStore.USER_FINGERPRINT)
        if (previous != null && previous != fingerprint) {
            // A different user on this device: never let them continue the previous user's conversation.
            resetLocal(notifyServer = true)
        }
        val body = JSONObject().put("identity_token", identityToken)
        name?.takeIf { it.isNotBlank() }?.let { body.put("name", it) }
        email?.takeIf { it.isNotBlank() }?.let { body.put("email", it) }
        phone?.takeIf { it.isNotBlank() }?.let { body.put("phone", it) }
        authed { api.request("POST", "identify", it, body) }
        val changed = previous != fingerprint
        store.put(SecureStore.USER_FINGERPRINT, fingerprint)
        if (changed) events.forEachSurface { it.reload() }
    }

    /** Forgets the visitor locally right away; tells the server in the background. */
    fun logout() {
        resetLocal(notifyServer = true)
        events.forEachSurface { it.reload() }
    }

    private fun resetLocal(notifyServer: Boolean) {
        val old = store.get(SecureStore.VISITOR_TOKEN)
        store.put(SecureStore.VISITOR_TOKEN, null)
        store.put(SecureStore.USER_FINGERPRINT, null)
        store.put(SecureStore.PUSH_REGISTRATION, null)
        events.setUnread(0)
        if (notifyServer && old != null) {
            scope.launch(io) {
                try {
                    api.request("POST", "logout", old)
                } catch (_: LetsBotException) {
                    // Best effort: the old token is already gone from this device.
                }
            }
        }
    }

    // endregion

    // region Push

    suspend fun setPushToken(token: String, provider: PushProvider) {
        if (token.isBlank()) throw LetsBotException(LetsBotErrorCode.INVALID, "push token must not be blank")
        store.put(SecureStore.PUSH_TOKEN, token)
        store.put(SecureStore.PUSH_PROVIDER, provider.wireValue)
        authed { registerDeviceBlocking(it) }
    }

    /** Re-sends the stored push token when something it depends on (session, locale) changed. */
    suspend fun syncPushQuietly() {
        if (store.get(SecureStore.PUSH_TOKEN) == null || store.get(SecureStore.VISITOR_TOKEN) == null) return
        try {
            authed { registerDeviceBlocking(it) }
        } catch (e: LetsBotException) {
            if (e.code != LetsBotErrorCode.NETWORK) events.error(e)
        }
    }

    private suspend fun registerStoredPushQuietly(visitor: String) {
        if (store.get(SecureStore.PUSH_TOKEN) == null) return
        try {
            withContext(io) { registerDeviceBlocking(visitor) }
        } catch (e: LetsBotException) {
            if (e.code != LetsBotErrorCode.NETWORK) events.error(e)
        }
    }

    private fun registerDeviceBlocking(visitor: String) {
        val push = store.get(SecureStore.PUSH_TOKEN) ?: return
        val provider = store.get(SecureStore.PUSH_PROVIDER) ?: PushProvider.FCM.wireValue
        val locale = effectiveLocale
        val fingerprint = sha256Hex(listOf(visitor, push, provider, locale, client.appVersion.orEmpty()).joinToString("|"))
        if (store.get(SecureStore.PUSH_REGISTRATION) == fingerprint) return
        val body = JSONObject()
            .put("provider", provider)
            .put("token", push)
            .put("platform", client.platform)
            .put("app_id", client.appId)
            .put("app_version", client.appVersion ?: JSONObject.NULL)
            .put("sdk", client.sdk)
            .put("locale", locale)
            .put("sandbox", false)
        api.request("PUT", "device", visitor, body)
        store.put(SecureStore.PUSH_REGISTRATION, fingerprint)
    }

    // endregion

    // region Unread & context

    /**
     * Fetches the unread count. Does not create a session: without one there is nothing unread.
     * While the chat is visible the hosted page owns the count.
     */
    suspend fun refreshUnread(): Int {
        val token = store.get(SecureStore.VISITOR_TOKEN)
        if (token == null) {
            events.setUnread(0)
            return 0
        }
        if (events.isChatVisible) return events.unreadCount.value
        val response = try {
            withContext(io) { api.request("GET", "unread", token) }
        } catch (e: LetsBotException) {
            if (e.code == LetsBotErrorCode.INVALID_VISITOR) {
                sessionMutex.withLock {
                    if (store.get(SecureStore.VISITOR_TOKEN) == token) store.put(SecureStore.VISITOR_TOKEN, null)
                }
                events.setUnread(0)
                return 0
            }
            throw e
        }
        val count = response.body?.optInt("count", 0)?.coerceAtLeast(0) ?: 0
        events.setUnread(count)
        return count
    }

    fun setContext(values: Map<String, Any?>) {
        context = LinkedHashMap(values)
        val json = values.toJsonObject().toString()
        events.forEachSurface { it.pushContext(json) }
    }

    fun contextJson(): JSONObject = context.toJsonObject()

    /** Payload for `window.LetsBotHost.boot(...)` (API.md §5). */
    fun bootPayload(token: String, resolvedTheme: String): JSONObject {
        val payload = JSONObject()
            .put("token", token)
            .put("appId", client.appId)
            .put("platform", client.platform)
            .put("sdk", client.sdk)
            .put("context", contextJson())
            .put("locale", effectiveLocale)
            .put("theme", resolvedTheme)
        color?.let { payload.put("color", it) }
        return payload
    }

    // endregion
}
