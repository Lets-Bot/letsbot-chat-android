# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [0.2.0] - 2026-10-09

### Changed
- Edge-to-edge chat screen: `LetsBotChatActivity` draws behind the status bar, navigation bar and display cutout
  with transparent bars and no longer pads the chat. The chat page paints its header colour under the status bar and
  pads its composer above the navigation bar and the keyboard using the insets the SDK passes (`boot({insets})`,
  then `LetsBotHost.setInsets` on rotation, keyboard and bar changes; CSS px, keyboard included in `bottom`).
- `LetsBotChatScreen` (Compose) works the same way: use `Modifier.fillMaxSize()` without `safeDrawingPadding()`;
  the sample app is updated.
- `X-LB-SDK` is now `android/0.2.0`.

### Added
- Handles the page's `chrome` event: status/navigation-bar icon style follows the chat (only for the bars the chat
  sits under) and the window / view / WebView background follows the page background, so no white flashes show on
  open, close, rotation or keyboard animations. The previous bar style is restored when the chat closes.
- The last chrome colours are cached per app and theme (private preferences, colours only) and used before the page
  paints next time; without them a neutral colour (your brand colour for the header, if set) is used.

## [0.1.0] - 2026-10-08

### Added
- `LetsBot.configure(context, appKey, baseUrl, locale, theme, color)`.
- Hosted chat screen: `LetsBot.show(activity)` (`LetsBotChatActivity`) and Jetpack Compose `LetsBotChatScreen()` in the
  optional `net.letsbot:chat-sdk-compose` artifact; `LetsBot.hide()`.
- Verified identity: `suspend LetsBot.identify(userId, identityToken, name, email, phone)` plus a callback variant;
  `LetsBot.logout()`. Switching users on a device always starts a fresh conversation.
- Push: `LetsBot.setPushToken(token)` (FCM), `isLetsBotNotification(data | intent)`,
  `handleNotification(context, data | intent)`, `chatIntent(context)`.
- Unread badge: `LetsBot.unreadCount` (`StateFlow<Int>`), `addUnreadCountListener`, automatic refresh whenever the
  app returns to the foreground, `refreshUnreadCount()`.
- `LetsBot.setContext(map)`, `setLocale(locale)`, `setTheme(theme)`.
- `LetsBotListener` (`onOpen`, `onClose`, `onMessage`, `onUnreadChanged`, `onError`) and typed `LetsBotException`
  / `LetsBotErrorCode`.
- Visitor token stored encrypted with an Android Keystore AES-GCM key.
- Locked-down WebView: navigation limited to the chat page, other http(s) links open in the browser, JavaScript bridge
  accepts messages only from the LetsBot origin, no file/content access, no mixed content; image/PDF picker and
  microphone permission flow for voice notes.
- Consumer R8 rules, sample app, unit tests, Maven Central publishing configuration.
- Install straight from GitHub through JitPack: `com.github.Lets-Bot.letsbot-chat-android:chat-sdk:0.1.0` and
  `com.github.Lets-Bot.letsbot-chat-android:chat-sdk-compose:0.1.0` (`jitpack.yml`).

[0.2.0]: https://github.com/Lets-Bot/letsbot-chat-android/releases/tag/0.2.0
[0.1.0]: https://github.com/Lets-Bot/letsbot-chat-android/releases/tag/0.1.0
