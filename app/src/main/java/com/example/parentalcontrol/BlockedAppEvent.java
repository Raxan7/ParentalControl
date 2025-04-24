package com.example.parentalcontrol;

public class BlockedAppEvent {
    public final String packageName;

    public BlockedAppEvent(String packageName) {
        this.packageName = packageName;
    }
}