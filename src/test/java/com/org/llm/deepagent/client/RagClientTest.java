package com.org.llm.deepagent.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import com.org.llm.deepagent.security.UrlAllowlistValidator;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RagClientTest {

    private final RagProperties properties = new RagProperties();
    private final PlatformTokenService tokenService = mock(PlatformTokenService.class);
    private final UrlAllowlistValidator urlValidator = new UrlAllowlistValidator();

    {
        properties.setBaseUrl("http://rag.test/api/v1");
        when(tokenService.getToken()).thenReturn("bearer-token");
    }

    @Test
    @DisplayName("retrieve() posts to /retrieve and parses the returned chunks and citations")
    void retrievePostsToSlashRetrieveAndParsesChunksAndCitations() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server
                .expect(requestTo("http://rag.test/api/v1/retrieve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer bearer-token"))
                .andExpect(jsonPath("$.query").value("leave policy"))
                .andRespond(
                        withSuccess(
                                "{\"chunks\":[{\"source\":\"hr.pdf\",\"content\":\"20 days/year\",\"metadata\":{},\"chunkIndex\":0}],"
                                        + "\"citations\":[{\"source\":\"hr.pdf\",\"fileName\":\"hr.pdf\",\"identity\":\"hr\",\"page\":1,\"chunkIndex\":0,\"score\":0.9}]}",
                                MediaType.APPLICATION_JSON));

        RagClient client = new RagClient(builder, properties, tokenService, urlValidator);
        var result = client.retrieve("leave policy", 5);

        assertThat(result.chunks()).hasSize(1);
        assertThat(result.citations()).hasSize(1);
        server.verify();
    }

    @Test
    @DisplayName("generate() posts to /generate and returns the generated answer")
    void generatePostsToSlashGenerateAndReturnsTheAnswer() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server
                .expect(requestTo("http://rag.test/api/v1/generate"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(
                        withSuccess(
                                "{\"answer\":\"Up to 20 days/year.\",\"citations\":[],\"faithful\":true,"
                                        + "\"fromSemanticCache\":false,\"insufficientContext\":false}",
                                MediaType.APPLICATION_JSON));

        RagClient client = new RagClient(builder, properties, tokenService, urlValidator);
        var response = client.generate("what is the leave policy?", 5, "session-1");

        assertThat(response.answer()).isEqualTo("Up to 20 days/year.");
        assertThat(response.insufficientContext()).isFalse();
        server.verify();
    }
}
