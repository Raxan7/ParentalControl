#!/bin/bash

# Build and Run Android App Script
cd /home/saidi/Projects/FYP/ParentalControl

echo "Building Android app..."
./gradlew assembleDebug

if [ $? -eq 0 ]; then
  echo "Build successful!"
  
  # Check if a device is connected
  DEVICE=$(adb devices | grep -v "List" | grep "device" | head -n 1 | cut -f 1)
  
  if [ -n "$DEVICE" ]; then
    echo "Installing app on device $DEVICE..."
    ./gradlew installDebug
    
    if [ $? -eq 0 ]; then
      echo "App installed successfully!"
      echo "Launching the app..."
      adb shell am start -n com.example.parentalcontrol/.MainActivity
    else
      echo "Error installing the app."
    fi
  else
    echo "No device connected. Please connect an Android device and try again."
  fi
else
  echo "Build failed. Please check the errors above."
fi
