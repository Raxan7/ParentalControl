// ActivityEvent.java
package com.example.parentalcontrol;

public class ActivityEvent {
    public final String packageName;
    public final long startTime;
    public final long endTime;

    public ActivityEvent(String packageName, long startTime, long endTime) {
        this.packageName = packageName;
        this.startTime = startTime;
        this.endTime = endTime;
    }
}