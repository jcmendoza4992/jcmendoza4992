package com.voicepilot.app;

import android.app.Notification;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Reads message notifications and answers them through their built-in "Reply" action (Messenger, WhatsApp, SMS, ...). */
public class NotifService extends NotificationListenerService {
    static NotifService instance;

    @Override public void onListenerConnected() { instance = this; }
    @Override public void onListenerDisconnected() { instance = null; }

    private List<StatusBarNotification> newestFirst() {
        List<StatusBarNotification> list = new ArrayList<>();
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all != null) list.addAll(Arrays.asList(all));
        } catch (Exception ignored) { }
        list.sort((a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
        return list;
    }

    private static String title(StatusBarNotification n) {
        Bundle e = n.getNotification().extras;
        CharSequence conv = e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        CharSequence t = e.getCharSequence(Notification.EXTRA_TITLE);
        return (t == null ? "" : t.toString()) + (conv == null ? "" : " " + conv);
    }

    private static Notification.Action replyAction(StatusBarNotification n) {
        Notification.Action[] actions = n.getNotification().actions;
        if (actions == null) return null;
        for (Notification.Action a : actions) {
            RemoteInput[] in = a.getRemoteInputs();
            if (in != null && in.length > 0 && a.actionIntent != null) return a;
        }
        return null;
    }

    /** Replies to the newest message notification; if who is not null the sender must match. Returns the sender, or null. */
    String reply(String who, String message) {
        String want = who == null ? null : Screen.clean(who);
        for (StatusBarNotification n : newestFirst()) {
            Notification.Action a = replyAction(n);
            if (a == null) continue;
            String t = title(n);
            if (want != null && !Screen.clean(t).contains(want)) continue;
            try {
                Intent i = new Intent();
                Bundle b = new Bundle();
                for (RemoteInput r : a.getRemoteInputs()) b.putCharSequence(r.getResultKey(), message);
                RemoteInput.addResultsToIntent(a.getRemoteInputs(), i, b);
                a.actionIntent.send((Context) this, 0, i);
                return t.trim().isEmpty() ? "them" : t.trim();
            } catch (Exception ignored) { }
        }
        return null;
    }

    /** Spoken summary of the newest message notifications. */
    String summary() {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (StatusBarNotification n : newestFirst()) {
            if (replyAction(n) == null && !Notification.CATEGORY_MESSAGE.equals(n.getNotification().category)) continue;
            CharSequence text = n.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT);
            if (text == null) continue;
            sb.append(title(n).trim()).append(" says: ").append(text).append(". ");
            if (++count >= 4) break;
        }
        return count == 0 ? "No new messages." : sb.toString();
    }
}
