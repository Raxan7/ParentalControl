package com.example.parentalcontrol;

/**
 * Centralized configuration for Django server connection settings
 * Update this file when Django server location changes
 */
public class DjangoServerConfig {
    
    // Django Server Configuration
    // =========================
    // Update these values when Django server location changes
    
    /**
     * Django server IP address
     * Common values:
     * - "127.0.0.1" for localhost (same device)
     * - "192.168.1.123" for local network access
     * - "192.168.1.188" for network access
     * - "10.0.2.2" for Android emulator to host machine
     */
    public static final String DJANGO_DOMAIN = "192.168.1.123"; // Update to your Django server IP or domain
    // Note: If using a domain name, ensure it resolves to the correct IP
    // Example: public static final String DJANGO_DOMAIN = "my-django-server.com";
    
    /**
     * Django server port
     * Default Django development server port: 8000
     * Alternative ports: 8080, 3000, etc.
     */
    public static final String DJANGO_PORT = "8080";
    
    /**
     * Path to Django blocked content page
     * This should match the URL pattern in Django's urls.py
     */
    public static final String BLOCKED_PAGE_PATH = "/blocked/";
    
    // Derived Configuration
    // ====================
    // These are automatically generated from above settings
    
    /**
     * Complete URL to Django blocked page
     */
    public static final String BLOCKED_PAGE_URL = "http://" + DJANGO_DOMAIN + ":" + DJANGO_PORT + BLOCKED_PAGE_PATH;
    
    /**
     * Django server base URL
     */
    public static final String DJANGO_BASE_URL = "http://" + DJANGO_DOMAIN + ":" + DJANGO_PORT;
    
    // Utility Methods
    // ==============
    
    /**
     * Check if a domain is the Django server (prevent infinite redirect loops)
     * @param domain Domain to check
     * @return true if domain matches Django server
     */
    public static boolean isDjangoServerDomain(String domain) {
        if (domain == null) return false;
        String lowerDomain = domain.toLowerCase();
        return lowerDomain.equals(DJANGO_DOMAIN) || 
               lowerDomain.equals("localhost") || 
               lowerDomain.startsWith(DJANGO_DOMAIN + ":") ||
               lowerDomain.startsWith("localhost:");
    }
    
    /**
     * Get Django server IP as byte array for DNS responses
     * @return IP address as 4-byte array
     */
    public static byte[] getDjangoServerIPBytes() {
        String[] parts = DJANGO_DOMAIN.split("\\.");
        if (parts.length != 4) {
            // Fallback for non-IP domains (like "localhost")
            return new byte[]{127, 0, 0, 1}; // localhost
        }
        
        try {
            return new byte[]{
                (byte) Integer.parseInt(parts[0]),
                (byte) Integer.parseInt(parts[1]),
                (byte) Integer.parseInt(parts[2]),
                (byte) Integer.parseInt(parts[3])
            };
        } catch (NumberFormatException e) {
            // Fallback for invalid IP format
            return new byte[]{127, 0, 0, 1}; // localhost
        }
    }
    
    /**
     * Configuration validation - checks if settings are valid
     * @return true if configuration appears valid
     */
    public static boolean isConfigurationValid() {
        // Basic validation checks
        if (DJANGO_DOMAIN == null || DJANGO_DOMAIN.isEmpty()) return false;
        if (DJANGO_PORT == null || DJANGO_PORT.isEmpty()) return false;
        if (BLOCKED_PAGE_PATH == null || BLOCKED_PAGE_PATH.isEmpty()) return false;
        
        // Port should be numeric
        try {
            int port = Integer.parseInt(DJANGO_PORT);
            if (port < 1 || port > 65535) return false;
        } catch (NumberFormatException e) {
            return false;
        }
        
        // Path should start with /
        if (!BLOCKED_PAGE_PATH.startsWith("/")) return false;
        
        return true;
    }
    
    /**
     * Get configuration info for logging/debugging
     * @return Human-readable configuration summary
     */
    public static String getConfigurationInfo() {
        return String.format(
            "Django Server Config:\n" +
            "  Domain: %s\n" +
            "  Port: %s\n" +
            "  Blocked Page: %s\n" +
            "  Full URL: %s\n" +
            "  Valid: %s",
            DJANGO_DOMAIN, DJANGO_PORT, BLOCKED_PAGE_PATH, BLOCKED_PAGE_URL,
            isConfigurationValid() ? "Yes" : "No"
        );
    }
}
