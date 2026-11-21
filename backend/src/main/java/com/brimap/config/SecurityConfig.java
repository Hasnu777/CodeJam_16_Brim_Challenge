package com.brimap.config;

import com.brimap.audit.AuditService;
import com.brimap.auth.Auth;
import com.brimap.auth.CurrentUser;
import com.brimap.model.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * Server-side role enforcement, layer 1 (the filter chain):
 *  - /api/auth/users and /api/auth/login are public (demo user switcher); everything else under /api needs a session.
 *  - Auditor is read-only: any non-GET request from an Auditor is rejected here, before any controller runs.
 * Layer 2 is @PreAuthorize on every controller method; layer 3 is scope/segregation-of-duties checks in services.
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(AppProperties.class)
public class SecurityConfig {

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AuditService audit, ObjectMapper json,
                                    SecurityContextRepository contextRepository) throws Exception {
        http
                // Session cookie is SameSite=Lax and the API only accepts JSON/multipart from same origin (see decisions D-017).
                .csrf(AbstractHttpConfigurer::disable)
                .securityContext(c -> c.securityContextRepository(contextRepository))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/api/auth/users").permitAll()
                        .requestMatchers("/api/auth/login", "/api/auth/logout").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(r -> !READ_METHODS.contains(r.getMethod()) && r.getRequestURI().startsWith("/api/"))
                        .access((auth, ctx) -> new AuthorizationDecision(auth.get().isAuthenticated()
                                && !(auth.get().getPrincipal() instanceof CurrentUser u && u.role() == Role.AUD)))
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> {
                            audit.recordDenial(null, req.getMethod(), req.getRequestURI(), "Not logged in");
                            write(json, res, 401, "Not logged in. POST /api/auth/login with a userId first.");
                        })
                        .accessDeniedHandler((req, res, ex) -> {
                            String reason = denialReason(req);
                            audit.recordDenial(Auth.actorId(), req.getMethod(), req.getRequestURI(), reason);
                            write(json, res, 403, reason);
                        }));
        return http.build();
    }

    private static String denialReason(HttpServletRequest req) {
        return Auth.current()
                .map(u -> u.role() == Role.AUD && !READ_METHODS.contains(req.getMethod())
                        ? "Auditors have read-only access; " + req.getMethod() + " is not allowed"
                        : "Role " + u.role() + " is not allowed to " + req.getMethod() + " " + req.getRequestURI())
                .orElse("Not logged in");
    }

    static void write(ObjectMapper json, HttpServletResponse res, int status, String reason) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        json.writeValue(res.getOutputStream(), Map.of("status", status, "error", status == 401 ? "Unauthorized" : "Forbidden", "reason", reason));
    }
}
