package telegram.files;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 (D-shutdown BLOCKING fix): the shutdown drain must AWAIT in-flight worker/callback DB work
 * before the pool closes, so no write can execute against a closed pool after Start.close().
 * <p>
 * These tests exercise {@link TelegramVerticle}'s outstanding-operation tracker + drain directly
 * (via package-private test seams) — no live TDLib client required.
 */
class TelegramVerticleDrainTest {

    static Vertx vertx;

    @BeforeAll
    static void setUp() {
        new java.io.File(Config.LOG_PATH).mkdirs();
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void tearDown() {
        vertx.close();
    }

    @Test
    @DisplayName("drain awaits an in-flight tracked DB operation before completing (no write after pool close)")
    void drainAwaitsInFlightOperation() throws Exception {
        TelegramVerticle v = new TelegramVerticle("account/root-123");

        // Model an in-flight worker/callback DB write that completes only when released.
        Promise<Void> slowWrite = Promise.promise();
        AtomicBoolean writeCompleted = new AtomicBoolean(false);
        Future<Void> tracked = v.trackOperationForTest(slowWrite.future()
                .onSuccess(_ -> writeCompleted.set(true)));

        assertEquals(1, v.outstandingOperationCountForTest(), "operation must be tracked while in flight");

        // Begin the drain (models shutdown). It MUST NOT complete while the write is still pending.
        Future<Void> drain = v.drainOutstandingOperationsForTest();
        assertTrue(v.isDrainingForTest(), "drain must mark the verticle as draining");

        AtomicBoolean drainCompleted = new AtomicBoolean(false);
        AtomicLong drainCompletedAtWriteState = new AtomicLong(-1);
        drain.onComplete(_ -> {
            drainCompleted.set(true);
            // Capture whether the write had completed at the instant the drain resolved.
            drainCompletedAtWriteState.set(writeCompleted.get() ? 1 : 0);
        });

        // Give the drain a chance to (wrongly) complete early.
        Thread.sleep(200);
        assertFalse(drainCompleted.get(), "drain must NOT complete while a tracked write is still in flight");

        // Release the write; now the drain may complete — AND only after the write finished.
        slowWrite.complete();
        await(drain, 5, TimeUnit.SECONDS);

        assertTrue(writeCompleted.get(), "the tracked write must have completed");
        assertEquals(1, drainCompletedAtWriteState.get(),
                "the drain must complete only AFTER the tracked write — no write may outlive the drain");
        assertTrueEventually(() -> v.outstandingOperationCountForTest() == 0,
                "the tracker must be empty after the operation completes");
        assertNotNull(tracked);
    }

    @Test
    @DisplayName("after draining begins, a new operation is NOT tracked (no post-shutdown pool work)")
    void noNewTrackingAfterDrain() {
        TelegramVerticle v = new TelegramVerticle("account/root-456");
        // Start the drain with nothing in flight (completes immediately) — sets draining=true.
        await(v.drainOutstandingOperationsForTest(), 5, TimeUnit.SECONDS);
        assertTrue(v.isDrainingForTest());

        // A new operation registered after the drain must NOT be added to the tracker.
        Promise<Void> late = Promise.promise();
        v.trackOperationForTest(late.future());
        assertEquals(0, v.outstandingOperationCountForTest(),
                "no operation may be tracked after draining begins (it would race past the drain to a closed pool)");
        late.complete();
    }

    @Test
    @DisplayName("onFileUpdated DB write is tracked: the drain awaits an in-flight file-update status write")
    void fileUpdateWriteIsTracked() throws Exception {
        TelegramVerticle v = newVerticleWithVertx("account/root-fu-1");

        // Override the file-update persist with a controllable-latency write (no real DB / native
        // client). This exercises the REAL onFileUpdated gate+track wrapper — the exact path a live
        // TDLib UpdateFile takes — around a write we can hold open.
        Promise<Void> statusWrite = Promise.promise();
        AtomicBoolean writeCompleted = new AtomicBoolean(false);
        v.setFileUpdatePersistOverrideForTest(() -> statusWrite.future().onSuccess(_ -> writeCompleted.set(true)));

        // A live file update arrives (models a download-progress UpdateFile).
        v.handleFileUpdateForTest(updateFile("uid-fu-1"));
        assertEquals(1, v.outstandingOperationCountForTest(),
                "the onFileUpdated status write must be tracked as an outstanding operation");

        // Shutdown drain begins WHILE the file-update write is in flight — it must not complete yet.
        Future<Void> drain = v.drainOutstandingOperationsForTest();
        AtomicBoolean drainCompleted = new AtomicBoolean(false);
        AtomicLong writeStateAtDrain = new AtomicLong(-1);
        drain.onComplete(_ -> {
            drainCompleted.set(true);
            writeStateAtDrain.set(writeCompleted.get() ? 1 : 0);
        });

        Thread.sleep(200);
        assertFalse(drainCompleted.get(),
                "drain must NOT complete while the in-flight file-update write is pending");

        // Release the write; the drain may now complete — and only after the write finished.
        statusWrite.complete();
        await(drain, 5, TimeUnit.SECONDS);
        assertTrue(writeCompleted.get(), "the file-update write must have completed");
        assertEquals(1, writeStateAtDrain.get(),
                "the drain must complete only AFTER the file-update write — no write may outlive the drain");
    }

    @Test
    @DisplayName("onFileUpdated is gated on draining: a file update arriving after shutdown starts no new DB write")
    void fileUpdateGatedOnDraining() throws Exception {
        TelegramVerticle v = newVerticleWithVertx("account/root-fu-2");

        AtomicBoolean persistInvoked = new AtomicBoolean(false);
        v.setFileUpdatePersistOverrideForTest(() -> {
            persistInvoked.set(true);
            return Future.succeededFuture();
        });

        // Begin the drain (models shutdown) — sets draining=true.
        await(v.drainOutstandingOperationsForTest(), 5, TimeUnit.SECONDS);
        assertTrue(v.isDrainingForTest());

        // A late file update MUST NOT start a new DB write (nor track one).
        v.handleFileUpdateForTest(updateFile("uid-fu-2"));
        assertFalse(persistInvoked.get(),
                "no file-update DB write may START once draining has begun (it would hit a closing/closed pool)");
        assertEquals(0, v.outstandingOperationCountForTest(),
                "no file-update operation may be tracked after draining begins");
    }

    /** A minimal TdApi.UpdateFile carrying a remote uniqueId, as delivered by TDLib. */
    private static org.drinkless.tdlib.TdApi.UpdateFile updateFile(String uniqueId) {
        org.drinkless.tdlib.TdApi.File file = new org.drinkless.tdlib.TdApi.File();
        file.id = 1;
        file.remote = new org.drinkless.tdlib.TdApi.RemoteFile();
        file.remote.uniqueId = uniqueId;
        file.local = new org.drinkless.tdlib.TdApi.LocalFile();
        return new org.drinkless.tdlib.TdApi.UpdateFile(file);
    }

    /** A TelegramVerticle with its {@code vertx} bound (so onFileUpdated's event publish works). */
    private static TelegramVerticle newVerticleWithVertx(String rootPath) throws Exception {
        TelegramVerticle v = new TelegramVerticle(rootPath);
        java.lang.reflect.Field vertxField = io.vertx.core.AbstractVerticle.class.getDeclaredField("vertx");
        vertxField.setAccessible(true);
        vertxField.set(v, vertx);
        return v;
    }

    private static void await(Future<?> f, long timeout, TimeUnit unit) {
        CompletableFuture<Void> cf = new CompletableFuture<>();
        f.onComplete(ar -> cf.complete(null));
        try {
            cf.get(timeout, unit);
        } catch (Exception e) {
            fail("future did not complete within timeout: " + e);
        }
    }

    private static void assertTrueEventually(java.util.function.BooleanSupplier cond, String msg) {
        for (int i = 0; i < 50; i++) {
            if (cond.getAsBoolean()) return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        fail(msg);
    }
}
