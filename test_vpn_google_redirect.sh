#!/bin/bash

# VPN Google Redirect Testing and Debug Script
# This script helps test and debug the immediate Google.com redirect functionality

echo "🔄 VPN Google Redirect Testing & Debug Script"
echo "============================================="

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Get package name and device info
PACKAGE_NAME="com.example.parentalcontrol"
DEVICE_ID=$(adb shell settings get secure android_id 2>/dev/null)

echo -e "${BLUE}📱 Device ID: ${DEVICE_ID}${NC}"
echo -e "${BLUE}📦 Package: ${PACKAGE_NAME}${NC}"
echo ""

# Function to print section headers
print_section() {
    echo -e "${YELLOW}🔍 $1${NC}"
    echo "----------------------------------------"
}

# Function to run adb command with error handling
run_adb() {
    local cmd="$1"
    local description="$2"
    echo -e "${BLUE}Running: ${description}${NC}"
    echo "Command: adb $cmd"
    adb $cmd
    echo ""
}

# Function to clear and monitor logs
start_log_monitoring() {
    print_section "Starting Log Monitoring"
    
    # Clear existing logs
    run_adb "logcat -c" "Clearing existing logs"
    
    echo -e "${GREEN}📋 Starting real-time log monitoring...${NC}"
    echo -e "${YELLOW}Look for these key log tags:${NC}"
    echo "  - SimpleDnsVPN (DNS redirect logic)"
    echo "  - IMMEDIATE_REDIRECT (redirect debugging)"
    echo "  - BrowserRedirectService (backup redirect)"
    echo "  - VpnDebugAuditor (VPN auditing)"
    echo ""
    echo -e "${RED}Press Ctrl+C to stop monitoring${NC}"
    echo ""
    
    # Start filtered log monitoring
    adb logcat -v time \
        SimpleDnsVPN:I \
        BrowserRedirectService:I \
        VpnDebugAuditor:I \
        VpnContentFilterManager:I \
        ContentFilterEngine:I \
        "*:E" | \
    while read line; do
        # Highlight important lines
        if [[ $line == *"IMMEDIATE_REDIRECT"* ]]; then
            echo -e "${GREEN}🔄 $line${NC}"
        elif [[ $line == *"BLOCKING"* ]] || [[ $line == *"blocked"* ]]; then
            echo -e "${RED}🚫 $line${NC}"
        elif [[ $line == *"Google"* ]] || [[ $line == *"redirect"* ]]; then
            echo -e "${BLUE}🎯 $line${NC}"
        elif [[ $line == *"ERROR"* ]] || [[ $line == *"FAILED"* ]]; then
            echo -e "${RED}❌ $line${NC}"
        else
            echo "$line"
        fi
    done
}

# Function to test VPN status
check_vpn_status() {
    print_section "Checking VPN Status"
    
    # Check if VPN is connected
    VPN_STATUS=$(adb shell dumpsys connectivity | grep -i vpn)
    if [[ -n "$VPN_STATUS" ]]; then
        echo -e "${GREEN}✅ VPN appears to be active${NC}"
        echo "$VPN_STATUS"
    else
        echo -e "${RED}❌ VPN not detected${NC}"
    fi
    echo ""
    
    # Check if our VPN service is running
    SERVICE_STATUS=$(adb shell dumpsys activity services | grep -i SimpleDnsVpnService)
    if [[ -n "$SERVICE_STATUS" ]]; then
        echo -e "${GREEN}✅ SimpleDnsVpnService is running${NC}"
    else
        echo -e "${RED}❌ SimpleDnsVpnService not running${NC}"
    fi
    echo ""
}

# Function to test DNS resolution
test_dns_resolution() {
    print_section "Testing DNS Resolution"
    
    # Test domains that should be blocked
    TEST_DOMAINS=("facebook.com" "instagram.com" "tiktok.com" "snapchat.com")
    
    for domain in "${TEST_DOMAINS[@]}"; do
        echo -e "${BLUE}🔍 Testing DNS resolution for: $domain${NC}"
        DNS_RESULT=$(adb shell nslookup $domain 2>/dev/null | grep "Address")
        
        if [[ $DNS_RESULT == *"172.217.12.142"* ]]; then
            echo -e "${GREEN}✅ $domain correctly redirects to Google IP (172.217.12.142)${NC}"
        elif [[ $DNS_RESULT == *"127.0.0.1"* ]]; then
            echo -e "${YELLOW}⚠️  $domain redirects to localhost (127.0.0.1) - old behavior${NC}"
        else
            echo -e "${RED}❌ $domain resolves to: $DNS_RESULT${NC}"
        fi
        echo ""
    done
}

# Function to trigger test blocking
trigger_test_block() {
    print_section "Triggering Test Block"
    
    echo -e "${BLUE}🧪 Attempting to trigger content blocking...${NC}"
    
    # Method 1: Try to access a blocked domain via browser intent
    echo "Method 1: Opening blocked domain in browser"
    run_adb "shell am start -a android.intent.action.VIEW -d 'https://facebook.com'" "Opening Facebook in browser"
    sleep 2
    
    # Method 2: Try DNS query directly
    echo "Method 2: Direct DNS query"
    run_adb "shell nslookup facebook.com" "DNS lookup for facebook.com"
    
    echo -e "${YELLOW}💡 Check the log output above for redirect behavior${NC}"
    echo ""
}

# Function to build and install app
build_and_install() {
    print_section "Building and Installing App"
    
    echo -e "${BLUE}🔨 Building APK...${NC}"
    if ./gradlew assembleDebug; then
        echo -e "${GREEN}✅ Build successful${NC}"
        
        echo -e "${BLUE}📲 Installing APK...${NC}"
        if adb install -r app/build/outputs/apk/debug/app-debug.apk; then
            echo -e "${GREEN}✅ Installation successful${NC}"
        else
            echo -e "${RED}❌ Installation failed${NC}"
            return 1
        fi
    else
        echo -e "${RED}❌ Build failed${NC}"
        return 1
    fi
    echo ""
}

# Function to start VPN service manually
start_vpn_service() {
    print_section "Starting VPN Service"
    
    # Launch the main app
    run_adb "shell am start -n $PACKAGE_NAME/.MainActivity" "Starting main app"
    sleep 3
    
    # Try to start VPN service directly
    run_adb "shell am startservice -n $PACKAGE_NAME/.SimpleDnsVpnService" "Starting VPN service"
    sleep 2
    
    echo -e "${YELLOW}💡 You may need to grant VPN permissions manually in the app${NC}"
    echo ""
}

# Function to show comprehensive debug info
show_debug_info() {
    print_section "Comprehensive Debug Information"
    
    echo -e "${BLUE}📊 Network Interfaces:${NC}"
    adb shell ip route show
    echo ""
    
    echo -e "${BLUE}📊 Active Connections:${NC}"
    adb shell netstat -tuln | head -20
    echo ""
    
    echo -e "${BLUE}📊 DNS Configuration:${NC}"
    adb shell getprop | grep dns
    echo ""
    
    echo -e "${BLUE}📊 VPN-related Processes:${NC}"
    adb shell ps | grep -E "(vpn|dns|$PACKAGE_NAME)"
    echo ""
}

# Main menu
show_menu() {
    echo -e "${YELLOW}🎛️  Choose an action:${NC}"
    echo "1. 📋 Start Real-time Log Monitoring"
    echo "2. 🔍 Check VPN Status"
    echo "3. 🧪 Test DNS Resolution"
    echo "4. 🎯 Trigger Test Block"
    echo "5. 🔨 Build and Install App"
    echo "6. 🚀 Start VPN Service"
    echo "7. 📊 Show Debug Info"
    echo "8. 🔄 Full Test Sequence"
    echo "9. ❌ Exit"
    echo ""
}

# Full test sequence
full_test_sequence() {
    print_section "Full Test Sequence"
    
    echo -e "${BLUE}🔄 Running complete test sequence...${NC}"
    echo ""
    
    build_and_install
    sleep 2
    
    start_vpn_service
    sleep 5
    
    check_vpn_status
    sleep 2
    
    test_dns_resolution
    sleep 2
    
    trigger_test_block
    
    echo -e "${GREEN}✅ Full test sequence completed${NC}"
    echo -e "${YELLOW}💡 Monitor the logs above for redirect behavior${NC}"
    echo ""
}

# Main script execution
main() {
    while true; do
        show_menu
        read -p "Enter your choice (1-9): " choice
        echo ""
        
        case $choice in
            1) start_log_monitoring ;;
            2) check_vpn_status ;;
            3) test_dns_resolution ;;
            4) trigger_test_block ;;
            5) build_and_install ;;
            6) start_vpn_service ;;
            7) show_debug_info ;;
            8) full_test_sequence ;;
            9) echo -e "${GREEN}👋 Goodbye!${NC}"; exit 0 ;;
            *) echo -e "${RED}❌ Invalid choice. Please try again.${NC}"; echo "" ;;
        esac
        
        read -p "Press Enter to continue..."
        echo ""
    done
}

# Check if adb is available
if ! command -v adb &> /dev/null; then
    echo -e "${RED}❌ ADB not found. Please install Android SDK platform-tools.${NC}"
    exit 1
fi

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo -e "${RED}❌ No Android device connected. Please connect a device and enable USB debugging.${NC}"
    exit 1
fi

# Start main script
main
