// ScreenTimeCalculator.java
package com.example.parentalcontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

public class ScreenTimeCalculator {
    private static final String TAG = "ScreenTimeCalculator";
    private static final String PREFS_NAME = "screen_time_calculator_prefs";
    private static final String KEY_LAST_UPDATED = "last_updated_timestamp";
    private static final String KEY_COUNTDOWN_START_TIME = "countdown_start_time";
    private static final String KEY_DAILY_LIMIT = "cached_daily_limit";
    // New keys for timer-based approach
    private static final String KEY_TIMER_TARGET_MINUTES = "timer_target_minutes";
    private static final String KEY_TIMER_START_USAGE = "timer_start_usage";
    private static final String KEY_TIMER_ACTIVE = "timer_active";
    
    private final AppUsageDatabaseHelper dbHelper;
    private final Context context;
    private final SharedPreferences prefs;

    public ScreenTimeCalculator(Context context) {
        this.context = context;
        this.dbHelper = ServiceLocator.getInstance(context).getDatabaseHelper();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Set up a timer-based screen time limit
     * This creates a timer that starts from current usage and adds limitMinutes
     * @param limitMinutes the additional minutes to allow from now
     */
    public void setTimerBasedLimit(long limitMinutes) {
        long currentUsage = getTodayUsageMinutes();
        long targetUsage = currentUsage + limitMinutes;
        long currentTime = System.currentTimeMillis();
        
        SharedPreferences.Editor editor = prefs.edit();
        editor.putLong(KEY_TIMER_TARGET_MINUTES, targetUsage);
        editor.putLong(KEY_TIMER_START_USAGE, currentUsage);
        editor.putLong(KEY_COUNTDOWN_START_TIME, currentTime);
        editor.putBoolean(KEY_TIMER_ACTIVE, true);
        editor.putLong(KEY_LAST_UPDATED, currentTime);
        editor.apply();
        
        Log.d(TAG, String.format("🎯 Timer-based limit set: Current usage=%d min, Additional=%d min, Target=%d min", 
                currentUsage, limitMinutes, targetUsage));
    }

    /**
     * Check if timer-based limit is active and has been exceeded
     */
    public boolean isTimerLimitExceeded() {
        boolean timerActive = prefs.getBoolean(KEY_TIMER_ACTIVE, false);
        if (!timerActive) {
            return false;
        }
        
        long targetUsage = prefs.getLong(KEY_TIMER_TARGET_MINUTES, 0);
        long currentUsage = getTodayUsageMinutes();
        
        return currentUsage >= targetUsage;
    }

    /**
     * Get remaining time for timer-based limit
     */
    public long getTimerRemainingMinutes() {
        boolean timerActive = prefs.getBoolean(KEY_TIMER_ACTIVE, false);
        if (!timerActive) {
            return -1; // No timer active
        }
        
        long targetUsage = prefs.getLong(KEY_TIMER_TARGET_MINUTES, 0);
        long currentUsage = getTodayUsageMinutes();
        
        return Math.max(0, targetUsage - currentUsage);
    }

    /**
     * Get detailed timer status for debugging
     */
    public String getTimerStatus() {
        boolean timerActive = prefs.getBoolean(KEY_TIMER_ACTIVE, false);
        if (!timerActive) {
            return "❌ No timer active";
        }
        
        long targetUsage = prefs.getLong(KEY_TIMER_TARGET_MINUTES, 0);
        long startUsage = prefs.getLong(KEY_TIMER_START_USAGE, 0);
        long currentUsage = getTodayUsageMinutes();
        long remaining = Math.max(0, targetUsage - currentUsage);
        
        return String.format("🎯 Timer Active - Start: %dm, Current: %dm, Target: %dm, Remaining: %dm", 
                startUsage, currentUsage, targetUsage, remaining);
    }

    /**
     * Clear timer-based limit
     */
    public void clearTimerLimit() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(KEY_TIMER_ACTIVE, false);
        editor.remove(KEY_TIMER_TARGET_MINUTES);
        editor.remove(KEY_TIMER_START_USAGE);
        editor.apply();
        
        Log.d(TAG, "🔄 Timer-based limit cleared");
    }

    /**
     * Simple rule check - change detection now handled by server-side sync flags
     * This method only checks if local database has valid rules
     * @return false - change detection moved to server-side sync flag approach
     */
    public boolean checkAndUpdateRulesIfChanged() {
        // SERVER-SIDE SYNC FLAG APPROACH:
        // Change detection is now handled entirely by the server using sync flags.
        // The sync services (PeriodicHttpSyncService and DataSyncService) handle
        // applying rule changes when the server indicates there are unsynced updates.
        // This eliminates complex client-side timestamp comparison that was causing
        // unwanted timer resets.
        
        Log.d(TAG, "📊 Rule change detection now handled by server-side sync flags");
        return false; // Never trigger timer resets from this method
    }
    public long getTodayUsageMinutes() {
        long todayStart = getStartOfDay();
        long currentTime = System.currentTimeMillis();
        
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = null;
        long totalUsageMs = 0;
        int sessionCount = 0;

        try {
            // Get all app usage sessions that started or ended today
            cursor = db.rawQuery(
                "SELECT start_time, end_time, app_name FROM app_usage WHERE " +
                "(start_time >= ? OR end_time >= ?) AND start_time <= ? " +
                "ORDER BY start_time ASC",
                new String[]{
                    String.valueOf(todayStart),
                    String.valueOf(todayStart),
                    String.valueOf(currentTime)
                }
            );

            Log.d(TAG, String.format("Found %d app usage sessions for today (start: %d, current: %d)", 
                    cursor.getCount(), todayStart, currentTime));

            while (cursor.moveToNext()) {
                long startTime = cursor.getLong(0);
                long endTime = cursor.getLong(1);
                String appName = cursor.getString(2);
                
                // Adjust times to today's boundaries
                long sessionStart = Math.max(startTime, todayStart);
                long sessionEnd = Math.min(endTime, currentTime);
                
                // Only count if session is valid (end > start)
                if (sessionEnd > sessionStart) {
                    long sessionDuration = sessionEnd - sessionStart;
                    totalUsageMs += sessionDuration;
                    sessionCount++;
                    
                    Log.d(TAG, String.format("Session %d: %s - %d ms (start: %d, end: %d)", 
                            sessionCount, appName, sessionDuration, sessionStart, sessionEnd));
                } else {
                    Log.d(TAG, String.format("Skipping invalid session: %s (start: %d >= end: %d)", 
                            appName, sessionStart, sessionEnd));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error calculating today's usage", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            db.close();
        }

        long usageMinutes = TimeUnit.MILLISECONDS.toMinutes(totalUsageMs);
        Log.d(TAG, String.format("Total usage today: %d minutes (%d ms) from %d sessions", 
                usageMinutes, totalUsageMs, sessionCount));
        
        // Also log some recent sessions for debugging
        logRecentSessions();
        
        return usageMinutes;
    }
    
    /**
     * Log recent app usage sessions for debugging
     */
    private void logRecentSessions() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = null;
        
        try {
            cursor = db.rawQuery(
                "SELECT app_name, start_time, end_time, (end_time - start_time) as duration " +
                "FROM app_usage ORDER BY end_time DESC LIMIT 10",
                null
            );
            
            Log.d(TAG, "=== Recent 10 app usage sessions ===");
            while (cursor.moveToNext()) {
                String appName = cursor.getString(0);
                long startTime = cursor.getLong(1);
                long endTime = cursor.getLong(2);
                long duration = cursor.getLong(3);
                
                Log.d(TAG, String.format("%s: %d ms (start: %d, end: %d)", 
                        appName, duration, startTime, endTime));
            }
            Log.d(TAG, "=== End recent sessions ===");
        } catch (Exception e) {
            Log.e(TAG, "Error logging recent sessions", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            db.close();
        }
    }

    /**
     * Get remaining screen time for today
     * @param dailyLimitMinutes daily limit in minutes
     * @return remaining time in minutes (0 if limit exceeded)
     */
    public long getRemainingTimeMinutes(long dailyLimitMinutes) {
        long usedMinutes = getTodayUsageMinutes();
        return Math.max(0, dailyLimitMinutes - usedMinutes);
    }

    /**
     * Get percentage of daily limit used
     * @param dailyLimitMinutes daily limit in minutes
     * @return percentage (0-100+)
     */
    public float getUsagePercentage(long dailyLimitMinutes) {
        if (dailyLimitMinutes <= 0) return 0f;
        long usedMinutes = getTodayUsageMinutes();
        return (usedMinutes * 100f) / dailyLimitMinutes;
    }

    /**
     * Check if daily limit has been exceeded
     * @param dailyLimitMinutes daily limit in minutes
     * @return true if limit exceeded
     */
    public boolean isLimitExceeded(long dailyLimitMinutes) {
        return getTodayUsageMinutes() >= dailyLimitMinutes;
    }

    /**
     * Get estimated time until limit is reached based on current usage pattern
     * @param dailyLimitMinutes daily limit in minutes
     * @return minutes until limit (negative if already exceeded)
     */
    public long getEstimatedTimeUntilLimit(long dailyLimitMinutes) {
        long usedMinutes = getTodayUsageMinutes();
        long remainingMinutes = dailyLimitMinutes - usedMinutes;
        
        if (remainingMinutes <= 0) {
            return remainingMinutes; // Already exceeded
        }

        // Simple estimation: if we've been using the device for X minutes 
        // over Y hours, estimate when we'll hit the limit
        long dayProgressMs = System.currentTimeMillis() - getStartOfDay();
        long dayProgressMinutes = TimeUnit.MILLISECONDS.toMinutes(dayProgressMs);
        
        if (dayProgressMinutes > 0 && usedMinutes > 0) {
            // Usage rate in minutes per hour
            float usageRate = (float) usedMinutes / (dayProgressMinutes / 60f);
            
            if (usageRate > 0) {
                // Estimate time to reach limit based on current rate
                long minutesToLimit = (long) (remainingMinutes / usageRate * 60);
                Log.d(TAG, String.format("Usage rate: %.2f min/hour, estimated time to limit: %d minutes", 
                        usageRate, minutesToLimit));
                return Math.min(minutesToLimit, remainingMinutes);
            }
        }
        
        return remainingMinutes;
    }

    /**
     * Get screen time data for display
     */
    public ScreenTimeData getScreenTimeData(long dailyLimitMinutes) {
        long usedMinutes = getTodayUsageMinutes();
        long remainingMinutes = Math.max(0, dailyLimitMinutes - usedMinutes);
        float percentageUsed = (dailyLimitMinutes > 0) ? 
                (usedMinutes * 100f) / dailyLimitMinutes : 0f;
        
        return new ScreenTimeData(dailyLimitMinutes, usedMinutes, remainingMinutes, percentageUsed);
    }

    private long getStartOfDay() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    /**
     * Data class for screen time information
     */
    public static class ScreenTimeData {
        public final long dailyLimitMinutes;
        public final long usedMinutes;
        public final long remainingMinutes;
        public final float percentageUsed;

        public ScreenTimeData(long dailyLimitMinutes, long usedMinutes, long remainingMinutes, float percentageUsed) {
            this.dailyLimitMinutes = dailyLimitMinutes;
            this.usedMinutes = usedMinutes;
            this.remainingMinutes = remainingMinutes;
            this.percentageUsed = percentageUsed;
        }

        public boolean isLimitExceeded() {
            return remainingMinutes <= 0;
        }

        public boolean isWarningState() {
            return remainingMinutes <= 15 && remainingMinutes > 0;
        }

        @Override
        public String toString() {
            return String.format("ScreenTimeData{used=%dm, remaining=%dm, limit=%dm, %.1f%%}", 
                    usedMinutes, remainingMinutes, dailyLimitMinutes, percentageUsed);
        }
    }

    /**
     * Data class for countdown-specific screen time information
     */
    public static class ScreenTimeCountdownData {
        public final long dailyLimitMinutes;
        public final long usedMinutes;
        public final long remainingMinutes;
        public final float percentageUsed;
        public final boolean wasUpdated; // Indicates if rules changed

        public ScreenTimeCountdownData(long dailyLimitMinutes, long usedMinutes, long remainingMinutes, 
                                     float percentageUsed, boolean wasUpdated) {
            this.dailyLimitMinutes = dailyLimitMinutes;
            this.usedMinutes = usedMinutes;
            this.remainingMinutes = remainingMinutes;
            this.percentageUsed = percentageUsed;
            this.wasUpdated = wasUpdated;
        }

        public boolean isLimitExceeded() {
            return remainingMinutes <= 0;
        }

        public boolean isWarningState() {
            return remainingMinutes <= 15 && remainingMinutes > 0;
        }

        @Override
        public String toString() {
            return String.format("CountdownData{used=%dm, remaining=%dm, limit=%dm, %.1f%%, updated=%s}", 
                    usedMinutes, remainingMinutes, dailyLimitMinutes, percentageUsed, wasUpdated);
        }
    }

    /**
     * Get remaining time based on actual usage only (no wall-clock countdown)
     * This provides accurate countdown based on real app usage time
     */
    public long getRemainingTimeMinutesFromCountdown(long dailyLimitMinutes) {
        // Only use actual usage for countdown - this fixes the timing discrepancy issue
        long actualUsedMinutes = getTodayUsageMinutes();
        long actualRemainingMinutes = Math.max(0, dailyLimitMinutes - actualUsedMinutes);
        
        Log.d(TAG, String.format("Accurate countdown calculation - Used: %d min, Limit: %d min, Remaining: %d min", 
              actualUsedMinutes, dailyLimitMinutes, actualRemainingMinutes));
        
        return actualRemainingMinutes;
    }

    /**
     * Get cached daily limit (avoids database queries)
     */
    public long getCachedDailyLimit() {
        return prefs.getInt(KEY_DAILY_LIMIT, 120); // Default 2 hours
    }

    /**
     * Get screen time data optimized for countdown display
     * Now supports both timer-based and traditional limit approaches
     */
    public ScreenTimeCountdownData getCountdownData() {
        // SIMPLIFIED: Don't check for rule changes here, let sync services handle that
        // This prevents conflicts between ScreenTimeCalculator and sync services
        boolean wasUpdated = false;
        
        // Check if timer-based limit is active
        boolean timerActive = prefs.getBoolean(KEY_TIMER_ACTIVE, false);
        
        long dailyLimitMinutes = getCachedDailyLimit();
        long usedMinutes = getTodayUsageMinutes(); // Actual usage
        long remainingMinutes;
        
        if (timerActive) {
            // Use timer-based calculation
            remainingMinutes = getTimerRemainingMinutes();
            Log.d(TAG, String.format("🎯 Timer-based countdown - Used: %d min, Timer remaining: %d min", 
                    usedMinutes, remainingMinutes));
        } else {
            // Fallback to traditional approach
            remainingMinutes = Math.max(0, dailyLimitMinutes - usedMinutes);
            Log.d(TAG, String.format("📊 Traditional countdown - Used: %d min, Remaining: %d min, Limit: %d min", 
                    usedMinutes, remainingMinutes, dailyLimitMinutes));
        }
        
        float percentageUsed = (dailyLimitMinutes > 0) ? 
                (usedMinutes * 100f) / dailyLimitMinutes : 0f;
        
        return new ScreenTimeCountdownData(dailyLimitMinutes, usedMinutes, remainingMinutes, 
                                          percentageUsed, wasUpdated);
    }

    /**
     * Debug method to monitor timing accuracy in real-time
     */
    public void debugTimingAccuracy() {
        try {
            long todayStart = getStartOfDay();
            long currentTime = System.currentTimeMillis();
            long wallClockTimeElapsed = (currentTime - todayStart) / (60 * 1000); // minutes since start of day
            
            long actualUsageMinutes = getTodayUsageMinutes();
            long dailyLimit = getCachedDailyLimit();
            
            Log.d(TAG, "========== TIMING ACCURACY DEBUG ==========");
            Log.d(TAG, String.format("⏰ Wall clock time since start of day: %d minutes", wallClockTimeElapsed));
            Log.d(TAG, String.format("📱 Actual app usage time tracked: %d minutes", actualUsageMinutes));
            Log.d(TAG, String.format("🎯 Daily limit: %d minutes", dailyLimit));
            Log.d(TAG, String.format("⚖️ Usage accuracy ratio: %.2f%% (lower = more idle time)", 
                    wallClockTimeElapsed > 0 ? (actualUsageMinutes * 100.0f) / wallClockTimeElapsed : 0));
            Log.d(TAG, String.format("📊 Progress: %.1f%% of daily limit used", 
                    dailyLimit > 0 ? (actualUsageMinutes * 100.0f) / dailyLimit : 0));
            Log.d(TAG, "============================================");
            
        } catch (Exception e) {
            Log.e(TAG, "Error in timing accuracy debug", e);
        }
    }
}
