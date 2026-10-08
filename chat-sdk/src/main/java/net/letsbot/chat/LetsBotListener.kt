package net.letsbot.chat

/**
 * Receives chat lifecycle events. Every method has an empty default implementation; override what you need.
 * Callbacks are always delivered on the main thread.
 *
 * Register with [LetsBot.addListener] and unregister with [LetsBot.removeListener] (for example in `onDestroy`).
 */
public interface LetsBotListener {
    /** The chat screen became visible. */
    public fun onOpen() {}

    /** The chat screen was dismissed. */
    public fun onClose() {}

    /**
     * A new message from the team or the AI assistant arrived while the chat screen was open.
     * Do not log [text]: it is the user's conversation.
     */
    public fun onMessage(text: String) {}

    /** The number of unread team / AI messages changed. */
    public fun onUnreadChanged(count: Int) {}

    /** An operation failed. See [LetsBotException.code]. */
    public fun onError(error: LetsBotException) {}
}

/** Result callback for the non-suspending variants (for Java and callback-style Kotlin code). Main thread. */
public fun interface LetsBotCallback {
    /** Called once with `null` on success, or the error that occurred. */
    public fun onComplete(error: LetsBotException?)
}

/** Lightweight unread-count observer (an alternative to collecting [LetsBot.unreadCount]). Main thread. */
public fun interface UnreadCountListener {
    /** Called with the current count right after registration and on every change. */
    public fun onUnreadCountChanged(count: Int)
}

/** Handle returned by listener registrations; call [cancel] when the observing screen is disposed. */
public fun interface LetsBotSubscription {
    /** Stops delivering callbacks. Safe to call more than once. */
    public fun cancel()
}
