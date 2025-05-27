# App Blocking Flow Testing Plan

## Purpose
This document outlines steps to verify that the app blocking functionality works end-to-end,
particularly the flow between ImmediateSyncService and the app blocking components.

## Prerequisites
1. Android device running the ParentalControl app
2. Admin access to the web interface to block apps
3. Accessibility service enabled on the device

## Test Cases

### Test Case 1: Manual App Blocking via Diagnostics Screen
1. Launch the ParentalControl app
2. Access the Blocking Diagnostics screen from the menu
3. Click "Check Services Status" to verify all services are running
4. Click "Test Blocking Flow" to run the integrated test
5. Verify that the test events are logged in the diagnostics screen
6. Expected: Test should show successful component communication and app blocking

### Test Case 2: End-to-End Server Sync Test
1. Launch the ParentalControl app
2. Block an app (e.g., Instagram) from the web interface
3. On the device, access the Blocking Diagnostics screen
4. Click "Force Sync Blocked Apps"
5. Verify that the diagnostics screen shows successful sync
6. Launch the blocked app (e.g., Instagram)
7. Expected: The app should be blocked immediately

### Test Case 3: Component Communication Test
1. Launch the ParentalControl app
2. Access the Blocking Diagnostics screen
3. Check the status of the services and verify all are running
4. Perform a force sync
5. Watch the event logs to verify:
   - ImmediateSyncService successfully fetches blocked apps
   - BlockingCoordinator receives and processes the data
   - AppBlockerService and AppBlockAccessibilityService receive notifications
   - Immediate enforcement works when a blocked app is running
6. Expected: All components should communicate properly and block apps

### Known Issues to Check
1. Server-side endpoint error with Django
2. Timing issues with immediate blocking
3. Permission issues with accessibility service

## Documentation
Document any issues found and how they were resolved.
