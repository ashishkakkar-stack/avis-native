package com.avis.app;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.getcapacitor.BridgeActivity;
import com.google.firebase.messaging.FirebaseMessaging;

import androidx.annotation.Nullable;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

public class MainActivity extends BridgeActivity {
    private static final String TAG = "AVIS_MAIN";
    private WebView webView;
    private static long lastReloadTime = 0; // Used for 5-second throttle

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 🔍 Print current FCM token at startup
        FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null) {
                        Log.d("AVIS_MAIN", "📲 Current FCM token on startup: " + task.getResult());
                    } else {
                        Log.e("AVIS_MAIN", "⚠️ Unable to fetch FCM token at startup", task.getException());
                    }
                });

        webView = (WebView) bridge.getWebView();
        webView.getSettings().setJavaScriptEnabled(true);
        webView.addJavascriptInterface(new JsBridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "✅ WebView loaded: " + url);
                injectJwtReader(webView);
                startJwtRefreshMonitor(webView);
                handleIntentPush(getIntent()); // In case an FCM intent was waiting
                super.onPageFinished(view, url);
            }
        });

        // Schedule a daily background FCM sync
        PeriodicWorkRequest resyncWork = new PeriodicWorkRequest.Builder(
                FcmResyncWorker.class,
                1, TimeUnit.DAYS
        ).build();

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "daily_fcm_resync",
                ExistingPeriodicWorkPolicy.KEEP,
                resyncWork
        );

        Log.d(TAG, "📅 Daily FCM resync scheduled (once every 24h)");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Log.d(TAG, "📬 onNewIntent received (likely from FCM)");
        handleIntentPush(intent);
    }

    @Override
    public void onResume() {
        super.onResume();

        // Print FCM token every time app comes to foreground
        com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(task -> {
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

        handleIntentPush(getIntent());
    }

    /**
     * Handles reload when an FCM push is received.
     * Prevents multiple reloads within 5 seconds.
     */
    private void handleIntentPush(Intent intent) {
        if (intent == null) return;

        String dataJson = intent.getStringExtra("native_push_data");
        if (dataJson == null) return;

        long now = System.currentTimeMillis();
        if (now - lastReloadTime < 5000) {
            Log.d(TAG, "⏱ Skipping reload (throttled within 5s)");
            return;
        }
        lastReloadTime = now;

        Log.d(TAG, "🔁 Refreshing WebView after FCM push: " + dataJson);

        webView.post(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                webView.evaluateJavascript("location.reload()", (ValueCallback<String>) value ->
                        Log.d(TAG, "✅ WebView reloaded successfully"));
            } else {
                webView.loadUrl("javascript:location.reload()");
            }
        });
    }

    /**
     * Reads JWT token from the PWA’s local storage and sends it to native layer.
     */
    private void injectJwtReader(WebView webView) {
        new Handler().postDelayed(() -> {
            final String script =
                    "(() => {" +
                            "try {" +
                            "const raw = localStorage.getItem('sb-qxynpmqnfvdebfyhuykk-auth-token');" +
                            "if (!raw) return;" +
                            "const data = JSON.parse(raw);" +
                            "const jwt = data?.access_token;" +
                            "if (jwt) { window.AndroidBridge.receiveJwt(jwt); }" +
                            "} catch(e) { console.error('JWT injection error', e); }" +
                            "})();";
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
            getApplicationContext().getSharedPreferences("avis_prefs", 0)
                    .edit()
                    .putString("user_jwt", jwt)
                    .apply();

            // Immediately sync FCM token + JWT to backend
            MyFirebaseMessagingService.syncTokenToBackendStatic(getApplicationContext(), jwt);
        }
    }
}
