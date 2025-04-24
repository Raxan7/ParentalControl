// AppUsage.java
package com.example.parentalcontrol;

public class AppUsage {
    private String packageName;
    private long startTime;
    private long endTime;

    public AppUsage(String packageName, long startTime, long endTime) {
        this.packageName = packageName;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    // Getters
    public String getPackageName() {
        return packageName;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    // Setters (if needed)
    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }
}