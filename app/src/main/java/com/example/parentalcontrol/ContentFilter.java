package com.example.parentalcontrol;

import android.content.Context;
import android.content.pm.PackageManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.Arrays;
import java.util.List;

public class ContentFilter {
    private static final List<String> BLOCKED_APPS = Arrays.asList("com.instagram.android", "com.tiktok");
    private static final List<String> BLOCKED_URLS = Arrays.asList("pornhub", "xxx");

    public static boolean isAppAllowed(String packageName) {
        return !BLOCKED_APPS.contains(packageName);
    }

    public static WebViewClient getFilteredWebClient() {
        return new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                for (String blocked : BLOCKED_URLS) {
                    if (url.contains(blocked)) {
                        view.loadUrl("about:blank");
                        return true;
                    }
                }
                return false;
            }
        };
    }
}