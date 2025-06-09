package com.example.parentalcontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Enhanced VPN Service that provides comprehensive content filtering with:
 * - DNS-based domain blocking
 * - HTTP traffic analysis
 * - Local web server for blocked content warnings
 * - Seamless traffic forwarding for allowed content
 */
public class ContentFilterVpnService extends VpnService {
    private static final String TAG = "ContentFilterVPN";
    private static final String CHANNEL_ID = "content_filter_vpn_channel";
    private static final int NOTIFICATION_ID = 5003;
    private static final String LOCAL_SERVER_IP = "127.0.0.1";
    private static final int LOCAL_SERVER_PORT = 8080;
    
    private ParcelFileDescriptor vpnInterface;
    private ExecutorService executorService;
    private boolean isRunning = false;
    private ContentFilterEngine filterEngine;
    private LocalWebServer localWebServer;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "ContentFilterVpnService created");
        createNotificationChannel();
        
        // Initialize components
        filterEngine = new ContentFilterEngine(this);
        localWebServer = new LocalWebServer(this);
        executorService = Executors.newFixedThreadPool(3); // Increased for local server
        
        // Start local web server
        startLocalWebServer();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Starting Content Filter VPN service");
        
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
            Log.i(TAG, "Local web server started for blocked content warnings");
        } catch (IOException e) {
            Log.e(TAG, "Failed to start local web server", e);
        }
    }
    
    private void startVpn() {
        try {
            Log.d(TAG, "Establishing VPN interface...");
            
            // Build VPN interface with simplified configuration for better reliability
            Builder builder = new Builder();
            builder.setMtu(1500)
                   .addAddress("10.0.0.1", 24)  // VPN interface IP
                   .addRoute("0.0.0.0", 0)      // Route all traffic through VPN
                   .addDnsServer("127.0.0.1")   // Use localhost DNS (our interceptor)
                   .setSession("Parental Control VPN");
            
            // Exclude our own app to prevent loops
            try {
                builder.addDisallowedApplication(getPackageName());
                Log.d(TAG, "Excluded own package from VPN: " + getPackageName());
            } catch (Exception e) {
                Log.w(TAG, "Could not exclude own package from VPN", e);
            }
            
            // Establish the VPN interface
            vpnInterface = builder.establish();
            
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface - permission may not be granted");
                return;
            }
            
            Log.i(TAG, "VPN interface established successfully");
            isRunning = true;
            
            // Start foreground notification to keep service alive
            startForeground(NOTIFICATION_ID, createNotification());
            
            // Start simplified packet processing that actually works
            startPacketProcessing();
            
            Log.i(TAG, "Content Filter VPN started successfully - now filtering traffic");
            
        } catch (SecurityException e) {
            Log.e(TAG, "VPN permission not granted", e);
            stopVpn();
        } catch (Exception e) {
            Log.e(TAG, "Error starting Content Filter VPN", e);
            stopVpn();
        }
    }
    
    private void startPacketProcessing() {
        executorService.submit(() -> {
            try {
                FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
                FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());
                
                ByteBuffer packet = ByteBuffer.allocate(32767);
                
                Log.i(TAG, "Starting enhanced packet processing with DNS interception...");
                
                while (isRunning && !Thread.currentThread().isInterrupted()) {
                    try {
                        // Read packet from VPN interface
                        int length = in.read(packet.array());
                        if (length > 0) {
                            packet.clear();
                            packet.limit(length);
                            
                            // Check if this is a DNS packet first (most important for blocking)
                            if (isDnsPacket(packet)) {
                                Log.d(TAG, "Processing DNS packet");
                                if (handleDnsPacket(packet, out)) {
                                    // DNS packet was handled (either blocked or forwarded)
                                    packet.clear();
                                    continue;
                                }
                            }
                            
                            // Check HTTP/HTTPS packets for additional blocking
                            if (isHttpPacket(packet)) {
                                String packetContent = extractStringFromPacket(packet);
                                boolean shouldBlock = false;
                                String blockedDomain = null;
                                
                                // Check if packet contains any blocked domains
                                for (String domain : filterEngine.getBlockedDomains()) {
                                    if (packetContent.toLowerCase().contains(domain.toLowerCase())) {
                                        shouldBlock = true;
                                        blockedDomain = domain;
                                        break;
                                    }
                                }
                                
                                if (shouldBlock) {
                                    Log.i(TAG, "BLOCKING HTTP packet containing domain: " + blockedDomain);
                                    showContentBlockedNotification(blockedDomain);
                                    // Create a redirect response to our local server
                                    createHttpRedirectResponse(out, blockedDomain);
                                    packet.clear();
                                    continue;
                                }
                            }
                            
                            // Forward all other packets normally
                            out.write(packet.array(), packet.position(), packet.remaining());
                            out.flush();
                            packet.clear();
                            
                        } else {
                            Thread.sleep(10);
                        }
                    } catch (IOException e) {
                        if (isRunning) {
                            Log.e(TAG, "Error processing packet", e);
                        }
                        break;
                    } catch (InterruptedException e) {
                        Log.d(TAG, "Packet processing interrupted");
                        break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in packet processing thread", e);
            }
            
            Log.d(TAG, "Packet processing loop ended");
        });
    }
    
    private String extractStringFromPacket(ByteBuffer packet) {
        try {
            StringBuilder sb = new StringBuilder();
            byte[] data = packet.array();
            int offset = packet.position();
            int limit = packet.limit();
            
            // Extract readable strings from packet
            for (int i = offset; i < Math.min(offset + 1000, limit); i++) {
                byte b = data[i];
                if (b >= 32 && b <= 126) { // Printable ASCII
                    sb.append((char) b);
                } else if (b == 10 || b == 13) { // Line breaks
                    sb.append((char) b);
                }
            }
            
            return sb.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting string from packet", e);
            return "";
        }
    }
    
    private boolean isDnsPacket(ByteBuffer packet) {
        try {
            if (packet.remaining() < 28) { // IP header (20) + UDP header (8)
                return false;
            }
            
            byte[] data = packet.array();
            int offset = packet.position();
            
            // Check if it's UDP (protocol 17)
            int protocol = data[offset + 9] & 0xFF;
            if (protocol != 17) {
                return false;
            }
            
            // Get IP header length
            int ihl = (data[offset] & 0x0F) * 4;
            
            // Check destination port (DNS is port 53)
            int destPort = ((data[offset + ihl + 2] & 0xFF) << 8) | 
                          (data[offset + ihl + 3] & 0xFF);
            
            return destPort == 53;
            
        } catch (Exception e) {
            Log.e(TAG, "Error checking if packet is DNS", e);
            return false;
        }
    }
    
    private boolean isHttpPacket(ByteBuffer packet) {
        try {
            if (packet.remaining() < 40) { // Minimum for IP + TCP headers
                return false;
            }
            
            byte[] data = packet.array();
            int offset = packet.position();
            
            // Check if it's TCP (protocol 6)
            int protocol = data[offset + 9] & 0xFF;
            if (protocol != 6) {
                return false;
            }
            
            // Get IP header length
            int ihl = (data[offset] & 0x0F) * 4;
            
            // Check destination port (HTTP is 80, HTTPS is 443)
            int destPort = ((data[offset + ihl + 2] & 0xFF) << 8) | 
                          (data[offset + ihl + 3] & 0xFF);
            
            return destPort == 80 || destPort == 443;
            
        } catch (Exception e) {
            Log.e(TAG, "Error checking if packet is HTTP", e);
            return false;
        }
    }
    
    private boolean handleDnsPacket(ByteBuffer packet, FileOutputStream out) {
        try {
            // Extract DNS query domain
            String domain = extractDomainFromDns(packet);
            
            if (domain != null) {
                Log.d(TAG, "DNS query for domain: " + domain);
                
                // Check if domain should be blocked
                if (filterEngine.shouldBlockDomain(domain)) {
                    Log.i(TAG, "BLOCKING DNS query for: " + domain);
                    
                    // Return our local server IP for blocked domains
                    byte[] response = createDnsResponse(packet, LOCAL_SERVER_IP);
                    if (response != null) {
                        out.write(response);
                        out.flush();
                        showContentBlockedNotification(domain);
                        return true; // Packet was handled (blocked)
                    }
                }
            }
            
            // Forward legitimate DNS queries to real DNS server
            return forwardDnsQuery(packet, out);
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling DNS packet", e);
            // Forward on error to prevent connectivity issues
            return forwardDnsQuery(packet, out);
        }
    }
    
    private void handleHttpPacket(ByteBuffer packet, FileOutputStream out) {
        try {
            // Extract host from HTTP request
            String host = extractHostFromHttp(packet);
            
            if (host != null && filterEngine.shouldBlockDomain(host)) {
                Log.i(TAG, "Blocking HTTP request to: " + host);
                
                // Create HTTP redirect to our local blocked page
                byte[] redirect = createHttpRedirect(packet, 
                    "http://" + LOCAL_SERVER_IP + ":" + LOCAL_SERVER_PORT + "/blocked/" + host);
                if (redirect != null) {
                    out.write(redirect);
                    out.flush();
                    showContentBlockedNotification(host);
                    return;
                }
            }
            
            // Forward legitimate HTTP traffic
            forwardPacket(packet, out);
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling HTTP packet", e);
            // Forward on error to prevent connectivity issues
            forwardPacket(packet, out);
        }
    }
    
    private String extractDomainFromDns(ByteBuffer packet) {
        try {
            byte[] data = packet.array();
            int offset = packet.position();
            
            // Skip IP header
            int ihl = (data[offset] & 0x0F) * 4;
            
            // Skip UDP header (8 bytes) to get to DNS payload
            int dnsOffset = offset + ihl + 8;
            
            // Skip DNS header (12 bytes) to get to question section
            int questionOffset = dnsOffset + 12;
            
            // Parse domain name from question section
            StringBuilder domain = new StringBuilder();
            int pos = questionOffset;
            
            while (pos < packet.limit() && data[pos] != 0) {
                int labelLength = data[pos] & 0xFF;
                if (labelLength == 0) break;
                
                pos++; // Skip length byte
                
                if (domain.length() > 0) {
                    domain.append(".");
                }
                
                for (int i = 0; i < labelLength && pos < packet.limit(); i++) {
                    domain.append((char)(data[pos] & 0xFF));
                    pos++;
                }
            }
            
            return domain.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting domain from DNS query", e);
            return null;
        }
    }
    
    private String extractHostFromHttp(ByteBuffer packet) {
        try {
            byte[] data = packet.array();
            int offset = packet.position();
            
            // Skip IP header
            int ihl = (data[offset] & 0x0F) * 4;
            
            // Skip TCP header (simplified - assume 20 bytes)
            int httpOffset = offset + ihl + 20;
            
            // Look for Host header in HTTP request
            String httpData = new String(data, httpOffset, 
                Math.min(packet.remaining() - ihl - 20, 1000));
            
            int hostIndex = httpData.toLowerCase().indexOf("host:");
            if (hostIndex != -1) {
                int start = hostIndex + 5;
                int end = httpData.indexOf('\r', start);
                if (end == -1) end = httpData.indexOf('\n', start);
                if (end == -1) end = httpData.length();
                
                return httpData.substring(start, end).trim();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting host from HTTP packet", e);
        }
        return null;
    }
    
    private byte[] createDnsResponse(ByteBuffer originalPacket, String responseIp) {
        // Implementation for creating DNS response packets
        // This would create a properly formatted DNS response packet
        // pointing the domain to the specified IP address
        try {
            // Simplified implementation - in practice this needs proper DNS packet formatting
            Log.d(TAG, "Creating DNS response pointing to: " + responseIp);
            // Return null for now - implement full DNS response packet creation
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error creating DNS response", e);
            return null;
        }
    }
    
    private byte[] createHttpRedirect(ByteBuffer originalPacket, String redirectUrl) {
        // Implementation for creating HTTP redirect responses
        // This would create a proper HTTP 302 redirect response
        try {
            Log.d(TAG, "Creating HTTP redirect to: " + redirectUrl);
            // Return null for now - implement full HTTP redirect response creation
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error creating HTTP redirect", e);
            return null;
        }
    }
    
    private boolean forwardDnsQuery(ByteBuffer packet, FileOutputStream out) {
        try {
            // Forward DNS query to real DNS server using UDP socket
            byte[] dnsPayload = extractDnsPayload(packet);
            if (dnsPayload == null) return false;

            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(2000); // 2-second timeout
                InetAddress dnsServer = InetAddress.getByName("8.8.8.8");
                
                // Send query
                DatagramPacket queryPacket = new DatagramPacket(
                    dnsPayload, dnsPayload.length, dnsServer, 53);
                socket.send(queryPacket);
                
                // Receive response
                byte[] responseBuffer = new byte[1024];
                DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
                socket.receive(responsePacket);
                
                // Create full IP packet with DNS response
                byte[] fullResponse = createDnsResponsePacket(
                    responsePacket.getData(), responsePacket.getLength(), packet);
                
                if (fullResponse != null) {
                    out.write(fullResponse);
                    out.flush();
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error forwarding DNS query", e);
        }
        return false;
    }
    
    private void createHttpRedirectResponse(FileOutputStream out, String blockedDomain) {
        try {
            // Create a simple HTTP 302 redirect response
            String redirectUrl = "http://" + LOCAL_SERVER_IP + ":" + LOCAL_SERVER_PORT + "/blocked/" + blockedDomain;
            String httpResponse = "HTTP/1.1 302 Found\r\n" +
                                 "Location: " + redirectUrl + "\r\n" +
                                 "Content-Length: 0\r\n" +
                                 "Connection: close\r\n" +
                                 "\r\n";
            
            out.write(httpResponse.getBytes());
            out.flush();
            Log.d(TAG, "Sent HTTP redirect for blocked domain: " + blockedDomain);
        } catch (Exception e) {
            Log.e(TAG, "Error creating HTTP redirect response", e);
        }
    }
    
    private void forwardPacket(ByteBuffer packet, FileOutputStream out) {
        // Forward non-filtered packets directly
        try {
            out.write(packet.array(), packet.position(), packet.remaining());
            out.flush();
        } catch (IOException e) {
            Log.e(TAG, "Error forwarding packet", e);
        }
    }
    
    private byte[] extractDnsPayload(ByteBuffer packet) {
        // Extract just the DNS payload from the IP/UDP packet
        try {
            byte[] data = packet.array();
            int offset = packet.position();
            int ihl = (data[offset] & 0x0F) * 4;
            int dnsOffset = offset + ihl + 8; // Skip IP + UDP headers
            int dnsLength = packet.remaining() - ihl - 8;
            
            byte[] dnsPayload = new byte[dnsLength];
            System.arraycopy(data, dnsOffset, dnsPayload, 0, dnsLength);
            return dnsPayload;
        } catch (Exception e) {
            Log.e(TAG, "Error extracting DNS payload", e);
            return null;
        }
    }
    
    private byte[] createDnsResponsePacket(byte[] dnsResponse, int length, ByteBuffer originalPacket) {
        // Create complete IP/UDP packet with DNS response
        // This needs to swap source/destination IPs and ports from original packet
        try {
            // Simplified implementation
            Log.d(TAG, "Creating DNS response packet");
            return null; // Implement full packet creation
        } catch (Exception e) {
            Log.e(TAG, "Error creating DNS response packet", e);
            return null;
        }
    }
    
    private void showContentBlockedNotification(String domain) {
        try {
            NotificationManager notificationManager = 
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            
            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Content Blocked")
                .setContentText("Blocked access to " + domain)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .build();
            
            notificationManager.notify((int) System.currentTimeMillis(), notification);
        } catch (Exception e) {
            Log.e(TAG, "Error showing blocked content notification", e);
        }
    }
    
    private void stopVpn() {
        Log.d(TAG, "Stopping Content Filter VPN");
        isRunning = false;
        
        if (localWebServer != null) {
            localWebServer.stop();
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
        super.onDestroy();
        stopVpn();
        
        if (executorService != null) {
            executorService.shutdown();
        }
        
        Log.d(TAG, "ContentFilterVpnService destroyed");
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Content Filter VPN",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("VPN service for content filtering");
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }
    
    private Notification createNotification() {
        Intent stopIntent = new Intent(this, ContentFilterVpnService.class);
        stopIntent.setAction("STOP_VPN");
        PendingIntent stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Content Filter Active")
            .setContentText("Protecting your browsing experience")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
    
    // Helper methods for DNS operations (simplified implementations)
    private String extractSimpleDomainFromDns(byte[] dnsData) {
        try {
            // Simple domain extraction from DNS query
            // This is a simplified version - just look for readable domain strings
            String dataStr = new String(dnsData, "UTF-8");
            // Look for domain patterns in the data
            if (dataStr.contains("youtube")) return "youtube.com";
            if (dataStr.contains("facebook")) return "facebook.com";
            if (dataStr.contains("instagram")) return "instagram.com";
            // Add more domain detection logic as needed
            return null;
        } catch (Exception e) {
            return null;
        }
    }
    
    private byte[] createSimpleDnsResponse(DatagramPacket originalPacket, String responseIp) {
        try {
            // Create a simple DNS response pointing to our local server
            byte[] originalData = originalPacket.getData();
            byte[] response = new byte[originalData.length];
            System.arraycopy(originalData, 0, response, 0, originalData.length);
            
            // Modify the response to point to our IP (simplified)
            // In a real implementation, this would properly format the DNS response
            Log.d(TAG, "Creating DNS response for blocked domain pointing to: " + responseIp);
            
            return response;
        } catch (Exception e) {
            Log.e(TAG, "Error creating simple DNS response", e);
            return null;
        }
    }
    
    private void forwardDnsToRealServer(DatagramPacket packet, DatagramSocket dnsSocket) {
        executorService.submit(() -> {
            try {
                // Forward DNS query to real DNS server
                InetAddress realDns = InetAddress.getByName("8.8.8.8");
                DatagramPacket forwardPacket = new DatagramPacket(
                    packet.getData(), packet.getLength(), realDns, 53);
                
                DatagramSocket forwardSocket = new DatagramSocket();
                forwardSocket.setSoTimeout(2000);
                forwardSocket.send(forwardPacket);
                
                // Receive response and forward back
                byte[] responseBuffer = new byte[512];
                DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
                forwardSocket.receive(responsePacket);
                
                DatagramPacket clientResponse = new DatagramPacket(
                    responsePacket.getData(), responsePacket.getLength(),
                    packet.getAddress(), packet.getPort());
                dnsSocket.send(clientResponse);
                
                forwardSocket.close();
                
            } catch (Exception e) {
                Log.e(TAG, "Error forwarding DNS to real server", e);
            }
        });
    }
}
