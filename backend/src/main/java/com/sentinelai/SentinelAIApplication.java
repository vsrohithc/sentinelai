package com.sentinelai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import reactor.core.publisher.Hooks;

/**
 * SentinelAI — AI Governance Proxy
 *
 * <p>Entry point for the Spring Boot application. SentinelAI sits between enterprise
 * users and their AI providers (OpenAI, Anthropic, Gemini, Azure OpenAI, etc.) and
 * intercepts every prompt for governance and audit purposes.</p>
 *
 * <h2>Request flow</h2>
 * <ol>
 *   <li>Client sends prompt to POST /api/proxy</li>
 *   <li>ProxyService fires two parallel async WebClient calls:
 *     <ul>
 *       <li>→ target AI model (OpenAI / Anthropic / Gemini API)</li>
 *       <li>→ prompt injection detection API</li>
 *     </ul>
 *   </li>
 *   <li>AI model response is returned to the caller <strong>immediately</strong> — zero added latency</li>
 *   <li>AuditService persists the full record (prompt + response + risk score + metadata)
 *       asynchronously as a background task, after the response has been sent</li>
 * </ol>
 *
 * <h2>Design principles</h2>
 * <ul>
 *   <li><strong>Audit not block</strong>: detection API errors never fail the proxy call;
 *       risk_score is set to null and auditors can review retroactively.</li>
 *   <li><strong>Non-blocking I/O</strong>: all HTTP calls use Spring WebFlux / WebClient.</li>
 *   <li><strong>Self-hosted</strong>: clients deploy in their own cloud; no data leaves their environment.</li>
 * </ul>
 *
 * <h2>Reactor context propagation</h2>
 * <p>{@link Hooks#enableAutomaticContextPropagation()} is called at startup so
 * that {@link org.slf4j.MDC} values (e.g. {@code request_id}) set in a WebFilter
 * are automatically carried across reactor operator boundaries, including thread
 * hops to {@code Schedulers.boundedElastic()} and other scheduler switches.
 * Without this hook, MDC values set on the Netty I/O thread would be silently
 * lost when the chain switches schedulers, producing log lines with no correlation
 * fields in production.</p>
 */
@SpringBootApplication
@EnableAsync        // Required for @Async in AuditService — persists audit records off the hot path
@EnableScheduling   // Required for the nightly log-retention cleanup job (RetentionCleanupService)
public class SentinelAIApplication {

    /**
     * Application entry point. Enables automatic Reactor-to-MDC context propagation
     * before handing off to Spring Boot's auto-configuration.
     *
     * @param args command-line arguments forwarded to Spring Boot (e.g. --server.port=9090)
     */
    public static void main(String[] args) {
        // Propagate ThreadLocal values (including SLF4J MDC) across reactive operator
        // boundaries. Must be called before the Spring context is created so that all
        // WebClient calls and reactive chains benefit from the hook.
        Hooks.enableAutomaticContextPropagation();

        SpringApplication.run(SentinelAIApplication.class, args);
    }
}
