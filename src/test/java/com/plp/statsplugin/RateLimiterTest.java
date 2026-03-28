package com.plp.statsplugin;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RateLimiter")
class RateLimiterTest {

    private static Clock fixedClock(long epochMillis) {
        return Clock.fixed(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
    }

    // =========================================================================

    @Nested
    @DisplayName("Basic allow / deny")
    class BasicBehavior {

        @Test
        @DisplayName("allows up to maxRequests in one window")
        void allowsUpToMax() {
            RateLimiter rl = new RateLimiter(3, 60_000, fixedClock(0));
            assertTrue(rl.tryAcquire("127.0.0.1"), "request 1");
            assertTrue(rl.tryAcquire("127.0.0.1"), "request 2");
            assertTrue(rl.tryAcquire("127.0.0.1"), "request 3");
        }

        @Test
        @DisplayName("blocks the (maxRequests+1)th request in the same window")
        void blocksOnOverflow() {
            RateLimiter rl = new RateLimiter(3, 60_000, fixedClock(0));
            rl.tryAcquire("1.2.3.4"); // 1
            rl.tryAcquire("1.2.3.4"); // 2
            rl.tryAcquire("1.2.3.4"); // 3 — last allowed
            assertFalse(rl.tryAcquire("1.2.3.4"), "4th request must be denied");
            assertFalse(rl.tryAcquire("1.2.3.4"), "5th request must be denied");
        }

        @Test
        @DisplayName("different IPs have independent buckets")
        void independentBuckets() {
            RateLimiter rl = new RateLimiter(1, 60_000, fixedClock(0));
            assertTrue(rl.tryAcquire("10.0.0.1"));
            assertFalse(rl.tryAcquire("10.0.0.1"), "second from same IP denied");
            assertTrue(rl.tryAcquire("10.0.0.2"), "first from other IP still allowed");
        }

        @Test
        @DisplayName("null/blank IP is always allowed regardless of limit")
        void nullIpAlwaysAllowed() {
            RateLimiter rl = new RateLimiter(1, 60_000, fixedClock(0));
            for (int i = 0; i < 10; i++) {
                assertTrue(rl.tryAcquire(null));
                assertTrue(rl.tryAcquire("  "));
            }
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("Window reset")
    class WindowReset {

        @Test
        @DisplayName("bucket fully exhausts within one window")
        void exhaustsInWindow() {
            RateLimiter rl = new RateLimiter(2, 60_000, fixedClock(0));
            assertTrue(rl.tryAcquire("x"), "1st allowed");
            assertTrue(rl.tryAcquire("x"), "2nd allowed");
            assertFalse(rl.tryAcquire("x"), "3rd denied — exhausted");
        }

        @Test
        @DisplayName("bucket refills after window expires")
        void refillsAfterWindow() {
            long windowMs = 1_000;
            // Window 1 — exhaust
            RateLimiter w1 = new RateLimiter(2, windowMs, fixedClock(0));
            w1.tryAcquire("x");
            w1.tryAcquire("x");
            assertFalse(w1.tryAcquire("x"), "exhausted at t=0");

            // Window 2 — new instance with clock past the window boundary
            RateLimiter w2 = new RateLimiter(2, windowMs, fixedClock(windowMs + 1));
            assertTrue(w2.tryAcquire("x"), "allowed in new window");
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("Metadata accessors")
    class Metadata {

        @Test
        @DisplayName("remainingRequests() decrements correctly after each acquire")
        void remainingDecrement() {
            RateLimiter rl = new RateLimiter(5, 60_000, fixedClock(0));
            // Before first call: unknown bucket → returns maxRequests
            assertEquals(5, rl.remainingRequests("ip"), "full before first call");

            rl.tryAcquire("ip"); // consumes 1 → 4 left
            assertEquals(4, rl.remainingRequests("ip"), "4 after first acquire");

            rl.tryAcquire("ip"); // consumes 1 → 3 left
            assertEquals(3, rl.remainingRequests("ip"), "3 after second acquire");
        }

        @Test
        @DisplayName("remainingRequests() never returns negative")
        void remainingNotNegative() {
            RateLimiter rl = new RateLimiter(1, 60_000, fixedClock(0));
            rl.tryAcquire("ip"); // allowed
            rl.tryAcquire("ip"); // denied
            rl.tryAcquire("ip"); // denied
            assertTrue(rl.remainingRequests("ip") >= 0, "remainingRequests must not be negative");
        }

        @Test
        @DisplayName("windowResetMillis() is within (now, now + window]")
        void windowResetInFuture() {
            long now = 5_000L;
            long window = 60_000L;
            RateLimiter rl = new RateLimiter(10, window, fixedClock(now));
            rl.tryAcquire("ip");
            long reset = rl.windowResetMillis("ip");
            assertTrue(reset > now, "reset must be after now");
            assertTrue(reset <= now + window, "reset must be within one window");
        }

        @Test
        @DisplayName("getMaxRequests() and getWindowMillis() return configured values")
        void configAccessors() {
            RateLimiter rl = new RateLimiter(42, 30_000);
            assertEquals(42, rl.getMaxRequests());
            assertEquals(30_000, rl.getWindowMillis());
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidation {

        @Test
        @DisplayName("throws on maxRequests < 1")
        void throwsOnZeroMax() {
            assertThrows(IllegalArgumentException.class, () -> new RateLimiter(0, 1000));
        }

        @Test
        @DisplayName("throws on windowMillis < 1")
        void throwsOnZeroWindow() {
            assertThrows(IllegalArgumentException.class, () -> new RateLimiter(10, 0));
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("Concurrency")
    class ConcurrencyTest {

        @Test
        @DisplayName("exactly maxRequests threads are allowed under full concurrency")
        void concurrentAllowed() throws InterruptedException {
            int limit = 10;
            int threads = 50;
            RateLimiter rl = new RateLimiter(limit, 60_000, fixedClock(0));

            AtomicInteger allowed = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (rl.tryAcquire("shared-ip")) allowed.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            done.await();
            pool.shutdownNow();

            assertEquals(limit, allowed.get(), "exactly maxRequests should be allowed, got " + allowed.get());
        }
    }
}
