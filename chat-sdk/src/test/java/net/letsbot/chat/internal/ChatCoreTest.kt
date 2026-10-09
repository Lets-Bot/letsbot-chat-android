package net.letsbot.chat.internal

import kotlinx.coroutines.runBlocking
import net.letsbot.chat.LetsBotErrorCode
import net.letsbot.chat.LetsBotException
import net.letsbot.chat.LetsBotListener
import net.letsbot.chat.PushProvider
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ChatCoreTest {
    private lateinit var server: MockWebServer
    private lateinit var store: InMemorySecureStore
    private lateinit var events: EventHub
    private lateinit var core: ChatCore

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        store = InMemorySecureStore()
        events = EventHub { it() }
        core = newCore(server, store, events)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `creates a session once, sends device info and context, and stores the token`() = runBlocking {
        server.enqueue(json(200, """{"token":"$TOKEN_A"}"""))
        core.setContext(mapOf("screen" to "order_details", "order_id" to 1234))

        assertEquals(TOKEN_A, core.visitorToken())
        assertEquals(TOKEN_A, core.visitorToken())

        assertEquals(1, server.requestCount)
        val request = server.next()
        assertEquals("/api/sdk/v1/$APP_KEY/session", request.path)
        assertNull(request.getHeader("X-LB-Visitor"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("ar", body.getString("locale"))
        val device = body.getJSONObject("device")
        assertEquals("android", device.getString("platform"))
        assertEquals("com.acme.app", device.getString("app_id"))
        assertEquals("2.3.0", device.getString("app_version"))
        assertEquals("android/0.2.0", device.getString("sdk"))
        assertEquals("order_details", body.getJSONObject("ctx").getString("screen"))
        assertEquals(1234, body.getJSONObject("ctx").getInt("order_id"))
        assertEquals(TOKEN_A, store.get(SecureStore.VISITOR_TOKEN))
    }

    @Test
    fun `session without a token is an invalid response`() = runBlocking {
        server.enqueue(json(200, """{}"""))

        assertEquals(LetsBotErrorCode.INVALID_RESPONSE, expectError { core.visitorToken() }.code)
        assertNull(store.get(SecureStore.VISITOR_TOKEN))
    }

    @Test
    fun `identify posts the identity token with the visitor header`() = runBlocking {
        store.put(SecureStore.VISITOR_TOKEN, TOKEN_A)
        server.enqueue(json(200, """{"verified":true}"""))

        core.identify("user-1", "jwt.value.sig", "Sara", "sara@x.com", null)

        val request = server.next()
        assertEquals("/api/sdk/v1/$APP_KEY/identify", request.path)
        assertEquals(TOKEN_A, request.getHeader("X-LB-Visitor"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("jwt.value.sig", body.getString("identity_token"))
        assertEquals("Sara", body.getString("name"))
        assertEquals("sara@x.com", body.getString("email"))
        assertFalse(body.has("phone"))
        assertTrue(store.get(SecureStore.USER_FINGERPRINT) != null)
    }

    @Test
    fun `invalid_visitor renews the session and retries once`() = runBlocking {
        store.put(SecureStore.VISITOR_TOKEN, TOKEN_A)
        server.enqueue(json(401, """{"error":"invalid_visitor"}"""))
        server.enqueue(json(200, """{"token":"$TOKEN_B"}"""))
        server.enqueue(json(200, """{"verified":true}"""))

        core.identify("user-1", "jwt", null, null, null)

        assertEquals(TOKEN_A, server.next().getHeader("X-LB-Visitor"))
        assertEquals("/api/sdk/v1/$APP_KEY/session", server.next().path)
        val retry = server.next()
        assertEquals("/api/sdk/v1/$APP_KEY/identify", retry.path)
        assertEquals(TOKEN_B, retry.getHeader("X-LB-Visitor"))
        assertEquals(TOKEN_B, store.get(SecureStore.VISITOR_TOKEN))
    }

    @Test
    fun `identity_expired is surfaced to the caller`() = runBlocking {
        store.put(SecureStore.VISITOR_TOKEN, TOKEN_A)
        server.enqueue(json(401, """{"error":"identity_expired"}"""))

        assertEquals(LetsBotErrorCode.IDENTITY_EXPIRED, expectError { core.identify("u", "jwt", null, null, null) }.code)
        assertNull(store.get(SecureStore.USER_FINGERPRINT))
    }

    @Test
    fun `blank identity input is rejected without a request`() = runBlocking {
        assertEquals(LetsBotErrorCode.INVALID, expectError { core.identify(" ", "jwt", null, null, null) }.code)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a different user on the same device starts a fresh session`() = runBlocking {
        store.put(SecureStore.VISITOR_TOKEN, TOKEN_A)
        server.enqueue(json(200, """{"verified":true}"""))
        core.identify("user-1", "jwt-1", null, null, null)
        server.next()

        server.routes(
            "logout" to json(200, """{"ok":true}"""),
            "session" to json(200, """{"token":"$TOKEN_B"}"""),
            "identify" to json(200, """{"verified":true}"""),
        )
        core.identify("user-2", "jwt-2", null, null, null)

        val paths = (1..3).map { server.next() }.associateBy { it.path!!.substringAfterLast('/') }
        assertEquals(TOKEN_A, paths.getValue("logout").getHeader("X-LB-Visitor"))
        assertEquals(TOKEN_B, paths.getValue("identify").getHeader("X-LB-Visitor"))
        assertEquals(TOKEN_B, store.get(SecureStore.VISITOR_TOKEN))
    }

    @Test
    fun `logout forgets the visitor immediately and notifies the server`() {
        store.put(SecureStore.VISITOR_TOKEN, TOKEN_A)
        store.put(SecureStore.USER_FINGERPRINT, "fp")
        events.setUnread(3)
        server.enqueue(json(200, """{"ok":true}"""))

        core.logout()

        assertNull(store.get(SecureStore.VISITOR_TOKEN))
        assertNull(store.get(SecureStore.USER_FINGERPRINT))
        assertEquals(0, events.unreadCount.value)
        val request = server.next()
        assertEquals("/api/sdk/v1/$APP_KEY/logout", request.path)
        assertEquals("POST", request.method)
        assertEquals(TOKEN_A, request.getHeader("X-LB-Visitor"))
    }

    @Test
    fun `logout without a session makes no request`() {
        core.logout()

        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `setPushToken registers the device once per visitor and token`() = runBlocking {
        server.enqueue(json(200, """{"token":"$TOKEN_A"}"""))
        server.enqueue(json(200, """{"ok":true}"""))

        core.setPushToken("fcm-token-1", PushProvider.FCM)
        core.setPushToken("fcm-token-1", PushProvider.FCM)

        assertEquals("/api/sdk/v1/$APP_KEY/session", server.next().path)
        val device = server.next()
        assertEquals("PUT", device.method)
        assertEquals("/api/sdk/v1/$APP_KEY/device", device.path)
        assertEquals(TOKEN_A, device.getHeader("X-LB-Visitor"))
        val body = JSONObject(device.body.readUtf8())
        assertEquals("fcm", body.getString("provider"))
        assertEquals("fcm-token-1", body.getString("token"))
        assertEquals("android", body.getString("platform"))
        assertEquals("com.acme.app", body.getString("app_id"))
        assertEquals("2.3.0", body.getString("app_version"))
        assertEquals("android/0.2.0", body.getString("sdk"))
        assertEquals("ar", body.getString("locale"))
        assertFalse(body.getBoolean("sandbox"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `push token is re-registered on the next session after logout`() = runBlocking {
        store.put(SecureStore.VISITOR_TOKEN, TOKEN_A)
        server.enqueue(json(200, """{"ok":true}"""))
        core.setPushToken("fcm-token-1", PushProvider.FCM)
        server.next()

        server.enqueue(json(200, """{"ok":true}"""))
        core.logout()
        assertEquals("/api/sdk/v1/$APP_KEY/logout", server.next().path)

        server.enqueue(json(200, """{"token":"$TOKEN_B"}"""))
        server.enqueue(json(200, """{"ok":true}"""))
        assertEquals(TOKEN_B, core.visitorToken())
        assertEquals("/api/sdk/v1/$APP_KEY/session", server.next().path)
        val device = server.next()
        assertEquals("/api/sdk/v1/$APP_KEY/device", device.path)
        assertEquals(TOKEN_B, device.getHeader("X-LB-Visitor"))
    }

    @Test
    fun `unread without a session is zero and makes no request`() = runBlocking {
        assertEquals(0, core.refreshUnread())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `unread updates the state flow and listeners`() = runBlocking {
        store.put(SecureStore.VISITOR_TOKEN, TOKEN_A)
        val seen = mutableListOf<Int>()
        events.addListener(object : LetsBotListener {
            override fun onUnreadChanged(count: Int) {
                seen += count
            }
        })
        val subscription = events.addUnreadListener { seen += it * 100 }
        server.enqueue(json(200, """{"count":2,"last":{"t":"Your order shipped","at":"2026-10-08T12:00:00+03:00"}}"""))

        assertEquals(2, core.refreshUnread())

        assertEquals(2, events.unreadCount.value)
        assertEquals(listOf(0, 2, 200), seen)
        val request = server.next()
        assertEquals("GET", request.method)
        assertEquals("/api/sdk/v1/$APP_KEY/unread", request.path)
        subscription.cancel()
    }

    @Test
    fun `unread with an invalid visitor drops the token without creating a session`() = runBlocking {
        store.put(SecureStore.VISITOR_TOKEN, TOKEN_A)
        server.enqueue(json(401, """{"error":"invalid_visitor"}"""))

        assertEquals(0, core.refreshUnread())

        assertNull(store.get(SecureStore.VISITOR_TOKEN))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `ui url and boot payload follow the contract`() {
        val colored = newCore(server, store, events, locale = null, color = "#0e7c66")

        assertEquals(
            "${server.baseUrl()}/api/sdk/v1/$APP_KEY/ui?l=en&theme=dark&p=android",
            colored.uiUrl("dark"),
        )
        assertTrue(colored.urlPolicy.isChatUi(colored.uiUrl("light")))
        colored.setContext(mapOf("screen" to "help"))
        val boot = colored.bootPayload(TOKEN_A, "light")
        assertEquals(TOKEN_A, boot.getString("token"))
        assertEquals("com.acme.app", boot.getString("appId"))
        assertEquals("android", boot.getString("platform"))
        assertEquals("android/0.2.0", boot.getString("sdk"))
        assertEquals("help", boot.getJSONObject("context").getString("screen"))
        assertEquals("#0e7c66", boot.getString("color"))
        assertFalse(boot.has("insets"))
        val withInsets = colored.bootPayload(TOKEN_A, "light", SafeInsets(84, 63, 0, 0).toCssJson(2.625f))
        assertEquals(32.0, withInsets.getJSONObject("insets").getDouble("top"), 0.0)
        assertEquals(24.0, withInsets.getJSONObject("insets").getDouble("bottom"), 0.0)
    }

    @Test
    fun `errors and messages reach listeners`() {
        val got = mutableListOf<String>()
        events.addListener(object : LetsBotListener {
            override fun onError(error: LetsBotException) {
                got += error.code.wireValue
            }

            override fun onMessage(text: String) {
                got += "msg"
            }
        })

        events.error(LetsBotException(LetsBotErrorCode.BLOCKED))
        events.message("hello")

        assertEquals(listOf("blocked", "msg"), got)
    }

    @Test
    fun `renewSession keeps a token another caller already replaced`() = runBlocking {
        store.put(SecureStore.VISITOR_TOKEN, TOKEN_B)

        assertEquals(TOKEN_B, core.renewSession(TOKEN_A))
        assertEquals(0, server.requestCount)
        assertNotEquals(TOKEN_A, store.get(SecureStore.VISITOR_TOKEN))
    }

    private suspend fun expectError(block: suspend () -> Unit): LetsBotException {
        try {
            block()
        } catch (e: LetsBotException) {
            return e
        }
        fail("expected LetsBotException")
        throw AssertionError()
    }
}
