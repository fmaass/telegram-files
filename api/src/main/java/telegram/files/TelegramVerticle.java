package telegram.files;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.codec.Base64;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.VertxException;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.drinkless.tdlib.TdApi;
import org.jooq.lambda.tuple.Tuple;
import org.jooq.lambda.tuple.Tuple2;
import telegram.files.repository.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TelegramVerticle extends AbstractVerticle {

    private static final Log log = LogFactory.get();

    // volatile: created on this verticle's context but read from other verticles/contexts
    // (HttpVerticle/AutoDownload/Preload/HistoryDiscovery call client.execute). Safe publication.
    public volatile TelegramClient client;

    private TelegramChats telegramChats;

    // volatile cross-context fields (D4 safe publication): written on THIS verticle's context
    // (TDLib ingress is marshaled here) but read from Http/AutoDownload/Preload/AutomationsHolder.
    public volatile boolean authorized = false;

    public volatile TdApi.AuthorizationState lastAuthorizationState;

    public String rootPath;

    private volatile String proxyName;

    private String rootId;

    private boolean needDelete = false;

    public volatile TelegramRecord telegramRecord;

    private volatile AvgSpeed avgSpeed = new AvgSpeed();

    private long avgSpeedPersistenceTimerId;

    private long downloadStatusReconciliationTimerId;

    public volatile TdApi.ConnectionState lastConnectionState;

    private volatile long lastFileEventTime;

    private volatile long lastFileDownloadEventTime;

    // In-flight guards (D7): a slow reconciliation pass must not overlap the next timer tick.
    private volatile boolean reconciliationInFlight = false;

    // Outstanding worker/callback DB operations spawned off the request path (the off-loop
    // syncCompletedFilesStatus sweep and the reconciliation pass). shutdown/close() AWAITS these
    // before the pool closes, so no callback/worker/reconciliation DB write can execute against a
    // closed pool. A ConcurrentHashMap-backed set: registration happens on the verticle context
    // (single-threaded) but completion handlers that self-deregister may run on other contexts.
    private final Set<Future<?>> outstandingOperations = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Set once close()/stop() begins draining, so no NEW operation is registered after the drain
    // snapshot — a late registrant would otherwise race past the join and hit a closed pool.
    private volatile boolean draining = false;

    public TelegramVerticle(String rootPath) {
        this.rootPath = rootPath;
    }

    /**
     * Register an in-flight worker/callback DB operation so {@link #close} awaits it before the pool
     * closes. Self-deregisters on completion. If a drain has already begun the operation is NOT
     * tracked (the caller must not have started new pool work after shutdown) — it is returned as-is;
     * callers gate their own work on {@code draining} first.
     */
    private <T> Future<T> trackOperation(Future<T> operation) {
        if (draining) {
            return operation;
        }
        outstandingOperations.add(operation);
        operation.onComplete(_ -> outstandingOperations.remove(operation));
        return operation;
    }

    // Package-private test seams for the shutdown-drain invariant (no live TDLib client required).
    <T> Future<T> trackOperationForTest(Future<T> operation) {
        return trackOperation(operation);
    }

    Future<Void> drainOutstandingOperationsForTest() {
        return drainOutstandingOperations();
    }

    boolean isDrainingForTest() {
        return draining;
    }

    int outstandingOperationCountForTest() {
        return outstandingOperations.size();
    }

    // Test-only override of the file-update DB write (persistFileUpdate) so the onFileUpdated
    // track+gate wrapper can be exercised with a controllable-latency write and no real DB/native
    // client. When set, onFileUpdated uses this instead of the real getByUniqueId+write chain.
    private java.util.function.Supplier<Future<Void>> fileUpdatePersistOverrideForTest;

    void setFileUpdatePersistOverrideForTest(java.util.function.Supplier<Future<Void>> override) {
        this.fileUpdatePersistOverrideForTest = override;
    }

    // Package-private seam: drive the REAL onFileUpdated gate+track path with a given update.
    void handleFileUpdateForTest(TdApi.UpdateFile updateFile) {
        onFileUpdated(updateFile);
    }

    /**
     * Hermetic-gateway injection seam (Phase-5 E2E; APP_ENV != prod only, gated by
     * {@link telegram.files.http.HermeticGateway#isEnabled()} at the call site). Marshals a FAKE
     * {@code UpdateFile} onto THIS verticle's context and runs the REAL {@code onFileUpdated} — so the
     * fake completion flows through the Phase-2 completion CAS and Phase-3 transfer publish exactly as
     * a genuine TDLib callback would. This mutates NO frontend state directly; it drives the real
     * pipeline. Public so the gateway (a different package) can reach it; behaviorally a no-op unless
     * the gateway that calls it is enabled.
     * <p>
     * <b>Defense-in-depth prod safety.</b> This method REFUSES under {@code APP_ENV=prod} even if the
     * HTTP gate is somehow bypassed — the injector must never mutate the real pipeline in production.
     * The check lives on the mutation entrypoint itself, not only on the (already-gated) HTTP call site.
     */
    public void injectUpdateForGateway(TdApi.UpdateFile updateFile) {
        injectUpdateForGateway(updateFile, Config.isProd());
    }

    /**
     * Package-visible overload carrying the prod flag so the prod-refusal guard can be unit-tested
     * WITHOUT mutating the static-final {@code Config.APP_ENV}. When {@code isProd} is true it refuses
     * (throws) and never touches the pipeline; otherwise it marshals the update onto this verticle's
     * context and runs the REAL {@code onFileUpdated}.
     */
    void injectUpdateForGateway(TdApi.UpdateFile updateFile, boolean isProd) {
        if (isProd) {
            log.error("[%s] REFUSED gateway update injection under APP_ENV=prod — this must never happen"
                    .formatted(getRootId()));
            throw new IllegalStateException("hermetic gateway injection is forbidden under APP_ENV=prod");
        }
        io.vertx.core.Context ctx = context;
        if (ctx == null) {
            onFileUpdated(updateFile);
        } else {
            ctx.runOnContext(_ -> onFileUpdated(updateFile));
        }
    }

    /**
     * Hermetic-gateway bootstrap (Phase-5 E2E; enabled ONLY via
     * {@link telegram.files.http.HermeticGateway#isEnabled()} at the call site — never in prod). Deploy
     * this verticle with a FAKE {@link TelegramClient.Sender} instead of a real native TDLib client:
     * {@code initializeForTest} binds the owning context + the fake sender so NO native Client is
     * constructed (which would abort the JVM). The verticle is marked authorized with a synthetic
     * {@link TelegramRecord} so it registers as a live account for the file-list enrichment and the
     * download trigger — all backed by the fake sender, so it never touches the real Telegram network.
     */
    public void startHermetic(Promise<Void> startPromise, long telegramId, TelegramClient.Sender fakeSender) {
        this.telegramRecord = new TelegramRecord(telegramId, "hermetic", this.rootPath, this.proxyName);
        this.authorized = true;
        this.client = new TelegramClient();
        this.client.initializeForTest(context, fakeSender);
        this.telegramChats = new TelegramChats(this.client);
        startPromise.complete();
    }

    /** Join every currently-outstanding worker/callback DB operation (used by the shutdown drain). */
    private Future<Void> drainOutstandingOperations() {
        draining = true;
        List<Future<?>> snapshot = new java.util.ArrayList<>(outstandingOperations);
        if (snapshot.isEmpty()) {
            return Future.succeededFuture();
        }
        log.info("[%s] Draining %d outstanding DB operation(s) before pool close".formatted(getRootId(), snapshot.size()));
        return Future.join(snapshot).mapEmpty();
    }

    public TelegramVerticle(TelegramRecord telegramRecord) {
        this.telegramRecord = telegramRecord;
        this.rootPath = telegramRecord.rootPath();
        this.proxyName = telegramRecord.proxy();
    }

    public String getRootId() {
        if (StrUtil.isNotBlank(this.rootId)) return rootId;

        this.rootId = StrUtil.subAfter(this.rootPath, '-', true);
        return this.rootId;
    }

    public Object getId() {
        return telegramRecord == null ? this.getRootId() : telegramRecord.id();
    }

    public void setProxy(String proxyName) {
        this.proxyName = proxyName;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        client = new TelegramClient();
        telegramChats = new TelegramChats(client);
        TelegramUpdateHandler telegramUpdateHandler = new TelegramUpdateHandler();
        telegramUpdateHandler.setOnAuthorizationStateUpdated(this::onAuthorizationStateUpdated);
        telegramUpdateHandler.setOnFileUpdated(this::onFileUpdated);
        telegramUpdateHandler.setOnFileDownloadsUpdated(this::onFileDownloadsUpdated);
        telegramUpdateHandler.setOnChatUpdated(telegramChats::onChatUpdated);
        telegramUpdateHandler.setOnMessageReceived(this::onMessageReceived);
        telegramUpdateHandler.setOnConnectionStateUpdated(this::onConnectionStateUpdated);

        // Bind the client to THIS verticle's context so ALL TDLib ingress (update callbacks AND
        // per-request result callbacks) is marshaled onto the verticle's own thread (D4/D5 root fix).
        client.initialize(telegramUpdateHandler, this::handleException, this::handleException, context);
        Future.all(initEventConsumer(), initAvgSpeed())
                .compose(_ -> this.enableProxy(this.proxyName))
                .compose(_ -> this.initDownloadStatusReconciliation())
                .onSuccess(_ -> startPromise.complete())
                .onFailure(startPromise::fail);
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        this.close(false)
                .onComplete(stopPromise);
    }

    public Future<Void> close(boolean needDelete) {
        // Stop new passes first so nothing new is registered while we drain.
        if (downloadStatusReconciliationTimerId != 0) {
            vertx.cancelTimer(downloadStatusReconciliationTimerId);
            downloadStatusReconciliationTimerId = 0;
        }
        if (avgSpeedPersistenceTimerId != 0) {
            vertx.cancelTimer(avgSpeedPersistenceTimerId);
            avgSpeedPersistenceTimerId = 0;
        }
        // Await every in-flight worker/callback DB operation (the off-loop syncCompletedFilesStatus
        // sweep and the reconciliation pass, each of which issues DB writes) BEFORE closing the pool.
        // These operations are internally bounded by their 15s per-call TDLib timeouts, so the drain
        // cannot hang indefinitely. This guarantees no callback/worker/reconciliation DB write runs
        // against a closed pool after Start.close().
        return drainOutstandingOperations()
                // D5: bound the Close with a timeout so shutdown cannot hang forever waiting on TDLib.
                .compose(_ -> client.execute(new TdApi.Close(), 10_000, vertx))
                .onSuccess(_ -> {
                    log.info("[%s] Telegram account closed".formatted(this.getRootId()));
                    this.needDelete = needDelete;
                })
                .onFailure(e -> log.error("[%s] Failed to close telegram account: %s".formatted(this.getRootId(), e.getMessage())))
                // Reject every still-outstanding request promise (cancellation on close) regardless of
                // the Close outcome — after the client closes no per-request callback will fire again.
                .onComplete(_ -> client.rejectAllOutstanding(
                        new IllegalStateException("[%s] TDLib client closed".formatted(this.getRootId()))))
                .mapEmpty();
    }

    public boolean check() {
        if (StrUtil.isBlank(this.rootPath) || !FileUtil.exist(this.rootPath)) {
            log.error("[%s] Telegram account is invalid, root path: %s not exist.".formatted(this.getRootId(), this.rootPath));
            return false;
        }
        return true;
    }

    public Future<JsonObject> getTelegramAccount() {
        return Future.future(promise -> {
            if (!authorized) {
                JsonObject jsonObject = new JsonObject()
                        .put("id", this.getRootId())
                        .put("name", this.getRootId())
                        .put("phoneNumber", "")
                        .put("avatar", "")
                        .put("status", "inactive")
                        .put("rootPath", this.rootPath)
                        .put("isPremium", false)
                        .put("lastAuthorizationState", lastAuthorizationState)
                        .put("proxy", this.proxyName);
                if (this.telegramRecord != null) {
                    jsonObject.put("id", Convert.toStr(this.telegramRecord.id()))
                            .put("name", this.telegramRecord.firstName());
                }
                promise.complete(jsonObject);
                return;
            }
            client.execute(new TdApi.GetMe())
                    .onSuccess(user -> {
                        JsonObject result = new JsonObject()
                                .put("id", Convert.toStr(user.id))
                                .put("name", StrUtil.join(user.firstName, " ", user.lastName))
                                .put("phoneNumber", user.phoneNumber)
                                .put("avatar", Base64.encode((byte[]) BeanUtil.getProperty(user, "profilePhoto.minithumbnail.data")))
                                .put("status", "active")
                                .put("rootPath", this.rootPath)
                                .put("isPremium", user.isPremium)
                                .put("proxy", this.proxyName);
                        promise.complete(result);
                    })
                    .onFailure(e -> {
                        log.error("[%s] Failed to get telegram account: %s".formatted(this.getRootId(), e.getMessage()));
                        promise.fail(e);
                    });
        });
    }

    public Future<JsonArray> getChats(Long activatedChatId, String query, boolean archived) {
        Set<Long> enabledChatIds = AutomationsHolder.INSTANCE.autoRecords().getDownloadEnabledItems().stream()
                .filter(item -> item.telegramId == this.telegramRecord.id())
                .map(item -> item.chatId)
                .collect(Collectors.toSet());
        return TelegramConverter.convertChat(this.telegramRecord.id(), telegramChats.getChatList(activatedChatId, query, 100, archived, enabledChatIds));
    }

    public TdApi.Chat getChat(long chatId) {
        return telegramChats.getChat(chatId);
    }

    public Future<JsonObject> getChatFiles(long chatId, Map<String, String> filter) {
        boolean offline = Convert.toBool(filter.get("offline"), false);
        if (offline) {
            return FileRecordRetriever.getFiles(chatId, filter);
        } else {
            long messageThreadId = Convert.toLong(filter.get("messageThreadId"), 0L);
            TdApi.SearchChatMessages searchChatMessages = new TdApi.SearchChatMessages();
            searchChatMessages.chatId = chatId;
            searchChatMessages.query = filter.get("search");
            searchChatMessages.fromMessageId = Convert.toLong(filter.get("fromMessageId"), 0L);
            searchChatMessages.offset = Convert.toInt(filter.get("offset"), 0);
            searchChatMessages.limit = Convert.toInt(filter.get("limit"), 20);
            searchChatMessages.filter = TdApiHelp.getSearchMessagesFilter(filter.get("type"));
            searchChatMessages.topicId = messageThreadId > 0 ? new TdApi.MessageTopicThread(messageThreadId) : null;

            return (Objects.equals(filter.get("downloadStatus"), FileRecord.DownloadStatus.idle.name()) ?
                    this.getIdleChatFiles(searchChatMessages, 0) :
                    client.execute(searchChatMessages))
                    .compose(t ->
                            // Eager thumbnail preload REMOVED (regression from PR #132-#135):
                            // it called downloadThumbnail() for EVERY message on every online
                            // list-load/scan, flooding TDLib file events and the HttpVerticle
                            // TELEGRAM_EVENT consumer until Vert.x paused it and new-message
                            // ingestion froze (albums stopped downloading). Previews are now
                            // minithumbnail-only until a file is downloaded (see the thumbnail
                            // previews ADR). The eager-preload method was deleted so an upstream
                            // merge that reintroduces it must be reviewed, not silently re-armed.
                            TelegramConverter.convertFiles(this.telegramRecord.id(), t));
        }
    }

    private Future<TdApi.FoundChatMessages> getIdleChatFiles(TdApi.SearchChatMessages searchChatMessages, int seq) {
        if (seq != 0) {
            // Increase the limit and reduce the number of requests
            searchChatMessages.limit = 100;
        }
        return client.execute(searchChatMessages)
                .compose(foundChatMessages -> {
                    TdApi.Message[] messages = Stream.of(foundChatMessages.messages)
                            .filter(message ->
                                    TdApiHelp.getFileHandler(message)
                                            .map(TdApiHelp.FileHandler::getFile)
                                            .map(file -> file.local == null || (
                                                    !file.local.isDownloadingActive
                                                    && !file.local.isDownloadingCompleted
                                                    && file.local.downloadedSize == 0
                                            ))
                                            .orElse(false)
                            )
                            .toArray(TdApi.Message[]::new);
                    if (ArrayUtil.isEmpty(messages) && foundChatMessages.nextFromMessageId != 0) {
                        searchChatMessages.fromMessageId = foundChatMessages.nextFromMessageId;
                        return getIdleChatFiles(searchChatMessages, seq + 1);
                    } else {
                        foundChatMessages.messages = messages;
                        return Future.succeededFuture(foundChatMessages);
                    }
                });
    }

    public Future<JsonObject> getChatFilesCount(long chatId) {
        return Future.all(
                Stream.of(new TdApi.SearchMessagesFilterPhotoAndVideo(),
                                new TdApi.SearchMessagesFilterPhoto(),
                                new TdApi.SearchMessagesFilterVideo(),
                                new TdApi.SearchMessagesFilterAudio(),
                                new TdApi.SearchMessagesFilterDocument())
                        .map(filter -> client.execute(
                                                new TdApi.GetChatMessageCount(chatId,
                                                        null,
                                                        filter,
                                                        false)
                                        )
                                        .map(count -> new JsonObject()
                                                .put("type", TdApiHelp.getSearchMessagesFilterType(filter))
                                                .put("count", count.count)
                                        )
                        )
                        .toList()
        ).map(counts -> {
            JsonObject result = new JsonObject();
            counts.<JsonObject>list().forEach(count -> result.put(count.getString("type"), count.getInteger("count")));
            return result;
        });
    }

    public Future<JsonObject> getChatDownloadStatistics(long chatId) {
        // Get automation config to check for history cutoff
        Integer historySince = null;
        var automation = AutomationsHolder.INSTANCE.autoRecords().getItems(this.telegramRecord.id()).get(chatId);
        if (automation != null && automation.download != null && automation.download.rule != null) {
            historySince = automation.download.rule.historySince;
        }
        return DataVerticle.fileRepository.getChatDownloadStatistics(this.telegramRecord.id(), chatId, historySince);
    }

    public Future<JsonObject> parseLink(String link) {
        return client.execute(new TdApi.GetMessageLinkInfo(link))
                .compose(messageLinkInfo -> {
                    if (messageLinkInfo.message == null) {
                        return Future.failedFuture("Message not found for link: " + link);
                    }
                    return FileRecordRetriever.getAlbumMessages(this.telegramRecord.id(), messageLinkInfo.message);
                })
                .compose(messages -> TelegramConverter.convertFiles(this.telegramRecord.id(), messages)
                        .map(files -> new JsonObject()
                                .put("files", files)
                                .put("count", files.size())
                                .put("size", files.size())
                                .put("nextFromMessageId", 0L) // No next message ID for link parsing
                        ));
    }

    public Future<Tuple2<String, String>> loadPreview(String uniqueId) {
        return DataVerticle.fileRepository
                .getByUniqueId(uniqueId)
                .compose(fileRecord -> {
                    if (fileRecord == null || !fileRecord.isDownloadStatus(FileRecord.DownloadStatus.completed)
                        || !FileUtil.exist(fileRecord.localPath())) {
                        return Future.failedFuture("File not found or not downloaded");
                    }
                    return Future.succeededFuture(Tuple.tuple(fileRecord.localPath(), fileRecord.mimeType()));
                });
    }

    /**
     * Fetch a message by ID, with automatic server fallback.
     * <p>
     * TDLib's getMessage() only returns messages from the local cache, which is
     * empty after a restart. This method falls back to getChatHistory() to fetch
     * the message from the Telegram server when the local cache misses.
     *
     * @return the message, or a failed future with 404 if the message was deleted
     */
    public Future<TdApi.Message> fetchMessage(long chatId, long messageId) {
        return client.execute(new TdApi.GetMessage(chatId, messageId))
                .recover(err -> {
                    if (!(err instanceof TelegramRunException tre) || tre.getError().code != 404) {
                        return Future.failedFuture(err);
                    }
                    // Not in local cache — fetch from server via getChatHistory.
                    // getChatHistory returns messages with id < fromMessageId,
                    // so we use messageId + 1 to include the target message.
                    return client.execute(new TdApi.GetChatHistory(chatId, messageId + 1, 0, 1, false))
                            .compose(history -> {
                                if (history.messages != null && history.messages.length > 0
                                        && history.messages[0].id == messageId) {
                                    return Future.succeededFuture(history.messages[0]);
                                }
                                return Future.failedFuture(new TelegramRunException(
                                        new TdApi.Error(404, "Message %d not found in chat %d (deleted from channel)"
                                                .formatted(messageId, chatId))));
                            });
                });
    }

    /**
     * The outcome of a download trigger for THIS call — determined by the atomic-claim RESULT (not by
     * re-reading the row afterward). A lost claim must NEVER be reported as a success/claim.
     */
    public enum ClaimOutcome {
        /** THIS call won the idle->downloading atomic claim (rowCount==1) and started the download. */
        CLAIMED,
        /** THIS call lost the atomic claim (row was not idle for us / another worker won it). */
        LOST,
        /** The file was already active/terminal before any claim was attempted (a conflict). */
        ALREADY_ACTIVE,
        /** TDLib reported the file already fully downloaded; it was synced, not claimed. */
        SYNCED
    }

    /** The trigger result: the current file_record plus THIS call's authoritative claim outcome. */
    public record DownloadTrigger(FileRecord record, ClaimOutcome outcome) {
        public boolean claimed() {
            return outcome == ClaimOutcome.CLAIMED;
        }
    }

    public Future<FileRecord> startDownload(Long chatId, Long messageId, Integer fileId) {
        // Preserve the legacy Future<FileRecord> contract for existing callers (AutoDownloadVerticle).
        return startDownloadWithOutcome(chatId, messageId, fileId).map(DownloadTrigger::record);
    }

    /**
     * Like {@link #startDownload} but returns THIS call's authoritative {@link ClaimOutcome} alongside
     * the record, so the API layer can distinguish "I won the atomic claim" (CLAIMED) from "another
     * worker won it" (LOST) WITHOUT re-reading the row state (which may belong to the other attempt).
     * The state logic is unchanged — only the outcome is now surfaced.
     */
    public Future<DownloadTrigger> startDownloadWithOutcome(Long chatId, Long messageId, Integer fileId) {
        return Future.all(
                        client.execute(new TdApi.GetFile(fileId)),
                        fetchMessage(chatId, messageId),
                        client.execute(new TdApi.GetMessageThread(chatId, messageId), true)
                )
                .compose(results -> {
                    TdApi.File file = results.resultAt(0);
                    return DataVerticle.fileRepository.getByUniqueId(file.remote.uniqueId)
                            .map(fileRecord -> Tuple.tuple(file,
                                    results.<TdApi.Message>resultAt(1),
                                    results.<TdApi.MessageThreadInfo>resultAt(2),
                                    fileRecord
                            ));
                })
                .compose(results -> {
                    TdApi.File file = results.v1;
                    TdApi.Message message = results.v2;
                    TdApi.MessageThreadInfo messageThreadInfo = results.v3;
                    FileRecord dbFileRecord = results.v4;
                    if (file.local != null) {
                        if (file.local.isDownloadingCompleted) {
                            return syncFileDownloadStatus(file, message, messageThreadInfo)
                                    .compose(_ -> DataVerticle.fileRepository.getByUniqueId(file.remote.uniqueId))
                                    .map(r -> new DownloadTrigger(r, ClaimOutcome.SYNCED));
                        }
                        if (file.local.isDownloadingActive) {
                            return Future.failedFuture("File is downloading");
                        }
//                        return Future.failedFuture("Unknown file download status");
                    }
                    // Reject only the states that are NOT a legal download start: a live download or a
                    // terminal/external-owned state. idle/paused/error ARE claimable start states
                    // (canTransitionTo allows paused->downloading and error->downloading) and fall
                    // through to the atomic-claim block below, which gates each on a single-winner CAS.
                    if (dbFileRecord != null
                        && !dbFileRecord.isDownloadStatus(FileRecord.DownloadStatus.idle)
                        && !dbFileRecord.isDownloadStatus(FileRecord.DownloadStatus.paused)
                        && !dbFileRecord.isDownloadStatus(FileRecord.DownloadStatus.error)) {
                        return Future.failedFuture("File is already downloading or completed");
                    }

                    TdApiHelp.FileHandler<? extends TdApi.MessageContent> fileHandler = TdApiHelp.getFileHandler(message)
                            .orElseThrow(() -> VertxException.noStackTrace("not support message type"));
                    FileRecord fileRecord = fileHandler.convertFileRecord(telegramRecord.id()).withThreadInfo(messageThreadInfo);
                    return DataVerticle.fileRepository.createIfNotExist(fileRecord)
                            .compose(created -> {
                                if (!created) {
                                    // FileRecord already exists, get it and update file ID if needed
                                    return DataVerticle.fileRepository.getByUniqueId(fileRecord.uniqueId())
                                            .compose(existingRecord -> {
                                                if (existingRecord == null) {
                                                    return Future.succeededFuture(fileRecord);
                                                }
                                                // Update file ID if needed
                                                return DataVerticle.fileRepository.updateFileId(fileRecord.id(), fileRecord.uniqueId())
                                                        .map(ignore -> existingRecord);
                                            });
                                }
                                // FileRecord was just created, return it
                                return Future.succeededFuture(fileRecord);
                            })
                            .compose(record -> {
                                // Check if we should start the download
                                // Don't start if already downloading, completed, processed, or imported
                                if (record.isDownloadStatus(FileRecord.DownloadStatus.downloading) ||
                                    record.isDownloadStatus(FileRecord.DownloadStatus.completed) ||
                                    record.isDownloadStatus(FileRecord.DownloadStatus.processed) ||
                                    record.isDownloadStatus(FileRecord.DownloadStatus.imported)) {
                                    // The row is already active/terminal — NOT claimed by THIS call.
                                    return Future.succeededFuture(new DownloadTrigger(record, ClaimOutcome.ALREADY_ACTIVE));
                                }

                                // Atomic claim for EVERY claimable start state (idle OR a paused/error
                                // re-download): ONE transaction CASes the exact current state ->
                                // downloading AND mints the owning active attempt. Both-or-neither,
                                // single-winner under concurrency (the one-active-attempt index rejects a
                                // duplicate). If the row is not in THIS call's observed start state (lost
                                // the race to another worker, or externally changed mid-flight), the claim
                                // returns null and we must NOT proceed — only the CAS winner downloads.
                                // The paused/error re-download therefore has exactly one winner too.
                                FileRecord.DownloadStatus fromState;
                                if (record.isDownloadStatus(FileRecord.DownloadStatus.idle)) {
                                    fromState = FileRecord.DownloadStatus.idle;
                                } else if (record.isDownloadStatus(FileRecord.DownloadStatus.paused)) {
                                    fromState = FileRecord.DownloadStatus.paused;
                                } else if (record.isDownloadStatus(FileRecord.DownloadStatus.error)) {
                                    fromState = FileRecord.DownloadStatus.error;
                                } else {
                                    // Unknown/legacy status: not claimable via a defined transition.
                                    return Future.succeededFuture(new DownloadTrigger(record, ClaimOutcome.ALREADY_ACTIVE));
                                }

                                // idle: never retire a lingering active attempt (it means a concurrent
                                // claim is in flight -> this call must lose). paused/error re-download:
                                // retire the row's own prior attempt so the fresh claim can mint one.
                                boolean retireExistingActive = fromState != FileRecord.DownloadStatus.idle;
                                Future<String> claimFuture = DataVerticle.fileRepository.claimForDownloadFrom(
                                        record.id(), record.uniqueId(), getRootId(),
                                        java.util.Set.of(fromState), retireExistingActive);

                                return claimFuture.compose(attemptId -> {
                                    if (attemptId == null) {
                                        // Lost the atomic CAS — another worker won this (re-)download.
                                        // THIS call did NOT win: report LOST, never a claim. The record
                                        // may now read 'downloading' because the WINNER set it; we must
                                        // NOT infer success from that state.
                                        log.debug("[%s] startDownload claim lost for uniqueId=%s (from %s)"
                                                .formatted(getRootId(), record.uniqueId(), fromState));
                                        return Future.succeededFuture(new DownloadTrigger(record, ClaimOutcome.LOST));
                                    }
                                    // Start the download — THIS call won the atomic claim (rowCount==1).
                                    return client.execute(new TdApi.AddFileToDownloads(fileId, chatId, messageId, 32))
                                            .onSuccess(ignore -> {
                                                sendEvent(EventPayload.build(EventPayload.TYPE_FILE_STATUS, new JsonObject()
                                                        .put("fileId", fileId)
                                                        .put("uniqueId", record.uniqueId())
                                                        .put("downloadStatus", FileRecord.DownloadStatus.downloading)
                                                ));

                                                downloadThumbnail(chatId, messageId, fileHandler.convertThumbnailRecord(telegramRecord.id()));
                                            })
                                            .onFailure(err -> {
                                                FileRecord.DownloadStatus rollbackStatus = FileRecord.DownloadStatus.idle;
                                                if (err instanceof TelegramRunException tre && tre.getError().code == 404) {
                                                    rollbackStatus = FileRecord.DownloadStatus.error;
                                                    log.warn("[%s] AddFileToDownloads got 404 (file unavailable), marking as error: fileId=%d, uniqueId=%s"
                                                            .formatted(getRootId(), fileId, record.uniqueId()));
                                                } else {
                                                    log.warn("[%s] AddFileToDownloads failed, rolling back to idle: %s (fileId=%d, uniqueId=%s)"
                                                            .formatted(getRootId(), err.getMessage(), fileId, record.uniqueId()));
                                                }
                                                // Roll back the claim we own: CAS downloading->rollback via
                                                // the owning attempt (retires it), tolerating an external
                                                // reset. attemptId is always non-null here (we only reach
                                                // AddFileToDownloads after winning the atomic CAS).
                                                DataVerticle.fileRepository.transitionOwned(
                                                        record.id(), record.uniqueId(), attemptId,
                                                        FileRecord.DownloadStatus.downloading, rollbackStatus, null, null
                                                ).onFailure(rollbackErr ->
                                                        log.error("[%s] Rollback failed for uniqueId=%s: %s"
                                                                .formatted(getRootId(), record.uniqueId(), rollbackErr.getMessage()))
                                                );
                                            })
                                            // Reaching AddFileToDownloads means THIS call won the atomic
                                            // claim (rowCount==1, attemptId minted) for its start state
                                            // (idle OR a paused/error re-download) — THIS call caused the
                                            // download to start: CLAIMED. A concurrent loser got LOST above.
                                            .map(ignore -> new DownloadTrigger(record, ClaimOutcome.CLAIMED));
                                });
                            });
                });
    }

    public Future<Boolean> downloadThumbnail(Long chatId, Long messageId, FileRecord thumbnailRecord) {
        if (thumbnailRecord == null) {
            return Future.succeededFuture(false);
        }
        return DataVerticle.fileRepository.createIfNotExist(thumbnailRecord)
                .compose(created -> {
                    if (!created) {
                        return DataVerticle.fileRepository.updateFileId(thumbnailRecord.id(), thumbnailRecord.uniqueId());
                    }
                    return Future.succeededFuture();
                })
                .compose(ignore -> {
                    if (thumbnailRecord.isDownloadStatus(FileRecord.DownloadStatus.completed)) {
                        return Future.succeededFuture(false);
                    }
                    return client.execute(new TdApi.AddFileToDownloads(thumbnailRecord.id(), chatId, messageId, 32))
                            .map(true);
                })
                .onSuccess(download -> {
                    if (download) {
                        log.debug("[%s] Download thumbnail: %s".formatted(this.getRootId(), thumbnailRecord.uniqueId()));
                    }
                });
    }

    public Future<Void> cancelDownload(Integer fileId) {
        return client.execute(new TdApi.GetFile(fileId))
                .compose(file -> DataVerticle.fileRepository
                        .updateFileId(file.id, file.remote.uniqueId)
                        .map(file)
                )
                .compose(file -> {
                    if (file.local == null) {
                        return Future.failedFuture("File not started downloading");
                    }

                    return client.execute(new TdApi.CancelDownloadFile(fileId, false))
                            .map(file);
                })
                .compose(file -> client.execute(new TdApi.DeleteFile(fileId)).map(file))
                .compose(file -> DataVerticle.fileRepository.deleteByUniqueId(file.remote.uniqueId).map(file))
                .onSuccess(file ->
                        sendEvent(EventPayload.build(EventPayload.TYPE_FILE_STATUS, new JsonObject()
                                .put("fileId", fileId)
                                .put("uniqueId", file.remote.uniqueId)
                                .put("downloadStatus", FileRecord.DownloadStatus.idle)
                        )))
                .mapEmpty();
    }

    public Future<Void> togglePauseDownload(Integer fileId, boolean isPaused) {
        return client.execute(new TdApi.GetFile(fileId))
                .compose(file -> DataVerticle.fileRepository
                        .updateFileId(file.id, file.remote.uniqueId)
                        .map(file)
                )
                .compose(file -> {
                    if (file.local == null) {
                        return Future.failedFuture("File not started downloading");
                    }
                    if (file.local.isDownloadingCompleted) {
                        return syncFileDownloadStatus(file, null, null).mapEmpty();
                    }
                    if (isPaused && !file.local.isDownloadingActive) {
                        return Future.failedFuture("File is not downloading");
                    }
                    if (!isPaused && file.local.isDownloadingActive) {
                        return Future.failedFuture("File is downloading");
                    }
                    if (!isPaused && !file.local.canBeDeleted) {
                        // Maybe the file is not exist, so we need to redownload it
                        return DataVerticle.fileRepository.getByUniqueId(file.remote.uniqueId)
                                .compose(fileRecord ->
                                        client.execute(new TdApi.AddFileToDownloads(fileId, fileRecord.chatId(), fileRecord.messageId(), 32)))
                                .mapEmpty();
                    }

                    return client.execute(new TdApi.ToggleDownloadIsPaused(fileId, isPaused));
                })
                .mapEmpty();
    }

    public Future<Void> removeFile(Integer fileId, String uniqueId) {
        return client.execute(new TdApi.GetFile(fileId))
                .otherwise((TdApi.File) null)
                .compose(file -> DataVerticle.fileRepository
                        .getByUniqueId(uniqueId)
                        .map(fileRecord -> Tuple.tuple(file, fileRecord))
                )
                .compose(tuple2 -> {
                    TdApi.File file = tuple2.v1;
                    FileRecord fileRecord = tuple2.v2;
                    if (fileRecord == null) {
                        return Future.failedFuture("File not found");
                    }

                    if (fileRecord.isTransferStatus(FileRecord.TransferStatus.completed)) {
                        if (FileUtil.del(fileRecord.localPath())) {
                            log.debug("[%s] Remove file success: %s".formatted(this.getRootId(), fileRecord.localPath()));
                        }
                    }

                    if (file != null && file.local != null && StrUtil.isNotBlank(file.local.path)) {
                        return client.execute(new TdApi.DeleteFile(fileId))
                                .map(file);
                    } else if (!fileRecord.isTransferStatus(FileRecord.TransferStatus.completed)
                               && StrUtil.isNotBlank(fileRecord.localPath())) {
                        if (FileUtil.del(fileRecord.localPath())) {
                            log.debug("[%s] Remove file success: %s".formatted(this.getRootId(), fileRecord.localPath()));
                        }
                    }
                    return Future.succeededFuture(file);
                })
                .compose(file -> DataVerticle.fileRepository.deleteByUniqueId(uniqueId).map(file))
                .onSuccess(_ -> sendEvent(EventPayload.build(EventPayload.TYPE_FILE_STATUS, new JsonObject()
                        .put("fileId", fileId)
                        .put("uniqueId", uniqueId)
                        .put("removed", true)
                )))
                .mapEmpty();
    }

    /**
     * Resolve the CURRENT (volatile) TDLib fileId for a message from its STABLE identity
     * (chatId + messageId). TDLib's fileId changes across sessions/restarts, so a trigger keyed on a
     * remembered fileId can address the wrong (or a gone) file — this resolves the live one exactly as
     * {@link AutoDownloadVerticle} does before starting a download. Fails 404 if the message is gone or
     * carries no downloadable file.
     */
    public Future<Integer> resolveCurrentFileId(long chatId, long messageId) {
        return fetchMessage(chatId, messageId)
                .compose(message -> {
                    Optional<TdApiHelp.FileHandler<?>> handlerOpt = TdApiHelp.getFileHandler(message);
                    if (handlerOpt.isEmpty() || handlerOpt.get().getFileId() == null) {
                        return Future.failedFuture(telegram.files.http.ApiException.notFound(
                                "Message %d in chat %d has no downloadable file".formatted(messageId, chatId)));
                    }
                    return Future.succeededFuture(handlerOpt.get().getFileId());
                });
    }

    /**
     * Trigger a download from STABLE identity (chat + message), resolving the volatile fileId at
     * trigger time and delegating to the Phase-2 {@link #startDownload} atomic-claim workflow. Never
     * queues — it claims immediately (or reports the real state / a lost-claim race). When
     * {@code knownFileId} is supplied it is used only as a hint; the resolved live id always wins.
     */
    public Future<DownloadTrigger> triggerDownload(long chatId, long messageId, Integer knownFileId) {
        return resolveCurrentFileId(chatId, messageId)
                .compose(fileId -> startDownloadWithOutcome(chatId, messageId, fileId));
    }

    /**
     * API-boundary transition guard (Phase-5 second layer over the Phase-2 CAS): load the file by
     * {@code uniqueId}, verify {@code current.canTransitionTo(target)}, and — for destructive ops —
     * refuse a {@code processed}/{@code imported} (externally-owned) file. On a legal request it runs
     * {@code action}; on an illegal one it fails with a 409 {@link telegram.files.http.ApiException}
     * carrying the real current state (never a false success). 404 if the row is absent.
     *
     * @param target      the download_status the op transitions the file toward (for the legality check)
     * @param destructive whether the op destroys externally-owned state (cancel/remove/delete)
     */
    public <T> Future<T> guardedFileOp(String uniqueId,
                                       FileRecord.DownloadStatus target,
                                       boolean destructive,
                                       java.util.function.Function<FileRecord, Future<T>> action) {
        return DataVerticle.fileRepository.getByUniqueId(uniqueId)
                .compose(record -> {
                    if (record == null) {
                        return Future.failedFuture(telegram.files.http.ApiException.notFound(
                                "File not found: " + uniqueId));
                    }
                    FileRecord.DownloadStatus current;
                    try {
                        current = FileRecord.DownloadStatus.valueOf(record.downloadStatus());
                    } catch (IllegalArgumentException | NullPointerException e) {
                        return Future.failedFuture(telegram.files.http.ApiException.conflict(
                                "File %s has an unknown download status '%s'".formatted(uniqueId, record.downloadStatus())));
                    }
                    if (destructive && (current == FileRecord.DownloadStatus.processed
                                        || current == FileRecord.DownloadStatus.imported)) {
                        return Future.failedFuture(telegram.files.http.ApiException.conflict(
                                "File %s is %s and owned by external services; it cannot be destroyed via the API"
                                        .formatted(uniqueId, current)));
                    }
                    if (target != null && !current.canTransitionTo(target)) {
                        return Future.failedFuture(telegram.files.http.ApiException.conflict(
                                "Illegal transition %s -> %s for file %s".formatted(current, target, uniqueId)));
                    }
                    return action.apply(record);
                });
    }

    public Future<Void> updateAutoSettings(Long chatId, JsonObject params) {
        return DataVerticle.settingRepository.<SettingAutoRecords>getByKey(SettingKey.automation)
                .compose(settingAutoRecords -> {
                    if (settingAutoRecords == null) {
                        settingAutoRecords = new SettingAutoRecords();
                    }
                    SettingAutoRecords.Automation automation = params.mapTo(SettingAutoRecords.Automation.class);
                    boolean hasEnabled = automation.preload.enabled
                                         || automation.download.enabled
                                         || automation.transfer.enabled;

                    if (settingAutoRecords.exists(this.telegramRecord.id(), chatId) && !hasEnabled) {
                        settingAutoRecords.remove(this.telegramRecord.id(), chatId);
                    } else {
                        if (!hasEnabled) {
                            return Future.succeededFuture();
                        }
                        automation.telegramId = this.telegramRecord.id();
                        automation.chatId = chatId;
                        settingAutoRecords.add(automation);
                    }

                    return DataVerticle.settingRepository.createOrUpdate(SettingKey.automation.name(), Json.encode(settingAutoRecords))
                            .onSuccess(r -> vertx.eventBus().publish(EventEnum.AUTO_DOWNLOAD_UPDATE.name(), r.value()));
                })
                .mapEmpty();
    }

    public Future<JsonObject> getDownloadStatistics() {
        return Future.all(DataVerticle.fileRepository.getDownloadStatistics(this.telegramRecord.id()),
                client.execute(new TdApi.GetNetworkStatistics())
        ).map(r -> {
            JsonObject jsonObject = r.resultAt(0);
            TdApi.NetworkStatistics networkStatistics = r.resultAt(1);
            Tuple2<Long, Long> bytes = Arrays.stream(networkStatistics.entries)
                    .filter(e -> e instanceof TdApi.NetworkStatisticsEntryFile)
                    .map(e -> {
                        TdApi.NetworkStatisticsEntryFile entry = (TdApi.NetworkStatisticsEntryFile) e;
                        return Tuple.tuple(entry.sentBytes, entry.receivedBytes);
                    })
                    .reduce((a, b) -> Tuple.tuple(a.v1 + b.v1, a.v2 + b.v2))
                    .orElse(Tuple.tuple(0L, 0L));

            jsonObject.put("networkStatistics", JsonObject.of()
                    .put("sinceDate", networkStatistics.sinceDate)
                    .put("sentBytes", bytes.v1)
                    .put("receivedBytes", bytes.v2)
            );

            jsonObject.put("speedStats", avgSpeed.getSpeedStats());
            return jsonObject;
        });
    }

    public Future<JsonObject> getDownloadStatisticsByPhase(Integer timeRange) {
        // 1: 1 hour, 2: 1 day, 3: 1 week, 4: 1 month
        long endTime = System.currentTimeMillis();
        long startTime = switch (timeRange) {
            case 1 -> DateUtil.offsetHour(DateUtil.date(), -1).getTime();
            case 2 -> DateUtil.offsetDay(DateUtil.date(), -1).getTime();
            case 3 -> DateUtil.offsetWeek(DateUtil.date(), -1).getTime();
            case 4 -> DateUtil.offsetMonth(DateUtil.date(), -1).getTime();
            default -> throw new IllegalStateException("Unexpected value: " + timeRange);
        };

        return Future.all(
                        DataVerticle.statisticRepository.getRangeStatistics(StatisticRecord.Type.speed, this.telegramRecord.id(), startTime, endTime)
                                .map(statisticRecords -> TelegramConverter.convertRangedSpeedStats(statisticRecords, timeRange)),
                        DataVerticle.fileRepository.getCompletedRangeStatistics(this.telegramRecord.id(), startTime, endTime, timeRange)
                )
                .map(r -> new JsonObject()
                        .put("speedStats", r.resultAt(0))
                        .put("completedStats", r.resultAt(1))
                );
    }

    public Future<TdApi.Proxy> enableProxy(String proxyName) {
        if (StrUtil.isBlank(proxyName)) return Future.succeededFuture();
        return DataVerticle.settingRepository.<SettingProxyRecords>getByKey(SettingKey.proxys)
                .map(settingProxyRecords -> Optional.ofNullable(settingProxyRecords)
                        .flatMap(r -> r.getProxy(proxyName))
                        .orElseThrow(() -> VertxException.noStackTrace("Proxy %s not found".formatted(proxyName)))
                )
                .compose(proxy -> this.getTdProxy(proxy)
                        .map(r -> Tuple.tuple(proxy, r))
                )
                .compose(tuple -> {
                    SettingProxyRecords.Item proxy = tuple.v1;
                    TdApi.Proxy tdProxy = tuple.v2;
                    boolean edit = false;
                    if (tdProxy != null) {
                        if (tdProxy.isEnabled) {
                            return Future.succeededFuture(tdProxy);
                        }
                        edit = true;
                    }

                    TdApi.ProxyType proxyType;
                    switch (proxy.type) {
                        case "http" -> proxyType = new TdApi.ProxyTypeHttp(proxy.username, proxy.password, false);
                        case "socks5" -> proxyType = new TdApi.ProxyTypeSocks5(proxy.username, proxy.password);
                        case "mtproto" -> proxyType = new TdApi.ProxyTypeMtproto(proxy.secret);
                        case null, default -> {
                            return Future.failedFuture("Unsupported proxy type: %s".formatted(proxy.type));
                        }
                    }
                    return edit ? client.execute(new TdApi.EditProxy(tdProxy.id, proxy.server, proxy.port, true, proxyType))
                            : client.execute(new TdApi.AddProxy(proxy.server, proxy.port, true, proxyType));
                })
                .compose(r -> {
                    this.proxyName = proxyName;
                    if (this.telegramRecord != null) {
                        return DataVerticle.telegramRepository.update(this.telegramRecord.withProxy(proxyName))
                                .onSuccess(telegramRecord -> this.telegramRecord = telegramRecord)
                                .map(r);
                    } else {
                        return Future.succeededFuture(r);
                    }
                });
    }

    public Future<TdApi.Proxy> toggleProxy(JsonObject jsonObject) {
        String toggleProxyName = jsonObject.getString("proxyName");
        if (Objects.equals(toggleProxyName, this.proxyName)) {
            return Future.succeededFuture();
        }

        if (StrUtil.isBlank(toggleProxyName) && StrUtil.isNotBlank(this.proxyName)) {
            // disable proxy
            return client.execute(new TdApi.DisableProxy())
                    .compose(_ -> {
                        this.proxyName = null;
                        if (this.telegramRecord != null) {
                            return DataVerticle.telegramRepository.update(this.telegramRecord.withProxy(null))
                                    .onSuccess(telegramRecord -> this.telegramRecord = telegramRecord)
                                    .mapEmpty();
                        }
                        return Future.succeededFuture();
                    });
        } else {
            return this.enableProxy(toggleProxyName);
        }
    }

    public Future<TdApi.Proxy> getTdProxy(SettingProxyRecords.Item proxy) {
        return client.execute(new TdApi.GetProxies())
                .map(proxies -> Stream.of(proxies.proxies)
                        .filter(proxy::equalsTdProxy)
                        .findFirst()
                        .orElse(null));
    }

    public Future<TdApi.Proxy> getTdProxy() {
        return client.execute(new TdApi.GetProxies())
                .map(proxies -> Stream.of(proxies.proxies)
                        .filter(p -> p.isEnabled)
                        .findFirst()
                        .orElse(null));
    }

    public Future<Double> ping() {
        return this.getTdProxy()
                .compose(proxy -> client.execute(new TdApi.PingProxy(proxy == null ? 0 : proxy.id)))
                .map(r -> r.seconds);
    }

    public Future<String> execute(String method, Object params) {
        String code = RandomUtil.randomString(10);
        log.trace("[{}] Execute code: {} method: {}, params: {}", getRootId(), code, method, params);
        return Future.future(promise -> {
            TdApi.Function<?> func = TdApiHelp.getFunction(method, params);
            if (func == null) {
                promise.fail("Unsupported method: " + method);
                return;
            }
            io.vertx.core.Context ctx = context;
            client.getNativeClient().send(func, object -> {
                // Marshal the raw TDLib result callback onto the verticle's context so the handler
                // runs on the verticle's own thread, not TDLib's native receive thread (D4).
                Runnable handle = () -> {
                    log.debug("[{}] Execute: [{}] Receive result: {}", getRootId(), code, object);
                    handleDefaultResult(object, code);
                };
                if (ctx == null) {
                    handle.run();
                } else {
                    ctx.runOnContext(_ -> handle.run());
                }
            });
            promise.complete(code);
        });
    }

    private void sendEvent(EventPayload payload) {
        vertx.eventBus().publish(EventEnum.TELEGRAM_EVENT.address(),
                JsonObject.of("telegramId", this.getId(), "payload", JsonObject.mapFrom(payload)));
    }

    private void sendFileStatusHttpEvent(TdApi.File file, JsonObject fileUpdated) {
        if (fileUpdated == null || fileUpdated.isEmpty()) return;

        JsonObject statusData = new JsonObject()
                .put("fileId", file.id)
                .put("uniqueId", file.remote.uniqueId)
                .put("downloadStatus", fileUpdated.getString("downloadStatus"))
                .put("localPath", fileUpdated.getString("localPath"))
                .put("completionDate", fileUpdated.getLong("completionDate"))
                .put("downloadedSize", file.local.downloadedSize);

        // 如果文件下载完成，尝试获取并包含缩略图文件信息
        if ("completed".equals(fileUpdated.getString("downloadStatus"))) {
            DataVerticle.fileRepository.getByUniqueId(file.remote.uniqueId)
                    .compose(mainFileRecord -> {
                        if (mainFileRecord != null) {
                            statusData.put("type", mainFileRecord.type());
                        }
                        if (mainFileRecord != null && mainFileRecord.thumbnailUniqueId() != null) {
                            return FileRecordRetriever.getThumbnails(List.of(mainFileRecord))
                                    .map(thumbnailMap -> {
                                        FileRecord thumbnailRecord = thumbnailMap.get(mainFileRecord.thumbnailUniqueId());
                                        if (thumbnailRecord != null && thumbnailRecord.isDownloadStatus(FileRecord.DownloadStatus.completed)) {
                                            statusData.put("thumbnailFile", JsonObject.of(
                                                    "uniqueId", thumbnailRecord.uniqueId(),
                                                    "mimeType", thumbnailRecord.mimeType(),
                                                    "extra", StrUtil.isBlank(thumbnailRecord.extra()) ? null : Json.decodeValue(thumbnailRecord.extra())
                                            ));
                                        }
                                        return statusData;
                                    });
                        }
                        return Future.succeededFuture(statusData);
                    })
                    .onSuccess(finalStatusData -> sendEvent(EventPayload.build(EventPayload.TYPE_FILE_STATUS, finalStatusData)))
                    .onFailure(err -> {
                        // 如果获取缩略图失败，仍然发送基本状态信息
                        log.error("Failed to get thumbnail info for file: %s, error: %s".formatted(file.remote.uniqueId, err.getMessage()));
                        sendEvent(EventPayload.build(EventPayload.TYPE_FILE_STATUS, statusData));
                    });
        } else {
            // 非完成状态直接发送
            sendEvent(EventPayload.build(EventPayload.TYPE_FILE_STATUS, statusData));
        }
    }

    private void handleAuthorizationResult(TdApi.Object object) {
        switch (object.getConstructor()) {
            case TdApi.Error.CONSTRUCTOR:
                sendEvent(EventPayload.build(EventPayload.TYPE_ERROR, object));
                break;
            case TdApi.Ok.CONSTRUCTOR:
                break;
            default:
                log.warn("[%s] Receive UpdateAuthorizationState with invalid authorization state%s".formatted(getRootId(), object));
        }
    }

    private void handleDefaultResult(TdApi.Object object, String code) {
        if (object.getConstructor() == TdApi.Error.CONSTRUCTOR) {
            sendEvent(EventPayload.build(EventPayload.TYPE_ERROR, code, object));
        } else {
            sendEvent(EventPayload.build(EventPayload.TYPE_METHOD_RESULT, code, object));
        }
    }

    private void handleException(Throwable e) {
        log.error(e);
    }

    private Future<Void> cleanupOldVerticle(String oldRootPath) {
        if (oldRootPath.equals(this.rootPath)) {
            return Future.succeededFuture();
        }
        Optional<TelegramVerticle> found = TelegramVerticles.getAll().stream()
                .filter(v -> v != this && oldRootPath.equals(v.rootPath))
                .findFirst();
        if (found.isEmpty()) {
            return Future.succeededFuture();
        }
        TelegramVerticle old = found.get();
        log.info("[%s] Replacing stale verticle at path: %s".formatted(getRootId(), oldRootPath));
        TelegramVerticles.remove(old);
        String deployId = old.deploymentID();
        if (deployId == null) {
            return Future.succeededFuture();
        }
        return vertx.undeploy(deployId)
                .recover(e -> {
                    log.warn("[%s] Could not undeploy stale verticle: %s".formatted(getRootId(), e.getMessage()));
                    return Future.succeededFuture();
                })
                .mapEmpty();
    }

    private void handleSaveAvgSpeed() {
        if (!authorized || telegramRecord == null) return;
        AvgSpeed.SpeedStats speedStats = avgSpeed.getSpeedStats();
        if (speedStats.avgSpeed() == 0
            && speedStats.minSpeed() == 0
            && speedStats.medianSpeed() == 0
            && speedStats.maxSpeed() == 0) {
            return;
        }
        JsonObject data = JsonObject.mapFrom(speedStats);
        data.remove("interval");
        // D8: surface the write failure (was fire-and-forget). A dropped speed-statistic insert
        // silently loses a data point from the speed history charts.
        DataVerticle.statisticRepository.create(new StatisticRecord(Convert.toStr(telegramRecord.id()),
                StatisticRecord.Type.speed,
                System.currentTimeMillis(),
                data.encode()))
                .onFailure(err -> log.error("[%s] Failed to persist speed statistic: %s"
                        .formatted(getRootId(), err.getMessage())));

        // Avoid speed not being updated for a long time
        avgSpeed.update(0, System.currentTimeMillis());
    }

    private Future<Void> initAvgSpeed() {
        return DataVerticle.settingRepository.<Integer>getByKey(SettingKey.avgSpeedInterval)
                .compose(interval -> {
                    if (Objects.equals(interval, avgSpeed.getSpeedStats().interval())) {
                        if (avgSpeedPersistenceTimerId == 0) {
                            avgSpeedPersistenceTimerId = vertx.setPeriodic(interval * 1000, _ -> handleSaveAvgSpeed());
                        }
                        return Future.succeededFuture();
                    }

                    avgSpeed = new AvgSpeed(interval);
                    if (avgSpeedPersistenceTimerId != 0) {
                        vertx.cancelTimer(avgSpeedPersistenceTimerId);
                    }
                    avgSpeedPersistenceTimerId = vertx.setPeriodic(interval * 1000, _ -> handleSaveAvgSpeed());
                    return Future.succeededFuture();
                });
    }

    private Future<Void> initEventConsumer() {
        vertx.eventBus().consumer(EventEnum.SETTING_UPDATE.address(SettingKey.avgSpeedInterval.name()), message -> {
            log.debug("Avg Speed Interval update: %s".formatted(message.body()));
            this.initAvgSpeed();
        });

        return Future.succeededFuture();
    }

    private Future<Void> initDownloadStatusReconciliation() {
        // Set up periodic timer to reconcile download statuses every 30 seconds
        if (downloadStatusReconciliationTimerId == 0) {
            downloadStatusReconciliationTimerId = vertx.setPeriodic(30000, _ -> reconcileDownloadStatuses());
            log.debug("[%s] Download status reconciliation timer initialized".formatted(getRootId()));
        }
        return Future.succeededFuture();
    }

    private void reconcileDownloadStatuses() {
        if (!authorized || telegramRecord == null || draining) {
            return;
        }

        // D7 in-flight guard: a reconciliation pass that outruns the 30s timer must not start a
        // second concurrent pass (overlapping GetFile fan-outs and duplicate zombie writes). Skip
        // this tick if the previous pass is still running; the flag clears when the sweep completes.
        if (reconciliationInFlight) {
            log.trace("[%s] Reconciliation still in flight, skipping this pass".formatted(getRootId()));
            return;
        }
        reconciliationInFlight = true;

        log.trace("[%s] Starting download status reconciliation".formatted(getRootId()));

        // Retire orphaned active attempts: a row an external service reset downloading->idle (or that
        // reached a terminal state without the owning worker retiring its attempt) still carries an
        // 'active' attempt, which would block a fresh claim under the one-active-attempt constraint.
        // GLOBALLY single-flight (D7): retireOrphanedAttempts is process-wide, so multiple telegram
        // verticles ticking at once must not all run it concurrently.
        Future<Integer> orphanFuture = retireOrphanedAttemptsSingleFlight();

        Future<Void> sweepFuture = DataVerticle.fileRepository.getByDownloadStatus(telegramRecord.id(), FileRecord.DownloadStatus.downloading)
                .compose(fileRecords -> {
                    if (fileRecords == null || fileRecords.isEmpty()) {
                        return Future.<Void>succeededFuture();
                    }

                    log.debug("[%s] Reconciling %d files with 'downloading' status".formatted(getRootId(), fileRecords.size()));
                    int[] reconciledCount = {0};

                    List<Future<?>> perFileFutures = new java.util.ArrayList<>();
                    fileRecords.forEach(fileRecord ->
                            // Each per-file future COMPOSES its DB write, so it completes only AFTER the
                            // write finishes — the pass (and the in-flight guard, and the shutdown drain
                            // that tracks this whole pass) therefore reflects real DB-write completion,
                            // not just the GetFile response. D5: bound each GetFile so a hung TDLib call
                            // cannot pin the pass open forever.
                            perFileFutures.add(reconcileOneDownloadingFile(fileRecord, reconciledCount)));

                    // Join the per-file chains so the pass stays open until every GetFile AND its write
                    // has resolved. Individual failures are already handled per-file; use join() so one
                    // failure does not short-circuit the rest.
                    return Future.join(perFileFutures)
                            .onComplete(_ -> {
                                if (reconciledCount[0] > 0) {
                                    log.info("[%s] Reconciliation completed: fixed %d stuck downloads".formatted(getRootId(), reconciledCount[0]));
                                }
                            })
                            .mapEmpty();
                })
                .mapEmpty();
        sweepFuture.onFailure(e -> log.error("[%s] Failed to get downloading files for reconciliation: %s".formatted(getRootId(), e.getMessage())));

        // The whole pass (orphan retire + downloading sweep, INCLUDING every DB write it composes) is
        // one tracked operation: the in-flight guard clears only when it fully resolves, and the
        // shutdown drain awaits it so no reconciliation write can hit a closed pool.
        Future<Void> pass = Future.join(orphanFuture, sweepFuture).mapEmpty();
        trackOperation(pass).onComplete(_ -> reconciliationInFlight = false);
    }

    /**
     * Reconcile ONE 'downloading' row against TDLib and COMPOSE the resulting DB write, so the
     * returned future completes only after the write completes. Never fails (per-file errors are
     * logged and swallowed into a succeeded future) so a single bad row cannot short-circuit the
     * joined pass.
     */
    private Future<Void> reconcileOneDownloadingFile(FileRecord fileRecord, int[] reconciledCount) {
        return client.execute(new TdApi.GetFile(fileRecord.id()), 15_000, vertx)
                .compose(file -> {
                    if (file.local != null && file.local.isDownloadingCompleted) {
                        log.info("[%s] Reconciliation: File completed but not updated in DB: %s".formatted(getRootId(), file.remote.uniqueId));
                        reconciledCount[0]++;
                        return DataVerticle.fileRepository.updateDownloadStatus(
                                        file.id,
                                        file.remote.uniqueId,
                                        file.local.path,
                                        FileRecord.DownloadStatus.completed,
                                        System.currentTimeMillis())
                                .onSuccess(result -> {
                                    sendFileStatusHttpEvent(file, result);
                                    log.debug("[%s] Reconciliation fixed file status: %s".formatted(getRootId(), file.remote.uniqueId));
                                })
                                .mapEmpty();
                    } else if (file.local == null
                            || (!file.local.isDownloadingActive && !file.local.isDownloadingCompleted)) {
                        // Zombie: DB says 'downloading' but TDLib says not active and not completed.
                        // If queued > 2 min ago, mark as error to break retry cycles; otherwise reset to idle for one retry.
                        boolean staleZombie = fileRecord.queuedAt() != null
                                && (System.currentTimeMillis() - fileRecord.queuedAt()) > 120_000;
                        FileRecord.DownloadStatus targetStatus = staleZombie
                                ? FileRecord.DownloadStatus.error
                                : FileRecord.DownloadStatus.idle;
                        log.info("[%s] Reconciliation: Zombie download detected (%s), setting to %s: %s (dbId=%d)"
                                .formatted(getRootId(), staleZombie ? "stale" : "fresh", targetStatus, fileRecord.uniqueId(), fileRecord.id()));
                        reconciledCount[0]++;
                        // D8: surface the write failure (do not fire-and-forget). A zombie reconciliation
                        // write that silently fails leaves the row stuck 'downloading' forever.
                        return DataVerticle.fileRepository.updateDownloadStatus(
                                        fileRecord.id(), fileRecord.uniqueId(), null, targetStatus, null)
                                .onFailure(err -> log.error("[%s] Reconciliation: failed to set zombie %s to %s: %s"
                                        .formatted(getRootId(), fileRecord.uniqueId(), targetStatus, err.getMessage())))
                                .mapEmpty();
                    }
                    return Future.<Void>succeededFuture();
                })
                .recover(e -> {
                    // TDLib doesn't know this file ID (or the call timed out) — treat as a zombie, mark
                    // as error, and COMPOSE that write so the future reflects its completion.
                    log.info("[%s] Reconciliation: File ID unknown to TDLib, setting to error: %s (dbId=%d)"
                            .formatted(getRootId(), fileRecord.uniqueId(), fileRecord.id()));
                    reconciledCount[0]++;
                    return DataVerticle.fileRepository.updateDownloadStatus(
                                    fileRecord.id(), fileRecord.uniqueId(), null, FileRecord.DownloadStatus.error, null)
                            .onFailure(err -> log.error("[%s] Reconciliation: failed to set unknown-to-TDLib %s to error: %s"
                                    .formatted(getRootId(), fileRecord.uniqueId(), err.getMessage())))
                            .mapEmpty();
                })
                // Never fail the joined pass on a per-file error (writes already surfaced above).
                .recover(_ -> Future.succeededFuture());
    }

    // Process-wide single-flight for retireOrphanedAttempts (a global DB sweep). Multiple
    // TelegramVerticles tick their 30s reconciliation timers independently; without this they would
    // all issue the same global retire concurrently. Concurrent callers share the pending future.
    private static final SingleFlight<Integer> ORPHAN_RECONCILIATION = new SingleFlight<>();

    private Future<Integer> retireOrphanedAttemptsSingleFlight() {
        return ORPHAN_RECONCILIATION.run(() -> DataVerticle.fileRepository.retireOrphanedAttempts()
                .onSuccess(retired -> {
                    if (retired != null && retired > 0) {
                        log.debug("[%s] Reconciliation retired %d orphaned download attempt(s)".formatted(getRootId(), retired));
                    }
                })
                .onFailure(err -> log.warn("[%s] Failed to retire orphaned attempts: %s".formatted(getRootId(), err.getMessage()))));
    }

    private void onConnectionStateUpdated(TdApi.ConnectionState connectionState) {
        this.lastConnectionState = connectionState;
        log.debug("[%s] Connection state: %s".formatted(getRootId(), connectionState.getClass().getSimpleName()));
        if (connectionState.getConstructor() == TdApi.ConnectionStateWaitingForNetwork.CONSTRUCTOR) {
            // Tell TDLib the network is available so it retries connecting instead of waiting indefinitely.
            client.execute(new TdApi.SetNetworkType(new TdApi.NetworkTypeOther()), true);
        }
        sendEvent(EventPayload.build(EventPayload.TYPE_CONNECTION, new JsonObject()
                .put("state", connectionStateName(connectionState))));
    }

    private static String connectionStateName(TdApi.ConnectionState state) {
        return switch (state.getConstructor()) {
            case TdApi.ConnectionStateReady.CONSTRUCTOR -> "ready";
            case TdApi.ConnectionStateConnecting.CONSTRUCTOR -> "connecting";
            case TdApi.ConnectionStateConnectingToProxy.CONSTRUCTOR -> "connectingToProxy";
            case TdApi.ConnectionStateUpdating.CONSTRUCTOR -> "updating";
            case TdApi.ConnectionStateWaitingForNetwork.CONSTRUCTOR -> "waitingForNetwork";
            default -> "unknown";
        };
    }

    private void onAuthorizationStateUpdated(TdApi.AuthorizationState authorizationState) {
        log.debug("[%s] Receive authorization state update: %s".formatted(getRootId(), authorizationState));
        this.lastAuthorizationState = authorizationState;
        switch (authorizationState.getConstructor()) {
            case TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR:
                TdApi.SetTdlibParameters request = new TdApi.SetTdlibParameters();
                request.databaseDirectory = this.rootPath;
                request.useMessageDatabase = true;
                request.useFileDatabase = true;
                request.useChatInfoDatabase = true;
                request.useSecretChats = true;
                request.apiId = Config.TELEGRAM_API_ID;
                request.apiHash = Config.TELEGRAM_API_HASH;
                request.systemLanguageCode = "en";
                request.deviceModel = "Telegram Files";
                request.applicationVersion = Start.VERSION;
                log.trace("[%s] Send SetTdlibParameters: %s".formatted(getRootId(), request));

                client.execute(request).onSuccess(this::handleAuthorizationResult);
                break;
            case TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR:
            case TdApi.AuthorizationStateWaitOtherDeviceConfirmation.CONSTRUCTOR:
            case TdApi.AuthorizationStateWaitEmailAddress.CONSTRUCTOR:
            case TdApi.AuthorizationStateWaitEmailCode.CONSTRUCTOR:
            case TdApi.AuthorizationStateWaitCode.CONSTRUCTOR:
            case TdApi.AuthorizationStateWaitRegistration.CONSTRUCTOR:
            case TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR:
            case TdApi.AuthorizationStateWaitPremiumPurchase.CONSTRUCTOR:
                authorized = false;
                sendEvent(EventPayload.build(EventPayload.TYPE_AUTHORIZATION, authorizationState));
                break;
            case TdApi.AuthorizationStateReady.CONSTRUCTOR:
                authorized = true;
                if (telegramRecord == null) {
                    client.execute(new TdApi.GetMe())
                            .compose(user ->
                                    DataVerticle.telegramRepository.create(new TelegramRecord(user.id, user.firstName, this.rootPath, this.proxyName))
                                            .recover(e -> {
                                                if (e.getMessage() != null && (e.getMessage().contains("UNIQUE constraint") ||
                                                        e.getMessage().contains("SQLITE_CONSTRAINT") ||
                                                        e.getMessage().contains("duplicate key"))) {
                                                    return DataVerticle.telegramRepository.getById(user.id)
                                                            .compose(existing -> {
                                                                if (existing != null) {
                                                                    return cleanupOldVerticle(existing.rootPath())
                                                                            .compose(_ -> DataVerticle.telegramRepository.update(
                                                                                    new TelegramRecord(user.id, user.firstName, this.rootPath, this.proxyName)
                                                                            ));
                                                                }
                                                                return Future.failedFuture(e);
                                                            });
                                                }
                                                return Future.failedFuture(e);
                                            })
                            )
                            .onSuccess(o -> {
                                telegramRecord = o;
                                log.info("[%s] %s Authorization Ready".formatted(getRootId(), this.telegramRecord.firstName()));
                            })
                            .onFailure(e -> log.error("[%s] Authorization Ready, but failed to create telegram record: %s".formatted(getRootId(), e.getMessage())));
                } else {
                    log.info("[%s] %s Authorization Ready".formatted(getRootId(), this.telegramRecord.firstName()));
                }
                sendEvent(EventPayload.build(EventPayload.TYPE_AUTHORIZATION, authorizationState));
                telegramChats.loadMainChatList();
                telegramChats.loadArchivedChatList();
                // Sync download status for files marked as completed in database.
                // This authorization callback is now MARSHALED onto the verticle's event-loop context
                // (D4/D5 root fix). syncCompletedFilesStatus does per-file blocking filesystem checks
                // (FileUtil.exist / Files.getLastModifiedTime sweeps) and blocking DB awaits, so it
                // runs on a WORKER thread (executeBlocking) — marshaling the callback must not push
                // that blocking work onto the event loop. TRACKED so the shutdown drain awaits its DB
                // writes before the pool closes (skip entirely once draining).
                if (!draining) {
                    trackOperation(vertx.executeBlocking(() -> {
                        syncCompletedFilesStatus();
                        return (Void) null;
                    }, false)).onFailure(e -> log.error("[%s] syncCompletedFilesStatus failed: %s"
                            .formatted(getRootId(), e.getMessage())));
                }
                break;
            case TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR:
                authorized = false;
                sendEvent(EventPayload.build(EventPayload.TYPE_AUTHORIZATION, authorizationState));
                break;
            case TdApi.AuthorizationStateClosing.CONSTRUCTOR:
                authorized = false;
                break;
            case TdApi.AuthorizationStateClosed.CONSTRUCTOR:
                authorized = false;
                // D5: the client is definitively closed — reject any outstanding request promises so
                // no Java future hangs waiting on a callback that will never fire. Idempotent with
                // the close() path.
                client.rejectAllOutstanding(
                        new IllegalStateException("[%s] TDLib client closed".formatted(this.getRootId())));
                if (needDelete) {
                    File root = FileUtil.file(this.rootPath);
                    if (root.exists()) {
                        FileUtil.del(root);
                    }
                    if (getId() instanceof Long telegramId) {
                        DataVerticle.telegramRepository.delete(telegramId)
                                .onFailure(e -> log.error("[%s] Failed to delete telegram record: %s".formatted(this.getRootId(), e.getMessage())));
                    }
                    log.info("[%s] Telegram account deleted".formatted(this.getRootId()));
                }
                break;
            default:
                log.warn("[%s] Unsupported authorization state received:%s".formatted(this.getRootId(), authorizationState));
        }
    }

    private void onFileUpdated(TdApi.UpdateFile updateFile) {
        log.trace("📃[{}] Receive file update: {}", getRootId(), updateFile);
        TdApi.File file = updateFile.file;
        if (file != null) {
            String localPath = null;
            Long completionDate = null;
            if (file.local != null && file.local.isDownloadingCompleted) {
                localPath = file.local.path;
                completionDate = System.currentTimeMillis();
            }
            String finalLocalPath = localPath;
            Long finalCompletionDate = completionDate;
            // Shutdown-drain gate: once draining, do NOT start a new file-update status write — a late
            // UpdateFile arriving during shutdown must not spin up fresh DB work that could outlive the
            // drain and hit a closing/closed pool. The event publish below still runs (in-memory only).
            if (!draining) {
                // This is the MOST COMMON callback-originated DB write (every download-progress update).
                // The whole read+write chain is one TRACKED operation so drainOutstandingOperations()
                // joins it before the client/pool closes (no write after pool close). The write is
                // COMPOSED (not fire-and-forget in onSuccess) so the tracked future reflects real DB
                // completion.
                Future<Void> persist = fileUpdatePersistOverrideForTest != null
                        ? fileUpdatePersistOverrideForTest.get()
                        : persistFileUpdate(file, finalLocalPath, finalCompletionDate);
                trackOperation(persist)
                        .onFailure(e -> log.debug("[{}] onFileUpdated persist failed for {}: {}",
                                getRootId(), file.remote.uniqueId, e.getMessage()));
            }

            if (completionDate != null || lastFileEventTime == 0 || System.currentTimeMillis() - lastFileEventTime > 1000) {
                sendEvent(EventPayload.build(EventPayload.TYPE_FILE, updateFile));
                lastFileEventTime = System.currentTimeMillis();
            }
        }
    }

    /**
     * The DB read+write chain for a live TDLib file update, returned as ONE {@code Future<Void>} that
     * completes only AFTER the status write completes — so it can be tracked by the shutdown drain.
     * Composes the write (never fire-and-forget). Preserves the never-downgrade / dedup-CAS semantics
     * exactly; a null record or an early-return branch resolves to a succeeded no-op.
     */
    private Future<Void> persistFileUpdate(TdApi.File file, String finalLocalPath, Long finalCompletionDate) {
        return DataVerticle.fileRepository.getByUniqueId(file.remote.uniqueId)
                .compose(fileRecord -> {
                    if (fileRecord == null) {
                        return Future.<Void>succeededFuture();
                    }
                    FileRecord.DownloadStatus downloadStatus = TdApiHelp.getDownloadStatus(file);

                    // Never downgrade 'processed' or 'imported' status — these are set by
                    // external services (telegram-postproc) after files are moved out of inbox.
                    // After a container restart, tdlib reports these as 'idle' because its
                    // local cache is gone, but the DB status is authoritative.
                    if (fileRecord.isDownloadStatus(FileRecord.DownloadStatus.processed) ||
                        fileRecord.isDownloadStatus(FileRecord.DownloadStatus.imported)) {
                        return Future.<Void>succeededFuture();
                    }
                    if (fileRecord.isDownloadStatus(FileRecord.DownloadStatus.completed) &&
                        fileRecord.isTransferStatus(FileRecord.TransferStatus.completed) &&
                        FileUtil.exist(fileRecord.localPath())) {
                        return Future.<Void>succeededFuture();
                    }
                    if (downloadStatus == null) {
                        // Check if download actually completed even though getDownloadStatus returned null
                        if (file.local != null && file.local.isDownloadingCompleted) {
                            log.debug("[{}] File download completed but getDownloadStatus returned null: {}", getRootId(), file.remote.uniqueId);
                            downloadStatus = FileRecord.DownloadStatus.completed;
                        } else {
                            downloadStatus = FileRecord.DownloadStatus.idle;
                        }
                    }
                    // Files downloaded to inbox are marked as 'completed'
                    // External services (telegram-postproc) will update to 'processed' when moved
                    FileRecord.DownloadStatus finalStatus = downloadStatus == null
                            ? FileRecord.DownloadStatus.idle : downloadStatus;

                    Future<JsonObject> writeFuture;
                    // A 'completed' write is the TDLib download-finished event. TDLib DEDUPS:
                    // one TdApi.File / one download / ONE completion per file identity
                    // (UpdateFile carries only the file object, no attempt id — see
                    // TdApiHelp.getDownloadStatus and the TelegramClient result handler). So a
                    // "stale attempt1 completion distinct from attempt2's" cannot physically
                    // occur — there is exactly one completion, reporting the current file. The
                    // single atomic exact-state CAS (download_status='downloading' -> 'completed'
                    // AND retire the active attempt in ONE statement) is therefore both correct
                    // and sufficient: the 'downloading' guard makes an external reset-to-idle a
                    // no-op (rowCount 0, no clobber); if the row is downloading it IS the current
                    // file finishing. No per-attempt attribution is achievable OR needed.
                    if (finalStatus == FileRecord.DownloadStatus.completed) {
                        writeFuture = DataVerticle.fileRepository.completeDownloadAndRetireAttempt(file.id,
                                file.remote.uniqueId,
                                finalLocalPath,
                                finalCompletionDate);
                    } else {
                        // Non-terminal statuses (idle/paused) keep the un-owned chokepoint, which
                        // still enforces canTransitionTo + exact-state CAS (external-reset tolerant).
                        writeFuture = DataVerticle.fileRepository.updateDownloadStatus(file.id,
                                file.remote.uniqueId,
                                finalLocalPath,
                                finalStatus,
                                finalCompletionDate);
                    }
                    return writeFuture.onSuccess(r -> {
                        sendFileStatusHttpEvent(file, r);

                        // Set file modification time to match original Telegram upload date
                        if (finalCompletionDate != null && finalLocalPath != null && fileRecord.date() > 0) {
                            try {
                                Path filePath = Path.of(finalLocalPath);
                                if (Files.exists(filePath)) {
                                    FileTime originalTime = FileTime.fromMillis(fileRecord.date() * 1000L);
                                    Files.setLastModifiedTime(filePath, originalTime);
                                    log.debug("Set file modification time for {} to {}", filePath.getFileName(),
                                            DateUtil.date(fileRecord.date() * 1000L));
                                }
                            } catch (Exception e) {
                                log.warn("Failed to set file modification time for {}: {}",
                                        finalLocalPath, e.getMessage());
                            }
                        }
                    }).mapEmpty();
                });
    }

    private void onFileDownloadsUpdated(TdApi.UpdateFileDownloads updateFileDownloads) {
        log.trace("[{}] Receive file downloads update: {}", getRootId(), updateFileDownloads);
        avgSpeed.update(updateFileDownloads.downloadedSize, System.currentTimeMillis());
        if (lastFileDownloadEventTime == 0 || System.currentTimeMillis() - lastFileDownloadEventTime > 1000) {
            sendEvent(EventPayload.build(EventPayload.TYPE_FILE_DOWNLOAD, updateFileDownloads));
            lastFileDownloadEventTime = System.currentTimeMillis();
        }
    }

    private void onMessageReceived(TdApi.Message message) {
        log.trace("[{}] Receive message: {}", getRootId(), message);
        if (this.telegramRecord == null) {
            log.trace("[%s] Telegram record is null, can't handle message".formatted(getRootId()));
            return;
        }
        vertx.eventBus().publish(EventEnum.MESSAGE_RECEIVED.address(), JsonObject.of()
                .put("telegramId", telegramRecord.id())
                .put("chatId", message.chatId)
                .put("messageId", message.id)
        );
    }

    private Future<Void> syncFileDownloadStatus(TdApi.File file, TdApi.Message message, TdApi.MessageThreadInfo messageThreadInfo) {
        return DataVerticle.fileRepository
                .getByUniqueId(file.remote.uniqueId)
                .compose(fileRecord -> {
                    if (fileRecord != null) {
                        FileRecord finalFileRecord = fileRecord;
                        // Files synced from Telegram are marked as 'completed'
                        // External services will update to 'processed'/'imported' as needed
                        FileRecord.DownloadStatus finalStatus = FileRecord.DownloadStatus.completed;
                        
                        return DataVerticle.fileRepository.updateDownloadStatus(
                                file.id,
                                file.remote.uniqueId,
                                file.local.path,
                                finalStatus,
                                System.currentTimeMillis()
                        ).onSuccess(r -> {
                            // Set file modification time to match original Telegram upload date
                            if (file.local.path != null && finalFileRecord.date() > 0) {
                                try {
                                    Path filePath = Path.of(file.local.path);
                                    if (Files.exists(filePath)) {
                                        FileTime originalTime = FileTime.fromMillis(finalFileRecord.date() * 1000L);
                                        Files.setLastModifiedTime(filePath, originalTime);
                                        log.debug("Set file modification time for {} to {}", filePath.getFileName(), 
                                                 DateUtil.date(finalFileRecord.date() * 1000L));
                                    }
                                } catch (Exception e) {
                                    log.warn("Failed to set file modification time for {}: {}", 
                                            file.local.path, e.getMessage());
                                }
                            }
                        });
                    }

                    if (message == null) {
                        return Future.failedFuture("File not found");
                    }

                    FileRecord newFileRecord = TdApiHelp.getFileHandler(message)
                            .orElseThrow(() -> VertxException.noStackTrace("not support message type"))
                            .convertFileRecord(telegramRecord.id())
                            .withThreadInfo(messageThreadInfo);

                    return DataVerticle.fileRepository.create(newFileRecord)
                            .compose(r -> DataVerticle.fileRepository.updateDownloadStatus(
                                    file.id,
                                    file.remote.uniqueId,
                                    file.local.path,
                                    FileRecord.DownloadStatus.completed,
                                    System.currentTimeMillis()
                            ).onSuccess(updateResult -> {
                                // Set file modification time to match original Telegram upload date
                                if (file.local.path != null && newFileRecord.date() > 0) {
                                    try {
                                        Path filePath = Path.of(file.local.path);
                                        if (Files.exists(filePath)) {
                                            FileTime originalTime = FileTime.fromMillis(newFileRecord.date() * 1000L);
                                            Files.setLastModifiedTime(filePath, originalTime);
                                            log.debug("Set file modification time for {} to {}", filePath.getFileName(), 
                                                     DateUtil.date(newFileRecord.date() * 1000L));
                                        }
                                    } catch (Exception e) {
                                        log.warn("Failed to set file modification time for {}: {}", 
                                                file.local.path, e.getMessage());
                                    }
                                }
                            }));
                })
                .compose(r -> {
                    sendFileStatusHttpEvent(file, r);
                    if (r == null || r.isEmpty()) {
                        return Future.failedFuture("File is downloaded completed, but update status failed");
                    } else {
                        return Future.failedFuture("File is already downloaded successfully");
                    }
                });
    }
    
    /**
     * Reconcile DB-'completed' rows against disk at authorization-ready. Runs on a WORKER thread
     * (dispatched via executeBlocking from the marshaled authorization callback): the per-file
     * filesystem checks (FileUtil.exist / Files.getLastModifiedTime) and the DB writes are blocking,
     * so they MUST NOT run on the event loop. Async calls are joined with {@link MessyUtils#await}
     * (safe on a worker thread) so the FS and DB work is sequential and every write is SURFACED
     * (D8: no fire-and-forget). The Phase-3 recovery policy (mid-transfer skip, completed-but-missing
     * re-queue, never-downgrade) is unchanged.
     */
    private void syncCompletedFilesStatus() {
        if (telegramRecord == null) {
            return;
        }

        log.info("[%s] Starting sync of completed files status...".formatted(getRootId()));

        // Get completed files in batches to avoid loading too many at once
        Map<String, String> filter = new HashMap<>();
        filter.put("downloadStatus", FileRecord.DownloadStatus.completed.name());
        filter.put("limit", "100"); // Process in batches of 100

        List<FileRecord> completedFiles;
        try {
            completedFiles = MessyUtils.await(DataVerticle.fileRepository.getFiles(0, filter)).v1();
        } catch (Exception e) {
            log.error("[%s] Failed to sync completed files status: %s".formatted(getRootId(), e.getMessage()));
            return;
        }
        if (completedFiles.isEmpty()) {
            log.debug("[%s] No completed files to sync".formatted(getRootId()));
            return;
        }

        log.info("[%s] Syncing %d completed files...".formatted(getRootId(), completedFiles.size()));

        int synced = 0;
        int notFound = 0;
        int processed = 0;

        for (FileRecord fileRecord : completedFiles) {
            // Skip if file doesn't belong to this telegram account
            if (fileRecord.telegramId() != telegramRecord.id()) {
                processed++;
                continue;
            }

            // A row mid-transfer (transfer_status='transferring') is a crashed transfer owned
            // by TransferVerticle's filesystem-truth reconciliation, which may recover it
            // FORWARD (the file is safely at the destination). Do NOT classify it as a loss or
            // re-download it here — that would misclassify a SUCCESSFUL crash-after-rename
            // transfer. Leave it for the transfer reconciler.
            if (fileRecord.isTransferStatus(FileRecord.TransferStatus.transferring)) {
                log.debug("[%s] Skipping mid-transfer row in completed-sync (owned by transfer reconciliation): %s"
                        .formatted(getRootId(), fileRecord.uniqueId()));
                synced++;
                checkSyncComplete(++processed, completedFiles.size(), synced, notFound);
                continue;
            }

            // Check if file exists on disk (blocking — safe here, worker thread)
            if (StrUtil.isBlank(fileRecord.localPath()) || !FileUtil.exist(fileRecord.localPath())) {
                // COMPLETED-BUT-MISSING RECOVERY POLICY (Phase 3, D6 invariant resolution).
                // A row that is download_status='completed' (download reported done) whose
                // artifact is ABSENT, is NOT mid-transfer (guarded above), and is NOT
                // processed/imported, is a GENUINE LOSS: the file the DB claims to hold does not
                // exist. The prior behavior silently PRESERVED such a completed-but-gone row
                // (backfilling a completion_date), which hid the loss forever. The explicit
                // policy is to make it RECOVERABLE: re-queue for re-download (reset to idle).
                //
                // processed/imported are NEVER touched here: syncCompletedFilesStatus filters
                // download_status='completed' (line above), so those external-owned terminal
                // rows are never even loaded; requeueCompletedMissingArtifact additionally
                // guards download_status='completed' as defence-in-depth. A row the user
                // deliberately moved out of the inbox reaches 'processed' via the external
                // pipeline and is thus excluded — only a still-'completed' row with a vanished
                // artifact (never transferred/processed) is re-queued.
                log.warn("[%s] Completed-but-missing artifact (genuine loss) - re-queuing for re-download: %s (path=%s)"
                        .formatted(getRootId(), fileRecord.uniqueId(), fileRecord.localPath()));
                try {
                    MessyUtils.await(DataVerticle.fileRepository.requeueCompletedMissingArtifact(List.of(fileRecord.uniqueId())));
                    synced++;
                } catch (Exception e) {
                    // Surface the failure (do not swallow): a completed-but-missing row
                    // that could not be re-queued remains a silent loss until next boot.
                    log.error("[%s] Failed to re-queue completed-but-missing file %s: %s"
                            .formatted(getRootId(), fileRecord.uniqueId(), e.getMessage()));
                }
                checkSyncComplete(++processed, completedFiles.size(), synced, notFound);
                continue;
            }

            // File exists - verify with Telegram client (bounded so a hung TDLib call cannot pin
            // the worker thread). Query the file to sync its status.
            TdApi.File file;
            boolean queryFailed = false;
            try {
                file = MessyUtils.await(client.execute(new TdApi.GetFile(fileRecord.id()), 15_000, vertx));
            } catch (Exception e) {
                file = null;
                queryFailed = true;
                log.debug("[%s] Could not query file from Telegram (file exists on disk) - keeping as completed: %s"
                        .formatted(getRootId(), fileRecord.uniqueId()));
            }

            if (!queryFailed && file != null && file.local != null && file.local.isDownloadingCompleted) {
                // File is actually completed - sync status
                try {
                    MessyUtils.await(syncFileDownloadStatus(file, null, null));
                } catch (Exception e) {
                    log.debug("[%s] Failed to sync file status: %s".formatted(getRootId(), fileRecord.uniqueId()));
                }
                synced++;
                checkSyncComplete(++processed, completedFiles.size(), synced, notFound);
                continue;
            }

            // File not completed in Telegram cache (or query failed), but exists on disk — keep it
            // as completed since the file is actually there. Ensure completionDate is set if missing.
            if (!queryFailed) {
                log.debug("[%s] File exists on disk but not in Telegram cache - keeping as completed: %s"
                        .formatted(getRootId(), fileRecord.uniqueId()));
            }
            Long completionDate = fileRecord.completionDate();
            if (completionDate == null || completionDate == 0) {
                try {
                    Path filePath = Path.of(fileRecord.localPath());
                    if (Files.exists(filePath)) {
                        completionDate = Files.getLastModifiedTime(filePath).toMillis();
                    } else {
                        completionDate = System.currentTimeMillis();
                    }
                    // D8: surface the write failure (was fire-and-forget). A dropped completionDate
                    // backfill silently leaves the row inconsistent.
                    MessyUtils.await(DataVerticle.fileRepository.updateDownloadStatus(
                            fileRecord.id(),
                            fileRecord.uniqueId(),
                            fileRecord.localPath(),
                            FileRecord.DownloadStatus.completed,
                            completionDate
                    ));
                } catch (Exception ex) {
                    log.debug("[%s] Failed to set completionDate: %s".formatted(getRootId(), ex.getMessage()));
                }
            }
            synced++;
            checkSyncComplete(++processed, completedFiles.size(), synced, notFound);
        }
    }

    private void checkSyncComplete(int processed, int total, int synced, int notFound) {
        if (processed >= total) {
            log.info("[%s] Completed files sync finished. Synced: %d, Not found: %d"
                    .formatted(getRootId(), synced, notFound));
        }
    }
}
