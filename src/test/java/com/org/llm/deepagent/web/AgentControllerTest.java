package com.org.llm.deepagent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.org.llm.deepagent.agent.*;
import com.org.llm.deepagent.agent.dto.*;
import com.org.llm.deepagent.exception.InvalidRunStateException;
import com.org.llm.deepagent.persistence.AgentArtifactRepository;
import com.org.llm.deepagent.persistence.AgentRunRepository;
import com.org.llm.deepagent.persistence.AgentTaskRepository;
import com.org.llm.deepagent.web.dto.AgentRunRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private AgentLoopExecutor agentLoopExecutor;
    @MockitoBean
    private AgentRunRepository agentRunRepository;
    @MockitoBean
    private AgentTaskRepository agentTaskRepository;
    @MockitoBean
    private AgentArtifactRepository agentArtifactRepository;
    @MockitoBean
    private RunEventBroadcaster runEventBroadcaster;

    private static void authenticateAs(String principal, GrantedAuthority... authorities) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of(authorities)));
    }

    private static AgentRun ownedRunOf(long id, String createdBy) {
        return new AgentRun(
                id,
                "s1",
                "hi",
                AgentRunStatus.RUNNING,
                null,
                List.of(),
                Instant.now(),
                null,
                null,
                id,
                null,
                0,
                null,
                createdBy,
                0);
    }

    private static AgentRun runOf(
            long id,
            String sessionId,
            String prompt,
            AgentRunStatus status,
            String finalAnswer,
            List<AgentStep> steps) {
        return new AgentRun(
                id,
                sessionId,
                prompt,
                status,
                finalAnswer,
                steps,
                Instant.now(),
                Instant.now(),
                null,
                id,
                null,
                0,
                null,
                null,
                0);
    }

    @AfterEach
    void clearSecurityContext() {
        // Security auto-config is excluded from this slice, so nothing else resets the ThreadLocal
        // SecurityContext between tests that set one explicitly (the ownership tests below).
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("POST /agent/run starts the run and returns 200 with status RUNNING")
    void runStartsAsynchronouslyAndReturnsRunning() throws Exception {
        AgentRun run = runOf(7L, "s1", "hi", AgentRunStatus.RUNNING, null, List.of());
        when(agentLoopExecutor.startRun(eq("hi"), eq("s1"), any())).thenReturn(run);

        mockMvc
                .perform(
                        post("/agent/run")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(new AgentRunRequest("hi", "s1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(7))
                .andExpect(jsonPath("$.status").value("RUNNING"));
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
        AgentStep step =
                new AgentStep(1L, 7L, 0, AgentAction.GATEWAY_LLM, null, "hi", "obs", "r", Instant.now());
        AgentRun run = runOf(7L, "s1", "hi", AgentRunStatus.COMPLETED, "final answer", List.of(step));
        when(agentRunRepository.findById(7L)).thenReturn(run);

        mockMvc
                .perform(get("/agent/run/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(7))
                .andExpect(jsonPath("$.finalAnswer").value("final answer"))
                .andExpect(jsonPath("$.steps[0].observation").value("obs"));
    }

    @Test
    @DisplayName("GET /agent/run/{id} returns 404 when the run doesn't exist")
    void getRunReturns404WhenMissing() throws Exception {
        when(agentRunRepository.findById(99L)).thenReturn(null);

        mockMvc.perform(get("/agent/run/99")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /agent/run/{id} returns 403 when the caller isn't the run's creator")
    void getRunReturns403ForNonOwner() throws Exception {
        AgentRun run = ownedRunOf(7L, "owner-1");
        when(agentRunRepository.findById(7L)).thenReturn(run);
        authenticateAs("someone-else");

        mockMvc.perform(get("/agent/run/7")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /agent/run/{id} allows ROLE_ADMIN to access a run it doesn't own")
    void getRunAllowsAdminRegardlessOfOwnership() throws Exception {
        AgentRun run = ownedRunOf(7L, "owner-1");
        when(agentRunRepository.findById(7L)).thenReturn(run);
        authenticateAs("someone-else", new SimpleGrantedAuthority("ROLE_ADMIN"));

        mockMvc.perform(get("/agent/run/7")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("An unexpected exception from the loop executor returns 500")
    void runThrowingReturns500() throws Exception {
        when(agentLoopExecutor.startRun(any(), any(), any())).thenThrow(new RuntimeException("boom"));

        mockMvc
                .perform(
                        post("/agent/run")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(new AgentRunRequest("hi", null))))
                .andExpect(status().isInternalServerError());
    }

    @Test
    @DisplayName("POST /agent/run/{id}/approve dispatches and returns the resumed run")
    void approveResumesRun() throws Exception {
        AgentRun run = runOf(7L, "s1", "hi", AgentRunStatus.RUNNING, null, List.of());
        when(agentRunRepository.findById(7L)).thenReturn(run);
        when(agentLoopExecutor.approve(eq(7L), any())).thenReturn(run);

        mockMvc
                .perform(post("/agent/run/7/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    @DisplayName("POST /agent/run/{id}/approve on a run that isn't awaiting approval returns 409")
    void approveOnWrongStateReturns409() throws Exception {
        AgentRun run = runOf(7L, "s1", "hi", AgentRunStatus.RUNNING, null, List.of());
        when(agentRunRepository.findById(7L)).thenReturn(run);
        when(agentLoopExecutor.approve(eq(7L), any()))
                .thenThrow(new InvalidRunStateException("not awaiting approval"));

        mockMvc.perform(post("/agent/run/7/approve")).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST /agent/run/{id}/reject feeds the reason back and returns the resumed run")
    void rejectResumesRun() throws Exception {
        AgentRun run = runOf(7L, "s1", "hi", AgentRunStatus.RUNNING, null, List.of());
        when(agentRunRepository.findById(7L)).thenReturn(run);
        when(agentLoopExecutor.reject(eq(7L), any(), eq("too risky"))).thenReturn(run);

        mockMvc
                .perform(
                        post("/agent/run/7/reject")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"too risky\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    @DisplayName("POST /agent/run/{id}/cancel returns the cancelled run")
    void cancelReturnsCancelledRun() throws Exception {
        AgentRun run = runOf(7L, "s1", "hi", AgentRunStatus.RUNNING, null, List.of());
        AgentRun cancelled = runOf(7L, "s1", "hi", AgentRunStatus.CANCELLED, null, List.of());
        when(agentRunRepository.findById(7L)).thenReturn(run);
        when(agentLoopExecutor.cancel(7L)).thenReturn(cancelled);

        mockMvc
                .perform(post("/agent/run/7/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("GET /agent/run/{id}/files/{path} returns the file's content when it exists")
    void getFileReturnsContent() throws Exception {
        AgentRun run = runOf(7L, "s1", "hi", AgentRunStatus.COMPLETED, "done", List.of());
        when(agentRunRepository.findById(7L)).thenReturn(run);
        when(agentArtifactRepository.findByRootRunIdAndPath(7L, "notes.md"))
                .thenReturn(
                        Optional.of(new AgentArtifact(1L, 7L, "notes.md", "hello scratchpad", Instant.now())));

        mockMvc
                .perform(get("/agent/run/7/files/notes.md"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("hello scratchpad"));
    }

    @Test
    @DisplayName("GET /agent/run/{id}/files/{path} returns 404 when the file doesn't exist")
    void getFileReturns404WhenMissing() throws Exception {
        AgentRun run = runOf(7L, "s1", "hi", AgentRunStatus.COMPLETED, "done", List.of());
        when(agentRunRepository.findById(7L)).thenReturn(run);
        when(agentArtifactRepository.findByRootRunIdAndPath(7L, "missing.md"))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/agent/run/7/files/missing.md")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /agent/run/{id}/events returns 404 when the run doesn't exist")
    void streamReturns404WhenMissing() throws Exception {
        when(agentRunRepository.findById(anyLong())).thenReturn(null);

        mockMvc.perform(get("/agent/run/99/events")).andExpect(status().isNotFound());
    }
}
