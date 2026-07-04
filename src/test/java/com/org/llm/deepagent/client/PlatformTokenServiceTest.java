package com.org.llm.deepagent.client;

import com.org.llm.deepagent.exception.TokenAcquisitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PlatformTokenServiceTest {

    private final PlatformAuthProperties properties = new PlatformAuthProperties();

    {
        properties.setTokenUri("http://keycloak.test/realms/llm-gateway/protocol/openid-connect/token");
        properties.setClientId("llm-orchestrator-client");
        properties.setClientSecret("dev-secret");
    }

    @Test
    @DisplayName("getToken() fetches a token from Keycloak then caches it until near expiry")
    void getTokenFetchesAndCachesUntilNearExpiry() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server
                .expect(requestTo(properties.getTokenUri()))
                .andExpect(method(HttpMethod.POST))
                .andRespond(
                        withSuccess(
                                "{\"access_token\":\"tok-1\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));

        PlatformTokenService service = new PlatformTokenService(builder, properties);

        assertThat(service.getToken()).isEqualTo("tok-1");
        assertThat(service.getToken())
                .isEqualTo("tok-1"); // second call must hit the cache, not the server
        server.verify();
    }

    @Test
    @DisplayName("getToken() throws TokenAcquisitionException when the response has no access_token")
    void getTokenThrowsWhenResponseHasNoAccessToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server
                .expect(requestTo(properties.getTokenUri()))
                .andRespond(withSuccess("{\"expires_in\":3600}", MediaType.APPLICATION_JSON));

        PlatformTokenService service = new PlatformTokenService(builder, properties);

        assertThatThrownBy(service::getToken).isInstanceOf(TokenAcquisitionException.class);
    }
}
