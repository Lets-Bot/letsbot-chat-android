package net.letsbot.chat.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeAndPolicyTest {
    private val policy = UrlPolicy("https://letsbot.net/", "/api/sdk/v1/$APP_KEY/ui")

    @Test
    fun `parses every page event`() {
        assertEquals(BridgeMessage.Ready, BridgeMessage.parse("""{"lb":"ready"}"""))
        assertEquals(BridgeMessage.TokenInvalid, BridgeMessage.parse("""{"lb":"token_invalid"}"""))
        assertEquals(BridgeMessage.Close, BridgeMessage.parse("""{"lb":"close"}"""))
        assertEquals(BridgeMessage.OpenUrl("https://acme.com/x"), BridgeMessage.parse("""{"lb":"open_url","url":"https://acme.com/x"}"""))
        assertEquals(BridgeMessage.Unread(4), BridgeMessage.parse("""{"lb":"unread","count":4}"""))
        assertEquals(BridgeMessage.Unread(0), BridgeMessage.parse("""{"lb":"unread","count":-1}"""))
        assertEquals(BridgeMessage.Message("Hi"), BridgeMessage.parse("""{"lb":"message","t":"Hi"}"""))
        assertEquals(BridgeMessage.Error("blocked"), BridgeMessage.parse("""{"lb":"error","code":"blocked"}"""))
    }

    @Test
    fun `ignores malformed and unknown events`() {
        assertNull(BridgeMessage.parse(null))
        assertNull(BridgeMessage.parse("not json"))
        assertNull(BridgeMessage.parse("""{"lb":"teleport"}"""))
        assertNull(BridgeMessage.parse("""{"lb":"open_url"}"""))
        assertNull(BridgeMessage.parse("{" + "\"x\":\"" + "a".repeat(70_000) + "\"}"))
    }

    @Test
    fun `only the ui page on the LetsBot origin is trusted for navigation`() {
        assertTrue(policy.isChatUi("https://letsbot.net/api/sdk/v1/$APP_KEY/ui?l=ar&theme=dark&p=android"))
        assertTrue(policy.isChatUi("https://LETSBOT.net:443/api/sdk/v1/$APP_KEY/ui"))
        assertFalse(policy.isChatUi("https://letsbot.net/api/sdk/v1/$APP_KEY/messages"))
        assertFalse(policy.isChatUi("https://letsbot.net/api/sdk/v1/other/ui"))
        assertFalse(policy.isChatUi("http://letsbot.net/api/sdk/v1/$APP_KEY/ui"))
        assertFalse(policy.isChatUi("https://letsbot.net.evil.com/api/sdk/v1/$APP_KEY/ui"))
        assertFalse(policy.isChatUi("https://evil.com/api/sdk/v1/$APP_KEY/ui"))
        assertFalse(policy.isChatUi("javascript:alert(1)"))
        assertFalse(policy.isChatUi(null))
    }

    @Test
    fun `bridge origin check compares scheme host and port`() {
        assertTrue(policy.isTrustedOrigin("https://letsbot.net/anything"))
        assertFalse(policy.isTrustedOrigin("https://letsbot.net:8443/"))
        assertFalse(policy.isTrustedOrigin("https://sub.letsbot.net/"))
        assertFalse(policy.isTrustedOrigin("file:///android_asset/index.html"))
    }

    @Test
    fun `only http and https links may leave the app`() {
        assertTrue(UrlPolicy.isExternalWebUrl("https://acme.com"))
        assertTrue(UrlPolicy.isExternalWebUrl("http://acme.com/a?b=c"))
        assertFalse(UrlPolicy.isExternalWebUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(UrlPolicy.isExternalWebUrl("file:///sdcard/x"))
        assertFalse(UrlPolicy.isExternalWebUrl("javascript:alert(1)"))
        assertFalse(UrlPolicy.isExternalWebUrl("content://com.acme/x"))
        assertFalse(UrlPolicy.isExternalWebUrl("not a url"))
    }

    @Test
    fun `context maps become JSON-safe`() {
        val json = mapOf(
            "s" to "x", "i" to 1, "b" to true, "n" to null, "f" to 1.5f,
            "list" to listOf(1, "a"), "map" to mapOf("k" to "v"), "other" to StringBuilder("sb"),
        ).toJsonObject()
        assertEquals("x", json.getString("s"))
        assertEquals(1, json.getInt("i"))
        assertTrue(json.getBoolean("b"))
        assertTrue(json.isNull("n"))
        assertEquals(1.5, json.getDouble("f"), 0.0)
        assertEquals("a", json.getJSONArray("list").getString(1))
        assertEquals("v", json.getJSONObject("map").getString("k"))
        assertEquals("sb", json.getString("other"))
    }
}
