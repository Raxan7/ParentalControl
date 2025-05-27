package com.example.parentalcontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * Utility class for showing notifications about app blocking events
 */
public class AlertNotifier {
    private static final String TAG = "AlertNotifier";
    private static final String CHANNEL_ID = "ALERTS_CHANNEL";
    private static final String CHANNEL_NAME = "Parental Alerts";
    private static final int NOTIFICATION_ID_BASE = 4000;
    private static int notificationCounter = 0;

    /**
     * Show a notification to the user about app blocking events
     */
    public static void showNotification(Context context, String title, String message) {
        try {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

            // Create notification channel for Android O and above
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_HIGH
                );
                manager.createNotificationChannel(channel);
            }
            
            // Intent to open app when notification is tapped
            Intent intent = new Intent(context, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE);
            
            // Get notification sound
            Uri defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            
            // Build the notification - use androidx.core.app.NotificationCompat for better compatibility
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_block)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setSound(defaultSoundUri)
                    .setContentIntent(pendingIntent);

            // Show notification with unique ID to avoid overwriting
            int notificationId = NOTIFICATION_ID_BASE + (notificationCounter++ % 10);
            manager.notify(notificationId, builder.build());
            
            // Log the event
            Log.d(TAG, "Alert notification shown: " + title + " - " + message);
            
            // Log to blocking debugger if available
            try {
                BlockingDebugger.log("Alert notification shown: " + title + " - " + message);
            } catch (Exception e) {
                // Ignore - BlockingDebugger might not be initialized
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error showing notification", e);
        }
    }
}