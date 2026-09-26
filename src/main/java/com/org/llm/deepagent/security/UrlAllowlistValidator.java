package com.org.llm.deepagent.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * Validates outbound HTTP URLs to prevent SSRF (Server-Side Request Forgery).
 * Call {@link #validate} at startup (e.g. in a {@code @PostConstruct}) for every
 * configured base URL before the first HTTP call is made.
 * <p>
 * Wildcard, link-local (which includes the 169.254.169.254 cloud metadata endpoint) and multicast
 * addresses are always rejected. Loopback and private ranges are rejected only when
 * {@link SsrfProperties#isBlockPrivateNetworks()} is on, because the platform services normally
 * run on localhost or a private network. Every address the host resolves to is checked.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UrlAllowlistValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final SsrfProperties properties;

    /**
     * Validates that {@code url} has an allowed scheme, a non-blank host, and resolves only to
     * allowed addresses. Throws {@link IllegalArgumentException} on any violation so the
     * application fails fast at startup rather than at runtime.
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
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(
                    "SSRF | " + fieldName + " host cannot be resolved: " + host, e);
        }
        for (InetAddress address : addresses) {
            String blocked = blockedAddressClass(address);
            if (blocked != null) {
                throw new IllegalArgumentException("SSRF | " + fieldName + " resolves to a " + blocked
                        + " address: " + host + " -> " + address.getHostAddress());
            }
        }
        log.debug("SSRF | URL validated: {} = {}", fieldName, url);
    }

    private String blockedAddressClass(InetAddress address) {
        if (address.isAnyLocalAddress()) {
            return "wildcard";
        }
        if (address.isLinkLocalAddress()) {
            return "link-local";
        }
        if (address.isMulticastAddress()) {
            return "multicast";
        }
        if (properties.isBlockPrivateNetworks()) {
            if (address.isLoopbackAddress()) {
                return "loopback";
            }
            if (address.isSiteLocalAddress() || isUniqueLocalIpv6(address)) {
                return "private";
            }
        }
        return null;
    }

    /** fc00::/7 — the IPv6 counterpart of RFC 1918, which {@link InetAddress#isSiteLocalAddress()} does not cover. */
    private static boolean isUniqueLocalIpv6(InetAddress address) {
        return address instanceof Inet6Address && (address.getAddress()[0] & 0xFE) == 0xFC;
    }
}
