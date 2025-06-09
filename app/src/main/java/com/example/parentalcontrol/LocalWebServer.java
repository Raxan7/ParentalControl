package com.example.parentalcontrol;

import android.content.Context;
import android.util.Log;

import fi.iki.elonen.NanoHTTPD;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Local web server that serves blocked content warning pages
 * Runs on localhost to redirect blocked sites to warning pages
 */
public class LocalWebServer extends NanoHTTPD {
    private static final String TAG = "LocalWebServer";
    private static final int PORT = 8080;
    private Context context;
    private boolean isRunning = false;

    public LocalWebServer(Context context) {
        super(PORT);
        this.context = context;
    }

    @Override
    public void start() throws IOException {
        super.start();
        isRunning = true;
        Log.i(TAG, "Local web server started on port " + PORT);
    }

    @Override
    public void stop() {
        super.stop();
        isRunning = false;
        Log.i(TAG, "Local web server stopped");
    }

    public boolean isServerRunning() {
        return isRunning;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        String method = session.getMethod().toString();
        
        Log.d(TAG, "Serving request: " + method + " " + uri);

        try {
            if (uri.equals("/blocked") || uri.equals("/")) {
                // Serve the blocked content warning page
                String html = readBlockedPageHtml();
                return newFixedLengthResponse(Response.Status.OK, "text/html", html);
            } else if (uri.equals("/styles.css")) {
                // Serve CSS for the blocked page
                String css = readBlockedPageCss();
                return newFixedLengthResponse(Response.Status.OK, "text/css", css);
            } else if (uri.startsWith("/blocked/")) {
                // Handle specific blocked domain requests
                String domain = uri.substring("/blocked/".length());
                String html = readBlockedPageHtml(domain);
                return newFixedLengthResponse(Response.Status.OK, "text/html", html);
            }
            
            // Default blocked page for any other request
            String html = readBlockedPageHtml();
            return newFixedLengthResponse(Response.Status.OK, "text/html", html);
            
        } catch (Exception e) {
            Log.e(TAG, "Error serving request", e);
            String errorHtml = getErrorPageHtml();
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/html", errorHtml);
        }
    }

    private String readBlockedPageHtml() {
        return readBlockedPageHtml(null);
    }

    private String readBlockedPageHtml(String domain) {
        try {
            InputStream is = context.getAssets().open("blocked_page.html");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            
            String html = sb.toString();
            
            // Replace placeholder with actual domain if provided
            if (domain != null && !domain.isEmpty()) {
                html = html.replace("{{BLOCKED_DOMAIN}}", domain);
            } else {
                html = html.replace("{{BLOCKED_DOMAIN}}", "this website");
            }
            
            return html;
            
        } catch (IOException e) {
            Log.e(TAG, "Error reading blocked page HTML from assets", e);
            return getDefaultBlockedPageHtml(domain);
        }
    }

    private String readBlockedPageCss() {
        try {
            InputStream is = context.getAssets().open("blocked_page.css");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            return sb.toString();
            
        } catch (IOException e) {
            Log.e(TAG, "Error reading CSS from assets", e);
            return getDefaultCss();
        }
    }

    private String getDefaultBlockedPageHtml(String domain) {
        String domainText = domain != null ? domain : "this website";
        return "<!DOCTYPE html>\n" +
               "<html>\n" +
               "<head>\n" +
               "    <title>Content Blocked</title>\n" +
               "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
               "    <style>\n" +
               getDefaultCss() +
               "    </style>\n" +
               "</head>\n" +
               "<body>\n" +
               "    <div class=\"container\">\n" +
               "        <div class=\"warning-icon\">⚠️</div>\n" +
               "        <h1>Content Blocked</h1>\n" +
               "        <div class=\"message\">\n" +
               "            Access to <strong>" + domainText + "</strong> has been blocked by parental controls.\n" +
               "        </div>\n" +
               "        <div class=\"reason\">\n" +
               "            This content is not appropriate or has been restricted by your parent or guardian.\n" +
               "        </div>\n" +
               "        <div class=\"actions\">\n" +
               "            <button onclick=\"window.history.back()\" class=\"btn-back\">Go Back</button>\n" +
               "            <button onclick=\"window.close()\" class=\"btn-close\">Close Tab</button>\n" +
               "        </div>\n" +
               "        <div class=\"info\">\n" +
               "            <p>If you believe this is an error, please contact your parent or guardian.</p>\n" +
               "        </div>\n" +
               "    </div>\n" +
               "</body>\n" +
               "</html>";
    }

    private String getDefaultCss() {
        return "body {\n" +
               "    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Arial, sans-serif;\n" +
               "    margin: 0;\n" +
               "    padding: 20px;\n" +
               "    background: linear-gradient(135deg, #f5f7fa 0%, #c3cfe2 100%);\n" +
               "    min-height: 100vh;\n" +
               "    display: flex;\n" +
               "    align-items: center;\n" +
               "    justify-content: center;\n" +
               "}\n" +
               ".container {\n" +
               "    background: white;\n" +
               "    border-radius: 12px;\n" +
               "    padding: 40px;\n" +
               "    box-shadow: 0 10px 30px rgba(0,0,0,0.1);\n" +
               "    text-align: center;\n" +
               "    max-width: 500px;\n" +
               "    width: 100%;\n" +
               "}\n" +
               ".warning-icon {\n" +
               "    font-size: 48px;\n" +
               "    margin-bottom: 20px;\n" +
               "}\n" +
               "h1 {\n" +
               "    color: #d9534f;\n" +
               "    margin: 0 0 20px 0;\n" +
               "    font-size: 28px;\n" +
               "}\n" +
               ".message {\n" +
               "    font-size: 16px;\n" +
               "    margin-bottom: 15px;\n" +
               "    color: #333;\n" +
               "}\n" +
               ".reason {\n" +
               "    font-size: 14px;\n" +
               "    color: #666;\n" +
               "    margin-bottom: 30px;\n" +
               "}\n" +
               ".actions {\n" +
               "    margin-bottom: 20px;\n" +
               "}\n" +
               ".btn-back, .btn-close {\n" +
               "    background: #007bff;\n" +
               "    color: white;\n" +
               "    border: none;\n" +
               "    padding: 10px 20px;\n" +
               "    border-radius: 6px;\n" +
               "    cursor: pointer;\n" +
               "    margin: 0 5px;\n" +
               "    font-size: 14px;\n" +
               "}\n" +
               ".btn-back:hover, .btn-close:hover {\n" +
               "    background: #0056b3;\n" +
               "}\n" +
               ".info {\n" +
               "    font-size: 12px;\n" +
               "    color: #999;\n" +
               "    border-top: 1px solid #eee;\n" +
               "    padding-top: 15px;\n" +
               "}\n" +
               "@media (max-width: 480px) {\n" +
               "    .container { padding: 20px; margin: 10px; }\n" +
               "    h1 { font-size: 24px; }\n" +
               "}";
    }

    private String getErrorPageHtml() {
        return "<!DOCTYPE html>\n" +
               "<html>\n" +
               "<head><title>Server Error</title></head>\n" +
               "<body>\n" +
               "<h1>Server Error</h1>\n" +
               "<p>An error occurred while processing your request.</p>\n" +
               "</body>\n" +
               "</html>";
    }
}
