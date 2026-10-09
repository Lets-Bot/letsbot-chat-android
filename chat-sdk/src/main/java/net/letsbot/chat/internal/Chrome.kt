package net.letsbot.chat.internal

import android.content.Context
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Colours of the hosted page's chrome (API.md §8.1): status-bar icon style over the header, header colour and page
 * background. Reported by the page with `{"lb":"chrome",...}` and cached per app + theme so the next chat opens with
 * the right colours before the page paints.
 */
internal data class ChatChrome(
    /** `true` = light (white) status-bar icons. */
    val lightStatusBar: Boolean,
    /** `#rrggbb`, lower case. */
    val header: String,
    /** `#rrggbb`, lower case. */
    val background: String,
) {
    /** ARGB of [background]. */
    val backgroundArgb: Int get() = argb(background)

    /** Whether the navigation-bar icons should be dark (light page background). */
    val lightNavigationBar: Boolean get() = !isDark(background)

    fun merged(event: BridgeMessage.Chrome): ChatChrome = ChatChrome(
        lightStatusBar = event.lightStatusBar,
        header = event.header ?: header,
        background = event.background ?: background,
    )

    fun encode(): String = JSONObject()
        .put("statusBar", if (lightStatusBar) "light" else "dark")
        .put("header", header)
        .put("background", background)
        .toString()

    companion object {
        const val LIGHT_BACKGROUND = "#ffffff"
        const val DARK_BACKGROUND = "#111418"
        private val HEX = Regex("^#[0-9a-fA-F]{6}$")

        /** Before the page reports its chrome and nothing is cached: brand colour as header, plain background. */
        fun neutral(dark: Boolean, brandColor: String?): ChatChrome {
            val background = if (dark) DARK_BACKGROUND else LIGHT_BACKGROUND
            val header = brandColor?.let(::normalizedHex) ?: background
            return ChatChrome(lightStatusBar = isDark(header), header = header, background = background)
        }

        fun normalizedHex(value: String?): String? = value?.takeIf { HEX.matches(it) }?.lowercase()

        fun argb(hex: String): Int = (0xFF000000L or hex.substring(1).toLong(16)).toInt()

        /** WCAG relative luminance < 0.5 → light content on top. */
        fun isDark(hex: String): Boolean {
            val rgb = argb(hex)
            fun linear(c: Int): Double {
                val v = c / 255.0
                return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
            }
            val luminance = 0.2126 * linear(rgb shr 16 and 0xFF) + 0.7152 * linear(rgb shr 8 and 0xFF) +
                0.0722 * linear(rgb and 0xFF)
            return luminance < 0.5
        }

        fun decode(raw: String?): ChatChrome? {
            if (raw.isNullOrEmpty()) return null
            return try {
                val json = JSONObject(raw)
                val statusBar = json.optString("statusBar")
                val header = normalizedHex(json.optString("header"))
                val background = normalizedHex(json.optString("background"))
                if ((statusBar != "light" && statusBar != "dark") || header == null || background == null) {
                    null
                } else {
                    ChatChrome(statusBar == "light", header, background)
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** Last chrome per server + App Key + theme. Colours only; nothing secret. */
internal class ChromeCache(private val store: KeyValue) {
    interface KeyValue {
        fun get(key: String): String?
        fun put(key: String, value: String)
    }

    fun load(baseUrl: String, appKey: String, theme: String): ChatChrome? =
        ChatChrome.decode(store.get(key(baseUrl, appKey, theme)))

    fun save(chrome: ChatChrome, baseUrl: String, appKey: String, theme: String) {
        store.put(key(baseUrl, appKey, theme), chrome.encode())
    }

    companion object {
        fun key(baseUrl: String, appKey: String, theme: String): String = "chrome|$baseUrl|$appKey|$theme"
    }
}

/** [ChromeCache.KeyValue] in the app's private preferences file `net.letsbot.chat.chrome`. */
internal class PreferencesKeyValue(context: Context) : ChromeCache.KeyValue {
    private val prefs = context.getSharedPreferences("net.letsbot.chat.chrome", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

/** Safe-area insets in physical px, already clipped to the chat view's bounds. */
internal data class SafeInsets(val top: Int, val bottom: Int, val left: Int, val right: Int) {
    /** `{top, bottom, left, right}` in CSS px for `boot({insets})` / `setInsets(...)`. */
    fun toCssJson(density: Float): JSONObject {
        val d = if (density > 0f) density else 1f
        fun css(px: Int): Double = ((max(px, 0) / d) * 100).roundToInt() / 100.0
        return JSONObject()
            .put("top", css(top))
            .put("bottom", css(bottom))
            .put("left", css(left))
            .put("right", css(right))
    }

    companion object {
        val ZERO = SafeInsets(0, 0, 0, 0)

        /**
         * How much of the window's system bars / cutout / keyboard overlaps a view at [viewLeft], [viewTop] (in window
         * coordinates) of size [viewWidth] × [viewHeight] in a window of [windowWidth] × [windowHeight]. The bottom
         * inset is the larger of the navigation bar and the keyboard ([imeBottom]) so the composer stays above both.
         */
        @Suppress("LongParameterList")
        fun overlap(
            barsTop: Int,
            barsBottom: Int,
            barsLeft: Int,
            barsRight: Int,
            imeBottom: Int,
            viewLeft: Int,
            viewTop: Int,
            viewWidth: Int,
            viewHeight: Int,
            windowWidth: Int,
            windowHeight: Int,
        ): SafeInsets {
            val bottomInset = max(barsBottom, imeBottom)
            return SafeInsets(
                top = max(0, barsTop - viewTop),
                bottom = max(0, bottomInset - (windowHeight - (viewTop + viewHeight))),
                left = max(0, barsLeft - viewLeft),
                right = max(0, barsRight - (windowWidth - (viewLeft + viewWidth))),
            )
        }
    }
}

/** Reads and writes the system-bar icon appearance of a window (abstracted for tests). */
internal interface SystemBars {
    var lightStatusBars: Boolean
    var lightNavigationBars: Boolean
}

/**
 * Remembers the host window's status/navigation-bar icon appearance when the chat attaches and puts it back when the
 * chat goes away, so the app never keeps the chat's style.
 */
internal class SystemBarsKeeper(private val bars: SystemBars) {
    private var saved: Pair<Boolean, Boolean>? = null

    val isCaptured: Boolean get() = saved != null

    fun capture() {
        if (saved == null) saved = bars.lightStatusBars to bars.lightNavigationBars
    }

    /** Styles the bars the chat actually sits under ([statusBar] / [navigationBar]). */
    fun apply(chrome: ChatChrome, statusBar: Boolean = true, navigationBar: Boolean = true) {
        if (!statusBar && !navigationBar) return
        capture()
        if (statusBar) bars.lightStatusBars = !chrome.lightStatusBar
        if (navigationBar) bars.lightNavigationBars = chrome.lightNavigationBar
    }

    fun restore() {
        val previous = saved ?: return
        saved = null
        bars.lightStatusBars = previous.first
        bars.lightNavigationBars = previous.second
    }
}
