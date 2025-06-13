package com.example.parentalcontrol;

import android.content.Context;
import android.util.Log;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Comprehensive VPN diagnostics system to identify root causes of website loading failures.
 * This class performs systematic tests to isolate the specific issue preventing websites from loading.
 */
public class VpnDiagnostics {
    private static final String TAG = "VpnDiagnostics";
    
    // Test domains for validation
    private static final String[] TEST_DOMAINS = {
        "chatgpt.com",
        "google.com", 
        "github.com",
        "cloudflare.com",
        "example.com"
    };
    
    private static final String[] BLOCKED_TEST_DOMAINS = {
        "m.youtube.com",
        "facebook.com",
        "pornhub.com"
    };
    
    private final Context context;
    private final ContentFilterEngine filterEngine;
    
    public VpnDiagnostics(Context context) {
        this.context = context;
        this.filterEngine = new ContentFilterEngine(context);
    }
    
    /**
     * Run comprehensive diagnostics to identify VPN issues
     */
    public void runCompleteDiagnostics() {
        Log.i(TAG, "================ STARTING VPN DIAGNOSTICS ================");
        
        // Test 1: Validate DNS resolution outside VPN
        testExternalDnsResolution();
        
        // Test 2: Test filter engine logic
        testFilterEngine();
        
        // Test 3: Test DNS packet construction
        testDnsPacketConstruction();
        
        // Test 4: Test network connectivity
        testNetworkConnectivity();
        
        // Test 5: Test DNS forwarding mechanism
        testDnsForwardingMechanism();
        
        // Test 6: Validate VPN routing
        testVpnRouting();
        
        Log.i(TAG, "================ DIAGNOSTICS COMPLETE ================");
    }
    
    /**
     * TEST 1: Verify DNS resolution works outside VPN context
     */
    public void testExternalDnsResolution() {
        Log.i(TAG, "TEST 1: External DNS Resolution");
        
        for (String domain : TEST_DOMAINS) {
            try {
                long startTime = System.currentTimeMillis();
                InetAddress[] addresses = InetAddress.getAllByName(domain);
                long duration = System.currentTimeMillis() - startTime;
                
                Log.i(TAG, "  ✓ " + domain + " resolved to " + addresses.length + 
                      " addresses in " + duration + "ms");
                for (InetAddress addr : addresses) {
                    Log.i(TAG, "    - " + addr.getHostAddress());
                }
                
            } catch (Exception e) {
                Log.e(TAG, "  ✗ Failed to resolve " + domain + ": " + e.getMessage());
            }
        }
    }
    
    /**
     * TEST 2: Validate filter engine decisions
     */
    public void testFilterEngine() {
        Log.i(TAG, "TEST 2: Filter Engine Logic");
        
        // Test allowed domains
        for (String domain : TEST_DOMAINS) {
            boolean shouldBlock = filterEngine.shouldBlockDomain(domain);
            if (shouldBlock) {
                Log.e(TAG, "  ✗ CRITICAL: " + domain + " is being blocked when it should be allowed!");
            } else {
                Log.i(TAG, "  ✓ " + domain + " correctly allowed");
            }
        }
        
        // Test blocked domains
        for (String domain : BLOCKED_TEST_DOMAINS) {
            boolean shouldBlock = filterEngine.shouldBlockDomain(domain);
            if (!shouldBlock) {
                Log.w(TAG, "  ⚠ WARNING: " + domain + " is not being blocked when it should be");
            } else {
                Log.i(TAG, "  ✓ " + domain + " correctly blocked");
            }
        }
    }
    
    /**
     * TEST 3: Validate DNS packet construction and parsing
     */
    private void testDnsPacketConstruction() {
        Log.i(TAG, "TEST 3: DNS Packet Construction");
        
        try {
            // Create a mock DNS query packet for chatgpt.com
            byte[] mockDnsQuery = createMockDnsQuery("chatgpt.com");
            Log.i(TAG, "  ✓ Mock DNS query created, length: " + mockDnsQuery.length);
            
            // Test domain extraction
            ByteBuffer packet = ByteBuffer.wrap(mockDnsQuery);
            String extractedDomain = extractDomainFromMockPacket(packet);
            
            if ("chatgpt.com".equals(extractedDomain)) {
                Log.i(TAG, "  ✓ Domain extraction working correctly");
            } else {
                Log.e(TAG, "  ✗ CRITICAL: Domain extraction failed. Expected 'chatgpt.com', got: '" + 
                      extractedDomain + "'");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "  ✗ DNS packet construction test failed", e);
        }
    }
    
    /**
     * TEST 4: Test basic network connectivity
     */
    private void testNetworkConnectivity() {
        Log.i(TAG, "TEST 4: Network Connectivity");
        
        // Test direct DNS server connectivity
        testDirectDnsConnectivity("8.8.8.8", 53);
        testDirectDnsConnectivity("1.1.1.1", 53);
        
        // Test HTTP connectivity (if allowed)
        testHttpConnectivity("http://example.com");
    }
    
    private void testDirectDnsConnectivity(String dnsServer, int port) {
        try {
            long startTime = System.currentTimeMillis();
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(dnsServer, port), 3000);
            socket.close();
            long duration = System.currentTimeMillis() - startTime;
            
            Log.i(TAG, "  ✓ DNS server " + dnsServer + ":" + port + " reachable in " + duration + "ms");
            
        } catch (Exception e) {
            Log.e(TAG, "  ✗ DNS server " + dnsServer + ":" + port + " unreachable: " + e.getMessage());
        }
    }
    
    private void testHttpConnectivity(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("HEAD");
            
            int responseCode = connection.getResponseCode();
            Log.i(TAG, "  ✓ HTTP connection to " + url + " successful, response: " + responseCode);
            
        } catch (Exception e) {
            Log.e(TAG, "  ✗ HTTP connection to " + url + " failed: " + e.getMessage());
        }
    }
    
    /**
     * TEST 5: Test DNS forwarding mechanism in isolation
     */
    private void testDnsForwardingMechanism() {
        Log.i(TAG, "TEST 5: DNS Forwarding Mechanism");
        
        for (String domain : TEST_DOMAINS) {
            try {
                Log.i(TAG, "  Testing DNS forwarding for: " + domain);
                
                // Create a real DNS query
                byte[] dnsQuery = createRealDnsQuery(domain);
                
                // Forward to Google DNS
                byte[] response = forwardDnsQueryDirectly(dnsQuery);
                
                if (response != null && response.length > 0) {
                    Log.i(TAG, "    ✓ DNS forwarding successful, response length: " + response.length);
                    
                    // Parse response to verify it contains valid data
                    if (isValidDnsResponse(response)) {
                        Log.i(TAG, "    ✓ Response appears to be valid DNS data");
                    } else {
                        Log.w(TAG, "    ⚠ Response may not be valid DNS data");
                    }
                } else {
                    Log.e(TAG, "    ✗ DNS forwarding failed for " + domain);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "    ✗ DNS forwarding test failed for " + domain, e);
            }
        }
    }
    
    /**
     * TEST 6: Validate VPN routing configuration
     */
    private void testVpnRouting() {
        Log.i(TAG, "TEST 6: VPN Routing Analysis");
        
        // This would typically require root access or special permissions
        // For now, we'll log what we can detect about the routing configuration
        
        try {
            // Check if we can detect the VPN interface
            Log.i(TAG, "  Checking network interfaces...");
            
            // Log network configuration that we can access
            Collections.list(NetworkInterface.getNetworkInterfaces()).forEach(networkInterface -> {
                try {
                    if (networkInterface.isUp() && !networkInterface.isLoopback()) {
                        Log.i(TAG, "    Interface: " + networkInterface.getName() + 
                              " (" + networkInterface.getDisplayName() + ")");
                        
                        Collections.list(networkInterface.getInetAddresses()).forEach(inetAddress -> {
                            Log.i(TAG, "      Address: " + inetAddress.getHostAddress());
                        });
                    }
                } catch (Exception e) {
                    Log.w(TAG, "    Error checking interface: " + e.getMessage());
                }
            });
            
        } catch (Exception e) {
            Log.e(TAG, "  ✗ VPN routing analysis failed", e);
        }
    }
    
    /**
     * Helper method to create a mock DNS query packet
     */
    private byte[] createMockDnsQuery(String domain) {
        // Create a simplified mock DNS query packet
        // This is a basic implementation for testing purposes
        
        // IP Header (20 bytes) + UDP Header (8 bytes) + DNS Header (12 bytes) + Question
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        
        // IP Header (simplified)
        buffer.put((byte) 0x45); // Version 4, IHL 5
        buffer.put((byte) 0x00); // Type of Service
        buffer.putShort((short) 0); // Total Length (will be filled)
        buffer.putShort((short) 0x1234); // Identification
        buffer.putShort((short) 0x4000); // Flags, Fragment Offset
        buffer.put((byte) 64); // TTL
        buffer.put((byte) 17); // Protocol (UDP)
        buffer.putShort((short) 0); // Header Checksum
        buffer.putInt(0x0A000001); // Source IP (10.0.0.1)
        buffer.putInt(0x08080808); // Dest IP (8.8.8.8)
        
        // UDP Header
        buffer.putShort((short) 12345); // Source Port
        buffer.putShort((short) 53); // Dest Port (DNS)
        buffer.putShort((short) 0); // Length (will be filled)
        buffer.putShort((short) 0); // Checksum
        
        // DNS Header
        buffer.putShort((short) 0x1234); // Transaction ID
        buffer.putShort((short) 0x0100); // Flags (standard query)
        buffer.putShort((short) 1); // Questions
        buffer.putShort((short) 0); // Answer RRs
        buffer.putShort((short) 0); // Authority RRs
        buffer.putShort((short) 0); // Additional RRs
        
        // DNS Question
        String[] parts = domain.split("\\.");
        for (String part : parts) {
            buffer.put((byte) part.length());
            buffer.put(part.getBytes());
        }
        buffer.put((byte) 0); // End of name
        buffer.putShort((short) 1); // Type A
        buffer.putShort((short) 1); // Class IN
        
        // Fill in lengths
        int totalLength = buffer.position();
        int udpLength = totalLength - 20; // Total - IP header
        
        buffer.putShort(2, (short) totalLength); // IP total length
        buffer.putShort(24, (short) udpLength); // UDP length
        
        byte[] result = new byte[totalLength];
        buffer.rewind();
        buffer.get(result);
        return result;
    }
    
    private String extractDomainFromMockPacket(ByteBuffer packet) {
        // Simple domain extraction for testing
        try {
            byte[] data = packet.array();
            int dnsStart = 40; // Skip IP(20) + UDP(8) + DNS header(12)
            
            StringBuilder domain = new StringBuilder();
            int pos = dnsStart;
            
            while (pos < data.length && data[pos] != 0) {
                int length = data[pos] & 0xFF;
                if (length == 0) break;
                
                if (domain.length() > 0) domain.append('.');
                
                pos++;
                for (int i = 0; i < length && pos < data.length; i++) {
                    domain.append((char) data[pos++]);
                }
            }
            
            return domain.toString();
        } catch (Exception e) {
            return null;
        }
    }
    
    private byte[] createRealDnsQuery(String domain) throws Exception {
        // Create a proper DNS query using the system
        // This is a simplified version
        return createMockDnsQuery(domain);
    }
    
    private byte[] forwardDnsQueryDirectly(byte[] dnsQuery) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(3000);
            
            // Extract just the DNS payload (skip IP and UDP headers)
            byte[] dnsPayload = new byte[dnsQuery.length - 28]; // Skip IP(20) + UDP(8)
            System.arraycopy(dnsQuery, 28, dnsPayload, 0, dnsPayload.length);
            
            InetAddress dnsServer = InetAddress.getByName("8.8.8.8");
            DatagramPacket query = new DatagramPacket(dnsPayload, dnsPayload.length, dnsServer, 53);
            socket.send(query);
            
            byte[] responseBuffer = new byte[1024];
            DatagramPacket response = new DatagramPacket(responseBuffer, responseBuffer.length);
            socket.receive(response);
            
            byte[] result = new byte[response.getLength()];
            System.arraycopy(response.getData(), 0, result, 0, response.getLength());
            return result;
            
        } catch (Exception e) {
            Log.e(TAG, "Direct DNS forwarding failed", e);
            return null;
        }
    }
    
    private boolean isValidDnsResponse(byte[] response) {
        if (response.length < 12) return false; // Minimum DNS header size
        
        // Check if it looks like a valid DNS response
        // Transaction ID should be present
        // QR bit should be set (response)
        int flags = ((response[2] & 0xFF) << 8) | (response[3] & 0xFF);
        boolean isResponse = (flags & 0x8000) != 0;
        
        return isResponse;
    }
    
    /**
     * Test specific scenarios that might be causing issues
     */
    public void testSpecificIssueScenarios() {
        Log.i(TAG, "================ SPECIFIC ISSUE SCENARIOS ================");
        
        // Scenario 1: Check if VPN is properly intercepting traffic
        testTrafficInterception();
        
        // Scenario 2: Check if DNS responses are being corrupted
        testDnsResponseIntegrity();
        
        // Scenario 3: Check if there are timing issues
        testTimingIssues();
        
        // Scenario 4: Check if there are threading/concurrency issues
        testConcurrencyIssues();
    }
    
    private void testTrafficInterception() {
        Log.i(TAG, "SCENARIO 1: Traffic Interception");
        Log.i(TAG, "  Manual test required: Check if VPN is receiving all traffic");
        Log.i(TAG, "  Expected: All DNS queries should appear in VPN logs");
        Log.i(TAG, "  Action: Try accessing chatgpt.com and check logs for DNS query");
    }
    
    private void testDnsResponseIntegrity() {
        Log.i(TAG, "SCENARIO 2: DNS Response Integrity");
        Log.i(TAG, "  Testing if DNS responses are being corrupted during forwarding...");
        
        // This would involve comparing original vs forwarded responses
        // Implementation would require packet capture capabilities
    }
    
    private void testTimingIssues() {
        Log.i(TAG, "SCENARIO 3: Timing Issues");
        Log.i(TAG, "  Testing for race conditions and timeouts...");
        
        // Test multiple concurrent DNS requests
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (int i = 0; i < 5; i++) {
            final int requestId = i;
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    long start = System.currentTimeMillis();
                    InetAddress.getByName("google.com");
                    long duration = System.currentTimeMillis() - start;
                    Log.i(TAG, "    Concurrent request " + requestId + " completed in " + duration + "ms");
                } catch (Exception e) {
                    Log.e(TAG, "    Concurrent request " + requestId + " failed", e);
                }
            }));
        }
        
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(10, TimeUnit.SECONDS);
            Log.i(TAG, "  ✓ Concurrent DNS requests completed successfully");
        } catch (Exception e) {
            Log.e(TAG, "  ✗ Concurrent DNS requests failed", e);
        }
    }
    
    private void testConcurrencyIssues() {
        Log.i(TAG, "SCENARIO 4: Concurrency Issues");
        Log.i(TAG, "  Check logs for thread safety issues in VPN service");
        Log.i(TAG, "  Look for: Race conditions, deadlocks, resource contention");
    }
}
