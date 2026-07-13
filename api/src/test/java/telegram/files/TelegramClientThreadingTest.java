package telegram.files;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 (D5) timeout and cancellation for {@link TelegramClient}.
 * <ul>
 *   <li><b>timeout</b>: a never-completing call fails with {@link TimeoutException} within the bound;</li>
 *   <li><b>cancellation on close</b>: outstanding request promises are REJECTED when the client
 *       closes (rejectAllOutstanding), not left hanging.</li>
 * </ul>
 */
class TelegramClientThreadingTest {

    static Vertx vertx;

    @BeforeAll
    static void setUp() {
        // TelegramClient's static initializer opens a TDLib log stream at Config.LOG_PATH; ensure the
        // directory exists before the class is first referenced (parallel-suite isolation).
        new java.io.File(Config.LOG_PATH).mkdirs();
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void tearDown() {
        vertx.close();
    }

    @Test
    @DisplayName("timeout: a never-completing call fails with TimeoutException within the bound")
    void neverCompletingCallTimesOut() {
        // A promise that is never completed models a hung TDLib op.
        Promise<TdApi.Ok> hung = Promise.promise();

        Future<TdApi.Ok> wrapped = TelegramClient.withTimeout(hung.future(), 200, vertx);

        Throwable cause = awaitFailure(wrapped, 2, TimeUnit.SECONDS);
        assertNotNull(cause, "the wrapped future must fail on timeout, not hang");
        assertInstanceOf(TimeoutException.class, cause, "timeout must fail with TimeoutException");
    }

    @Test
    @DisplayName("timeout: a call that completes before the bound mirrors the source result")
    void completingCallMirrorsResult() {
        Promise<TdApi.Ok> src = Promise.promise();
        Future<TdApi.Ok> wrapped = TelegramClient.withTimeout(src.future(), 5_000, vertx);
        TdApi.Ok ok = new TdApi.Ok();
        src.complete(ok);
        assertSame(ok, MessyUtils.await(wrapped), "a completed source must be mirrored, not timed out");
    }

    @Test
    @DisplayName("timeout boundary: completing just under the bound mirrors; completing after the bound times out")
    void timeoutBoundary() {
        // JUST UNDER: source completes well before the timeout -> the result is mirrored.
        {
            Promise<TdApi.Ok> src = Promise.promise();
            Future<TdApi.Ok> wrapped = TelegramClient.withTimeout(src.future(), 1_000, vertx);
            TdApi.Ok ok = new TdApi.Ok();
            // Fire the completion on the event loop after a short delay well under the 1s bound.
            vertx.setTimer(50, _ -> src.complete(ok));
            assertSame(ok, MessyUtils.await(wrapped),
                    "a source completing under the bound must be mirrored, not timed out");
        }
        // JUST OVER: source completes only AFTER the bound has already fired -> TimeoutException wins,
        // and a later source completion does not flip the already-failed result.
        {
            Promise<TdApi.Ok> src = Promise.promise();
            Future<TdApi.Ok> wrapped = TelegramClient.withTimeout(src.future(), 150, vertx);
            vertx.setTimer(400, _ -> src.complete(new TdApi.Ok())); // completes AFTER the timeout
            Throwable cause = awaitFailure(wrapped, 2, TimeUnit.SECONDS);
            assertInstanceOf(TimeoutException.class, cause,
                    "a source completing after the bound must still time out");
        }
    }

    @Test
    @DisplayName("cancellation on close: an outstanding request promise is rejected, not left hanging")
    void outstandingRejectedOnClose() {
        // FAKE send (never fires the callback) — no native TDLib client is created, so there is no
        // native receive thread to race at teardown. The Java-side lifecycle is what we test.
        TelegramClient client = new TelegramClient();
        client.initializeForTest(null, (query, handler) -> { /* never completes */ });

        // Register a genuinely-pending request the same way execute() tracks one.
        Promise<TdApi.Chats> pending = Promise.promise();
        client.trackForTest(pending);
        assertTrue(client.outstandingCount() >= 1, "the request should be tracked as outstanding");

        RuntimeException cause = new IllegalStateException("client closed for test");
        client.rejectAllOutstanding(cause);

        Throwable failure = awaitFailure(pending.future(), 3, TimeUnit.SECONDS);
        assertNotNull(failure, "an outstanding request must be REJECTED on close, not hang");
        assertSame(cause, failure, "the promise must fail with the close cause");
        assertEquals(0, client.outstandingCount(), "outstanding map must be drained after close");
        assertTrue(client.isClosed(), "client must be marked closed");

        // A new send after close fails fast rather than tracking a promise that will never fire.
        Future<TdApi.Chats> afterClose = client.execute(new TdApi.GetChats(new TdApi.ChatListMain(), 10));
        assertTrue(afterClose.failed() || awaitFailure(afterClose, 2, TimeUnit.SECONDS) != null,
                "execute after close must fail fast");
    }

    @Test
    @DisplayName("execute/close race: a request in flight when close fires is rejected, not left hanging, and is not completed post-close")
    void executeCloseRace() throws Exception {
        // FAKE send (never completes) — no native client, no receive-thread race at teardown.
        TelegramClient client = new TelegramClient();
        client.initializeForTest(null, (query, handler) -> { /* never completes */ });

        // A genuinely-pending request (deterministic seam — the fake send never fires the callback).
        Promise<TdApi.Chats> inFlight = Promise.promise();
        long id = client.trackForTest(inFlight);
        assertEquals(1, client.outstandingCount());

        // Close fires WHILE the request is in flight.
        RuntimeException cause = new IllegalStateException("closed mid-flight");
        client.rejectAllOutstanding(cause);

        // The in-flight promise is rejected with the close cause.
        Throwable failure = awaitFailure(inFlight.future(), 3, TimeUnit.SECONDS);
        assertSame(cause, failure, "an in-flight request must be rejected with the close cause");

        // A late native callback arriving AFTER close must NOT re-complete the already-rejected
        // promise (removeAndComplete is idempotent by id). Simulate the late callback by completing
        // the same promise — Vert.x makes the second completion a no-op, and the observed result must
        // remain the rejection, never a post-close success.
        assertThrows(IllegalStateException.class, () -> inFlight.complete(new TdApi.Chats()),
                "a rejected promise must reject a late duplicate completion");
        assertTrue(inFlight.future().failed(), "the result must remain the close rejection, not a post-close value");
        assertSame(cause, inFlight.future().cause());
        assertEquals(0, client.outstandingCount());
        // The id is no longer tracked, so a late native result for it is a harmless no-op.
        assertFalse(client.isTrackedForTest(id), "the request id must be removed from the outstanding map");
    }

    private static Throwable awaitFailure(Future<?> future, long timeout, TimeUnit unit) {
        CompletableFuture<Throwable> cf = new CompletableFuture<>();
        future.onComplete(ar -> {
            if (ar.succeeded()) {
                cf.complete(null);
            } else {
                cf.complete(ar.cause());
            }
        });
        try {
            return cf.get(timeout, unit);
        } catch (Exception e) {
            if (e instanceof CompletionException ce) {
                return ce.getCause();
            }
            return null;
        }
    }
}
