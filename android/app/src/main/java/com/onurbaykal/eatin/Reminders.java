package com.onurbaykal.eatin;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * The streak reminder: at most one local notification waiting at a time.
 *
 * The page decides when (see `reminder` in index.html) and calls schedule()
 * through MainActivity's EatInReminder bridge after every round, which moves
 * the reminder on. The time and text are kept in SharedPreferences so the
 * alarm can be set again after a reboot or an app update, which clear alarms.
 * Nothing leaves the phone.
 */
public class Reminders extends BroadcastReceiver {
    static final String ACTION = "com.onurbaykal.eatin.REMIND";
    private static final String PREFS = "reminder";
    private static final String CHANNEL = "streak";

    static void schedule(Context c, long at, String title, String text) {
        prefs(c).edit().putLong("at", at).putString("title", title).putString("text", text).apply();
        arm(c, at);
    }

    static void cancel(Context c) {
        prefs(c).edit().clear().apply();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(alarmIntent(c));
    }

    /** Notifications are on for the app (and, on Android 13+, the permission is granted). */
    static boolean allowed(Context c) {
        if (Build.VERSION.SDK_INT >= 33
            && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.areNotificationsEnabled();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static PendingIntent alarmIntent(Context c) {
        Intent i = new Intent(c, Reminders.class).setAction(ACTION);
        return PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Inexact on purpose: no exact-alarm permission, and a few minutes either way is fine. */
    private static void arm(Context c, long at) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null || at <= System.currentTimeMillis()) return;
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(c));
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        SharedPreferences p = prefs(c);
        if (ACTION.equals(action)) {
            String title = p.getString("title", null), text = p.getString("text", null);
            p.edit().clear().apply();
            if (title != null && allowed(c)) show(c, title, text);
        } else {
            // BOOT_COMPLETED or MY_PACKAGE_REPLACED: alarms were cleared, set it again.
            arm(c, p.getLong("at", 0));
        }
    }

    @SuppressWarnings("deprecation")   // Notification.Builder(Context), only below Android 8
    private static void show(Context c, String title, String text) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Streak reminders", NotificationManager.IMPORTANCE_DEFAULT));
            b = new Notification.Builder(c, CHANNEL);
        } else {
            b = new Notification.Builder(c);
        }
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.setSmallIcon(R.drawable.ic_stat_eatin)
            .setColor(0xFF23C2B0)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tap)
            .setAutoCancel(true);
        nm.notify(1, b.build());
    }
}
