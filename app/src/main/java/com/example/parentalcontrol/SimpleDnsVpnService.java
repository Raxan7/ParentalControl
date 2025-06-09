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
import java.nio.ByteBuffer;
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
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "SimpleDnsVpnService created");
        createNotificationChannel();
        
        filterEngine = new ContentFilterEngine(this);
        localWebServer = new LocalWebServer(this);
        executorService = Executors.newFixedThreadPool(2);
        
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
                   .addRoute("0.0.0.0", 0)
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
                            Log.v(TAG, "[startDnsServer] Packet received, length: " + length);
                            if (isDnsPacket(packet)) {
                                Log.d(TAG, "[startDnsServer] DNS packet detected");
                                handleDnsQuery(packet, out);
                            } else {
                                Log.v(TAG, "[startDnsServer] Non-DNS packet, forwarding");
                                out.write(packet.array(), 0, length);
                                out.flush();
                            }
                            packet.clear();
                        }
                        Thread.sleep(1);
                    } catch (IOException e) {
                        if (isRunning) {
                            Log.e(TAG, "[startDnsServer] Error in DNS server loop", e);
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
            Log.d(TAG, "[handleDnsQuery] DNS query for: " + domain);
            if (domain != null) {
                if (filterEngine.shouldBlockDomain(domain)) {
                    Log.i(TAG, "[handleDnsQuery] BLOCKING DNS query for: " + domain);
                    byte[] blockedResponse = createBlockedDnsResponse(packet);
                    if (blockedResponse != null) {
                        out.write(blockedResponse);
                        out.flush();
                        showBlockedNotification(domain);
                        Log.d(TAG, "[handleDnsQuery] Blocked response sent for: " + domain);
                        return;
                    } else {
                        Log.e(TAG, "[handleDnsQuery] Failed to create blocked DNS response for: " + domain);
                    }
                }
            }
            // Forward legitimate queries to real DNS server synchronously
            Log.d(TAG, "[handleDnsQuery] Forwarding DNS query for: " + domain);
            byte[] dnsResponse = forwardDnsQueryAndGetResponse(packet);
            if (dnsResponse != null) {
                out.write(dnsResponse);
                out.flush();
                Log.d(TAG, "[handleDnsQuery] Forwarded DNS response sent for: " + domain);
            } else {
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
    
    private byte[] createBlockedDnsResponse(ByteBuffer originalPacket) {
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
            
            // Add answer section pointing to 127.0.0.1
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
            
            // TTL (300 seconds)
            response[answerStart + 6] = 0;
            response[answerStart + 7] = 0;
            response[answerStart + 8] = 1;
            response[answerStart + 9] = 44;
            
            // Data length (4 bytes for IPv4)
            response[answerStart + 10] = 0;
            response[answerStart + 11] = 4;
            
            // IP address: 127.0.0.1
            response[answerStart + 12] = 127;
            response[answerStart + 13] = 0;
            response[answerStart + 14] = 0;
            response[answerStart + 15] = 1;
            
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
}
