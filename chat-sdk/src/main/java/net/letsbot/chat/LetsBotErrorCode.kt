package net.letsbot.chat

/**
 * Typed error codes surfaced by the SDK.
 *
 * Server codes mirror the LetsBot In-App Chat API (`{"error": "<code>"}`); the last group is produced on the device.
 */
public enum class LetsBotErrorCode(
    /** The code as sent by the server (or the SDK-local identifier for client-side errors). */
    public val wireValue: String,
) {
    /** Unknown App Key, app disabled or deleted, workspace paused, or In-App Chat unavailable for the workspace. */
    NOT_FOUND("not_found"),

    /** This app's package name is not registered for the App Key (LetsBot panel → In-App Chat → Platforms). */
    APP_NOT_REGISTERED("app_not_registered"),

    /** The visitor token is missing, invalid or expired. The SDK renews the session automatically. */
    INVALID_VISITOR("invalid_visitor"),

    /** The identity token has a bad signature, is malformed, or no Identity Secret is configured. */
    IDENTITY_INVALID("identity_invalid"),

    /** The identity token's `exp` has passed: fetch a fresh token from your backend and call identify again. */
    IDENTITY_EXPIRED("identity_expired"),

    /** The visitor or their IP address was blocked by the business. */
    BLOCKED("blocked"),

    /** Request validation failed. */
    INVALID("invalid"),

    /** A text field is longer than allowed. */
    TOO_LONG("too_long"),

    /** The e-mail address or phone number is not valid. */
    INVALID_CONTACT("invalid_contact"),

    /** The business requires consent before chatting. */
    CONSENT_REQUIRED("consent_required"),

    /** The uploaded file is too large. */
    FILE_TOO_BIG("file_too_big"),

    /** The uploaded file type is not accepted. */
    FILE_TYPE("file_type"),

    /** Rate limited. Honour [LetsBotException.retryAfterSeconds] when present. */
    SLOW_DOWN("slow_down"),

    /** The chat is temporarily busy (budget reached). Honour [LetsBotException.retryAfterSeconds] when present. */
    BUSY("busy"),

    /** [LetsBot.configure] has not been called yet. */
    NOT_CONFIGURED("not_configured"),

    /** The device is offline or the connection failed / timed out. */
    NETWORK("network"),

    /** The server answered with an unexpected 5xx response. */
    SERVER("server"),

    /** The server answered with a body the SDK could not understand. */
    INVALID_RESPONSE("invalid_response"),

    /** Any other error. Check [LetsBotException.rawCode]. */
    UNKNOWN("unknown"),
    ;

    public companion object {
        /** Maps a wire code to its enum value, or [UNKNOWN] when the code is not known to this SDK version. */
        @JvmStatic
        public fun fromWire(code: String?): LetsBotErrorCode =
            entries.firstOrNull { it.wireValue == code } ?: UNKNOWN
    }
}
