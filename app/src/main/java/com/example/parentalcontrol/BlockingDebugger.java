package com.example.parentalcontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * A debugging utility for app blocking functionality
 * Helps track and debug the app blocking process, especially for immediate blocking
 */
public class BlockingDebugger {
    private static final String TAG = "BlockingDebugger";
    private static final String PREFS_NAME = "blocking_debugger_prefs";
    private static final String PREF_DEBUG_ENABLED = "debug_enabled";
    private static final int MAX_LOG_ENTRIES = 100;
    
    private static LinkedList<LogEntry> logEntries = new LinkedList<>();
    private static boolean isDebugEnabled = false;
    private static boolean isRegisteredForEvents = false;
    
    /**
     * Initialize the debugger
     */
    public static void init(Context context) {
        // Load debug state from preferences
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        isDebugEnabled = prefs.getBoolean(PREF_DEBUG_ENABLED, false);
        
        // Register for events if debug is enabled
        if (isDebugEnabled && !isRegisteredForEvents) {
            EventBus.getDefault().register(new BlockingDebugger());
            isRegisteredForEvents = true;
        }
        
        log("BlockingDebugger initialized, debug enabled: " + isDebugEnabled);
    }
    
    /**
     * Enable or disable debugging
     */
    public static void setDebugEnabled(Context context, boolean enabled) {
        if (isDebugEnabled != enabled) {
            isDebugEnabled = enabled;
            
            // Save state
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(PREF_DEBUG_ENABLED, enabled).apply();
            
            // Register/unregister for events
            if (enabled && !isRegisteredForEvents) {
                EventBus.getDefault().register(new BlockingDebugger());
                isRegisteredForEvents = true;
            } else if (!enabled && isRegisteredForEvents) {
                EventBus.getDefault().unregister(new BlockingDebugger());
                isRegisteredForEvents = false;
            }
            
            log("Debug mode " + (enabled ? "enabled" : "disabled"));
        }
    }
    
    /**
     * Log a blocking-related event
     */
    public static void log(String message) {
        if (isDebugEnabled) {
            LogEntry entry = new LogEntry(message);
            logEntries.addFirst(entry);
            
            // Trim log if it gets too large
            while (logEntries.size() > MAX_LOG_ENTRIES) {
                logEntries.removeLast();
            }
            
            // Also log to Android system log
            Log.d(TAG, message);
        }
    }
    
    /**
     * Get all log entries for display
     */
    public static List<LogEntry> getLogEntries() {
        return new ArrayList<>(logEntries);
    }
    
    /**
     * Clear all log entries
     */
    public static void clearLogs() {
        logEntries.clear();
        log("Log cleared");
    }
    
    /**
     * Test the immediate blocking mechanism
     */
    public static void testImmediateBlocking(Context context, BlockedAppsManager.TestResultListener listener) {
        log("Starting immediate blocking test...");
        BlockedAppsManager.enableTestMode(context, true);
        BlockedAppsManager.testImmediateBlocking(context, new BlockedAppsManager.TestResultListener() {
            @Override
            public void onTestResult(boolean success, String message) {
                log("Test result: " + (success ? "SUCCESS" : "FAILED") + " - " + message);
                BlockedAppsManager.enableTestMode(context, false);
                listener.onTestResult(success, message);
            }
        });
    }
    
    /**
     * EventBus subscription for BlockedAppsUpdatedEvent
     */
    @Subscribe
    public void onBlockedAppsUpdated(BlockedAppsUpdatedEvent event) {
        log("Blocked apps updated event received");
    }
    
    /**
     * Log entry class for storing debug logs
     */
    public static class LogEntry {
        private final String timestamp;
        private final String message;
        
        public LogEntry(String message) {
            this.timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            this.message = message;
        }
        
        public String getTimestamp() {
            return timestamp;
        }
        
        public String getMessage() {
            return message;
        }
        
        @Override
        public String toString() {
            return timestamp + ": " + message;
        }
    }
}
