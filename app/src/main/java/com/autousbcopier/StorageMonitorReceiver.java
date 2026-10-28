package com.autousbcopier;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

final class StorageMonitorReceiver extends BroadcastReceiver {
    static final String CHANNEL_ID = "usb_storage";
    static final int CONNECTION_NOTIFICATION_ID = 1001;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        final String action = intent.getAction();
        final boolean usbAttached = UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action);
        final boolean usbDetached = UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action);
        final boolean mounted = Intent.ACTION_MEDIA_MOUNTED.equals(action)
                || usbAttached;
        if (!mounted && !Intent.ACTION_MEDIA_REMOVED.equals(action)
                && !Intent.ACTION_MEDIA_EJECT.equals(action)
                && !Intent.ACTION_MEDIA_UNMOUNTED.equals(action)
                && !usbDetached) {
            return;
        }
        if (usbAttached || usbDetached) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device == null || !UsbStorageDetector.isMassStorageDevice(device)) {
                return;
            }
            DebugLog.log(context, usbAttached
                    ? "USB mass-storage device connected."
                    : "USB mass-storage device disconnected.");
        }
        if (Intent.ACTION_MEDIA_MOUNTED.equals(action) && intent.getData() == null) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        final PendingResult result = goAsync();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mounted) {
                        boolean enabled = appContext.getSharedPreferences(
                                MainActivity.PREFERENCES, Context.MODE_PRIVATE)
                                .getBoolean(MainActivity.PREF_AUTO_NOTIFICATION, true);
                        if (enabled && UsbStorageDetector.hasMountedStorage(appContext)) {
                            showConnectionNotification(appContext);
                        }
                    } else if (!UsbStorageDetector.hasMountedStorage(appContext)) {
                        cancelConnectionNotification(appContext);
                    }
                } finally {
                    result.finish();
                }
            }
        }, usbAttached ? 2500 : 1200);
    }

    static void showConnectionNotification(Context context) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled()) {
            return;
        }
        createChannel(manager);

        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                context, 0, openIntent, pendingIntentFlags());
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID) : new Notification.Builder(context);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(context.getString(R.string.usb_connected_title))
                .setContentText(context.getString(R.string.usb_connected_message))
                .setContentIntent(openPendingIntent)
                .addAction(android.R.drawable.ic_menu_view,
                        context.getString(R.string.open_app), openPendingIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build();
        manager.notify(CONNECTION_NOTIFICATION_ID, notification);
    }

    static void cancelConnectionNotification(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(CONNECTION_NOTIFICATION_ID);
        }
    }

    static void createChannel(NotificationManager manager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && manager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "USB storage", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Alerts when removable USB storage is connected");
            manager.createNotificationChannel(channel);
        }
    }

    static int pendingIntentFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
    }
}
