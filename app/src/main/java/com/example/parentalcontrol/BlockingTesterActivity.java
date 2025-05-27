package com.example.parentalcontrol;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class BlockingTesterActivity extends AppCompatActivity {
    private static final String TAG = "BlockingTester";
    
    private TextView statusTextView;
    private Handler handler = new Handler();
    private StringBuilder logBuffer = new StringBuilder();
    
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_blocking_tester);
        
        // Set up back button
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Blocking Diagnostics");
        }
        
        // Initialize views
        statusTextView = findViewById(R.id.tv_status);
        Button checkServicesButton = findViewById(R.id.btn_check_services);
        Button forceSyncButton = findViewById(R.id.btn_force_sync);
        Button testBlockingButton = findViewById(R.id.btn_test_blocking);
        Button accessibilityButton = findViewById(R.id.btn_accessibility_settings);
        
        // Set up button listeners
        checkServicesButton.setOnClickListener(v -> checkServiceStatus());
        forceSyncButton.setOnClickListener(v -> forceSyncBlockedApps());
        testBlockingButton.setOnClickListener(v -> testBlockingFlow());
        accessibilityButton.setOnClickListener(v -> openAccessibilitySettings());
        
        // Register for EventBus
        EventBus.getDefault().register(this);
        
        // Initial status check
        checkServiceStatus();
    }
    
    @Override
    protected void onDestroy() {
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }
    
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    
    private void checkServiceStatus() {
        log("Checking service status...");
        
        // Check accessibility service
        boolean accessibilityEnabled = AppBlockAccessibilityService.isAccessibilityServiceEnabled(this);
        log("Accessibility Service: " + (accessibilityEnabled ? "ENABLED ✓" : "DISABLED ✗"));
        
        // Check app blocker service
        boolean appBlockerRunning = isServiceRunning(AppBlockerService.class.getName());
        log("App Blocker Service: " + (appBlockerRunning ? "RUNNING ✓" : "NOT RUNNING ✗"));
        
        // Check blocked apps in database
        AppUsageDatabaseHelper dbHelper = new AppUsageDatabaseHelper(this);
        List<String> blockedApps = dbHelper.getAllBlockedPackages();
        log("Blocked Apps in Database: " + blockedApps.size());
        
        for (String app : blockedApps) {
            log("- " + getAppNameForPackage(app) + " (" + app + ")");
        }
    }
    
    private void forceSyncBlockedApps() {
        log("Forcing sync of blocked apps...");
        
        BlockingCoordinator.syncAndEnforceBlocking(this);
    }
    
    private void testBlockingFlow() {
        log("Testing blocking flow integration...");
        
        // Check accessibility service first
        if (!AppBlockAccessibilityService.isAccessibilityServiceEnabled(this)) {
            log("ERROR: Accessibility service is not enabled. Please enable it first.");
            openAccessibilitySettings();
            return;
        }
        
        // Test the flow
        new Thread(() -> {
            try {
                // Step 1: Clear local database
                log("Step 1: Clearing local blocked apps...");
                AppUsageDatabaseHelper dbHelper = new AppUsageDatabaseHelper(this);
                SQLiteDatabase db = dbHelper.getWritableDatabase();
                db.execSQL("DELETE FROM blocked_apps");
                db.close();
                
                // Step 2: Add a test app
                log("Step 2: Adding test app to database...");
                String testPackage = "com.instagram.android"; // Using Instagram as test
                ContentValues values = new ContentValues();
                values.put("package_name", testPackage);
                db = dbHelper.getWritableDatabase();
                db.insert("blocked_apps", null, values);
                db.close();
                
                // Step 3: Send notification
                log("Step 3: Broadcasting blocked apps updated event...");
                EventBus.getDefault().post(new BlockedAppsUpdatedEvent());
                
                // Step 4: Check if notification was received
                log("Step 4: Checking if notification was received...");
                // This is checked by the onBlockedAppsUpdated method below
                
                // Step 5: Test immediate enforcement
                log("Step 5: Testing immediate enforcement...");
                handler.postDelayed(() -> {
                    AppBlockerService service = AppController.getInstance().getAppBlockerService();
                    if (service != null) {
                        service.checkAndBlockCurrentApp();
                        log("Requested immediate enforcement");
                    } else {
                        log("ERROR: AppBlockerService is not available");
                    }
                }, 1000);
                
            } catch (Exception e) {
                log("ERROR: " + e.getMessage());
            }
        }).start();
    }
    
    private void openAccessibilitySettings() {
        log("Opening accessibility settings...");
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(intent);
    }
    
    private boolean isServiceRunning(String serviceName) {
        try {
            ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (serviceName.equals(service.service.getClassName())) {
                    return true;
                }
            }
        } catch (Exception e) {
            log("Error checking service: " + e.getMessage());
        }
        return false;
    }
    
    private String getAppNameForPackage(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return packageName;
        }
    }
    
    @SuppressLint("SimpleDateFormat")
    private void log(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        String logMessage = timestamp + " " + message + "\n";
        Log.d(TAG, message);
        
        runOnUiThread(() -> {
            logBuffer.insert(0, logMessage); // Add to top
            statusTextView.setText(logBuffer.toString());
        });
    }
    
    // EventBus subscribers
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onBlockedAppsUpdated(BlockedAppsUpdatedEvent event) {
        log("✓ Received BlockedAppsUpdatedEvent");
    }
    
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onNewBlockedApp(NewBlockedAppEvent event) {
        log("✓ Received NewBlockedAppEvent for " + event.packageName);
    }
    
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onBlockedApp(BlockedAppEvent event) {
        log("✓ Received BlockedAppEvent for " + event.packageName);
    }
}
