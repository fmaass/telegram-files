package telegram.files;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import org.drinkless.tdlib.TdApi;
import org.jooq.lambda.tuple.Tuple2;
import org.jooq.lambda.tuple.Tuple3;
import telegram.files.repository.AutomationState;
import telegram.files.repository.FileRecord;
import telegram.files.repository.SettingAutoRecords;
import telegram.files.repository.SettingKey;
import telegram.files.repository.SettingTimeLimitedDownload;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class AutoDownloadVerticle extends AbstractVerticle {

    private static final Log log = LogFactory.get();

    private static final int DEFAULT_LIMIT = 5;

    private static final int HISTORY_SCAN_INTERVAL = 2 * 60 * 1000;

    private static final int MAX_HISTORY_SCAN_TIME = 10 * 1000;

    private static final int MAX_WAITING_LENGTH = 30;

    private static final int DOWNLOAD_INTERVAL = 10 * 1000;

    private static final List<String> DEFAULT_FILE_TYPE_ORDER = List.of("photo", "video", "audio", "file");

    // telegramId -> waiting scan threads
    private final Map<Long, LinkedList<WaitingScanThread>> waitingScanThreads = new ConcurrentHashMap<>();

    private final SettingAutoRecords autoRecords;

    private int limit = DEFAULT_LIMIT;

    private SettingTimeLimitedDownload timeLimited;

    public AutoDownloadVerticle() {
        this.autoRecords = AutomationsHolder.INSTANCE.autoRecords();
    }

    @Override
    public void start(Promise<Void> startPromise) {
        initAutoDownload()
                .onFailure(err -> log.error("initAutoDownload() failed: %s".formatted(err.getMessage())))
                .compose(v -> this.initEventConsumer())
                .onSuccess(v -> {
                    vertx.setPeriodic(0, HISTORY_SCAN_INTERVAL,
                            _ -> {
                                if (!isDownloadTime()) {
                                    log.debug("Auto download time limited! Skip scan history.");
                                    return;
                                }

                                autoRecords.getDownloadEnabledItems()
                                        .stream()
                                        .filter(auto -> auto.download.rule.downloadHistory
                                                        && auto.isNotComplete(AutomationState.HISTORY_DOWNLOAD_COMPLETE))
                                        .forEach(auto -> {
                                            if (isDownloadCommentEnabled(auto)
                                                && CollUtil.isNotEmpty(waitingScanThreads.get(auto.telegramId))) {
                                                addCommentMessage(auto);
                                            } else {
                                                if (auto.isNotComplete(AutomationState.HISTORY_DOWNLOAD_SCAN_COMPLETE)) {
                                                    HistoryDiscoveryService.discoverHistory(auto,
                                                        result -> {
                                                            auto.download.nextFileType = result.nextFileType;
                                                            auto.download.nextFromMessageId = result.nextFromMessageId;
                                                            if (result.isComplete) {
                                                                auto.complete(AutomationState.HISTORY_DOWNLOAD_SCAN_COMPLETE);
                                                            }
                                                        },
                                                        System.currentTimeMillis()
                                                    );
                                                } else {
                                                    // If discovery is complete, check if there are any idle files left
                                                    DataVerticle.fileRepository.getFiles(auto.chatId, Map.of(
                                                        "downloadStatus", "idle",
                                                        "limit", "1"
                                                    )).onSuccess(result -> {
                                                        if (CollUtil.isEmpty(result.v1)) {
                                                            // No idle files left, mark history download as complete
                                                            auto.complete(AutomationState.HISTORY_DOWNLOAD_COMPLETE);
                                                            log.info("History download complete for chat %d (no idle files remaining)".formatted(auto.chatId));
                                                        } else {
                                                            log.debug("History download scan complete but %d idle files remain for chat %d".formatted(result.v3, auto.chatId));
                                                        }
                                                    }).onFailure(err ->
                                                        log.warn("Failed to check idle files: %s".formatted(err.getMessage()))
                                                    );
                                                }
                                            }
                                        });
                            });
                    vertx.setPeriodic(0, DOWNLOAD_INTERVAL,
                            _ -> {
                                if (!isDownloadTime()) {
                                    log.debug("Auto download time limited! Skip download.");
                                    return;
                                }
                                List<SettingAutoRecords.Automation> enabledItems = autoRecords.getDownloadEnabledItems();
                                if (CollUtil.isEmpty(enabledItems)) {
                                    log.debug("No download-enabled automations found - skipping download loop.");
                                    return;
                                }
                                enabledItems.forEach(auto -> downloadFromDatabase(auto));
                            });

                    log.info("""
                            Auto download verticle started!
                            |History scan interval: %s ms
                            |Download interval: %s ms
                            |Download limit: %s per telegram account!
                            |Time limit: %s
                            |Auto chats: %s
                            """.formatted(HISTORY_SCAN_INTERVAL,
                            DOWNLOAD_INTERVAL,
                            limit,
                            timeLimited == null ? "" : Json.encode(timeLimited),
                            autoRecords.getDownloadEnabledItems().size()));

                    startPromise.complete();
                })
                .onFailure(startPromise::fail);
    }

    @Override
    public void stop() {
        log.info("Auto download verticle stopped!");
    }

    private Future<Void> initAutoDownload() {
        return Future.all(
                        DataVerticle.settingRepository.<Integer>getByKey(SettingKey.autoDownloadLimit),
                        DataVerticle.settingRepository.<SettingTimeLimitedDownload>getByKey(SettingKey.autoDownloadTimeLimited)
                )
                .onSuccess(results -> {
                    if (results.resultAt(0) != null) {
                        this.limit = results.resultAt(0);
                    }
                    this.timeLimited = results.resultAt(1);
                })
                .onFailure(e -> log.error("Get Auto download limit failed!", e))
                .mapEmpty();
    }

    private Future<Void> initEventConsumer() {
        vertx.eventBus().consumer(EventEnum.SETTING_UPDATE.address(SettingKey.autoDownloadLimit.name()), message -> {
            log.debug("Auto download limit update: %s".formatted(message.body()));
            this.limit = Convert.toInt(message.body(), DEFAULT_LIMIT);
        });
        vertx.eventBus().consumer(EventEnum.SETTING_UPDATE.address(SettingKey.autoDownloadTimeLimited.name()), message -> {
            log.debug("Auto download time limit update: %s".formatted(message.body()));
            this.timeLimited = (SettingTimeLimitedDownload) SettingKey.autoDownloadTimeLimited.converter.apply((String) message.body());
        });
        vertx.eventBus().consumer(EventEnum.MESSAGE_RECEIVED.address(), message -> {
            log.trace("Auto download message received: %s".formatted(message.body()));
            this.onNewMessage((JsonObject) message.body());
        });
        return Future.succeededFuture();
    }

    private void addCommentMessage(SettingAutoRecords.Automation auto) {
        LinkedList<WaitingScanThread> scanThreads = waitingScanThreads.get(auto.telegramId);
        if (CollUtil.isEmpty(scanThreads)) {
            return;
        }
        scanThreads.removeIf(scanThread -> scanThread.isComplete);
        
        // Compute sentinel message date once if historySince is provided
        Long sentinelMessageId = null;
        Integer sentinelMessageDate = null;
        if (auto.download.rule.historySince != null && auto.download.rule.historySince > 0) {
            try {
                TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(auto.telegramId);
                TdApi.Message sentinelMessage = Future.await(
                    telegramVerticle.client.execute(
                        new TdApi.GetChatMessageByDate(auto.chatId, auto.download.rule.historySince)
                    )
                );
                if (sentinelMessage != null) {
                    sentinelMessageId = sentinelMessage.id;
                    sentinelMessageDate = sentinelMessage.date;
                    log.info("History cutoff enabled for comment messages in chat %d: sentinel message ID = %d, date = %d (%s) (cutoff date: %d (%s))"
                        .formatted(auto.chatId, sentinelMessageId, sentinelMessageDate, 
                            DateUtils.formatTelegramDate((long) sentinelMessageDate),
                            auto.download.rule.historySince, DateUtils.formatTelegramDate((long) auto.download.rule.historySince)));
                }
            } catch (Exception e) {
                log.warn("Failed to get sentinel message for comment history cutoff: %s".formatted(e.getMessage()));
            }
        }
        
        Long finalSentinelMessageId = sentinelMessageId;
        Integer finalSentinelMessageDate = sentinelMessageDate;
        waitingScanThreads.get(auto.telegramId).forEach(scanThread -> {
            HistoryDiscoveryService.DiscoveryParams discoveryParams = new HistoryDiscoveryService.DiscoveryParams(
                auto.uniqueKey() + ":" + scanThread.messageThreadId,
                auto.download.rule,
                auto.telegramId,
                scanThread.threadChatId,
                scanThread.nextFileType,
                scanThread.nextFromMessageId
            );
            discoveryParams.messageThreadId = scanThread.messageThreadId;
            discoveryParams.sentinelMessageId = finalSentinelMessageId;
            discoveryParams.sentinelMessageDate = finalSentinelMessageDate;
            
            HistoryDiscoveryService.discoverHistory(discoveryParams,
                result -> {
                    scanThread.nextFileType = result.nextFileType;
                    scanThread.nextFromMessageId = result.nextFromMessageId;
                    if (result.isComplete) {
                        scanThread.isComplete = true;
                    }
                },
                System.currentTimeMillis()
            );
        });
    }

    private Tuple3<String, List<String>, String> handleRule(SettingAutoRecords.DownloadRule rule) {
        String query = null;
        List<String> fileTypes = DEFAULT_FILE_TYPE_ORDER;
        String filterExpr = null;
        if (rule != null) {
            if (StrUtil.isNotBlank(rule.query)) {
                query = rule.query;
            }
            if (CollUtil.isNotEmpty(rule.fileTypes)) {
                fileTypes = rule.fileTypes;
            }
            if (StrUtil.isNotBlank(rule.filterExpr)) {
                filterExpr = rule.filterExpr;
            }
        }
        return new Tuple3<>(query, fileTypes, filterExpr);
    }

    private boolean isDownloadTime() {
        if (timeLimited == null) {
            return true;
        }
        LocalTime now = LocalTime.now();

        LocalTime startTime = LocalTime.parse(timeLimited.startTime);
        LocalTime endTime = LocalTime.parse(timeLimited.endTime);
        if (startTime.equals(LocalTime.MIN) && endTime.equals(LocalTime.MIN)) {
            return true;
        }

        if (startTime.isAfter(endTime)) {
            return now.isAfter(startTime) || now.isBefore(endTime);
        } else {
            return now.isAfter(startTime) && now.isBefore(endTime);
        }
    }

    private boolean isExceedLimit(long telegramId) {
        return getSurplusSize(telegramId) <= 0;
    }

    private int getSurplusSize(long telegramId) {
        Integer downloading = Future.await(DataVerticle.fileRepository.countByStatus(telegramId, FileRecord.DownloadStatus.downloading));
        return downloading == null ? limit : Math.max(0, limit - downloading);
    }

    private boolean isDownloadCommentEnabled(SettingAutoRecords.Automation auto) {
        if (!auto.download.enabled || !auto.download.rule.downloadCommentFiles) {
            return false;
        }
        return TelegramVerticles.get(auto.telegramId)
                .map(telegramVerticle -> telegramVerticle.getChat(auto.chatId))
                .map(chat -> chat.type.getConstructor() == TdApi.ChatTypeSupergroup.CONSTRUCTOR
                             && ((TdApi.ChatTypeSupergroup) chat.type).isChannel)
                .orElse(false);
    }

    
    /**
     * Queue new messages for download in database.
     * Files should already be in database from discovery, so we just mark them as queued.
     */
    private Future<Boolean> queueNewMessagesForDownload(long telegramId, long chatId, int limit) {
        return DownloadQueueService.queueFilesForDownload(telegramId, chatId, limit, null, null)
            .map(count -> {
                if (count > 0) {
                    log.debug("Queued %d new messages for download in database. TelegramId: %d ChatId: %d"
                        .formatted(count, telegramId, chatId));
                }
                return count > 0;
            })
            .onFailure(err -> log.error("Failed to queue new messages for download: %s".formatted(err.getMessage())));
    }

    
    /**
     * Download files from database-driven queue.
     * Queries database for files ready to download and starts downloads.
     */
    private void downloadFromDatabase(SettingAutoRecords.Automation automation) {
        long telegramId = automation.telegramId;
        int queueLimit = limit > Integer.MAX_VALUE / 2 
            ? Integer.MAX_VALUE 
            : limit * 2;
        
        if (automation.download != null && automation.download.rule != null 
            && automation.download.rule.historySince != null && automation.download.rule.historySince > 0) {
            // Use historySince directly as cutoff (it's already a Unix timestamp in seconds)
            queueAndDownload(telegramId, automation.chatId, queueLimit, automation.download.rule.historySince, automation.download.rule.downloadOldestFirst);
            return;
        }
        
        // No cutoff date found, queue without filtering
        Boolean downloadOldestFirst = automation.download != null && automation.download.rule != null
            ? automation.download.rule.downloadOldestFirst : null;
        queueAndDownload(telegramId, automation.chatId, queueLimit, null, downloadOldestFirst);
    }
    
    private void queueAndDownload(long telegramId, long chatId, int queueLimit, Integer cutoffDateSeconds, Boolean downloadOldestFirst) {
        DownloadQueueService.queueFilesForDownload(telegramId, chatId, queueLimit, cutoffDateSeconds, downloadOldestFirst)
            .compose(queuedCount -> {
                if (queuedCount > 0) {
                    log.debug("Queued %d idle files for download. TelegramId: %d, ChatId: %d".formatted(queuedCount, telegramId, chatId));
                }
                // Then get files to download
                return DownloadQueueService.getFilesForDownload(telegramId, chatId, limit, cutoffDateSeconds, downloadOldestFirst);
            })
            .onSuccess(files -> processDownloadFiles(telegramId, files))
            .onFailure(err -> log.error("Failed to queue/get files for download from database (telegramId=%d, chatId=%d): %s".formatted(telegramId, chatId, err.getMessage())));
    }
    
    private void processDownloadFiles(long telegramId, List<FileRecord> files) {
        if (CollUtil.isEmpty(files)) {
            return;
        }
        
        log.debug("Download start from database! TelegramId: %d size: %d".formatted(telegramId, files.size()));
        TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(telegramId);
        
        List<Future<FileRecord>> downloadFutures = files.stream()
            .map(fileRecord -> telegramVerticle.fetchMessage(fileRecord.chatId(), fileRecord.messageId())
                    .compose(message -> {
                        Optional<TdApiHelp.FileHandler<?>> handlerOpt = TdApiHelp.getFileHandler(message);
                        if (handlerOpt.isEmpty()) {
                            log.warn("Cannot get file handler for message %d in chat %d"
                                    .formatted(fileRecord.messageId(), fileRecord.chatId()));
                            return Future.failedFuture("No file handler for message");
                        }
                        Integer telegramFileId = handlerOpt.get().getFileId();
                        log.debug("Start download: DB ID=%d, FileID=%d, MsgID=%d"
                                .formatted(fileRecord.id(), telegramFileId, fileRecord.messageId()));
                        return telegramVerticle.startDownload(fileRecord.chatId(), fileRecord.messageId(), telegramFileId);
                    })
                    .onSuccess(updatedRecord -> {
                        log.debug("Download started: ChatId=%d MsgId=%d"
                                .formatted(fileRecord.chatId(), fileRecord.messageId()));
                        if (updatedRecord.threadChatId() != 0
                                && updatedRecord.messageThreadId() != 0
                                && updatedRecord.threadChatId() != updatedRecord.chatId()) {
                            waitingScanThreads.computeIfAbsent(telegramId, k -> new LinkedList<>())
                                    .add(new WaitingScanThread(telegramId, updatedRecord.threadChatId(), updatedRecord.messageThreadId()));
                        }
                    })
                    .onFailure(e -> {
                        if (e instanceof TelegramRunException tre && tre.getError().code == 404) {
                            log.warn("Message deleted, marking as error: DB ID=%d, ChatId=%d, MsgId=%d"
                                    .formatted(fileRecord.id(), fileRecord.chatId(), fileRecord.messageId()));
                            // D8: surface the write failure (do not fire-and-forget). If this error-mark
                            // silently fails the row stays 'idle'/'downloading' and is retried forever
                            // against a deleted message.
                            DataVerticle.fileRepository.updateDownloadStatus(
                                    fileRecord.id(), fileRecord.uniqueId(), null,
                                    FileRecord.DownloadStatus.error, null)
                                    .onFailure(err -> log.error("Failed to mark deleted-message file %s as error: %s"
                                            .formatted(fileRecord.uniqueId(), err.getMessage())));
                        } else {
                            log.error("Download failed: ChatId=%d MsgId=%d DB ID=%d — %s"
                                    .formatted(fileRecord.chatId(), fileRecord.messageId(), fileRecord.id(), e.getMessage()));
                        }
                    }))
            .toList();
        
        Future.all(downloadFutures)
            .onSuccess(results -> log.debug("Started %d downloads. TelegramId: %d".formatted(files.size(), telegramId)))
            .onFailure(err -> log.error("Failed to start some downloads: %s".formatted(err.getMessage())));
    }

    private void onNewMessage(JsonObject jsonObject) {
        long telegramId = jsonObject.getLong("telegramId");
        long chatId = jsonObject.getLong("chatId");
        long messageId = jsonObject.getLong("messageId");
        autoRecords.getDownloadEnabledItems().stream()
                .filter(item -> item.telegramId == telegramId && item.chatId == chatId)
                .findFirst()
                .flatMap(_ -> TelegramVerticles.get(telegramId))
                .ifPresent(telegramVerticle -> {
                    if (telegramVerticle.authorized) {
                        telegramVerticle.client.execute(new TdApi.GetMessage(chatId, messageId))
                            .onSuccess(message -> {
                                Optional<TdApiHelp.FileHandler<?>> handlerOpt = TdApiHelp.getFileHandler(message);
                                if (handlerOpt.isPresent()) {
                                    TdApiHelp.FileHandler<?> handler = handlerOpt.get();
                                    FileRecord fileRecord = handler.convertFileRecord(telegramId);
                                    DataVerticle.fileRepository.createIfNotExist(fileRecord)
                                        .compose(created -> {
                                            if (created) {
                                                log.debug("Created new file record for message %d in chat %d".formatted(messageId, chatId));
                                            }
                                            // Queue for download (whether newly created or already exists)
                                            return DownloadQueueService.queueFilesForDownload(telegramId, chatId, 5, null, null);
                                        })
                                        .onSuccess(queued -> {
                                            if (queued > 0) {
                                                log.debug("Queued %d new file(s) for download in chat %d".formatted(queued, chatId));
                                            }
                                        })
                                        .onFailure(err ->
                                            log.error("Failed to persist/queue new message: %s".formatted(err.getMessage()))
                                        );
                                } else {
                                    log.debug("Message %d in chat %d has no file handler (unsupported content type)".formatted(messageId, chatId));
                                }
                            })
                            .onFailure(err -> log.error("Auto download fail. Get message failed: %s".formatted(err.getMessage())));
                    }
                });
    }

    private static class ScanParams {
        public String uniqueKey;

        public SettingAutoRecords.DownloadRule rule;

        public long telegramId;

        public long chatId;

        public long messageThreadId;

        public String nextFileType;

        public long nextFromMessageId;

        public Long sentinelMessageId;
        public Integer sentinelMessageDate;

        public ScanParams(String uniqueKey,
                          SettingAutoRecords.DownloadRule rule,
                          long telegramId,
                          long chatId,
                          String nextFileType,
                          long nextFromMessageId) {
            this.uniqueKey = uniqueKey;
            this.rule = rule;
            this.telegramId = telegramId;
            this.chatId = chatId;
            this.nextFileType = nextFileType;
            this.nextFromMessageId = nextFromMessageId;
        }
    }

    private static class ScanResult {
        public String nextFileType;

        public long nextFromMessageId;

        public boolean isComplete;

        public ScanResult(String nextFileType, long nextFromMessageId, boolean isComplete) {
            this.nextFileType = nextFileType;
            this.nextFromMessageId = nextFromMessageId;
            this.isComplete = isComplete;
        }
    }

    private static class WaitingScanThread {
        public long telegramId;

        public long threadChatId;

        public long messageThreadId;

        public String nextFileType;

        public long nextFromMessageId;

        public boolean isComplete;

        public WaitingScanThread(long telegramId, long threadChatId, long messageThreadId) {
            this.telegramId = telegramId;
            this.threadChatId = threadChatId;
            this.messageThreadId = messageThreadId;
        }
    }

    private record MessageWrapper(TdApi.Message message, boolean isHistorical) {
    }
}
