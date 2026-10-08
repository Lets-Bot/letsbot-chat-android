# LetsBot In-App Chat SDK — R8 / ProGuard rules merged into the consuming app.

# The hosted chat page calls window.LetsBotAndroid.postMessage(...) through @JavascriptInterface.
-keepattributes JavascriptInterface
-keepclassmembers class net.letsbot.chat.internal.ChatBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Public error codes are matched by name in apps; keep enum entries intact.
-keepclassmembers enum net.letsbot.chat.LetsBotErrorCode {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
