package com.avis.app;

import android.app.Activity;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

public class VisitorAlertActivity extends Activity {

    private MediaPlayer player;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        setContentView(R.layout.activity_visitor_alert);

        String title = getIntent().getStringExtra("title");
        String body = getIntent().getStringExtra("body");

        TextView titleView = findViewById(R.id.txtTitle);
        TextView bodyView = findViewById(R.id.txtBody);
        titleView.setText(title != null ? title : "Visitor Alert");
        bodyView.setText(body != null ? body : "");

        Button dismissButton = findViewById(R.id.btnDismiss);
        dismissButton.setOnClickListener((View v) -> finish());

        player = MediaPlayer.create(this, R.raw.a_old_telephone);
        player.setLooping(true);
        player.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.stop();
            player.release();
        }
    }
}
