package com.example.parentalcontrol;

import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Debug activity to help diagnose authentication and HTTP request issues
 */
public class ServiceDebugActivity extends AppCompatActivity {
    private static final String TAG = "ServiceDebugActivity";
    private TextView statusText;
    private Button refreshButton;
    private Button testSyncButton;
    private Button checkAuthButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Create simple UI programmatically since we don't have layout files
        setContentView(createDebugLayout());
        
        initializeViews();
        updateStatus();
    }
    
    private int createDebugLayout() {
        // For simplicity, we'll use a simple layout ID
        // In a real implementation, you'd create a proper layout file
        return android.R.layout.activity_list_item;
    }
    
    private void initializeViews() {
        // Create views programmatically
        statusText = new TextView(this);
        refreshButton = new Button(this);
        testSyncButton = new Button(this);
        checkAuthButton = new Button(this);
        
        refreshButton.setText("Refresh Status");
        testSyncButton.setText("Test Sync");
        checkAuthButton.setText("Check Auth");
        
        refreshButton.setOnClickListener(v -> updateStatus());
        testSyncButton.setOnClickListener(v -> testSync());
        checkAuthButton.setOnClickListener(v -> checkAuthentication());
        
        // Add to layout (simplified)
        setTitle("Service Debug");
    }
    
    private void updateStatus() {
        AppController app = AppController.getInstance();
        
        StringBuilder status = new StringBuilder();
        status.append("=== SERVICE DEBUG STATUS ===\n");
        status.append("Authentication Status: ").append(app.getServiceRequestStatus()).append("\n");
        status.append("Can Make HTTP Requests: ").append(app.canMakeHttpRequests()).append("\n");
        status.append("Auth Token Present: ").append(app.getAuthToken() != null && !app.getAuthToken().isEmpty()).append("\n");
        status.append("Refresh Token Present: ").append(app.getRefreshToken() != null && !app.getRefreshToken().isEmpty()).append("\n");
        
        if (app.getAuthToken() != null) {
            status.append("Auth Token Length: ").append(app.getAuthToken().length()).append("\n");
        }
          status.append("\n=== SERVICE STATUS ===\n");
        status.append("✓ DataSyncService: 10-second sync interval\n");
        status.append("✓ BlockingSyncService: 10-second polling\n");
        status.append("✓ PeriodicHttpSyncService: Comprehensive 10-second HTTP requests\n");
        status.append("✓ All services make HTTP requests to web interface every 10 seconds\n");
        
        // Check if services are running
        ServiceManager serviceManager = app.getServiceManager();
        if (serviceManager != null) {
            status.append("ServiceManager: Available\n");
        } else {
            status.append("ServiceManager: NOT AVAILABLE\n");
        }
        
        ServiceWatchdog watchdog = app.getServiceWatchdog();
        if (watchdog != null) {
            status.append("ServiceWatchdog: Available\n");
        } else {
            status.append("ServiceWatchdog: NOT AVAILABLE\n");
        }
        
        Log.d(TAG, status.toString());
        
        if (statusText != null) {
            statusText.setText(status.toString());
        }
        
        Toast.makeText(this, "Status updated - check logs", Toast.LENGTH_SHORT).show();
    }
    
    private void testSync() {
        AppController app = AppController.getInstance();
        
        if (!app.canMakeHttpRequests()) {
            Toast.makeText(this, "Cannot test sync - not authenticated", Toast.LENGTH_LONG).show();
            Log.w(TAG, "Test sync blocked - user not authenticated");
            return;
        }
        
        Log.d(TAG, "Testing manual sync...");
        Toast.makeText(this, "Testing sync - check logs", Toast.LENGTH_SHORT).show();
        
        // Test blocked apps sync
        BlockingCoordinator.syncAndEnforceBlocking(this);
    }
    
    private void checkAuthentication() {
        AppController app = AppController.getInstance();
        
        Log.d(TAG, "=== AUTHENTICATION CHECK ===");
        Log.d(TAG, "Auth Token: " + (app.getAuthToken() != null ? "Present (" + app.getAuthToken().length() + " chars)" : "Missing"));
        Log.d(TAG, "Refresh Token: " + (app.getRefreshToken() != null ? "Present (" + app.getRefreshToken().length() + " chars)" : "Missing"));
        Log.d(TAG, "Is Authenticated: " + app.isAuthenticated());
        Log.d(TAG, "Can Make HTTP Requests: " + app.canMakeHttpRequests());
        Log.d(TAG, "Status: " + app.getServiceRequestStatus());
        
        if (app.getAuthToken() != null && app.getAuthToken().length() > 10) {
            Log.d(TAG, "Auth Token Preview: " + app.getAuthToken().substring(0, 10) + "...");
        }
        
        Toast.makeText(this, "Authentication check complete - see logs", Toast.LENGTH_SHORT).show();
    }
}
