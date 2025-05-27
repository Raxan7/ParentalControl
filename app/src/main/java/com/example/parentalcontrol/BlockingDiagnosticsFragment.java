package com.example.parentalcontrol;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.List;

/**
 * Fragment for testing and diagnosing the immediate app blocking functionality
 */
public class BlockingDiagnosticsFragment extends Fragment {

    private TextView logsTextView;
    private Button testButton;
    private Button forceSyncButton;
    private Button toggleDebugButton;
    private Button clearLogsButton;
    
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable logUpdateRunnable;
    private boolean isDebugModeEnabled = false;

    public BlockingDiagnosticsFragment() {
        // Required empty constructor
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_blocking_diagnostics, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        logsTextView = view.findViewById(R.id.tv_debug_logs);
        testButton = view.findViewById(R.id.btn_test_immediate_blocking);
        forceSyncButton = view.findViewById(R.id.btn_force_sync);
        toggleDebugButton = view.findViewById(R.id.btn_toggle_debug);
        clearLogsButton = view.findViewById(R.id.btn_clear_logs);
        
        setupButtons();
        
        // Start log updates
        startLogUpdates();
    }
    
    @Override
    public void onResume() {
        super.onResume();
        updateLogs();
    }
    
    @Override
    public void onPause() {
        super.onPause();
        stopLogUpdates();
    }
    
    private void setupButtons() {
        testButton.setOnClickListener(v -> testImmediateBlocking());
        forceSyncButton.setOnClickListener(v -> forceSyncBlockedApps());
        toggleDebugButton.setOnClickListener(v -> toggleDebugMode());
        clearLogsButton.setOnClickListener(v -> clearLogs());
    }
    
    private void testImmediateBlocking() {
        Toast.makeText(getContext(), "Starting immediate blocking test...", Toast.LENGTH_SHORT).show();
        
        BlockingDebugger.testImmediateBlocking(getContext(), new BlockedAppsManager.TestResultListener() {
            @Override
            public void onTestResult(boolean success, String message) {
                Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
                updateLogs(); // Refresh logs immediately
            }
        });
    }
    
    @SuppressLint("HardwareIds")
    private void forceSyncBlockedApps() {
        Toast.makeText(getContext(), "Forcing sync of blocked apps...", Toast.LENGTH_SHORT).show();
        
        String deviceId = Settings.Secure.getString(
                getContext().getContentResolver(),
                Settings.Secure.ANDROID_ID
        );
        
        BlockedAppsManager.forceImmediateSync(getContext(), deviceId);
    }
    
    private void toggleDebugMode() {
        isDebugModeEnabled = !isDebugModeEnabled;
        BlockingDebugger.setDebugEnabled(getContext(), isDebugModeEnabled);
        
        toggleDebugButton.setText(isDebugModeEnabled ? "Disable Debug Mode" : "Enable Debug Mode");
        
        Toast.makeText(getContext(), 
                "Debug mode " + (isDebugModeEnabled ? "enabled" : "disabled"), 
                Toast.LENGTH_SHORT).show();
    }
    
    private void clearLogs() {
        BlockingDebugger.clearLogs();
        updateLogs();
    }
    
    private void startLogUpdates() {
        logUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateLogs();
                handler.postDelayed(this, 2000); // Update every 2 seconds
            }
        };
        
        handler.post(logUpdateRunnable);
    }
    
    private void stopLogUpdates() {
        handler.removeCallbacks(logUpdateRunnable);
    }
    
    private void updateLogs() {
        if (logsTextView != null) {
            StringBuilder sb = new StringBuilder();
            List<BlockingDebugger.LogEntry> logs = BlockingDebugger.getLogEntries();
            
            if (logs.isEmpty()) {
                sb.append("No logs yet. Enable debug mode and test blocking functionality.");
            } else {
                for (BlockingDebugger.LogEntry entry : logs) {
                    sb.append(entry.getTimestamp()).append(": ").append(entry.getMessage()).append("\n\n");
                }
            }
            
            logsTextView.setText(sb.toString());
        }
    }
}
