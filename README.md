# LetsBot In-App Chat SDK for Android

[![CI](https://github.com/Lets-Bot/letsbot-chat-android/actions/workflows/ci.yml/badge.svg)](https://github.com/Lets-Bot/letsbot-chat-android/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/net.letsbot/chat-sdk)](https://central.sonatype.com/artifact/net.letsbot/chat-sdk)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Add a support chat to your Android app. Your users talk to the same LetsBot AI assistant and team that already answer
your business on WhatsApp and on your website, and every conversation lands in the LetsBot inbox.

- Ready-made chat screen (text, buttons, cards, images/PDF, voice notes, Arabic RTL, dark mode, 4 languages)
- Verified identity: logged-in users keep one conversation across devices and reinstalls
- Push notifications through your own Firebase project, unread badge
- Kotlin-first, Java-friendly, Jetpack Compose support · minSdk 23 · MIT

Docs: <https://letsbot.net/developers/in-app-chat> · Support: <support@letsbot.net>

## Requirements

- Android 6.0 (API 23) or newer, `compileSdk` 36
- An **App Key** from LetsBot panel → Channels → In-App Chat
- Your app's package name (`applicationId`) registered in LetsBot panel → In-App Chat → Platforms

## Install

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// app/build.gradle.kts — pin the version
dependencies {
    implementation("net.letsbot:chat-sdk:0.1.0")
    // Optional, for Jetpack Compose:
    implementation("net.letsbot:chat-sdk-compose:0.1.0")
}
```

### Install directly from GitHub (JitPack)

No Maven Central needed: JitPack builds the tagged release straight from this repository. Add the JitPack repository
and use the JitPack coordinates (group `com.github.Lets-Bot.letsbot-chat-android`):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// app/build.gradle.kts — pin the release tag
dependencies {
    implementation("com.github.Lets-Bot.letsbot-chat-android:chat-sdk:0.1.0")
    // Optional, for Jetpack Compose:
    implementation("com.github.Lets-Bot.letsbot-chat-android:chat-sdk-compose:0.1.0")
}
```

Groovy (`settings.gradle` / `app/build.gradle`):

```groovy
maven { url 'https://jitpack.io' }

implementation 'com.github.Lets-Bot.letsbot-chat-android:chat-sdk:0.1.0'
```

Use either the Maven Central (`net.letsbot`) or the JitPack coordinates, never both in the same app. The code and
the package names (`net.letsbot.chat`) are identical.

The SDK's manifest merges `INTERNET`, `POST_NOTIFICATIONS` and `RECORD_AUDIO` (voice notes, requested only when the
user starts recording). If your app must not declare the microphone permission:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" tools:node="remove" />
```

R8/ProGuard rules ship with the library (`consumer-rules.pro`); nothing to add.

## Quick start

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LetsBot.configure(
            context = this,
            appKey = "YOUR_APP_KEY",
            locale = "en",               // ar | en | es | pt; null = device language
            theme = LetsBotTheme.AUTO,   // AUTO | LIGHT | DARK
            // color = "#0e7c66",        // optional; defaults to the colour set in the panel
        )
    }
}
```

Open the chat:

```kotlin
helpButton.setOnClickListener {
    LetsBot.setContext(mapOf("screen" to "order_details", "orderId" to order.id)) // optional
    LetsBot.show(this)   // this = Activity
}
```

Jetpack Compose:

```kotlin
composable("support") {
    LetsBotChatScreen(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        onClose = { navController.popBackStack() },
    )
}
```

`LetsBot.hide()` closes any open chat. Use `LetsBot.setLocale("ar")` when the user switches language and
`LetsBot.setTheme(...)` if your app has its own dark-mode switch.

### Unread badge

```kotlin
// Coroutines / Flow
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        LetsBot.unreadCount.collect { count -> badge.isVisible = count > 0; badge.text = "$count" }
    }
}

// Or a plain listener (also from Java) — cancel it when the screen goes away
val subscription = LetsBot.addUnreadCountListener { count -> updateBadge(count) }
override fun onDestroy() { subscription.cancel(); super.onDestroy() }
```

The count refreshes automatically whenever the app comes to the foreground and while the chat is open; call
`LetsBot.refreshUnreadCount()` after a LetsBot push arrives in the foreground.

### Events

```kotlin
LetsBot.addListener(object : LetsBotListener {
    override fun onOpen() {}
    override fun onClose() {}
    override fun onMessage(text: String) {}         // new reply while the chat is open — never log it
    override fun onUnreadChanged(count: Int) {}
    override fun onError(error: LetsBotException) { /* see error codes */ }
})
```

Callbacks run on the main thread. Remove listeners with `LetsBot.removeListener(listener)`.

## Identity (logged-in users)

1. **Backend** — add an authenticated endpoint that returns a short-lived token for the current user: a JWT, `HS256`,
   signed with your **Identity Secret** (env `LETSBOT_IDENTITY_SECRET`, never in the app), claims
   `{ "sub": "<stable user id>", "iat": <now>, "exp": <now + 24h> }`, optional `name`, `email`, `phone`.
2. **App** — right after login and on every app start while logged in:

   ```kotlin
   lifecycleScope.launch {
       try {
           LetsBot.identify(userId = user.id, identityToken = api.letsbotIdentityToken(), name = user.name, email = user.email)
       } catch (e: LetsBotException) {
           if (e.code == LetsBotErrorCode.IDENTITY_EXPIRED) { /* fetch a new token and retry */ }
       }
   }
   ```

   Callback / Java variant: `LetsBot.identify(userId, token, name, email, phone) { error -> }`.
3. **Logout** — call `LetsBot.logout()` *before* clearing your own session. The visitor token is deleted at once,
   push for this device is unregistered, and the next chat starts fresh.

Guests can chat anonymously; when they log in, `identify` links their conversation. If a *different* user is identified
on the same device, the SDK discards the previous session first so nobody sees someone else's conversation.

## Push notifications (Firebase Cloud Messaging)

Upload your FCM service-account JSON in LetsBot panel → In-App Chat → Notifications, then:

```kotlin
class MyMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        LetsBot.setPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (LetsBot.isLetsBotNotification(message.data)) {
            LetsBot.refreshUnreadCount()
            // Show your own notification; its PendingIntent can use LetsBot.chatIntent(this).
            return
        }
        // ... your existing handling
    }
}

// On app start, too:
FirebaseMessaging.getInstance().token.addOnSuccessListener { LetsBot.setPushToken(it) }
```

When the app is in the background, Android shows the notification itself and tapping it launches your launcher
activity with the payload as extras:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (LetsBot.isLetsBotNotification(intent)) LetsBot.handleNotification(this, intent)
}
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    if (LetsBot.isLetsBotNotification(intent)) LetsBot.handleNotification(this, intent)
}
```

`handleNotification(context, data)` also accepts the `Map<String, String>` data payload. Both return `false` for
notifications that are not from LetsBot, so your existing handling keeps working. On Android 13+ request
`POST_NOTIFICATIONS` at runtime at a sensible moment.

## Security

- The visitor token is stored encrypted (AES-256-GCM, key held in the Android Keystore), never in plain preferences.
  If the app is restored from a backup on another device the encrypted value cannot be decrypted and the SDK simply
  starts a new session; to skip backing it up at all, exclude `sharedpref/net.letsbot.chat.secure.xml` in your
  backup rules (see the sample app).
- The SDK never logs tokens or message text, and suppresses the chat page's console output.
- The WebView loads only the LetsBot chat page for your App Key. Any other link opens in the browser (http/https
  only). The JavaScript bridge (`window.LetsBotAndroid`) accepts messages only while the LetsBot page is shown.
- File and content access, mixed content, pop-up windows and geolocation are disabled; the file picker is limited to
  images and PDF; microphone access is granted only to the LetsBot page after the user allows it.
- The «Powered by LetsBot» credit is part of the chat screen and cannot be removed.

## Troubleshooting

Errors arrive as `LetsBotException` (`identify`) or through `LetsBotListener.onError`. `error.code`:

| Code | Meaning | Fix |
|---|---|---|
| `not_found` | Unknown App Key, app disabled/deleted, or In-App Chat unavailable for the workspace | Check the App Key; check the app is enabled in the panel |
| `app_not_registered` | This package name isn't registered for the App Key | Add your `applicationId` in panel → In-App Chat → Platforms |
| `invalid_visitor` | Session expired | Handled automatically (new session) |
| `identity_invalid` | Identity token badly signed / malformed, or no Identity Secret configured | Sign with HS256 and the app's Identity Secret; check `sub`, `iat`, `exp` |
| `identity_expired` | Identity token `exp` has passed | Fetch a new token from your backend and call `identify` again |
| `blocked` | The user or their IP was blocked by your team | — |
| `invalid`, `too_long`, `invalid_contact`, `consent_required`, `file_too_big`, `file_type` | Validation | Check the input |
| `slow_down`, `busy` | Rate limited / temporarily busy | Retry after `error.retryAfterSeconds` |
| `not_configured` | An SDK call ran before `LetsBot.configure` | Configure in `Application.onCreate` |
| `network` | Offline or timeout | The chat screen shows a retry button |
| `server`, `invalid_response`, `unknown` | Unexpected server answer | Retry; contact support with `error.rawCode` |

Other checks:
- **Blank chat / "We couldn't load the chat"** — confirm the device has internet and `baseUrl` is reachable.
- **No push** — upload FCM credentials in the panel, press «Send test notification», make sure `setPushToken` runs
  after `configure` and that `POST_NOTIFICATIONS` is granted on Android 13+.
- **Badge never changes** — the visitor needs a session (open the chat once or call `identify`).

## Sample app

See [`example/`](example/) (`./gradlew :sample:installDebug -Pletsbot.appKey=YOUR_APP_KEY`).

## Building

```bash
./gradlew :chat-sdk:testDebugUnitTest :chat-sdk:assembleRelease :sample:assembleDebug lint
```

Publishing to Maven Central uses `com.vanniktech.maven.publish`; credentials and the signing key are read from
`ORG_GRADLE_PROJECT_mavenCentralUsername`, `ORG_GRADLE_PROJECT_mavenCentralPassword`,
`ORG_GRADLE_PROJECT_signingInMemoryKey`, `ORG_GRADLE_PROJECT_signingInMemoryKeyId` and
`ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`, then `./gradlew publishToMavenCentral`.

JitPack builds use [`jitpack.yml`](jitpack.yml) (JDK 17, unsigned `publishToMavenLocal`); with `JITPACK=true` the
artifacts are published under `com.github.Lets-Bot.letsbot-chat-android` instead of `net.letsbot`.

## License

MIT © 2026 LetsBot — see [LICENSE](LICENSE).
