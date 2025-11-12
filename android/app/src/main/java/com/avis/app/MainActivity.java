package com.avis.app;

import android.annotation.SuppressLint;
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

import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.Context;

import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.net.Uri;
import android.content.ActivityNotFoundException;


public class MainActivity extends BridgeActivity {
    private static final String TAG = "AVIS_MAIN";
    private WebView webView;
    private static long lastReloadTime = 0; // Used for 5-second throttle

    private BroadcastReceiver webViewRefreshReceiver;

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
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

        // ✅ Register the WebView refresh broadcast receiver
        IntentFilter filter = new IntentFilter("com.avis.app.REFRESH_WEBVIEW");

        try {
            // Use legacy 2-argument call to support all SDKs safely
            registerReceiver(webViewRefreshReceiver, filter);
            Log.d(TAG, "✅ WebView refresh receiver registered");
        } catch (Exception e) {
            Log.e(TAG, "❌ Error registering WebView refresh receiver", e);
        }


        webView = (WebView) bridge.getWebView();
        webView.getSettings().setJavaScriptEnabled(true);
        webView.addJavascriptInterface(new JsBridge(), "AndroidBridge");

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
                Log.d(TAG, "✅ WebView loaded: " + url);
                injectJwtReader(webView);
                startJwtRefreshMonitor(webView);
                handleIntentPush(getIntent()); // In case an FCM intent was waiting
                super.onPageFinished(view, url);
            }
        });


        // ✅ Listen for broadcast from FCM service to refresh the WebView
        webViewRefreshReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.avis.app.REFRESH_WEBVIEW".equals(intent.getAction())) {
                    Log.d(TAG, "🔁 Refreshing WebView after FCM message");

                    if (webView != null) {
                        // Delay slightly to ensure content loads cleanly
                        webView.postDelayed(() -> {
                            webView.reload();
                            Log.d(TAG, "✅ WebView reloaded");
                        }, 1000);
                    }
                }
            }
        };

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

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (webViewRefreshReceiver != null) {
            unregisterReceiver(webViewRefreshReceiver);
            webViewRefreshReceiver = null;
        }
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
