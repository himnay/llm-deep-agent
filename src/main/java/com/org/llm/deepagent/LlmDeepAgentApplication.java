package com.org.llm.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LlmOrchestratorApplication {

  static void main(String[] args) {
    SpringApplication.run(LlmOrchestratorApplication.class, args);
  }
}
