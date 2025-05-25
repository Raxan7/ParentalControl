package com.example.parentalcontrol;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Button;
import android.widget.TextView;

public class AppBlockedActivity extends Activity {
    private static final int AUTO_CLOSE_DELAY = 3000; // 3 seconds
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_blocked);
        
        // Get blocked app information
        String blockedPackage = getIntent().getStringExtra("blocked_package");
        String blockedAppName = getIntent().getStringExtra("blocked_app_name");
        
        // Set up UI
        TextView messageText = findViewById(R.id.blocked_message);
        Button okButton = findViewById(R.id.ok_button);
        
        String message = "The app \"" + (blockedAppName != null ? blockedAppName : blockedPackage) + 
                        "\" is blocked by parental controls.";
        messageText.setText(message);
        
        okButton.setOnClickListener(v -> goHome());
        
        // Auto-close after delay
        new Handler().postDelayed(this::goHome, AUTO_CLOSE_DELAY);
    }
    
    private void goHome() {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(homeIntent);
        finish();
    }
    
    @Override
    public void onBackPressed() {
        // Prevent going back to blocked app
        goHome();
    }
}
