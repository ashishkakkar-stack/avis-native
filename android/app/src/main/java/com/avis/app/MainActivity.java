package com.avis.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import android.webkit.WebResourceRequest;
import android.net.Uri;
import android.content.ActivityNotFoundException;

public class MainActivity extends BridgeActivity {
    private static final String TAG = "AVIS_MAIN";
    private WebView webView;
    private static long lastReloadTime = 0; // Used for 5-second throttle
    public static boolean isActive = false;
    private static long lastWebViewReloadTime = 0;
    private static final long WEBVIEW_RELOAD_THROTTLE_MS = 3000; // 3 seconds
    private BroadcastReceiver webViewRefreshReceiver;
    private boolean isWebViewReady = false;
    private static final String REFRESH_ACTION = "com.avis.app.REFRESH_WEBVIEW";
    private boolean receiverRegistered = false; // track registration state

    // Simple safe reload wrapper
    private void safeReloadWebView() {
        try {
            if (webView == null) {
                Log.w(TAG, "safeReloadWebView: webView is null, skipping");
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastWebViewReloadTime < WEBVIEW_RELOAD_THROTTLE_MS) {
                Log.d(TAG, "safeReloadWebView: throttled, skipping");
                return;
            }
            lastWebViewReloadTime = now;
            webView.post(() -> {
                try {
                    webView.reload();
                    Log.d(TAG, "safeReloadWebView: reload called");
                } catch (Exception e) {
                    Log.e(TAG, "safeReloadWebView: reload failed", e);
                }
            });
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

        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            Log.e(TAG, "Uncaught exception in thread " + thread.getName(), ex);
        });

        super.onCreate(savedInstanceState);

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
                if (System.currentTimeMillis() - lastWebViewReloadTime > WEBVIEW_RELOAD_THROTTLE_MS) {
                    handleIntentPush(getIntent());
                } else {
                    Log.d(TAG, "🧩 Skipped handleIntentPush due to recent reload");
                }

            }
        });

        // Define receiver (must be defined before registration)
        webViewRefreshReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!REFRESH_ACTION.equals(intent.getAction())) return;

                Log.d(TAG, "Broadcast received: REFRESH_WEBVIEW");

                // Throttle duplicates
                long now = System.currentTimeMillis();
                if (now - lastWebViewReloadTime < WEBVIEW_RELOAD_THROTTLE_MS) {
                    Log.d(TAG, "Broadcast handler: skipping duplicate refresh (throttled)");
                    return;
                }

                // If webView is ready, reload safely; otherwise defer
                if (isWebViewReady && webView != null) {
                    safeReloadWebView();
                    Log.d(TAG, "Broadcast handler: immediate reload requested");
                } else {
                    Log.w(TAG, "Broadcast handler: WebView not ready, deferring reload");
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (isWebViewReady && webView != null) {
                            safeReloadWebView();
                            Log.d(TAG, "Broadcast handler: deferred reload executed");
                        } else {
                            Log.e(TAG, "Broadcast handler: deferred reload skipped, WebView still not ready");
                        }
                    }, 1500); // 1.5s delay
                }
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
                    handleIntentPush(intent);
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
                            handleIntentPush(getIntent());
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

        handleIntentPush(getIntent());
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
                webView.evaluateJavascript("location.reload()", (ValueCallback<String>) value -> Log.d(TAG, "✅ WebView reloaded successfully"));
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
    }
}
