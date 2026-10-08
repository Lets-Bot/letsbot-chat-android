# Sample app

Demonstrates `LetsBot.show`, the Compose `LetsBotChatScreen`, unread badge, identify / logout and notification
hand-off.

```bash
./gradlew :sample:installDebug -Pletsbot.appKey=YOUR_APP_KEY
# optional: -Pletsbot.baseUrl=https://staging.example.com
```

Register `net.letsbot.chat.sample` as an Android package for the App Key in LetsBot panel → Channels → In-App Chat →
Platforms, otherwise the server answers `app_not_registered`.
