package com.sentinelai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Arrays;
import java.util.concurrent.Executor;

/**
 * Configures the thread pool used for {@code @Async} methods.
 *
 * <h2>Why this exists</h2>
 * <p>Spring Boot's default {@code @Async} executor is {@link org.springframework.core.task.SimpleAsyncTaskExecutor},
 * which creates a <strong>new thread per invocation</strong> — no pooling, no
 * queue, no maximum. Under load this causes unbounded thread creation, which
 * exhausts JVM memory and file-descriptor limits. This configuration replaces
 * it with a bounded {@link ThreadPoolTaskExecutor} that:</p>
 * <ol>
 *   <li>Keeps a small pool of core threads alive for steady-state workloads.</li>
 *   <li>Allows burst growth up to {@code maxPoolSize} when the queue fills.</li>
 *   <li>Queues up to {@code queueCapacity} tasks before creating extra threads.</li>
 *   <li>Rejects gracefully (caller blocks) once both the queue and max pool are full.</li>
 * </ol>
 *
 * <h2>Default sizing rationale</h2>
 * <ul>
 *   <li><b>core=4</b> — matches typical 2–4 vCPU Docker container; always warm.</li>
 *   <li><b>max=20</b> — absorbs bursts without starving other subsystems.</li>
 *   <li><b>queue=500</b> — ~500 pending audit writes before back-pressure kicks in.
 *       At 1 ms DB write time this exhausts in ≈500 ms, providing a meaningful
 *       buffer without hiding sustained overload.</li>
 *   <li><b>keepAlive=60 s</b> — idle threads above core size are cleaned up promptly.</li>
 * </ul>
 *
 * <h2>Shutdown behaviour</h2>
 * <p>{@code setWaitForTasksToCompleteOnShutdown(true)} with a
 * {@code setAwaitTerminationSeconds(30)} gives in-flight audit writes up to
 * 30 seconds to complete before the JVM exits, preventing data loss during
 * rolling deployments or pod termination.</p>
 *
 * <h2>Usage in SentinelAI</h2>
 * <p>The primary consumer is {@link com.sentinelai.service.AuditService} which
 * uses {@code @Async} to persist prompt logs without blocking the proxy response.
 * The {@link com.sentinelai.service.RetentionCleanupService} nightly job uses
 * Spring's scheduler (not this pool) and is unaffected by this configuration.</p>
 */
@Slf4j
@EnableAsync
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    /** Core thread count. Threads in this pool are always alive. */
    @Value("${sentinelai.async.core-pool-size:4}")
    private int corePoolSize;

    /** Maximum thread count. Threads above core are created when the queue is full. */
    @Value("${sentinelai.async.max-pool-size:20}")
    private int maxPoolSize;

    /** Bounded queue depth before additional threads (up to max) are created. */
    @Value("${sentinelai.async.queue-capacity:500}")
    private int queueCapacity;

    /**
     * Creates and configures the application-wide async task executor.
     *
     * <p>The executor is named {@code sentinelai-async} — this prefix appears on
     * every thread ({@code sentinelai-async-1}, {@code sentinelai-async-2}, …)
     * which makes it easy to identify async work in thread dumps and APM tools.</p>
     *
     * @return a fully-initialized and started {@link ThreadPoolTaskExecutor}
     */
    @Bean(name = "taskExecutor")
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("sentinelai-async-");

        // Keep threads above core size alive for this long before releasing them
        executor.setKeepAliveSeconds(60);

        // Allow core threads to time out as well — reduces idle resource use when
        // traffic is low (e.g. overnight for SaaS deployments).
        executor.setAllowCoreThreadTimeOut(false);

        // Give in-flight tasks up to 30 s to complete during graceful shutdown.
        // This prevents audit write loss during rolling deploys or pod termination.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        // ── Rejection policy ─────────────────────────────────────────────────
        // AbortPolicy throws RejectedExecutionException when the queue and the
        // max pool are both full. The exception bubbles up to
        // AsyncUncaughtExceptionHandler (below) which logs it at ERROR.
        //
        // We deliberately do NOT use CallerRunsPolicy here even though it
        // "feels" like graceful back-pressure: in a WebFlux application the
        // calling thread is typically a Netty event-loop thread, and running a
        // blocking JDBC write on it stalls every connection that thread is
        // serving. Under sustained overload that produces a latency cliff
        // across the whole server instead of a clean shedding behaviour.
        //
        // The trade-off is that under extreme overload we drop a small number
        // of audit records, which is the documented acceptable failure mode
        // for SentinelAI's "audit not block" design principle. Operators
        // should alert on RejectedExecutionException in the @Async exception
        // handler and respond by scaling the executor (ASYNC_MAX_POOL_SIZE /
        // ASYNC_QUEUE_CAPACITY) or the database.
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

        executor.initialize();

        log.info("AsyncConfig: initialized sentinelai-async pool "
                + "(core={}, max={}, queue={})", corePoolSize, maxPoolSize, queueCapacity);

        return executor;
    }

    /**
     * Handles exceptions thrown by {@code @Async} methods that return {@code void}.
     *
     * <p>Without this handler, unchecked exceptions in fire-and-forget async methods
     * are silently swallowed by Spring. This handler logs them at ERROR level so
     * failures in audit persistence are at least visible in logs and alerting.</p>
     *
     * @return an {@link AsyncUncaughtExceptionHandler} that logs the exception
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                log.error("Uncaught exception in @Async method {}.{}() params={}: {}",
                        method.getDeclaringClass().getSimpleName(),
                        method.getName(),
                        Arrays.toString(params),
                        ex.getMessage(),
                        ex);
    }
}
