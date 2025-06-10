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
    
    private final AppUsageDatabaseHelper dbHelper;
    private final Context context;
    private final SharedPreferences prefs;

    public ScreenTimeCalculator(Context context) {
        this.context = context;
        this.dbHelper = ServiceLocator.getInstance(context).getDatabaseHelper();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Check if screen time rules have been updated and refresh countdown if needed
     * @return true if rules were updated and countdown was reset
     */
    public boolean checkAndUpdateRulesIfChanged() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = null;
        boolean wasUpdated = false;
        
        try {
            cursor = db.rawQuery(
                "SELECT daily_limit_minutes, last_updated FROM screen_time_rules ORDER BY last_updated DESC LIMIT 1",
                null
            );
            
            if (cursor.moveToFirst()) {
                int dailyLimitMinutes = cursor.getInt(0);
                long lastUpdated = cursor.getLong(1);
                
                // Get cached last_updated timestamp
                long cachedLastUpdated = prefs.getLong(KEY_LAST_UPDATED, 0);
                
                // Check if the server data has actually changed
                if (lastUpdated != cachedLastUpdated) {
                    Log.d(TAG, String.format("🔄 Screen time rule update detected from web interface!"));
                    Log.d(TAG, String.format("   Previous timestamp: %d (%s)", cachedLastUpdated, 
                          cachedLastUpdated > 0 ? new java.util.Date(cachedLastUpdated).toString() : "Never"));
                    Log.d(TAG, String.format("   New timestamp: %d (%s)", lastUpdated, new java.util.Date(lastUpdated).toString()));
                    Log.d(TAG, String.format("   New daily limit: %d minutes", dailyLimitMinutes));
                    
                    // Reset the countdown start time since we have new rules
                    long currentTime = System.currentTimeMillis();
                    
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putLong(KEY_LAST_UPDATED, lastUpdated);
                    editor.putLong(KEY_COUNTDOWN_START_TIME, currentTime);
                    editor.putInt(KEY_DAILY_LIMIT, dailyLimitMinutes);
                    editor.apply();
                    
                    Log.d(TAG, "✅ Screen time countdown reset due to web interface update. New limit: " + dailyLimitMinutes + " minutes");
                    wasUpdated = true;
                } else {
                    Log.d(TAG, "No change in screen time rules, keeping existing countdown");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking screen time rule updates", e);
        } finally {
            if (cursor != null) cursor.close();
            db.close();
        }
        
        return wasUpdated;
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
     * Uses actual usage time only for accurate countdown (fixes timing discrepancy)
     */
    public ScreenTimeCountdownData getCountdownData() {
        // Check if rules were updated first
        boolean wasUpdated = checkAndUpdateRulesIfChanged();
        
        long dailyLimitMinutes = getCachedDailyLimit();
        long usedMinutes = getTodayUsageMinutes(); // Actual usage
        long remainingMinutes = Math.max(0, dailyLimitMinutes - usedMinutes); // Only use actual usage
        
        float percentageUsed = (dailyLimitMinutes > 0) ? 
                (usedMinutes * 100f) / dailyLimitMinutes : 0f;
        
        Log.d(TAG, String.format("Accurate countdown data - Used: %d min, Remaining: %d min, Limit: %d min, %.1f%%", 
                usedMinutes, remainingMinutes, dailyLimitMinutes, percentageUsed));
        
        return new ScreenTimeCountdownData(dailyLimitMinutes, usedMinutes, remainingMinutes, 
                                          percentageUsed, wasUpdated);
    }

    /**
     * Debug method to compare wall-clock time vs actual usage tracking
     * Helps identify timing discrepancies
     */
    public void debugTimingAccuracy() {
        long todayStart = getStartOfDay();
        long currentTime = System.currentTimeMillis();
        long wallClockMinutesSinceStartOfDay = (currentTime - todayStart) / (60 * 1000);
        long actualUsageMinutes = getTodayUsageMinutes();
        
        float usagePercentageOfWallClock = wallClockMinutesSinceStartOfDay > 0 ? 
            (actualUsageMinutes * 100f / wallClockMinutesSinceStartOfDay) : 0f;
        
        Log.d(TAG, "=== TIMING ACCURACY DEBUG ===");
        Log.d(TAG, String.format("Wall-clock time since start of day: %d minutes", wallClockMinutesSinceStartOfDay));
        Log.d(TAG, String.format("Actual tracked usage: %d minutes", actualUsageMinutes));
        Log.d(TAG, String.format("Usage efficiency: %.1f%% (actual vs wall-clock)", usagePercentageOfWallClock));
        Log.d(TAG, String.format("Time ratio: 1 minute tracked = %.2f minutes real time", 
            wallClockMinutesSinceStartOfDay > 0 ? (wallClockMinutesSinceStartOfDay / (float)actualUsageMinutes) : 0f));
        Log.d(TAG, "=== END TIMING DEBUG ===");
    }
}
