package com.org.llm.deepagent.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Outbound base-URL checks applied by {@link UrlAllowlistValidator} at startup.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.security.ssrf")
public class SsrfProperties {

    /**
     * Also reject loopback and private-range base URLs (127.0.0.0/8, 10/8, 172.16/12,
     * 192.168/16, fc00::/7). Off by default: the platform services normally run on localhost
     * or on a private Docker/Kubernetes network. Turn it on when every downstream is public.
     */
    private boolean blockPrivateNetworks = false;
}
