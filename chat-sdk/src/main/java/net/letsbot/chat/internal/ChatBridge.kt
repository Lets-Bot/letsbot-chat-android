package net.letsbot.chat.internal

import android.webkit.JavascriptInterface

/**
 * Exposed to the hosted page as `window.LetsBotAndroid`. Runs on a WebView binder thread; [onMessage] must hop
 * to the main thread and verify the page origin before acting.
 */
internal class ChatBridge(private val onMessage: (String) -> Unit) {
    @JavascriptInterface
    fun postMessage(message: String?) {
        if (message != null) onMessage(message)
    }

    companion object {
        const val NAME = "LetsBotAndroid"
    }
}
