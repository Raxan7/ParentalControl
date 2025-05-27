package com.example.parentalcontrol;

/**
 * Event to trigger immediate sync of blocked apps
 */
public class ImmediateSyncRequestEvent {
    public final String reason;

    public ImmediateSyncRequestEvent(String reason) {
        this.reason = reason;
    }
}
