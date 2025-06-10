// BedtimeEnforcer.java
package com.example.parentalcontrol;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Handles bedtime enforcement by checking current time against bedtime rules
 * Integrates with existing device locking system when bedtime periods are active
 */
public class BedtimeEnforcer {
    private static final String TAG = "BedtimeEnforcer";
    
    private final Context context;
    private final AppUsageDatabaseHelper dbHelper;
    
    public BedtimeEnforcer(Context context) {
        this.context = context;
        this.dbHelper = ServiceLocator.getInstance(context).getDatabaseHelper();
    }
    
    /**
     * Enhanced bedtime check with warning notifications
     * @return true if device should be locked due to bedtime
     */
    public boolean checkAndEnforceBedtime() {
        try {
            BedtimeRule rule = getCurrentBedtimeRule();
            if (rule == null) {
                Log.d(TAG, "No bedtime rules found");
                return false;
            }
            
            // Check current bedtime status
            boolean inBedtime = isCurrentTimeInBedtime(rule);
            
            // Check for approaching bedtime (warning notifications)
            int minutesToBedtime = getMinutesToBedtime(rule);
            
            Log.d(TAG, String.format("Enhanced bedtime check - Start: %s, End: %s, Current in bedtime: %s, Minutes to bedtime: %d", 
                    rule.bedtimeStart, rule.bedtimeEnd, inBedtime, minutesToBedtime));
            
            // Send warning notifications if bedtime is approaching
            if (!inBedtime && minutesToBedtime > 0 && minutesToBedtime <= 60) {
                sendBedtimeWarningNotifications(minutesToBedtime, rule);
            }
            
            if (inBedtime) {
                Log.d(TAG, "🌙 Current time is within bedtime period - triggering device lock");
                
                // Clear any warning notifications
                EnhancedAlertNotifier.clearBedtimeNotifications(context);
                
                triggerBedtimeLock();
                return true;
            }
            
            return false;
            
        } catch (Exception e) {
            Log.e(TAG, "Error checking bedtime enforcement", e);
            return false;
        }
    }
    
    /**
     * Calculate minutes until bedtime starts
     * @return minutes until bedtime, or -1 if bedtime has passed for today
     */
    private int getMinutesToBedtime(BedtimeRule rule) {
        try {
            Calendar now = Calendar.getInstance();
            int currentHour = now.get(Calendar.HOUR_OF_DAY);
            int currentMinute = now.get(Calendar.MINUTE);
            int currentTimeMinutes = currentHour * 60 + currentMinute;
            
            int startTimeMinutes = parseTimeToMinutes(rule.bedtimeStart);
            if (startTimeMinutes == -1) {
                return -1;
            }
            
            // Calculate minutes until bedtime
            int minutesToBedtime;
            
            if (startTimeMinutes > currentTimeMinutes) {
                // Bedtime is later today
                minutesToBedtime = startTimeMinutes - currentTimeMinutes;
            } else {
                // Bedtime is tomorrow (add 24 hours)
                minutesToBedtime = (24 * 60) - currentTimeMinutes + startTimeMinutes;
            }
            
            Log.d(TAG, String.format("Minutes to bedtime calculation - Current: %d, Start: %d, Result: %d", 
                    currentTimeMinutes, startTimeMinutes, minutesToBedtime));
            
            return minutesToBedtime;
            
        } catch (Exception e) {
            Log.e(TAG, "Error calculating minutes to bedtime", e);
            return -1;
        }
    }
    
    /**
     * Send progressive bedtime warning notifications
     */
    private void sendBedtimeWarningNotifications(int minutesToBedtime, BedtimeRule rule) {
        try {
            String title;
            String message;
            boolean isCritical = false;
            
            if (minutesToBedtime <= 5) {
                // Critical warning - 5 minutes or less
                title = "Bedtime in " + minutesToBedtime + " minutes!";
                message = "Bedtime starts at " + rule.bedtimeStart + ". Device will lock in " + minutesToBedtime + " minutes.";
                isCritical = true;
                
            } else if (minutesToBedtime <= 15) {
                // High priority - 15 minutes or less
                title = "Bedtime approaching - " + minutesToBedtime + " min";
                message = "Bedtime starts at " + rule.bedtimeStart + ". Please start winding down.";
                isCritical = false;
                
            } else if (minutesToBedtime <= 30) {
                // Normal warning - 30 minutes or less
                title = "Bedtime reminder";
                message = "Bedtime starts at " + rule.bedtimeStart + " (" + minutesToBedtime + " minutes). Time to prepare for bed.";
                isCritical = false;
                
            } else if (minutesToBedtime <= 60) {
                // Early warning - 60 minutes or less
                title = "Bedtime reminder";
                message = "Bedtime starts at " + rule.bedtimeStart + " in " + minutesToBedtime + " minutes.";
                isCritical = false;
                
            } else {
                return; // No notification needed
            }
            
            // Send the bedtime warning notification
            EnhancedAlertNotifier.showBedtimeWarning(context, title, message, isCritical);
            
            Log.d(TAG, String.format("Bedtime warning sent: %s (%d minutes to bedtime)", title, minutesToBedtime));
            
        } catch (Exception e) {
            Log.e(TAG, "Error sending bedtime warning notifications", e);
        }
    }
    
    /**
     * Get current bedtime rule from database
     */
    private BedtimeRule getCurrentBedtimeRule() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = null;
        
        try {
            cursor = db.rawQuery(
                "SELECT bedtime_start, bedtime_end FROM screen_time_rules " +
                "WHERE bedtime_start IS NOT NULL AND bedtime_end IS NOT NULL " +
                "ORDER BY last_updated DESC LIMIT 1",
                null
            );
            
            if (cursor.moveToFirst()) {
                String bedtimeStart = cursor.getString(0);
                String bedtimeEnd = cursor.getString(1);
                
                if (bedtimeStart != null && bedtimeEnd != null && 
                    !bedtimeStart.trim().isEmpty() && !bedtimeEnd.trim().isEmpty()) {
                    return new BedtimeRule(bedtimeStart, bedtimeEnd);
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error fetching bedtime rule", e);
        } finally {
            if (cursor != null) cursor.close();
            db.close();
        }
        
        return null;
    }
    
    /**
     * Check if current time falls within bedtime period
     * Handles overnight periods (e.g., 22:00 to 07:00)
     */
    private boolean isCurrentTimeInBedtime(BedtimeRule rule) {
        try {
            Calendar now = Calendar.getInstance();
            int currentHour = now.get(Calendar.HOUR_OF_DAY);
            int currentMinute = now.get(Calendar.MINUTE);
            int currentTimeMinutes = currentHour * 60 + currentMinute;
            
            // Parse bedtime start and end
            int startTimeMinutes = parseTimeToMinutes(rule.bedtimeStart);
            int endTimeMinutes = parseTimeToMinutes(rule.bedtimeEnd);
            
            Log.d(TAG, String.format("Time comparison - Current: %02d:%02d (%d min), Start: %d min, End: %d min", 
                    currentHour, currentMinute, currentTimeMinutes, startTimeMinutes, endTimeMinutes));
            
            if (startTimeMinutes == -1 || endTimeMinutes == -1) {
                Log.w(TAG, "Invalid bedtime format");
                return false;
            }
            
            // Handle overnight bedtime (e.g., 22:00 to 07:00)
            if (startTimeMinutes > endTimeMinutes) {
                // Overnight period: current time is either >= start OR <= end
                boolean result = (currentTimeMinutes >= startTimeMinutes) || (currentTimeMinutes <= endTimeMinutes);
                Log.d(TAG, "Overnight bedtime check: " + result);
                return result;
            } else {
                // Same day period: current time is >= start AND <= end
                boolean result = (currentTimeMinutes >= startTimeMinutes) && (currentTimeMinutes <= endTimeMinutes);
                Log.d(TAG, "Same-day bedtime check: " + result);
                return result;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error checking current time against bedtime", e);
            return false;
        }
    }
    
    /**
     * Parse time string (HH:mm or HH:mm:ss) to minutes since midnight
     */
    private int parseTimeToMinutes(String timeStr) {
        try {
            String[] parts = timeStr.split(":");
            if (parts.length >= 2) {
                int hours = Integer.parseInt(parts[0]);
                int minutes = Integer.parseInt(parts[1]);
                
                // Validate ranges
                if (hours >= 0 && hours <= 23 && minutes >= 0 && minutes <= 59) {
                    return hours * 60 + minutes;
                }
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "Failed to parse time: " + timeStr, e);
        }
        return -1;
    }
    
    /**
     * Trigger device lock for bedtime enforcement
     */
    private void triggerBedtimeLock() {
        Log.d(TAG, "Triggering bedtime device lock");
        
        // Use the same device locking mechanism as screen time limits
        Intent intent = new Intent(context, LockDeviceReceiver.class);
        intent.putExtra("lock_reason", "bedtime");
        context.sendBroadcast(intent);
    }
    
    /**
     * Data class for bedtime rule
     */
    private static class BedtimeRule {
        final String bedtimeStart;
        final String bedtimeEnd;
        
        BedtimeRule(String bedtimeStart, String bedtimeEnd) {
            this.bedtimeStart = bedtimeStart;
            this.bedtimeEnd = bedtimeEnd;
        }
    }
    
    /**
     * Get bedtime status information for UI display
     */
    public BedtimeStatus getBedtimeStatus() {
        try {
            BedtimeRule rule = getCurrentBedtimeRule();
            if (rule == null) {
                return new BedtimeStatus(false, null, null, false);
            }
            
            boolean inBedtime = isCurrentTimeInBedtime(rule);
            return new BedtimeStatus(true, rule.bedtimeStart, rule.bedtimeEnd, inBedtime);
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting bedtime status", e);
            return new BedtimeStatus(false, null, null, false);
        }
    }
    
    /**
     * Data class for bedtime status information
     */
    public static class BedtimeStatus {
        public final boolean hasBedtimeRules;
        public final String bedtimeStart;
        public final String bedtimeEnd;
        public final boolean inBedtimePeriod;
        
        public BedtimeStatus(boolean hasBedtimeRules, String bedtimeStart, String bedtimeEnd, boolean inBedtimePeriod) {
            this.hasBedtimeRules = hasBedtimeRules;
            this.bedtimeStart = bedtimeStart;
            this.bedtimeEnd = bedtimeEnd;
            this.inBedtimePeriod = inBedtimePeriod;
        }
        
        @Override
        public String toString() {
            return String.format("BedtimeStatus{hasRules=%s, start=%s, end=%s, inPeriod=%s}", 
                    hasBedtimeRules, bedtimeStart, bedtimeEnd, inBedtimePeriod);
        }
    }
}
