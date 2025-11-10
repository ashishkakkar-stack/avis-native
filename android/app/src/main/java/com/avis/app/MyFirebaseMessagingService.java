package com.avis.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONObject;

import java.util.Map;

public class MyFirebaseMessagingService extends FirebaseMessagingService {

    private static final String TAG = "AVIS_FCM";
    private static MediaPlayer mediaPlayer;
    private static long lastSoundTime = 0; // Throttle audio replay

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        Log.d(TAG, "📨 FCM message received!");

        if (remoteMessage.getFrom() != null) {
            Log.d(TAG, "Message received from: " + remoteMessage.getFrom());
        }

        // Log notification content (if any)
        if (remoteMessage.getNotification() != null) {
            Log.d(TAG, "Notification payload: "
                    + remoteMessage.getNotification().getTitle() + " | "
                    + remoteMessage.getNotification().getBody());
        }

        // Log data content (if any)
        if (!remoteMessage.getData().isEmpty()) {
            Log.d(TAG, "Data payload: " + remoteMessage.getData().toString());
        }

        // Try to extract title and body (prefer data payload)
        String title = "New Alert";
        String body = "You have a new notification";

        if (remoteMessage.getData().containsKey("title")) {
            title = remoteMessage.getData().get("title");
        } else if (remoteMessage.getNotification() != null) {
            title = remoteMessage.getNotification().getTitle();
        }

        if (remoteMessage.getData().containsKey("body")) {
            body = remoteMessage.getData().get("body");
        } else if (remoteMessage.getNotification() != null) {
            body = remoteMessage.getNotification().getBody();
        }

        Log.d(TAG, "📩 Final Notification Content → Title: " + title + " | Body: " + body);

        // Create notification & play sound
        showNotification(title, body, remoteMessage);
        playCustomSound();
    }

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        Log.d(TAG, "🔁 New FCM Token generated: " + token);

        // Try to sync immediately if JWT is already stored
        String jwt = getApplicationContext()
                .getSharedPreferences("avis_prefs", 0)
                .getString("user_jwt", null);

        if (jwt != null) {
            syncTokenToBackendStatic(getApplicationContext(), jwt);
        }
    }

    private void showNotification(String title, String messageBody, RemoteMessage remoteMessage) {
        Context context = getApplicationContext();
        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        String channelId = "avis_notifications";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "AVIS Notifications",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Channel for visitor and alert notifications");
            channel.enableVibration(true);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            notificationManager.createNotificationChannel(channel);
        }

        // ✅ Prepare intent to open MainActivity and include data for WebView reload
        JSONObject json = new JSONObject(remoteMessage.getData());
        Intent fullScreenIntent = new Intent(context, MainActivity.class);
        fullScreenIntent.putExtra("native_push_data", json.toString());
        fullScreenIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK |
                        Intent.FLAG_ACTIVITY_CLEAR_TOP |
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
        );

        // ✅ PendingIntent for both full screen and notification tap
        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
                context,
                0,
                fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // ✅ Build notification
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title != null ? title : "AVIS Alert")
                .setContentText(messageBody != null ? messageBody : "New visitor alert")
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setContentIntent(fullScreenPendingIntent)
                .setSound(null); // Sound handled by MediaPlayer

        // ✅ Issue notification immediately
        notificationManager.notify((int) System.currentTimeMillis(), notificationBuilder.build());

        Log.d(TAG, "📲 Notification shown: " + title + " | " + messageBody);

        // ✅ Auto-launch MainActivity if app is in background or closed
        try {
            context.startActivity(fullScreenIntent);
            Log.d(TAG, "🚀 App brought to foreground automatically");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to auto-launch MainActivity", e);
        }

        // ✅ Start playing custom sound (if not already playing)
        try {
            playCustomSound(context);
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to play custom sound", e);
        }
    }


    private void playCustomSound() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastSoundTime < 5000) {
                Log.d(TAG, "⏱ Skipping sound (throttled)");
                return;
            }
            lastSoundTime = now;

            if (mediaPlayer != null) {
                mediaPlayer.release();
                mediaPlayer = null;
            }

            Uri soundUri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.a_old_telephone);
            mediaPlayer = MediaPlayer.create(this, soundUri);

            if (mediaPlayer != null) {
                mediaPlayer.setOnCompletionListener(mp -> stopSound());
                mediaPlayer.start();
                Log.d(TAG, "🔔 Custom ringtone playing...");
            } else {
                Log.w(TAG, "⚠️ Failed to create MediaPlayer");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error playing custom sound", e);
        }
    }

    public static void stopSound() {
        try {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
                mediaPlayer.release();
                mediaPlayer = null;
                Log.d(TAG, "🔇 Sound stopped");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error stopping sound", e);
        }
    }

    // ✅ Called from JsBridge or onNewToken() to sync FCM token to backend
    // ✅ Called from JsBridge or onNewToken() to sync FCM token to backend
    // ✅ Called from JsBridge or onNewToken() to sync FCM token to backend
    // ✅ Called from JsBridge or onNewToken() to sync FCM token to backend
    public static void syncTokenToBackendStatic(Context context, String jwt) {
        try {
            // Read previously synced token
            String lastSyncedToken = context.getSharedPreferences("avis_prefs", 0)
                    .getString("last_synced_fcm_token", null);

            com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken()
                    .addOnCompleteListener(task -> {
                        if (!task.isSuccessful()) {
                            Log.e(TAG, "❌ Unable to fetch FCM token", task.getException());
                            return;
                        }

                        String token = task.getResult();
                        if (token == null || token.isEmpty()) {
                            Log.w(TAG, "⚠️ Empty FCM token received");
                            return;
                        }

                        Log.d(TAG, "💾 Last synced FCM token: " + (lastSyncedToken != null ? lastSyncedToken : "<none>"));
                        Log.d(TAG, "🪪 Current FCM Token: " + token);

                        // ✅ If token hasn't changed, skip sync
                        if (lastSyncedToken != null && lastSyncedToken.equals(token)) {
                            Log.d(TAG, "⏭ Token unchanged, skipping sync to backend");
                            return;
                        }

                        Log.d(TAG, "🌐 Syncing FCM token to backend with JWT");
                        Log.d(TAG, "JWT: " + jwt);

                        new Thread(() -> {
                            try {
                                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
                                okhttp3.RequestBody body = okhttp3.RequestBody.create(
                                        okhttp3.MediaType.parse("application/json"),
                                        "{\"fcm_token\":\"" + token + "\",\"platform\":\"android\"}"
                                );

                                okhttp3.Request request = new okhttp3.Request.Builder()
                                        .url("https://qxynpmqnfvdebfyhuykk.supabase.co/functions/v1/register-fcm-token")
                                        .post(body)
                                        .addHeader("Authorization", "Bearer " + jwt)
                                        .addHeader("apikey", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InF4eW5wbXFuZnZkZWJmeWh1eWtrIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjE3MTQzMDIsImV4cCI6MjA3NzI5MDMwMn0.gHyqB8oy1X_ljIVmAcetfrOYR9G2yMN1WSqCBrcrU-o")
                                        .addHeader("Content-Type", "application/json")
                                        .build();

                                okhttp3.Response response = client.newCall(request).execute();
                                String respBody = response.body() != null ? response.body().string() : "<empty>";

                                Log.d(TAG, "✅ Token sync response: " + response.code() + " | " + respBody);

                                if (response.isSuccessful()) {
                                    // Save token locally only after successful sync
                                    context.getSharedPreferences("avis_prefs", 0)
                                            .edit()
                                            .putString("last_synced_fcm_token", token)
                                            .apply();
                                    Log.d(TAG, "💾 Saved FCM token to preferences");
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "❌ Error syncing FCM token to backend", e);
                            }
                        }).start();
                    });
        } catch (Exception e) {
            Log.e(TAG, "❌ Error preparing FCM sync", e);
        }
    }

}
