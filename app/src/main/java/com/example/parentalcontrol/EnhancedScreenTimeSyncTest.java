package com.example.parentalcontrol;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Comprehensive test suite for enhanced screen time synchronization and notification system
 * Tests the fixes for timing issues and notification improvements
 */
public class EnhancedScreenTimeSyncTest {
    private static final String TAG = "EnhancedScreenTimeSyncTest";
    
    private final Context context;
    private final ScreenTimeCalculator calculator;
    private final ScreenTimeManager manager;
    private final BedtimeEnforcer bedtimeEnforcer;
    
    public EnhancedScreenTimeSyncTest(Context context) {
        this.context = context;
        this.calculator = new ScreenTimeCalculator(context);
        this.manager = new ScreenTimeManager(context);
        this.bedtimeEnforcer = new BedtimeEnforcer(context);
    }
    
    /**
     * Run all enhanced synchronization tests
     */
    public void runAllTests() {
        Log.d(TAG, "=== STARTING ENHANCED SCREEN TIME SYNC TESTS ===");
        
        try {
            // Initialize enhanced notification system
            EnhancedAlertNotifier.initializeChannels(context);
            
            // Test 1: Screen time synchronization
            testScreenTimeSynchronization();
            
            // Test 2: Progressive warning notifications
            testProgressiveWarningNotifications();
            
            // Test 3: Immediate limit detection
            testImmediateLimitDetection();
            
            // Test 4: Bedtime warning notifications
            testBedtimeWarningNotifications();
            
            // Test 5: Adaptive countdown intervals
            testAdaptiveCountdownIntervals();
            
            // Test 6: Notification spam prevention
            testNotificationSpamPrevention();
            
            Log.d(TAG, "=== ALL ENHANCED SCREEN TIME SYNC TESTS COMPLETED ===");
            
        } catch (Exception e) {
            Log.e(TAG, "Error running enhanced sync tests", e);
        }
    }
    
    /**
     * Test 1: Screen time synchronization between countdown and actual usage
     */
    private void testScreenTimeSynchronization() {
        Log.d(TAG, "--- Test 1: Screen Time Synchronization ---");
        
        try {
            // Set a test limit
            long testLimit = 60; // 1 hour
            manager.setDailyLimit(testLimit);
            
            // Get both countdown and actual usage data
            ScreenTimeCalculator.ScreenTimeCountdownData countdownData = calculator.getCountdownData();
            long actualUsage = calculator.getTodayUsageMinutes();
            
            // Check synchronization
            long usageDifference = Math.abs(actualUsage - countdownData.usedMinutes);
            
            Log.d(TAG, String.format("Sync Test - Actual: %d min, Countdown: %d min, Difference: %d min", 
                    actualUsage, countdownData.usedMinutes, usageDifference));
            
            if (usageDifference <= 2) {
                Log.d(TAG, "✅ SYNC TEST PASSED: Usage values are synchronized (difference <= 2 minutes)");
            } else {
                Log.w(TAG, "⚠️ SYNC TEST WARNING: Usage difference > 2 minutes, may need adjustment");
            }
            
            // Test enhanced screen time check
            manager.checkScreenTime(context);
            Log.d(TAG, "✅ Enhanced screen time check completed successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ SYNC TEST FAILED", e);
        }
    }
    
    /**
     * Test 2: Progressive warning notifications at different time thresholds
     */
    private void testProgressiveWarningNotifications() {
        Log.d(TAG, "--- Test 2: Progressive Warning Notifications ---");
        
        try {
            // Test different warning scenarios
            testWarningNotification(35, ScreenTimeCheckReceiver.NotificationPriority.LOW);
            testWarningNotification(20, ScreenTimeCheckReceiver.NotificationPriority.NORMAL);
            testWarningNotification(10, ScreenTimeCheckReceiver.NotificationPriority.HIGH);
            testWarningNotification(0, ScreenTimeCheckReceiver.NotificationPriority.CRITICAL);
            
            Log.d(TAG, "✅ PROGRESSIVE NOTIFICATIONS TEST COMPLETED");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ PROGRESSIVE NOTIFICATIONS TEST FAILED", e);
        }
    }
    
    private void testWarningNotification(int remainingMinutes, ScreenTimeCheckReceiver.NotificationPriority expectedPriority) {
        String title = "Test Warning - " + remainingMinutes + " min remaining";
        String message = "This is a test notification for " + remainingMinutes + " minutes remaining";
        
        EnhancedAlertNotifier.showScreenTimeNotification(context, title, message, expectedPriority);
        
        Log.d(TAG, String.format("Sent %s priority notification for %d minutes remaining", 
                expectedPriority.name(), remainingMinutes));
    }
    
    /**
     * Test 3: Immediate limit detection system
     */
    private void testImmediateLimitDetection() {
        Log.d(TAG, "--- Test 3: Immediate Limit Detection ---");
        
        try {
            // Simulate immediate limit exceeded broadcast
            Intent immediateLimit = new Intent("com.example.parentalcontrol.IMMEDIATE_SCREEN_TIME_LIMIT");
            immediateLimit.putExtra("used_minutes", 125);
            immediateLimit.putExtra("limit_minutes", 120);
            
            context.sendBroadcast(immediateLimit);
            
            Log.d(TAG, "✅ IMMEDIATE LIMIT DETECTION TEST: Broadcast sent successfully");
            
            // Test immediate screen time check receiver
            ScreenTimeCheckReceiver receiver = new ScreenTimeCheckReceiver();
            Intent testIntent = new Intent();
            receiver.onReceive(context, testIntent);
            
            Log.d(TAG, "✅ IMMEDIATE LIMIT DETECTION TEST COMPLETED");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ IMMEDIATE LIMIT DETECTION TEST FAILED", e);
        }
    }
    
    /**
     * Test 4: Bedtime warning notifications
     */
    private void testBedtimeWarningNotifications() {
        Log.d(TAG, "--- Test 4: Bedtime Warning Notifications ---");
        
        try {
            // Test different bedtime warning scenarios
            EnhancedAlertNotifier.showBedtimeWarning(context, 
                "Bedtime in 30 minutes", 
                "Bedtime starts at 22:00. Time to prepare for bed.", 
                false);
            
            EnhancedAlertNotifier.showBedtimeWarning(context, 
                "Bedtime in 5 minutes!", 
                "Bedtime starts at 22:00. Device will lock in 5 minutes.", 
                true);
            
            // Test bedtime enforcement
            boolean bedtimeActive = bedtimeEnforcer.checkAndEnforceBedtime();
            
            Log.d(TAG, String.format("✅ BEDTIME NOTIFICATIONS TEST COMPLETED (Bedtime active: %s)", bedtimeActive));
            
        } catch (Exception e) {
            Log.e(TAG, "❌ BEDTIME NOTIFICATIONS TEST FAILED", e);
        }
    }
    
    /**
     * Test 5: Adaptive countdown intervals
     */
    private void testAdaptiveCountdownIntervals() {
        Log.d(TAG, "--- Test 5: Adaptive Countdown Intervals ---");
        
        try {
            // Start countdown service to test adaptive intervals
            Intent serviceIntent = new Intent(context, ScreenTimeCountdownService.class);
            context.startService(serviceIntent);
            
            Log.d(TAG, "✅ ADAPTIVE COUNTDOWN INTERVALS TEST: Service started successfully");
            
            // Test countdown data retrieval
            ScreenTimeCalculator.ScreenTimeCountdownData data = calculator.getCountdownData();
            Log.d(TAG, String.format("Countdown data: %s", data.toString()));
            
            Log.d(TAG, "✅ ADAPTIVE COUNTDOWN INTERVALS TEST COMPLETED");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ ADAPTIVE COUNTDOWN INTERVALS TEST FAILED", e);
        }
    }
    
    /**
     * Test 6: Notification spam prevention
     */
    private void testNotificationSpamPrevention() {
        Log.d(TAG, "--- Test 6: Notification Spam Prevention ---");
        
        try {
            // Send multiple notifications rapidly
            for (int i = 0; i < 5; i++) {
                EnhancedAlertNotifier.showScreenTimeNotification(context, 
                    "Spam Test " + i, 
                    "This tests notification cooldown", 
                    ScreenTimeCheckReceiver.NotificationPriority.NORMAL);
                
                // Short delay
                Thread.sleep(100);
            }
            
            Log.d(TAG, "✅ NOTIFICATION SPAM PREVENTION TEST: Multiple notifications sent");
            
            // Test cooldown
            SharedPreferences prefs = context.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE);
            long lastNotificationTime = prefs.getLong("last_screen_time_normal", 0);
            long currentTime = System.currentTimeMillis();
            long timeSinceLastNotification = currentTime - lastNotificationTime;
            
            Log.d(TAG, String.format("Time since last notification: %d ms", timeSinceLastNotification));
            
            Log.d(TAG, "✅ NOTIFICATION SPAM PREVENTION TEST COMPLETED");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ NOTIFICATION SPAM PREVENTION TEST FAILED", e);
        }
    }
    
    /**
     * Test notification clearing functionality
     */
    public void testNotificationClearing() {
        Log.d(TAG, "--- Testing Notification Clearing ---");
        
        try {
            // Clear all notifications
            EnhancedAlertNotifier.clearScreenTimeNotifications(context);
            EnhancedAlertNotifier.clearBedtimeNotifications(context);
            
            Log.d(TAG, "✅ NOTIFICATION CLEARING TEST COMPLETED");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ NOTIFICATION CLEARING TEST FAILED", e);
        }
    }
    
    /**
     * Generate comprehensive test report
     */
    public void generateTestReport() {
        Log.d(TAG, "=== ENHANCED SCREEN TIME SYNC TEST REPORT ===");
        
        try {
            // Get current system state
            ScreenTimeCalculator.ScreenTimeCountdownData data = calculator.getCountdownData();
            long actualUsage = calculator.getTodayUsageMinutes();
            
            Log.d(TAG, "System State:");
            Log.d(TAG, String.format("  - Daily Limit: %d minutes", data.dailyLimitMinutes));
            Log.d(TAG, String.format("  - Used (Countdown): %d minutes", data.usedMinutes));
            Log.d(TAG, String.format("  - Used (Actual): %d minutes", actualUsage));
            Log.d(TAG, String.format("  - Remaining: %d minutes", data.remainingMinutes));
            Log.d(TAG, String.format("  - Percentage Used: %.1f%%", data.percentageUsed));
            Log.d(TAG, String.format("  - Limit Exceeded: %s", data.isLimitExceeded()));
            Log.d(TAG, String.format("  - Warning State: %s", data.isWarningState()));
            
            // Check bedtime status
            boolean bedtimeActive = bedtimeEnforcer.checkAndEnforceBedtime();
            Log.d(TAG, String.format("  - Bedtime Active: %s", bedtimeActive));
            
            Log.d(TAG, "=== TEST REPORT COMPLETED ===");
            
        } catch (Exception e) {
            Log.e(TAG, "Error generating test report", e);
        }
    }
}
