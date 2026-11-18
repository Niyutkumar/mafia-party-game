# Keep the methods JavaScript calls through window.AndroidBridge
-keepattributes JavascriptInterface
-keepclassmembers class com.mafia.game.WebAppInterface {
   @android.webkit.JavascriptInterface <methods>;
}
