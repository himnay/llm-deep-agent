package com.org.llm.deepagent.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Keycloak OAuth2 resource-server security for the REST API (servlet stack) — same pattern as every
 * other service on the platform: {@code Authorization: Bearer <jwt>} validated against the shared
 * "llm-gateway" realm's JWKS endpoint, with Keycloak's {@code realm_access.roles} claim mapped to
 * {@code ROLE_*} authorities.
 *
 * <p>Auth is <b>enabled by default</b>. Actuator and Swagger/OpenAPI docs stay open; every other
 * route requires a valid token. Set {@code gateway-auth.enabled=false} to open everything for local
 * development.
 */
@Slf4j
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/actuator/**", "/error", "/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
    };

    @Value("${gateway-auth.enabled:true}")
    private boolean authEnabled;

    @Value("${gateway-auth.cors.allowed-origins:}")
    private List<String> allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(
                        headers ->
                                headers
                                        .frameOptions(frame -> frame.deny())
                                        .referrerPolicy(
                                                r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                                        .httpStrictTransportSecurity(
                                                hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000)));

        if (!authEnabled) {
            log.warn("SECURITY | OAuth2 authentication is DISABLED (gateway-auth.enabled=false)");
            return http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
        }

        log.info("SECURITY | OAuth2 (Keycloak) JWT authentication is ENABLED");
        return http.oauth2ResourceServer(
                        oauth2 ->
                                oauth2.jwt(
                                        jwt -> jwt.jwtAuthenticationConverter(keycloakJwtAuthenticationConverter())))
                .authorizeHttpRequests(
                        a -> a.requestMatchers(PUBLIC_PATHS).permitAll().anyRequest().authenticated())
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = allowedOrigins.stream().filter(o -> !o.isBlank()).toList();

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(Arrays.asList("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "Authorization"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private JwtAuthenticationConverter keycloakJwtAuthenticationConverter() {
        JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();
        delegate.setJwtGrantedAuthoritiesConverter(this::keycloakAuthoritiesConverter);
        return delegate;
    }

    @SuppressWarnings("unchecked")
    private Collection<GrantedAuthority> keycloakAuthoritiesConverter(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .map(String::valueOf)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                .toList();
    }
}
