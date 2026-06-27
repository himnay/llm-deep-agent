package com.org.llm.deepagent.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * Validates outbound HTTP URLs to prevent SSRF (Server-Side Request Forgery).
 * Call {@link #validate} at startup (e.g. in a {@code @PostConstruct}) for every
 * configured base URL before the first HTTP call is made.
 */
@Slf4j
@Component
public class UrlAllowlistValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /**
     * Validates that {@code url} has an allowed scheme and a non-blank host.
     * Throws {@link IllegalArgumentException} on any violation so the application
     * fails fast at startup rather than at runtime.
     *
     * @param url       the URL to validate
     * @param fieldName human-readable name used in error messages
     */
    public void validate(String url, String fieldName) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("SSRF | " + fieldName + " must not be blank");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException(
                    "SSRF | " + fieldName + " is not a valid URI: " + url, ex);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            throw new IllegalArgumentException(
                    "SSRF | " + fieldName + " scheme '" + scheme + "' is not allowed (http/https only)");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(
                    "SSRF | " + fieldName + " has no host component: " + url);
        }
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                    || addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
                throw new IllegalArgumentException(
                        "SSRF | " + fieldName + " resolves to a private/reserved address: " + host);
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(
                    "SSRF | " + fieldName + " host cannot be resolved: " + host, e);
        }
        log.debug("SSRF | URL validated: {} = {}", fieldName, url);
    }
}
