package net.letsbot.chat

/** Colour scheme of the hosted chat screen. */
public enum class LetsBotTheme(internal val wireValue: String) {
    /** Follow the device's light / dark setting (updated live while the chat is open). */
    AUTO("auto"),

    /** Always light. */
    LIGHT("light"),

    /** Always dark. */
    DARK("dark"),
}

/** Push provider of the token passed to [LetsBot.setPushToken]. */
public enum class PushProvider(internal val wireValue: String) {
    /** Firebase Cloud Messaging registration token. */
    FCM("fcm"),
}
