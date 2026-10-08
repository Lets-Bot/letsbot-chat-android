package net.letsbot.chat

/**
 * An error raised by the LetsBot SDK.
 *
 * @property code typed error code (see [LetsBotErrorCode]).
 * @property httpStatus HTTP status of the failed request, or `null` for client-side errors.
 * @property retryAfterSeconds value of the `Retry-After` header for `slow_down` / `busy`, when present.
 * @property rawCode the code exactly as received from the server (useful when [code] is [LetsBotErrorCode.UNKNOWN]).
 */
public class LetsBotException @JvmOverloads constructor(
    public val code: LetsBotErrorCode,
    message: String = code.wireValue,
    public val httpStatus: Int? = null,
    public val retryAfterSeconds: Long? = null,
    public val rawCode: String? = code.wireValue,
    cause: Throwable? = null,
) : Exception(message, cause) {
    override fun toString(): String =
        "LetsBotException(code=${code.wireValue}, httpStatus=$httpStatus, message=$message)"
}
