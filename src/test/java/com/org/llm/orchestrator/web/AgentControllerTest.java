package com.org.llm.orchestrator.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.org.llm.orchestrator.agent.AgentAction;
import com.org.llm.orchestrator.agent.AgentLoopExecutor;
import com.org.llm.orchestrator.agent.AgentRun;
import com.org.llm.orchestrator.agent.AgentRunStatus;
import com.org.llm.orchestrator.agent.AgentStep;
import com.org.llm.orchestrator.persistence.AgentRunRepository;
import com.org.llm.orchestrator.web.dto.AgentRunRequest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = {AgentController.class, GlobalExceptionHandler.class},
    excludeAutoConfiguration = {
      SecurityAutoConfiguration.class,
      SecurityFilterAutoConfiguration.class,
      OAuth2ResourceServerAutoConfiguration.class,
      OAuth2ResourceServerWebSecurityAutoConfiguration.class
    })
class AgentControllerTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Autowired private MockMvc mockMvc;
  @MockitoBean private AgentLoopExecutor agentLoopExecutor;
  @MockitoBean private AgentRunRepository agentRunRepository;

  @Test
  @DisplayName("POST /agent/run returns 200 with the run's final answer and steps")
  void runReturnsCompletedRun() throws Exception {
    AgentStep step =
        new AgentStep(1L, 7L, 0, AgentAction.GATEWAY_LLM, null, "hi", "obs", "r", Instant.now());
    AgentRun run =
        new AgentRun(
            7L,
            "s1",
            "hi",
            AgentRunStatus.COMPLETED,
            "final answer",
            List.of(step),
            Instant.now(),
            Instant.now());
    when(agentLoopExecutor.run(eq("hi"), eq("s1"))).thenReturn(run);

    mockMvc
        .perform(
            post("/agent/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new AgentRunRequest("hi", "s1"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.runId").value(7))
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.finalAnswer").value("final answer"))
        .andExpect(jsonPath("$.steps[0].observation").value("obs"));
  }

  @Test
  @DisplayName("POST /agent/run with a blank prompt returns 400")
  void runWithBlankPromptReturns400() throws Exception {
    mockMvc
        .perform(
            post("/agent/run").contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("GET /agent/run/{id} returns 200 with the persisted run when it exists")
  void getRunReturnsPersistedRun() throws Exception {
    AgentRun run =
        new AgentRun(
            7L,
            "s1",
            "hi",
            AgentRunStatus.COMPLETED,
            "final answer",
            List.of(),
            Instant.now(),
            Instant.now());
    when(agentRunRepository.findById(7L)).thenReturn(run);

    mockMvc
        .perform(get("/agent/run/7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.runId").value(7));
  }

  @Test
  @DisplayName("GET /agent/run/{id} returns 404 when the run doesn't exist")
  void getRunReturns404WhenMissing() throws Exception {
    when(agentRunRepository.findById(99L)).thenReturn(null);

    mockMvc.perform(get("/agent/run/99")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("An unexpected exception from the loop executor returns 500")
  void runThrowingReturns500() throws Exception {
    when(agentLoopExecutor.run(any(), any())).thenThrow(new RuntimeException("boom"));

    mockMvc
        .perform(
            post("/agent/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new AgentRunRequest("hi", null))))
        .andExpect(status().isInternalServerError());
  }
}
