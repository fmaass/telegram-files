package telegram.files;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.templates.SqlTemplate;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import telegram.files.repository.FileRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Real-Postgres integration tests for the Phase-2 DownloadStateMachine: the atomic claim
 * ({@code claimForDownload}), scoped attempt-ownership ({@code transitionOwned}), exact-state CAS,
 * {@code canTransitionTo} enforcement, external-reset tolerance + orphaned-attempt retirement, the
 * unique_id-keyed queue (D2), queued_at renewal (D9), completion_date preservation (D10), and the
 * discovery never-downgrade rule.
 * <p>
 * Runs on the real Postgres 17 fixture ({@link PostgresIntegrationTest}); a silent SQLite fallback
 * fails loudly. Each test deploys a fresh {@link DataVerticle} against the truncated schema.
 */
class DownloadStateMachinePostgresTest extends PostgresIntegrationTest {

    // ---- helpers -------------------------------------------------------------------------------

    private Future<Void> deploy(Vertx vertx) {
        return assertReachedPostgres(vertx)
                .compose(v -> vertx.deployVerticle(new DataVerticle()))
                .mapEmpty();
    }

    /** Insert a minimal file_record with an explicit download_status/start_date/downloaded_size/queued_at. */
    private Future<Void> insertRow(String uniqueId, int id, long telegramId, String status,
                                   Long startDate, long downloadedSize, Long queuedAt, Long completionDate) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("uniqueId", uniqueId);
        p.put("telegramId", telegramId);
        p.put("status", status);
        p.put("startDate", startDate);
        p.put("downloadedSize", downloadedSize);
        p.put("queuedAt", queuedAt);
        p.put("completionDate", completionDate);
        return SqlTemplate.forUpdate(DataVerticle.pool, """
                        INSERT INTO file_record
                            (id, unique_id, telegram_id, chat_id, message_id, date, has_sensitive_content, size, downloaded_size,
                             type, download_status, transfer_status, start_date, completion_date, scan_state,
                             download_priority, queued_at)
                        VALUES
                            (#{id}, #{uniqueId}, #{telegramId}, 100, 200, 1000, false, 1000, #{downloadedSize},
                             'file', #{status}, 'idle', #{startDate}, #{completionDate}, 'idle', 0, #{queuedAt})
                        """)
                .execute(p).mapEmpty();
    }

    private Future<String> statusOf(String uniqueId) {
        return SqlTemplate.forQuery(DataVerticle.pool,
                        "SELECT download_status FROM file_record WHERE unique_id = #{u}")
                .execute(Map.of("u", uniqueId))
                .map(rs -> rs.size() > 0 ? rs.iterator().next().getString("download_status") : null);
    }

    private Future<Row> rowOf(String uniqueId) {
        return SqlTemplate.forQuery(DataVerticle.pool,
                        "SELECT * FROM file_record WHERE unique_id = #{u}")
                .execute(Map.of("u", uniqueId))
                .map(rs -> rs.size() > 0 ? rs.iterator().next() : null);
    }

    private Future<Integer> activeAttemptCount(String uniqueId) {
        return SqlTemplate.forQuery(DataVerticle.pool,
                        "SELECT COUNT(*) AS c FROM download_attempt WHERE unique_id = #{u} AND status = 'active'")
                .execute(Map.of("u", uniqueId))
                .map(rs -> rs.iterator().next().getInteger("c"));
    }

    /** Simulate an external reset-stuck: downloading -> idle, start_date NULL, downloaded_size 0. */
    private Future<Void> externalReset(String uniqueId) {
        return SqlTemplate.forUpdate(DataVerticle.pool, """
                        UPDATE file_record
                        SET download_status = 'idle', start_date = NULL, downloaded_size = 0
                        WHERE unique_id = #{u} AND download_status = 'downloading'
                        """)
                .execute(Map.of("u", uniqueId)).mapEmpty();
    }

    // ---- concurrent claim ----------------------------------------------------------------------

    @Test
    @DisplayName("PG: concurrent claim mints exactly one attempt per row; only the winner proceeds")
    void concurrentClaimSingleWinner(Vertx vertx, VertxTestContext ctx) {
        String uid = "uid-concurrent";
        deploy(vertx)
                .compose(v -> insertRow(uid, 1, 10, "idle", null, 0, null, null))
                .compose(v -> {
                    // 20 workers race to claim the SAME idle row. Exactly one must win.
                    List<Future<String>> claims = new ArrayList<>();
                    for (int i = 0; i < 20; i++) {
                        claims.add(DataVerticle.fileRepository.claimForDownload(1, uid, "worker-" + i));
                    }
                    return Future.all(claims);
                })
                .compose(cf -> {
                    long winners = IntStream.range(0, cf.size())
                            .filter(i -> cf.resultAt(i) != null).count();
                    ctx.verify(() -> Assertions.assertEquals(1, winners,
                            "Exactly one worker must win the claim, got " + winners));
                    return Future.all(statusOf(uid), activeAttemptCount(uid));
                })
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    Assertions.assertEquals("downloading", cf.resultAt(0), "row must be downloading");
                    Assertions.assertEquals(1, (int) cf.resultAt(1), "exactly one ACTIVE attempt");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("PG: a second claim on an already-claimed row is rejected (one active attempt)")
    void secondClaimRejected(Vertx vertx, VertxTestContext ctx) {
        String uid = "uid-second";
        deploy(vertx)
                .compose(v -> insertRow(uid, 1, 10, "idle", null, 0, null, null))
                .compose(v -> DataVerticle.fileRepository.claimForDownload(1, uid, "w1"))
                .compose(a1 -> {
                    Assertions.assertNotNull(a1, "first claim wins");
                    return DataVerticle.fileRepository.claimForDownload(1, uid, "w2");
                })
                .compose(a2 -> {
                    ctx.verify(() -> Assertions.assertNull(a2, "second claim must be rejected"));
                    return activeAttemptCount(uid);
                })
                .onComplete(ctx.succeeding(c -> ctx.verify(() -> {
                    Assertions.assertEquals(1, (int) c, "still exactly one active attempt");
                    ctx.completeNow();
                })));
    }

    // ---- claim atomicity rollback --------------------------------------------------------------

    @Test
    @DisplayName("PG: claim rolls back the idle->downloading update when the attempt INSERT fails")
    void claimAtomicRollbackOnAttemptInsertFailure(Vertx vertx, VertxTestContext ctx) {
        String uid = "uid-atomic";
        // Pre-seed an ACTIVE attempt for this uid so the claim's attempt INSERT violates the
        // one-active-attempt partial unique index. The idle->downloading UPDATE inside the same
        // transaction MUST roll back: the row stays idle (both-or-neither). This proves atomicity
        // without mocking internals -- the injected failure is a real unique-index violation between
        // the UPDATE and the INSERT.
        deploy(vertx)
                .compose(v -> insertRow(uid, 1, 10, "idle", null, 0, null, null))
                .compose(v -> SqlTemplate.forUpdate(DataVerticle.pool, """
                                INSERT INTO download_attempt
                                    (attempt_id, unique_id, lease_owner, lease_expires_at, status, created_at, updated_at)
                                VALUES ('preexisting', #{u}, 'ext', NULL, 'active', 1, 1)
                                """).execute(Map.of("u", uid)))
                .compose(v -> DataVerticle.fileRepository.claimForDownload(1, uid, "w1"))
                .compose(attemptId -> {
                    ctx.verify(() -> Assertions.assertNull(attemptId,
                            "claim must fail (active attempt already exists)"));
                    return Future.all(statusOf(uid), activeAttemptCount(uid));
                })
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    Assertions.assertEquals("idle", cf.resultAt(0),
                            "idle->downloading must have ROLLED BACK -- row must still be idle");
                    Assertions.assertEquals(1, (int) cf.resultAt(1),
                            "only the pre-existing attempt remains (no second active attempt inserted)");
                    ctx.completeNow();
                })));
    }

    // ---- boundary illegal transitions ----------------------------------------------------------

    @Test
    @DisplayName("PG: every illegal download_status transition is rejected (rowCount 0, row unchanged)")
    void illegalTransitionsRejected(Vertx vertx, VertxTestContext ctx) {
        // Drive representative ILLEGAL transitions through updateDownloadStatus; each must leave the
        // row unchanged. (Legal edges are covered by the happy-path claim/transition tests.)
        FileRecord.DownloadStatus[][] illegal = new FileRecord.DownloadStatus[][]{
                {FileRecord.DownloadStatus.idle, FileRecord.DownloadStatus.completed},
                {FileRecord.DownloadStatus.idle, FileRecord.DownloadStatus.paused},
                {FileRecord.DownloadStatus.idle, FileRecord.DownloadStatus.processed},
                {FileRecord.DownloadStatus.idle, FileRecord.DownloadStatus.imported},
                {FileRecord.DownloadStatus.completed, FileRecord.DownloadStatus.downloading},
                {FileRecord.DownloadStatus.completed, FileRecord.DownloadStatus.idle},
                {FileRecord.DownloadStatus.completed, FileRecord.DownloadStatus.paused},
                {FileRecord.DownloadStatus.completed, FileRecord.DownloadStatus.imported},
                {FileRecord.DownloadStatus.processed, FileRecord.DownloadStatus.idle},
                {FileRecord.DownloadStatus.processed, FileRecord.DownloadStatus.completed},
                {FileRecord.DownloadStatus.processed, FileRecord.DownloadStatus.downloading},
                {FileRecord.DownloadStatus.processed, FileRecord.DownloadStatus.error},
                {FileRecord.DownloadStatus.imported, FileRecord.DownloadStatus.idle},
                {FileRecord.DownloadStatus.imported, FileRecord.DownloadStatus.completed},
                {FileRecord.DownloadStatus.imported, FileRecord.DownloadStatus.error},
                {FileRecord.DownloadStatus.imported, FileRecord.DownloadStatus.processed},
                {FileRecord.DownloadStatus.error, FileRecord.DownloadStatus.completed},
                {FileRecord.DownloadStatus.error, FileRecord.DownloadStatus.paused},
                {FileRecord.DownloadStatus.error, FileRecord.DownloadStatus.processed},
                {FileRecord.DownloadStatus.paused, FileRecord.DownloadStatus.completed},
                {FileRecord.DownloadStatus.paused, FileRecord.DownloadStatus.processed},
                {FileRecord.DownloadStatus.downloading, FileRecord.DownloadStatus.processed},
                {FileRecord.DownloadStatus.downloading, FileRecord.DownloadStatus.imported},
        };

        Future<Void> chain = deploy(vertx);
        for (int i = 0; i < illegal.length; i++) {
            final int idx = i;
            final String uid = "uid-illegal-" + i;
            final FileRecord.DownloadStatus from = illegal[i][0];
            final FileRecord.DownloadStatus to = illegal[i][1];
            chain = chain
                    .compose(v -> insertRow(uid, 1, 10, from.name(), null, 0, null, null))
                    .compose(v -> DataVerticle.fileRepository.updateDownloadStatus(1, uid, null, to, null))
                    .compose(res -> statusOf(uid))
                    .compose(after -> {
                        ctx.verify(() -> Assertions.assertEquals(from.name(), after,
                                "Illegal transition #" + idx + " " + from + "->" + to
                                        + " must leave row unchanged, but it became " + after));
                        return Future.<Void>succeededFuture();
                    });
        }
        chain.onComplete(ctx.succeeding(v -> ctx.completeNow()));
    }

    // ---- exact-state CAS: stale writer in a different predecessor state -------------------------

    @Test
    @DisplayName("PG: a stale writer in a different legal predecessor state gets rowCount 0 (no clobber)")
    void staleWriterDifferentPredecessorRejected(Vertx vertx, VertxTestContext ctx) {
        String uid = "uid-stale";
        // Claim (idle->downloading, mints attempt). A stale worker that still believes the row is
        // 'paused' tries paused->completed via its attempt: EXPECTED-from mismatch => rowCount 0.
        deploy(vertx)
                .compose(v -> insertRow(uid, 1, 10, "idle", null, 0, null, null))
                .compose(v -> DataVerticle.fileRepository.claimForDownload(1, uid, "w1"))
                .compose(attemptId -> {
                    Assertions.assertNotNull(attemptId);
                    // Row is actually 'downloading'. Stale writer pins expectedFrom=paused.
                    return DataVerticle.fileRepository.transitionOwned(1, uid, attemptId,
                            FileRecord.DownloadStatus.paused, FileRecord.DownloadStatus.completed, null, null);
                })
                .compose(ok -> {
                    ctx.verify(() -> Assertions.assertFalse(ok, "stale-predecessor transition must fail"));
                    return statusOf(uid);
                })
                .onComplete(ctx.succeeding(s -> ctx.verify(() -> {
                    Assertions.assertEquals("downloading", s, "row must remain downloading (no clobber)");
                    ctx.completeNow();
                })));
    }

    // ---- cross-attempt write rejected ----------------------------------------------------------

    @Test
    @DisplayName("PG: a write from a superseded attempt is rejected even though the state matches")
    void crossAttemptWriteRejected(Vertx vertx, VertxTestContext ctx) {
        String uid = "uid-cross";
        deploy(vertx)
                .compose(v -> insertRow(uid, 1, 10, "idle", null, 0, null, null))
                .compose(v -> DataVerticle.fileRepository.claimForDownload(1, uid, "w1"))
                .compose(realAttempt -> {
                    Assertions.assertNotNull(realAttempt);
                    // A DIFFERENT (bogus/superseded) attempt id tries the correct state transition.
                    String bogusAttempt = "bogus-attempt-id";
                    return DataVerticle.fileRepository.transitionOwned(1, uid, bogusAttempt,
                            FileRecord.DownloadStatus.downloading, FileRecord.DownloadStatus.completed,
                            "/path", System.currentTimeMillis());
                })
                .compose(ok -> {
                    ctx.verify(() -> Assertions.assertFalse(ok, "superseded-attempt write must be rejected"));
                    return statusOf(uid);
                })
                .onComplete(ctx.succeeding(s -> ctx.verify(() -> {
                    Assertions.assertEquals("downloading", s, "row unchanged (state matched but attempt didn't own it)");
                    ctx.completeNow();
                })));
    }

    // ---- external reset tolerated + orphaned attempt retired ------------------------------------

    @Test
    @DisplayName("PG: external reset tolerated; owning CAS no-ops; orphan retired; fresh claim succeeds")
    void externalResetToleratedAndOrphanRetired(Vertx vertx, VertxTestContext ctx) {
        String uid = "uid-reset";
        deploy(vertx)
                .compose(v -> insertRow(uid, 1, 10, "idle", null, 0, null, null))
                .compose(v -> DataVerticle.fileRepository.claimForDownload(1, uid, "w1"))
                .compose(attemptId -> {
                    Assertions.assertNotNull(attemptId);
                    // External service resets downloading->idle (nulls start_date, zeros size).
                    return externalReset(uid).map(attemptId);
                })
                .compose(attemptId ->
                        // The owning worker's CAS pins download_status='downloading' -> now 0 rows,
                        // MUST NOT clobber the external idle state.
                        DataVerticle.fileRepository.transitionOwned(1, uid, attemptId,
                                        FileRecord.DownloadStatus.downloading, FileRecord.DownloadStatus.completed,
                                        "/path", System.currentTimeMillis())
                                .compose(ok -> {
                                    ctx.verify(() -> Assertions.assertFalse(ok, "owning CAS must no-op after external reset"));
                                    return statusOf(uid);
                                }))
                .compose(s -> {
                    ctx.verify(() -> Assertions.assertEquals("idle", s, "external idle preserved (no clobber)"));
                    // The active attempt is now orphaned (row is idle). Reconciliation retires it.
                    return activeAttemptCount(uid);
                })
                .compose(before -> {
                    ctx.verify(() -> Assertions.assertEquals(1, (int) before, "orphan active attempt exists pre-reconcile"));
                    return DataVerticle.fileRepository.retireOrphanedAttempts();
                })
                .compose(retired -> {
                    ctx.verify(() -> Assertions.assertTrue(retired >= 1, "at least one orphan retired"));
                    return activeAttemptCount(uid);
                })
                .compose(after -> {
                    ctx.verify(() -> Assertions.assertEquals(0, (int) after, "no active attempt after retirement"));
                    // A fresh claim must now succeed (not blocked by the stale active attempt).
                    return DataVerticle.fileRepository.claimForDownload(1, uid, "w2");
                })
                .onComplete(ctx.succeeding(fresh -> ctx.verify(() -> {
                    Assertions.assertNotNull(fresh, "fresh claim succeeds after orphan retirement");
                    ctx.completeNow();
                })));
    }

    // ---- completion via the PRODUCTION completion path (TDLib DEDUP design) ---------------------

    @Test
    @DisplayName("PG: a completion arriving after external reset does NOT clobber the idle row")
    void completionAfterExternalResetDoesNotClobber(Vertx vertx, VertxTestContext ctx) {
        String uid = "uid-xattempt";
        // The coordinator's scenario, resolved by the TDLib-DEDUP design: TDLib fires ONE completion
        // per file. If an external service reset the (single) download to idle, the completion write's
        // exact-state CAS on download_status='downloading' matches nothing -> the idle row is NOT
        // clobbered to completed. There is no distinct "stale attempt1 completion" to attribute; the
        // 'downloading' guard alone is the correct and sufficient defense.
        deploy(vertx)
                .compose(v -> insertRow(uid, 1, 10, "idle", null, 0, null, null))
                .compose(v -> DataVerticle.fileRepository.claimForDownload(1, uid, "A"))
                .compose(a1 -> {
                    Assertions.assertNotNull(a1, "attempt1 claim wins");
                    return externalReset(uid); // downloading -> idle (external reset-stuck)
                })
                // A completion callback arrives via the EXACT production repository call onFileUpdated uses.
                .compose(v -> DataVerticle.fileRepository.completeDownloadAndRetireAttempt(
                        1, uid, "/stale/path/from/A", System.currentTimeMillis()))
                .compose(result -> {
                    ctx.verify(() -> Assertions.assertNull(result,
                            "completion MUST be a no-op against the externally-reset idle row (rowCount 0)"));
                    return rowOf(uid);
                })
                .onComplete(ctx.succeeding(row -> ctx.verify(() -> {
                    Assertions.assertEquals("idle", row.getString("download_status"),
                            "external idle preserved (NOT clobbered to completed)");
                    Assertions.assertNotEquals("/stale/path/from/A", row.getString("local_path"),
                            "stale local_path must NOT have been written");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("PG: completion of a downloading row completes it AND retires the active attempt atomically")
    void completionCompletesAndRetiresAttempt(Vertx vertx, VertxTestContext ctx) {
        String uid = "uid-xattempt-ok";
        deploy(vertx)
                .compose(v -> insertRow(uid, 1, 10, "idle", null, 0, null, null))
                .compose(v -> DataVerticle.fileRepository.claimForDownload(1, uid, "A"))
                .compose(a1 -> DataVerticle.fileRepository.completeDownloadAndRetireAttempt(
                        1, uid, "/good/path", 111L))
                .compose(result -> {
                    ctx.verify(() -> Assertions.assertNotNull(result, "downloading row completes"));
                    return Future.all(rowOf(uid), activeAttemptCount(uid));
                })
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    io.vertx.sqlclient.Row row = cf.resultAt(0);
                    Assertions.assertEquals("completed", row.getString("download_status"));
                    Assertions.assertEquals("/good/path", row.getString("local_path"));
                    Assertions.assertEquals(111L, row.getLong("completion_date"));
                    Assertions.assertEquals(0, (int) cf.resultAt(1),
                            "active attempt retired atomically with the completion");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("PG: completion preserves an existing completion_date when the callback omits one (D10)")
    void completionPreservesExistingDate(Vertx vertx, VertxTestContext ctx) {
        String uid = "uid-compdate-keep";
        long existing = 555_000L;
        deploy(vertx)
                // A downloading row that somehow already carries a completion_date; a completion write
                // with completionDate=null must COALESCE-preserve it, not null it.
                .compose(v -> insertRow(uid, 1, 10, "idle", null, 0, null, existing))
                .compose(v -> DataVerticle.fileRepository.claimForDownload(1, uid, "A"))
                .compose(a1 -> DataVerticle.fileRepository.completeDownloadAndRetireAttempt(
                        1, uid, "/p", null))
                .compose(result -> rowOf(uid))
                .onComplete(ctx.succeeding(row -> ctx.verify(() -> {
                    Assertions.assertEquals("completed", row.getString("download_status"));
                    Assertions.assertEquals(existing, row.getLong("completion_date"),
                            "existing completion_date preserved via COALESCE when callback omits one");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("PG: concurrent completions of one downloading row -> exactly one completes, attempt retired once (no dangling active attempt)")
    void concurrentCompletionsSingleEffect(Vertx vertx, VertxTestContext ctx) {
        String uid = "uid-concurrent-complete";
        // Bugs 2 & 3 are structurally gone (no map; single-transaction CAS+retire). Prove it: many
        // completion callbacks race the SAME downloading row. The exact-state CAS makes exactly one
        // win; the atomic retire leaves NO dangling active attempt and the row ends 'completed' once.
        deploy(vertx)
                .compose(v -> insertRow(uid, 1, 10, "idle", null, 0, null, null))
                .compose(v -> DataVerticle.fileRepository.claimForDownload(1, uid, "A"))
                .compose(a1 -> {
                    Assertions.assertNotNull(a1);
                    List<Future<io.vertx.core.json.JsonObject>> completions = new ArrayList<>();
                    for (int i = 0; i < 15; i++) {
                        completions.add(DataVerticle.fileRepository.completeDownloadAndRetireAttempt(
                                1, uid, "/p", 222L));
                    }
                    return Future.all(completions);
                })
                .compose(cf -> {
                    long applied = IntStream.range(0, cf.size())
                            .filter(i -> cf.resultAt(i) != null).count();
                    ctx.verify(() -> Assertions.assertEquals(1, applied,
                            "exactly one concurrent completion may apply (exact-state CAS), got " + applied));
                    return Future.all(statusOf(uid), activeAttemptCount(uid));
                })
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    Assertions.assertEquals("completed", cf.resultAt(0), "row completed exactly once");
                    Assertions.assertEquals(0, (int) cf.resultAt(1),
                            "no dangling active attempt after the atomic complete+retire");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("PG: a completion for a downloading row RE-CLAIMED after reset completes the current (single) attempt correctly")
    void completionAfterResetAndReclaimCompletesCurrentAttempt(Vertx vertx, VertxTestContext ctx) {
        String uid = "uid-reclaim-complete";
        // With TDLib DEDUP there is one file/one completion; after reset + re-claim the row is
        // 'downloading' again under the single current attempt, and THAT completion is the current
        // file finishing -> completing it is correct, and the current attempt (only) is retired.
        final int[] activeBeforeComplete = new int[1];
        deploy(vertx)
                .compose(v -> insertRow(uid, 1, 10, "idle", null, 0, null, null))
                .compose(v -> DataVerticle.fileRepository.claimForDownload(1, uid, "A"))
                .compose(a1 -> externalReset(uid))
                .compose(v -> DataVerticle.fileRepository.retireOrphanedAttempts())
                .compose(v -> DataVerticle.fileRepository.claimForDownload(1, uid, "B"))
                .compose(a2 -> {
                    Assertions.assertNotNull(a2, "attempt2 re-claims the downloading row");
                    return activeAttemptCount(uid);
                })
                .compose(active -> {
                    activeBeforeComplete[0] = active;
                    return DataVerticle.fileRepository.completeDownloadAndRetireAttempt(1, uid, "/final", 333L);
                })
                .compose(result -> {
                    ctx.verify(() -> {
                        Assertions.assertEquals(1, activeBeforeComplete[0],
                                "exactly one active attempt (attempt2) before completion");
                        Assertions.assertNotNull(result, "the current downloading attempt completes");
                    });
                    return Future.all(rowOf(uid), activeAttemptCount(uid));
                })
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    io.vertx.sqlclient.Row row = cf.resultAt(0);
                    Assertions.assertEquals("completed", row.getString("download_status"));
                    Assertions.assertEquals("/final", row.getString("local_path"));
                    Assertions.assertEquals(0, (int) cf.resultAt(1), "the single current attempt is retired");
                    ctx.completeNow();
                })));
    }

    // ---- discovery never-downgrade -------------------------------------------------------------

    @Test
    @DisplayName("PG: never-downgrade keeps processed/imported; completed->error still works")
    void neverDowngradeAndLegalCompletedToError(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx)
                .compose(v -> insertRow("uid-proc", 1, 10, "processed", null, 0, null, null))
                .compose(v -> insertRow("uid-imp", 2, 10, "imported", null, 0, null, null))
                .compose(v -> insertRow("uid-comp", 3, 10, "completed", null, 0, null, 123L))
                // Attempt to downgrade processed -> idle (illegal): must be rejected.
                .compose(v -> DataVerticle.fileRepository.updateDownloadStatus(1, "uid-proc", null,
                        FileRecord.DownloadStatus.idle, null))
                .compose(v -> DataVerticle.fileRepository.updateDownloadStatus(2, "uid-imp", null,
                        FileRecord.DownloadStatus.idle, null))
                // Legal completed -> error must succeed.
                .compose(v -> DataVerticle.fileRepository.updateDownloadStatus(3, "uid-comp", null,
                        FileRecord.DownloadStatus.error, null))
                .compose(v -> Future.all(statusOf("uid-proc"), statusOf("uid-imp"), statusOf("uid-comp")))
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    Assertions.assertEquals("processed", cf.resultAt(0), "processed must not downgrade");
                    Assertions.assertEquals("imported", cf.resultAt(1), "imported must not downgrade");
                    Assertions.assertEquals("error", cf.resultAt(2), "completed->error is legal");
                    ctx.completeNow();
                })));
    }

    // ---- D2: queue keyed on unique_id ----------------------------------------------------------

    @Test
    @DisplayName("PG: queueFilesForDownload keyed on unique_id does NOT mass-queue rows sharing a telegram id")
    void queueKeyedOnUniqueId(Vertx vertx, VertxTestContext ctx) {
        // Two rows share the SAME non-unique telegram file `id` (=777) but different unique_ids.
        // Only rows in the eligible set (limit=1) must be queued, never all rows sharing `id`.
        deploy(vertx)
                .compose(v -> insertRow("uid-a", 777, 10, "idle", null, 0, null, null))
                .compose(v -> insertRow("uid-b", 777, 10, "idle", null, 0, null, null))
                .compose(v -> insertRow("uid-c", 777, 10, "idle", null, 0, null, null))
                .compose(v -> DataVerticle.fileRepository.queueFilesForDownload(10, 100, 1, null, false))
                .compose(count -> {
                    ctx.verify(() -> Assertions.assertEquals(1, (int) count,
                            "limit=1 must queue exactly one row, not every row sharing telegram id 777"));
                    return SqlTemplate.forQuery(DataVerticle.pool,
                                    "SELECT COUNT(*) AS c FROM file_record WHERE queued_at IS NOT NULL")
                            .execute(Map.of())
                            .map(rs -> rs.iterator().next().getInteger("c"));
                })
                .onComplete(ctx.succeeding(queued -> ctx.verify(() -> {
                    Assertions.assertEquals(1, (int) queued, "exactly one row queued (unique_id-keyed)");
                    ctx.completeNow();
                })));
    }

    // ---- D9: queued_at renewal -----------------------------------------------------------------

    @Test
    @DisplayName("PG: queued_at is renewed for an externally-reset row whose old queued_at is stale")
    void queuedAtRenewedForResetRow(Vertx vertx, VertxTestContext ctx) {
        String uid = "uid-renew";
        long oldQueuedAt = 1_000_000L; // ancient
        // Reset-row signature: idle, start_date NULL, downloaded_size 0, but stale queued_at populated.
        deploy(vertx)
                .compose(v -> insertRow(uid, 1, 10, "idle", null, 0, oldQueuedAt, null))
                .compose(v -> DataVerticle.fileRepository.queueFilesForDownload(10, 100, 5, null, false))
                .compose(count -> rowOf(uid))
                .onComplete(ctx.succeeding(row -> ctx.verify(() -> {
                    Long newQueuedAt = row.getLong("queued_at");
                    Assertions.assertNotNull(newQueuedAt);
                    Assertions.assertTrue(newQueuedAt > oldQueuedAt,
                            "queued_at must be renewed (was " + oldQueuedAt + ", now " + newQueuedAt + ")");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("PG: an already-queued non-reset idle row keeps its queued_at (no churn)")
    void queuedAtNotChurnedForNormalRow(Vertx vertx, VertxTestContext ctx) {
        String uid = "uid-nochurn";
        long queuedAt = 5_000_000L;
        // Normal queued idle row: has a start_date and a queued_at, NOT reset -> must not be renewed.
        deploy(vertx)
                .compose(v -> insertRow(uid, 1, 10, "idle", 4_000_000L, 123, queuedAt, null))
                .compose(v -> DataVerticle.fileRepository.queueFilesForDownload(10, 100, 5, null, false))
                .compose(count -> rowOf(uid))
                .onComplete(ctx.succeeding(row -> ctx.verify(() -> {
                    Assertions.assertEquals(queuedAt, row.getLong("queued_at"),
                            "already-queued non-reset row must keep its queued_at");
                    ctx.completeNow();
                })));
    }

    // ---- D10: completion_date preserved --------------------------------------------------------

    @Test
    @DisplayName("PG: completed row re-reported as downloading keeps its completion_date")
    void completionDatePreserved(Vertx vertx, VertxTestContext ctx) {
        String uid = "uid-compdate";
        long completion = 987654321L;
        // completed -> error (legal) with completionDate omitted (null): existing date must survive.
        deploy(vertx)
                .compose(v -> insertRow(uid, 1, 10, "completed", null, 0, null, completion))
                .compose(v -> DataVerticle.fileRepository.updateDownloadStatus(1, uid, null,
                        FileRecord.DownloadStatus.error, null))
                .compose(v -> rowOf(uid))
                .onComplete(ctx.succeeding(row -> ctx.verify(() -> {
                    Assertions.assertEquals("error", row.getString("download_status"));
                    Assertions.assertEquals(completion, row.getLong("completion_date"),
                            "completion_date must be preserved on a non-terminal-date transition");
                    ctx.completeNow();
                })));
    }

    // ---- claim renews queued_at / sets start_date ----------------------------------------------

    @Test
    @DisplayName("PG: claim sets start_date and preserves an existing queued_at (COALESCE)")
    void claimSetsStartDatePreservesQueuedAt(Vertx vertx, VertxTestContext ctx) {
        String uid = "uid-claimfields";
        long existingQueuedAt = 7_000_000L;
        deploy(vertx)
                .compose(v -> insertRow(uid, 1, 10, "idle", null, 0, existingQueuedAt, null))
                .compose(v -> DataVerticle.fileRepository.claimForDownload(1, uid, "w1"))
                .compose(a -> {
                    Assertions.assertNotNull(a);
                    return rowOf(uid);
                })
                .onComplete(ctx.succeeding(row -> ctx.verify(() -> {
                    Assertions.assertEquals("downloading", row.getString("download_status"));
                    Assertions.assertNotNull(row.getLong("start_date"), "claim sets start_date");
                    Assertions.assertEquals(existingQueuedAt, row.getLong("queued_at"),
                            "claim preserves existing queued_at via COALESCE");
                    ctx.completeNow();
                })));
    }
}
