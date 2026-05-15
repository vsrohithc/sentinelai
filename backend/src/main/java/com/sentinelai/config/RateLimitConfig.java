package com.sentinelai.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Rate limiting configuration for the SentinelAI proxy endpoint.
 *
 * <h2>Algorithm: Token Bucket</h2>
 * <p>Bucket4j implements the token-bucket algorithm. Each "bucket" holds a
 * fixed number of tokens. Each request consumes one token. Tokens refill at a
 * configurable rate. When a bucket is empty the request is rejected with
 * HTTP 429 Too Many Requests.</p>
 *
 * <h2>Per-IP bucketing</h2>
 * <p>A separate bucket is maintained per client IP address so that one heavy
 * caller cannot exhaust the global rate limit and affect other callers. Buckets
 * are created lazily on first request and stored in a Caffeine cache.</p>
 *
 * <h2>Memory safety — Caffeine eviction</h2>
 * <p>The bucket store is a Caffeine {@link Cache} rather than a bare
 * {@code ConcurrentHashMap}. Two eviction policies are applied simultaneously:</p>
 * <ul>
 *   <li><strong>Size cap</strong> ({@value #MAX_ENTRIES} entries) — prevents
 *       unbounded growth under a flood of unique source IPs (e.g. a distributed
 *       DDoS). When the cache is full, Caffeine evicts the least-recently-used
 *       entry before inserting a new one.</li>
 *   <li><strong>TTL eviction</strong> (1 hour idle) — buckets for IPs that stop
 *       sending traffic are automatically removed. A returning IP gets a fresh
 *       bucket with full capacity, which is the desired behaviour (a client that
 *       was quiet for an hour should not be penalised for old activity).</li>
 * </ul>
 *
 * <h2>Multi-node deployment</h2>
 * <p>Caffeine is a single-JVM cache. In a multi-node deployment each node
 * maintains independent buckets, so the effective rate limit per IP is
 * {@code capacity × nodeCount}. For true distributed rate limiting, replace
 * with Redis-backed Bucket4j ({@code bucket4j-redis} module).</p>
 *
 * <h2>Configuration</h2>
 * <pre>
 * sentinel.rate-limit.capacity          — max tokens per bucket (default 60)
 * sentinel.rate-limit.refill-per-minute — tokens added per minute (default 60)
 * </pre>
 *
 * <p>At the defaults, each IP can fire up to 60 burst requests and is then
 * limited to ~1 request per second on average.</p>
 */
@Slf4j
@Configuration
public class RateLimitConfig {

    /**
     * Maximum number of distinct IP entries the cache will hold.
     * Entries beyond this limit are evicted using the W-TinyLFU policy
     * (Caffeine's default, which is near-optimal for most access patterns).
     * Sizing: 100,000 entries × ~2 KB per Bucket4j bucket ≈ 200 MB worst case.
     */
    static final long MAX_ENTRIES = 100_000;

    /**
     * Idle TTL: buckets not accessed for this many hours are evicted.
     * A returning IP after this window gets a fresh full-capacity bucket.
     */
    static final long IDLE_TTL_HOURS = 1;

    /** Maximum tokens in the bucket (controls burst capacity). */
    private final long capacity;

    /** Number of tokens refilled per minute. */
    private final long refillPerMinute;

    /**
     * Caffeine cache of per-IP rate-limit buckets.
     *
     * <p>Initialised in {@link #init()} after construction.
     * Caffeine's {@link Cache#get(Object, java.util.function.Function)} is atomic
     * (equivalent to {@code ConcurrentHashMap.computeIfAbsent}) so no duplicate
     * bucket can be created for the same IP under concurrent requests.</p>
     */
    private Cache<String, Bucket> bucketCache;

    /**
     * Primary constructor — used by Spring via constructor injection with {@code @Value}.
     *
     * <p>Constructor injection is preferred over field injection because it makes
     * dependencies explicit, simplifies unit testing (the class can be constructed
     * without a Spring context), and plays well with Lombok's {@code @RequiredArgsConstructor}.</p>
     *
     * @param capacity        max tokens per bucket; injected from {@code sentinel.rate-limit.capacity}
     * @param refillPerMinute tokens refilled per minute; from {@code sentinel.rate-limit.refill-per-minute}
     */
    public RateLimitConfig(
            @Value("${sentinel.rate-limit.capacity:60}") long capacity,
            @Value("${sentinel.rate-limit.refill-per-minute:60}") long refillPerMinute) {
        this.capacity = capacity;
        this.refillPerMinute = refillPerMinute;
    }

    /**
     * Initialises the Caffeine cache after construction.
     *
     * <p>Called automatically by Spring after bean construction and property
     * injection. The two eviction policies (size cap and idle TTL) are applied
     * simultaneously — whichever triggers first causes the entry to be removed.</p>
     */
    @PostConstruct
    public void init() {
        bucketCache = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .expireAfterAccess(IDLE_TTL_HOURS, TimeUnit.HOURS)
                // recordStats() enables hit/miss/eviction counters accessible via getCacheStats().
                // Negligible overhead; valuable for tuning MAX_ENTRIES in production.
                .recordStats()
                .build();

        log.info("Rate limiter cache initialised: capacity={} refillPerMinute={} "
                        + "maxEntries={} idleTtlHours={}",
                capacity, refillPerMinute, MAX_ENTRIES, IDLE_TTL_HOURS);
    }

    /**
     * Returns the rate-limit bucket for the given client IP address.
     *
     * <p>Creates a new bucket for the IP if one does not exist (or was evicted).
     * The Caffeine {@code get()} call is atomic — concurrent requests from the
     * same IP always receive the same bucket instance.</p>
     *
     * @param clientIp the client's IP address string (from the HTTP request)
     * @return the existing or newly created {@link Bucket} for this IP
     */
    public Bucket resolveBucket(String clientIp) {
        return bucketCache.get(clientIp, ip -> newBucket());
    }

    /**
     * Creates a new Bucket4j bucket with the configured capacity and refill rate.
     *
     * <p>Uses greedy refill: tokens are restored continuously as time passes
     * rather than in a lump sum at the end of each minute. This means a caller
     * that sends 60 requests at minute=0 will start receiving tokens again
     * immediately, not after waiting a full minute.</p>
     *
     * @return a configured {@link Bucket} ready to accept tokens
     */
    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(refillPerMinute, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    /**
     * Returns a snapshot of Caffeine cache statistics for monitoring and tuning.
     *
     * <p>Reported metrics include hit rate, miss count, load time, and eviction
     * count. Wire to a Micrometer gauge in a future phase so these appear in your
     * Datadog / Prometheus metrics dashboard.</p>
     *
     * @return a human-readable cache-stats string from the underlying Caffeine cache
     */
    public String getCacheStats() {
        return bucketCache.stats().toString();
    }
}
