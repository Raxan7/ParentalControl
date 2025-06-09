package com.example.parentalcontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Engine that analyzes network packets to detect and block inappropriate content
 */
public class ContentFilterEngine {
    private static final String TAG = "ContentFilterEngine";
    private static final String PREFS_NAME = "content_filter_prefs";
    
    // Configuration keys
    private static final String KEY_BLOCK_ADULT_CONTENT = "block_adult_content";
    private static final String KEY_BLOCK_SOCIAL_MEDIA = "block_social_media";
    private static final String KEY_BLOCK_GAMING = "block_gaming";
    
    private final Context context;
    private final SharedPreferences prefs;
    private final Set<String> blockedDomains;
    private final Set<String> blockedKeywords;
    
    // Adult content domains
    private static final String[] ADULT_DOMAINS = {
        // Popular adult sites
        "pornhub.com", "xvideos.com", "xnxx.com", "redtube.com", 
        "youporn.com", "tube8.com", "spankbang.com", "xhamster.com",
        "porn.com", "sex.com", "xxx.com", "adult.com", "nsfw.com",
        "brazzers.com", "bangbros.com", "reality-kings.com",
        "playboy.com", "penthouse.com", "hustler.com",
        
        // Additional adult content sites
        "chaturbate.com", "cam4.com", "myfreecams.com", "livejasmin.com",
        "stripchat.com", "bongacams.com", "camsoda.com", "flirt4free.com",
        "adultfriendfinder.com", "ashley-madison.com", "seeking.com",
        "onlyfans.com", "patreon.com", "manyvids.com", "clips4sale.com",
        
        // Content aggregators
        "reddit.com/r/gonewild", "reddit.com/r/nsfw", "imgur.com/r/nsfw",
        "tumblr.com", "deviantart.com", "flickr.com"
    };
    
    // Social media domains (configurable blocking)
    private static final String[] SOCIAL_MEDIA_DOMAINS = {
        "facebook.com", "www.facebook.com", "m.facebook.com",
        "instagram.com", "www.instagram.com", "m.instagram.com",
        "twitter.com", "www.twitter.com", "m.twitter.com", "x.com",
        "tiktok.com", "www.tiktok.com", "m.tiktok.com",
        "snapchat.com", "www.snapchat.com", "web.snapchat.com",
        "discord.com", "discordapp.com", "canary.discord.com",
        "reddit.com", "www.reddit.com", "m.reddit.com", "old.reddit.com",
        "pinterest.com", "www.pinterest.com", "m.pinterest.com",
        "linkedin.com", "www.linkedin.com", "m.linkedin.com",
        "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be",
        "twitch.tv", "www.twitch.tv", "m.twitch.tv",
        "telegram.org", "web.telegram.org", "t.me"
    };
    
    // Gaming and entertainment (optional blocking)
    private static final String[] GAMING_DOMAINS = {
        "steam.com", "store.steampowered.com", "steamcommunity.com",
        "epic-games.com", "epicgames.com", "fortnite.com",
        "battle.net", "blizzard.com", "activision.com",
        "roblox.com", "www.roblox.com", "m.roblox.com",
        "minecraft.net", "mojang.com", "xbox.com", "playstation.com"
    };
    
    // Keywords that indicate adult content
    private static final String[] ADULT_KEYWORDS = {
        "porn", "sex", "xxx", "adult", "nude", "naked", "erotic",
        "explicit", "nsfw", "18+", "mature", "fetish", "webcam",
        "camgirl", "escort", "hookup", "dating", "milf", "teen",
        "amateur", "hardcore", "softcore", "bikini", "lingerie"
    };
    
    public ContentFilterEngine(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.blockedDomains = new HashSet<>();
        this.blockedKeywords = new HashSet<>();
        
        initializeBlockLists();
    }
    
    private void initializeBlockLists() {
        // Always block adult content by default
        boolean blockAdultContent = prefs.getBoolean(KEY_BLOCK_ADULT_CONTENT, true);
        if (blockAdultContent) {
            blockedDomains.addAll(Arrays.asList(ADULT_DOMAINS));
            blockedKeywords.addAll(Arrays.asList(ADULT_KEYWORDS));
        }
        
        // Social media blocking is configurable
        boolean blockSocialMedia = prefs.getBoolean(KEY_BLOCK_SOCIAL_MEDIA, true);
        if (blockSocialMedia) {
            blockedDomains.addAll(Arrays.asList(SOCIAL_MEDIA_DOMAINS));
        }
        
        // Gaming blocking is optional (default off)
        boolean blockGaming = prefs.getBoolean(KEY_BLOCK_GAMING, false);
        if (blockGaming) {
            blockedDomains.addAll(Arrays.asList(GAMING_DOMAINS));
        }
        
        Log.d(TAG, "Initialized content filter with " + blockedDomains.size() + 
              " blocked domains and " + blockedKeywords.size() + " blocked keywords");
        Log.d(TAG, "Config - Adult: " + blockAdultContent + ", Social: " + blockSocialMedia + 
              ", Gaming: " + blockGaming);
    }
    
    /**
     * Configuration methods for updating filter settings
     */
    public void setBlockAdultContent(boolean block) {
        prefs.edit().putBoolean(KEY_BLOCK_ADULT_CONTENT, block).apply();
        reinitializeBlockLists();
    }
    
    public void setBlockSocialMedia(boolean block) {
        prefs.edit().putBoolean(KEY_BLOCK_SOCIAL_MEDIA, block).apply();
        reinitializeBlockLists();
    }
    
    public void setBlockGaming(boolean block) {
        prefs.edit().putBoolean(KEY_BLOCK_GAMING, block).apply();
        reinitializeBlockLists();
    }
    
    public boolean isBlockingAdultContent() {
        return prefs.getBoolean(KEY_BLOCK_ADULT_CONTENT, true);
    }
    
    public boolean isBlockingSocialMedia() {
        return prefs.getBoolean(KEY_BLOCK_SOCIAL_MEDIA, true);
    }
    
    public boolean isBlockingGaming() {
        return prefs.getBoolean(KEY_BLOCK_GAMING, false);
    }
    
    private void reinitializeBlockLists() {
        blockedDomains.clear();
        blockedKeywords.clear();
        initializeBlockLists();
    }
    
    /**
     * Analyze a network packet to determine if it should be blocked
     */
    public boolean shouldBlockPacket(ByteBuffer packet) {
        try {
            // Parse IP header
            if (packet.remaining() < 20) {
                return false; // Too small to be a valid IP packet
            }
            
            byte[] data = packet.array();
            int offset = packet.position();
            
            // Check if it's an IP packet (version 4)
            int version = (data[offset] & 0xF0) >> 4;
            if (version != 4) {
                return false; // Not IPv4
            }
            
            // Get IP header length
            int ihl = (data[offset] & 0x0F) * 4;
            if (packet.remaining() < ihl) {
                return false; // Invalid header length
            }
            
            // Get protocol
            int protocol = data[offset + 9] & 0xFF;
            
            // We're primarily interested in TCP (6) and UDP (17)
            if (protocol == 6) { // TCP
                return analyzeTcpPacket(data, offset + ihl, packet.remaining() - ihl);
            } else if (protocol == 17) { // UDP
                return analyzeUdpPacket(data, offset + ihl, packet.remaining() - ihl);
            }
            
            return false;
            
        } catch (Exception e) {
            Log.e(TAG, "Error analyzing packet", e);
            return false;
        }
    }
    
    private boolean analyzeTcpPacket(byte[] data, int offset, int length) {
        try {
            if (length < 20) {
                return false; // Too small for TCP header
            }
            
            // Get destination port
            int destPort = ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
            
            // Check if it's HTTP (80) or HTTPS (443)
            if (destPort == 80 || destPort == 443) {
                // Get TCP header length
                int tcpHeaderLength = ((data[offset + 12] & 0xF0) >> 4) * 4;
                
                if (length > tcpHeaderLength) {
                    // Analyze HTTP payload
                    String payload = extractStringFromPayload(data, offset + tcpHeaderLength, 
                                                            length - tcpHeaderLength);
                    return analyzeHttpContent(payload, destPort == 443);
                }
            }
            
            return false;
            
        } catch (Exception e) {
            Log.e(TAG, "Error analyzing TCP packet", e);
            return false;
        }
    }
    
    private boolean analyzeUdpPacket(byte[] data, int offset, int length) {
        try {
            if (length < 8) {
                return false; // Too small for UDP header
            }
            
            // Get destination port
            int destPort = ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
            
            // Check if it's DNS (53)
            if (destPort == 53) {
                // Analyze DNS query
                return analyzeDnsQuery(data, offset + 8, length - 8);
            }
            
            return false;
            
        } catch (Exception e) {
            Log.e(TAG, "Error analyzing UDP packet", e);
            return false;
        }
    }
    
    private boolean analyzeDnsQuery(byte[] data, int offset, int length) {
        try {
            if (length < 12) {
                return false; // Too small for DNS header
            }
            
            // Parse DNS query to extract domain name
            String domain = extractDomainFromDnsQuery(data, offset, length);
            if (domain != null) {
                Log.d(TAG, "DNS query for domain: " + domain);
                return isDomainBlocked(domain);
            }
            
            return false;
            
        } catch (Exception e) {
            Log.e(TAG, "Error analyzing DNS query", e);
            return false;
        }
    }
    
    private String extractDomainFromDnsQuery(byte[] data, int offset, int length) {
        try {
            // Skip DNS header (12 bytes)
            int pos = offset + 12;
            StringBuilder domain = new StringBuilder();
            
            while (pos < offset + length) {
                int labelLength = data[pos] & 0xFF;
                if (labelLength == 0) {
                    break; // End of domain name
                }
                
                if (labelLength > 63) {
                    break; // Invalid label length
                }
                
                pos++;
                if (pos + labelLength > offset + length) {
                    break; // Would read beyond packet
                }
                
                if (domain.length() > 0) {
                    domain.append('.');
                }
                
                for (int i = 0; i < labelLength; i++) {
                    domain.append((char) (data[pos + i] & 0xFF));
                }
                
                pos += labelLength;
            }
            
            return domain.toString().toLowerCase();
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting domain from DNS query", e);
            return null;
        }
    }
    
    private boolean analyzeHttpContent(String payload, boolean isHttps) {
        if (payload == null || payload.isEmpty()) {
            return false;
        }
        
        String lowerPayload = payload.toLowerCase();
        
        // Extract Host header for HTTP requests
        if (!isHttps && lowerPayload.contains("host:")) {
            String host = extractHostHeader(lowerPayload);
            if (host != null && isDomainBlocked(host)) {
                Log.d(TAG, "Blocked HTTP request to: " + host);
                return true;
            }
        }
        
        // Check for adult content keywords in the payload
        for (String keyword : blockedKeywords) {
            if (lowerPayload.contains(keyword)) {
                Log.d(TAG, "Blocked content containing keyword: " + keyword);
                return true;
            }
        }
        
        return false;
    }
    
    private String extractHostHeader(String payload) {
        try {
            int hostIndex = payload.indexOf("host:");
            if (hostIndex != -1) {
                int start = hostIndex + 5; // "host:".length()
                int end = payload.indexOf('\n', start);
                if (end == -1) {
                    end = payload.indexOf('\r', start);
                }
                if (end == -1) {
                    end = payload.length();
                }
                
                return payload.substring(start, end).trim();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting host header", e);
        }
        return null;
    }
    
    /**
     * Check if a domain should be blocked (public method for VPN service)
     */
    public boolean shouldBlockDomain(String domain) {
        return isDomainBlocked(domain);
    }
    
    private boolean isDomainBlocked(String domain) {
        if (domain == null || domain.isEmpty()) {
            return false;
        }
        
        String normalizedDomain = domain.toLowerCase().trim();
        
        // Remove www. prefix if present for checking
        String checkDomain = normalizedDomain.startsWith("www.") ? 
                            normalizedDomain.substring(4) : normalizedDomain;
        
        // Check exact match
        if (blockedDomains.contains(checkDomain) || blockedDomains.contains(normalizedDomain)) {
            Log.d(TAG, "Blocking domain (exact match): " + domain);
            return true;
        }
        
        // Check subdomains
        for (String blockedDomain : blockedDomains) {
            if (checkDomain.endsWith("." + blockedDomain) || 
                checkDomain.equals(blockedDomain) ||
                normalizedDomain.endsWith("." + blockedDomain)) {
                Log.d(TAG, "Blocking domain (subdomain match): " + domain + " matches " + blockedDomain);
                return true;
            }
        }
        
        // Check if domain contains blocked keywords
        for (String keyword : blockedKeywords) {
            if (normalizedDomain.contains(keyword)) {
                Log.d(TAG, "Blocking domain (keyword match): " + domain + " contains " + keyword);
                return true;
            }
        }
        
        return false;
    }
    
    private String extractStringFromPayload(byte[] data, int offset, int length) {
        try {
            // Only extract printable ASCII characters to avoid binary data
            StringBuilder sb = new StringBuilder();
            int maxLength = Math.min(length, 1000); // Limit to first 1000 bytes
            
            for (int i = 0; i < maxLength; i++) {
                byte b = data[offset + i];
                if (b >= 32 && b <= 126) { // Printable ASCII
                    sb.append((char) b);
                } else if (b == 10 || b == 13) { // Line breaks
                    sb.append((char) b);
                }
            }
            
            return sb.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting string from payload", e);
            return "";
        }
    }
    
    /**
     * Add a domain to the blocked list
     */
    public void addBlockedDomain(String domain) {
        if (domain != null && !domain.isEmpty()) {
            blockedDomains.add(domain.toLowerCase());
            Log.d(TAG, "Added blocked domain: " + domain);
        }
    }
    
    /**
     * Remove a domain from the blocked list
     */
    public void removeBlockedDomain(String domain) {
        if (domain != null) {
            blockedDomains.remove(domain.toLowerCase());
            Log.d(TAG, "Removed blocked domain: " + domain);
        }
    }
    
    /**
     * Get the current list of blocked domains
     */
    public Set<String> getBlockedDomains() {
        return new HashSet<>(blockedDomains);
    }
}
