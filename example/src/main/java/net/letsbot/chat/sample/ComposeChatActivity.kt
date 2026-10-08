package net.letsbot.chat.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import net.letsbot.chat.compose.LetsBotChatScreen

/** Jetpack Compose demo of `LetsBotChatScreen`. */
class ComposeChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                LetsBotChatScreen(
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    onClose = { finish() },
                )
            }
        }
    }
}
