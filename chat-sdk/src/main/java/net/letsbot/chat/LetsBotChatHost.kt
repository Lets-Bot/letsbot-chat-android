package net.letsbot.chat

import android.content.Intent
import android.net.Uri
import androidx.annotation.RestrictTo

/**
 * Bridges [LetsBotChatView] to whoever hosts it (the SDK's activity or the Compose screen) for things a View cannot
 * do itself: activity results, runtime permissions and dismissal. Not intended for app code.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public interface LetsBotChatHost {
    /** Launches a document picker; returns `false` when it could not be started. [onResult] gets `null` on cancel. */
    public fun launchFileChooser(intent: Intent, onResult: (Array<Uri>?) -> Unit): Boolean

    /** Requests `RECORD_AUDIO` at runtime and reports whether it was granted. */
    public fun requestRecordAudioPermission(onResult: (Boolean) -> Unit)

    /** Dismisses the chat. */
    public fun closeChat()
}
