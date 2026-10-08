package net.letsbot.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LetsBotPublicApiTest {
    @Test
    fun `recognises LetsBot push payloads`() {
        assertTrue(LetsBot.isLetsBotNotification(mapOf("lb" to "1", "lb_k" to "pk", "lb_c" to "12")))
        assertFalse(LetsBot.isLetsBotNotification(mapOf("lb" to "0")))
        assertFalse(LetsBot.isLetsBotNotification(mapOf("type" to "promo")))
        assertFalse(LetsBot.isLetsBotNotification(null as Map<String, String>?))
    }

    @Test
    fun `error codes round-trip their wire values`() {
        val apiCodes = listOf(
            "not_found", "app_not_registered", "invalid_visitor", "identity_invalid", "identity_expired", "blocked",
            "invalid", "too_long", "invalid_contact", "consent_required", "file_too_big", "file_type", "slow_down", "busy",
        )
        apiCodes.forEach { assertEquals(it, LetsBotErrorCode.fromWire(it).wireValue) }
        assertEquals(LetsBotErrorCode.UNKNOWN, LetsBotErrorCode.fromWire("nope"))
        assertEquals(LetsBotErrorCode.UNKNOWN, LetsBotErrorCode.fromWire(null))
    }

    @Test
    fun `sdk version matches the build`() {
        assertEquals(BuildConfig.SDK_VERSION, LetsBot.SDK_VERSION)
    }

    @Test
    fun `starts unconfigured with zero unread`() {
        assertFalse(LetsBot.isConfigured)
        assertEquals(0, LetsBot.unreadCount.value)
    }
}
