package com.org.llm.deepagent.mcp;

import com.org.llm.deepagent.exception.TokenAcquisitionException;
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

class McpTokenServiceTest {

    private final McpOAuth2Properties properties = new McpOAuth2Properties();

    {
        properties.setTokenUri("http://keycloak.test/realms/org-mcp/protocol/openid-connect/token");
        properties.setClientId("llm-orchestrator");
        properties.setClientSecret("dev-secret");
    }

    @Test
    void getTokenFetchesAndCachesUntilNearExpiry() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server
                .expect(requestTo(properties.getTokenUri()))
                .andExpect(method(HttpMethod.POST))
                .andRespond(
                        withSuccess(
                                "{\"access_token\":\"mcp-tok-1\",\"expires_in\":3600}",
                                MediaType.APPLICATION_JSON));

        McpTokenService service = new McpTokenService(builder, properties);

        assertThat(service.getToken()).isEqualTo("mcp-tok-1");
        assertThat(service.getToken()).isEqualTo("mcp-tok-1");
        server.verify();
    }

    @Test
    void getTokenThrowsWhenResponseHasNoAccessToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server
                .expect(requestTo(properties.getTokenUri()))
                .andRespond(withSuccess("{\"expires_in\":3600}", MediaType.APPLICATION_JSON));

        McpTokenService service = new McpTokenService(builder, properties);

        assertThatThrownBy(service::getToken).isInstanceOf(TokenAcquisitionException.class);
    }
}
