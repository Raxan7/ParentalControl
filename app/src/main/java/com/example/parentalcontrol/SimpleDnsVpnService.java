package com.example.parentalcontrol;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Simplified VPN Service that focuses specifically on DNS filtering
 * This approach is more reliable than complex packet inspection
 */
public class SimpleDnsVpnService extends VpnService {
    private static final String TAG = "SimpleDnsVPN";
    private static final String CHANNEL_ID = "simple_dns_vpn_channel";
    private static final int NOTIFICATION_ID = 5004;
    
    private ParcelFileDescriptor vpnInterface;
    private ExecutorService executorService;
    private boolean isRunning = false;
    private ContentFilterEngine filterEngine;
    private LocalWebServer localWebServer;
    private VpnDiagnostics diagnostics;
    
    // Diagnostic control
    private static final long DIAGNOSTIC_INTERVAL = 60000; // Run diagnostics every 60 seconds
    private long lastDiagnosticRun = 0;
    private int consecutiveFailures = 0;
    
    // Redirect control - prevent infinite loops and unnecessary redirects
    private static final long REDIRECT_COOLDOWN = 5000; // 5 seconds cooldown between redirects
    private long lastRedirectTime = 0;
    private String lastRedirectedDomain = null;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "SimpleDnsVpnService created");
        createNotificationChannel();
        
        // Log Django server configuration
        Log.i(TAG, DjangoServerConfig.getConfigurationInfo());
        if (!DjangoServerConfig.isConfigurationValid()) {
            Log.e(TAG, "⚠️ Django server configuration is invalid!");
        }
        
        filterEngine = new ContentFilterEngine(this);
        localWebServer = new LocalWebServer(this);
        diagnostics = new VpnDiagnostics(this);
        executorService = Executors.newFixedThreadPool(2);
        
        // Run initial diagnostics
        runInitialDiagnostics();
        
        // Run focused issue identification
        runIssueIdentification();
        
        // Start local web server for blocked content warnings
        startLocalWebServer();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Starting Simple DNS VPN service");
        
        if (intent != null && "STOP_VPN".equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }
        
        startVpn();
        return START_STICKY;
    }
    
    private void startLocalWebServer() {
        try {
            localWebServer.start();
            Log.i(TAG, "Local web server started on port 8080");
        } catch (IOException e) {
            Log.e(TAG, "Failed to start local web server", e);
        }
    }
    
    private void startVpn() {
        try {
            Log.d(TAG, "[startVpn] Establishing VPN interface for DNS filtering...");
            Builder builder = new Builder();
            builder.setMtu(1500)
                   .addAddress("10.0.0.1", 24)
                   // CRITICAL FIX: Only route DNS traffic through VPN instead of all traffic
                   // This resolves the connectivity issue where DNS works but websites don't load
                   .addRoute("8.8.8.8", 32)      // Google DNS primary
                   .addRoute("8.8.4.4", 32)      // Google DNS secondary  
                   .addRoute("1.1.1.1", 32)      // Cloudflare DNS primary
                   .addRoute("1.0.0.1", 32)      // Cloudflare DNS secondary
                   .addDnsServer("8.8.8.8")
                   .setSession("Parental Control DNS Filter");
            try {
                builder.addDisallowedApplication(getPackageName());
            } catch (Exception e) {
                Log.w(TAG, "[startVpn] Could not exclude own package", e);
            }
            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                Log.e(TAG, "[startVpn] Failed to establish VPN interface");
                return;
            }
            Log.i(TAG, "[startVpn] VPN interface established - DNS filtering active");
            isRunning = true;
            startForeground(NOTIFICATION_ID, createNotification());
            startDnsServer();
        } catch (SecurityException e) {
            Log.e(TAG, "[startVpn] VPN permission not granted", e);
            stopVpn();
        } catch (Exception e) {
            Log.e(TAG, "[startVpn] Error starting VPN", e);
            stopVpn();
        }
    }
    
    private void startDnsServer() {
        executorService.submit(() -> {
            try {
                Log.d(TAG, "[startDnsServer] DNS server thread started");
                FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
                FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());
                ByteBuffer packet = ByteBuffer.allocate(32767);
                Log.i(TAG, "[startDnsServer] DNS server started - filtering queries...");
                while (isRunning && !Thread.currentThread().isInterrupted()) {
                    try {
                        int length = in.read(packet.array());
                        if (length > 0) {
                            packet.clear();
                            packet.limit(length);
                            
                            // AUDIT POINT 1: Validate packet reception
                            VpnDebugAuditor.auditPacketReception(packet, length);
                            
                            Log.v(TAG, "[startDnsServer] Packet received, length: " + length);
                            
                            // AUDIT POINT 2: Validate DNS packet detection
                            boolean isDns = VpnDebugAuditor.auditDnsPacketDetection(packet);
                            
                            if (isDns) {
                                Log.d(TAG, "[startDnsServer] DNS packet detected");
                                handleDnsQuery(packet, out);
                            } else {
                                // With DNS-only routing, non-DNS packets shouldn't reach here
                                // If they do, it indicates a configuration issue
                                Log.w(TAG, "[startDnsServer] Unexpected non-DNS packet received (routing issue?)");
                                // AUDIT POINT 6: Track non-DNS packet handling
                                VpnDebugAuditor.auditNonDnsPacket(packet);
                                // Don't forward - let it use normal routing
                            }
                            packet.clear();
                        }
                        
                        // Generate periodic audit reports and run diagnostics
                        VpnDebugAuditor.generateAuditReport();
                        runPeriodicDiagnostics();
                        
                        Thread.sleep(1);
                    } catch (IOException e) {
                        if (isRunning) {
                            Log.e(TAG, "[startDnsServer] Error in DNS server loop", e);
                            VpnDebugAuditor.detectCriticalIssues();
                        }
                        break;
                    } catch (InterruptedException e) {
                        Log.d(TAG, "[startDnsServer] DNS server thread interrupted");
                        break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "[startDnsServer] DNS server error", e);
            }
            Log.d(TAG, "[startDnsServer] DNS server stopped");
        });
    }
    
    private boolean isDnsPacket(ByteBuffer packet) {
        try {
            if (packet.remaining() < 28) return false; // Min IP + UDP header size
            
            byte[] data = packet.array();
            int offset = packet.position();
            
            // Check if UDP (protocol 17)
            int protocol = data[offset + 9] & 0xFF;
            if (protocol != 17) return false;
            
            // Get IP header length
            int ihl = (data[offset] & 0x0F) * 4;
            
            // Check destination port (53 = DNS)
            int destPort = ((data[offset + ihl + 2] & 0xFF) << 8) | 
                          (data[offset + ihl + 3] & 0xFF);
            
            return destPort == 53;
            
        } catch (Exception e) {
            return false;
        }
    }
    
    private void handleDnsQuery(ByteBuffer packet, FileOutputStream out) {
        try {
            String domain = extractDomainFromDnsPacket(packet);
            
            // AUDIT POINT 3: Validate domain extraction
            VpnDebugAuditor.auditDomainExtraction(packet, domain);
            
            Log.d(TAG, "[handleDnsQuery] DNS query for: " + domain);
            if (domain != null) {
                // CRITICAL FIX: Prevent infinite redirect loop - never redirect Django server itself
                if (DjangoServerConfig.isDjangoServerDomain(domain)) {
                    Log.d(TAG, "[handleDnsQuery] ✅ Allowing Django server - no redirect needed: " + domain);
                    // Forward Django server queries normally - no blocking
                    long startTime = System.currentTimeMillis();
                    byte[] dnsResponse = forwardDnsQueryAndGetResponse(packet);
                    
                    VpnDebugAuditor.auditDnsForwardingPerformance(domain, startTime, dnsResponse != null);
                    VpnDebugAuditor.auditDnsResponse(domain, dnsResponse, false);
                    
                    if (dnsResponse != null) {
                        out.write(dnsResponse);
                        out.flush();
                        trackSuccess("DNS forward response for Django server: " + domain);
                        Log.d(TAG, "[handleDnsQuery] ✅ Django server DNS response sent: " + domain);
                    }
                    return;
                }
                
                boolean shouldBlock = filterEngine.shouldBlockDomain(domain);
                
                if (shouldBlock) {
                    // CRITICAL FIX: Check if browser is active and apply cooldown
                    if (!shouldPerformRedirect(domain)) {
                        Log.d(TAG, "[handleDnsQuery] 🚫 Skipping redirect (browser inactive or cooldown): " + domain);
                        // Just block without redirect - return NXDOMAIN or no response
                        return;
                    }
                    // AUDIT POINT 4: Track filtering decision
                    VpnDebugAuditor.auditFilteringDecision(domain, true, "Domain blocked by filter engine");
                    
                    // Enhanced logging for debugging
                    Log.i(TAG, "🚫🔄 [IMMEDIATE_REDIRECT] Blocking and redirecting DNS query for: " + domain);
                    Log.d(TAG, "[IMMEDIATE_REDIRECT] Creating DNS response to redirect " + domain + " → Django blocked page");
                    
                    // Create DNS response that points to Django server IP
                    byte[] blockedResponse = createDjangoRedirectDnsResponse(packet, domain);
                    
                    // AUDIT POINT 5: Validate blocked response
                    VpnDebugAuditor.auditDnsResponse(domain, blockedResponse, true);
                    
                    if (blockedResponse != null) {
                        // Send DNS response first
                        out.write(blockedResponse);
                        out.flush();
                        
                        // Log the successful DNS redirect
                        Log.i(TAG, "✅ [IMMEDIATE_REDIRECT] DNS response sent: " + domain + " → Django server (" + DjangoServerConfig.DJANGO_BASE_URL + ")");
                        
                        // Also trigger immediate browser redirect as backup
                        triggerImmediateBrowserRedirect(domain);
                        
                        // Show notification with redirect info
                        showRedirectNotification(domain);
                        
                        trackSuccess("DNS redirect response for " + domain + " → Django blocked page");
                        Log.d(TAG, "[IMMEDIATE_REDIRECT] ✅ Complete redirect chain executed for: " + domain);
                        return;
                    } else {
                        trackFailure("Failed to create redirect DNS response for " + domain);
                        Log.e(TAG, "[IMMEDIATE_REDIRECT] ❌ Failed to create redirect DNS response for: " + domain);
                    }
                }
            }
            // Forward legitimate queries to real DNS server synchronously
            // AUDIT POINT 4: Track filtering decision for allowed domains
            VpnDebugAuditor.auditFilteringDecision(domain, false, "Domain allowed by filter engine");
            
            Log.d(TAG, "[handleDnsQuery] Forwarding DNS query for: " + domain);
            long startTime = System.currentTimeMillis();
            byte[] dnsResponse = forwardDnsQueryAndGetResponse(packet);
            
            // AUDIT POINT 7: Track performance
            VpnDebugAuditor.auditDnsForwardingPerformance(domain, startTime, dnsResponse != null);
            
            // AUDIT POINT 5: Validate forwarded response
            VpnDebugAuditor.auditDnsResponse(domain, dnsResponse, false);
            
            if (dnsResponse != null) {
                out.write(dnsResponse);
                out.flush();
                trackSuccess("DNS forward response for " + domain);
                Log.d(TAG, "[handleDnsQuery] Forwarded DNS response sent for: " + domain);
            } else {
                trackFailure("Failed to forward DNS query for " + domain);
                Log.e(TAG, "[handleDnsQuery] Failed to forward DNS query for: " + domain);
            }
        } catch (Exception e) {
            Log.e(TAG, "[handleDnsQuery] Error handling DNS query", e);
        }
    }

    // Synchronously forward DNS query and return the full response packet
    private byte[] forwardDnsQueryAndGetResponse(ByteBuffer packet) {
        try {
            byte[] data = packet.array();
            int offset = packet.position();
            int ihl = (data[offset] & 0x0F) * 4;
            int dnsStart = offset + ihl + 8;
            int dnsLength = packet.remaining() - ihl - 8;
            if (dnsLength <= 0) {
                Log.e(TAG, "[forwardDnsQueryAndGetResponse] Invalid DNS length");
                return null;
            }
            byte[] dnsPayload = new byte[dnsLength];
            System.arraycopy(data, dnsStart, dnsPayload, 0, dnsLength);
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(3000);
                InetAddress dnsServer = InetAddress.getByName("8.8.8.8");
                DatagramPacket query = new DatagramPacket(dnsPayload, dnsPayload.length, dnsServer, 53);
                socket.send(query);
                Log.d(TAG, "[forwardDnsQueryAndGetResponse] DNS query sent to 8.8.8.8");
                byte[] responseBuffer = new byte[1024];
                DatagramPacket response = new DatagramPacket(responseBuffer, responseBuffer.length);
                socket.receive(response);
                Log.d(TAG, "[forwardDnsQueryAndGetResponse] DNS response received from 8.8.8.8, length: " + response.getLength());
                return reconstructIpPacket(packet, response.getData(), response.getLength());
            }
        } catch (Exception e) {
            Log.e(TAG, "[forwardDnsQueryAndGetResponse] Error forwarding DNS query synchronously", e);
            return null;
        }
    }
    
    private String extractDomainFromDnsPacket(ByteBuffer packet) {
        try {
            byte[] data = packet.array();
            int offset = packet.position();
            
            // Skip IP header
            int ihl = (data[offset] & 0x0F) * 4;
            
            // Skip UDP header (8 bytes) and DNS header (12 bytes)
            int dnsQuestionStart = offset + ihl + 8 + 12;
            
            if (dnsQuestionStart >= packet.limit()) return null;
            
            // Parse DNS question (domain name)
            StringBuilder domain = new StringBuilder();
            int pos = dnsQuestionStart;
            
            while (pos < packet.limit()) {
                int labelLen = data[pos] & 0xFF;
                if (labelLen == 0) break; // End of domain name
                
                if (labelLen > 63) break; // Invalid label length
                
                pos++;
                if (pos + labelLen > packet.limit()) break;
                
                if (domain.length() > 0) {
                    domain.append(".");
                }
                
                for (int i = 0; i < labelLen && pos < packet.limit(); i++) {
                    domain.append((char) (data[pos++] & 0xFF));
                }
            }
            
            String result = domain.toString().toLowerCase();
            return result.isEmpty() ? null : result;
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting domain from DNS packet", e);
            return null;
        }
    }
    
    /**
     * Create DNS response that redirects blocked domain to Django blocked page
     * This makes the browser immediately navigate to the Django server instead of showing an error
     */
    private byte[] createDjangoRedirectDnsResponse(ByteBuffer originalPacket, String domain) {
        Log.d(TAG, "[createDjangoRedirectDnsResponse] Creating redirect response: " + domain + " → Django server (127.0.0.1)");
        try {
            byte[] original = originalPacket.array();
            int offset = originalPacket.position();
            int length = originalPacket.remaining();
            
            // Create response packet (copy original and modify)
            byte[] response = new byte[length + 16]; // Add space for answer
            System.arraycopy(original, offset, response, 0, length);
            
            // Get IP header length
            int ihl = (response[0] & 0x0F) * 4;
            
            // Modify DNS header to make it a response
            int dnsHeaderStart = ihl + 8;
            
            // Set QR bit (response), no error
            response[dnsHeaderStart + 2] = (byte) 0x81; // QR=1, Opcode=0, AA=0, TC=0, RD=1
            response[dnsHeaderStart + 3] = (byte) 0x80; // RA=1, Z=0, RCODE=0
            
            // Set answer count to 1
            response[dnsHeaderStart + 6] = 0;
            response[dnsHeaderStart + 7] = 1;
            
            // Add answer section pointing to Google.com IP address
            int answerStart = length;
            
            // Name pointer (points to question)
            response[answerStart] = (byte) 0xC0;
            response[answerStart + 1] = (byte) (dnsHeaderStart + 12);
            
            // Type A (0x0001)
            response[answerStart + 2] = 0;
            response[answerStart + 3] = 1;
            
            // Class IN (0x0001)
            response[answerStart + 4] = 0;
            response[answerStart + 5] = 1;
            
            // TTL (60 seconds - short TTL for immediate effect)
            response[answerStart + 6] = 0;
            response[answerStart + 7] = 0;
            response[answerStart + 8] = 0;
            response[answerStart + 9] = 60;
            
            // Data length (4 bytes for IPv4)
            response[answerStart + 10] = 0;
            response[answerStart + 11] = 4;
            
            // CRITICAL: Django server IP address - this forces redirect to local Django blocked page
            // This will cause browser to navigate to the Django server's /blocked/ page
            byte[] djangoIP = DjangoServerConfig.getDjangoServerIPBytes();
            response[answerStart + 12] = djangoIP[0];
            response[answerStart + 13] = djangoIP[1];
            response[answerStart + 14] = djangoIP[2];
            response[answerStart + 15] = djangoIP[3];
            
            Log.d(TAG, "[createDjangoRedirectDnsResponse] ✅ DNS response created with Django server IP: " + DjangoServerConfig.DJANGO_DOMAIN);
            
            // Update IP packet length
            int newLength = length + 16;
            response[2] = (byte) ((newLength >> 8) & 0xFF);
            response[3] = (byte) (newLength & 0xFF);
            
            // Update UDP length
            int udpLength = newLength - ihl;
            response[ihl + 4] = (byte) ((udpLength >> 8) & 0xFF);
            response[ihl + 5] = (byte) (udpLength & 0xFF);
            
            // Swap source and destination addresses
            for (int i = 0; i < 4; i++) {
                byte temp = response[12 + i];
                response[12 + i] = response[16 + i];
                response[16 + i] = temp;
            }
            
            // Swap source and destination ports
            byte temp1 = response[ihl];
            byte temp2 = response[ihl + 1];
            response[ihl] = response[ihl + 2];
            response[ihl + 1] = response[ihl + 3];
            response[ihl + 2] = temp1;
            response[ihl + 3] = temp2;
            
            return response;
            
        } catch (Exception e) {
            Log.e(TAG, "Error creating blocked DNS response", e);
            return null;
        }
    }
    
    private void forwardDnsQuery(ByteBuffer packet, FileOutputStream out) {
        try {
            // Extract DNS payload
            byte[] data = packet.array();
            int offset = packet.position();
            int ihl = (data[offset] & 0x0F) * 4;
            
            // DNS payload starts after IP and UDP headers
            int dnsStart = offset + ihl + 8;
            int dnsLength = packet.remaining() - ihl - 8;
            
            if (dnsLength <= 0) return;
            
            byte[] dnsPayload = new byte[dnsLength];
            System.arraycopy(data, dnsStart, dnsPayload, 0, dnsLength);
            
            // Forward to Google DNS
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(3000);
                
                InetAddress dnsServer = InetAddress.getByName("8.8.8.8");
                DatagramPacket query = new DatagramPacket(dnsPayload, dnsPayload.length, dnsServer, 53);
                socket.send(query);
                
                // Receive response
                byte[] responseBuffer = new byte[1024];
                DatagramPacket response = new DatagramPacket(responseBuffer, responseBuffer.length);
                socket.receive(response);
                
                // Create full IP response packet
                byte[] fullResponse = reconstructIpPacket(packet, response.getData(), response.getLength());
                if (fullResponse != null) {
                    out.write(fullResponse);
                    out.flush();
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error forwarding DNS query", e);
        }
    }
    
    private byte[] reconstructIpPacket(ByteBuffer originalPacket, byte[] dnsResponse, int dnsLength) {
        try {
            Log.d(TAG, "[reconstructIpPacket] Reconstructing IP packet for DNS response, dnsLength: " + dnsLength);
            byte[] original = originalPacket.array();
            int offset = originalPacket.position();
            int ihl = (original[offset] & 0x0F) * 4;

            // Create new packet
            int newPacketLength = ihl + 8 + dnsLength; // IP + UDP + DNS
            byte[] newPacket = new byte[newPacketLength];

            // Copy IP header
            System.arraycopy(original, offset, newPacket, 0, ihl);

            // Copy UDP header
            System.arraycopy(original, offset + ihl, newPacket, ihl, 8);

            // Add DNS response
            System.arraycopy(dnsResponse, 0, newPacket, ihl + 8, dnsLength);

            // Update lengths
            newPacket[2] = (byte) ((newPacketLength >> 8) & 0xFF);
            newPacket[3] = (byte) (newPacketLength & 0xFF);

            int udpLength = 8 + dnsLength;
            newPacket[ihl + 4] = (byte) ((udpLength >> 8) & 0xFF);
            newPacket[ihl + 5] = (byte) (udpLength & 0xFF);

            // Swap source and destination addresses
            for (int i = 0; i < 4; i++) {
                byte temp = newPacket[12 + i];
                newPacket[12 + i] = newPacket[16 + i];
                newPacket[16 + i] = temp;
            }

            // Swap source and destination ports
            byte temp1 = newPacket[ihl];
            byte temp2 = newPacket[ihl + 1];
            newPacket[ihl] = newPacket[ihl + 2];
            newPacket[ihl + 1] = newPacket[ihl + 3];
            newPacket[ihl + 2] = temp1;
            newPacket[ihl + 3] = temp2;

            // Zero out old checksums
            newPacket[10] = 0;
            newPacket[11] = 0;
            newPacket[ihl + 6] = 0;
            newPacket[ihl + 7] = 0;

            // Calculate and set new IP header checksum
            int ipChecksum = ipChecksum(newPacket, 0, ihl);
            newPacket[10] = (byte) ((ipChecksum >> 8) & 0xFF);
            newPacket[11] = (byte) (ipChecksum & 0xFF);

            // Calculate and set new UDP checksum
            int udpChecksum = udpChecksum(newPacket, 12, 16, ihl, udpLength);
            newPacket[ihl + 6] = (byte) ((udpChecksum >> 8) & 0xFF);
            newPacket[ihl + 7] = (byte) (udpChecksum & 0xFF);

            return newPacket;

        } catch (Exception e) {
            Log.e(TAG, "[reconstructIpPacket] Error reconstructing IP packet", e);
            return null;
        }
    }

    // Calculate IP header checksum
    private int ipChecksum(byte[] buf, int offset, int length) {
        int sum = 0;
        for (int i = offset; i < offset + length; i += 2) {
            int word = ((buf[i] & 0xFF) << 8) | (buf[i + 1] & 0xFF);
            sum += word;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return ~sum & 0xFFFF;
    }

    // Calculate UDP checksum (with pseudo-header)
    private int udpChecksum(byte[] buf, int srcIpOffset, int dstIpOffset, int udpOffset, int udpLength) {
        int sum = 0;
        // Pseudo-header
        for (int i = 0; i < 4; i += 2) {
            sum += ((buf[srcIpOffset + i] & 0xFF) << 8) | (buf[srcIpOffset + i + 1] & 0xFF);
            sum += ((buf[dstIpOffset + i] & 0xFF) << 8) | (buf[dstIpOffset + i + 1] & 0xFF);
        }
        sum += 0x0011; // Protocol (UDP)
        sum += udpLength;
        // UDP header + data
        for (int i = udpOffset; i < udpOffset + udpLength; i += 2) {
            int word = (buf[i] & 0xFF) << 8;
            if (i + 1 < udpOffset + udpLength) {
                word |= (buf[i + 1] & 0xFF);
            }
            sum += word;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        int result = ~sum & 0xFFFF;
        return result == 0 ? 0xFFFF : result;
    }
    
    /**
     * Show notification about the redirect to Django blocked page
     */
    private void showRedirectNotification(String domain) {
        try {
            NotificationManager notificationManager = 
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            
            if (notificationManager != null) {
                Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("🚫 Content Blocked")
                    .setContentText("Blocked " + domain + " → Redirected to blocked page")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build();
                
                notificationManager.notify((int) System.currentTimeMillis(), notification);
                Log.d(TAG, "[showRedirectNotification] ✅ Notification shown for redirect: " + domain);
            }
        } catch (Exception e) {
            Log.e(TAG, "[showRedirectNotification] Error showing redirect notification", e);
        }
    }
    
    /**
     * Check if we should perform a redirect (browser active + cooldown)
     */
    private boolean shouldPerformRedirect(String domain) {
        long currentTime = System.currentTimeMillis();
        
        // Check cooldown period - but use shorter cooldown if browser detection is uncertain
        long effectiveCooldown = isBrowserDetectionReliable() ? REDIRECT_COOLDOWN : REDIRECT_COOLDOWN / 2;
        
        if (currentTime - lastRedirectTime < effectiveCooldown) {
            Log.d(TAG, "[shouldPerformRedirect] ⏰ Redirect cooldown active (" + effectiveCooldown + "ms), skipping: " + domain);
            return false;
        }
        
        // Check if same domain was just redirected
        if (domain.equals(lastRedirectedDomain) && 
            currentTime - lastRedirectTime < REDIRECT_COOLDOWN * 2) {
            Log.d(TAG, "[shouldPerformRedirect] 🔄 Same domain recently redirected, skipping: " + domain);
            return false;
        }
        
        // Check if browser is active (with fallback for detection issues)
        boolean browserActive = isBrowserActive();
        if (!browserActive) {
            // If browser detection fails but we suspect it might be wrong, allow redirects during peak usage times
            boolean allowFallback = shouldAllowFallbackRedirect();
            if (!allowFallback) {
                Log.d(TAG, "[shouldPerformRedirect] 📱 Browser not active and no fallback conditions met, skipping redirect: " + domain);
                return false;
            } else {
                Log.d(TAG, "[shouldPerformRedirect] 📱 Browser detection uncertain, allowing redirect with fallback: " + domain);
            }
        }
        
        Log.d(TAG, "[shouldPerformRedirect] ✅ Redirect approved for: " + domain + " (browser active: " + browserActive + ")");
        return true;
    }
    
    /**
     * Check if browser detection methods are reliable on this device
     */
    private boolean isBrowserDetectionReliable() {
        // On newer Android versions, detection is less reliable
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q; // Android 10+
    }
    
    /**
     * Fallback conditions when browser detection is uncertain
     */
    private boolean shouldAllowFallbackRedirect() {
        // Allow redirects during common browsing hours (6 AM to 11 PM)
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        int hour = calendar.get(java.util.Calendar.HOUR_OF_DAY);
        boolean isDuringBrowsingHours = hour >= 6 && hour <= 23;
        
        // Allow if it's been a while since last redirect (user might have opened browser)
        long timeSinceLastRedirect = System.currentTimeMillis() - lastRedirectTime;
        boolean hasBeenQuietForAWhile = timeSinceLastRedirect > 30000; // 30 seconds
        
        boolean allowFallback = isDuringBrowsingHours && hasBeenQuietForAWhile;
        
        if (allowFallback) {
            Log.d(TAG, "[shouldAllowFallbackRedirect] Allowing fallback redirect - browsing hours: " + isDuringBrowsingHours + ", quiet period: " + hasBeenQuietForAWhile);
        }
        
        return allowFallback;
    }
    
    /**
     * Check if a browser app is currently in the foreground using multiple detection methods
     */
    private boolean isBrowserActive() {
        // Method 1: Try modern UsageStatsManager (API 21+)
        boolean browserActiveMethod1 = isBrowserActiveViaUsageStats();
        
        // Method 2: Try legacy getRunningTasks (fallback)
        boolean browserActiveMethod2 = isBrowserActiveViaRunningTasks();
        
        // Method 3: Check running processes for browser apps
        boolean browserActiveMethod3 = isBrowserActiveViaRunningProcesses();
        
        // Method 4: Always assume browser is active during redirect window (fallback)
        boolean browserActiveMethod4 = isWithinRecentRedirectWindow();
        
        boolean finalResult = browserActiveMethod1 || browserActiveMethod2 || browserActiveMethod3 || browserActiveMethod4;
        
        Log.d(TAG, String.format("[isBrowserActive] Detection methods - UsageStats: %s, RunningTasks: %s, Processes: %s, RecentWindow: %s → Final: %s", 
                browserActiveMethod1, browserActiveMethod2, browserActiveMethod3, browserActiveMethod4, finalResult));
        
        return finalResult;
    }
    
    /**
     * Method 1: Check browser activity using UsageStatsManager (most reliable on modern Android)
     */
    private boolean isBrowserActiveViaUsageStats() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                return false; // UsageStatsManager not available
            }
            
            // Note: This requires PACKAGE_USAGE_STATS permission which is hard to get
            // For now, we'll implement a simpler approach
            return false;
            
        } catch (Exception e) {
            Log.w(TAG, "[isBrowserActiveViaUsageStats] Error", e);
            return false;
        }
    }
    
    /**
     * Method 2: Legacy method using getRunningTasks (deprecated but sometimes works)
     */
    private boolean isBrowserActiveViaRunningTasks() {
        try {
            ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager == null) return false;
            
            // Get running tasks (deprecated but still available)
            List<ActivityManager.RunningTaskInfo> runningTasks = activityManager.getRunningTasks(1);
            if (runningTasks.isEmpty()) return false;
            
            String topActivity = runningTasks.get(0).topActivity.getPackageName();
            
            // Check if it's a browser app
            boolean isBrowser = isBrowserPackage(topActivity);
            
            Log.d(TAG, "[isBrowserActiveViaRunningTasks] Top app: " + topActivity + ", is browser: " + isBrowser);
            return isBrowser;
            
        } catch (Exception e) {
            Log.w(TAG, "[isBrowserActiveViaRunningTasks] Error", e);
            return false;
        }
    }
    
    /**
     * Method 3: Check running processes for browser applications
     */
    private boolean isBrowserActiveViaRunningProcesses() {
        try {
            ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager == null) return false;
            
            List<ActivityManager.RunningAppProcessInfo> runningProcesses = activityManager.getRunningAppProcesses();
            if (runningProcesses == null) return false;
            
            for (ActivityManager.RunningAppProcessInfo processInfo : runningProcesses) {
                // Check if process is in foreground and is a browser
                if (processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                    processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                    
                    for (String processName : processInfo.pkgList) {
                        if (isBrowserPackage(processName)) {
                            Log.d(TAG, "[isBrowserActiveViaRunningProcesses] Found active browser process: " + processName);
                            return true;
                        }
                    }
                }
            }
            
            return false;
            
        } catch (Exception e) {
            Log.w(TAG, "[isBrowserActiveViaRunningProcesses] Error", e);
            return false;
        }
    }
    
    /**
     * Method 4: Assume browser is active if we're within a recent redirect window
     * This prevents blocking redirects when browser detection fails
     */
    private boolean isWithinRecentRedirectWindow() {
        long currentTime = System.currentTimeMillis();
        long timeSinceLastRedirect = currentTime - lastRedirectTime;
        
        // If a redirect happened in the last 10 seconds, assume browser might still be active
        boolean withinWindow = timeSinceLastRedirect < 10000; // 10 seconds
        
        if (withinWindow) {
            Log.d(TAG, "[isWithinRecentRedirectWindow] Within recent redirect window (" + timeSinceLastRedirect + "ms ago)");
        }
        
        return withinWindow;
    }
    
    /**
     * Check if a package name corresponds to a browser application
     */
    private boolean isBrowserPackage(String packageName) {
        if (packageName == null) return false;
        
        String lowerPackage = packageName.toLowerCase();
        
        // Common browser package patterns
        return lowerPackage.contains("chrome") ||
               lowerPackage.contains("firefox") ||
               lowerPackage.contains("browser") ||
               lowerPackage.contains("opera") ||
               lowerPackage.contains("edge") ||
               lowerPackage.contains("samsung") ||
               lowerPackage.contains("webview") ||
               lowerPackage.contains("brave") ||
               lowerPackage.contains("vivaldi") ||
               lowerPackage.contains("dolphin") ||
               lowerPackage.contains("uc.browser") ||
               lowerPackage.contains("duckduckgo") ||
               // Specific package names
               lowerPackage.equals("com.android.browser") ||
               lowerPackage.equals("com.google.android.apps.chrome") ||
               lowerPackage.equals("org.mozilla.firefox") ||
               lowerPackage.equals("com.opera.browser") ||
               lowerPackage.equals("com.microsoft.emmx") ||
               lowerPackage.equals("com.sec.android.app.sbrowser");
    }

    /**
     * Trigger immediate browser redirect as backup mechanism
     * This ensures that even if DNS redirect doesn't work, browser still goes to Django blocked page
     */
    private void triggerImmediateBrowserRedirect(String domain) {
        try {
            Log.i(TAG, "[triggerImmediateBrowserRedirect] 🚀 Triggering immediate browser redirect for: " + domain);
            
            // Update redirect tracking
            lastRedirectTime = System.currentTimeMillis();
            lastRedirectedDomain = domain;
            
            // Method 1: Direct Intent to Django blocked page
            String djangoBlockedUrl = DjangoServerConfig.BLOCKED_PAGE_URL;
            Intent djangoIntent = new Intent(Intent.ACTION_VIEW);
            djangoIntent.setData(android.net.Uri.parse(djangoBlockedUrl));
            djangoIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            
            try {
                startActivity(djangoIntent);
                Log.i(TAG, "[triggerImmediateBrowserRedirect] ✅ Intent to Django blocked page launched successfully");
            } catch (Exception e) {
                Log.w(TAG, "[triggerImmediateBrowserRedirect] Intent method failed, trying alternative", e);
            }
            
            // Method 2: Start a blocking overlay service as backup
            Intent overlayIntent = new Intent(this, BrowserRedirectService.class);
            overlayIntent.putExtra("blocked_domain", domain);
            overlayIntent.putExtra("redirect_url", djangoBlockedUrl);
            
            try {
                startService(overlayIntent);
                Log.d(TAG, "[triggerImmediateBrowserRedirect] 🎯 Redirect service started as backup");
            } catch (Exception e) {
                Log.w(TAG, "[triggerImmediateBrowserRedirect] Backup service failed", e);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "[triggerImmediateBrowserRedirect] ❌ Error triggering browser redirect", e);
        }
    }
    
    private void showBlockedNotification(String domain) {
        try {
            NotificationManager notificationManager = 
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            
            if (notificationManager != null) {
                Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Content Blocked")
                    .setContentText("Blocked access to: " + domain)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setAutoCancel(true)
                    .build();
                
                notificationManager.notify((int) System.currentTimeMillis(), notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error showing blocked notification", e);
        }
    }
    
    private void stopVpn() {
        Log.d(TAG, "Stopping Simple DNS VPN");
        isRunning = false;
        
        if (localWebServer != null) {
            localWebServer.stop();
        }
        
        if (executorService != null) {
            executorService.shutdownNow();
        }
        
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing VPN interface", e);
            }
            vpnInterface = null;
        }
        
        stopForeground(true);
        stopSelf();
    }
    
    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }
    
    private Notification createNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? 
                PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT);
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Parental Control VPN")
            .setContentText("DNS filtering active - protecting device")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Simple DNS VPN Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("DNS-based content filtering");
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }
    
    /**
     * Run initial diagnostics to identify potential issues before VPN starts
     */
    private void runInitialDiagnostics() {
        executorService.submit(() -> {
            try {
                Log.i(TAG, "Running initial VPN diagnostics...");
                diagnostics.runCompleteDiagnostics();
                Log.i(TAG, "Initial diagnostics completed");
            } catch (Exception e) {
                Log.e(TAG, "Initial diagnostics failed", e);
            }
        });
    }
    
    /**
     * Run focused issue identification to determine root cause
     */
    private void runIssueIdentification() {
        executorService.submit(() -> {
            try {
                Log.i(TAG, "Running focused issue identification...");
                VpnIssueIdentifier.identifyRootCause();
                VpnIssueIdentifier.provideRecommendations();
                VpnIssueIdentifier.showCodeChanges();
                Log.i(TAG, "Issue identification completed");
            } catch (Exception e) {
                Log.e(TAG, "Issue identification failed", e);
            }
        });
    }
    
    /**
     * Run periodic diagnostics to monitor VPN health
     */
    private void runPeriodicDiagnostics() {
        long currentTime = System.currentTimeMillis();
        
        if (currentTime - lastDiagnosticRun > DIAGNOSTIC_INTERVAL) {
            lastDiagnosticRun = currentTime;
            
            executorService.submit(() -> {
                try {
                    Log.i(TAG, "Running periodic VPN diagnostics...");
                    
                    // Run focused diagnostics based on current issues
                    if (consecutiveFailures > 3) {
                        Log.w(TAG, "Multiple consecutive failures detected, running comprehensive diagnostics");
                        diagnostics.runCompleteDiagnostics();
                        diagnostics.testSpecificIssueScenarios();
                        VpnDebugAuditor.detectCriticalIssues();
                    } else {
                        // Quick health check
                        diagnostics.testExternalDnsResolution();
                        diagnostics.testFilterEngine();
                    }
                    
                } catch (Exception e) {
                    Log.e(TAG, "Periodic diagnostics failed", e);
                }
            });
        }
    }
    
    /**
     * Track failures and trigger enhanced diagnostics when needed
     */
    private void trackFailure(String context) {
        consecutiveFailures++;
        Log.w(TAG, "Failure tracked: " + context + " (consecutive failures: " + consecutiveFailures + ")");
        
        if (consecutiveFailures >= 5) {
            Log.e(TAG, "CRITICAL: Too many consecutive failures, running emergency diagnostics");
            executorService.submit(() -> {
                diagnostics.testSpecificIssueScenarios();
                VpnDebugAuditor.detectCriticalIssues();
            });
        }
    }
    
    /**
     * Reset failure counter on successful operations
     */
    private void trackSuccess(String context) {
        if (consecutiveFailures > 0) {
            Log.i(TAG, "Success after " + consecutiveFailures + " failures: " + context);
            consecutiveFailures = 0;
        }
    }
}
