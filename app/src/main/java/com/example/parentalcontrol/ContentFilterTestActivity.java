package com.example.parentalcontrol;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Activity to test and verify VPN-based content filtering
 */
public class ContentFilterTestActivity extends Activity {
    private static final String TAG = "ContentFilterTest";
    
    private TextView statusText;
    private Button startFilterButton;
    private Button stopFilterButton;
    private Button testBlockingButton;
    private Switch adultContentSwitch;
    private Switch socialMediaSwitch;
    private Switch gamingSwitch;
    
    private ContentFilterEngine filterEngine;
    private VpnContentFilterManager vpnManager;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_content_filter_test);
        
        // Initialize filter engine and VPN manager
        filterEngine = new ContentFilterEngine(this);
        vpnManager = new VpnContentFilterManager(this);
        
        // Find views
        statusText = findViewById(R.id.filter_status_text);
        startFilterButton = findViewById(R.id.btn_start_filter);
        stopFilterButton = findViewById(R.id.btn_stop_filter);
        testBlockingButton = findViewById(R.id.btn_test_blocking);
        adultContentSwitch = findViewById(R.id.switch_block_adult);
        socialMediaSwitch = findViewById(R.id.switch_block_social);
        gamingSwitch = findViewById(R.id.switch_block_gaming);
        
        setupButtons();
        setupSwitches();
        updateStatus();
    }
    
    private void setupSwitches() {
        // Set initial states from preferences
        adultContentSwitch.setChecked(filterEngine.isBlockingAdultContent());
        socialMediaSwitch.setChecked(filterEngine.isBlockingSocialMedia());
        gamingSwitch.setChecked(filterEngine.isBlockingGaming());
        
        // Set up listeners
        adultContentSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                filterEngine.setBlockAdultContent(isChecked);
                updateStatus();
                Log.d(TAG, "Adult content blocking: " + isChecked);
            }
        });
        
        socialMediaSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                filterEngine.setBlockSocialMedia(isChecked);
                updateStatus();
                Log.d(TAG, "Social media blocking: " + isChecked);
            }
        });
        
        gamingSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                filterEngine.setBlockGaming(isChecked);
                updateStatus();
                Log.d(TAG, "Gaming site blocking: " + isChecked);
            }
        });
    }
    
    private void setupButtons() {
        startFilterButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startContentFilter();
            }
        });
        
        stopFilterButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopContentFilter();
            }
        });
        
        testBlockingButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testContentBlocking();
            }
        });
    }
    
    private void startContentFilter() {
        try {
            vpnManager.startContentFiltering(this);
            Toast.makeText(this, "Starting content filter...", Toast.LENGTH_SHORT).show();
            updateStatus();
        } catch (Exception e) {
            Log.e(TAG, "Error starting content filter", e);
            Toast.makeText(this, "Failed to start content filter", Toast.LENGTH_LONG).show();
        }
    }
    
    private void stopContentFilter() {
        try {
            vpnManager.stopContentFiltering();
            Toast.makeText(this, "Content filter stopped", Toast.LENGTH_SHORT).show();
            updateStatus();
        } catch (Exception e) {
            Log.e(TAG, "Error stopping content filter", e);
            Toast.makeText(this, "Failed to stop content filter", Toast.LENGTH_LONG).show();
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        // Handle VPN permission result
        if (requestCode == VpnContentFilterManager.getVpnRequestCode() || 
            requestCode == VpnContentFilterManager.getOverlayRequestCode()) {
            vpnManager.handleVpnPermissionResult(requestCode, resultCode);
            updateStatus();
        }
    }
    
    private void testContentBlocking() {
        try {
            // Test with YouTube which should be blocked
            Intent browserIntent = new Intent(Intent.ACTION_VIEW);
            browserIntent.setData(android.net.Uri.parse("https://youtube.com"));
            
            if (browserIntent.resolveActivity(getPackageManager()) != null) {
                startActivity(browserIntent);
                Toast.makeText(this, "Opening YouTube - should show blocked page if filter is active", 
                              Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "No browser found to test with", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error testing content blocking", e);
            Toast.makeText(this, "Error opening test site", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void updateStatus() {
        StringBuilder status = new StringBuilder();
        status.append("=== VPN Content Filter Status ===\n\n");
        
        boolean isActive = VpnContentFilterManager.isContentFilteringActive();
        status.append("Filter Status: ").append(isActive ? "ACTIVE ✓" : "INACTIVE ✗").append("\n\n");
        
        status.append("=== Current Configuration ===\n");
        status.append("Adult Content: ").append(filterEngine.isBlockingAdultContent() ? "BLOCKED ✓" : "ALLOWED ✗").append("\n");
        status.append("Social Media: ").append(filterEngine.isBlockingSocialMedia() ? "BLOCKED ✓" : "ALLOWED ✗").append("\n");
        status.append("Gaming Sites: ").append(filterEngine.isBlockingGaming() ? "BLOCKED ✓" : "ALLOWED ✗").append("\n\n");
        
        status.append("=== Blocked Domains Examples ===\n");
        if (filterEngine.isBlockingAdultContent()) {
            status.append("• pornhub.com, xvideos.com, adult.com\n");
        }
        if (filterEngine.isBlockingSocialMedia()) {
            status.append("• facebook.com, instagram.com, tiktok.com\n");
            status.append("• youtube.com, m.youtube.com\n");
        }
        if (filterEngine.isBlockingGaming()) {
            status.append("• steam.com, roblox.com, fortnite.com\n");
        }
        
        status.append("\n=== How It Works ===\n");
        status.append("1. VPN intercepts all network traffic\n");
        status.append("2. DNS queries and HTTP requests are analyzed\n");
        status.append("3. Blocked content redirected to warning page\n");
        status.append("4. Local web server serves blocked page\n\n");
        
        status.append("=== Test Instructions ===\n");
        status.append("1. Start the content filter\n");
        status.append("2. Click 'Test Blocking' to open YouTube\n");
        status.append("3. Should see professional blocked page\n");
        
        statusText.setText(status.toString());
    }
}
