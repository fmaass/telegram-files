package telegram.files;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.templates.SqlTemplate;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import telegram.files.http.ApiException;
import telegram.files.repository.FileRecord;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real-Postgres tests for the Phase-5 API BOUNDARY guard {@link TelegramVerticle#guardedFileOp}: the
 * second layer over the Phase-2 CAS that enforces {@code canTransitionTo} (409 on an illegal
 * transition), 404 on an absent row, and the destructive-op refusal for {@code processed}/
 * {@code imported} (externally-owned) files. The guard's {@code action} is a no-op probe so these
 * tests exercise ONLY the boundary decision (no TDLib), against the real schema.
 */
class ApiBoundaryPostgresTest extends PostgresIntegrationTest {

    private Future<Void> deploy(Vertx vertx) {
        return assertReachedPostgres(vertx)
                .compose(v -> vertx.deployVerticle(new DataVerticle()))
                .mapEmpty();
    }

    private Future<Void> insertRow(String uniqueId, int id, long telegramId, String status) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("uniqueId", uniqueId);
        p.put("telegramId", telegramId);
        p.put("status", status);
        return SqlTemplate.forUpdate(DataVerticle.pool, """
                        INSERT INTO file_record
                            (id, unique_id, telegram_id, chat_id, message_id, date, has_sensitive_content, size,
                             downloaded_size, type, download_status, transfer_status, start_date, scan_state,
                             download_priority)
                        VALUES
                            (#{id}, #{uniqueId}, #{telegramId}, 100, 200, 1000, false, 1000, 0,
                             'file', #{status}, 'idle', NULL, 'idle', 0)
                        """)
                .execute(p).mapEmpty();
    }

    /** A bare TelegramVerticle instance is enough — guardedFileOp only touches the repository. */
    private TelegramVerticle bareVerticle() {
        return new TelegramVerticle("/tmp/root-42");
    }

    @Test
    @DisplayName("guard: 404 when the file row is absent")
    void guardNotFound(Vertx vertx, VertxTestContext ctx) {
        AtomicBoolean actionRan = new AtomicBoolean(false);
        deploy(vertx)
                .compose(v -> bareVerticle().guardedFileOp("nope", FileRecord.DownloadStatus.paused, false,
                        rec -> {
                            actionRan.set(true);
                            return Future.succeededFuture("x");
                        }))
                .onComplete(ar -> ctx.verify(() -> {
                    Assertions.assertTrue(ar.failed(), "absent row must fail");
                    Assertions.assertInstanceOf(ApiException.class, ar.cause());
                    Assertions.assertEquals(404, ((ApiException) ar.cause()).getStatusCode());
                    Assertions.assertFalse(actionRan.get(), "action must NOT run for a missing row");
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("guard: 409 on an illegal transition (idle -> paused), action not run")
    void guardIllegalTransition(Vertx vertx, VertxTestContext ctx) {
        String uid = "uid-illegal";
        AtomicBoolean actionRan = new AtomicBoolean(false);
        deploy(vertx)
                .compose(v -> insertRow(uid, 1, 10, "idle"))
                // idle -> paused is illegal per canTransitionTo.
                .compose(v -> bareVerticle().guardedFileOp(uid, FileRecord.DownloadStatus.paused, false,
                        rec -> {
                            actionRan.set(true);
                            return Future.succeededFuture("x");
                        }))
                .onComplete(ar -> ctx.verify(() -> {
                    Assertions.assertTrue(ar.failed(), "illegal transition must fail");
                    Assertions.assertInstanceOf(ApiException.class, ar.cause());
                    Assertions.assertEquals(409, ((ApiException) ar.cause()).getStatusCode());
                    Assertions.assertFalse(actionRan.get(), "action must NOT run on an illegal transition");
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("guard: legal transition (downloading -> paused) runs the action")
    void guardLegalTransition(Vertx vertx, VertxTestContext ctx) {
        String uid = "uid-legal";
        AtomicBoolean actionRan = new AtomicBoolean(false);
        deploy(vertx)
                .compose(v -> insertRow(uid, 1, 10, "downloading"))
                .compose(v -> bareVerticle().guardedFileOp(uid, FileRecord.DownloadStatus.paused, false,
                        rec -> {
                            actionRan.set(true);
                            return Future.succeededFuture("ok");
                        }))
                .onComplete(ar -> ctx.verify(() -> {
                    Assertions.assertTrue(ar.succeeded(), "legal transition must succeed: "
                            + (ar.cause() == null ? "" : ar.cause().getMessage()));
                    Assertions.assertEquals("ok", ar.result());
                    Assertions.assertTrue(actionRan.get(), "action MUST run on a legal transition");
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("guard: destructive op REFUSED on a processed (externally-owned) file -> 409")
    void guardDestructiveRefusedProcessed(Vertx vertx, VertxTestContext ctx) {
        String uid = "uid-processed";
        AtomicBoolean actionRan = new AtomicBoolean(false);
        deploy(vertx)
                .compose(v -> insertRow(uid, 1, 10, "processed"))
                // A destructive op (cancel/remove) targeting idle: must be refused because the file is
                // processed and owned by external services — never mind the transition legality.
                .compose(v -> bareVerticle().guardedFileOp(uid, FileRecord.DownloadStatus.idle, true,
                        rec -> {
                            actionRan.set(true);
                            return Future.succeededFuture("x");
                        }))
                .onComplete(ar -> ctx.verify(() -> {
                    Assertions.assertTrue(ar.failed(), "destructive op on processed must fail");
                    Assertions.assertInstanceOf(ApiException.class, ar.cause());
                    Assertions.assertEquals(409, ((ApiException) ar.cause()).getStatusCode());
                    Assertions.assertFalse(actionRan.get(), "must NOT destroy an externally-owned file");
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("guard: destructive op REFUSED on an imported file -> 409")
    void guardDestructiveRefusedImported(Vertx vertx, VertxTestContext ctx) {
        String uid = "uid-imported";
        deploy(vertx)
                .compose(v -> insertRow(uid, 1, 10, "imported"))
                .compose(v -> bareVerticle().guardedFileOp(uid, null, true,
                        rec -> Future.succeededFuture("x")))
                .onComplete(ar -> ctx.verify(() -> {
                    Assertions.assertTrue(ar.failed());
                    Assertions.assertEquals(409, ((ApiException) ar.cause()).getStatusCode());
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("guard: NON-destructive op is allowed on processed when the transition is legal")
    void guardNonDestructiveAllowedOnProcessed(Vertx vertx, VertxTestContext ctx) {
        // processed -> processed (self) is legal and non-destructive: must NOT be blocked by the
        // externally-owned guard (which only fires for destructive ops).
        String uid = "uid-processed-read";
        AtomicBoolean actionRan = new AtomicBoolean(false);
        deploy(vertx)
                .compose(v -> insertRow(uid, 1, 10, "processed"))
                .compose(v -> bareVerticle().guardedFileOp(uid, FileRecord.DownloadStatus.processed, false,
                        rec -> {
                            actionRan.set(true);
                            return Future.succeededFuture("ok");
                        }))
                .onComplete(ar -> ctx.verify(() -> {
                    Assertions.assertTrue(ar.succeeded(), "non-destructive self-transition allowed");
                    Assertions.assertTrue(actionRan.get());
                    ctx.completeNow();
                }));
    }
}
