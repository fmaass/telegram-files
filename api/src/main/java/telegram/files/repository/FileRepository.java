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
