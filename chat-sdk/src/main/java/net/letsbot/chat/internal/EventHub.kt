package net.letsbot.chat.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.letsbot.chat.LetsBotException
import net.letsbot.chat.LetsBotListener
import net.letsbot.chat.LetsBotSubscription
import net.letsbot.chat.UnreadCountListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/** Posts work to the UI thread (Handler-backed in the app, immediate in unit tests). */
internal fun interface MainThread {
    fun post(block: () -> Unit)
}

/** An on-screen chat (activity or Compose) the SDK can drive. All calls happen on the main thread. */
internal interface ChatSurface {
    fun requestClose()

    fun reload()

    fun pushContext(json: String)

    fun applyTheme()
}

/** Listener registry, unread state and open chat surfaces. Outlives re-configuration. */
internal class EventHub(private val main: MainThread) {
    private val listeners = CopyOnWriteArrayList<LetsBotListener>()
    private val unreadListeners = CopyOnWriteArrayList<UnreadCountListener>()
    private val surfaces = CopyOnWriteArraySet<ChatSurface>()
    private val visible = AtomicInteger(0)
    private val unread = MutableStateFlow(0)

    val unreadCount: StateFlow<Int> = unread.asStateFlow()

    val isChatVisible: Boolean get() = visible.get() > 0

    fun addListener(listener: LetsBotListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: LetsBotListener) {
        listeners.remove(listener)
    }

    fun addUnreadListener(listener: UnreadCountListener): LetsBotSubscription {
        unreadListeners.add(listener)
        main.post { if (unreadListeners.contains(listener)) listener.onUnreadCountChanged(unread.value) }
        return LetsBotSubscription { unreadListeners.remove(listener) }
    }

    fun removeUnreadListener(listener: UnreadCountListener) {
        unreadListeners.remove(listener)
    }

    fun setUnread(count: Int) {
        val value = count.coerceAtLeast(0)
        if (unread.value == value) return
        unread.value = value
        main.post {
            listeners.forEach { it.onUnreadChanged(value) }
            unreadListeners.forEach { it.onUnreadCountChanged(value) }
        }
    }

    fun error(error: LetsBotException) {
        main.post { listeners.forEach { it.onError(error) } }
    }

    fun message(text: String) {
        main.post { listeners.forEach { it.onMessage(text) } }
    }

    fun attach(surface: ChatSurface) {
        if (surfaces.add(surface)) {
            visible.incrementAndGet()
            main.post { listeners.forEach { it.onOpen() } }
        }
    }

    fun detach(surface: ChatSurface) {
        if (surfaces.remove(surface)) {
            visible.decrementAndGet()
            main.post { listeners.forEach { it.onClose() } }
        }
    }

    fun forEachSurface(action: (ChatSurface) -> Unit) {
        main.post { surfaces.forEach(action) }
    }
}
