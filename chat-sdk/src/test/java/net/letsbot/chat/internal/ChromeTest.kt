package net.letsbot.chat.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromeTest {
    @Test
    fun `parses the chrome event`() {
        assertEquals(
            BridgeMessage.Chrome(lightStatusBar = true, header = "#0e7c66", background = "#f5f7f9"),
            BridgeMessage.parse("""{"lb":"chrome","statusBar":"light","header":"#0E7C66","background":"#f5f7f9"}"""),
        )
        assertEquals(
            BridgeMessage.Chrome(lightStatusBar = false, header = null, background = null),
            BridgeMessage.parse("""{"lb":"chrome","statusBar":"dark"}"""),
        )
        for (raw in listOf(
            """{"lb":"chrome"}""",
            """{"lb":"chrome","statusBar":"white"}""",
            """{"lb":"chrome","statusBar":"light","header":"green"}""",
            """{"lb":"chrome","statusBar":"light","header":"#0e7c6"}""",
            """{"lb":"chrome","statusBar":"dark","background":"#fff"}""",
            """{"lb":"chrome","statusBar":"dark","background":12}""",
        )) {
            assertNull(raw, BridgeMessage.parse(raw))
        }
    }

    @Test
    fun `neutral chrome uses the brand colour for the header`() {
        assertEquals(ChatChrome(true, "#0e7c66", "#ffffff"), ChatChrome.neutral(dark = false, brandColor = "#0E7C66"))
        assertFalse(ChatChrome.neutral(dark = false, brandColor = null).lightStatusBar)
        assertTrue(ChatChrome.neutral(dark = true, brandColor = null).lightStatusBar)
        assertEquals("#111418", ChatChrome.neutral(dark = true, brandColor = "nope").header)
    }

    @Test
    fun `merges events and maps colours`() {
        val base = ChatChrome(true, "#0e7c66", "#ffffff")
        assertEquals(
            ChatChrome(false, "#0e7c66", "#000000"),
            base.merged(BridgeMessage.Chrome(lightStatusBar = false, header = null, background = "#000000")),
        )
        assertEquals(0xFFFF8000.toInt(), ChatChrome.argb("#ff8000"))
        assertTrue(ChatChrome.isDark("#0e7c66"))
        assertFalse(ChatChrome.isDark("#f5f7f9"))
        assertTrue(base.lightNavigationBar)
        assertFalse(ChatChrome(true, "#000000", "#111418").lightNavigationBar)
    }

    @Test
    fun `chrome cache is per server app and theme`() {
        val map = HashMap<String, String>()
        val cache = ChromeCache(object : ChromeCache.KeyValue {
            override fun get(key: String): String? = map[key]
            override fun put(key: String, value: String) {
                map[key] = value
            }
        })
        val chrome = ChatChrome(true, "#0e7c66", "#f5f7f9")
        assertNull(cache.load("https://letsbot.net", "k1", "light"))
        cache.save(chrome, "https://letsbot.net", "k1", "light")
        assertEquals(chrome, cache.load("https://letsbot.net", "k1", "light"))
        assertNull(cache.load("https://letsbot.net", "k1", "dark"))
        assertNull(cache.load("https://letsbot.net", "k2", "light"))
        map[ChromeCache.key("https://letsbot.net", "k3", "light")] = "{}"
        assertNull(cache.load("https://letsbot.net", "k3", "light"))
        assertNull(ChatChrome.decode("not json"))
    }

    @Test
    fun `insets are clipped to the view and serialised as CSS px`() {
        // Full-screen view on a 1080x2400 window: status bar 84, navigation bar 63, keyboard closed.
        val full = SafeInsets.overlap(84, 63, 0, 0, 0, 0, 0, 1080, 2400, 1080, 2400)
        assertEquals(SafeInsets(84, 63, 0, 0), full)
        // Keyboard open (IME 900 px incl. the navigation bar): bottom follows the keyboard.
        assertEquals(900, SafeInsets.overlap(84, 63, 0, 0, 900, 0, 0, 1080, 2400, 1080, 2400).bottom)
        // Below a 200 px app bar: no top inset.
        assertEquals(0, SafeInsets.overlap(84, 63, 0, 0, 0, 0, 200, 1080, 2200, 1080, 2400).top)
        // Landscape cutout on the left.
        assertEquals(120, SafeInsets.overlap(0, 0, 120, 0, 0, 0, 0, 2400, 1080, 2400, 1080).left)

        val css = full.toCssJson(2.625f)
        assertEquals(32.0, css.getDouble("top"), 0.0)
        assertEquals(24.0, css.getDouble("bottom"), 0.0)
        assertEquals(0.0, css.getDouble("left"), 0.0)
        assertEquals(0.0, css.getDouble("right"), 0.0)
        assertEquals(33.33, SafeInsets(100, 0, 0, 0).toCssJson(3f).getDouble("top"), 0.0)
    }

    @Test
    fun `restores the host bar style when the chat goes away`() {
        val bars = object : SystemBars {
            override var lightStatusBars = true
            override var lightNavigationBars = true
        }
        val keeper = SystemBarsKeeper(bars)
        keeper.apply(ChatChrome(lightStatusBar = true, header = "#0e7c66", background = "#111418"))
        assertFalse(bars.lightStatusBars)
        assertFalse(bars.lightNavigationBars)
        assertTrue(keeper.isCaptured)
        // A later chrome must not overwrite the saved host style.
        keeper.apply(ChatChrome(lightStatusBar = false, header = "#ffffff", background = "#ffffff"))
        assertTrue(bars.lightStatusBars)
        keeper.restore()
        assertTrue(bars.lightStatusBars)
        assertTrue(bars.lightNavigationBars)
        assertFalse(keeper.isCaptured)

        // Under an app bar (not under the status bar): status bar untouched.
        bars.lightStatusBars = true
        keeper.apply(ChatChrome(true, "#0e7c66", "#ffffff"), statusBar = false, navigationBar = true)
        assertTrue(bars.lightStatusBars)
        keeper.restore()
    }
}
