package telegram.files;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.templates.SqlTemplate;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import telegram.files.repository.FileRecord;
import telegram.files.repository.SettingAutoRecords;
import telegram.files.repository.TransferOperationRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Real-Postgres integration tests for the Phase-3 transfer-durability + recovery protocol (D6/D8):
 * the transfer-finalize exact-state CAS ({@code finalizeTransfer}), the per-row stuck-transfer
 * classification DB actions ({@code getStuckTransfers}, {@code resetTransferToIdle}), and the
 * completed-but-missing recovery ({@code requeueCompletedMissingArtifact}, incl. processed/imported
 * protection).
 * <p>
 * The crash-AFTER-rename recovery-FORWARD path is proven end-to-end in
 * {@link #crashAfterRename_recoversForwardNotReDownload}: the DB is in the REAL crash state
 * (transfer_status='transferring', local_path still the SOURCE inbox path — the finalize that would
 * have written the destination never ran) and the row must be finalized forward, NEVER re-downloaded.
 * Before the Phase-3 fix, {@code getStuckTransfers}/{@code finalizeTransfer} did not exist and the
 * recovery blanket-reset/misclassified this SUCCESSFUL transfer as a loss.
 */
class TransferDurabilityPostgresTest extends PostgresIntegrationTest {

    private Future<Void> deploy(Vertx vertx) {
        return assertReachedPostgres(vertx)
                .compose(v -> vertx.deployVerticle(new DataVerticle()))
                .mapEmpty();
    }

    private Future<Void> insertRow(String uniqueId, String downloadStatus, String transferStatus,
                                   String localPath, Long completionDate) {
        Map<String, Object> p = new HashMap<>();
        p.put("uniqueId", uniqueId);
        p.put("downloadStatus", downloadStatus);
        p.put("transferStatus", transferStatus);
        p.put("localPath", localPath);
        p.put("completionDate", completionDate);
        return SqlTemplate.forUpdate(DataVerticle.pool, """
                        INSERT INTO file_record
                            (id, unique_id, telegram_id, chat_id, message_id, date, has_sensitive_content, size,
                             downloaded_size, type, local_path, download_status, transfer_status, start_date,
                             completion_date, scan_state, download_priority, queued_at)
                        VALUES
                            (1, #{uniqueId}, 10, 100, 200, 1000, false, 1000, 1000, 'file', #{localPath},
                             #{downloadStatus}, #{transferStatus}, 111, #{completionDate}, 'idle', 0, 222)
                        """)
                .execute(p).mapEmpty();
    }

    private Future<Row> rowOf(String uniqueId) {
        return SqlTemplate.forQuery(DataVerticle.pool,
                        "SELECT * FROM file_record WHERE unique_id = #{u}")
                .execute(Map.of("u", uniqueId))
                .map(rs -> rs.size() > 0 ? rs.iterator().next() : null);
    }

    // ---- finalize CAS --------------------------------------------------------------------------

    @Test
    @DisplayName("PG: finalizeTransfer flips transferring->completed and sets local_path")
    void finalizeHappyPath(Vertx vertx, VertxTestContext ctx) {
        String uid = "uid-finalize-ok";
        deploy(vertx)
                .compose(v -> insertRow(uid, "completed", "transferring", "/inbox/a.bin", 5000L))
                .compose(v -> DataVerticle.fileRepository.finalizeTransfer(uid, "/dest/a.bin"))
                .compose(applied -> {
                    ctx.verify(() -> {
                        Assertions.assertNotNull(applied, "finalize must apply on a transferring row");
                        Assertions.assertEquals("completed", applied.getString("transferStatus"));
                        Assertions.assertEquals("/dest/a.bin", applied.getString("localPath"));
                    });
                    return rowOf(uid);
                })
                .onComplete(ctx.succeeding(row -> ctx.verify(() -> {
                    Assertions.assertEquals("completed", row.getString("transfer_status"));
                    Assertions.assertEquals("/dest/a.bin", row.getString("local_path"));
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("PG: a replayed finalize (transfer_status already moved) is a NO-OP, rowCount 0")
    void staleFinalizeIsNoOp(Vertx vertx, VertxTestContext ctx) {
        String uid = "uid-finalize-stale";
        deploy(vertx)
                .compose(v -> insertRow(uid, "completed", "completed", "/dest/a.bin", 5000L))
                .compose(v -> DataVerticle.fileRepository.finalizeTransfer(uid, "/dest/OTHER.bin"))
                .compose(applied -> {
                    ctx.verify(() -> Assertions.assertNull(applied,
                            "replayed finalize on a non-transferring row must be a no-op (null)"));
                    return rowOf(uid);
                })
                .onComplete(ctx.succeeding(row -> ctx.verify(() -> {
                    Assertions.assertEquals("completed", row.getString("transfer_status"));
                    Assertions.assertEquals("/dest/a.bin", row.getString("local_path"),
                            "replayed finalize must NOT overwrite the already-final local_path");
                    ctx.completeNow();
                })));
    }

    // ---- crash AFTER rename: recover forward (BLOCKING-bug regression) --------------------------

    @Test
    @DisplayName("PG: crash AFTER rename (file at dest, DB still transferring w/ SOURCE local_path) -> recover FORWARD, no re-download")
    void crashAfterRename_recoversForwardNotReDownload(Vertx vertx, VertxTestContext ctx) {
        String uid = "uid-crash-after-rename";
        // REAL crash-after-rename DB state: the atomic rename onto the destination COMPLETED, but the
        // process died before the finalize DB write. So:
        //   - download_status='completed', transfer_status='transferring';
        //   - local_path is STILL the SOURCE inbox path (finalize never rewrote it to the destination).
        // The reconciler observes the destination file exists (classified RECOVER_FORWARD) and finalizes
        // forward. It must NEVER re-download. Here we drive the RECOVER_FORWARD DB action directly
        // (finalizeTransfer with the resolved destination), which is exactly what reconcileStuckTransfer
        // invokes for that classification; the classification itself is unit-tested in
        // TransferReconciliationClassifyTest.
        String sourcePath = "/inbox/account/crash.bin";
        String destPath = "/dest/telegram/crash.bin";
        deploy(vertx)
                .compose(v -> insertRow(uid, "completed", "transferring", sourcePath, 5000L))
                .compose(v -> DataVerticle.fileRepository.getStuckTransfers())
                .compose(stuck -> {
                    ctx.verify(() -> {
                        Assertions.assertEquals(1, stuck.size(), "the stuck transferring row must be returned");
                        Assertions.assertEquals(uid, stuck.get(0).uniqueId());
                        Assertions.assertEquals(sourcePath, stuck.get(0).localPath(),
                                "at crash time local_path is still the SOURCE (finalize never ran)");
                    });
                    // RECOVER_FORWARD action: finalize forward to the destination.
                    return DataVerticle.fileRepository.finalizeTransfer(uid, destPath);
                })
                .compose(applied -> {
                    ctx.verify(() -> Assertions.assertNotNull(applied, "forward-finalize must apply"));
                    return rowOf(uid);
                })
                .onComplete(ctx.succeeding(row -> ctx.verify(() -> {
                    Assertions.assertEquals("completed", row.getString("download_status"),
                            "download_status must stay completed (NO re-download)");
                    Assertions.assertEquals("completed", row.getString("transfer_status"),
                            "transfer must be finalized forward");
                    Assertions.assertEquals(destPath, row.getString("local_path"),
                            "local_path must now point at the destination the file actually reached");
                    ctx.completeNow();
                })));
    }

    /**
     * Run the REAL per-row reconciler ({@link TransferVerticle#reconcileStuckTransfer}) on a worker
     * context where {@code Future.await} is legal. Uses a constructed (un-deployed) verticle — the
     * reconciler reads persisted truth + the filesystem and touches only the repository, not
     * autoRecords, so no deployment/automation seeding is needed.
     */
    private Future<Void> runReconciler(Vertx vertx, FileRecord record) {
        // Run on a VIRTUAL-THREAD verticle context (same as production TransferVerticle) so the
        // reconciler's Future.await calls are legal.
        return vertx.deployVerticle(new io.vertx.core.AbstractVerticle() {
            @Override
            public void start(io.vertx.core.Promise<Void> startPromise) {
                try {
                    new TransferVerticle().reconcileStuckTransfer(record);
                    startPromise.complete();
                } catch (Throwable t) {
                    startPromise.fail(t);
                }
            }
        }, Config.VIRTUAL_THREAD_DEPLOYMENT_OPTIONS).mapEmpty();
    }

    private FileRecord completedTransferringRecord(String uniqueId, String localPath) {
        return new FileRecord(1, uniqueId, 10, 100, 200, 0, 1000, false, 1000, 1000, "file",
                null, "f.bin", null, null, null, null, localPath, "completed", "transferring",
                111, 5000L, null, 0, 0, 0, "idle", 0, 222L);
    }

    private TransferOperationRecord op(String uid, Path dest, Path temp, String source, long size, String policy) {
        return new TransferOperationRecord(uid, dest.toString(), temp.toString(), source, size, policy, System.currentTimeMillis());
    }

    /**
     * Build a real {@link Transfer} wired EXACTLY like production ({@link TransferVerticle#getTransfer}):
     * persist-first record, publish-on-finalize event, finalizer (CAS + record delete), and the no-op
     * cleaner. {@code eventPublished} captures whether a 'completed' event was emitted.
     */
    private Transfer wireTransfer(String destination, Transfer.DuplicationPolicy policy, boolean[] eventPublished) {
        SettingAutoRecords.TransferRule rule = new SettingAutoRecords.TransferRule();
        rule.destination = destination;
        rule.transferPolicy = Transfer.TransferPolicy.DIRECT;
        rule.duplicationPolicy = policy;
        Transfer transfer = Transfer.create(rule);
        transfer.transferStatusUpdated = updated -> {
            if (updated.transferStatus() == FileRecord.TransferStatus.completed) {
                eventPublished[0] = true;
            } else {
                // Mirror production TransferVerticle.updateTransferStatus: the 'transferring' (and
                // idle/error) transitions do their real DB write so the finalize CAS
                // (WHERE transfer_status='transferring') has a row to match.
                Future.await(DataVerticle.fileRepository.updateTransferStatus(
                        updated.fileRecord().uniqueId(), updated.transferStatus(), updated.localPath()));
            }
        };
        transfer.transferOperationPersister = operation -> {
            Throwable err = Future.await(DataVerticle.fileRepository.recordTransferOperation(operation)
                    .map((Throwable) null).otherwise(t -> t));
            if (err != null) throw new java.io.IOException("persist failed", err);
        };
        transfer.transferFinalizer = (uniqueId, finalPath) -> {
            Object outcome = Future.await(DataVerticle.fileRepository.finalizeTransfer(uniqueId, finalPath)
                    .map(applied -> (Object) (applied != null)).otherwise(t -> (Object) t));
            if (outcome instanceof Throwable t) throw new java.io.IOException("finalize failed", t);
            return (Boolean) outcome;
        };
        transfer.transferOperationCleaner = uniqueId -> {
            Throwable err = Future.await(DataVerticle.fileRepository.deleteTransferOperation(uniqueId)
                    .map((Throwable) null).otherwise(t -> t));
            if (err != null) throw new java.io.IOException("clean failed", err);
        };
        return transfer;
    }

    /** Run {@code body} on a VIRTUAL-THREAD verticle context (so its Future.await calls are legal). */
    private Future<Void> runOnVirtualThread(Vertx vertx, Runnable body) {
        return vertx.deployVerticle(new io.vertx.core.AbstractVerticle() {
            @Override
            public void start(io.vertx.core.Promise<Void> startPromise) {
                try {
                    body.run();
                    startPromise.complete();
                } catch (Throwable t) {
                    startPromise.fail(t);
                }
            }
        }, Config.VIRTUAL_THREAD_DEPLOYMENT_OPTIONS).mapEmpty();
    }

    private Future<TransferOperationRecord> opOf(String uniqueId) {
        return DataVerticle.fileRepository.getTransferOperation(uniqueId);
    }

    // ---- item 1: HASH-dedup persist-first (no spurious re-download on crash) ---------------------

    @Test
    @DisplayName("PG: HASH dedup (identical file already at dest) persists-first, finalizes, deletes source; a crash BEFORE source-delete recovers WITHOUT re-download")
    void hashDedupPersistsFirstNoReDownload(Vertx vertx, VertxTestContext ctx, @TempDir Path base) throws Exception {
        String uid = "uid-hash-dedup";
        Path destDir = Files.createDirectories(base.resolve("dest"));
        Path inbox = Files.createDirectories(base.resolve("inbox"));
        // Identical payload already at the destination (HASH match); the inbox source is the same bytes.
        Path destFile = destDir.resolve("f.bin");
        Files.writeString(destFile, "IDENTICAL-PAYLOAD");
        Path source = inbox.resolve("f.bin");
        Files.writeString(source, "IDENTICAL-PAYLOAD");
        long size = Files.size(source);

        FileRecord fr = new FileRecord(1, uid, 10, 100, 200, 0, 0, false, size, size, "file",
                null, "f.bin", null, null, null, null, source.toString(), "completed", "idle",
                111, 5000L, null, 0, 0, 0, "idle", 0, 222L);
        boolean[] published = {false};
        // DirectTransfer.getTransferPath = destination/<name>, so destination=destDir puts it at f.bin.
        Transfer transfer = wireTransfer(destDir.toString(), Transfer.DuplicationPolicy.HASH, published);

        deploy(vertx)
                .compose(v -> insertRow(uid, "completed", "idle", source.toString(), 5000L))
                .compose(v -> runOnVirtualThread(vertx, () -> transfer.transfer(fr)))
                .compose(v -> rowOf(uid))
                .compose(row -> opOf(uid).map(op -> new Object[]{row, op}))
                .onComplete(ctx.succeeding(arr -> ctx.verify(() -> {
                    Row row = (Row) arr[0];
                    TransferOperationRecord leftover = (TransferOperationRecord) arr[1];
                    // The HASH path finalized forward against the EXISTING destination (no re-download),
                    // deleted the source, and cleaned the record (deleted by the finalize transaction).
                    Assertions.assertEquals("completed", row.getString("download_status"), "no re-download");
                    Assertions.assertEquals("completed", row.getString("transfer_status"), "finalized forward");
                    Assertions.assertEquals(destFile.toString(), row.getString("local_path"),
                            "local_path points at the existing destination payload");
                    Assertions.assertTrue(published[0], "a 'completed' event is published on successful HASH dedup");
                    Assertions.assertFalse(Files.exists(source), "source deleted after finalize");
                    Assertions.assertTrue(Files.exists(destFile), "the destination payload is preserved");
                    Assertions.assertNull(leftover, "the operation record must not leak (deleted on finalize)");
                    ctx.completeNow();
                })));
    }

    // ---- item 2: live finalize no-op (row went processed) ----------------------------------------

    @Test
    @DisplayName("PG: row becomes 'processed' before finalize -> NO 'completed' event, NO leaked record, processed preserved, source NOT deleted")
    void liveFinalizeNoOp_processedPreserved(Vertx vertx, VertxTestContext ctx, @TempDir Path base) throws Exception {
        String uid = "uid-live-noop";
        Path destDir = Files.createDirectories(base.resolve("dest"));
        Path inbox = Files.createDirectories(base.resolve("inbox"));
        Path source = inbox.resolve("g.bin");
        Files.writeString(source, "PAYLOAD-BYTES");
        long size = Files.size(source);

        FileRecord fr = new FileRecord(1, uid, 10, 100, 200, 0, 0, false, size, size, "file",
                null, "g.bin", null, null, null, null, source.toString(), "completed", "idle",
                111, 5000L, null, 0, 0, 0, "idle", 0, 222L);
        boolean[] published = {false};
        // Finalizer that advances the row to 'processed' JUST BEFORE finalizing (simulates the TOCTOU
        // window) then delegates to the real finalizer — which must then no-op on the CAS guard.
        Transfer transfer = wireTransfer(destDir.toString(), Transfer.DuplicationPolicy.OVERWRITE, published);
        Transfer.TransferFinalizer realFinalizer = transfer.transferFinalizer;
        transfer.transferFinalizer = (uniqueId, finalPath) -> {
            Future.await(SqlTemplate.forUpdate(DataVerticle.pool,
                            "UPDATE file_record SET download_status = 'processed' WHERE unique_id = #{u}")
                    .execute(Map.of("u", uniqueId)));
            return realFinalizer.finalize(uniqueId, finalPath);
        };

        deploy(vertx)
                .compose(v -> insertRow(uid, "completed", "idle", source.toString(), 5000L))
                .compose(v -> runOnVirtualThread(vertx, () -> transfer.transfer(fr)))
                .compose(v -> rowOf(uid))
                .compose(row -> opOf(uid).map(op -> new Object[]{row, op}))
                .onComplete(ctx.succeeding(arr -> ctx.verify(() -> {
                    Row row = (Row) arr[0];
                    TransferOperationRecord leftover = (TransferOperationRecord) arr[1];
                    Assertions.assertFalse(published[0], "NO 'completed' event on a finalize no-op");
                    Assertions.assertEquals("processed", row.getString("download_status"),
                            "processed must be preserved (never-downgrade)");
                    Assertions.assertEquals("transferring", row.getString("transfer_status"),
                            "the finalize no-op must not have touched transfer_status");
                    Assertions.assertNull(leftover, "the operation record must NOT leak after a no-op finalize");
                    Assertions.assertTrue(Files.exists(source), "source must NOT be deleted on a no-op finalize");
                    ctx.completeNow();
                })));
    }

    // ---- crash-window + wrong-payload recovery (size-verified persisted truth) -------------------

    @Test
    @DisplayName("PG: crash BETWEEN rename and finalize (payload at dest, source still intact) -> recover FORWARD + delete source")
    void crashBetweenRenameAndFinalize_recoversForwardDeletesSource(Vertx vertx, VertxTestContext ctx, @TempDir Path base) throws Exception {
        String uid = "uid-crash-rename-finalize";
        // The rename completed: the size-verified payload is at the (suffixed) destination. The source
        // still exists (copy-not-move). Recover forward, then delete the source (source-deleted-last).
        Path destDir = Files.createDirectories(base.resolve("dest"));
        Path inbox = Files.createDirectories(base.resolve("inbox"));
        Path suffixedDest = destDir.resolve("b(1).bin");
        Files.writeString(suffixedDest, "PAYLOAD8"); // 8 bytes
        Path source = inbox.resolve("b.bin");
        Files.writeString(source, "PAYLOAD8"); // same 8 bytes — source survived
        Path temp = destDir.resolve(".b(1).bin.tf-abc.part");

        deploy(vertx)
                .compose(v -> insertRow(uid, "completed", "transferring", source.toString(), 5000L))
                .compose(v -> DataVerticle.fileRepository.recordTransferOperation(op(uid, suffixedDest, temp, source.toString(), 8, "RENAME")))
                .compose(v -> runReconciler(vertx, completedTransferringRecord(uid, source.toString())))
                .compose(v -> rowOf(uid))
                .onComplete(ctx.succeeding(row -> ctx.verify(() -> {
                    Assertions.assertEquals("completed", row.getString("download_status"), "NO re-download");
                    Assertions.assertEquals("completed", row.getString("transfer_status"), "finalized forward");
                    Assertions.assertEquals(suffixedDest.toString(), row.getString("local_path"),
                            "local_path is the EXACT persisted suffixed destination");
                    Assertions.assertTrue(Files.exists(suffixedDest), "the suffixed payload is preserved");
                    Assertions.assertFalse(Files.exists(source), "source deleted LAST after finalize");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("PG: crash BETWEEN persist and rename (payload in temp, source intact) -> recover from temp, file NOT lost, source deleted")
    void crashBetweenPersistAndRename_recoversFromTemp(Vertx vertx, VertxTestContext ctx, @TempDir Path base) throws Exception {
        String uid = "uid-crash-persist-rename";
        // The copy finished (temp is the size-verified payload) but the rename never ran. The source is
        // intact too (copy-not-move). Recovery renames temp->dest and finalizes; source deleted last.
        Path destDir = Files.createDirectories(base.resolve("dest"));
        Path inbox = Files.createDirectories(base.resolve("inbox"));
        Path finalDest = destDir.resolve("m.bin");
        Path temp = destDir.resolve(".m.bin.tf-cafe.part");
        Files.writeString(temp, "COMPLETE9!"); // 10 bytes
        Path source = inbox.resolve("m.bin");
        Files.writeString(source, "COMPLETE9!"); // same 10 bytes — source survived

        deploy(vertx)
                .compose(v -> insertRow(uid, "completed", "transferring", source.toString(), 5000L))
                .compose(v -> DataVerticle.fileRepository.recordTransferOperation(op(uid, finalDest, temp, source.toString(), 10, "RENAME")))
                .compose(v -> runReconciler(vertx, completedTransferringRecord(uid, source.toString())))
                .compose(v -> rowOf(uid))
                .onComplete(ctx.succeeding(row -> ctx.verify(() -> {
                    Assertions.assertEquals("completed", row.getString("download_status"), "NO re-download");
                    Assertions.assertEquals("completed", row.getString("transfer_status"), "finalized forward from temp");
                    Assertions.assertEquals(finalDest.toString(), row.getString("local_path"));
                    Assertions.assertTrue(Files.exists(finalDest), "temp RENAMED to the destination, NOT lost");
                    Assertions.assertEquals("COMPLETE9!", Files.readString(finalDest), "content intact");
                    Assertions.assertFalse(Files.exists(temp), "temp consumed by the completed rename");
                    Assertions.assertFalse(Files.exists(source), "source deleted LAST after finalize");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("PG: a WRONG-SIZE file sitting at final_dest is NOT treated as our payload -> recover from source instead")
    void wrongSizeFileAtDestinationIsNotOurPayload(Vertx vertx, VertxTestContext ctx, @TempDir Path base) throws Exception {
        String uid = "uid-wrong-size-dest";
        // An unrelated file (WRONG size) sits at final_dest. It must NOT be treated as our payload. There
        // is no temp; the source survives -> retry the transfer from the source (not finalize the wrong file).
        Path destDir = Files.createDirectories(base.resolve("dest"));
        Path inbox = Files.createDirectories(base.resolve("inbox"));
        Path finalDest = destDir.resolve("w.bin");
        Files.writeString(finalDest, "some-unrelated-external-file-of-a-different-size"); // wrong size
        Path source = inbox.resolve("w.bin");
        Files.writeString(source, "OURPAYLOAD"); // 10 bytes — our real payload survives
        Path temp = destDir.resolve(".w.bin.tf-x.part"); // does not exist

        deploy(vertx)
                .compose(v -> insertRow(uid, "completed", "transferring", source.toString(), 5000L))
                .compose(v -> DataVerticle.fileRepository.recordTransferOperation(op(uid, finalDest, temp, source.toString(), 10, "RENAME")))
                .compose(v -> runReconciler(vertx, completedTransferringRecord(uid, source.toString())))
                .compose(v -> rowOf(uid))
                .onComplete(ctx.succeeding(row -> ctx.verify(() -> {
                    Assertions.assertEquals("completed", row.getString("download_status"),
                            "download stays completed — retry the TRANSFER, not a re-download");
                    Assertions.assertEquals("idle", row.getString("transfer_status"),
                            "the wrong-size file is NOT our payload: reset to idle to retry from the source");
                    Assertions.assertTrue(Files.exists(source), "source (durable anchor) preserved for the retry");
                    Assertions.assertEquals("some-unrelated-external-file-of-a-different-size", Files.readString(finalDest),
                            "the unrelated external file must be untouched");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("PG: genuine loss (no size-matching dest, no temp, no source) -> requeue for re-download")
    void genuineLossRequeues(Vertx vertx, VertxTestContext ctx, @TempDir Path base) throws Exception {
        String uid = "uid-genuine-loss";
        Path destDir = Files.createDirectories(base.resolve("dest"));
        Path finalDest = destDir.resolve("z.bin"); // absent
        Path temp = destDir.resolve(".z.bin.tf-z.part"); // absent
        String source = base.resolve("inbox/z.bin").toString(); // absent

        deploy(vertx)
                .compose(v -> insertRow(uid, "completed", "transferring", source, 5000L))
                .compose(v -> DataVerticle.fileRepository.recordTransferOperation(op(uid, finalDest, temp, source, 10, "RENAME")))
                .compose(v -> runReconciler(vertx, completedTransferringRecord(uid, source)))
                .compose(v -> rowOf(uid))
                .onComplete(ctx.succeeding(row -> ctx.verify(() -> {
                    Assertions.assertEquals("idle", row.getString("download_status"),
                            "genuine loss: re-queued for re-download");
                    Assertions.assertNull(row.getString("local_path"), "stale path cleared");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("PG: external terminal write (processed) between select and finalize -> finalize is a NO-OP, processed preserved")
    void finalizeVsExternalTerminalIsNoOp(Vertx vertx, VertxTestContext ctx) {
        String uid = "uid-toctou";
        // Row selected as stuck (completed+transferring); an external service then advances it to
        // 'processed' before the finalize runs. The finalize CAS guard (download_status NOT IN
        // processed/imported) must make it a NO-OP and never clobber the external terminal state.
        deploy(vertx)
                .compose(v -> insertRow(uid, "completed", "transferring", "/inbox/t.bin", 5000L))
                // simulate the external write landing in the TOCTOU window:
                .compose(v -> SqlTemplate.forUpdate(DataVerticle.pool, """
                                UPDATE file_record SET download_status = 'processed' WHERE unique_id = #{u}
                                """).execute(Map.of("u", uid)).mapEmpty())
                .compose(v -> DataVerticle.fileRepository.finalizeTransfer(uid, "/dest/t.bin"))
                .compose(applied -> {
                    ctx.verify(() -> Assertions.assertNull(applied,
                            "finalize must be a no-op when the row went processed (TOCTOU guard)"));
                    return rowOf(uid);
                })
                .onComplete(ctx.succeeding(row -> ctx.verify(() -> {
                    Assertions.assertEquals("processed", row.getString("download_status"),
                            "external terminal state must be preserved (never-downgrade)");
                    Assertions.assertEquals("transferring", row.getString("transfer_status"),
                            "finalize must not have touched transfer_status");
                    Assertions.assertEquals("/inbox/t.bin", row.getString("local_path"),
                            "finalize must not have overwritten local_path");
                    ctx.completeNow();
                })));
    }

    // ---- retry-from-source DB action -----------------------------------------------------------

    @Test
    @DisplayName("PG: resetTransferToIdle flips transferring->idle (retry transfer, not re-download); guards processed/imported")
    void resetTransferToIdle_retryAndGuards(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx)
                .compose(v -> insertRow("uid-retry", "completed", "transferring", "/inbox/r.bin", 5000L))
                .compose(v -> insertRow("uid-proc", "processed", "transferring", "/dest/p.bin", 5000L))
                .compose(v -> DataVerticle.fileRepository.resetTransferToIdle("uid-retry"))
                .compose(reset -> {
                    ctx.verify(() -> Assertions.assertTrue(reset, "a completed+transferring row must reset to idle"));
                    return DataVerticle.fileRepository.resetTransferToIdle("uid-proc");
                })
                .compose(resetProc -> {
                    ctx.verify(() -> Assertions.assertFalse(resetProc,
                            "processed row must NOT be reset (download_status guard)"));
                    return Future.all(rowOf("uid-retry"), rowOf("uid-proc"));
                })
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    Row retry = cf.resultAt(0);
                    Row proc = cf.resultAt(1);
                    Assertions.assertEquals("idle", retry.getString("transfer_status"));
                    Assertions.assertEquals("completed", retry.getString("download_status"),
                            "download stays completed — this is a transfer retry, not a re-download");
                    Assertions.assertEquals("transferring", proc.getString("transfer_status"),
                            "processed row untouched");
                    ctx.completeNow();
                })));
    }

    // ---- completed-but-missing recovery --------------------------------------------------------

    @Test
    @DisplayName("PG: completed-but-missing is re-queued to idle; processed/imported in same state are UNTOUCHED")
    void completedMissingRequeued_processedUntouched(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx)
                .compose(v -> insertRow("uid-lost", "completed", "idle", "/gone/lost.bin", 5000L))
                .compose(v -> insertRow("uid-proc2", "processed", "completed", "/gone/proc.bin", 5000L))
                .compose(v -> insertRow("uid-imp2", "imported", "completed", "/gone/imp.bin", 5000L))
                .compose(v -> DataVerticle.fileRepository.requeueCompletedMissingArtifact(
                        List.of("uid-lost", "uid-proc2", "uid-imp2")))
                .compose(n -> {
                    ctx.verify(() -> Assertions.assertEquals(1, (int) n,
                            "only the completed-but-missing row is re-queued (processed/imported guarded out)"));
                    return Future.all(rowOf("uid-lost"), rowOf("uid-proc2"), rowOf("uid-imp2"));
                })
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    Row lost = cf.resultAt(0);
                    Row proc = cf.resultAt(1);
                    Row imp = cf.resultAt(2);
                    Assertions.assertEquals("idle", lost.getString("download_status"),
                            "completed-but-missing must be re-queued to idle for re-download");
                    Assertions.assertNull(lost.getString("local_path"), "stale local_path cleared on re-queue");
                    Assertions.assertNull(lost.getLong("completion_date"), "completion_date cleared on re-queue");
                    Assertions.assertEquals("processed", proc.getString("download_status"),
                            "processed row must be UNTOUCHED by recovery");
                    Assertions.assertEquals("imported", imp.getString("download_status"),
                            "imported row must be UNTOUCHED by recovery");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("PG: getStuckTransfers only returns completed+transferring rows (never processed/imported)")
    void getStuckTransfersExcludesExternalTerminal(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx)
                .compose(v -> insertRow("uid-c", "completed", "transferring", "/inbox/c.bin", 5000L))
                .compose(v -> insertRow("uid-p", "processed", "transferring", "/dest/p.bin", 5000L))
                .compose(v -> insertRow("uid-i", "imported", "transferring", "/dest/i.bin", 5000L))
                .compose(v -> DataVerticle.fileRepository.getStuckTransfers())
                .onComplete(ctx.succeeding(stuck -> ctx.verify(() -> {
                    Assertions.assertEquals(1, stuck.size(), "only the completed row is a reconcilable stuck transfer");
                    Assertions.assertEquals("uid-c", stuck.get(0).uniqueId());
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("PG: requeue with an empty list is a no-op")
    void requeueEmptyIsNoop(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx)
                .compose(v -> DataVerticle.fileRepository.requeueCompletedMissingArtifact(List.of()))
                .onComplete(ctx.succeeding(n -> ctx.verify(() -> {
                    Assertions.assertEquals(0, (int) n);
                    ctx.completeNow();
                })));
    }
}
