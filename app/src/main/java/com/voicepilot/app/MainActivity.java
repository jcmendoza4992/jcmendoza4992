package com.voicepilot.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Setup screen: three large buttons, two settings and the command list. */
public class MainActivity extends Activity {
    private static final String[] PERMISSIONS = {Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS, Manifest.permission.ANSWER_PHONE_CALLS};

    private static final String COMMANDS =
            "APPS\n  open Messenger · open YouTube · go home · back · recent apps\n\n"
            + "TAP ANYTHING\n  tap Settings · long press Photos · or just say the words on a button\n"
            + "  show numbers → tap 7 · hide numbers\n  scroll down · scroll up · swipe left · swipe right\n\n"
            + "TYPE\n  type see you at five · delete · clear · enter · send\n\n"
            + "CALLS\n  call Maria · call 0917 123 4567 · answer · hang up\n\n"
            + "MESSAGES\n  read messages\n  reply saying I'm on my way\n  reply to Maria saying call you later\n"
            + "  text Maria saying running late   (SMS)\n  message Maria on Messenger saying hello\n\n"
            + "MUSIC\n  play music · pause · next · previous\n  play Imagine Dragons · play lofi on YouTube\n"
            + "  volume up · volume down · mute\n\n"
            + "LOCK SCREEN\n  unlock (swipes up) · pin 1 2 3 4 · lock phone\n\n"
            + "SYSTEM\n  notifications · quick settings · screenshot · flashlight on · what time is it\n"
            + "  stop listening · wake up";

    private TextView statusView;
    private EditText lang, prefix;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        statusView = text("", 18);
        root.addView(statusView);
        root.addView(button("1. Allow microphone, contacts, calls, SMS", v -> requestPermissions(PERMISSIONS, 1)));
        root.addView(button("2. Turn on VoicePilot in Accessibility", v -> open(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(button("3. Allow notification access (for replies)", v -> open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        SharedPreferences cfg = getSharedPreferences("cfg", 0);
        root.addView(text("Recognition language (for example en-US, en-PH, fil-PH)", 14));
        lang = new EditText(this);
        lang.setSingleLine(true);
        lang.setText(cfg.getString("lang", "en-US"));
        root.addView(lang);
        root.addView(text("Optional trigger word. If set, only commands starting with it are obeyed (for example: phone)", 14));
        prefix = new EditText(this);
        prefix.setSingleLine(true);
        prefix.setText(cfg.getString("prefix", ""));
        root.addView(prefix);
        root.addView(button("Save settings", v -> {
            cfg.edit().putString("lang", lang.getText().toString().trim())
                    .putString("prefix", prefix.getText().toString().trim()).apply();
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        }));

        root.addView(text("\nWHAT YOU CAN SAY\n", 18));
        root.addView(text(COMMANDS, 15));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setPadding(0, 12, 0, 12);
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(17);
        b.setAllCaps(false);
        b.setMinHeight((int) (72 * getResources().getDisplayMetrics().density));
        b.setOnClickListener(l);
        return b;
    }

    private void open(String action) {
        try {
            startActivity(new Intent(action));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void updateStatus() {
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean service = VoiceService.instance != null;
        String listeners = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean notif = listeners != null && listeners.contains(getPackageName());
        statusView.setText((mic ? "✓" : "✗") + " Microphone permission\n"
                + (service ? "✓" : "✗") + " Accessibility service\n"
                + (notif ? "✓" : "✗") + " Notification access\n\n"
                + "Status: " + (service ? VoiceService.status : "Turn on the accessibility service (step 2)"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }
}
