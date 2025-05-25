// ServiceLocator.java
package com.example.parentalcontrol;

import android.content.Context;

public class ServiceLocator {
    private static ServiceLocator instance;
    private AppUsageDatabaseHelper dbHelper;

    public static synchronized ServiceLocator getInstance(Context context) {
        if (instance == null) {
            instance = new ServiceLocator(context.getApplicationContext());
        }
        return instance;
    }

    private ServiceLocator(Context context) {
        dbHelper = new AppUsageDatabaseHelper(context);
    }

    public AppUsageDatabaseHelper getDatabaseHelper() {
        return dbHelper;
    }

    public ContentFilter getContentFilter() {
        return new ContentFilter();
    }

    public ScreenTimeManager getScreenTimeManager(Context context) {
        return new ScreenTimeManager(context);
    }

    public ScreenTimeRepository getScreenTimeRepository(Context context) {
        return new ScreenTimeRepository(context);
    }

    public ScreenTimeSync getScreenTimeSync(Context context) {
        return new ScreenTimeSync(context);
    }
}