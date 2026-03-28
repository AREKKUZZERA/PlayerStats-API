package com.plp.statsplugin;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Token-bucket rate limiter per IP address.
 *
 * <p>Each IP gets a bucket of {@code maxRequests} tokens that refills fully
 * every {@code windowMillis} milliseconds. A request is allowed when the
 * bucket has ≥ 1 token; otherwise it is rejected with HTTP 429.
 *
 * <p>Old entries are evicted lazily: whenever a bucket is checked we drop
 * entries whose window has already passed, keeping memory bounded without
 * a background thread.
 */
public final class RateLimiter {

    /** One bucket per remote IP. */
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    private final int maxRequests;
    private final long windowMillis;
    private final Clock clock;

    /**
     * @param maxRequests  maximum requests per IP per window (≥ 1)
     * @param windowMillis sliding window length in milliseconds (≥ 1)
     */
    public RateLimiter(int maxRequests, long windowMillis) {
        this(maxRequests, windowMillis, Clock.systemUTC());
    }

    /** Package-private constructor for tests with a controllable clock. */
    RateLimiter(int maxRequests, long windowMillis, Clock clock) {
        if (maxRequests < 1) throw new IllegalArgumentException("maxRequests must be ≥ 1");
        if (windowMillis < 1) throw new IllegalArgumentException("windowMillis must be ≥ 1");
        this.maxRequests  = maxRequests;
        this.windowMillis = windowMillis;
        this.clock        = clock;
    }

    /**
     * Attempt to consume one token for the given IP.
     *
     * @param ip remote address string (e.g. "192.168.1.1")
     * @return {@code true} if the request is allowed, {@code false} if rate-limited
     */
    public boolean tryAcquire(String ip) {
        if (ip == null || ip.isBlank()) return true; // safety: never block unknown IPs

        long now = clock.millis();
        Bucket bucket = buckets.compute(ip, (k, existing) -> {
            if (existing == null || now >= existing.windowEnd) {
                // New window: fresh bucket with one token already consumed
                return new Bucket(maxRequests - 1, now + windowMillis);
            }
            return existing;
        });

        // If we just created (or reset) the bucket, the request was already counted in compute()
        if (bucket.windowEnd == now + windowMillis && bucket.remaining.get() == maxRequests - 1) {
            evictStale(now);
            return true;
        }

        // Existing bucket — try to consume a token
        int left = bucket.remaining.getAndDecrement();
        evictStale(now);
        return left > 0;
    }

    /** Returns how many requests remain in the current window for {@code ip}. */
    public int remainingRequests(String ip) {
        if (ip == null) return maxRequests;
        Bucket b = buckets.get(ip);
        if (b == null || clock.millis() >= b.windowEnd) return maxRequests;
        return Math.max(0, b.remaining.get());
    }

    /** Returns the epoch-millisecond timestamp when the window resets for {@code ip}. */
    public long windowResetMillis(String ip) {
        if (ip == null) return clock.millis() + windowMillis;
        Bucket b = buckets.get(ip);
        return (b == null) ? clock.millis() + windowMillis : b.windowEnd;
    }

    public int getMaxRequests()  { return maxRequests; }
    public long getWindowMillis() { return windowMillis; }

    // -------------------------------------------------------------------------

    /** Remove buckets whose window has already expired (lazy GC). */
    private void evictStale(long now) {
        // Only scan occasionally to avoid per-request overhead
        if ((now & 0xFF) == 0) { // ~every 256 ms on average
            buckets.entrySet().removeIf(e -> now >= e.getValue().windowEnd);
        }
    }

    // -------------------------------------------------------------------------

    private static final class Bucket {
        final AtomicInteger remaining;
        final long windowEnd;

        Bucket(int remaining, long windowEnd) {
            this.remaining = new AtomicInteger(remaining);
            this.windowEnd = windowEnd;
        }
    }
}
