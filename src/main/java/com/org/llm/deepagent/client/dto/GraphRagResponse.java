package com.org.llm.deepagent.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Minimal projection of llm-rag-graph's RagResponse.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GraphRagResponse(
        String answer,
        String graphContext,
        List<String> relevantEntities,
        Boolean groundedness) {
}
