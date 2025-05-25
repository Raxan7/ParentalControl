// MainActivity.java
package com.example.parentalcontrol;

import android.app.admin.DevicePolicyManager;
import android.app.AppOpsManager;
import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import android.Manifest;
import android.provider.Settings;
import android.util.Log;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import android.os.Process;

@RequiresApi(api = Build.VERSION_CODES.P)
public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.PACKAGE_USAGE_STATS,
            Manifest.permission.INTERNET,
            Manifest.permission.FOREGROUND_SERVICE
    };
    private static final int USAGE_STATS_PERMISSION_REQUEST = 1002;
    private static final int USAGE_STATS_REQUEST = 1001;
    private static final int REQUEST_CODE_ENABLE_ADMIN = 1003; // Unique request code for device admin

    private AppUsageRepository repository;
    private ProgressDialog progressDialog;
    private FrameLayout fragmentContainer;

    private boolean isDeviceRegistered() {
        SharedPreferences prefs = getSharedPreferences("ParentalControlPrefs", MODE_PRIVATE);
        return prefs.getBoolean("device_registered", false);
    }

    private void setDeviceRegistered() {
        SharedPreferences prefs = getSharedPreferences("ParentalControlPrefs", MODE_PRIVATE);
        prefs.edit().putBoolean("device_registered", true).apply();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        fragmentContainer = findViewById(R.id.fragment_container);

        // Check if device admin is already enabled
        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName adminComponentName = new ComponentName(this, DeviceAdminReceiverCustom.class);

        if (!devicePolicyManager.isAdminActive(adminComponentName)) {
            // Request admin privileges
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponentName);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "This app needs device admin permissions to lock the device.");
            startActivityForResult(intent, REQUEST_CODE_ENABLE_ADMIN);
        }

        // Check critical permissions first
        if (!checkUsageStatsPermission()) {
            requestUsageStatsPermission();
            return; // Wait for onActivityResult
        }

        if (!checkPermissions()) {
            requestNeededPermissions();
            return; // Wait for onRequestPermissionsResult
        }

        // Only proceed if we have permissions
        initializeIfPermissionsGranted();

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == USAGE_STATS_REQUEST) {
            if (checkUsageStatsPermission()) {
                initializeIfPermissionsGranted();
            } else {
                Toast.makeText(this, "Usage stats permission required", Toast.LENGTH_LONG).show();
                finish(); // Close app if permission not granted
            }
        } else if (requestCode == REQUEST_CODE_ENABLE_ADMIN) {
            if (resultCode == RESULT_OK) {
                // Device admin enabled
                initializeApp();
            } else {
                // User denied device admin permissions
                Toast.makeText(this, "Device admin permissions are required to lock the device.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void initializeIfPermissionsGranted() {
        if (checkUsageStatsPermission() && checkPermissions()) {
            String authToken = AppController.getInstance().getAuthToken();
            if (authToken == null || authToken.isEmpty()) {
                showLoginScreen();
            } else {
                initializeApp();
            }
        }
    }

    private void initializeApp() {
        // Check if we have an auth token
        String authToken = AppController.getInstance().getAuthToken();
        if (authToken == null || authToken.isEmpty()) {
            // Handle login flow
            showLoginScreen();
            return;
        }

        // Log app usage data
        AppUsageDatabaseHelper dbHelper = new AppUsageDatabaseHelper(this);
        dbHelper.logScreenTimeRulesData();

        logAllSharedPreferences();

        // MANUAL BLOCKING TEST: Add WhatsApp to blocked apps
        manuallyBlockWhatsApp();
        
        // Check accessibility service status
        checkAccessibilityServiceStatus();

        // Start services
        startForegroundServices();

        // Register device if needed
        if (!isDeviceRegistered()) {
            showLoading("Registering device...");
            DeviceRegistration.registerDevice(
                    this,
                    authToken,
                    new DeviceRegistration.RegistrationCallback() {
                        @Override
                        public void onSuccess() {
                            hideLoading();
                            setDeviceRegistered();
                            startDataSync();
                        }

                        @Override
                        public void onFailure(Exception e) {
                            hideLoading();
                            ErrorHandler.handleApiError(MainActivity.this, e, "device_registration");
                        }
                    });
        } else {
            startDataSync();
        }
    }

    /**
     * Manually block WhatsApp for testing purposes
     */
    private void manuallyBlockWhatsApp() {
        try {
            // Check if accessibility service is enabled
            if (!AppBlockAccessibilityService.isAccessibilityServiceEnabled(this)) {
                Toast.makeText(this, "Please enable Accessibility Service for app blocking to work", Toast.LENGTH_LONG).show();
                requestAccessibilityPermission();
                return;
            }
            
            // Add WhatsApp to local blocked apps database
            AppUsageDatabaseHelper dbHelper = new AppUsageDatabaseHelper(this);
            android.database.sqlite.SQLiteDatabase db = dbHelper.getWritableDatabase();
            
            // First check if WhatsApp is already blocked
            android.database.Cursor cursor = db.rawQuery(
                "SELECT package_name FROM blocked_apps WHERE package_name = ?", 
                new String[]{"com.whatsapp"}
            );
            
            if (!cursor.moveToFirst()) {
                // WhatsApp not blocked yet, add it
                android.content.ContentValues values = new android.content.ContentValues();
                values.put("package_name", "com.whatsapp");
                long result = db.insert("blocked_apps", null, values);
                
                if (result != -1) {
                    Log.d("MANUAL_BLOCK", "WhatsApp manually blocked successfully");
                    Toast.makeText(this, "WhatsApp manually blocked for testing", Toast.LENGTH_SHORT).show();
                    
                    // Notify both services about the new blocked app
                    org.greenrobot.eventbus.EventBus.getDefault().post(new NewBlockedAppEvent("com.whatsapp"));
                } else {
                    Log.e("MANUAL_BLOCK", "Failed to block WhatsApp manually");
                }
            } else {
                Log.d("MANUAL_BLOCK", "WhatsApp is already blocked");
                Toast.makeText(this, "WhatsApp is already blocked", Toast.LENGTH_SHORT).show();
            }
            
            cursor.close();
            db.close();
            
        } catch (Exception e) {
            Log.e("MANUAL_BLOCK", "Error manually blocking WhatsApp", e);
            Toast.makeText(this, "Error blocking WhatsApp: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    /**
     * Request accessibility service permission
     */
    private void requestAccessibilityPermission() {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            
            // Show instructions to user
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
            builder.setTitle("Enable Accessibility Service")
                   .setMessage("To block apps effectively, please:\n\n" +
                              "1. Find 'ParentalControl' in the accessibility services list\n" +
                              "2. Turn it ON\n" +
                              "3. Confirm by tapping 'OK'\n\n" +
                              "This allows the app to block restricted applications.")
                   .setPositiveButton("OK", null)
                   .show();
                   
        } catch (Exception e) {
            Log.e("ACCESSIBILITY", "Error opening accessibility settings", e);
            Toast.makeText(this, "Please enable accessibility service manually in Settings", Toast.LENGTH_LONG).show();
        }
    }
    
    /**
     * Method to manually unblock WhatsApp for testing
     */
    private void manuallyUnblockWhatsApp() {
        try {
            AppUsageDatabaseHelper dbHelper = new AppUsageDatabaseHelper(this);
            android.database.sqlite.SQLiteDatabase db = dbHelper.getWritableDatabase();
            
            int deletedRows = db.delete("blocked_apps", "package_name = ?", new String[]{"com.whatsapp"});
            
            if (deletedRows > 0) {
                Log.d("MANUAL_UNBLOCK", "WhatsApp manually unblocked successfully");
                Toast.makeText(this, "WhatsApp manually unblocked", Toast.LENGTH_SHORT).show();
                
                // You could add an event to notify AppBlockerService to refresh its list
                // For now, it will be refreshed on next sync
            } else {
                Log.d("MANUAL_UNBLOCK", "WhatsApp was not blocked");
                Toast.makeText(this, "WhatsApp was not blocked", Toast.LENGTH_SHORT).show();
            }
            
            db.close();
            
        } catch (Exception e) {
            Log.e("MANUAL_UNBLOCK", "Error manually unblocking WhatsApp", e);
            Toast.makeText(this, "Error unblocking WhatsApp: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void startDataSync() {
        showLoading("Syncing data...");
        DataSync.syncAppUsage(
                this,
                AppController.getInstance().getAuthToken(),
                new DataSync.SyncCallback() {
                    @Override
                    public void onSuccess() {
                        hideLoading();
                        // Continue with app initialization
                        ScreenTimeManager screenTimeManager = ServiceLocator.getInstance(MainActivity.this)
                                .getScreenTimeManager(MainActivity.this);
                        screenTimeManager.setDailyLimit(120);
                    }

                    @Override
                    public void onFailure(Exception e) {
                        hideLoading();
                        ErrorHandler.handleApiError(MainActivity.this, e, "data_sync");
                    }
                });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (hasAllPermissionsGranted(grantResults)) {
                // All permissions granted, initialize the app
                initializeApp();
            } else {
                // Some permissions denied
                Toast.makeText(this, "Some permissions were denied. App may not function properly.", Toast.LENGTH_LONG).show();
                // You might want to show another explanation and request again
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
    }

    @Override
    protected void onStop() {
        EventBus.getDefault().unregister(this);
        super.onStop();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onBlockedAppAttempt(BlockedAppEvent event) {
        AlertNotifier.showNotification(this, "App Blocked",
                "Attempt to open " + event.packageName + " was blocked");
    }

    private void startForegroundServices() {
        Log.d("SERVICE", "Attempting to start services...");

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(new Intent(this, ActivityTrackerService.class));
                startForegroundService(new Intent(this, DataSyncService.class));
                // Start the app blocking service
                startForegroundService(new Intent(this, AppBlockerService.class));
            } else {
                startService(new Intent(this, ActivityTrackerService.class));
                startService(new Intent(this, DataSyncService.class));
                // Start the app blocking service
                startService(new Intent(this, AppBlockerService.class));
            }
            Log.d("SERVICE", "Services started successfully");
        } catch (Exception e) {
            Log.e("SERVICE", "Failed to start services", e);
        }
    }

    private boolean checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (String permission : REQUIRED_PERMISSIONS) {
                if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    requestNeededPermissions();
                    return false;
                }
            }
        }
        return true;
    }

    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Filter out already granted permissions
            List<String> permissionsToRequest = new ArrayList<>();
            for (String permission : REQUIRED_PERMISSIONS) {
                if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(permission);
                }
            }

            if (!permissionsToRequest.isEmpty()) {
                ActivityCompat.requestPermissions(
                        this,
                        permissionsToRequest.toArray(new String[0]),
                        PERMISSION_REQUEST_CODE
                );
            }
        }
    }

    private boolean hasAllPermissionsGranted(@NonNull int[] grantResults) {
        for (int grantResult : grantResults) {
            if (grantResult != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private boolean checkUsageStatsPermission() {
        try {
            AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return false;

            int mode = appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    getPackageName()
            );
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            Log.e("PERMISSION", "Error checking usage stats permission", e);
            return false;
        }
    }

    private void requestUsageStatsPermission() {
        if (!checkUsageStatsPermission()) {
            startActivityForResult(
                    new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
                    USAGE_STATS_REQUEST
            );
        }
    }

    private void showLoginScreen() {
        // Create and show the login fragment
        LoginFragment loginFragment = new LoginFragment();
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, loginFragment)
                .commit();
    }

    private void showLoading(String message) {
        runOnUiThread(() -> {
            if (progressDialog == null) {
                progressDialog = new ProgressDialog(this);
                progressDialog.setCancelable(false);
            }
            progressDialog.setMessage(message);
            progressDialog.show();
        });
    }

    private void hideLoading() {
        runOnUiThread(() -> {
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }
        });
    }

    public void attemptLogin(String username, String password) {
        showLoading("Logging in...");

        AuthService.login(username, password, new AuthService.AuthCallback() {
            @Override
            public void onSuccess(String accessToken, String refreshToken) {
                hideLoading();
                AppController.getInstance().setAuthToken(accessToken);
                // Remove login fragment
                getSupportFragmentManager().beginTransaction()
                        .remove(getSupportFragmentManager()
                                .findFragmentById(R.id.fragment_container))
                        .commit();
                initializeApp();
                schedulePeriodicSync();
            }

            @Override
            public void onFailure(Exception e) {
                hideLoading();
                Toast.makeText(MainActivity.this,
                        "Login failed: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void schedulePeriodicSync() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        // Sync app usage and screen time every 15 minutes
        PeriodicWorkRequest syncRequest = new PeriodicWorkRequest.Builder(
                DataSyncWorker.class,
                15, // Every 15 minutes
                TimeUnit.MINUTES
        )
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "data_sync",
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
        );

        // Additional worker for daily screen time calculation
        PeriodicWorkRequest dailyScreenTimeRequest = new PeriodicWorkRequest.Builder(
                ScreenTimeWorker.class,
                24, // Every 24 hours
                TimeUnit.HOURS
        )
                .setInitialDelay(1, TimeUnit.HOURS) // Start 1 hour after initial setup
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "daily_screen_time",
                ExistingPeriodicWorkPolicy.KEEP,
                dailyScreenTimeRequest
        );
    }

    /**
     * Check accessibility service status and show notification if needed
     */
    private void checkAccessibilityServiceStatus() {
        if (!AppBlockAccessibilityService.isAccessibilityServiceEnabled(this)) {
            Log.w("ACCESSIBILITY", "Accessibility service is not enabled");
            
            // Show a subtle notification that accessibility is needed
            Toast.makeText(this, "Tip: Enable Accessibility Service for better app blocking", Toast.LENGTH_LONG).show();
        } else {
            Log.d("ACCESSIBILITY", "Accessibility service is enabled");
            Toast.makeText(this, "App blocking is active", Toast.LENGTH_SHORT).show();
        }
    }
    
    public void logAllSharedPreferences() {
        SharedPreferences prefs = getSharedPreferences("ParentalControlPrefs", MODE_PRIVATE);

        // Get all keys from SharedPreferences
        Map<String, ?> allPrefs = prefs.getAll();

        // Log each key-value pair
        for (Map.Entry<String, ?> entry : allPrefs.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // Log key and value (handling different types)
            if (value instanceof String) {
                Log.d("SharedPreferencesLog", key + ": " + value);
            } else if (value instanceof Integer) {
                Log.d("SharedPreferencesLog", key + ": " + (Integer) value);
            } else if (value instanceof Boolean) {
                Log.d("SharedPreferencesLog", key + ": " + (Boolean) value);
            } else if (value instanceof Float) {
                Log.d("SharedPreferencesLog", key + ": " + (Float) value);
            } else if (value instanceof Long) {
                Log.d("SharedPreferencesLog", key + ": " + (Long) value);
            } else {
                Log.d("SharedPreferencesLog", key + ": " + value.toString());
            }
        }
    }
}