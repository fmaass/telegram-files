package telegram.files;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 (D7) scheduler single-flight / in-flight guard. {@link SingleFlight} backs the process-wide
 * orphaned-attempt reconciliation coalescing: a slow pass must not start a second concurrent pass,
 * and concurrent callers coalesce onto the one in-flight future.
 */
class SingleFlightTest {

    @Test
    @DisplayName("a long pass does not start a second concurrent pass: action runs once while in flight")
    void coalescesWhileInFlight() {
        SingleFlight<Integer> sf = new SingleFlight<>();
        AtomicInteger invocations = new AtomicInteger(0);
        Promise<Integer> gate = Promise.promise();

        // First call launches the action (which does not complete until we release the gate).
        Future<Integer> a = sf.run(() -> {
            invocations.incrementAndGet();
            return gate.future();
        });
        assertTrue(sf.isInFlight(), "the pass must be in flight after the first call");

        // While in flight, further calls MUST NOT invoke the action again — they share the pending future.
        Future<Integer> b = sf.run(() -> {
            invocations.incrementAndGet();
            return Future.succeededFuture(-1);
        });
        Future<Integer> c = sf.run(() -> {
            invocations.incrementAndGet();
            return Future.succeededFuture(-2);
        });

        assertEquals(1, invocations.get(), "action must run exactly once while a pass is in flight");
        assertSame(a, b, "concurrent callers must share the same in-flight future");
        assertSame(a, c, "concurrent callers must share the same in-flight future");

        // Complete the pass; the shared future resolves with the real result.
        gate.complete(7);
        assertEquals(7, MessyUtils.await(a));
        assertEquals(7, MessyUtils.await(b));
        assertFalse(sf.isInFlight(), "the guard must clear once the pass completes");
    }

    @Test
    @DisplayName("after a pass completes, the next call starts a fresh pass")
    void freshPassAfterCompletion() {
        SingleFlight<Integer> sf = new SingleFlight<>();
        AtomicInteger invocations = new AtomicInteger(0);

        MessyUtils.await(sf.run(() -> {
            invocations.incrementAndGet();
            return Future.succeededFuture(1);
        }));
        MessyUtils.await(sf.run(() -> {
            invocations.incrementAndGet();
            return Future.succeededFuture(2);
        }));

        assertEquals(2, invocations.get(), "a completed pass must not block the next one");
    }

    @Test
    @DisplayName("a failed pass also clears the guard so the next call can retry")
    void guardClearsOnFailure() {
        SingleFlight<Integer> sf = new SingleFlight<>();
        AtomicInteger invocations = new AtomicInteger(0);

        Future<Integer> failed = sf.run(() -> {
            invocations.incrementAndGet();
            return Future.failedFuture(new RuntimeException("boom"));
        });
        assertTrue(failed.failed());
        assertFalse(sf.isInFlight(), "the guard must clear even on failure");

        sf.run(() -> {
            invocations.incrementAndGet();
            return Future.succeededFuture(1);
        });
        assertEquals(2, invocations.get(), "a failed pass must not permanently block reconciliation");
    }
}
