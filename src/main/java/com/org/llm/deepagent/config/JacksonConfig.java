package com.org.llm.deepagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 4.1's {@code JacksonAutoConfiguration} now builds a Jackson 3 {@code
 * tools.jackson.databind.json.JsonMapper}, not the legacy Jackson 2 {@link ObjectMapper} this
 * codebase's planner-transcript serialization ({@code RagRoutingStrategy}, {@code
 * AgentLoopExecutor}) is written against — so that type is no longer auto-configured as an
 * injectable bean. This fills the gap explicitly rather than switching every internal serialization
 * call site to the Jackson 3 API.
 */
@Configuration
public class JacksonConfig {

  @Bean
  public ObjectMapper objectMapper() {
    return new ObjectMapper();
  }
}
