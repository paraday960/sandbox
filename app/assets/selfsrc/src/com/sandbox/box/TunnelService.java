package com.sandbox.box;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * سرویس Foreground — نگه‌دارنده‌ی تونل در پس‌زمینه
 * بدون این، اندروید بعد از مدتی پروسه‌ی اپ در پس‌زمینه را می‌کشد
 * و cloudflared می‌میرد. با این سرویس، پروسه اولویت «foreground» دارد.
 */
public class TunnelService extends Service {

    private static final String CH = "sandbox_tunnel";
    private static final int NOTIF_ID = 1001;
    private PowerManager.WakeLock wl;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CH, "تونل سندباکس", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("برای پایداری اتصال در پس‌زمینه");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        b.setContentTitle("سندباکس فعال 🌉")
                .setContentText("تونل در حال اجراست — اتصال راه‌دور پایدار است")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .setContentIntent(pi)
                .setPriority(Notification.PRIORITY_LOW);
        startForeground(NOTIF_ID, b.build());

        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SandBox:svc");
            wl.setReferenceCounted(false);
            wl.acquire(12L * 60 * 60 * 1000);
        } catch (Exception ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // اگر سیستم کشت، دوباره بالا بیا
    }

    @Override
    public void onDestroy() {
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
