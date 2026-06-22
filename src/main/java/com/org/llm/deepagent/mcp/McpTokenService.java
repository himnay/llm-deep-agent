package com.org.llm.orchestrator.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.org.llm.orchestrator.exception.TokenAcquisitionException;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Fetches and caches the Keycloak client-credentials token presented to OAuth2-protected MCP
 * servers (currently: {@code deployment-service}, against llm-mcp's own {@code org-mcp} realm).
 * Refreshes 60s before expiry to avoid mid-request failures.
 */
@Slf4j
@Service
public class McpTokenService {

  private static final long REFRESH_BUFFER_SECONDS = 60;

  private final McpOAuth2Properties properties;
  private final RestClient restClient;
  private final ReentrantLock lock = new ReentrantLock();

  public McpTokenService(RestClient.Builder restClientBuilder, McpOAuth2Properties properties) {
    this.properties = properties;
    this.restClient = restClientBuilder.build();
  }

  private volatile String cachedToken;
  private volatile Instant expiresAt = Instant.EPOCH;

  public String getToken() {
    if (isValid()) {
      return cachedToken;
    }
    lock.lock();
    try {
      if (isValid()) {
        return cachedToken;
      }
      return fetchToken();
    } finally {
      lock.unlock();
    }
  }

  private boolean isValid() {
    return cachedToken != null
        && Instant.now().isBefore(expiresAt.minusSeconds(REFRESH_BUFFER_SECONDS));
  }

  private String fetchToken() {
    log.debug("MCP_TOKEN | fetching new client-credentials token");
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

    cachedToken = response.accessToken();
    expiresAt = Instant.now().plusSeconds(response.expiresIn());
    log.info("MCP_TOKEN | refreshed | expiresInSeconds={}", response.expiresIn());
    return cachedToken;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record TokenResponse(
      @JsonProperty("access_token") String accessToken,
      @JsonProperty("expires_in") long expiresIn) {}
}
