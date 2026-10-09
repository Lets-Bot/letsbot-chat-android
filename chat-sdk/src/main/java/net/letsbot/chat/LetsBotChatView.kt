package net.letsbot.chat

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.RestrictTo
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.letsbot.chat.internal.BridgeMessage
import net.letsbot.chat.internal.ChatBridge
import net.letsbot.chat.internal.ChatChrome
import net.letsbot.chat.internal.ChatCore
import net.letsbot.chat.internal.ChatSurface
import net.letsbot.chat.internal.SafeInsets
import net.letsbot.chat.internal.SystemBars
import net.letsbot.chat.internal.SystemBarsKeeper
import net.letsbot.chat.internal.UrlPolicy
import org.json.JSONObject

/**
 * The hosted chat screen in a locked-down [WebView]. Used by [LetsBotChatActivity] and the Compose
 * `LetsBotChatScreen`; apps should use those (or [LetsBot.show]) instead of this view.
 *
 * Edge-to-edge (API.md §8.1): the view may sit under the status bar, navigation bar, display cutout and keyboard. It
 * works out how much of each overlaps it and passes that to the page (`boot({insets})`, then
 * `LetsBotHost.setInsets`), which pads its header and composer itself. The page's `chrome` event sets the bar icon
 * style (only for the bars the view is under) and the background; the host window's previous bar style comes back
 * when the view is detached.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class LetsBotChatView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** Receives file-chooser / permission / close requests. Set before the view is attached. */
    public var host: LetsBotChatHost? = null

    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val progress = ProgressBar(context).apply { isIndeterminate = true }
    private val errorPanel: View
    private val errorText: TextView
    private var webView: WebView? = null
    private var core: ChatCore? = null
    private var bootedToken: String? = null
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null
    private var loaded = false
    private var destroyed = false
    private var chrome: ChatChrome? = null
    private var chromeTheme: String? = null
    private var chromeFromPage = false
    private var insets: SafeInsets = SafeInsets.ZERO
    private var insetsSent: String? = null
    private var barsKeeper: SystemBarsKeeper? = null
    private val errorPadding = (24 * resources.displayMetrics.density).toInt()

    /** Called with the chat background (ARGB) whenever it changes, e.g. to paint the hosting window. */
    internal var onBackgroundChanged: ((Int) -> Unit)? = null

    init {
        addView(progress, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val padding = (24 * resources.displayMetrics.density).toInt()
        errorText = TextView(context).apply {
            gravity = Gravity.CENTER
            setText(R.string.letsbot_chat_error)
        }
        val retry = Button(context).apply {
            setText(R.string.letsbot_chat_retry)
            setOnClickListener { load() }
        }
        errorPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
            addView(errorText)
            addView(retry, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = padding / 2
            })
            visibility = GONE
        }
        addView(errorPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        contentDescription = context.getString(R.string.letsbot_chat_title)
        setBackgroundColor(currentChrome().backgroundArgb)
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, windowInsets ->
            updateInsets()
            windowInsets // not consumed: siblings / children keep them
        }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateInsets() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (destroyed) return
        LetsBot.eventHub.attach(surface)
        if (barsKeeper == null) barsKeeper = findWindow(context)?.let { SystemBarsKeeper(WindowBars(it)) }
        ViewCompat.requestApplyInsets(this)
        if (!chromeFromPage) chrome = null // configured since construction: pick up the cached colours
        applyChrome(currentChrome())
        if (!loaded) load()
    }

    override fun onDetachedFromWindow() {
        LetsBot.eventHub.detach(surface)
        barsKeeper?.restore()
        super.onDetachedFromWindow()
    }

    /** Releases the WebView. Call from the host's `onDestroy` / `onDispose`. */
    public fun destroy() {
        if (destroyed) return
        destroyed = true
        LetsBot.eventHub.detach(surface)
        barsKeeper?.restore()
        viewScope.cancel()
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        pendingPermission?.deny()
        pendingPermission = null
        releaseWebView()
    }

    private val surface = object : ChatSurface {
        override fun requestClose() {
            host?.closeChat()
        }

        override fun reload() {
            if (!destroyed && loaded) load()
        }

        override fun pushContext(json: String) {
            evaluateOnTrustedPage("window.LetsBotHost&&window.LetsBotHost.setContext($json);")
        }

        override fun applyTheme() {
            this@LetsBotChatView.applyTheme()
        }
    }

    private fun applyTheme() {
        refreshChromeForTheme()
        evaluateOnTrustedPage("window.LetsBotHost&&window.LetsBotHost.setTheme(${JSONObject.quote(resolvedTheme())});")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (core?.theme == LetsBotTheme.AUTO) applyTheme()
    }

    private fun load() {
        if (destroyed) return
        loaded = true
        showLoading()
        val current = LetsBot.coreOrNull()
        if (current == null) {
            showError(LetsBotException(LetsBotErrorCode.NOT_CONFIGURED, "Call LetsBot.configure() first"))
            return
        }
        core = current
        viewScope.launch {
            try {
                current.visitorToken()
                val web = webView ?: createWebView(current).also { webView = it }
                bootedToken = null
                web.loadUrl(current.uiUrl(resolvedTheme()))
            } catch (e: LetsBotException) {
                showError(e)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(core: ChatCore): WebView {
        val web = WebView(context)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
            if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.isVerticalScrollBarEnabled = false
        web.setBackgroundColor(currentChrome().backgroundArgb)
        web.webViewClient = Client(core.urlPolicy)
        web.webChromeClient = Chrome(core.urlPolicy)
        web.setDownloadListener { url, _, _, _, _ -> openExternal(url) }
        web.addJavascriptInterface(
            ChatBridge { raw -> web.post { onBridgeMessage(web, raw) } },
            ChatBridge.NAME,
        )
        addView(web, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        web.visibility = INVISIBLE
        return web
    }

    private fun releaseWebView() {
        val web = webView ?: return
        webView = null
        removeView(web)
        web.stopLoading()
        web.removeJavascriptInterface(ChatBridge.NAME)
        web.destroy()
    }

    private fun onBridgeMessage(web: WebView, raw: String) {
        val current = core ?: return
        if (destroyed || web !== webView || !current.urlPolicy.isChatUi(web.url)) return
        when (val message = BridgeMessage.parse(raw) ?: return) {
            BridgeMessage.Ready -> viewScope.launch {
                try {
                    boot(current, current.visitorToken())
                } catch (e: LetsBotException) {
                    showError(e)
                }
            }
            BridgeMessage.TokenInvalid -> viewScope.launch {
                try {
                    boot(current, current.renewSession(bootedToken))
                } catch (e: LetsBotException) {
                    showError(e)
                }
            }
            BridgeMessage.Close -> host?.closeChat()
            is BridgeMessage.OpenUrl -> openExternal(message.url)
            is BridgeMessage.Unread -> LetsBot.eventHub.setUnread(message.count)
            is BridgeMessage.Message -> LetsBot.eventHub.message(message.text)
            is BridgeMessage.Error -> LetsBot.eventHub.error(
                LetsBotException(LetsBotErrorCode.fromWire(message.code), message.code, rawCode = message.code),
            )
            is BridgeMessage.Chrome -> onPageChrome(current, message)
        }
    }

    private fun boot(core: ChatCore, token: String) {
        bootedToken = token
        updateInsets(send = false)
        val css = insets.toCssJson(resources.displayMetrics.density)
        insetsSent = css.toString()
        val payload = core.bootPayload(token, resolvedTheme(), css).toString()
        evaluateOnTrustedPage("window.LetsBotHost&&window.LetsBotHost.boot($payload);")
    }

    // region Edge-to-edge: insets and chrome

    /** Recomputes how much of the system bars / cutout / keyboard overlaps this view and tells the page. */
    private fun updateInsets(send: Boolean = true) {
        val root = ViewCompat.getRootWindowInsets(this) ?: return
        val bars = root.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = root.getInsets(WindowInsetsCompat.Type.ime())
        val location = IntArray(2)
        getLocationInWindow(location)
        val window = rootView
        val next = SafeInsets.overlap(
            barsTop = bars.top, barsBottom = bars.bottom, barsLeft = bars.left, barsRight = bars.right,
            imeBottom = ime.bottom,
            viewLeft = location[0], viewTop = location[1], viewWidth = width, viewHeight = height,
            windowWidth = window.width, windowHeight = window.height,
        )
        if (next != insets) {
            insets = next
            errorPanel.setPadding(
                errorPadding + next.left, errorPadding + next.top, errorPadding + next.right, errorPadding + next.bottom,
            )
            applyChrome(currentChrome())
        }
        if (!send || bootedToken == null) return
        val css = insets.toCssJson(resources.displayMetrics.density).toString()
        if (css == insetsSent) return
        insetsSent = css
        evaluateOnTrustedPage("window.LetsBotHost&&window.LetsBotHost.setInsets($css);")
    }

    /** Chrome for the current theme: reported by the page, else cached from an earlier session, else neutral. */
    private fun currentChrome(): ChatChrome {
        val theme = resolvedTheme()
        chrome?.takeIf { chromeTheme == theme }?.let { return it }
        val current = core ?: LetsBot.coreOrNull()
        val initial = current?.let { LetsBot.chromeCache?.load(it.baseUrl, it.appKey, theme) }
            ?: ChatChrome.neutral(dark = theme == "dark", brandColor = current?.color)
        chrome = initial
        chromeTheme = theme
        chromeFromPage = false
        return initial
    }

    private fun refreshChromeForTheme() {
        val before = chrome
        val now = currentChrome()
        if (now != before) applyChrome(now)
    }

    private fun onPageChrome(core: ChatCore, event: BridgeMessage.Chrome) {
        val theme = resolvedTheme()
        val updated = currentChrome().merged(event)
        chrome = updated
        chromeTheme = theme
        chromeFromPage = true
        applyChrome(updated)
        LetsBot.chromeCache?.save(updated, core.baseUrl, core.appKey, theme)
    }

    private fun applyChrome(chrome: ChatChrome) {
        val background = chrome.backgroundArgb
        setBackgroundColor(background)
        webView?.setBackgroundColor(background)
        onBackgroundChanged?.invoke(background)
        if (isAttachedToWindow) {
            barsKeeper?.apply(chrome, statusBar = insets.top > 0, navigationBar = insets.bottom > 0)
        }
    }

    private class WindowBars(window: Window) : SystemBars {
        private val controller = WindowInsetsControllerCompat(window, window.decorView)

        override var lightStatusBars: Boolean
            get() = controller.isAppearanceLightStatusBars
            set(value) {
                controller.isAppearanceLightStatusBars = value
            }

        override var lightNavigationBars: Boolean
            get() = controller.isAppearanceLightNavigationBars
            set(value) {
                controller.isAppearanceLightNavigationBars = value
            }
    }

    // endregion

    /** Runs [script] only while the WebView shows the LetsBot `ui` page (the token never reaches another origin). */
    private fun evaluateOnTrustedPage(script: String) {
        val web = webView ?: return
        val current = core ?: return
        if (destroyed || !current.urlPolicy.isChatUi(web.url)) return
        web.evaluateJavascript(script, null)
    }

    private fun resolvedTheme(): String = when (core?.theme ?: LetsBotTheme.AUTO) {
        LetsBotTheme.LIGHT -> "light"
        LetsBotTheme.DARK -> "dark"
        LetsBotTheme.AUTO -> {
            val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
        }
    }

    private fun showLoading() {
        errorPanel.visibility = GONE
        progress.visibility = VISIBLE
    }

    private fun showContent() {
        progress.visibility = GONE
        errorPanel.visibility = GONE
        webView?.visibility = VISIBLE
    }

    private fun showError(error: LetsBotException) {
        progress.visibility = GONE
        webView?.visibility = INVISIBLE
        errorText.setText(
            when (error.code) {
                LetsBotErrorCode.NETWORK -> R.string.letsbot_chat_error_offline
                else -> R.string.letsbot_chat_error
            },
        )
        errorPanel.visibility = VISIBLE
        LetsBot.eventHub.error(error)
    }

    private fun openExternal(url: String?) {
        if (!UrlPolicy.isExternalWebUrl(url)) return
        val intent = Intent(Intent.ACTION_VIEW, url!!.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No browser installed: nothing sensible to do.
        }
    }

    private inner class Client(private val policy: UrlPolicy) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            handleNavigation(request.url.toString(), request.isForMainFrame)

        @Deprecated("Deprecated in Java")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = handleNavigation(url, true)

        /** Only the `ui` page may load in the main frame; any other http(s) link opens in the browser. */
        private fun handleNavigation(url: String, mainFrame: Boolean): Boolean {
            if (policy.isChatUi(url)) return false
            if (!mainFrame) return !policy.isTrustedOrigin(url)
            openExternal(url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (!policy.isChatUi(url)) view.stopLoading()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (policy.isChatUi(url) && errorPanel.visibility != VISIBLE) showContent()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                showError(LetsBotException(LetsBotErrorCode.NETWORK, "Chat page failed to load"))
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (!request.isForMainFrame) return
            val status = response.statusCode
            val code = when {
                status == 404 -> LetsBotErrorCode.NOT_FOUND
                status == 403 -> LetsBotErrorCode.APP_NOT_REGISTERED
                status >= 500 -> LetsBotErrorCode.SERVER
                else -> LetsBotErrorCode.UNKNOWN
            }
            showError(LetsBotException(code, "Chat page returned HTTP $status", httpStatus = status))
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view === webView) releaseWebView() else view.destroy()
            showError(LetsBotException(LetsBotErrorCode.UNKNOWN, "Chat renderer stopped"))
            return true
        }
    }

    private inner class Chrome(private val policy: UrlPolicy) : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = filePathCallback
            val requested = fileChooserParams.acceptTypes.orEmpty()
                .flatMap { it.split(',') }
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
            val mimeTypes = requested.filter { type -> ALLOWED_MIME.any { allowed -> mimeMatches(type, allowed) } }
                .ifEmpty { ALLOWED_MIME }
                .toTypedArray()
            val intent = Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(if (mimeTypes.size == 1) mimeTypes[0] else "*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
            val started = host?.launchFileChooser(Intent.createChooser(intent, null)) { uris ->
                pendingFileCallback?.onReceiveValue(uris)
                pendingFileCallback = null
            } ?: false
            if (!started) {
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = null
            }
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
            if (!wantsAudio || !policy.isTrustedOrigin(request.origin.toString())) {
                request.deny()
                return
            }
            val grant = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                request.grant(grant)
                return
            }
            val currentHost = host
            if (currentHost == null) {
                request.deny()
                return
            }
            pendingPermission?.deny()
            pendingPermission = request
            currentHost.requestRecordAudioPermission { granted ->
                val pending = pendingPermission ?: return@requestRecordAudioPermission
                pendingPermission = null
                if (granted) pending.grant(grant) else pending.deny()
            }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if (pendingPermission === request) pendingPermission = null
        }

        // Keep page console output (which may contain conversation text) out of logcat.
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true
    }

    public companion object {
        private fun findWindow(context: Context): Window? {
            var current: Context? = context
            while (current is ContextWrapper) {
                if (current is Activity) return current.window
                current = current.baseContext
            }
            return null
        }

        private val ALLOWED_MIME = listOf("image/*", "application/pdf")

        private fun mimeMatches(requested: String, allowed: String): Boolean = when {
            requested == allowed -> true
            allowed.endsWith("/*") -> requested.startsWith(allowed.removeSuffix("*"))
            else -> false
        }

        /** Turns a document-picker result into the URIs expected by the WebView file chooser. */
        @JvmStatic
        public fun parseFileChooserResult(resultCode: Int, data: Intent?): Array<Uri>? {
            if (resultCode != Activity.RESULT_OK || data == null) return null
            val clip = data.clipData
            if (clip != null && clip.itemCount > 0) {
                val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                return if (uris.isEmpty()) null else uris.toTypedArray()
            }
            return data.data?.let { arrayOf(it) }
        }
    }
}
