package com.eonmux.cadetcoder.util;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Utility class for URL operations including validation, normalization, and correction.
 */
public class URLUtils {

    // Pattern to match URLs without protocol
    private static final Pattern URL_WITHOUT_PROTOCOL = Pattern.compile(
            "^([a-zA-Z0-9][-a-zA-Z0-9]*\\.)+[a-zA-Z0-9][-a-zA-Z0-9]*(/.*)?$"
    );

    // Pattern to match placeholder URLs
    private static final Pattern PLACEHOLDER_URL = Pattern.compile(
            "example\\.com|placeholder\\.com|example\\.org|example\\.net|" +
            "domain\\.com|yourdomain\\.com|site\\.com|website\\.com|" +
            "url\\.com|<url>|\\[url\\]|\\{url\\}|URL_HERE"
    );

    // Pattern to match common URL typos
    private static final Pattern URL_TYPO = Pattern.compile(
            "^(htps?://|htttp://|htpps://|https;//|http;//).*"
    );

    /**
     * Validates a URL string.
     * 
     * @param urlString The URL string to validate
     * @return ValidationResult containing validation status and message
     */
    public static ValidationResult validateURL(String urlString) {
        if (urlString == null || urlString.trim().isEmpty()) {
            return new ValidationResult(false, "URL cannot be empty");
        }

        // Check for placeholder URLs
        if (isPlaceholderURL(urlString)) {
            return new ValidationResult(false, "Placeholder URL detected: " + urlString);
        }

        // Try to normalize the URL first
        String normalizedURL = normalizeURL(urlString);
        
        try {
            // Try to create a URI (more strict validation than URL)
            URI uri = new URI(normalizedURL);
            
            // Check if the URI has a scheme
            if (uri.getScheme() == null) {
                return new ValidationResult(false, "URL must have a protocol (http:// or https://)");
            }
            
            // Check if the scheme is http or https
            if (!uri.getScheme().equals("http") && !uri.getScheme().equals("https")) {
                return new ValidationResult(false, "URL must use http or https protocol");
            }
            
            // Check if the URI has a host
            if (uri.getHost() == null || uri.getHost().isEmpty()) {
                return new ValidationResult(false, "URL must have a valid host");
            }
            
            // Additional validation for the host
            String host = uri.getHost();
            if (!host.contains(".") || host.startsWith(".") || host.endsWith(".")) {
                return new ValidationResult(false, "URL must have a valid domain name");
            }
            
            // Try to create a URL (to catch other issues)
            new URL(normalizedURL);
            
            return new ValidationResult(true, "Valid URL");
        } catch (URISyntaxException e) {
            return new ValidationResult(false, "Invalid URL format: " + e.getMessage());
        } catch (MalformedURLException e) {
            return new ValidationResult(false, "Malformed URL: " + e.getMessage());
        }
    }

    /**
     * Normalizes a URL string by adding protocol if missing, fixing common typos, etc.
     * 
     * @param urlString The URL string to normalize
     * @return The normalized URL string
     */
    public static String normalizeURL(String urlString) {
        if (urlString == null || urlString.trim().isEmpty()) {
            return "";
        }
        
        String url = urlString.trim();
        
        // Fix common typos in protocol
        Matcher typoMatcher = URL_TYPO.matcher(url);
        if (typoMatcher.matches()) {
            url = "https://" + url.substring(url.indexOf("//") + 2);
        }
        
        // Add https:// if protocol is missing
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            // Check if it looks like a URL without protocol
            if (URL_WITHOUT_PROTOCOL.matcher(url).matches()) {
                url = "https://" + url;
            }
        }
        
        // Remove trailing slashes
        while (url.endsWith("/") && url.length() > 8) { // Don't remove if it's just http:// or https://
            url = url.substring(0, url.length() - 1);
        }
        
        // Remove fragments for API calls
        if (url.contains("#") && (url.contains("/api/") || url.contains("/v1/") || url.contains("/v2/"))) {
            url = url.substring(0, url.indexOf("#"));
        }
        
        return url;
    }

    /**
     * Checks if a URL string is a placeholder URL.
     * 
     * @param urlString The URL string to check
     * @return true if the URL is a placeholder, false otherwise
     */
    public static boolean isPlaceholderURL(String urlString) {
        if (urlString == null || urlString.trim().isEmpty()) {
            return false;
        }
        
        String url = urlString.toLowerCase();
        
        // Check for placeholder URLs
        return PLACEHOLDER_URL.matcher(url).find() ||
               url.contains("example") && url.contains("domain") ||
               url.contains("placeholder") ||
               url.contains("yoursite") ||
               url.contains("your-site") ||
               url.contains("your_site");
    }

    /**
     * Attempts to substitute a placeholder URL with a real URL.
     * 
     * @param urlString The placeholder URL string
     * @return A suggested real URL or the original URL if no substitution is found
     */
    public static String substitutePlaceholderURL(String urlString) {
        if (!isPlaceholderURL(urlString)) {
            return urlString;
        }
        
        String url = urlString.toLowerCase();
        
        // Common substitutions for placeholder URLs
        if (url.contains("example.com")) {
            return url.replace("example.com", "mozilla.org");
        } else if (url.contains("placeholder")) {
            return "https://www.wikipedia.org";
        } else if (url.contains("yoursite") || url.contains("your-site") || url.contains("your_site")) {
            return "https://www.github.com";
        } else {
            // Default substitution
            return "https://www.google.com";
        }
    }

    /**
     * Represents the result of URL validation.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String message;
        
        public ValidationResult(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }
        
        public boolean isValid() {
            return valid;
        }
        
        public String getMessage() {
            return message;
        }
    }
}