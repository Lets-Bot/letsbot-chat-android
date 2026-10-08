package net.letsbot.chat.compose

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import net.letsbot.chat.LetsBot
import net.letsbot.chat.LetsBotChatHost
import net.letsbot.chat.LetsBotChatView

/**
 * The LetsBot hosted chat screen as a composable. [LetsBot.configure] must have been called first.
 *
 * The composable fills [modifier]; apply window-inset padding (e.g. `Modifier.safeDrawingPadding()`) if your
 * screen is edge-to-edge.
 *
 * @param onClose called when the user closes the chat from the page or [LetsBot.hide] is called; navigate back here.
 */
@SuppressLint("RestrictedApi")
@Composable
public fun LetsBotChatScreen(modifier: Modifier = Modifier, onClose: () -> Unit = {}) {
    val context = LocalContext.current
    val currentOnClose by rememberUpdatedState(onClose)
    val pending = remember { PendingResults() }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = pending.file
        pending.file = null
        callback?.invoke(LetsBotChatView.parseFileChooserResult(result.resultCode, result.data))
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val callback = pending.permission
        pending.permission = null
        callback?.invoke(granted)
    }

    val chatView = remember(context) {
        LetsBotChatView(context).apply {
            host = object : LetsBotChatHost {
                override fun launchFileChooser(intent: Intent, onResult: (Array<Uri>?) -> Unit): Boolean = try {
                    pending.file?.invoke(null)
                    pending.file = onResult
                    fileLauncher.launch(intent)
                    true
                } catch (_: ActivityNotFoundException) {
                    pending.file = null
                    false
                }

                override fun requestRecordAudioPermission(onResult: (Boolean) -> Unit) {
                    pending.permission?.invoke(false)
                    pending.permission = onResult
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }

                override fun closeChat() {
                    currentOnClose()
                }
            }
        }
    }

    DisposableEffect(chatView) {
        onDispose {
            pending.file?.invoke(null)
            pending.permission?.invoke(false)
            pending.file = null
            pending.permission = null
            chatView.destroy()
        }
    }

    AndroidView(factory = { chatView }, modifier = modifier)
}

private class PendingResults {
    var file: ((Array<Uri>?) -> Unit)? = null
    var permission: ((Boolean) -> Unit)? = null
}
