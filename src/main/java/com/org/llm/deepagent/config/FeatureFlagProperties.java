package com.org.llm.deepagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.features")
public class FeatureFlagProperties {
    private boolean humanApprovalEnabled = true;
    private boolean subAgentDelegationEnabled = true;
    private boolean ragRetrievalEnabled = true;
    private boolean auditLoggingEnabled = true;
    private boolean stepCompactionEnabled = true;
    private boolean longTermMemoryEnabled = false;
}
