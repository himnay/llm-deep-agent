package com.org.llm.deepagent.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UrlAllowlistValidatorTest {

    private final SsrfProperties properties = new SsrfProperties();
    private final UrlAllowlistValidator validator = new UrlAllowlistValidator(properties);

    @ParameterizedTest
    @DisplayName("Default localhost and private base URLs are allowed (local and Docker setups)")
    @ValueSource(strings = {"http://localhost:8080/llm/v1", "http://127.0.0.1:8083", "http://10.1.2.3:8080",
            "http://172.18.0.5:8080", "https://192.168.1.10/rag"})
    void allowsLoopbackAndPrivateByDefault(String url) {
        assertDoesNotThrow(() -> validator.validate(url, "base-url"));
    }

    @ParameterizedTest
    @DisplayName("Cloud metadata, wildcard and multicast addresses are always rejected")
    @ValueSource(strings = {"http://169.254.169.254/latest/meta-data/", "http://0.0.0.0:8080", "http://224.0.0.1",
            "http://[fe80::1]:8080"})
    void alwaysRejectsSsrfTargets(String url) {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(url, "base-url"));
    }

    @ParameterizedTest
    @DisplayName("With block-private-networks on, loopback and private ranges are rejected too")
    @ValueSource(strings = {"http://localhost:8080", "http://10.1.2.3", "http://192.168.1.10", "http://[fd00::1]:8080"})
    void rejectsPrivateNetworksWhenEnabled(String url) {
        properties.setBlockPrivateNetworks(true);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(url, "base-url"));
    }

    @Test
    @DisplayName("A public address stays allowed with block-private-networks on")
    void allowsPublicAddressWhenBlockingPrivateNetworks() {
        properties.setBlockPrivateNetworks(true);
        assertDoesNotThrow(() -> validator.validate("https://8.8.8.8/llm/v1", "base-url"));
    }

    @ParameterizedTest
    @DisplayName("Blank, non-HTTP and hostless URLs are rejected")
    @ValueSource(strings = {" ", "ftp://localhost/file", "file:///etc/passwd", "http:///llm"})
    void rejectsMalformedUrls(String url) {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(url, "base-url"));
    }
}
