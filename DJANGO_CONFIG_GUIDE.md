# Django Server Configuration - Usage Guide

## Overview
The Django server configuration has been centralized in `DjangoServerConfig.java` for easy maintenance and updates.

## Configuration File Location
```
/home/saidi/Projects/FINAL_PROJECT/NEW_FOLDER/FYP/ParentalControl/app/src/main/java/com/example/parentalcontrol/DjangoServerConfig.java
```

## How to Update Django Server Settings

### 1. Change Django Server IP/Domain
Edit the `DJANGO_DOMAIN` constant in `DjangoServerConfig.java`:

```java
// For localhost (same device)
public static final String DJANGO_DOMAIN = "127.0.0.1";

// For network access from Android device
public static final String DJANGO_DOMAIN = "192.168.1.188";

// For Android emulator accessing host machine
public static final String DJANGO_DOMAIN = "10.0.2.2";
```

### 2. Change Django Server Port
Edit the `DJANGO_PORT` constant:

```java
// Default Django development server
public static final String DJANGO_PORT = "8000";

// Alternative ports
public static final String DJANGO_PORT = "8080";
public static final String DJANGO_PORT = "3000";
```

### 3. Change Blocked Page Path
Edit the `BLOCKED_PAGE_PATH` constant:

```java
// Default Django blocked page
public static final String BLOCKED_PAGE_PATH = "/blocked/";

// Custom paths
public static final String BLOCKED_PAGE_PATH = "/content-blocked/";
public static final String BLOCKED_PAGE_PATH = "/parental-control/blocked/";
```

## Current Configuration
```java
DJANGO_DOMAIN = "127.0.0.1"           // localhost
DJANGO_PORT = "8000"                  // Django default port
BLOCKED_PAGE_PATH = "/blocked/"       // Django blocked page URL
```

## Derived URLs
The configuration automatically generates:
- **Full Blocked Page URL**: `http://127.0.0.1:8000/blocked/`
- **Django Base URL**: `http://127.0.0.1:8000`

## Files That Use This Configuration

### VPN Service
- `SimpleDnsVpnService.java` - DNS redirect responses
- `BrowserRedirectService.java` - Browser redirect backup

### Usage in Code
```java
// Check if domain is Django server (prevent loops)
if (DjangoServerConfig.isDjangoServerDomain(domain)) {
    // Allow Django server access
}

// Get Django IP bytes for DNS response
byte[] djangoIP = DjangoServerConfig.getDjangoServerIPBytes();

// Get full blocked page URL
String blockedUrl = DjangoServerConfig.BLOCKED_PAGE_URL;

// Validate configuration
if (!DjangoServerConfig.isConfigurationValid()) {
    Log.e(TAG, "Invalid Django configuration!");
}
```

## Testing Different Configurations

### Local Testing (Same Device)
```java
DJANGO_DOMAIN = "127.0.0.1"
DJANGO_PORT = "8000"
```

### Network Testing (Different Devices)
```java
DJANGO_DOMAIN = "192.168.1.188"  // Replace with actual IP
DJANGO_PORT = "8000"
```

### Android Emulator Testing
```java
DJANGO_DOMAIN = "10.0.2.2"       // Special emulator host IP
DJANGO_PORT = "8000"
```

## Verification
The configuration includes validation methods:

```java
// Check if configuration is valid
boolean isValid = DjangoServerConfig.isConfigurationValid();

// Get configuration summary for logging
String configInfo = DjangoServerConfig.getConfigurationInfo();
Log.i(TAG, configInfo);
```

## Benefits of Centralized Configuration

1. **Single Point of Change**: Update Django server settings in one place
2. **Automatic Validation**: Built-in checks for valid IP, port, and path
3. **Consistent Usage**: All components use the same configuration
4. **Easy Debugging**: Configuration info available for logging
5. **Type Safety**: Compile-time checking for configuration usage

## Common Scenarios

### Scenario 1: Django Server IP Changes
Just update `DJANGO_DOMAIN` in `DjangoServerConfig.java` and rebuild the app.

### Scenario 2: Different Port for Testing
Update `DJANGO_PORT` in `DjangoServerConfig.java`.

### Scenario 3: Custom Blocked Page URL
Update `BLOCKED_PAGE_PATH` in `DjangoServerConfig.java`.

### Scenario 4: Production Deployment
Set appropriate production IP/domain in `DJANGO_DOMAIN`.

## Notes
- Changes require rebuilding the Android app
- The Django server must be running on the configured IP and port
- The Django server must have the configured IP in `ALLOWED_HOSTS`
- The blocked page URL must exist in Django's URL configuration
