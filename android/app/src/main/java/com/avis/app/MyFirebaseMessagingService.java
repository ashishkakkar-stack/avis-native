package com.avis.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MyFirebaseMessagingService extends FirebaseMessagingService {

    public static MediaPlayer activePlayer;
    private static Handler stopHandler = new Handler();
    private static Runnable stopRunnable;

    private static final int ALERT_TIMEOUT_MS = 3000;
    private static final String TAG = "AVIS_FCM";

    @Override
    public void onNewToken(String token) {
        Log.d(TAG, "🔑 New FCM token: " + token);

        Context context = getApplicationContext();
        String jwt = context.getSharedPreferences("avis_prefs", 0)
                .getString("user_jwt", null);

        if (jwt != null) {
            syncTokenToBackendStatic(context, jwt);
        } else {
            Log.w(TAG, "⚠️ JWT not available yet, will sync later");
        }
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        Log.d(TAG, "📩 FCM message received");
        Log.d(TAG, "Data payload: " + remoteMessage.getData().toString());

        String title = remoteMessage.getData().get("title");
        String body = remoteMessage.getData().get("body");

        playRingtoneWithTimeout();
        showFullScreenNotification(title, body);
    }

    public static void syncTokenToBackendStatic(Context context, String jwtToken) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                Log.d(TAG, "🌐 Starting FCM token sync...");

                String fcmToken = FirebaseMessaging.getInstance().getToken().getResult();
                if (fcmToken == null) {
                    Log.e(TAG, "❌ FCM token not available");
                    return;
                }

                JSONObject jsonBody = new JSONObject();
                jsonBody.put("fcm_token", fcmToken);
                jsonBody.put("platform", "android");

                JSONObject deviceInfo = new JSONObject();
                deviceInfo.put("app_version", "1.0.0");
                deviceInfo.put("device_model", Build.MODEL);
                jsonBody.put("device_info", deviceInfo);

                // 🔍 Log the outgoing request body
                Log.d(TAG, "➡️  Request body:\n" + jsonBody.toString(2));

                URL url = new URL("https://qxynpmqnfvdebfyhuykk.supabase.co/functions/v1/register-fcm-token");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + jwtToken);
                conn.setRequestProperty("apikey", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonBody.toString().getBytes());
                    os.flush();
                }

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "✅ HTTP response code: " + responseCode);

                BufferedReader reader;
                if (responseCode >= 200 && responseCode < 300) {
                    reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                } else {
                    reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                }

                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                // 🔍 Log the full response body
                Log.d(TAG, "⬅️  Response body:\n" + response.toString());

            } catch (Exception e) {
                Log.e(TAG, "❌ FCM sync failed: " + e.getMessage(), e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private void playRingtoneWithTimeout() {
        try {
            stopRingtone();
            activePlayer = MediaPlayer.create(this, R.raw.a_old_telephone);
            activePlayer.setLooping(true);
            activePlayer.start();
            Log.d(TAG, "🔊 Playing ringtone");

            stopRunnable = MyFirebaseMessagingService::stopRingtone;
            stopHandler.postDelayed(stopRunnable, ALERT_TIMEOUT_MS);
        } catch (Exception e) {
            Log.e(TAG, "❌ Error playing sound: " + e.getMessage());
        }
    }

    public static void stopRingtone() {
        try {
            if (activePlayer != null) {
                if (activePlayer.isPlaying()) activePlayer.stop();
                activePlayer.release();
                activePlayer = null;
                Log.d(TAG, "🔇 Ringtone stopped");
            }
            if (stopHandler != null && stopRunnable != null)
                stopHandler.removeCallbacks(stopRunnable);
        } catch (Exception ignored) {}
    }

    private void showFullScreenNotification(String title, String body) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        String channelId = "avis_channel";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "AVIS Alerts",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setSound(null, null);
            notificationManager.createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title != null ? title : "Visitor arrived")
                .setContentText(body != null ? body : "A new visitor is waiting at the gate")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setFullScreenIntent(pendingIntent, true)
                .setContentIntent(pendingIntent);

        notificationManager.notify((int) System.currentTimeMillis(), builder.build());
    }
}
