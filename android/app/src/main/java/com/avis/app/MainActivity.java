package com.avis.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.getcapacitor.BridgeActivity;
import com.google.firebase.messaging.FirebaseMessaging;

import androidx.annotation.Nullable;
import androidx.core.splashscreen.SplashScreen;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.Context;

import android.webkit.WebResourceRequest;
import android.net.Uri;
import android.content.ActivityNotFoundException;

import org.json.JSONObject;

import java.util.Locale;

public class MainActivity extends BridgeActivity {
    private static final String TAG = "AVIS_MAIN";
    private WebView webView;
    public static boolean isActive = false;
    private static long lastWebViewReloadTime = 0;
    private static final long WEBVIEW_RELOAD_THROTTLE_MS = 3000; // 3 seconds
    private BroadcastReceiver webViewRefreshReceiver;
    private boolean isWebViewReady = false;
    private static final String REFRESH_ACTION = "com.avis.app.REFRESH_WEBVIEW";
    private boolean receiverRegistered = false; // track registration state
    private static final Object PUSH_LOCK = new Object();
    private static String lastProcessedPushKey = null;
    private static String pendingPushKey = null;
    private static String pendingPushSource = null;
    private static boolean isAudioAlertPlaying = false;
    private static boolean isAudioStateKnown = false;
    private static long lastAudioStateUpdateMs = 0;
    private static final long AUDIO_FLAG_POLL_INTERVAL_MS = 2000L;
    private static final long AUDIO_STATE_STALE_THRESHOLD_MS = 1200L;
    private Handler audioStateHandler;
    private Runnable audioStateRunnable;
    private Handler mainHandler;
    private View splashOverlay;
    private boolean splashOverlayRemoved = false;
    private boolean splashOverlayAttached = false;

    // Simple safe reload wrapper
    private void safeReloadWebView() {
        try {
            if (webView == null) {
                Log.w(TAG, "safeReloadWebView: webView is null, skipping");
                return;
            }
            triggerWebViewReload("safeReloadWebView");
        } catch (Exception e) {
            Log.e(TAG, "safeReloadWebView: unexpected error", e);
        }
    }

    private void safeRegisterReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ requires explicit exported/not exported flag
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // API 26+ supports 3-arg overload; pass 0 as flags for legacy behavior
                registerReceiver(receiver, filter, 0);
            } else {
                // Older devices — use legacy 2-arg call
                registerReceiver(receiver, filter);
            }
            receiverRegistered = true;
            Log.d(TAG, "✅ safeRegisterReceiver: receiver registered");
        } catch (SecurityException se) {
            // Log but don't crash the app
            Log.e(TAG, "⚠️ safeRegisterReceiver: SecurityException when registering receiver", se);
        } catch (Exception e) {
            Log.e(TAG, "❌ safeRegisterReceiver: unexpected error", e);
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {

        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);

        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            Log.e(TAG, "Uncaught exception in thread " + thread.getName(), ex);
        });

        super.onCreate(savedInstanceState);

        mainHandler = new Handler(Looper.getMainLooper());

        setupSplashOverlay();
        if (splashScreen != null) {
            splashScreen.setKeepOnScreenCondition(() -> !splashOverlayAttached);
        }

        // 🔍 Print current FCM token at startup
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                Log.d("AVIS_MAIN", "📲 Current FCM token on startup: " + task.getResult());
            } else {
                Log.e("AVIS_MAIN", "⚠️ Unable to fetch FCM token at startup", task.getException());
            }
        });

        // Prepare IntentFilter (we'll register after WebView init)
        IntentFilter filter = new IntentFilter(REFRESH_ACTION);

        // Initialize WebView and JS bridge
        webView = (WebView) bridge.getWebView();
        webView.getSettings().setJavaScriptEnabled(true);
        webView.addJavascriptInterface(new JsBridge(), "AndroidBridge");

        // Set WebViewClient (includes external-link handling and onPageFinished)
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String url = uri.toString();
                Log.d(TAG, "🌐 Intercepted URL: " + url);

                // Allow only your PWA domain inside WebView
                if (url.contains("avis-srs.lovable.app")) {
                    return false; // allow normal in-app navigation
                }

                // Handle special schemes
                if (url.startsWith("tel:")) {
                    Intent dialIntent = new Intent(Intent.ACTION_DIAL, uri);
                    view.getContext().startActivity(dialIntent);
                    return true;
                }

                if (url.startsWith("mailto:")) {
                    Intent emailIntent = new Intent(Intent.ACTION_SENDTO, uri);
                    view.getContext().startActivity(emailIntent);
                    return true;
                }

                // Everything else: open externally
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                try {
                    // Try to open in native app (WhatsApp, dialer, etc.)
                    view.getContext().startActivity(intent);
                    Log.d(TAG, "✅ Opened external link in native app: " + url);
                } catch (ActivityNotFoundException e) {
                    // Fallback: open in default browser
                    try {
                        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        view.getContext().startActivity(browserIntent);
                        Log.d(TAG, "🌍 Opened link in system browser: " + url);
                    } catch (Exception ex) {
                        Log.e(TAG, "❌ Unable to open URL externally: " + url, ex);
                    }
                }

                // Returning true prevents WebView from navigating away
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {

                super.onPageFinished(view, url);
                Log.d(TAG, "✅ WebView loaded: " + url);

                isWebViewReady = true;
                Log.d(TAG, "🌐 WebView is now ready for refresh broadcasts");

                injectJwtReader(webView);
                startJwtRefreshMonitor(webView);
                injectAudioFlagReader(webView);
                startAudioFlagMonitor(webView);
                handleIntentPush(getIntent(), false);
                processPendingPushIfNeeded();
                removeSplashOverlay();

            }
        });

        // Define receiver (must be defined before registration)
        webViewRefreshReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!REFRESH_ACTION.equals(intent.getAction())) return;

                Log.d(TAG, "Broadcast received: REFRESH_WEBVIEW");

                String payload = intent.getStringExtra("native_push_data");
                String pushKey = intent.getStringExtra("native_push_id");
                handlePushPayloadInternal(pushKey, payload, "broadcast", true);
            }
        };

        // Register receiver safely AFTER it has been created and after WebView initialized
        safeRegisterReceiver(webViewRefreshReceiver, filter);

        // Schedule a daily background FCM sync
        PeriodicWorkRequest resyncWork = new PeriodicWorkRequest.Builder(FcmResyncWorker.class, 1, TimeUnit.DAYS).build();

        WorkManager.getInstance(this).enqueueUniquePeriodicWork("daily_fcm_resync", ExistingPeriodicWorkPolicy.KEEP, resyncWork);

        Log.d(TAG, "📅 Daily FCM resync scheduled (once every 24h)");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d(TAG, "📬 onNewIntent received (likely from FCM)");

        // store the intent
        setIntent(intent);

        try {
            String payload = null;
            if (intent != null && intent.hasExtra("native_push_data")) {
                payload = intent.getStringExtra("native_push_data");
                Log.d(TAG, "onNewIntent payload: " + payload);
            }

            // If webview ready, handle immediately under try/catch
            if (isWebViewReady && webView != null) {
                try {
                    handleIntentPush(intent, false);
                    Log.d(TAG, "onNewIntent: handleIntentPush executed immediately");
                } catch (Exception e) {
                    Log.e(TAG, "onNewIntent: handleIntentPush failed", e);
                }
            } else {
                // Defer handling to allow WebView to initialize
                Log.d(TAG, "onNewIntent: deferring handleIntentPush until WebView ready");
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        if (isWebViewReady && webView != null) {
                            handleIntentPush(getIntent(), false);
                            Log.d(TAG, "onNewIntent: deferred handleIntentPush executed");
                        } else {
                            Log.e(TAG, "onNewIntent: WebView not ready at deferred time; skipping");
                        }
                    } catch (Exception ex) {
                        Log.e(TAG, "onNewIntent: deferred handleIntentPush threw", ex);
                    }
                }, 800);
            }
        } catch (Exception e) {
            Log.e(TAG, "onNewIntent: unexpected error", e);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        isActive = true;

        // Print FCM token every time app comes to foreground
        com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                Log.d("AVIS_MAIN", "📲 Current FCM token on resume: " + task.getResult());
            } else {
                Log.e("AVIS_MAIN", "⚠️ Unable to fetch FCM token on resume", task.getException());
            }
        });

        // ✅ Stop any ringing tone when app comes to foreground
        try {
            com.avis.app.MyFirebaseMessagingService.stopSound();
            Log.d(TAG, "🔇 Sound stopped on app foreground");
        } catch (Exception e) {
            Log.e(TAG, "❌ Error stopping sound on resume", e);
        }

        injectAudioFlagReader(webView);
        handleIntentPush(getIntent(), false);
        processPendingPushIfNeeded();
    }

    @Override
    public void onPause() {
        super.onPause();
        isActive = false;
    }

    public static boolean isActive() {
        return isActive;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (receiverRegistered) {
                unregisterReceiver(webViewRefreshReceiver);
                Log.d(TAG, "🧹 Receiver unregistered in onDestroy");
            } else {
                Log.w(TAG, "Receiver not registered, skipping unregister");
            }
        } catch (Exception e) {
            Log.w(TAG, "Receiver already unregistered or null", e);
        }

        if (audioStateHandler != null && audioStateRunnable != null) {
            audioStateHandler.removeCallbacks(audioStateRunnable);
        }
    }

    /**
     * Handles reload when an FCM push is received.
     * Prevents multiple reloads within 5 seconds.
     */
    private void handleIntentPush(Intent intent, boolean allowImmediateReload) {
        if (intent == null) return;

        String dataJson = intent.getStringExtra("native_push_data");
        String pushKey = intent.getStringExtra("native_push_id");
        handlePushPayloadInternal(pushKey, dataJson, "intent", allowImmediateReload);
    }

    private void triggerWebViewReload(String source) {
        if (webView == null) {
            Log.w(TAG, "triggerWebViewReload: webView is null");
            return;
        }
        lastWebViewReloadTime = System.currentTimeMillis();
        webView.post(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    webView.evaluateJavascript("location.reload()", (ValueCallback<String>) value -> Log.d(TAG, "✅ WebView reloaded via " + source));
                } else {
                    webView.loadUrl("javascript:location.reload()");
                }
            } catch (Exception e) {
                Log.e(TAG, "triggerWebViewReload: reload failed (" + source + ")", e);
            }
        });
    }

    private void handlePushPayloadInternal(String pushKey, String payload, String source, boolean allowImmediateReload) {
        String normalizedKey = (pushKey != null && !pushKey.trim().isEmpty())
                ? pushKey.trim()
                : payload != null ? payload.trim() : null;

        if (normalizedKey == null || normalizedKey.isEmpty()) {
            Log.d(TAG, "handlePushPayloadInternal: no key or payload provided (" + source + ")");
            return;
        }

        Log.d(TAG, "handlePushPayloadInternal: key=" + normalizedKey + ", source=" + source + ", allowImmediate=" + allowImmediateReload);

        boolean audioStateFresh;
        synchronized (PUSH_LOCK) {
            audioStateFresh = isAudioStateKnown && (System.currentTimeMillis() - lastAudioStateUpdateMs) <= AUDIO_STATE_STALE_THRESHOLD_MS;
        }

        if (!audioStateFresh) {
            Log.d(TAG, "handlePushPayloadInternal: audio state stale, requesting refresh");
            synchronized (PUSH_LOCK) {
                pendingPushKey = normalizedKey;
                pendingPushSource = source;
            }
            if (webView != null) {
                injectAudioFlagReader(webView);
            }
            Handler handler = mainHandler != null ? mainHandler : new Handler(Looper.getMainLooper());
            handler.postDelayed(this::processPendingPushIfNeeded, 400);
            return;
        }

        boolean shouldReloadNow;
        boolean canReloadNow = allowImmediateReload && isWebViewReady && webView != null;
        synchronized (PUSH_LOCK) {
            if (normalizedKey.equals(lastProcessedPushKey)) {
                Log.d(TAG, "handlePushPayloadInternal: payload already processed (" + source + "), lastProcessed=" + lastProcessedPushKey);
                return;
            }

            if (isAudioAlertPlaying) {
                pendingPushKey = null;
                pendingPushSource = null;
                lastProcessedPushKey = normalizedKey;
                Log.d(TAG, "handlePushPayloadInternal: audio alert playing, skipping reload for key=" + normalizedKey);
                return;
            }

            if (canReloadNow) {
                lastProcessedPushKey = normalizedKey;
                pendingPushKey = null;
                pendingPushSource = null;
                Log.d(TAG, "handlePushPayloadInternal: will reload immediately for key=" + normalizedKey);
                shouldReloadNow = true;
            } else {
                pendingPushKey = normalizedKey;
                pendingPushSource = source;
                Log.d(TAG, "handlePushPayloadInternal: deferring reload, pendingKey=" + pendingPushKey);
                shouldReloadNow = false;
            }
        }

        if (shouldReloadNow) {
            Log.d(TAG, "handlePushPayloadInternal: triggering reload (" + source + ")");
            triggerWebViewReload(source);
        } else if (allowImmediateReload) {
            Log.w(TAG, "handlePushPayloadInternal: WebView not ready, deferring (" + source + ")");
            new Handler(Looper.getMainLooper()).postDelayed(this::processPendingPushIfNeeded, 1500);
        } else {
            Log.w(TAG, "handlePushPayloadInternal: deferring until activity active (" + source + ")");
        }
    }

    private void processPendingPushIfNeeded() {
        String keyToProcess = null;
        String sourceToUse = null;
        boolean retryNeeded = false;
        boolean audioPlaying;
        boolean webReady;
        boolean audioStateFresh;

        synchronized (PUSH_LOCK) {
            if (pendingPushKey == null) {
                Log.d(TAG, "processPendingPushIfNeeded: no pending key");
                return;
            }

            long now = System.currentTimeMillis();
            audioStateFresh = isAudioStateKnown && (now - lastAudioStateUpdateMs) <= AUDIO_STATE_STALE_THRESHOLD_MS;
            audioPlaying = isAudioAlertPlaying;
            webReady = isWebViewReady && webView != null;

            if (audioPlaying) {
                Log.d(TAG, "processPendingPushIfNeeded: audio playing, clearing pending refresh");
                pendingPushKey = null;
                pendingPushSource = null;
                return;
            }

            if (!audioStateFresh || !webReady) {
                retryNeeded = true;
            } else if (pendingPushKey.equals(lastProcessedPushKey)) {
                pendingPushKey = null;
                pendingPushSource = null;
            } else {
                keyToProcess = pendingPushKey;
                sourceToUse = pendingPushSource != null ? pendingPushSource : "pending";
                pendingPushKey = null;
                pendingPushSource = null;
                lastProcessedPushKey = keyToProcess;
            }
        }

        if (retryNeeded) {
            Log.d(TAG, "processPendingPushIfNeeded: deferring reload (audioFresh=" + audioStateFresh + ", webViewReady=" + (isWebViewReady && webView != null) + ")");
            Handler handler = mainHandler != null ? mainHandler : new Handler(Looper.getMainLooper());
            handler.postDelayed(this::processPendingPushIfNeeded, 500);
            if (!audioStateFresh && webView != null) {
                injectAudioFlagReader(webView);
            }
            return;
        }

        if (keyToProcess == null) {
            Log.d(TAG, "processPendingPushIfNeeded: nothing to process after checks");
            return;
        }

        Log.d(TAG, "processPendingPushIfNeeded: executing deferred reload for key=" + keyToProcess + " from " + sourceToUse);
        triggerWebViewReload(sourceToUse != null ? sourceToUse : "pending");
    }

    private void injectAudioFlagReader(WebView targetWebView) {
        if (targetWebView == null) {
            Log.w(TAG, "injectAudioFlagReader: webView is null");
            return;
        }
        Handler handler = mainHandler != null ? mainHandler : new Handler(Looper.getMainLooper());
        handler.post(() -> {
            try {
                final String script = "(() => { try { const raw = localStorage.getItem('isAudioAlertPlaying'); const value = raw === null ? 'false' : String(raw); if (window.AndroidBridge && window.AndroidBridge.receiveAudioState) { window.AndroidBridge.receiveAudioState(value); } } catch (e) { console.error('Audio flag read error', e); } })();";
                targetWebView.evaluateJavascript(script, null);
            } catch (Exception e) {
                Log.e(TAG, "injectAudioFlagReader: execution failed", e);
            }
        });
    }

    private void startAudioFlagMonitor(WebView targetWebView) {
        if (targetWebView == null) {
            Log.w(TAG, "startAudioFlagMonitor: webView is null");
            return;
        }

        if (audioStateHandler == null) {
            audioStateHandler = new Handler(Looper.getMainLooper());
        }
        if (audioStateRunnable != null) {
            audioStateHandler.removeCallbacks(audioStateRunnable);
        }

        audioStateRunnable = new Runnable() {
            @Override
            public void run() {
                injectAudioFlagReader(targetWebView);
                if (audioStateHandler != null) {
                    audioStateHandler.postDelayed(this, AUDIO_FLAG_POLL_INTERVAL_MS);
                }
            }
        };
        audioStateHandler.postDelayed(audioStateRunnable, AUDIO_FLAG_POLL_INTERVAL_MS);
    }

    private void setupSplashOverlay() {
        ViewGroup root = findViewById(android.R.id.content);
        if (root == null) {
            Log.w(TAG, "setupSplashOverlay: root view is null");
            return;
        }
        splashOverlayRemoved = false;
        if (splashOverlay != null) {
            root.removeView(splashOverlay);
        }
        splashOverlay = getLayoutInflater().inflate(R.layout.native_splash, root, false);
        ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        root.addView(splashOverlay, params);
        splashOverlayAttached = true;
        if (mainHandler != null) {
            mainHandler.postDelayed(() -> {
                if (!splashOverlayRemoved) {
                    Log.w(TAG, "removeSplashOverlay: timeout fallback");
                    removeSplashOverlay();
                }
            }, 6000);
        }
    }

    private void removeSplashOverlay() {
        if (splashOverlayRemoved) {
            return;
        }
        splashOverlayRemoved = true;
        splashOverlayAttached = false;
        if (splashOverlay == null) {
            return;
        }
        AlphaAnimation fadeOut = new AlphaAnimation(1f, 0f);
        fadeOut.setDuration(250);
        fadeOut.setFillAfter(true);
        fadeOut.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                ViewGroup root = findViewById(android.R.id.content);
                if (root != null && splashOverlay != null) {
                    root.removeView(splashOverlay);
                }
                splashOverlay = null;
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        });
        splashOverlay.startAnimation(fadeOut);
    }

    private void handleAudioStateFromJs(String rawValue) {
        boolean isPlaying = parseAudioFlagValue(rawValue);
        boolean changed;
        long now = System.currentTimeMillis();
        synchronized (PUSH_LOCK) {
            isAudioStateKnown = true;
            lastAudioStateUpdateMs = now;
            changed = isAudioAlertPlaying != isPlaying;
            isAudioAlertPlaying = isPlaying;
        }
        Log.d(TAG, "handleAudioStateFromJs: isPlaying=" + isPlaying + ", changed=" + changed);
        processPendingPushIfNeeded();
    }

    private boolean parseAudioFlagValue(String rawValue) {
        if (rawValue == null) {
            return false;
        }
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String lower = trimmed.toLowerCase(Locale.US);
        if ("true".equals(lower) || "1".equals(lower) || "yes".equals(lower)) {
            return true;
        }
        if ("false".equals(lower) || "0".equals(lower) || "no".equals(lower)) {
            return false;
        }
        try {
            JSONObject object = new JSONObject(trimmed);
            if (object.has("isPlaying")) {
                return object.optBoolean("isPlaying", false);
            }
            if (object.has("value")) {
                return object.optBoolean("value", false);
            }
        } catch (Exception e) {
            Log.w(TAG, "parseAudioFlagValue: unable to parse JSON value=" + rawValue, e);
        }
        return Boolean.parseBoolean(lower);
    }

    /**
     * Reads JWT token from the PWA’s local storage and sends it to native layer.
     */
    private void injectJwtReader(WebView webView) {
        new Handler().postDelayed(() -> {
            final String script = "(() => {" + "try {" + "const raw = localStorage.getItem('sb-qxynpmqnfvdebfyhuykk-auth-token');" + "if (!raw) return;" + "const data = JSON.parse(raw);" + "const jwt = data?.access_token;" + "if (jwt) { window.AndroidBridge.receiveJwt(jwt); }" + "} catch(e) { console.error('JWT injection error', e); }" + "})();";
            webView.evaluateJavascript(script, null);
        }, 5000);
    }

    /**
     * Checks JWT every 15 minutes and updates if changed.
     */
    private void startJwtRefreshMonitor(WebView webView) {
        Handler handler = new Handler();
        Runnable checkJwtTask = new Runnable() {
            @Override
            public void run() {
                injectJwtReader(webView);
                handler.postDelayed(this, 15 * 60 * 1000);
            }
        };
        handler.postDelayed(checkJwtTask, 15 * 60 * 1000);
    }

    /**
     * Bridge for PWA to send JWT token to native code.
     */
    private class JsBridge {
        @android.webkit.JavascriptInterface
        public void receiveJwt(String jwt) {
            Log.d(TAG, "🔑 JWT received from WebView");
            getApplicationContext().getSharedPreferences("avis_prefs", 0).edit().putString("user_jwt", jwt).apply();

            // Immediately sync FCM token + JWT to backend
            MyFirebaseMessagingService.syncTokenToBackendStatic(getApplicationContext(), jwt);
        }

        @android.webkit.JavascriptInterface
        public void receiveAudioState(String state) {
            Log.d(TAG, "🎵 Audio state received from WebView: " + state);
            handleAudioStateFromJs(state);
        }
    }
}
