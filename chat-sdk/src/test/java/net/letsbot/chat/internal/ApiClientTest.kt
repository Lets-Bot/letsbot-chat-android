package net.letsbot.chat.internal

import net.letsbot.chat.LetsBotErrorCode
import net.letsbot.chat.LetsBotException
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = ApiClient(server.baseUrl() + "/", APP_KEY, testClient)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `sends the contract headers and JSON body`() {
        server.enqueue(json(200, """{"ok":true}"""))

        val response = api.request("PUT", "device", TOKEN_A, JSONObject().put("token", "fcm-1"))

        assertEquals(200, response.status)
        assertTrue(response.body!!.getBoolean("ok"))
        val request = server.next()
        assertEquals("PUT", request.method)
        assertEquals("/api/sdk/v1/$APP_KEY/device", request.path)
        assertEquals("application/json", request.getHeader("Accept"))
        assertEquals("com.acme.app", request.getHeader("X-LB-App-Id"))
        assertEquals("android", request.getHeader("X-LB-Platform"))
        assertEquals("android/0.1.0", request.getHeader("X-LB-SDK"))
        assertEquals(TOKEN_A, request.getHeader("X-LB-Visitor"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("fcm-1", JSONObject(request.body.readUtf8()).getString("token"))
    }

    @Test
    fun `omits visitor header and body when not given`() {
        server.enqueue(json(200, """{"title":"Acme"}"""))

        api.request("GET", "config")

        val request = server.next()
        assertNull(request.getHeader("X-LB-Visitor"))
        assertNull(request.getHeader("Content-Type"))
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun `encodes the app key and query`() {
        assertEquals(
            "${server.baseUrl()}/api/sdk/v1/a%2Fb%20c/ui?l=ar&theme=dark&p=android",
            ApiClient(server.baseUrl(), "a/b c", testClient)
                .url("ui", linkedMapOf("l" to "ar", "theme" to "dark", "p" to "android")),
        )
    }

    @Test
    fun `maps every API error code`() {
        listOf(
            404 to "not_found", 403 to "app_not_registered", 401 to "invalid_visitor", 401 to "identity_invalid",
            401 to "identity_expired", 403 to "blocked", 422 to "invalid", 422 to "too_long",
            422 to "invalid_contact", 422 to "consent_required", 422 to "file_too_big", 422 to "file_type",
            429 to "slow_down", 429 to "busy",
        ).forEach { (status, code) ->
            server.enqueue(json(status, """{"error":"$code"}"""))
            val error = expectError { api.request("POST", "messages", TOKEN_A) }
            assertEquals(code, error.code.wireValue)
            assertEquals(status, error.httpStatus)
        }
    }

    @Test
    fun `reads 401 error codes after sending a JSON body`() {
        server.enqueue(json(401, """{"error":"identity_expired"}"""))

        val error = expectError { api.request("POST", "identify", TOKEN_A, JSONObject().put("identity_token", "x")) }

        assertEquals(LetsBotErrorCode.IDENTITY_EXPIRED, error.code)
    }

    @Test
    fun `honours Retry-After on rate limits`() {
        server.enqueue(json(429, """{"error":"slow_down"}""", "Retry-After" to "30"))

        val error = expectError { api.request("POST", "session") }

        assertEquals(LetsBotErrorCode.SLOW_DOWN, error.code)
        assertEquals(30L, error.retryAfterSeconds)
    }

    @Test
    fun `unknown codes keep the raw value`() {
        server.enqueue(json(400, """{"error":"something_new"}"""))

        val error = expectError { api.request("POST", "session") }

        assertEquals(LetsBotErrorCode.UNKNOWN, error.code)
        assertEquals("something_new", error.rawCode)
    }

    @Test
    fun `server errors without body map to SERVER`() {
        server.enqueue(json(502, "<html>bad gateway</html>"))

        assertEquals(LetsBotErrorCode.SERVER, expectError { api.request("GET", "config") }.code)
    }

    @Test
    fun `connection failures map to NETWORK`() {
        val port = server.port
        server.shutdown()
        val offline = ApiClient("http://127.0.0.1:$port", APP_KEY, testClient, 1_000, 1_000)

        assertEquals(LetsBotErrorCode.NETWORK, expectError { offline.request("GET", "config") }.code)
    }

    private fun expectError(block: () -> Unit): LetsBotException {
        try {
            block()
        } catch (e: LetsBotException) {
            return e
        }
        fail("expected LetsBotException")
        throw AssertionError()
    }
}
