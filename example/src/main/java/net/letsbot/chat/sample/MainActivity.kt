package net.letsbot.chat.sample

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import net.letsbot.chat.LetsBot
import net.letsbot.chat.LetsBotErrorCode
import net.letsbot.chat.LetsBotException
import net.letsbot.chat.LetsBotListener
import net.letsbot.chat.LetsBotSubscription

/** Plain-Views demo: open the chat, unread badge, identify / logout, notification hand-off. */
class MainActivity : Activity() {
    private var unreadSubscription: LetsBotSubscription? = null
    private lateinit var openButton: Button

    private val listener = object : LetsBotListener {
        override fun onError(error: LetsBotException) {
            val hint = when (error.code) {
                LetsBotErrorCode.APP_NOT_REGISTERED -> getString(R.string.hint_app_not_registered)
                LetsBotErrorCode.NOT_FOUND -> getString(R.string.hint_not_found)
                LetsBotErrorCode.IDENTITY_EXPIRED -> getString(R.string.hint_identity_expired)
                else -> error.code.wireValue
            }
            Toast.makeText(this@MainActivity, hint, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val userId = EditText(this).apply { setHint(R.string.user_id_hint) }
        val identityToken = EditText(this).apply {
            setHint(R.string.identity_token_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        openButton = Button(this).apply {
            setText(R.string.open_chat)
            setOnClickListener {
                LetsBot.setContext(mapOf("screen" to "sample_home"))
                LetsBot.show(this@MainActivity)
            }
        }
        val composeButton = Button(this).apply {
            setText(R.string.open_chat_compose)
            setOnClickListener { startActivity(Intent(this@MainActivity, ComposeChatActivity::class.java)) }
        }
        val identifyButton = Button(this).apply {
            setText(R.string.identify)
            setOnClickListener {
                LetsBot.identify(userId.text.toString(), identityToken.text.toString()) { error ->
                    val message = if (error == null) getString(R.string.identified) else getString(R.string.identify_failed, error.code.wireValue)
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
        val logoutButton = Button(this).apply {
            setText(R.string.logout)
            setOnClickListener { LetsBot.logout() }
        }
        val info = TextView(this).apply {
            text = getString(R.string.config_info, BuildConfig.LETSBOT_APP_KEY, BuildConfig.LETSBOT_BASE_URL)
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad * 3, pad, pad)
                addView(info)
                addView(openButton)
                addView(composeButton)
                addView(userId)
                addView(identityToken)
                addView(identifyButton)
                addView(logoutButton)
            },
        )
        LetsBot.addListener(listener)
        unreadSubscription = LetsBot.addUnreadCountListener { count ->
            openButton.text = if (count > 0) getString(R.string.open_chat_unread, count) else getString(R.string.open_chat)
        }
        requestNotificationPermission()
        handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onDestroy() {
        unreadSubscription?.cancel()
        LetsBot.removeListener(listener)
        super.onDestroy()
    }

    /** Tapping a LetsBot notification while the app was in the background launches this activity with its data. */
    private fun handleLaunchIntent(intent: Intent?) {
        if (LetsBot.isLetsBotNotification(intent)) LetsBot.handleNotification(this, intent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
