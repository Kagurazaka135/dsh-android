package com.deepseek.harness;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;

/**
 * Foreground owner for the DSH engine. Its watchdog is intentionally service-owned rather than
 * Activity-owned, so leaving the UI does not stop recovery and START_STICKY can restore it.
 */
public class EngineService extends Service {
    private static final String TAG = "DeepSeekHarness";
    private static final String CHANNEL_ID = "dsh_engine";
    private static final int NOTIF_ID = 1;
    private static final long KEEPALIVE_INTERVAL_MS = 15_000L;
    private static final long RESTART_COOLDOWN_MS = 120_000L;
    private static final int KEEPALIVE_MAX_FAILURES = 3;

    private final Object watchdogLock = new Object();
    private volatile boolean stopRequested;
    private Thread watchdog;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        // This must happen immediately after a foreground-service launch, including a sticky
        // recreation that will be stopped below when the user had opted out.
        startForeground(NOTIF_ID, buildNotification("DeepSeek Harness 正在运行", "AI 引擎保活中，后台任务持续执行"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (EngineCore.isUserStopped(this)) {
            Log.i(TAG, "engine service start ignored: user stopped engine");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIF_ID, buildNotification("DeepSeek Harness 正在运行", "AI 引擎保活中，后台任务持续执行"));
        }
        startWatchdogIfNeeded();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopRequested = true;
        Thread thread;
        synchronized (watchdogLock) {
            thread = watchdog;
            watchdog = null;
        }
        if (thread != null) thread.interrupt();
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTIF_ID);
        super.onDestroy();
    }

    private void startWatchdogIfNeeded() {
        synchronized (watchdogLock) {
            if (watchdog != null && watchdog.isAlive()) return;
            stopRequested = false;
            watchdog = new Thread(new Runnable() {
                @Override public void run() {
                    runWatchdog();
                }
            }, "engine-watchdog");
            watchdog.start();
        }
    }

    private void runWatchdog() {
        int failures = 0;
        long lastRestartAt = 0L;
        final Context app = getApplicationContext();
        while (!stopRequested && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(KEEPALIVE_INTERVAL_MS);
            } catch (InterruptedException interrupted) {
                return;
            }
            if (stopRequested) return;
            if (EngineCore.isUserStopped(app)) {
                // A persisted stop choice is final, including after START_STICKY recreates us.
                stopSelf();
                return;
            }
            try {
                if (EngineCore.healthOk(app)) {
                    failures = 0;
                    continue;
                }
                // A half-started node can listen before its HTTP page is ready. It is not a
                // restart candidate; only a refused TCP connection increments the failure count.
                int port = EngineCore.enginePort(app);
                if (EngineCore.portListening(port) || EngineCore.hasLiveNodeProcess()) {
                    failures = 0;
                    continue;
                }
                failures++;
                long now = SystemClock.uptimeMillis();
                if (failures < KEEPALIVE_MAX_FAILURES || now - lastRestartAt < RESTART_COOLDOWN_MS) continue;
                lastRestartAt = now;
                failures = 0;
                Log.w(TAG, "engine unavailable for " + KEEPALIVE_MAX_FAILURES + " checks; controlled respawn");
                EngineCore.spawnNode(app, new File(app.getFilesDir(), "payload"), null);
            } catch (Throwable error) {
                // A probe/spawn error must not kill the foreground service's recovery loop.
                Log.w(TAG, "engine watchdog error", error);
            }
        }
    }

    private Notification buildNotification(String title, String text) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pending)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "引擎保活", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("DeepSeek Harness 引擎运行状态");
        manager.createNotificationChannel(channel);
    }
}
