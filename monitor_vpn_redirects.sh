#!/bin/bash

# VPN Redirect Monitoring Script - Debug infinite loops and browser activity
# This script provides real-time monitoring of VPN redirects to identify issues

echo "🔍 VPN Redirect Monitoring Script Started"
echo "========================================"
echo "This will monitor:"
echo "- DNS redirects and infinite loops"
echo "- Browser activity detection"
echo "- Redirect cooldown mechanisms"
echo "- Google.com filtering"
echo ""

# Colors for better readability
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Function to show real-time logs
monitor_logs() {
    echo -e "${BLUE}📱 Starting real-time VPN log monitoring...${NC}"
    echo -e "${YELLOW}Press Ctrl+C to stop${NC}"
    echo ""
    
    # Clear existing logs and start fresh monitoring
    adb logcat -c
    
    # Monitor specific tags related to redirects
    adb logcat -s SimpleDnsVPN:* BrowserRedirectService:* | while read line; do
        # Color code different types of logs
        if echo "$line" | grep -q "IMMEDIATE_REDIRECT"; then
            echo -e "${RED}🔄 REDIRECT: $line${NC}"
        elif echo "$line" | grep -q "Google.com"; then
            echo -e "${GREEN}🟢 GOOGLE: $line${NC}"
        elif echo "$line" | grep -q "cooldown\|Cooldown"; then
            echo -e "${YELLOW}⏰ COOLDOWN: $line${NC}"
        elif echo "$line" | grep -q "browser\|Browser\|isBrowserActive"; then
            echo -e "${PURPLE}📱 BROWSER: $line${NC}"
        elif echo "$line" | grep -q "fallback\|Fallback\|browsing.*hour"; then
            echo -e "${CYAN}🕐 FALLBACK: $line${NC}"
        elif echo "$line" | grep -q "infinite\|loop\|Loop"; then
            echo -e "${RED}🔁 LOOP: $line${NC}"
        elif echo "$line" | grep -q "DNS"; then
            echo -e "${CYAN}🌐 DNS: $line${NC}"
        else
            echo "$line"
        fi
    done
}

# Function to test DNS resolution
test_dns_resolution() {
    echo -e "${BLUE}🌐 Testing DNS Resolution...${NC}"
    echo ""
    
    # Test blocked domains
    echo -e "${YELLOW}Testing blocked domains:${NC}"
    for domain in "facebook.com" "twitter.com" "instagram.com"; do
        echo -n "  $domain → "
        result=$(adb shell nslookup $domain 2>/dev/null | grep "Address" | tail -1 | awk '{print $2}')
        if [ "$result" = "172.217.12.142" ]; then
            echo -e "${GREEN}✅ Redirected to Google (172.217.12.142)${NC}"
        elif [ -z "$result" ]; then
            echo -e "${RED}❌ No resolution${NC}"
        else
            echo -e "${YELLOW}⚠️  Resolved to: $result${NC}"
        fi
    done
    
    echo ""
    echo -e "${YELLOW}Testing Google domains (should NOT redirect):${NC}"
    for domain in "google.com" "www.google.com"; do
        echo -n "  $domain → "
        result=$(adb shell nslookup $domain 2>/dev/null | grep "Address" | tail -1 | awk '{print $2}')
        if [ "$result" = "172.217.12.142" ] || echo "$result" | grep -q "172.217"; then
            echo -e "${GREEN}✅ Resolved to Google IP: $result${NC}"
        elif [ -z "$result" ]; then
            echo -e "${RED}❌ No resolution${NC}"
        else
            echo -e "${YELLOW}ℹ️  Resolved to: $result${NC}"
        fi
    done
    echo ""
}

# Function to check VPN status
check_vpn_status() {
    echo -e "${BLUE}🔒 Checking VPN Status...${NC}"
    
    # Check if VPN service is running
    vpn_running=$(adb shell ps | grep -c "parentalcontrol")
    if [ $vpn_running -gt 0 ]; then
        echo -e "${GREEN}✅ VPN Service is running${NC}"
    else
        echo -e "${RED}❌ VPN Service not running${NC}"
    fi
    
    # Check VPN interface
    vpn_interface=$(adb shell ip route | grep -c "tun")
    if [ $vpn_interface -gt 0 ]; then
        echo -e "${GREEN}✅ VPN interface active${NC}"
        adb shell ip route | grep tun
    else
        echo -e "${RED}❌ No VPN interface found${NC}"
    fi
    
    echo ""
}

# Function to check browser activity
check_browser_activity() {
    echo -e "${BLUE}📱 Checking Browser Activity...${NC}"
    
    # Get current foreground app using multiple methods
    echo "Method 1 - dumpsys activity:"
    current_app=$(adb shell dumpsys activity activities | grep -A 1 "ResumedActivity" | grep "ActivityRecord" | awk '{print $3}' | cut -d'/' -f1)
    echo "  Current foreground app: $current_app"
    
    echo ""
    echo "Method 2 - Running processes:"
    adb shell ps | grep -E "(chrome|browser|firefox|opera|edge|samsung)" | head -5
    
    echo ""
    echo "Method 3 - Package manager (installed browsers):"
    adb shell pm list packages | grep -E "(chrome|browser|firefox|opera|edge|samsung)" | head -5
    
    echo ""
    if echo "$current_app" | grep -qE "(chrome|browser|firefox|opera|edge|samsung|webview)"; then
        echo -e "${GREEN}✅ Browser is active (detected: $current_app)${NC}"
    else
        echo -e "${YELLOW}⚠️  Browser not clearly detected${NC}"
        echo -e "${CYAN}ℹ️  Current time: $(date '+%H:%M') - fallback may apply if between 06:00-23:00${NC}"
    fi
    echo ""
}

# Function to test redirect scenarios
test_redirect_scenarios() {
    echo -e "${BLUE}🧪 Testing Redirect Scenarios...${NC}"
    echo ""
    
    echo -e "${YELLOW}Scenario 1: Testing blocked domain redirect${NC}"
    adb shell am start -a android.intent.action.VIEW -d "https://facebook.com"
    sleep 3
    
    echo -e "${YELLOW}Scenario 2: Testing Google.com (should NOT redirect)${NC}"
    adb shell am start -a android.intent.action.VIEW -d "https://google.com"
    sleep 3
    
    echo -e "${YELLOW}Scenario 3: Testing multiple rapid requests (infinite loop test)${NC}"
    for i in {1..3}; do
        echo "  Request $i/3..."
        adb shell am start -a android.intent.action.VIEW -d "https://twitter.com"
        sleep 1
    done
    
    echo ""
}

# Function to show redirect statistics
show_redirect_stats() {
    echo -e "${BLUE}📊 Redirect Statistics (last 100 lines)${NC}"
    echo ""
    
    # Get recent logs
    redirect_count=$(adb logcat -d | grep "IMMEDIATE_REDIRECT" | wc -l)
    google_count=$(adb logcat -d | grep "Google.com" | wc -l)
    cooldown_count=$(adb logcat -d | grep -i "cooldown" | wc -l)
    browser_check_count=$(adb logcat -d | grep "isBrowserActive" | wc -l)
    
    echo "📈 Recent Activity:"
    echo "  - Total redirects: $redirect_count"
    echo "  - Google.com queries: $google_count"
    echo "  - Cooldown events: $cooldown_count"
    echo "  - Browser checks: $browser_check_count"
    echo ""
    
    # Show recent redirect events
    echo -e "${YELLOW}🕒 Recent redirect events:${NC}"
    adb logcat -d | grep "IMMEDIATE_REDIRECT" | tail -5 | while read line; do
        echo "  $line"
    done
    echo ""
}

# Function to run comprehensive test
run_comprehensive_test() {
    echo -e "${BLUE}🔬 Running Comprehensive VPN Redirect Test...${NC}"
    echo "================================================"
    
    check_vpn_status
    check_browser_activity
    test_dns_resolution
    show_redirect_stats
    
    echo -e "${YELLOW}🧪 Running live redirect test...${NC}"
    test_redirect_scenarios
    
    echo -e "${GREEN}✅ Comprehensive test completed${NC}"
    echo ""
}

# Function to clear and reset
clear_and_reset() {
    echo -e "${BLUE}🧹 Clearing logs and resetting...${NC}"
    adb logcat -c
    echo -e "${GREEN}✅ Logs cleared${NC}"
    echo ""
}

# Main menu
show_menu() {
    echo -e "${CYAN}VPN Redirect Monitoring Menu:${NC}"
    echo "1. 📱 Real-time log monitoring (RECOMMENDED)"
    echo "2. 🌐 Test DNS resolution"
    echo "3. 🔒 Check VPN status"
    echo "4. 📱 Check browser activity"
    echo "5. 🧪 Test redirect scenarios"
    echo "6. 📊 Show redirect statistics"
    echo "7. 🔬 Run comprehensive test"
    echo "8. 🧹 Clear logs and reset"
    echo "9. 🚪 Exit"
    echo ""
}

# Main execution
if [ $# -eq 0 ]; then
    while true; do
        show_menu
        read -p "Choose an option (1-9): " choice
        echo ""
        
        case $choice in
            1) monitor_logs ;;
            2) test_dns_resolution ;;
            3) check_vpn_status ;;
            4) check_browser_activity ;;
            5) test_redirect_scenarios ;;
            6) show_redirect_stats ;;
            7) run_comprehensive_test ;;
            8) clear_and_reset ;;
            9) echo "👋 Goodbye!"; exit 0 ;;
            *) echo -e "${RED}❌ Invalid option${NC}" ;;
        esac
        echo ""
        read -p "Press Enter to continue..."
        echo ""
    done
else
    # Command line argument support
    case $1 in
        "monitor"|"logs") monitor_logs ;;
        "dns") test_dns_resolution ;;
        "status") check_vpn_status ;;
        "browser") check_browser_activity ;;
        "test") test_redirect_scenarios ;;
        "stats") show_redirect_stats ;;
        "all") run_comprehensive_test ;;
        "clear") clear_and_reset ;;
        *) echo "Usage: $0 [monitor|dns|status|browser|test|stats|all|clear]" ;;
    esac
fi
