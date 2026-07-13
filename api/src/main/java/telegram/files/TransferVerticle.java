package telegram.files;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import org.jooq.lambda.tuple.Tuple3;
import telegram.files.repository.AutomationState;
import telegram.files.repository.FileRecord;
import telegram.files.repository.SettingAutoRecords;
import telegram.files.repository.TransferOperationRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class TransferVerticle extends AbstractVerticle {
    private static final Log log = LogFactory.get();

    private static final int HISTORY_SCAN_INTERVAL = 2 * 60 * 1000;

    private static final int TRANSFER_INTERVAL = 3 * 1000;

    private final SettingAutoRecords autoRecords;

    private final Map<String, Transfer> transfers = new HashMap<>();

    private final BlockingQueue<WaitingTransferFile> waitingTransferFiles = new LinkedBlockingQueue<>();

    private volatile boolean isStopped = false;

    private volatile Transfer beingTransferred;

    public TransferVerticle() {
        this.autoRecords = AutomationsHolder.INSTANCE.autoRecords();
        AutomationsHolder.INSTANCE.registerOnRemoveListener(removedItems -> removedItems.forEach(item -> {
            waitingTransferFiles.removeIf(waitingTransferFile -> waitingTransferFile.uniqueId().equals(item.uniqueKey()));
            transfers.remove(item.uniqueKey());
        }));
    }

    @Override
    public void start(Promise<Void> startPromise) {
        initEventConsumer()
                // Startup transfer reconciliation (D6) MUST complete BEFORE the transfer timers start,
                // otherwise a fresh transfer could race the reconciliation of a crashed one (e.g.
                // reset-to-idle a row the reconciler is about to recover-forward). Await it.
                .compose(_ -> reconcileTransfers())
                .onSuccess(_ -> {
                    vertx.setPeriodic(0, HISTORY_SCAN_INTERVAL, _ -> addHistoryFiles());
                    vertx.setPeriodic(0, TRANSFER_INTERVAL, _ -> startTransfer());

                    log.info("""
                            Transfer verticle started!
                            |History scan interval: %s ms
                            |Transfer interval: %s ms
                            |Auto chats: %s
                            """.formatted(HISTORY_SCAN_INTERVAL, TRANSFER_INTERVAL, autoRecords.getTransferEnabledItems().size()));

                    startPromise.complete();
                }).onFailure(startPromise::fail);
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        isStopped = true;
        if (beingTransferred != null) {
            log.info("Wait for transfer to complete, file: %s".formatted(beingTransferred.getTransferRecord().uniqueId()));
            while (beingTransferred != null) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    log.error("Stop transfer verticle error: %s".formatted(e.getMessage()));
                    stopPromise.fail(e);
                }
            }
        }
        log.info("Transfer verticle stopped");
        stopPromise.complete();
    }

    /**
     * Startup transfer reconciliation (D6). Idempotent, awaited before the transfer timers start.
     * Composes with (does not duplicate) the Phase-2 download-attempt reconciliation in
     * {@link TelegramVerticle#reconcileDownloadStatuses}: this handles only the TRANSFER side
     * (transfer_status + dest-local temps), never download attempts.
     * <p>
     * For each row stuck in {@code transfer_status='transferring'} (a transfer that crashed
     * mid-flight), it reads the durable {@link telegram.files.repository.TransferOperationRecord} and
     * reconciles from PERSISTED TRUTH with SIZE-verified payload identity (see
     * {@link #reconcileStuckTransfer} / {@link #classifyStuckTransfer}):
     * <ol type="a">
     *   <li><b>the size-verified payload is at the persisted destination</b> &rarr; recover FORWARD
     *       (finalize, then delete the source). Never re-download.</li>
     *   <li><b>the size-verified payload is in the staging temp</b> &rarr; complete the crashed rename
     *       (policy-honored) then finalize, then delete the source.</li>
     *   <li><b>the source survives</b> &rarr; retry the TRANSFER from the copy step (NOT a re-download).</li>
     *   <li><b>nothing size-matching survives</b> (and the row is not processed/imported) &rarr; genuine
     *       loss: re-queue for RE-DOWNLOAD.</li>
     * </ol>
     * A wrong-size file sitting at the destination is NOT treated as our payload; the source is never
     * deleted before the payload is confirmed at the destination. Failures are logged AND surfaced (not
     * swallowed); a per-row failure does not abort the sweep.
     */
    private Future<Void> reconcileTransfers() {
        return DataVerticle.fileRepository.getStuckTransfers()
                .compose(stuck -> {
                    if (CollUtil.isNotEmpty(stuck)) {
                        log.info("Transfer reconciliation: classifying %d stuck 'transferring' row(s)".formatted(stuck.size()));
                        for (FileRecord record : stuck) {
                            try {
                                reconcileStuckTransfer(record);
                            } catch (Exception e) {
                                log.error("Transfer reconciliation: failed to reconcile %s: %s"
                                        .formatted(record.uniqueId(), e.getMessage()));
                            }
                        }
                    }
                    cleanOrphanedStagingTemps();
                    return Future.<Void>succeededFuture();
                })
                .recover(err -> {
                    // Surface a getStuckTransfers DB failure but do not abort boot: still clean temps and
                    // let the transfer loop make progress once the DB recovers.
                    log.error("Transfer reconciliation: getStuckTransfers FAILED (stuck rows not classified this boot): %s"
                            .formatted(err.getMessage()));
                    cleanOrphanedStagingTemps();
                    return Future.succeededFuture();
                });
    }

    /** The reconciliation action chosen for one stuck {@code transferring} row (see {@link #classifyStuckTransfer}). */
    enum StuckTransferAction {
        /** (a) the SIZE-VERIFIED payload is at the persisted destination: finalize forward, delete source. */
        RECOVER_FORWARD_DEST,
        /** (b) the SIZE-VERIFIED payload is in the staging temp: rename temp->dest (policy), finalize, delete source. */
        RECOVER_FORWARD_FROM_TEMP,
        /** (c) the source survives: retry the TRANSFER from the copy step (not a re-download). */
        RETRY_FROM_SOURCE,
        /** (d) no size-matching destination, no size-matching temp, no source: genuine loss -> re-queue. */
        REQUEUE_LOSS
    }

    /**
     * PURE classification of a stuck {@code transferring} row from PERSISTED TRUTH (the durable
     * {@link telegram.files.repository.TransferOperationRecord} written FIRST) plus size-verified
     * filesystem identity. Side-effect-free and package-private so every branch is unit tested without a
     * DB or a live verticle. Correctness contracts:
     * <ul>
     *   <li><b>identity by SIZE</b>: a file at the destination (or temp) counts as ours ONLY if its size
     *       matches {@code record.source_size} — an unrelated external file at the path is NOT our
     *       payload;</li>
     *   <li>the SOURCE is the durable anchor: whenever it survives we can always retry, so a temp is
     *       never the sole copy under this protocol (we COPY, never move) — recovery prefers the payload
     *       at the destination, then the temp, then a retry from the source.</li>
     * </ul>
     *
     * @param destIsPayload the persisted destination exists AND its size == record.source_size.
     * @param tempIsPayload the persisted staging temp exists AND its size == record.source_size.
     * @param sourceExists  the persisted source path exists on disk.
     */
    static StuckTransferAction classifyStuckTransfer(boolean destIsPayload,
                                                     boolean tempIsPayload,
                                                     boolean sourceExists) {
        if (destIsPayload) {
            return StuckTransferAction.RECOVER_FORWARD_DEST;
        }
        if (tempIsPayload) {
            return StuckTransferAction.RECOVER_FORWARD_FROM_TEMP;
        }
        if (sourceExists) {
            return StuckTransferAction.RETRY_FROM_SOURCE;
        }
        return StuckTransferAction.REQUEUE_LOSS;
    }

    /**
     * Reconciliation of ONE stuck {@code transferring} row from PERSISTED TRUTH with SIZE-verified
     * identity. Runs on the verticle's virtual thread, so the blocking filesystem checks and
     * {@code Future.await} DB writes are safe.
     */
    // Package-private for test.
    void reconcileStuckTransfer(FileRecord record) {
        String uniqueId = record.uniqueId();
        TransferOperationRecord op = Future.await(DataVerticle.fileRepository.getTransferOperation(uniqueId));

        if (op == null) {
            reconcileWithoutPersistedRecord(record);
            return;
        }

        String destPath = op.finalDestPath();
        String tempPath = op.stagingTempPath();
        String sourcePath = op.sourcePath();
        long size = op.sourceSize();
        boolean overwrite = "OVERWRITE".equals(op.overwritePolicy());
        boolean destIsPayload = isFileOfSize(destPath, size);
        boolean tempIsPayload = isFileOfSize(tempPath, size);
        boolean sourceExists = StrUtil.isNotBlank(sourcePath) && FileUtil.exist(sourcePath);

        StuckTransferAction action = classifyStuckTransfer(destIsPayload, tempIsPayload, sourceExists);
        switch (action) {
            case RECOVER_FORWARD_DEST -> {
                // The size-verified payload is already at the persisted destination. Finalize forward
                // (CAS + delete record), then delete the source. Never re-download.
                if (Future.await(DataVerticle.fileRepository.finalizeTransfer(uniqueId, destPath)) != null) {
                    deleteSourceAfterFinalize(sourcePath, uniqueId);
                    log.info("Transfer reconciliation: recovered FORWARD (payload at persisted destination) %s -> %s"
                            .formatted(uniqueId, destPath));
                } else {
                    afterNoOpFinalize(uniqueId);
                }
            }
            case RECOVER_FORWARD_FROM_TEMP -> {
                // The size-verified payload is in the staging temp (rename crashed). Complete the rename
                // (policy-honored) then finalize, then delete the source. Do NOT delete the temp.
                try {
                    DurableTransfer.completeRename(Path.of(tempPath), Path.of(destPath), overwrite, size);
                } catch (IOException e) {
                    log.error("Transfer reconciliation: failed to complete rename temp->dest for %s (%s -> %s): %s"
                            .formatted(uniqueId, tempPath, destPath, e.getMessage()));
                    return; // leave transferring; next boot retries (temp still present, source intact)
                }
                if (Future.await(DataVerticle.fileRepository.finalizeTransfer(uniqueId, destPath)) != null) {
                    deleteSourceAfterFinalize(sourcePath, uniqueId);
                    log.info("Transfer reconciliation: recovered FORWARD from staging temp %s -> %s"
                            .formatted(uniqueId, destPath));
                } else {
                    afterNoOpFinalize(uniqueId);
                }
            }
            case RETRY_FROM_SOURCE -> {
                // Source survives (durable anchor): discard any partial temp and retry the TRANSFER from
                // the copy step (NOT a re-download).
                deleteTempQuietly(tempPath);
                Future.await(DataVerticle.fileRepository.resetTransferToIdle(uniqueId));
                Future.await(DataVerticle.fileRepository.deleteTransferOperation(uniqueId));
                log.info("Transfer reconciliation: source present, reset to idle to retry transfer: %s".formatted(uniqueId));
            }
            case REQUEUE_LOSS -> requeueGenuineLoss(uniqueId);
        }
    }

    /** True iff {@code path} exists and its on-disk size equals {@code size} (payload identity). */
    private boolean isFileOfSize(String path, long size) {
        if (StrUtil.isBlank(path) || size < 0) {
            return false;
        }
        Path p = Path.of(path);
        try {
            return Files.isRegularFile(p) && Files.size(p) == size;
        } catch (IOException e) {
            log.warn("Transfer reconciliation: failed to stat %s: %s".formatted(path, e.getMessage()));
            return false;
        }
    }

    private void deleteSourceAfterFinalize(String sourcePath, String uniqueId) {
        if (StrUtil.isBlank(sourcePath)) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(sourcePath));
        } catch (IOException e) {
            log.warn("Transfer reconciliation: failed to delete source %s after finalize of %s: %s"
                    .formatted(sourcePath, uniqueId, e.getMessage()));
        }
    }

    private void deleteTempQuietly(String tempPath) {
        if (StrUtil.isBlank(tempPath)) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(tempPath));
        } catch (IOException e) {
            log.warn("Transfer reconciliation: failed to delete temp %s: %s".formatted(tempPath, e.getMessage()));
        }
    }

    /** Reconcile a stuck row that has NO persisted transfer-operation record (pre-persist crash / legacy). */
    private void reconcileWithoutPersistedRecord(FileRecord record) {
        String uniqueId = record.uniqueId();
        // No record => the crash was BEFORE the (persist-first) record write, so NO filesystem mutation
        // happened yet: local_path is still the inbox SOURCE. If it survives, retry the transfer; else
        // there is no persisted destination to recover to -> genuine loss. Never re-download a file we
        // could verify at a destination — but without a record there is no destination to verify.
        if (StrUtil.isNotBlank(record.localPath()) && FileUtil.exist(record.localPath())) {
            Future.await(DataVerticle.fileRepository.resetTransferToIdle(uniqueId));
            log.info("Transfer reconciliation: no persisted record but source present, reset to idle to retry transfer: %s".formatted(uniqueId));
        } else {
            requeueGenuineLoss(uniqueId);
        }
    }

    private void afterNoOpFinalize(String uniqueId) {
        // finalize was a no-op: an external service advanced download_status to processed/imported
        // between select and finalize (TOCTOU guard fired). Never-downgrade: leave it, drop our record.
        // Do NOT delete the source here — the external terminal owner may still reference it.
        Future.await(DataVerticle.fileRepository.deleteTransferOperation(uniqueId));
        log.info("Transfer reconciliation: forward-finalize no-op for %s (externally terminal or already finalized); left untouched".formatted(uniqueId));
    }

    private void requeueGenuineLoss(String uniqueId) {
        // download_status='completed' per getStuckTransfers, so processed/imported are excluded.
        Integer n = Future.await(DataVerticle.fileRepository.requeueCompletedMissingArtifact(List.of(uniqueId)));
        Future.await(DataVerticle.fileRepository.deleteTransferOperation(uniqueId));
        if (n != null && n > 0) {
            log.warn("Transfer reconciliation: genuine loss (no size-matching destination/temp, no source), re-queued for re-download: %s".formatted(uniqueId));
        } else {
            log.warn("Transfer reconciliation: could not re-queue %s (state changed concurrently?)".formatted(uniqueId));
        }
    }

    /**
     * Delete orphaned dest-local staging temps ({@code .<name>.tf-*.part}) left by a transfer that
     * crashed before its atomic rename. Only files matching our staging-temp pattern are removed, and
     * only directly under each configured transfer destination root and its immediate subtree — a
     * bounded walk so a huge destination tree cannot stall boot.
     */
    private void cleanOrphanedStagingTemps() {
        Set<String> destinations = new HashSet<>();
        for (SettingAutoRecords.Automation automation : autoRecords.automations) {
            if (automation.transfer != null && automation.transfer.rule != null
                && StrUtil.isNotBlank(automation.transfer.rule.destination)) {
                destinations.add(automation.transfer.rule.destination);
            }
        }
        for (String destination : destinations) {
            Path root = Path.of(destination);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> DurableTransfer.isStagingTemp(p.getFileName().toString()))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                                log.info("Transfer reconciliation: removed orphaned staging temp %s".formatted(p));
                            } catch (IOException e) {
                                log.warn("Transfer reconciliation: failed to remove staging temp %s: %s"
                                        .formatted(p, e.getMessage()));
                            }
                        });
            } catch (IOException e) {
                log.warn("Transfer reconciliation: failed to scan destination %s for staging temps: %s"
                        .formatted(destination, e.getMessage()));
            }
        }
    }

    private Future<Void> initEventConsumer() {
        vertx.eventBus().consumer(EventEnum.TELEGRAM_EVENT.address(), message -> {
            JsonObject jsonObject = (JsonObject) message.body();
            EventPayload payload = jsonObject.getJsonObject("payload").mapTo(EventPayload.class);
            if (payload == null || payload.type() != EventPayload.TYPE_FILE_STATUS) {
                return;
            }

            if (payload.data() != null && payload.data() instanceof Map<?, ?> data && StrUtil.isNotBlank((String) data.get("downloadStatus"))) {
                FileRecord.DownloadStatus downloadStatus = FileRecord.DownloadStatus.valueOf((String) data.get("downloadStatus"));
                if (downloadStatus != FileRecord.DownloadStatus.completed) {
                    return;
                }
                FileRecord fileRecord = Future.await(DataVerticle.fileRepository.getByUniqueId((String) data.get("uniqueId")));
                if (fileRecord == null || "thumbnail".equals(fileRecord.type())) {
                    // Thumbnails are internal preview files; never transfer them.
                    return;
                }

                SettingAutoRecords.Automation automation = null;
                if (fileRecord.threadChatId() != 0 && fileRecord.messageThreadId() != 0 && fileRecord.threadChatId() == fileRecord.chatId()) {
                    // thread message file,try to get the main message
                    FileRecord mainFileRecord = Future.await(DataVerticle.fileRepository.getMainFileByThread(
                            fileRecord.telegramId(),
                            fileRecord.threadChatId(),
                            fileRecord.messageThreadId()));
                    if (mainFileRecord != null) {
                        automation = autoRecords.getItem(mainFileRecord.telegramId(), mainFileRecord.chatId());
                    }
                } else {
                    automation = autoRecords.getItem(fileRecord.telegramId(), fileRecord.chatId());
                }

                if (automation == null || !automation.transfer.enabled || getTransfer(automation) == null) {
                    return;
                }

                if (addWaitingTransferFile(automation.telegramId, automation.chatId, fileRecord.uniqueId())) {
                    log.debug("Add file to transfer queue: %s".formatted(fileRecord.uniqueId()));
                }
            }
        });

        return Future.succeededFuture();
    }

    private void addHistoryFiles() {
        if (CollUtil.isEmpty(autoRecords.automations)) {
            return;
        }
        log.trace("Start scan history files for transfer");
        for (SettingAutoRecords.Automation automation : autoRecords.automations) {
            if (!automation.transfer.enabled
                || !automation.transfer.rule.transferHistory
                || automation.isComplete(AutomationState.HISTORY_TRANSFER_COMPLETE)) {
                continue;
            }
            Transfer transfer = getTransfer(automation);
            if (transfer == null) {
                continue;
            }
            Tuple3<List<FileRecord>, Long, Long> filesTuple = Future.await(DataVerticle.fileRepository.getFiles(automation.chatId,
                    Map.of("downloadStatus", FileRecord.DownloadStatus.completed.name(),
                            "transferStatus", FileRecord.TransferStatus.idle.name()
                    )
            ));
            List<FileRecord> files = filesTuple.v1;
            if (CollUtil.isEmpty(files)) {
                log.debug("No history files found for transfer: %s".formatted(automation.uniqueKey()));
                automation.complete(AutomationState.HISTORY_TRANSFER_COMPLETE);
                continue;
            }

            int count = 0;
            for (FileRecord fileRecord : files) {
                if ("thumbnail".equals(fileRecord.type())) {
                    // Thumbnails are internal preview files; never transfer them.
                    continue;
                }
                if (addWaitingTransferFile(fileRecord)) {
                    count++;
                }
            }

            if (count > 0) {
                log.info("Add history files to transfer queue: %s".formatted(count));
                break;
            }
        }
    }

    private boolean addWaitingTransferFile(FileRecord fileRecord) {
        return addWaitingTransferFile(fileRecord.telegramId(), fileRecord.chatId(), fileRecord.uniqueId());
    }

    private boolean addWaitingTransferFile(long telegramId, long chatId, String uniqueId) {
        WaitingTransferFile waitingTransferFile = new WaitingTransferFile(telegramId, chatId, uniqueId);
        if (!waitingTransferFiles.contains(waitingTransferFile)) {
            waitingTransferFiles.add(waitingTransferFile);
            return true;
        }
        return false;
    }

    private Transfer getTransfer(SettingAutoRecords.Automation automation) {
        if (automation == null || !automation.transfer.enabled) {
            return null;
        }

        SettingAutoRecords.TransferRule transferRule = automation.transfer.rule;

        if (transfers.containsKey(automation.uniqueKey())) {
            Transfer transfer = transfers.get(automation.uniqueKey());
            if (!transfer.isRuleUpdated(transferRule)) {
                return transfer;
            } else {
                log.debug("Transfer rule updated: %s".formatted(automation.uniqueKey()));
                transfers.remove(automation.uniqueKey());
            }
        }

        return transfers.computeIfAbsent(automation.uniqueKey(), _ -> {
            Transfer transfer = Transfer.create(transferRule);
            transfer.transferStatusUpdated = updated ->
                    updateTransferStatus(updated.fileRecord(), updated.transferStatus(), updated.localPath());
            // Persist the durable transfer-operation record FIRST — before any filesystem mutation
            // (awaited on the verticle's virtual thread). If the persist fails, throw so DurableTransfer
            // aborts BEFORE staging (source untouched).
            transfer.transferOperationPersister = operation -> {
                Throwable err = Future.await(
                        DataVerticle.fileRepository.recordTransferOperation(operation)
                                .map((Throwable) null)
                                .otherwise(t -> t));
                if (err != null) {
                    throw new java.io.IOException("Failed to persist transfer operation for " + operation.uniqueId(), err);
                }
            };
            // CAS-finalize transfer_status + delete the record in one transaction; returns whether it
            // applied (so DurableTransfer only deletes the source on success — a TOCTOU no-op leaves the
            // source and payload for reconciliation).
            transfer.transferFinalizer = (uniqueId, finalPath) -> {
                Object outcome = Future.await(
                        DataVerticle.fileRepository.finalizeTransfer(uniqueId, finalPath)
                                .map(applied -> (Object) (applied != null))
                                .otherwise(t -> (Object) t));
                if (outcome instanceof Throwable t) {
                    throw new java.io.IOException("Failed to finalize transfer for " + uniqueId, t);
                }
                return (Boolean) outcome;
            };
            // Clean up the durable operation record after a finalize no-op (the finalize deletes it only
            // when it applies; on a no-op the row is externally terminal and excluded from reconciliation,
            // so without this the record would leak forever).
            transfer.transferOperationCleaner = uniqueId -> {
                Throwable err = Future.await(
                        DataVerticle.fileRepository.deleteTransferOperation(uniqueId)
                                .map((Throwable) null)
                                .otherwise(t -> t));
                if (err != null) {
                    throw new java.io.IOException("Failed to clean transfer operation for " + uniqueId, err);
                }
            };
            return transfer;
        });
    }

    public void startTransfer() {
        if (beingTransferred != null) {
            return;
        }
        try {
            WaitingTransferFile waitingTransferFile = waitingTransferFiles.poll(1, TimeUnit.SECONDS);
            if (waitingTransferFile == null) {
                log.trace("No file to transfer");
                return;
            }
            Transfer transfer = transfers.get("%d:%d".formatted(waitingTransferFile.telegramId(), waitingTransferFile.chatId()));
            if (transfer == null) {
                return;
            }
            if (beingTransferred == transfer) {
                waitingTransferFiles.add(waitingTransferFile);
                log.debug("Transfer is busy: %s".formatted(waitingTransferFile.uniqueId));
                return;
            }
            FileRecord fileRecord = Future.await(DataVerticle.fileRepository.getByUniqueId(waitingTransferFile.uniqueId));
            if (fileRecord == null) {
                log.error("File not found: %s".formatted(waitingTransferFile.uniqueId));
                return;
            }

            startTransfer(fileRecord, transfer);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                log.debug("Transfer loop interrupted");
            } else {
                log.error(e, "Transfer error");
            }
        }
    }

    public void startTransfer(FileRecord fileRecord, Transfer transfer) {
        if (isStopped) {
            return;
        }
        if (!fileRecord.isDownloadStatus(FileRecord.DownloadStatus.completed)
            || StrUtil.isBlank(fileRecord.localPath())) {
            log.warn("File {} is not downloaded yet", fileRecord.id());
            return;
        }
        if (fileRecord.transferStatus() != null
            && !fileRecord.isTransferStatus(FileRecord.TransferStatus.idle)) {
            log.debug("File {} transfer status is not idle: {}", fileRecord.id(), fileRecord.transferStatus());
            return;
        }

        beingTransferred = transfer;
        transfer.transfer(fileRecord);
        beingTransferred = null;
    }

    private void updateTransferStatus(FileRecord fileRecord, FileRecord.TransferStatus transferStatus, String localPath) {
        // The transfer FINALIZE (transferring -> completed) is performed INSIDE DurableTransfer via the
        // wired transferFinalizer (CAS transfer_status + delete record, then source-delete). So a
        // 'completed' status update here is publish-ONLY (the DB write already happened) — do NOT
        // re-finalize. Every other transition (transferring/idle/error) still does its DB write.
        if (transferStatus == FileRecord.TransferStatus.completed) {
            EventPayload payload = EventPayload.build(EventPayload.TYPE_FILE_STATUS, new JsonObject()
                    .put("fileId", fileRecord.id())
                    .put("uniqueId", fileRecord.uniqueId())
                    .put("transferStatus", FileRecord.TransferStatus.completed.name())
                    .put("localPath", localPath)
            );
            vertx.eventBus().publish(EventEnum.TELEGRAM_EVENT.address(),
                    JsonObject.of("telegramId", fileRecord.telegramId(), "payload", JsonObject.mapFrom(payload))
            );
            return;
        }
        Future.await(DataVerticle.fileRepository.updateTransferStatus(fileRecord.uniqueId(), transferStatus, localPath)
                .onSuccess(fileUpdated -> {
                    if (fileUpdated != null && !fileUpdated.isEmpty()) {
                        EventPayload payload = EventPayload.build(EventPayload.TYPE_FILE_STATUS, new JsonObject()
                                .put("fileId", fileRecord.id())
                                .put("uniqueId", fileRecord.uniqueId())
                                .put("transferStatus", fileUpdated.getString("transferStatus"))
                                .put("localPath", fileUpdated.getString("localPath"))
                        );
                        vertx.eventBus().publish(EventEnum.TELEGRAM_EVENT.address(),
                                JsonObject.of("telegramId", fileRecord.telegramId(), "payload", JsonObject.mapFrom(payload))
                        );
                    }
                }));
    }

    private record WaitingTransferFile(long telegramId, long chatId, String uniqueId) {
    }
}
