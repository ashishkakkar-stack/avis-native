package com.avis.app;

import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.getcapacitor.BridgeActivity;

import java.util.concurrent.TimeUnit;

public class MainActivity extends BridgeActivity {

    private static final String TAG = "AVIS_MAIN";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebView webView = (WebView) bridge.getWebView();
        webView.getSettings().setJavaScriptEnabled(true);
        webView.addJavascriptInterface(new JsBridge(), "AndroidBridge");

        // Inject JS to fetch JWT on page load
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "✅ WebView loaded: " + url);
                injectJwtReader(webView);
                startJwtRefreshMonitor(webView);
                super.onPageFinished(view, url);
            }
        });

        // Schedule a daily background sync
        scheduleDailyFcmResync();
    }

    private void injectJwtReader(WebView webView) {
        new Handler().postDelayed(() -> {
            String script =
                    "(() => {" +
                            "try {" +
                            "const raw = localStorage.getItem('sb-qxynpmqnfvdebfyhuykk-auth-token');" +
                            "if (!raw) { console.log('⚠️ No Supabase token found'); return; }" +
                            "const data = JSON.parse(raw);" +
                            "const jwt = data?.access_token;" +
                            "if (jwt) { window.AndroidBridge.receiveJwt(jwt); }" +
                            "} catch(e) { console.error('❌ JWT injection error', e); }" +
                            "})();";
            webView.evaluateJavascript(script, null);
        }, 5000); // wait 5s for full app load
    }

    /**
     * Periodically re-checks the web app’s localStorage every 15 min.
     * Ensures new JWT (after silent refresh) is synced to native layer.
     */
    private void startJwtRefreshMonitor(WebView webView) {
        Handler handler = new Handler();
        Runnable checkJwtTask = new Runnable() {
            @Override
            public void run() {
                injectJwtReader(webView);
                handler.postDelayed(this, 15 * 60 * 1000); // every 15 minutes
            }
        };
        handler.postDelayed(checkJwtTask, 15 * 60 * 1000);
    }

    /**
     * Schedules a daily background job to re-sync FCM + JWT with backend.
     */
    private void scheduleDailyFcmResync() {
        PeriodicWorkRequest resyncWork = new PeriodicWorkRequest.Builder(
                FcmResyncWorker.class,
                1, TimeUnit.DAYS)
                .build();

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "daily_fcm_resync",
                ExistingPeriodicWorkPolicy.KEEP,
                resyncWork
        );

        Log.d(TAG, "📅 Daily FCM resync scheduled (once every 24h)");
    }

    // Bridge to receive JWT from WebView JS
    private class JsBridge {
        @JavascriptInterface
        public void receiveJwt(String jwt) {
            Log.d(TAG, "🔐 JWT received from WebView: " + jwt);

            getApplicationContext().getSharedPreferences("avis_prefs", 0)
                    .edit()
                    .putString("user_jwt", jwt)
                    .apply();

            // Trigger backend sync
            MyFirebaseMessagingService.syncTokenToBackendStatic(getApplicationContext(), jwt);
        }
    }
}
