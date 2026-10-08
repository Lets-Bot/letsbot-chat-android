package net.letsbot.chat.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.letsbot.chat.LetsBotTheme
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.TimeUnit

internal const val APP_KEY = "pk_test_123"
internal const val TOKEN_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
internal const val TOKEN_B = "cccccccccccccccccccccccccccccccccccccccc.ddddddddddddddddddddddddddddddddddddddddddd"

internal val testClient = ClientInfo(
    appId = "com.acme.app",
    appVersion = "2.3.0",
    osVersion = "14",
    sdk = "android/0.1.0",
)

internal fun MockWebServer.baseUrl(): String = url("/").toString().trimEnd('/')

internal fun MockWebServer.next(): RecordedRequest =
    requireNotNull(takeRequest(5, TimeUnit.SECONDS)) { "expected another request" }

internal fun json(status: Int, body: String, vararg headers: Pair<String, String>): MockResponse =
    MockResponse().setResponseCode(status).setBody(body).addHeader("Content-Type", "application/json").apply {
        headers.forEach { (k, v) -> addHeader(k, v) }
    }

/** Answers by route name (last path segment) so concurrent requests get the right response. */
internal fun MockWebServer.routes(vararg responses: Pair<String, MockResponse>) {
    val map = responses.toMap()
    dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            map[request.path!!.substringBefore('?').substringAfterLast('/')] ?: MockResponse().setResponseCode(500)
    }
}

internal fun newCore(
    server: MockWebServer,
    store: SecureStore = InMemorySecureStore(),
    events: EventHub = EventHub { it() },
    locale: String? = "ar",
    color: String? = null,
): ChatCore = ChatCore(
    appKey = APP_KEY,
    baseUrl = server.baseUrl(),
    client = testClient,
    api = ApiClient(server.baseUrl(), APP_KEY, testClient, connectTimeoutMs = 3_000, readTimeoutMs = 3_000),
    store = store,
    events = events,
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    io = Dispatchers.IO,
    locale = locale,
    theme = LetsBotTheme.AUTO,
    color = color,
    deviceLocale = { "en" },
)
