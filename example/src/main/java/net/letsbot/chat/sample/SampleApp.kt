package net.letsbot.chat.sample

import android.app.Application
import net.letsbot.chat.LetsBot
import net.letsbot.chat.LetsBotTheme

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // App Key and base URL come from Gradle properties (see example/build.gradle.kts).
        LetsBot.configure(
            context = this,
            appKey = BuildConfig.LETSBOT_APP_KEY,
            baseUrl = BuildConfig.LETSBOT_BASE_URL,
            locale = null, // follow the device language
            theme = LetsBotTheme.AUTO,
        )
        // With Firebase Messaging in your app:
        // FirebaseMessaging.getInstance().token.addOnSuccessListener { LetsBot.setPushToken(it) }
    }
}
