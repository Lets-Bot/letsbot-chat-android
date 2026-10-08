# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

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

[0.1.0]: https://github.com/Lets-Bot/letsbot-chat-android/releases/tag/v0.1.0
