// ScreenTimeCountdownFragment.java
package com.example.parentalcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class ScreenTimeCountdownFragment extends Fragment {
    private TextView tvCountdownTitle;
    private TextView tvUsedTime;
    private TextView tvRemainingTime;
    private TextView tvDailyLimit;
    private ProgressBar progressBar;
    private View warningContainer;
    private TextView tvWarningText;

    private BroadcastReceiver screenTimeUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int usedMinutes = intent.getIntExtra("used_minutes", 0);
            int remainingMinutes = intent.getIntExtra("remaining_minutes", 0);
            int dailyLimitMinutes = intent.getIntExtra("daily_limit_minutes", 120);
            float percentageUsed = intent.getFloatExtra("percentage_used", 0);
            boolean wasUpdated = intent.getBooleanExtra("was_updated", false);

            updateUI(usedMinutes, remainingMinutes, dailyLimitMinutes, percentageUsed, wasUpdated);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_screen_time_countdown, container, false);
        
        initViews(view);
        
        return view;
    }

    private void initViews(View view) {
        tvCountdownTitle = view.findViewById(R.id.tv_countdown_title);
        tvUsedTime = view.findViewById(R.id.tv_used_time);
        tvRemainingTime = view.findViewById(R.id.tv_remaining_time);
        tvDailyLimit = view.findViewById(R.id.tv_daily_limit);
        progressBar = view.findViewById(R.id.progress_bar);
        warningContainer = view.findViewById(R.id.warning_container);
        tvWarningText = view.findViewById(R.id.tv_warning_text);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Register broadcast receiver
        IntentFilter filter = new IntentFilter("com.example.parentalcontrol.SCREEN_TIME_UPDATE");
        if (getContext() != null) {
            androidx.core.content.ContextCompat.registerReceiver(
                getContext(), 
                screenTimeUpdateReceiver, 
                filter, 
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            );
        }
        
        // Start the countdown service if not already running
        if (getContext() != null) {
            Intent serviceIntent = new Intent(getContext(), ScreenTimeCountdownService.class);
            getContext().startService(serviceIntent);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // Unregister broadcast receiver
        if (getContext() != null) {
            try {
                getContext().unregisterReceiver(screenTimeUpdateReceiver);
            } catch (IllegalArgumentException e) {
                // Receiver wasn't registered, ignore
            }
        }
    }

    private void updateUI(int usedMinutes, int remainingMinutes, int dailyLimitMinutes, float percentageUsed, boolean wasUpdated) {
        if (getActivity() == null) return;
        
        getActivity().runOnUiThread(() -> {
            // Log if rules were updated
            if (wasUpdated) {
                Log.d("ScreenTimeCountdown", "Screen time rules updated - UI refreshed");
            }
            
            // Update time displays
            tvUsedTime.setText(formatTime(usedMinutes));
            tvRemainingTime.setText(formatTime(remainingMinutes));
            tvDailyLimit.setText(formatTime(dailyLimitMinutes));
            
            // Update progress bar
            progressBar.setProgress((int) percentageUsed);
            
            // Update warning state
            if (remainingMinutes <= 0) {
                // Limit reached
                warningContainer.setVisibility(View.VISIBLE);
                tvWarningText.setText("Daily screen time limit reached!");
                warningContainer.setBackgroundColor(0xFFFF5252); // Red
                tvCountdownTitle.setText("Time's Up!");
            } else if (remainingMinutes <= 15) {
                // Warning state (15 minutes or less)
                warningContainer.setVisibility(View.VISIBLE);
                tvWarningText.setText("Warning: Only " + remainingMinutes + " minutes remaining!");
                warningContainer.setBackgroundColor(0xFFFF9800); // Orange
                tvCountdownTitle.setText("Screen Time Countdown");
            } else {
                // Normal state
                warningContainer.setVisibility(View.GONE);
                tvCountdownTitle.setText("Screen Time Today");
            }
            
            // Update progress bar color based on usage
            if (percentageUsed >= 100) {
                progressBar.getProgressDrawable().setTint(0xFFFF5252); // Red
            } else if (percentageUsed >= 80) {
                progressBar.getProgressDrawable().setTint(0xFFFF9800); // Orange
            } else {
                progressBar.getProgressDrawable().setTint(0xFF4CAF50); // Green
            }
        });
    }

    private String formatTime(int minutes) {
        if (minutes < 60) {
            return minutes + "m";
        } else {
            int hours = minutes / 60;
            int mins = minutes % 60;
            return hours + "h " + mins + "m";
        }
    }
}
