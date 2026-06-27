package com.org.llm.deepagent.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.org.llm.deepagent.exception.TokenAcquisitionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Fetches and caches the Keycloak client-credentials token this service presents to
 * llm-gateway-core and llm-rag-pipeline (both behind the same "llm-gateway" realm). Refreshes 60s
 * before expiry to avoid mid-request failures.
 */
@Slf4j
@Service
public class PlatformTokenService {

    private static final long REFRESH_BUFFER_SECONDS = 60;

    private final PlatformAuthProperties properties;
    private final RestClient restClient;
    private record TokenHolder(String token, Instant expiresAt) {
        boolean isValid(long bufferSeconds) {
            return token != null && Instant.now().isBefore(expiresAt.minusSeconds(bufferSeconds));
        }
    }

    private final ReentrantLock lock = new ReentrantLock();
    private volatile TokenHolder tokenHolder = new TokenHolder(null, Instant.EPOCH);
    public PlatformTokenService(
            RestClient.Builder restClientBuilder, PlatformAuthProperties properties) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
    }

    public String getToken() {
        if (tokenHolder.isValid(REFRESH_BUFFER_SECONDS)) {
            return tokenHolder.token();
        }
        lock.lock();
        try {
            if (tokenHolder.isValid(REFRESH_BUFFER_SECONDS)) {
                return tokenHolder.token();
            }
            return fetchToken();
        } finally {
            lock.unlock();
        }
    }

    private String fetchToken() {
        log.debug("PLATFORM_TOKEN | fetching new client-credentials token");
        String body =
                "grant_type=client_credentials"
                        + "&client_id="
                        + properties.getClientId()
                        + "&client_secret="
                        + properties.getClientSecret();

        TokenResponse response =
                restClient
                        .post()
                        .uri(properties.getTokenUri())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(body)
                        .retrieve()
                        .body(TokenResponse.class);

        if (response == null || response.accessToken() == null) {
            throw new TokenAcquisitionException(
                    "Keycloak token response was null or missing access_token");
        }

        // Single volatile write — eliminates the two-field update race window
        tokenHolder = new TokenHolder(response.accessToken(),
                Instant.now().plusSeconds(response.expiresIn()));
        log.info("PLATFORM_TOKEN | refreshed | expiresInSeconds={}", response.expiresIn());
        return tokenHolder.token();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn) {
    }
}
