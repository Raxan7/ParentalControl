package com.example.parentalcontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * Enhanced notification system for screen time and bedtime warnings
 * Supports progressive notification urgency and prevents notification spam
 */
public class EnhancedAlertNotifier {
    private static final String TAG = "EnhancedAlertNotifier";
    
    // Notification channels
    private static final String CHANNEL_SCREEN_TIME_LOW = "SCREEN_TIME_LOW";
    private static final String CHANNEL_SCREEN_TIME_NORMAL = "SCREEN_TIME_NORMAL"; 
    private static final String CHANNEL_SCREEN_TIME_HIGH = "SCREEN_TIME_HIGH";
    private static final String CHANNEL_SCREEN_TIME_CRITICAL = "SCREEN_TIME_CRITICAL";
    private static final String CHANNEL_BEDTIME_WARNING = "BEDTIME_WARNING";
    private static final String CHANNEL_BEDTIME_CRITICAL = "BEDTIME_CRITICAL";
    private static final String CHANNEL_RULE_UPDATE = "RULE_UPDATE";
    
    // Notification IDs
    private static final int NOTIFICATION_SCREEN_TIME_WARNING = 5001;
    private static final int NOTIFICATION_SCREEN_TIME_CRITICAL = 5002;
    private static final int NOTIFICATION_BEDTIME_WARNING = 5003;
    private static final int NOTIFICATION_BEDTIME_CRITICAL = 5004;
    private static final int NOTIFICATION_RULE_UPDATE = 5005;
    
    // Spam prevention
    private static final String PREFS_NAME = "notification_prefs";
    private static final long NOTIFICATION_COOLDOWN_MS = 60000; // 1 minute between same type notifications
    
    /**
     * Initialize notification channels
     */
    public static void initializeChannels(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            
            // Screen time notification channels
            createNotificationChannel(manager, CHANNEL_SCREEN_TIME_LOW, 
                "Screen Time Reminders", "Low priority screen time reminders", 
                NotificationManager.IMPORTANCE_LOW);
                
            createNotificationChannel(manager, CHANNEL_SCREEN_TIME_NORMAL, 
                "Screen Time Warnings", "Normal screen time warnings", 
                NotificationManager.IMPORTANCE_DEFAULT);
                
            createNotificationChannel(manager, CHANNEL_SCREEN_TIME_HIGH, 
                "Screen Time Alerts", "High priority screen time alerts", 
                NotificationManager.IMPORTANCE_HIGH);
                
            createNotificationChannel(manager, CHANNEL_SCREEN_TIME_CRITICAL, 
                "Screen Time Critical", "Critical screen time notifications", 
                NotificationManager.IMPORTANCE_HIGH);
            
            // Bedtime notification channels
            createNotificationChannel(manager, CHANNEL_BEDTIME_WARNING, 
                "Bedtime Warnings", "Bedtime approaching warnings", 
                NotificationManager.IMPORTANCE_DEFAULT);
                
            createNotificationChannel(manager, CHANNEL_BEDTIME_CRITICAL, 
                "Bedtime Critical", "Critical bedtime notifications", 
                NotificationManager.IMPORTANCE_HIGH);
                
            // Rule update notification channel
            createNotificationChannel(manager, CHANNEL_RULE_UPDATE, 
                "Rule Updates", "Screen time rule update notifications", 
                NotificationManager.IMPORTANCE_DEFAULT);
        }
    }
    
    @android.annotation.TargetApi(Build.VERSION_CODES.O)
    private static void createNotificationChannel(NotificationManager manager, String channelId, 
            String name, String description, int importance) {
        NotificationChannel channel = new NotificationChannel(channelId, name, importance);
        channel.setDescription(description);
        
        // Enable vibration for high priority channels
        if (importance >= NotificationManager.IMPORTANCE_HIGH) {
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{100, 200, 100, 200});
        }
        
        manager.createNotificationChannel(channel);
    }
    
    /**
     * Show screen time notification with priority level
     */
    public static void showScreenTimeNotification(Context context, String title, String message, 
            ScreenTimeCheckReceiver.NotificationPriority priority) {
        
        String channelId;
        int notificationId;
        int iconResource;
        int color;
        
        switch (priority) {
            case LOW:
                channelId = CHANNEL_SCREEN_TIME_LOW;
                notificationId = NOTIFICATION_SCREEN_TIME_WARNING;
                iconResource = R.drawable.ic_timer;
                color = 0xFF4CAF50; // Green
                break;
            case NORMAL:
                channelId = CHANNEL_SCREEN_TIME_NORMAL;
                notificationId = NOTIFICATION_SCREEN_TIME_WARNING;
                iconResource = R.drawable.ic_timer;
                color = 0xFFFF9800; // Orange
                break;
            case HIGH:
                channelId = CHANNEL_SCREEN_TIME_HIGH;
                notificationId = NOTIFICATION_SCREEN_TIME_WARNING;
                iconResource = R.drawable.ic_timer;
                color = 0xFFFF5722; // Deep Orange
                break;
            case CRITICAL:
                channelId = CHANNEL_SCREEN_TIME_CRITICAL;
                notificationId = NOTIFICATION_SCREEN_TIME_CRITICAL;
                iconResource = R.drawable.ic_block;
                color = 0xFFFF0000; // Red
                break;
            default:
                channelId = CHANNEL_SCREEN_TIME_NORMAL;
                notificationId = NOTIFICATION_SCREEN_TIME_WARNING;
                iconResource = R.drawable.ic_timer;
                color = 0xFFFF9800; // Orange
                break;
        }
        
        // Check for notification spam prevention
        if (!shouldShowNotification(context, "screen_time_" + priority.name())) {
            Log.d(TAG, "Skipping notification due to cooldown: " + title);
            return;
        }
        
        showNotificationInternal(context, channelId, notificationId, title, message, 
            iconResource, color, priority == ScreenTimeCheckReceiver.NotificationPriority.CRITICAL);
    }
    
    /**
     * Show bedtime warning notification
     */
    public static void showBedtimeWarning(Context context, String title, String message, boolean isCritical) {
        String channelId = isCritical ? CHANNEL_BEDTIME_CRITICAL : CHANNEL_BEDTIME_WARNING;
        int notificationId = isCritical ? NOTIFICATION_BEDTIME_CRITICAL : NOTIFICATION_BEDTIME_WARNING;
        int color = isCritical ? 0xFFFF0000 : 0xFF3F51B5; // Red for critical, Blue for warning
        
        // Check for notification spam prevention
        String notificationType = "bedtime_" + (isCritical ? "critical" : "warning");
        if (!shouldShowNotification(context, notificationType)) {
            Log.d(TAG, "Skipping bedtime notification due to cooldown: " + title);
            return;
        }
        
        showNotificationInternal(context, channelId, notificationId, title, message, 
            R.drawable.ic_notification, color, isCritical);
    }
    
    /**
     * Internal method to show notifications
     */
    private static void showNotificationInternal(Context context, String channelId, int notificationId,
            String title, String message, int iconResource, int color, boolean isUrgent) {
        
        try {
            // Initialize channels if not already done
            initializeChannels(context);
            
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            
            // Intent to open app when notification is tapped
            Intent intent = new Intent(context, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(context, notificationId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            
            // Build the notification
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(iconResource)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setColor(color)
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true);
            
            // Set priority based on urgency
            if (isUrgent) {
                builder.setPriority(NotificationCompat.PRIORITY_HIGH)
                       .setDefaults(NotificationCompat.DEFAULT_ALL)
                       .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
                       
                // Add LED light for critical notifications
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    builder.setLights(color, 500, 500);
                }
            } else {
                builder.setPriority(NotificationCompat.PRIORITY_DEFAULT);
            }
            
            // Show notification
            manager.notify(notificationId, builder.build());
            
            // Update last notification time
            updateLastNotificationTime(context, getNotificationKey(channelId, title));
            
            Log.d(TAG, "Enhanced notification shown: " + title + " (Channel: " + channelId + ")");
            
        } catch (Exception e) {
            Log.e(TAG, "Error showing enhanced notification", e);
        }
    }
    
    /**
     * Check if we should show notification (spam prevention)
     */
    private static boolean shouldShowNotification(Context context, String notificationType) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastNotificationTime = prefs.getLong("last_" + notificationType, 0);
        long currentTime = System.currentTimeMillis();
        
        return (currentTime - lastNotificationTime) >= NOTIFICATION_COOLDOWN_MS;
    }
    
    /**
     * Update last notification time for spam prevention
     */
    private static void updateLastNotificationTime(Context context, String notificationKey) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putLong("last_" + notificationKey, System.currentTimeMillis()).apply();
    }
    
    /**
     * Generate notification key for spam prevention
     */
    private static String getNotificationKey(String channelId, String title) {
        return channelId + "_" + title.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();
    }
    
    /**
     * Clear all screen time notifications
     */
    public static void clearScreenTimeNotifications(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.cancel(NOTIFICATION_SCREEN_TIME_WARNING);
        manager.cancel(NOTIFICATION_SCREEN_TIME_CRITICAL);
        Log.d(TAG, "Cleared all screen time notifications");
    }
    
    /**
     * Clear all bedtime notifications  
     */
    public static void clearBedtimeNotifications(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.cancel(NOTIFICATION_BEDTIME_WARNING);
        manager.cancel(NOTIFICATION_BEDTIME_CRITICAL);
        Log.d(TAG, "Cleared all bedtime notifications");
    }
    
    /**
     * Show rule update notification when screen time rules are changed from web interface
     */
    public static void showRuleUpdateNotification(Context context, long newLimitMinutes) {
        String notificationKey = "rule_update";
        
        // Check spam prevention (allow one notification every 5 minutes for rule updates)
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastNotificationTime = prefs.getLong("last_" + notificationKey, 0);
        long currentTime = System.currentTimeMillis();
        
        if ((currentTime - lastNotificationTime) < 300000) { // 5 minute cooldown for rule updates
            Log.d(TAG, "Rule update notification skipped due to cooldown");
            return;
        }
        
        try {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            
            // Format the time limit for display
            String limitText;
            if (newLimitMinutes >= 60) {
                long hours = newLimitMinutes / 60;
                long minutes = newLimitMinutes % 60;
                if (minutes == 0) {
                    limitText = hours + " hour" + (hours != 1 ? "s" : "");
                } else {
                    limitText = hours + "h " + minutes + "m";
                }
            } else {
                limitText = newLimitMinutes + " minute" + (newLimitMinutes != 1 ? "s" : "");
            }
            
            String title = "Screen Time Limit Updated";
            String message = "Your daily screen time limit has been changed to " + limitText + ". Screen time countdown has been reset.";
            
            // Create intent to open main activity
            Intent intent = new Intent(context, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_RULE_UPDATE)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setColor(context.getResources().getColor(android.R.color.holo_blue_light, null));
            
            // Show notification
            manager.notify(NOTIFICATION_RULE_UPDATE, builder.build());
            
            // Update last notification time
            prefs.edit().putLong("last_" + notificationKey, currentTime).apply();
            
            Log.d(TAG, "Rule update notification shown: New limit = " + newLimitMinutes + " minutes");
            
        } catch (Exception e) {
            Log.e(TAG, "Error showing rule update notification", e);
        }
    }
}
