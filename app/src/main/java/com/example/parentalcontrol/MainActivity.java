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
import android.view.Menu;
import android.view.MenuItem;

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
                startForegroundService(new Intent(this, BlockingSyncService.class)); // Start blocking sync service
            } else {
                startService(new Intent(this, ActivityTrackerService.class));
                startService(new Intent(this, DataSyncService.class));
                startService(new Intent(this, BlockingSyncService.class)); // Start blocking sync service
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

        // Sync blocked apps from server every 10 minutes
        PeriodicWorkRequest blockedAppsSyncRequest = new PeriodicWorkRequest.Builder(
                BlockedAppsSyncWorker.class,
                2, // Every 2 minutes
                TimeUnit.MINUTES
        )
                .setConstraints(constraints)
                // Initial trigger immediately to get blocked apps right away
                .setInitialDelay(1, TimeUnit.MINUTES)
                .build();

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "blocked_apps_sync",
                ExistingPeriodicWorkPolicy.KEEP,
                blockedAppsSyncRequest
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_diagnostics) {
            // Launch the BlockingTesterActivity
            Intent intent = new Intent(this, BlockingTesterActivity.class);
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}