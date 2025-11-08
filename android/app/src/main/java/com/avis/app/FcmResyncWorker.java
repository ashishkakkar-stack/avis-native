package com.avis.app;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class FcmResyncWorker extends Worker {

    private static final String TAG = "AVIS_RESYNC";

    public FcmResyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Context context = getApplicationContext();
            String jwt = context.getSharedPreferences("avis_prefs", 0)
                    .getString("user_jwt", null);

            if (jwt != null) {
                Log.d(TAG, "🔁 Running daily FCM token sync...");
                MyFirebaseMessagingService.syncTokenToBackendStatic(context, jwt);
            } else {
                Log.w(TAG, "⚠️ No JWT found, skipping daily sync");
            }

            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "❌ Error during daily sync: " + e.getMessage());
            return Result.failure();
        }
    }
}
