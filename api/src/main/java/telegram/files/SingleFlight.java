package telegram.files;

import io.vertx.core.Future;

import java.util.function.Supplier;

/**
 * Process-wide single-flight coalescer (D7): while one invocation is in flight, concurrent callers
 * receive the SAME pending future instead of launching a duplicate. Used to keep the global
 * orphaned-attempt reconciliation single-flight when multiple verticles tick their timers at once.
 * <p>
 * The in-flight future is cleared on completion so the next call starts a fresh pass. Thread-safe.
 */
public class SingleFlight<T> {
    private final Object lock = new Object();

    private Future<T> inFlight;

    /**
     * Run {@code action} unless a previous invocation is still in flight, in which case return that
     * pending future. {@code action} is invoked at most once per in-flight window.
     */
    public Future<T> run(Supplier<Future<T>> action) {
        synchronized (lock) {
            if (inFlight != null) {
                return inFlight;
            }
            Future<T> f = action.get();
            inFlight = f;
            f.onComplete(_ -> {
                synchronized (lock) {
                    if (inFlight == f) {
                        inFlight = null;
                    }
                }
            });
            return f;
        }
    }

    // Package-private for tests: whether a pass is currently in flight.
    boolean isInFlight() {
        synchronized (lock) {
            return inFlight != null;
        }
    }
}
