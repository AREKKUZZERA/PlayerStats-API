package com.plp.statsplugin;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token-bucket rate limiter per IP address.
 *
 * <p>Each IP gets {@code maxRequests} tokens per {@code windowMillis}-ms window.
 * A request is allowed when the bucket has ≥ 1 token; otherwise HTTP 429.
 *
 * <p>Thread-safety: all state mutations go through a single {@code AtomicInteger}
 * CAS loop — no locks, no race conditions.
 *
 * <p>Memory: stale buckets are evicted lazily on a per-256-call cadence.
 */
public final class RateLimiter {

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    private final int maxRequests;
    private final long windowMillis;
    private final Clock clock;

    public RateLimiter(int maxRequests, long windowMillis) {
        this(maxRequests, windowMillis, Clock.systemUTC());
    }

    /** Package-private — allows injecting a fixed clock in tests. */
    RateLimiter(int maxRequests, long windowMillis, Clock clock) {
        if (maxRequests < 1) throw new IllegalArgumentException("maxRequests must be ≥ 1");
        if (windowMillis < 1) throw new IllegalArgumentException("windowMillis must be ≥ 1");
        this.maxRequests = maxRequests;
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    /**
     * Attempt to consume one token for {@code ip}.
     *
     * @return {@code true} = allowed, {@code false} = rate-limited
     */
    public boolean tryAcquire(String ip) {
        if (ip == null || ip.isBlank()) return true;

        long now = clock.millis();
        Bucket bucket = buckets.compute(ip, (k, b) -> {
            if (b == null || now >= b.windowEnd.get()) {
                return new Bucket(maxRequests, now + windowMillis);
            }
            return b;
        });

        int token = bucket.tokens.decrementAndGet();
        evictStale(now);
        return token >= 0;
    }

    /** Remaining tokens in the current window (0 = exhausted). */
    public int remainingRequests(String ip) {
        if (ip == null) return maxRequests;
        Bucket b = buckets.get(ip);
        if (b == null || clock.millis() >= b.windowEnd.get()) return maxRequests;
        return Math.max(0, b.tokens.get());
    }

    /** Unix-millisecond timestamp when the current window resets for {@code ip}. */
    public long windowResetMillis(String ip) {
        if (ip == null) return clock.millis() + windowMillis;
        Bucket b = buckets.get(ip);
        return (b == null) ? clock.millis() + windowMillis : b.windowEnd.get();
    }

    public int getMaxRequests() {
        return maxRequests;
    }

    public long getWindowMillis() {
        return windowMillis;
    }

    // -------------------------------------------------------------------------

    private void evictStale(long now) {
        // Scan ~every 256 calls to keep memory bounded without a background thread
        if ((now & 0xFF) == 0) {
            buckets.entrySet().removeIf(e -> now >= e.getValue().windowEnd.get());
        }
    }

    // -------------------------------------------------------------------------

    private static final class Bucket {
        /** Remaining tokens; may go negative when exhausted. */
        final AtomicInteger tokens;
        /** Epoch-ms when this window ends and tokens refill. */
        final AtomicLong windowEnd;

        Bucket(int tokens, long windowEnd) {
            this.tokens = new AtomicInteger(tokens);
            this.windowEnd = new AtomicLong(windowEnd);
        }
    }
}
