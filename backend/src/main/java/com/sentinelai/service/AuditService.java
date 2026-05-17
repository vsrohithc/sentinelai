package com.sentinelai.service;

import com.sentinelai.model.PromptLog;
import com.sentinelai.repository.PromptLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service responsible for persisting audit records to the {@code prompt_logs} table.
 *
 * <h2>Critical design constraint: async-only writes</h2>
 * <p>The {@link #save(PromptLog)} method is annotated {@code @Async} so it
 * executes on a background thread from Spring's task executor, not on the
 * Reactor event-loop thread that handles the HTTP request. This means:</p>
 * <ol>
 *   <li>The HTTP response is sent to the caller <em>before</em> the database
 *       write completes — zero latency added by persistence.</li>
 *   <li>A slow or failing database write never causes a proxy request to fail
 *       or time out from the caller's perspective.</li>
 * </ol>
 *
 * <p>The consequence is that audit records may be missing or delayed if the
 * application crashes immediately after returning the response. This is an
 * accepted trade-off for the zero-latency design — the alternative (synchronous
 * write before response) would add database RTT to every single AI call.</p>
 *
 * <h2>Error handling</h2>
 * <p>Persistence failures are logged as errors but are not re-thrown because
 * there is no caller to propagate to (the HTTP response has already been sent).
 * Operators should monitor {@code ERROR} log events from this class to detect
 * persistence issues.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final PromptLogRepository promptLogRepository;
    private final SigningService signingService;

    /**
     * Persists a completed {@link PromptLog} audit record asynchronously.
     *
     * <p>This method is called by {@link ProxyService} after the proxy response
     * has been returned to the caller. Because it is {@code @Async}, the calling
     * thread (the Reactor scheduler) returns immediately and this method runs on
     * a separate thread from the {@code @EnableAsync} task executor configured
     * by Spring Boot.</p>
     *
     * <p>The {@code @Transactional} annotation ensures the save is committed
     * atomically. If the save fails, the transaction is rolled back and the
     * exception is caught and logged here — it does not propagate back to the
     * HTTP request.</p>
     *
     * @param promptLog the fully populated audit record to persist; must have
     *                  {@code id}, {@code requestTime}, {@code model}, and
     *                  {@code prompt} set at minimum
     */
    @Async
    @Transactional
    public void save(PromptLog promptLog) {
        try {
            promptLog.setSignature(signingService.sign(promptLog));
            promptLogRepository.save(promptLog);
            log.debug("Persisted audit record id={} model={} riskScore={}",
                    promptLog.getId(), promptLog.getModel(), promptLog.getRiskScore());
        } catch (Exception ex) {
            // Log at ERROR so operators are alerted to persistence failures.
            // Cannot re-throw because the HTTP response has already been sent.
            log.error("Failed to persist audit record id={}: {}",
                    promptLog.getId(), ex.getMessage(), ex);
        }
    }
}
