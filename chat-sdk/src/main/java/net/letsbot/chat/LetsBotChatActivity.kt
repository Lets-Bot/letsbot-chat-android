package net.letsbot.chat

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Full-screen activity hosting the LetsBot chat. Open it with [LetsBot.show]; it closes on the page's close button,
 * on back, or with [LetsBot.hide].
 */
public class LetsBotChatActivity : Activity(), LetsBotChatHost {
    private lateinit var chatView: LetsBotChatView
    private var fileResult: ((Array<Uri>?) -> Unit)? = null
    private var permissionResult: ((Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.letsbot_chat_title)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        chatView = LetsBotChatView(this).also { it.host = this }
        ViewCompat.setOnApplyWindowInsetsListener(chatView) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(
            chatView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    override fun onDestroy() {
        chatView.destroy()
        fileResult?.invoke(null)
        fileResult = null
        permissionResult?.invoke(false)
        permissionResult = null
        super.onDestroy()
    }

    override fun launchFileChooser(intent: Intent, onResult: (Array<Uri>?) -> Unit): Boolean = try {
        fileResult?.invoke(null)
        fileResult = onResult
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_FILE)
        true
    } catch (_: ActivityNotFoundException) {
        fileResult = null
        false
    }

    @Deprecated("Deprecated in Java")
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_FILE) {
            val callback = fileResult
            fileResult = null
            callback?.invoke(LetsBotChatView.parseFileChooserResult(resultCode, data))
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun requestRecordAudioPermission(onResult: (Boolean) -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onResult(true)
            return
        }
        permissionResult?.invoke(false)
        permissionResult = onResult
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_AUDIO) {
            val callback = permissionResult
            permissionResult = null
            callback?.invoke(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun closeChat() {
        if (!isFinishing) finish()
    }

    private companion object {
        const val REQUEST_FILE = 0x4c42
        const val REQUEST_AUDIO = 0x4c43
    }
}
