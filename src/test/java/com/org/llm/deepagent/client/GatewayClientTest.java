package com.org.llm.deepagent.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GatewayClientTest {

  private final GatewayProperties properties = new GatewayProperties();
  private final PlatformTokenService tokenService = mock(PlatformTokenService.class);

  {
    properties.setBaseUrl("http://gateway.test/llm/v1");
    properties.setProvider("openai");
    when(tokenService.getToken()).thenReturn("bearer-token");
  }

  @Test
  void chatPostsToSlashChatWithBearerTokenAndReturnsTheResponse() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("http://gateway.test/llm/v1/chat"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer bearer-token"))
        .andExpect(jsonPath("$.prompt").value("hello"))
        .andExpect(jsonPath("$.provider").value("OPENAI"))
        .andRespond(
            withSuccess(
                "{\"content\":\"hi there\",\"model\":\"gpt-4o\",\"provider\":\"openai\"}",
                MediaType.APPLICATION_JSON));

    GatewayClient client = new GatewayClient(builder, properties, tokenService);
    var response = client.chat("hello", null, "session-1");

    assertThat(response.content()).isEqualTo("hi there");
    server.verify();
  }

  @Test
  void queryPostsToSlashQuery() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("http://gateway.test/llm/v1/query"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{\"content\":\"planned action\"}", MediaType.APPLICATION_JSON));

    GatewayClient client = new GatewayClient(builder, properties, tokenService);
    var response = client.query("what next?", "system prompt");

    assertThat(response.content()).isEqualTo("planned action");
    server.verify();
  }
}
