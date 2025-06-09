package com.example.parentalcontrol;

import android.util.Log;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Comprehensive VPN debugging and auditing system to identify why websites fail to load
 * despite DNS processing appearing to work correctly.
 * 
 * This auditor tracks packet flow, validates responses, and identifies bottlenecks.
 */
public class VpnDebugAuditor {
    private static final String TAG = "VpnDebugAuditor";
    
    // Packet flow tracking
    private static final AtomicLong totalPacketsReceived = new AtomicLong(0);
    private static final AtomicLong dnsPacketsProcessed = new AtomicLong(0);
    private static final AtomicLong nonDnsPacketsForwarded = new AtomicLong(0);
    private static final AtomicLong dnsResponsesSent = new AtomicLong(0);
    private static final AtomicLong blockedDnsQueries = new AtomicLong(0);
    private static final AtomicLong forwardedDnsQueries = new AtomicLong(0);
    private static final AtomicLong failedDnsForwards = new AtomicLong(0);
    
    // Domain tracking
    private static final Map<String, Long> domainQueryCount = new HashMap<>();
    private static final Map<String, Long> domainBlockCount = new HashMap<>();
    private static final Map<String, Long> domainForwardCount = new HashMap<>();
    
    // Performance tracking
    private static final Map<String, Long> dnsResponseTimes = new HashMap<>();
    private static long lastStatsReport = 0;
    private static final long STATS_REPORT_INTERVAL = 30000; // 30 seconds
    
    /**
     * AUDIT POINT 1: Packet Reception Validation
     * Verify that packets are being received correctly from the VPN interface
     */
    public static void auditPacketReception(ByteBuffer packet, int length) {
        totalPacketsReceived.incrementAndGet();
        
        if (length <= 0) {
            Log.w(TAG, "[AUDIT-1] ISSUE: Received packet with invalid length: " + length);
            return;
        }
        
        if (packet == null || packet.remaining() < 20) {
            Log.w(TAG, "[AUDIT-1] ISSUE: Received malformed packet, remaining bytes: " + 
                  (packet != null ? packet.remaining() : "null"));
            return;
        }
        
        // Validate IP header basics
        byte[] data = packet.array();
        int offset = packet.position();
        int version = (data[offset] >> 4) & 0x0F;
        int ihl = (data[offset] & 0x0F) * 4;
        
        if (version != 4) {
            Log.w(TAG, "[AUDIT-1] ISSUE: Non-IPv4 packet received, version: " + version);
            return;
        }
        
        if (ihl < 20 || offset + ihl > packet.limit()) {
            Log.w(TAG, "[AUDIT-1] ISSUE: Invalid IP header length: " + ihl + 
                  ", offset: " + offset + ", limit: " + packet.limit());
            return;
        }
        
        Log.v(TAG, "[AUDIT-1] Valid packet received: length=" + length + ", version=" + version + ", ihl=" + ihl);
    }
    
    /**
     * AUDIT POINT 2: DNS Packet Identification
     * Verify DNS packet detection logic is working correctly
     */
    public static boolean auditDnsPacketDetection(ByteBuffer packet) {
        try {
            byte[] data = packet.array();
            int offset = packet.position();
            
            // Check if we have enough data for headers
            if (packet.remaining() < 28) { // IP(20) + UDP(8) minimum
                Log.v(TAG, "[AUDIT-2] Packet too small for DNS: " + packet.remaining());
                return false;
            }
            
            int ihl = (data[offset] & 0x0F) * 4;
            int protocol = data[offset + 9] & 0xFF;
            
            if (protocol != 17) { // Not UDP
                Log.v(TAG, "[AUDIT-2] Non-UDP packet, protocol: " + protocol);
                return false;
            }
            
            if (offset + ihl + 8 > packet.limit()) {
                Log.w(TAG, "[AUDIT-2] ISSUE: Invalid header sizes for UDP packet");
                return false;
            }
            
            // Check destination port (should be 53 for DNS)
            int destPort = ((data[offset + ihl + 2] & 0xFF) << 8) | (data[offset + ihl + 3] & 0xFF);
            
            if (destPort == 53) {
                dnsPacketsProcessed.incrementAndGet();
                Log.d(TAG, "[AUDIT-2] DNS packet detected: destPort=53");
                return true;
            } else {
                nonDnsPacketsForwarded.incrementAndGet();
                Log.v(TAG, "[AUDIT-2] Non-DNS UDP packet: destPort=" + destPort);
                return false;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "[AUDIT-2] ISSUE: Exception in DNS detection", e);
            return false;
        }
    }
    
    /**
     * AUDIT POINT 3: Domain Extraction Validation
     * Verify domain names are being extracted correctly from DNS packets
     */
    public static void auditDomainExtraction(ByteBuffer packet, String extractedDomain) {
        try {
            // Validate the extracted domain makes sense
            if (extractedDomain == null || extractedDomain.isEmpty()) {
                Log.w(TAG, "[AUDIT-3] ISSUE: Domain extraction failed - null or empty domain");
                return;
            }
            
            if (extractedDomain.length() > 253) {
                Log.w(TAG, "[AUDIT-3] ISSUE: Domain too long: " + extractedDomain.length() + " chars");
                return;
            }
            
            if (extractedDomain.contains(" ") || extractedDomain.contains("\n")) {
                Log.w(TAG, "[AUDIT-3] ISSUE: Domain contains invalid characters: '" + extractedDomain + "'");
                return;
            }
            
            // Track domain queries
            domainQueryCount.put(extractedDomain, domainQueryCount.getOrDefault(extractedDomain, 0L) + 1);
            
            Log.d(TAG, "[AUDIT-3] Domain extracted successfully: '" + extractedDomain + 
                  "' (query #" + domainQueryCount.get(extractedDomain) + ")");
            
        } catch (Exception e) {
            Log.e(TAG, "[AUDIT-3] ISSUE: Exception in domain extraction audit", e);
        }
    }
    
    /**
     * AUDIT POINT 4: DNS Filtering Decision Validation
     * Track and validate filtering decisions for domains
     */
    public static void auditFilteringDecision(String domain, boolean isBlocked, String reason) {
        if (isBlocked) {
            blockedDnsQueries.incrementAndGet();
            domainBlockCount.put(domain, domainBlockCount.getOrDefault(domain, 0L) + 1);
            Log.i(TAG, "[AUDIT-4] BLOCKED: '" + domain + "' - Reason: " + reason + 
                  " (blocked " + domainBlockCount.get(domain) + " times)");
        } else {
            forwardedDnsQueries.incrementAndGet();
            domainForwardCount.put(domain, domainForwardCount.getOrDefault(domain, 0L) + 1);
            Log.i(TAG, "[AUDIT-4] FORWARDED: '" + domain + "' - Reason: " + reason + 
                  " (forwarded " + domainForwardCount.get(domain) + " times)");
        }
    }
    
    /**
     * AUDIT POINT 5: DNS Response Validation
     * Validate the structure and content of DNS responses
     */
    public static void auditDnsResponse(String domain, byte[] responsePacket, boolean isBlocked) {
        if (responsePacket == null) {
            Log.e(TAG, "[AUDIT-5] CRITICAL ISSUE: Null response packet for domain: " + domain);
            failedDnsForwards.incrementAndGet();
            return;
        }
        
        if (responsePacket.length < 28) { // Minimum packet size
            Log.e(TAG, "[AUDIT-5] CRITICAL ISSUE: Response packet too small: " + responsePacket.length + 
                  " bytes for domain: " + domain);
            failedDnsForwards.incrementAndGet();
            return;
        }
        
        try {
            // Validate IP header of response
            int version = (responsePacket[0] >> 4) & 0x0F;
            int ihl = (responsePacket[0] & 0x0F) * 4;
            int totalLength = ((responsePacket[2] & 0xFF) << 8) | (responsePacket[3] & 0xFF);
            
            if (version != 4) {
                Log.e(TAG, "[AUDIT-5] ISSUE: Invalid IP version in response: " + version);
                return;
            }
            
            if (totalLength != responsePacket.length) {
                Log.w(TAG, "[AUDIT-5] WARNING: IP total length mismatch: header=" + totalLength + 
                      ", actual=" + responsePacket.length);
            }
            
            // Validate UDP header
            int srcPort = ((responsePacket[ihl] & 0xFF) << 8) | (responsePacket[ihl + 1] & 0xFF);
            int destPort = ((responsePacket[ihl + 2] & 0xFF) << 8) | (responsePacket[ihl + 3] & 0xFF);
            int udpLength = ((responsePacket[ihl + 4] & 0xFF) << 8) | (responsePacket[ihl + 5] & 0xFF);
            
            if (isBlocked) {
                Log.d(TAG, "[AUDIT-5] Blocked response for '" + domain + "': srcPort=" + srcPort + 
                      ", destPort=" + destPort + ", udpLen=" + udpLength);
            } else {
                Log.d(TAG, "[AUDIT-5] Forwarded response for '" + domain + "': srcPort=" + srcPort + 
                      ", destPort=" + destPort + ", udpLen=" + udpLength);
            }
            
            dnsResponsesSent.incrementAndGet();
            
        } catch (Exception e) {
            Log.e(TAG, "[AUDIT-5] ISSUE: Exception validating DNS response for " + domain, e);
            failedDnsForwards.incrementAndGet();
        }
    }
    
    /**
     * AUDIT POINT 6: Non-DNS Packet Handling
     * Track how non-DNS packets are being handled
     */
    public static void auditNonDnsPacket(ByteBuffer packet) {
        try {
            byte[] data = packet.array();
            int offset = packet.position();
            
            if (packet.remaining() < 20) {
                Log.v(TAG, "[AUDIT-6] Non-DNS packet too small: " + packet.remaining());
                return;
            }
            
            int protocol = data[offset + 9] & 0xFF;
            String protocolName = getProtocolName(protocol);
            
            if (protocol == 6) { // TCP
                int ihl = (data[offset] & 0x0F) * 4;
                if (offset + ihl + 4 <= packet.limit()) {
                    int destPort = ((data[offset + ihl + 2] & 0xFF) << 8) | (data[offset + ihl + 3] & 0xFF);
                    Log.v(TAG, "[AUDIT-6] Non-DNS TCP packet: destPort=" + destPort + ", size=" + packet.remaining());
                    
                    // Check for HTTP/HTTPS traffic that might be failing
                    if (destPort == 80 || destPort == 443) {
                        Log.i(TAG, "[AUDIT-6] HTTP/HTTPS traffic detected: port=" + destPort + 
                              ", this should pass through normally");
                    }
                }
            } else {
                Log.v(TAG, "[AUDIT-6] Non-DNS packet: protocol=" + protocolName + 
                      " (" + protocol + "), size=" + packet.remaining());
            }
            
        } catch (Exception e) {
            Log.e(TAG, "[AUDIT-6] ISSUE: Exception auditing non-DNS packet", e);
        }
    }
    
    /**
     * AUDIT POINT 7: Performance and Timing Analysis
     */
    public static void auditDnsForwardingPerformance(String domain, long startTime, boolean success) {
        long duration = System.currentTimeMillis() - startTime;
        
        if (success) {
            dnsResponseTimes.put(domain, duration);
            Log.d(TAG, "[AUDIT-7] DNS forwarding completed for '" + domain + "' in " + duration + "ms");
            
            if (duration > 5000) {
                Log.w(TAG, "[AUDIT-7] WARNING: Slow DNS response for '" + domain + "': " + duration + "ms");
            }
        } else {
            Log.e(TAG, "[AUDIT-7] CRITICAL: DNS forwarding FAILED for '" + domain + "' after " + duration + "ms");
        }
    }
    
    /**
     * Generate comprehensive audit report
     */
    public static void generateAuditReport() {
        long currentTime = System.currentTimeMillis();
        
        if (currentTime - lastStatsReport < STATS_REPORT_INTERVAL) {
            return; // Too soon for next report
        }
        
        lastStatsReport = currentTime;
        
        Log.i(TAG, "=============== VPN DEBUG AUDIT REPORT ===============");
        Log.i(TAG, "Total packets received: " + totalPacketsReceived.get());
        Log.i(TAG, "DNS packets processed: " + dnsPacketsProcessed.get());
        Log.i(TAG, "Non-DNS packets forwarded: " + nonDnsPacketsForwarded.get());
        Log.i(TAG, "DNS responses sent: " + dnsResponsesSent.get());
        Log.i(TAG, "DNS queries blocked: " + blockedDnsQueries.get());
        Log.i(TAG, "DNS queries forwarded: " + forwardedDnsQueries.get());
        Log.i(TAG, "Failed DNS forwards: " + failedDnsForwards.get());
        
        // Calculate success rate
        long totalDnsQueries = blockedDnsQueries.get() + forwardedDnsQueries.get();
        if (totalDnsQueries > 0) {
            double successRate = (double) dnsResponsesSent.get() / totalDnsQueries * 100;
            Log.i(TAG, "DNS success rate: " + String.format("%.1f%%", successRate));
        }
        
        // Top queried domains
        Log.i(TAG, "--- Top Queried Domains ---");
        domainQueryCount.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(10)
            .forEach(entry -> Log.i(TAG, "  " + entry.getKey() + ": " + entry.getValue() + " queries"));
        
        // Top blocked domains
        if (!domainBlockCount.isEmpty()) {
            Log.i(TAG, "--- Top Blocked Domains ---");
            domainBlockCount.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .forEach(entry -> Log.i(TAG, "  " + entry.getKey() + ": " + entry.getValue() + " blocks"));
        }
        
        Log.i(TAG, "====================================================");
    }
    
    /**
     * Critical issue detection - call this when websites fail to load
     */
    public static void detectCriticalIssues() {
        Log.w(TAG, "============ CRITICAL ISSUE ANALYSIS ============");
        
        // Check if DNS queries are being processed
        if (dnsPacketsProcessed.get() == 0) {
            Log.e(TAG, "CRITICAL: No DNS packets detected - VPN may not be intercepting traffic");
        }
        
        // Check if responses are being sent
        long totalQueries = blockedDnsQueries.get() + forwardedDnsQueries.get();
        if (totalQueries > 0 && dnsResponsesSent.get() == 0) {
            Log.e(TAG, "CRITICAL: DNS queries processed but no responses sent - response generation failing");
        }
        
        // Check failure rate
        if (failedDnsForwards.get() > forwardedDnsQueries.get() / 2) {
            Log.e(TAG, "CRITICAL: High DNS forwarding failure rate - external DNS unreachable or packet corruption");
        }
        
        // Check for packet loss
        if (totalPacketsReceived.get() > 0 && dnsPacketsProcessed.get() == 0) {
            Log.e(TAG, "CRITICAL: Packets received but no DNS detected - packet parsing may be broken");
        }
        
        Log.w(TAG, "===============================================");
    }
    
    private static String getProtocolName(int protocol) {
        switch (protocol) {
            case 1: return "ICMP";
            case 6: return "TCP";
            case 17: return "UDP";
            default: return "Unknown";
        }
    }
}
