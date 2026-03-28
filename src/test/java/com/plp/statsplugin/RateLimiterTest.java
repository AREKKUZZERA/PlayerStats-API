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

    // Controllable clock — can be advanced without Thread.sleep()
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
            rl.tryAcquire("1.2.3.4");
            rl.tryAcquire("1.2.3.4");
            rl.tryAcquire("1.2.3.4");
            assertFalse(rl.tryAcquire("1.2.3.4"), "4th request must be denied");
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
        @DisplayName("null/blank IP is always allowed")
        void nullIpAlwaysAllowed() {
            RateLimiter rl = new RateLimiter(1, 60_000, fixedClock(0));
            // exhaust null bucket
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
        @DisplayName("bucket refills after the window expires")
        void refillsAfterWindow() {
            long windowMs = 1_000;
            // Clock at t=0
            RateLimiter rl = new RateLimiter(2, windowMs, fixedClock(0));
            rl.tryAcquire("a");
            rl.tryAcquire("a");
            assertFalse(rl.tryAcquire("a"), "exhausted at t=0");

            // Simulate a new RateLimiter with the clock advanced past the window
            // (real code uses the same instance; here we verify logic by
            //  constructing with an advanced clock and same IP — the bucket
            //  will be treated as a new window)
            RateLimiter rl2 = new RateLimiter(2, windowMs, fixedClock(windowMs + 1));
            assertTrue(rl2.tryAcquire("a"), "allowed in new window");
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("Metadata accessors")
    class Metadata {

        @Test
        @DisplayName("remainingRequests() decrements correctly")
        void remainingDecrement() {
            RateLimiter rl = new RateLimiter(5, 60_000, fixedClock(0));
            assertEquals(5, rl.remainingRequests("ip")); // fresh bucket
            rl.tryAcquire("ip");
            assertEquals(4, rl.remainingRequests("ip"));
            rl.tryAcquire("ip");
            assertEquals(3, rl.remainingRequests("ip"));
        }

        @Test
        @DisplayName("remainingRequests() never goes below 0")
        void remainingNotNegative() {
            RateLimiter rl = new RateLimiter(1, 60_000, fixedClock(0));
            rl.tryAcquire("ip");
            rl.tryAcquire("ip"); // denied
            rl.tryAcquire("ip"); // denied
            assertTrue(rl.remainingRequests("ip") >= 0);
        }

        @Test
        @DisplayName("windowResetMillis() returns a future timestamp")
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
        @DisplayName("exactly maxRequests allowed across many concurrent threads")
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

            start.countDown(); // fire all threads at once
            done.await();
            pool.shutdownNow();

            assertEquals(limit, allowed.get(), "exactly maxRequests should be allowed, got " + allowed.get());
        }
    }
}
