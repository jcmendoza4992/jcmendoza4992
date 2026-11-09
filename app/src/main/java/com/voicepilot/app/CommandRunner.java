package com.voicepilot.app;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.telecom.TelecomManager;
import android.telephony.SmsManager;
import android.view.KeyEvent;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns a recognised sentence into an action. */
class CommandRunner {
    private final VoiceService s;
    private final Screen sc;

    private static final Map<String, String> DIGITS = new HashMap<>();
    static {
        String[][] d = {{"zero", "0"}, {"oh", "0"}, {"o", "0"}, {"one", "1"}, {"won", "1"}, {"two", "2"}, {"to", "2"},
                {"too", "2"}, {"three", "3"}, {"tree", "3"}, {"four", "4"}, {"for", "4"}, {"five", "5"}, {"six", "6"},
                {"seven", "7"}, {"eight", "8"}, {"ate", "8"}, {"nine", "9"}};
        for (String[] p : d) DIGITS.put(p[0], p[1]);
    }

    CommandRunner(VoiceService s, Screen sc) { this.s = s; this.sc = sc; }

    private static Matcher m(String re, String t) {
        Matcher x = Pattern.compile(re).matcher(t);
        return x.matches() ? x : null;
    }

    private static String digits(String spoken) {
        StringBuilder sb = new StringBuilder();
        for (String w : spoken.toLowerCase().split("[^a-z0-9]+")) {
            if (w.matches("\\d+")) sb.append(w);
            else if (DIGITS.containsKey(w)) sb.append(DIGITS.get(w));
        }
        return sb.toString();
    }

    private boolean has(String permission) {
        return s.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void later(long ms, Runnable r) { s.handler.postDelayed(r, ms); }

    private void refreshNumbers() {
        if (sc.numbersShowing()) later(900, sc::showNumbers);
    }

    void run(String t, List<String> alts) {
        Matcher x;

        // ----- numbered labels -----
        if (t.matches("(show |display )?(the )?(numbers?|labels?|grid)( on)?")) {
            int n = sc.showNumbers();
            s.say(n == 0 ? "Nothing to label here." : "Say tap and a number.");
            return;
        }
        if (t.matches("(hide|remove|clear|close) (the )?(numbers?|labels?|grid)|numbers? off")) { sc.hideNumbers(); return; }
        if (sc.numbersShowing() && (x = m("(?:tap |click |press |select |touch |number )*(.+)", t)) != null) {
            String d = x.group(1).matches("\\d+") ? x.group(1) : (DIGITS.containsKey(x.group(1)) ? DIGITS.get(x.group(1)) : "");
            if (!d.isEmpty() && d.length() < 4) {
                if (sc.tapNumber(Integer.parseInt(d))) refreshNumbers(); else s.say("No such number.");
                return;
            }
        }

        // ----- lock screen -----
        if ((x = m("(?:enter |type |input |my )*(?:pin code|pin|passcode)(?: is)? (.+)", t)) != null) {
            String d = digits(x.group(1));
            if (d.isEmpty()) s.say("Say pin, then the digits."); else enterPin(d);
            return;
        }
        if (t.matches("unlock( the)?( phone| screen)?|swipe up to unlock")) { sc.swipeDir("up", true); return; }

        // ----- system navigation -----
        if (t.matches("(go )?back")) { s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); refreshNumbers(); return; }
        if (t.matches("(go )?(to )?(the )?home( screen)?")) { s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); refreshNumbers(); return; }
        if (t.matches("(show |open )?(recent apps|recents|recent|switch apps?|overview)")) { s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS); return; }
        if (t.matches("(show |open |pull down )?(the )?notifications?( shade| panel)?")) { s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS); return; }
        if (t.matches("(show |open )?(the )?quick settings")) { s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS); return; }
        if (t.matches("(show |open )?(the )?power menu")) { s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG); return; }
        if (t.matches("lock( the| my)?( phone| screen)?|screen off")) {
            if (Build.VERSION.SDK_INT >= 28) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
            else s.say("Locking needs Android 9 or newer.");
            return;
        }
        if (t.matches("(take (a )?)?screenshot")) {
            if (Build.VERSION.SDK_INT >= 28) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT);
            return;
        }
        if ((x = m("(scroll|swipe|page)( to the)? (up|down|left|right)", t)) != null) {
            String dir = x.group(3);
            if (!x.group(1).equals("swipe")) dir = dir.equals("up") ? "down" : dir.equals("down") ? "up" : dir.equals("left") ? "right" : "left";
            sc.swipeDir(dir, false);
            refreshNumbers();
            return;
        }

        // ----- phone calls -----
        if (t.matches("answer( the)?( call| phone)?|pick up( the)?( call| phone)?|accept( the)? call")) { answer(); return; }
        if (t.matches("hang up|end( the)? call|decline( the)? call|reject( the)? call")) { hangUp(); return; }
        if ((x = m("(?:call|dial|phone|ring) (.+)", t)) != null) { call(x.group(1)); return; }

        // ----- messages -----
        if (t.matches("read( me)?( my| the)?( new| latest)? (messages?|notifications?)")) { readMessages(); return; }
        if ((x = m("reply to (.+?) (?:saying|with|that) (.+)", t)) != null) { reply(x.group(1), x.group(2)); return; }
        if ((x = m("reply (?:saying |with |that )?(.+)", t)) != null) {
            if (x.group(1).startsWith("to ")) s.say("Say reply to, the name, saying, then your message.");
            else reply(null, x.group(1));
            return;
        }
        if ((x = m("(?:send (?:a )?(?:message|text|sms) to|message|text|sms) (.+?)(?: (?:on|in|using|via) ([a-z ]+?))? (?:saying|say|that says|with message) (.+)", t)) != null) {
            String app = x.group(2);
            if (app == null || app.matches("sms|text|messages")) sms(x.group(1), x.group(3));
            else appMessage(app, x.group(1), x.group(3));
            return;
        }
        if ((x = m("(?:text|sms) (\\S+) (.+)", t)) != null) { sms(x.group(1), x.group(2)); return; }

        // ----- media and volume -----
        if (t.matches("play( the| some)?( music| song)?|resume( the)?( music| song)?")) { media(KeyEvent.KEYCODE_MEDIA_PLAY); return; }
        if (t.matches("(pause|stop)( the)?( music| song| video)?")) { media(KeyEvent.KEYCODE_MEDIA_PAUSE); return; }
        if (t.matches("(next|skip)( the)? (song|track)|skip")) { media(KeyEvent.KEYCODE_MEDIA_NEXT); return; }
        if (t.matches("(previous|last) (song|track)|previous")) { media(KeyEvent.KEYCODE_MEDIA_PREVIOUS); return; }
        if ((x = m("play (.+?) (?:on|in|using) ([a-z ]+)", t)) != null && findApp(x.group(2)) != null) { play(x.group(1), x.group(2)); return; }
        if ((x = m("play (.+)", t)) != null) { play(x.group(1), null); return; }
        if (t.matches("(turn (the )?)?volume up|louder|increase( the)? volume")) { volume(AudioManager.ADJUST_RAISE); return; }
        if (t.matches("(turn (the )?)?volume down|quieter|softer|decrease( the)? volume")) { volume(AudioManager.ADJUST_LOWER); return; }
        if (t.matches("mute( the)?( volume| sound)?")) { volume(AudioManager.ADJUST_MUTE); return; }
        if (t.matches("unmute( the)?( volume| sound)?")) { volume(AudioManager.ADJUST_UNMUTE); return; }

        // ----- typing -----
        if ((x = m("(?:type|write|dictate|enter text|input) (.+)", t)) != null) {
            if (!sc.typeText(s.original(x.group(1)), false)) s.say("No text box is open.");
            return;
        }
        if (t.matches("(delete|backspace|undo)( that| the last word| word)?")) { sc.deleteLastWord(); return; }
        if (t.matches("clear( the)?( text| all| everything)?|delete (all|everything)")) { sc.typeText("", true); return; }
        if (t.matches("enter|submit|press enter")) { if (!sc.imeEnter()) s.say("Nothing to submit."); return; }
        if (t.matches("send( it| message| the message)?")) {
            if (!(sc.tapText("send") || sc.imeEnter())) s.say("I can't find a send button.");
            return;
        }

        // ----- tapping -----
        if ((x = m("(?:long press|long tap|press and hold|hold)(?: on)? (.+)", t)) != null) {
            if (!sc.longPressText(x.group(1))) s.say("I can't see " + x.group(1));
            return;
        }
        if ((x = m("(?:tap|click|press|select|touch|choose)(?: on)? (.+)", t)) != null) {
            boolean ok = sc.tapText(x.group(1));
            for (int i = 1; !ok && i < alts.size(); i++) {
                Matcher y = m("(?:tap|click|press|select|touch|choose)(?: on)? (.+)", alts.get(i).toLowerCase());
                if (y != null) ok = sc.tapText(y.group(1));
            }
            if (ok) refreshNumbers(); else s.say("I can't see " + x.group(1) + ". Say show numbers.");
            return;
        }

        // ----- apps -----
        if ((x = m("(?:open|launch|start|run|go to)(?: the)? (.+?)(?: app)?", t)) != null) {
            ResolveInfo app = findApp(x.group(1));
            for (int i = 1; app == null && i < alts.size(); i++) {
                Matcher y = m("(?:open|launch|start|run|go to)(?: the)? (.+?)(?: app)?", alts.get(i).toLowerCase());
                if (y != null) app = findApp(y.group(1));
            }
            if (app == null) s.say("I can't find an app called " + x.group(1));
            else launch(app);
            return;
        }

        // ----- extras -----
        if ((x = m("(?:turn )?(?:the )?(?:flashlight|torch|flash) (on|off)|(?:turn )?(on|off) (?:the )?(?:flashlight|torch|flash)", t)) != null) {
            flashlight((x.group(1) != null ? x.group(1) : x.group(2)).equals("on"));
            return;
        }
        if (t.matches("what('s| is)( the)? time( is it)?|what time is it")) {
            s.say("It is " + new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date()));
            return;
        }
        if (t.matches("help|what can i say|show commands")) {
            s.startActivity(new Intent(s, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }

        // ----- say what you see -----
        if (sc.tapText(t)) { refreshNumbers(); return; }
        s.note("Not a command: " + t);
    }

    // ---------- lock screen PIN ----------

    private void enterPin(final String pin) {
        // Typed lock (password field) first, otherwise press the on-screen keypad digit by digit.
        if (sc.findExact(pin.substring(0, 1)) == null && sc.findById("key" + pin.charAt(0)) == null) {
            if (sc.typeText(pin, true)) { later(300, sc::imeEnter); return; }
            s.say("I can't see a keypad. Say unlock first.");
            return;
        }
        for (int i = 0; i < pin.length(); i++) {
            final String d = String.valueOf(pin.charAt(i));
            later(260L * i, () -> {
                if (!sc.click(sc.findExact(d))) sc.click(sc.findById("key" + d));
            });
        }
        later(260L * pin.length() + 200, () -> {
            if (!sc.click(sc.findById("key_enter"))) {
                if (!sc.click(sc.findExact("ok"))) sc.click(sc.findExact("enter"));
            }
        });
    }

    // ---------- apps ----------

    private List<ResolveInfo> launchers() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        try {
            return s.getPackageManager().queryIntentActivities(i, 0);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    ResolveInfo findApp(String name) {
        String q = Screen.clean(name).replace(" ", "");
        if (q.isEmpty()) return null;
        ResolveInfo best = null;
        int bestScore = 0;
        PackageManager pm = s.getPackageManager();
        for (ResolveInfo r : launchers()) {
            String label = Screen.clean(String.valueOf(r.loadLabel(pm))).replace(" ", "");
            if (label.isEmpty()) continue;
            int score = 0;
            if (label.equals(q)) score = 1000;
            else if (label.startsWith(q)) score = 700 - label.length();
            else if (label.contains(q)) score = 500 - label.length();
            else if (q.contains(label) && label.length() >= 4) score = 300 + label.length();
            if (score > bestScore) { bestScore = score; best = r; }
        }
        return best;
    }

    private void launch(ResolveInfo r) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(r.activityInfo.packageName, r.activityInfo.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            s.startActivity(i);
            refreshNumbers();
        } catch (Exception e) {
            s.say("I couldn't open that app.");
        }
    }

    // ---------- calls ----------

    private String[] contact(String name) {
        if (!has(Manifest.permission.READ_CONTACTS)) return null;
        String q = Screen.clean(name);
        String[] best = null;
        int bestScore = 0;
        try (Cursor c = s.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
                null, null, null)) {
            while (c != null && c.moveToNext()) {
                String n = c.getString(0);
                if (n == null) continue;
                String label = Screen.clean(n);
                int score = label.equals(q) ? 1000 : label.startsWith(q) ? 700 - label.length()
                        : label.contains(q) ? 500 - label.length() : 0;
                if (score > bestScore) { bestScore = score; best = new String[]{n, c.getString(1)}; }
            }
        } catch (Exception ignored) { }
        return best;
    }

    private String[] resolve(String who) {
        String d = who.replaceAll("[^0-9+]", "");
        if (d.length() >= 3 && who.replaceAll("[0-9+ ()-]", "").isEmpty()) return new String[]{d, d};
        String[] c = contact(who);
        if (c == null && digits(who).length() >= 3 && digits(who).length() >= who.split(" ").length) {
            return new String[]{digits(who), digits(who)};
        }
        return c;
    }

    private void call(String who) {
        String[] c = resolve(who);
        if (c == null) {
            s.say(has(Manifest.permission.READ_CONTACTS) ? "I can't find " + who + " in your contacts." : "Contacts permission is off.");
            return;
        }
        try {
            String action = has(Manifest.permission.CALL_PHONE) ? Intent.ACTION_CALL : Intent.ACTION_DIAL;
            s.startActivity(new Intent(action, Uri.fromParts("tel", c[1], null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            s.say("Calling " + c[0]);
        } catch (Exception e) {
            s.say("I couldn't start the call.");
        }
    }

    private void answer() {
        try {
            if (has(Manifest.permission.ANSWER_PHONE_CALLS)) {
                ((TelecomManager) s.getSystemService(Context.TELECOM_SERVICE)).acceptRingingCall();
                return;
            }
        } catch (Exception ignored) { }
        if (!(sc.tapText("answer") || sc.tapText("accept"))) s.say("I couldn't answer.");
    }

    private void hangUp() {
        try {
            if (Build.VERSION.SDK_INT >= 28 && has(Manifest.permission.ANSWER_PHONE_CALLS)
                    && ((TelecomManager) s.getSystemService(Context.TELECOM_SERVICE)).endCall()) return;
        } catch (Exception ignored) { }
        if (!(sc.tapText("end call") || sc.tapText("hang up") || sc.tapText("decline"))) s.say("I couldn't end the call.");
    }

    // ---------- messages ----------

    private void sms(String who, String body) {
        String[] c = resolve(who);
        if (c == null) { s.say("I can't find " + who + " in your contacts."); return; }
        if (!has(Manifest.permission.SEND_SMS)) { s.say("Text message permission is off."); return; }
        try {
            SmsManager sm = SmsManager.getDefault();
            sm.sendMultipartTextMessage(c[1], null, sm.divideMessage(s.original(body)), null, null);
            s.say("Text sent to " + c[0]);
        } catch (Exception e) {
            s.say("The text could not be sent.");
        }
    }

    private void reply(String who, String body) {
        NotifService n = NotifService.instance;
        if (n == null) { s.say("Turn on notification access in Voice Pilot to reply."); return; }
        String to = n.reply(who, s.original(body));
        s.say(to == null ? "There is no message to reply to." : "Replied to " + to);
    }

    private void readMessages() {
        NotifService n = NotifService.instance;
        s.say(n == null ? "Turn on notification access in Voice Pilot first." : n.summary());
    }

    /** New message in a chat app: share the text into the app, then pick the contact and press send. */
    private void appMessage(String appName, final String who, String body) {
        ResolveInfo app = findApp(appName);
        if (app == null) { s.say("I can't find an app called " + appName); return; }
        try {
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, s.original(body))
                    .setPackage(app.activityInfo.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            s.startActivity(i);
        } catch (Exception e) {
            launch(app);
            s.say("Opened the app. Say tap, then the name.");
            return;
        }
        later(3000, () -> {
            if (!sc.tapText(who)) {
                s.say("I can't see " + who + ". Say tap search, type the name, then tap it and say send.");
                return;
            }
            later(1500, () -> { if (!sc.tapText("send")) s.say("Say send to finish."); });
        });
    }

    // ---------- media ----------

    private void media(int key) {
        AudioManager am = (AudioManager) s.getSystemService(Context.AUDIO_SERVICE);
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
    }

    private void volume(int direction) {
        AudioManager am = (AudioManager) s.getSystemService(Context.AUDIO_SERVICE);
        int steps = (direction == AudioManager.ADJUST_RAISE || direction == AudioManager.ADJUST_LOWER) ? 2 : 1;
        for (int i = 0; i < steps; i++) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
    }

    private void play(String what, String appName) {
        ResolveInfo app = appName == null ? null : findApp(appName);
        Intent i = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .putExtra(SearchManager.QUERY, what).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (app != null) i.setPackage(app.activityInfo.packageName);
        try {
            s.startActivity(i);
            return;
        } catch (Exception ignored) { }
        try {
            s.startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(what)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            s.say("I couldn't find a player for that.");
        }
    }

    private void flashlight(boolean on) {
        try {
            CameraManager cm = (CameraManager) s.getSystemService(Context.CAMERA_SERVICE);
            cm.setTorchMode(cm.getCameraIdList()[0], on);
        } catch (Exception e) {
            s.say("The flashlight isn't available.");
        }
    }
}
