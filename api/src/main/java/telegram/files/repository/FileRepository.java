package telegram.files.repository;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jooq.lambda.tuple.Tuple3;

import java.util.List;
import java.util.Map;

public interface FileRepository {
    Future<FileRecord> create(FileRecord fileRecord);

    Future<Boolean> createIfNotExist(FileRecord fileRecord);

    Future<Tuple3<List<FileRecord>, Long, Long>> getFiles(long chatId, Map<String, String> filter);

    Future<Map<String, FileRecord>> getFilesByUniqueId(List<String> uniqueIds);

    Future<FileRecord> getByPrimaryKey(int fileId, String uniqueId);

    Future<FileRecord> getByUniqueId(String uniqueId);

    Future<FileRecord> getMainFileByThread(long telegramId, long threadChatId, long messageThreadId);

    Future<String> getCaptionByMediaAlbumId(long mediaAlbumId);

    Future<Long> getReactionCountByMediaAlbumId(long mediaAlbumId);

    Future<JsonObject> getDownloadStatistics(long telegramId);

    Future<JsonObject> getChatDownloadStatistics(long telegramId, long chatId, Integer historySince);

    Future<JsonObject> getDownloadStatistics();

    Future<JsonArray> getCompletedRangeStatistics(long id, long startTime, long endTime, int timeRange);

    Future<Integer> countByStatus(long telegramId, FileRecord.DownloadStatus downloadStatus);

    Future<List<FileRecord>> getByDownloadStatus(long telegramId, FileRecord.DownloadStatus downloadStatus);

    Future<JsonObject> countWithType(long telegramId, long chatId);

    Future<JsonObject> updateDownloadStatus(int fileId,
                                            String uniqueId,
                                            String localPath,
                                            FileRecord.DownloadStatus downloadStatus,
                                            Long completionDate);

    /**
     * Atomically claim a file for download: within ONE transaction, flip
     * {@code download_status idle -> downloading} (exact-state CAS on {@code unique_id}) AND INSERT
     * the owning {@code active} {@link DownloadAttemptRecord}. Both-or-neither.
     * <p>
     * The claim is idempotent under concurrency: only one worker wins the CAS, and the
     * one-active-attempt-per-unique_id partial unique index rejects a duplicate active attempt
     * (rolling the transaction back). Tolerates an external {@code downloading -> idle} reset because
     * the CAS keys on {@code download_status='idle'}.
     *
     * @return the minted {@code attempt_id} on success, or {@code null} if the row was not idle
     * (already claimed, terminal, externally reset mid-claim, etc.) — caller must NOT proceed.
     */
    Future<String> claimForDownload(int fileId, String uniqueId, String leaseOwner);

    /**
     * Owned progress transition performed by the worker that holds {@code attemptId}. Exact-state
     * CAS: {@code WHERE unique_id=? AND download_status=<exactObservedPrior> AND EXISTS(active attempt
     * with attempt_id=?)}. A stale writer (wrong prior state) or a superseded attempt yields
     * rowCount 0 and does NOT clobber. On a terminal target the owning attempt is retired
     * (best-effort, same transaction).
     *
     * @return true iff exactly one row transitioned.
     */
    Future<Boolean> transitionOwned(int fileId,
                                    String uniqueId,
                                    String attemptId,
                                    FileRecord.DownloadStatus expectedFrom,
                                    FileRecord.DownloadStatus target,
                                    String localPath,
                                    Long completionDate);

    /**
     * TDLib download-finished completion write. TDLib DEDUPS (one TdApi.File / one download / ONE
     * completion per file identity; {@code UpdateFile} carries no attempt id), so a "stale attempt
     * completion distinct from a newer attempt's" cannot physically occur — the single completion
     * always reports the current file. This is therefore a SINGLE ATOMIC statement/transaction: the
     * exact-state CAS {@code download_status='downloading' -> 'completed'} AND the retirement of the
     * current active attempt happen together (no separate lookup-then-update, so no window for a new
     * claim to slip in). The {@code download_status='downloading'} guard makes an external reset-to-idle
     * a no-op (rowCount 0, no clobber); a downloading row IS the current file finishing.
     * <p>
     * Returns the {@link JsonObject} describing the applied change (same shape as
     * {@link #updateDownloadStatus}) or {@code null} when the CAS matched no row.
     */
    Future<JsonObject> completeDownloadAndRetireAttempt(int fileId,
                                                        String uniqueId,
                                                        String localPath,
                                                        Long completionDate);

    /**
     * Retire (status {@code retired}) any {@code active} attempt for {@code uniqueId}. Used by the
     * claim rollback path and by reconciliation to clear an orphaned active attempt left behind when
     * an external service reset a {@code downloading} row to {@code idle} — so a fresh claim is not
     * blocked by the one-active-attempt constraint.
     *
     * @return number of attempts retired.
     */
    Future<Integer> retireActiveAttempts(String uniqueId);

    /**
     * Reconciliation sweep: for every {@code unique_id} whose {@code file_record} is idle or in a
     * terminal state but which still has an {@code active} download_attempt, retire that attempt.
     * Portable, set-based; no {@code FOR UPDATE}.
     *
     * @return number of orphaned attempts retired.
     */
    Future<Integer> retireOrphanedAttempts();

    Future<JsonObject> updateTransferStatus(String uniqueId,
                                            FileRecord.TransferStatus transferStatus,
                                            String localPath);

    /**
     * Crash-atomic transfer FINALIZE (D6). Exact-state CAS: flip {@code transfer_status
     * 'transferring' -> 'completed'} AND set the new {@code local_path} in ONE statement, guarded by
     * {@code WHERE transfer_status='transferring' AND download_status NOT IN ('processed','imported')}.
     * A crash-replay or a concurrent worker that tries to finalize a row whose {@code transfer_status}
     * already moved off {@code transferring} matches no row (rowCount 0) and is a NO-OP.
     * <p>
     * The {@code download_status NOT IN ('processed','imported')} guard closes a TOCTOU: between
     * reconciliation selecting a stuck row and finalizing it, an external service can advance
     * {@code download_status} to {@code processed}/{@code imported}; the guard makes the finalize a
     * no-op in that window so a concurrent external terminal write is never clobbered (never-downgrade).
     * On finalize the row's {@code transfer_operation} durable record is DELETED in the same
     * transaction (the operation is done — no crash residue to reconcile).
     * <p>
     * This is the DB half of the durable-transfer sequence: the caller has already atomically renamed
     * the artifact onto {@code localPath} and fsynced the destination directory BEFORE calling this.
     *
     * @return the applied change ({@code transferStatus}/{@code localPath}) or {@code null} on a
     * no-op CAS.
     */
    Future<JsonObject> finalizeTransfer(String uniqueId, String localPath);

    /**
     * Persist the durable {@link TransferOperationRecord} for an in-flight transfer BEFORE its atomic
     * rename (upsert on {@code unique_id}). Reconciliation reads this to reconcile from PERSISTED
     * TRUTH — the exact destination path chosen (incl. RENAME suffix), the staging temp, and whether
     * the source was consumed by a same-FS move — instead of recomputing a canonical path.
     */
    Future<Void> recordTransferOperation(TransferOperationRecord operation);

    /** The persisted in-flight {@link TransferOperationRecord} for {@code uniqueId}, or {@code null}. */
    Future<TransferOperationRecord> getTransferOperation(String uniqueId);

    /** Delete the persisted transfer-operation record for {@code uniqueId} (idempotent). */
    Future<Void> deleteTransferOperation(String uniqueId);

    /**
     * Startup transfer reconciliation (D6): fetch every {@code file_record} stuck in
     * {@code transfer_status='transferring'} whose {@code download_status='completed'}. On a clean boot
     * NO transfer is in flight (the single-threaded {@link telegram.files.TransferVerticle} is not yet
     * running), so any such row is the residue of a transfer that crashed mid-flight. The caller
     * inspects the ACTUAL filesystem per row to classify (recover-forward vs retry-transfer vs
     * re-download) — a blanket reset would misclassify a crash-AFTER-rename (file safely at the
     * destination) as a loss. Only {@code download_status='completed'} rows are returned (never
     * {@code processed}/{@code imported}, which are external-owned and past transfer).
     *
     * @return the stuck-transferring rows for per-row filesystem classification.
     */
    Future<List<FileRecord>> getStuckTransfers();

    /**
     * Reset a single row's {@code transfer_status='transferring' -> 'idle'} (exact-state CAS) so the
     * idempotent transfer re-runs. Used by startup reconciliation for the retry-transfer cases (b/c):
     * the download artifact or a dest-local temp still exists, so the file must be (re-)transferred,
     * NOT re-downloaded. Guarded on {@code download_status='completed'} so it can never touch
     * {@code processed}/{@code imported}.
     *
     * @return true iff exactly one transferring row was reset.
     */
    Future<Boolean> resetTransferToIdle(String uniqueId);

    /**
     * Startup recovery (D6/invariant conflict resolution): make {@code completed}-but-missing rows
     * recoverable. A row that is {@code download_status='completed'} with an ABSENT artifact
     * (local_path NULL/blank or the file does not exist on disk) and is NOT {@code processed}/
     * {@code imported} is a genuine data loss — the previous behavior silently preserved a
     * completed-but-gone row. The explicit policy is to re-queue it for re-download by resetting to
     * {@code idle} (respecting the state machine's {@code completed -> ...} legality is bypassed here
     * intentionally: this is a recovery reset, analogous to the external reset-stuck flow). Rows whose
     * artifact IS present are left untouched. {@code processed}/{@code imported} rows are NEVER
     * touched (external-owned).
     * <p>
     * The artifact-presence check cannot be done in SQL (the DB has no filesystem view), so the caller
     * supplies the list of {@code unique_id}s whose artifact it has already verified absent.
     *
     * @param uniqueIdsWithMissingArtifact unique_ids confirmed (by the caller, on disk) to have a
     *                                     missing artifact while still {@code completed}.
     * @return number of rows re-queued to idle.
     */
    Future<Integer> requeueCompletedMissingArtifact(List<String> uniqueIdsWithMissingArtifact);

    Future<Void> updateFileId(int fileId, String uniqueId);

    Future<Integer> updateAlbumDataByMediaAlbumId(long mediaAlbumId, String caption, long reactionCount);

    Future<Void> updateTags(String uniqueId, String tags);

    Future<Void> deleteByUniqueId(String uniqueId);

    Future<Long> getMinMessageId(long telegramId, long chatId);
    
    /**
     * Get files ready for download from database.
     * Queries files with download_status='idle' and scan_state='idle'.
     * Orders by download_priority DESC, queued_at ASC.
     * 
     * @param telegramId Telegram account ID
     * @param chatId Chat ID (0 for all chats)
     * @param limit Maximum number of files to return
     * @param cutoffDateSeconds Optional cutoff date in seconds. Only return files with date >= cutoffDate.
     * @param downloadOldestFirst If true, order by date ASC (oldest first), else DESC (newest first).
     * @return List of FileRecord ready for download
     */
    Future<List<FileRecord>> getFilesReadyForDownload(long telegramId, long chatId, int limit, Integer cutoffDateSeconds, Boolean downloadOldestFirst);
    
    /**
     * Mark files as queued by setting queued_at timestamp.
     * Updates files with download_status='idle' and scan_state='idle' (or NULL).
     * 
     * @param telegramId Telegram account ID
     * @param chatId Chat ID (0 for all chats)
     * @param limit Maximum number of files to queue
     * @return Number of files queued
     */
    Future<Integer> queueFilesForDownload(long telegramId, long chatId, int limit, Integer cutoffDateSeconds, Boolean downloadOldestFirst);

    Future<Integer> queueFilesByUniqueIds(List<String> uniqueIds);
}
