package com.example.parentalcontrol;

public class NewBlockedAppEvent {
    public final String packageName;

    public NewBlockedAppEvent(String packageName) {
        this.packageName = packageName;
    }
}