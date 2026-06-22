package com.org.llm.orchestrator.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.org.llm.orchestrator.agent.AgentAction;
import com.org.llm.orchestrator.agent.PlannedAction;
import com.org.llm.orchestrator.client.RagClient;
import com.org.llm.orchestrator.client.dto.RagGenerateResponse;
import com.org.llm.orchestrator.client.dto.RagRetrievalResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RagRoutingStrategyTest {

  private final RagClient ragClient = mock(RagClient.class);
  private final RagRoutingStrategy strategy = new RagRoutingStrategy(ragClient, new ObjectMapper());

  @Test
  @DisplayName("supports() matches both RAG_RETRIEVE and RAG_GENERATE")
  void supportsBothRagActions() {
    assertThat(strategy.supports(AgentAction.RAG_RETRIEVE)).isTrue();
    assertThat(strategy.supports(AgentAction.RAG_GENERATE)).isTrue();
    assertThat(strategy.supports(AgentAction.GATEWAY_LLM)).isFalse();
  }

  @Test
  @DisplayName("RAG_RETRIEVE serializes the retrieval result as the observation")
  void retrieveSerializesResult() {
    when(ragClient.retrieve("leave policy", 5))
        .thenReturn(new RagRetrievalResult(List.of(), List.of()));

    StepResult result =
        strategy.execute(
            new AgentContext(1L, "s1"),
            new PlannedAction(AgentAction.RAG_RETRIEVE, null, "leave policy", null));

    assertThat(result.success()).isTrue();
    assertThat(result.observation()).contains("chunks").contains("citations");
  }

  @Test
  @DisplayName("RAG_GENERATE returns the answer when context was sufficient")
  void generateReturnsAnswer() {
    when(ragClient.generate("what is the leave policy?", 5, "s1"))
        .thenReturn(new RagGenerateResponse("Up to 20 days/year.", List.of(), true, false, false));

    StepResult result =
        strategy.execute(
            new AgentContext(1L, "s1"),
            new PlannedAction(AgentAction.RAG_GENERATE, null, "what is the leave policy?", null));

    assertThat(result.success()).isTrue();
    assertThat(result.observation()).isEqualTo("Up to 20 days/year.");
  }

  @Test
  @DisplayName("RAG_GENERATE flags insufficient context in the observation")
  void generateFlagsInsufficientContext() {
    when(ragClient.generate("unknown topic", 5, null))
        .thenReturn(new RagGenerateResponse("I don't know.", List.of(), false, false, true));

    StepResult result =
        strategy.execute(
            new AgentContext(1L, null),
            new PlannedAction(AgentAction.RAG_GENERATE, null, "unknown topic", null));

    assertThat(result.success()).isTrue();
    assertThat(result.observation()).contains("insufficient context");
  }
}
