package net.letsbot.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.letsbot.chat.internal.ApiClient
import net.letsbot.chat.internal.ChatCore
import net.letsbot.chat.internal.ClientInfo
import net.letsbot.chat.internal.EventHub
import net.letsbot.chat.internal.KeystoreSecureStore
import net.letsbot.chat.internal.MainThread
import net.letsbot.chat.internal.UrlPolicy
import java.util.Locale

/**
 * Entry point of the LetsBot In-App Chat SDK.
 *
 * ```kotlin
 * // Application.onCreate
 * LetsBot.configure(this, appKey = "YOUR_APP_KEY", locale = "en")
 * // Anywhere
 * LetsBot.show(activity)
 * ```
 *
 * All methods are safe to call from any thread unless stated otherwise. Listener callbacks arrive on the main thread.
 */
public object LetsBot {
    /** Version of this SDK. */
    public const val SDK_VERSION: String = "0.1.0"

    /** Default LetsBot server. */
    public const val DEFAULT_BASE_URL: String = "https://letsbot.net"

    private const val TAG = "LetsBot"
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val main = MainThread { block ->
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
    private val events = EventHub(main)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var core: ChatCore? = null

    @Volatile
    private var lifecycleObserverInstalled = false

    /** Number of unread team / AI messages (0 while the chat is visible). Collect it to drive a badge. */
    @JvmStatic
    public val unreadCount: StateFlow<Int> get() = events.unreadCount

    /** `true` once [configure] succeeded. */
    @JvmStatic
    public val isConfigured: Boolean get() = core != null

    /** `true` while a chat screen is on screen. */
    @JvmStatic
    public val isChatVisible: Boolean get() = events.isChatVisible

    /**
     * Configures the SDK. Call once, as early as possible (typically `Application.onCreate`).
     * Calling it again with a different App Key or base URL replaces the configuration.
     *
     * @param context any context; the application context is retained.
     * @param appKey the public App Key from LetsBot panel → Channels → In-App Chat.
     * @param baseUrl LetsBot server, defaults to [DEFAULT_BASE_URL].
     * @param locale chat language (`ar`, `en`, `es`, `pt`; others fall back to English). `null` = device language.
     * @param theme light / dark / follow the device.
     * @param color optional brand colour `#RRGGBB`; otherwise the colour configured in the LetsBot panel is used.
     * @throws IllegalArgumentException when [appKey] is blank, [baseUrl] is not an http(s) URL or [color] is malformed.
     */
    @JvmStatic
    @JvmOverloads
    public fun configure(
        context: Context,
        appKey: String,
        baseUrl: String = DEFAULT_BASE_URL,
        locale: String? = null,
        theme: LetsBotTheme = LetsBotTheme.AUTO,
        color: String? = null,
    ) {
        val key = appKey.trim()
        require(key.isNotEmpty()) { "LetsBot appKey must not be blank" }
        val base = ApiClient.normalizeBaseUrl(baseUrl)
        require(UrlPolicy.isExternalWebUrl(base)) { "LetsBot baseUrl must be an http(s) URL" }
        val brand = color?.trim()?.takeIf { it.isNotEmpty() }
        require(brand == null || COLOR.matches(brand)) { "LetsBot color must look like #RRGGBB" }
        if (!base.startsWith("https://")) Log.w(TAG, "baseUrl is not https; use http only for local development")

        val app = context.applicationContext
        val existing = core
        if (existing != null && existing.appKey == key && existing.baseUrl == base) {
            existing.locale = locale?.trim()?.takeIf { it.isNotEmpty() }
            existing.theme = theme
            return
        }
        val client = ClientInfo(
            appId = app.packageName,
            appVersion = appVersion(app),
            osVersion = Build.VERSION.RELEASE ?: "",
            sdk = "android/$SDK_VERSION",
        )
        core = ChatCore(
            appKey = key,
            baseUrl = base,
            client = client,
            api = ApiClient(base, key, client),
            store = KeystoreSecureStore(app),
            events = events,
            scope = scope,
            io = Dispatchers.IO,
            locale = locale,
            theme = theme,
            color = brand,
            deviceLocale = { Locale.getDefault().language.ifEmpty { "en" } },
        )
        installLifecycleObserver()
    }

    /**
     * Links the chat to your logged-in user with an identity token minted by your backend (JWT HS256 signed with the
     * Identity Secret, `sub` = [userId]). Call after login and on every app start while logged in.
     * If a different user was identified on this device before, their session is discarded first.
     *
     * @throws LetsBotException e.g. [LetsBotErrorCode.IDENTITY_EXPIRED] → fetch a new token and retry.
     */
    @JvmSynthetic
    public suspend fun identify(
        userId: String,
        identityToken: String,
        name: String? = null,
        email: String? = null,
        phone: String? = null,
    ) {
        requireCore().identify(userId, identityToken, name, email, phone)
    }

    /** Callback variant of [identify] for Java and non-coroutine code. [callback] runs on the main thread. */
    @JvmStatic
    @JvmOverloads
    public fun identify(
        userId: String,
        identityToken: String,
        name: String? = null,
        email: String? = null,
        phone: String? = null,
        callback: LetsBotCallback,
    ) {
        scope.launch {
            val error = try {
                identify(userId, identityToken, name, email, phone)
                null
            } catch (e: LetsBotException) {
                e
            }
            main.post { callback.onComplete(error) }
        }
    }

    /**
     * Ends the current chat identity on this device: the visitor token is deleted immediately, push for this device is
     * unregistered on the server, and the next chat starts a fresh anonymous session. Call BEFORE clearing your own
     * session on logout.
     */
    @JvmStatic
    public fun logout() {
        core?.logout() ?: events.setUnread(0)
    }

    /** Opens the hosted chat screen ([LetsBotChatActivity]). Must be called on the main thread. */
    @JvmStatic
    public fun show(activity: Activity) {
        if (core == null) {
            notConfigured("show")
            return
        }
        activity.startActivity(chatIntent(activity))
    }

    /** An [Intent] that opens the chat screen, e.g. for a notification's `PendingIntent`. */
    @JvmStatic
    public fun chatIntent(context: Context): Intent {
        val intent = Intent(context, LetsBotChatActivity::class.java)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    /** Closes every open chat screen (activity or Compose). */
    @JvmStatic
    public fun hide() {
        events.forEachSurface { it.requestClose() }
    }

    /**
     * Registers this device's push token so replies reach the user while the app is closed. Call on start and from
     * `FirebaseMessagingService.onNewToken`. Errors are reported through [LetsBotListener.onError].
     */
    @JvmStatic
    @JvmOverloads
    public fun setPushToken(token: String, provider: PushProvider = PushProvider.FCM) {
        val current = core ?: return notConfigured("setPushToken")
        scope.launch {
            try {
                current.setPushToken(token, provider)
            } catch (e: LetsBotException) {
                events.error(e)
            }
        }
    }

    /** `true` when a push payload (FCM `RemoteMessage.data`) was sent by LetsBot. */
    @JvmStatic
    public fun isLetsBotNotification(data: Map<String, String>?): Boolean = data?.get("lb") == "1"

    /** `true` when an [Intent] (the launch intent after tapping a background FCM notification) came from LetsBot. */
    @JvmStatic
    public fun isLetsBotNotification(intent: Intent?): Boolean = intent?.extras?.getString("lb") == "1"

    /**
     * Opens the chat for a LetsBot notification. Returns `false` (and does nothing) for any other notification so
     * your app can handle it as usual.
     */
    @JvmStatic
    public fun handleNotification(context: Context, data: Map<String, String>?): Boolean {
        if (!isLetsBotNotification(data)) return false
        val current = core ?: return false.also { notConfigured("handleNotification") }
        val key = data?.get("lb_k")
        if (!key.isNullOrEmpty() && key != current.appKey) return false
        context.startActivity(chatIntent(context))
        return true
    }

    /** [handleNotification] for the launch [Intent] produced by tapping a background FCM notification. */
    @JvmStatic
    public fun handleNotification(context: Context, intent: Intent?): Boolean {
        val extras = intent?.extras ?: return false
        val data = HashMap<String, String>()
        for (k in listOf("lb", "lb_k", "lb_c")) extras.getString(k)?.let { data[k] = it }
        return handleNotification(context, data)
    }

    /** Re-fetches the unread count (done automatically whenever the app comes to the foreground). */
    @JvmStatic
    public fun refreshUnreadCount() {
        val current = core ?: return
        scope.launch {
            try {
                current.refreshUnread()
            } catch (e: LetsBotException) {
                if (e.code != LetsBotErrorCode.NETWORK) events.error(e)
            }
        }
    }

    /** Registers a listener for unread changes; returns a subscription to [LetsBotSubscription.cancel]. */
    @JvmStatic
    public fun addUnreadCountListener(listener: UnreadCountListener): LetsBotSubscription =
        events.addUnreadListener(listener)

    /** Removes a listener added with [addUnreadCountListener]. */
    @JvmStatic
    public fun removeUnreadCountListener(listener: UnreadCountListener) {
        events.removeUnreadListener(listener)
    }

    /** Adds a lifecycle / error listener. */
    @JvmStatic
    public fun addListener(listener: LetsBotListener) {
        events.addListener(listener)
    }

    /** Removes a listener added with [addListener]. */
    @JvmStatic
    public fun removeListener(listener: LetsBotListener) {
        events.removeListener(listener)
    }

    /**
     * Tells the team and the AI assistant what the user is looking at (e.g. `mapOf("screen" to "order", "orderId" to
     * "1234")`). Replaces the previous context. Values: strings, numbers, booleans, null, lists and maps of those.
     */
    @JvmStatic
    public fun setContext(context: Map<String, Any?>) {
        core?.setContext(context) ?: notConfigured("setContext")
    }

    /** Changes the chat language (`null` = device language). An open chat reloads in the new language. */
    @JvmStatic
    public fun setLocale(locale: String?) {
        val current = core ?: return notConfigured("setLocale")
        current.locale = locale?.trim()?.takeIf { it.isNotEmpty() }
        events.forEachSurface { it.reload() }
        scope.launch { current.syncPushQuietly() }
    }

    /** Changes the chat colour scheme; an open chat follows immediately. */
    @JvmStatic
    public fun setTheme(theme: LetsBotTheme) {
        val current = core ?: return notConfigured("setTheme")
        current.theme = theme
        events.forEachSurface { it.applyTheme() }
    }

    internal fun coreOrNull(): ChatCore? = core

    internal val eventHub: EventHub get() = events

    private fun notConfigured(call: String) {
        Log.w(TAG, "LetsBot.$call() called before LetsBot.configure()")
        events.error(LetsBotException(LetsBotErrorCode.NOT_CONFIGURED, "Call LetsBot.configure() first"))
    }

    private fun requireCore(): ChatCore =
        core ?: throw LetsBotException(LetsBotErrorCode.NOT_CONFIGURED, "Call LetsBot.configure() first")

    private fun installLifecycleObserver() {
        if (lifecycleObserverInstalled) return
        lifecycleObserverInstalled = true
        main.post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    refreshUnreadCount()
                }
            })
        }
    }

    private fun appVersion(context: Context): String? = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        info.versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private val COLOR = Regex("^#[0-9A-Fa-f]{6}$")
}
