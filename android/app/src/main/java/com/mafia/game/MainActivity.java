package com.mafia.game;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

/**
 * The only screen of the app: a full-screen WebView that shows the Mafia game
 * (app/src/main/assets/index.html) and gives it a few native Android features
 * through the "AndroidBridge" JavaScript object.
 */
public class MainActivity extends Activity {

    private static final String GAME_URL = "file:///android_asset/index.html";
    private static final int BACKGROUND = 0xFF141017; // the game's "ink" colour

    private FrameLayout root;
    private WebView webView;
    private WebAppInterface bridge;
    private AlertDialog exitDialog;
    private OnBackInvokedCallback backCallback; // Android 13+ only

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep the screen awake while the phone is passed around the table.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(BACKGROUND);
        setContentView(root);
        keepGameInSafeArea();

        bridge = new WebAppInterface(this);
        createWebView();
        hideSystemUI();
        registerBackHandler();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView() {
        // Nobody can attach Chrome DevTools to the release app.
        WebView.setWebContentsDebuggingEnabled(false);

        webView = new WebView(this);
        webView.setBackgroundColor(BACKGROUND);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);                 // the game is JavaScript
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false); // sound effects can play
        settings.setUseWideViewPort(true);                   // honour <meta name="viewport">
        settings.setLoadWithOverviewMode(false);
        settings.setTextZoom(100);                           // keep the layout as designed

        // Lock-down: the page can only see its own bundled file.
        settings.setAllowFileAccess(false);    // no files on the phone (the bundled game still loads)
        settings.setAllowContentAccess(false); // no other apps' data (content:// links)
        settings.setGeolocationEnabled(false); // no location
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false); // no pop-up windows
        blockFileUrlAccess(settings);

        // Native features for the page: window.AndroidBridge.speak(), .vibrate(), ...
        webView.addJavascriptInterface(bridge, "AndroidBridge");
        webView.setWebViewClient(new GameWebViewClient(this));
        webView.setWebChromeClient(new WebChromeClient());

        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.loadUrl(GAME_URL);
    }

    /** Old switches (already off by default) that would let a file:// page read other files. */
    @SuppressWarnings("deprecation")
    private static void blockFileUrlAccess(WebSettings settings) {
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
    }

    /** Decides what may load: only the bundled game ever opens inside the app. */
    private static class GameWebViewClient extends WebViewClient {
        private final MainActivity activity;

        GameWebViewClient(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (GAME_URL.equals(uri.toString())) {
                return false; // the game itself
            }
            String scheme = uri.getScheme();
            if ("https".equals(scheme) || "http".equals(scheme)) {
                try { // a web link opens in the phone's browser, never inside the game
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, uri)
                            .addCategory(Intent.CATEGORY_BROWSABLE));
                } catch (Exception ignored) {
                    // no browser installed: ignore the tap
                }
            }
            return true; // anything else (other files, intent:, javascript:, ...) is blocked
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            activity.restartAfterWebViewCrash(view);
            return true; // handled: Android must not close the whole app
        }
    }

    /** The WebView's engine crashed or was stopped to free memory: throw it away and start fresh. */
    void restartAfterWebViewCrash(WebView dead) {
        root.removeView(dead);
        dead.destroy();
        if (dead == webView) {
            webView = null;
        }
        recreate();
    }

    /**
     * Android 15+ draws every app edge to edge, under the camera cut-out and the system bars.
     * Pad the game so nothing hides behind them, and lift it above the keyboard.
     */
    private void keepGameInSafeArea() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // Android 11+
            root.setOnApplyWindowInsetsListener(MainActivity::applySafeArea);
        }
    }

    private static WindowInsets applySafeArea(View view, WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout()
                    | WindowInsets.Type.ime());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return WindowInsets.CONSUMED; // already handled here, so the page doesn't pad twice
        }
        return insets;
    }

    /** Full-screen "immersive" mode: hides the status bar and navigation bar. */
    @SuppressWarnings("deprecation")
    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // Android 11+
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else { // Android 7 - 10
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    // ---------------------------------------------------------------- Back button / gesture

    /** Android 13+: Back arrives here (the "predictive back" system used by Android 16). */
    private void registerBackHandler() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback = this::handleBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
    }

    /** Android 7 - 12 still use the old Back callback. */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        handleBack();
    }

    /** Back first closes an open sheet in the game; otherwise it asks before leaving. */
    private void handleBack() {
        if (webView == null) {
            showExitDialog();
            return;
        }
        webView.evaluateJavascript("!!(window.mafiaHandleBack && window.mafiaHandleBack())",
                result -> {
                    if (!"true".equals(result)) {
                        showExitDialog();
                    }
                });
    }

    /** Ask before leaving so a match isn't lost by accident. */
    private void showExitDialog() {
        if (isFinishing() || (exitDialog != null && exitDialog.isShowing())) {
            return;
        }
        exitDialog = new AlertDialog.Builder(this)
                .setTitle("Leave Mafia Game?")
                .setMessage("Are you sure you want to exit the app? Any active match progress will be lost.")
                .setPositiveButton("Exit", (dialog, which) -> finish())
                .setNegativeButton("Stay", null)
                .show();
    }

    // ---------------------------------------------------------------- Lifecycle

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI(); // re-hide bars after a dialog or notification shade
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
        hideSystemUI();
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        bridge.stopSpeaking(); // the narrator goes quiet when the app is hidden
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (backCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        if (exitDialog != null) {
            exitDialog.dismiss();
        }
        bridge.shutdown();
        if (webView != null) {
            root.removeView(webView);
            webView.removeJavascriptInterface("AndroidBridge");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
