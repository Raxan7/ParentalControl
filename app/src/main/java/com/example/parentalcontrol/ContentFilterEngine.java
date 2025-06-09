package com.example.parentalcontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
    
    // Social media PRIMARY domains (main sites that should be blocked)
    private static final String[] SOCIAL_MEDIA_PRIMARY_DOMAINS = {
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
    
    // Social media DEPENDENCY domains (APIs, CDNs, embeds - should be allowed as dependencies)
    private static final String[] SOCIAL_MEDIA_DEPENDENCY_DOMAINS = {
        // Facebook/Meta dependencies
        "graph.facebook.com", "connect.facebook.net", "fbcdn.net", "static.xx.fbcdn.net",
        "z-m-scontent.xx.fbcdn.net", "scontent.xx.fbcdn.net", "platform.instagram.com",
        
        // Twitter/X dependencies  
        "abs.twimg.com", "pbs.twimg.com", "ton.twimg.com", "platform.twitter.com",
        "cdn.syndication.twimg.com", "widgets.twimg.com",
        
        // YouTube dependencies (but not main site)
        "ytimg.com", "i.ytimg.com", "s.ytimg.com", "youtube-nocookie.com",
        "googlevideo.com", "ytimg.l.google.com",
        
        // LinkedIn dependencies
        "platform.linkedin.com", "licdn.com", "linkedin.sc.omtrdc.net",
        
        // Pinterest dependencies
        "pinimg.com", "widgets.pinterest.com",
        
        // TikTok dependencies
        "byteoversea.com", "musical.ly", "tiktokcdn.com",
        
        // Reddit dependencies
        "redditmedia.com", "redditstatic.com", "redd.it",
        
        // Discord dependencies
        "discordapp.net", "discord.gg", "cdn.discordapp.com"
    };
    
    // Context tracking for smart blocking
    private final Set<String> recentlyAccessedDomains = new HashSet<>();
    private final Map<String, Long> domainAccessTime = new HashMap<>();
    private static final long CONTEXT_WINDOW_MS = 10000; // 10 seconds
    
    // Social media domains (configurable blocking) - DEPRECATED, replaced by PRIMARY/DEPENDENCY approach
    private static final String[] SOCIAL_MEDIA_DOMAINS = {
        // This is now managed by SOCIAL_MEDIA_PRIMARY_DOMAINS and SOCIAL_MEDIA_DEPENDENCY_DOMAINS
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
    
    // Exhaustive whitelist for essential domains and subdomains required for general web/app functionality
    private static final String[] WHITELISTED_SUBDOMAINS = {
        // Google core and static resources
        "google.com", "www.google.com", "gstatic.com", "fonts.googleapis.com", "fonts.gstatic.com", "ssl.gstatic.com", "apis.google.com", "accounts.google.com", "clients1.google.com", "clients2.google.com", "clients3.google.com", "clients4.google.com", "clients5.google.com", "clients6.google.com", "lh3.googleusercontent.com", "lh4.googleusercontent.com", "lh5.googleusercontent.com", "lh6.googleusercontent.com", "googleusercontent.com",
        // Cloudflare and CDN
        "cloudflare.com", "www.cloudflare.com", "cdnjs.cloudflare.com", "cdn.jsdelivr.net", "jsdelivr.net", "cdn.cloudflare.net", "cdn.openai.com",
        // OpenAI/ChatGPT
        "openai.com", "chatgpt.com", "cdn.openai.com", "auth0.openai.com", "platform.openai.com",
        // Microsoft
        "microsoft.com", "www.microsoft.com", "login.microsoftonline.com", "live.com", "outlook.com", "office.com", "msn.com",
        // Facebook essential services (not main site)
        "graph.facebook.com", "z-m-gateway.facebook.com", "fbcdn.net", "static.xx.fbcdn.net",
        // Twitter/X
        "twitter.com", "abs.twimg.com", "pbs.twimg.com", "ton.twimg.com", "x.com",
        // Apple
        "apple.com", "www.apple.com", "icloud.com", "idmsa.apple.com",
        // Amazon
        "amazon.com", "www.amazon.com", "images-amazon.com", "ssl-images-amazon.com",
        // Github
        "github.com", "api.github.com", "raw.githubusercontent.com", "githubusercontent.com",
        // YouTube essential (not main site)
        "ytimg.com", "i.ytimg.com", "s.ytimg.com", "youtube-nocookie.com",
        // Firebase
        "firebaseio.com", "firebaseapp.com",
        // Akamai
        "akamaized.net", "akamaitechnologies.com",
        // Stripe/Payments
        "stripe.com", "js.stripe.com",
        // Miscellaneous essential
        "mozilla.org", "wikipedia.org", "wikimedia.org", "cdn.jsdelivr.net", "cdnjs.com", "bootstrapcdn.com", "jquery.com", "unpkg.com", "gravatar.com", "adobe.com", "cdn.segment.com", "cdn.optimizely.com", "cdn.sift.com", "cdn.ampproject.org", "cdn.shopify.com", "cdn.shopifycdn.net", "cdn.shopify.com",
        // DNS/Network
        "dns.google", "resolver1.opendns.com", "resolver2.opendns.com", "opendns.com", "cloudflare-dns.com",
        // Add more as needed for your environment
    };
    private final Set<String> whitelistedDomains = new HashSet<>(Arrays.asList(WHITELISTED_SUBDOMAINS));
    
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
        
        // NOTE: Social media blocking is now handled by smart blocking logic in isDomainBlocked()
        // We no longer add SOCIAL_MEDIA_PRIMARY_DOMAINS to blockedDomains directly
        
        // Gaming blocking is optional (default off)
        boolean blockGaming = prefs.getBoolean(KEY_BLOCK_GAMING, false);
        if (blockGaming) {
            blockedDomains.addAll(Arrays.asList(GAMING_DOMAINS));
        }
        
        Log.d(TAG, "Initialized content filter with " + blockedDomains.size() + 
              " blocked domains and " + blockedKeywords.size() + " blocked keywords");
        Log.d(TAG, "Config - Adult: " + blockAdultContent + 
              ", Social: " + prefs.getBoolean(KEY_BLOCK_SOCIAL_MEDIA, true) + 
              " (smart blocking), Gaming: " + blockGaming);
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
        String checkDomain = normalizedDomain.startsWith("www.") ? normalizedDomain.substring(4) : normalizedDomain;
        
        // Update domain access tracking for context analysis
        updateDomainAccessTracking(normalizedDomain);
        
        // Whitelist check: allow if in whitelist
        if (whitelistedDomains.contains(checkDomain) || whitelistedDomains.contains(normalizedDomain)) {
            Log.d(TAG, "Whitelisted domain: " + domain);
            return false;
        }
        
        // Smart social media blocking: check if this is a dependency access
        if (isSocialMediaDependency(normalizedDomain) && isAccessedAsContext(normalizedDomain)) {
            Log.d(TAG, "Allowing social media dependency in context: " + domain);
            return false;
        }
        
        // Check if it's a primary social media domain that should be blocked
        if (prefs.getBoolean(KEY_BLOCK_SOCIAL_MEDIA, true) && isSocialMediaPrimary(normalizedDomain)) {
            Log.d(TAG, "Blocking primary social media domain: " + domain);
            return true;
        }
        
        // Check exact match against blocked domains (adult content, gaming, etc.)
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
    
    /**
     * Helper methods for smart social media blocking
     */
    
    private void updateDomainAccessTracking(String domain) {
        long currentTime = System.currentTimeMillis();
        
        // Clean up old entries outside the context window
        cleanupOldDomainAccess(currentTime);
        
        // Record this domain access
        domainAccessTime.put(domain, currentTime);
        recentlyAccessedDomains.add(domain);
        
        Log.v(TAG, "Tracking domain access: " + domain + " at " + currentTime);
    }
    
    private void cleanupOldDomainAccess(long currentTime) {
        // Remove entries older than the context window
        recentlyAccessedDomains.removeIf(domain -> {
            Long accessTime = domainAccessTime.get(domain);
            if (accessTime == null || (currentTime - accessTime) > CONTEXT_WINDOW_MS) {
                domainAccessTime.remove(domain);
                return true;
            }
            return false;
        });
    }
    
    private boolean isSocialMediaPrimary(String domain) {
        for (String primaryDomain : SOCIAL_MEDIA_PRIMARY_DOMAINS) {
            if (domain.equals(primaryDomain) || domain.endsWith("." + primaryDomain)) {
                return true;
            }
        }
        return false;
    }
    
    private boolean isSocialMediaDependency(String domain) {
        for (String dependencyDomain : SOCIAL_MEDIA_DEPENDENCY_DOMAINS) {
            if (domain.equals(dependencyDomain) || domain.endsWith("." + dependencyDomain)) {
                return true;
            }
        }
        return false;
    }
    
    private boolean isAccessedAsContext(String domain) {
        // Check if there are recent non-social-media domains accessed
        // This suggests the social media dependency is being used by another site
        
        long currentTime = System.currentTimeMillis();
        boolean hasRecentNonSocialAccess = false;
        
        for (String recentDomain : recentlyAccessedDomains) {
            Long accessTime = domainAccessTime.get(recentDomain);
            if (accessTime != null && (currentTime - accessTime) <= CONTEXT_WINDOW_MS) {
                // Check if this recent domain is NOT a social media primary domain
                if (!isSocialMediaPrimary(recentDomain) && !isSocialMediaDependency(recentDomain)) {
                    hasRecentNonSocialAccess = true;
                    Log.v(TAG, "Found recent non-social domain: " + recentDomain + 
                          " (accessed " + (currentTime - accessTime) + "ms ago)");
                    break;
                }
            }
        }
        
        if (hasRecentNonSocialAccess) {
            Log.d(TAG, "Social media dependency " + domain + " accessed in context of other sites");
            return true;
        } else {
            Log.d(TAG, "Social media dependency " + domain + " accessed without context (blocking)");
            return false;
        }
    }
    
    /**
     * Debug and monitoring methods for smart social media blocking
     */
    
    public void logCurrentContext() {
        long currentTime = System.currentTimeMillis();
        cleanupOldDomainAccess(currentTime);
        
        Log.d(TAG, "=== Current Domain Context ===");
        Log.d(TAG, "Recently accessed domains (" + recentlyAccessedDomains.size() + "):");
        
        for (String domain : recentlyAccessedDomains) {
            Long accessTime = domainAccessTime.get(domain);
            if (accessTime != null) {
                long ageMs = currentTime - accessTime;
                String type = isSocialMediaPrimary(domain) ? "PRIMARY" : 
                            isSocialMediaDependency(domain) ? "DEPENDENCY" : "OTHER";
                Log.d(TAG, "  " + domain + " (" + type + ") - " + ageMs + "ms ago");
            }
        }
        Log.d(TAG, "==============================");
    }
    
    public boolean testSmartBlocking(String domain) {
        Log.d(TAG, "=== Testing Smart Blocking for: " + domain + " ===");
        
        boolean isPrimary = isSocialMediaPrimary(domain);
        boolean isDependency = isSocialMediaDependency(domain);
        boolean hasContext = isAccessedAsContext(domain);
        boolean socialMediaEnabled = prefs.getBoolean(KEY_BLOCK_SOCIAL_MEDIA, true);
        boolean wouldBlock = isDomainBlocked(domain);
        
        Log.d(TAG, "Domain: " + domain);
        Log.d(TAG, "Is Primary Social Media: " + isPrimary);
        Log.d(TAG, "Is Dependency: " + isDependency);
        Log.d(TAG, "Has Context: " + hasContext);
        Log.d(TAG, "Social Media Blocking Enabled: " + socialMediaEnabled);
        Log.d(TAG, "Final Decision: " + (wouldBlock ? "BLOCK" : "ALLOW"));
        Log.d(TAG, "================================================");
        
        return wouldBlock;
    }
    
    /**
     * Force add a domain to recent context (useful for testing)
     */
    public void addToRecentContext(String domain) {
        updateDomainAccessTracking(domain.toLowerCase());
        Log.d(TAG, "Added " + domain + " to recent context for testing");
    }
    
    /**
     * Clear domain access history (useful for testing)
     */
    public void clearDomainContext() {
        recentlyAccessedDomains.clear();
        domainAccessTime.clear();
        Log.d(TAG, "Cleared domain access context");
    }
}
