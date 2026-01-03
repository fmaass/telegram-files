package telegram.files.download;

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
import telegram.files.automation.AutomationsHolder;
import telegram.files.core.EventEnum;
import telegram.files.core.TelegramRunException;
import telegram.files.core.TelegramVerticle;
import telegram.files.core.TelegramVerticles;
import telegram.files.DataVerticle;
import telegram.files.ServiceContext;
import telegram.files.TdApiHelp;
import telegram.files.MessageFilter;
import telegram.files.util.DateUtils;
import telegram.files.util.ErrorHandling;
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

    // telegramId -> messages
    private final Map<Long, LinkedList<MessageWrapper>> waitingDownloadMessages = new ConcurrentHashMap<>();

    // telegramId -> waiting scan threads
    private final Map<Long, LinkedList<WaitingScanThread>> waitingScanThreads = new ConcurrentHashMap<>();

    private final SettingAutoRecords autoRecords;

    private int limit = DEFAULT_LIMIT;

    private SettingTimeLimitedDownload timeLimited;

    private ServiceContext context;
    private DownloadQueueService queueService;

    public AutoDownloadVerticle() {
        this.autoRecords = AutomationsHolder.INSTANCE.autoRecords();
        AutomationsHolder.INSTANCE.registerOnRemoveListener(removedItems -> removedItems.forEach(item ->
                waitingDownloadMessages.getOrDefault(item.telegramId, new LinkedList<>())
                        .removeIf(m -> m.message.chatId == item.chatId)));
    }

    @Override
    public void start(Promise<Void> startPromise) {
        this.context = ServiceContext.fromDataVerticle();
        this.queueService = new DownloadQueueService(context);
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
                                                    }).onFailure(err -> {
                                                        // On error, check in-memory queue as fallback
                                                        log.warn("Failed to check idle files, falling back to in-memory queue check: %s".formatted(err.getMessage()));
                                                        LinkedList<MessageWrapper> messageWrappers = waitingDownloadMessages.get(auto.telegramId);
                                                        if (CollUtil.isEmpty(messageWrappers) ||
                                                            messageWrappers.stream().noneMatch(w -> w.isHistorical)) {
                                                            auto.complete(AutomationState.HISTORY_DOWNLOAD_COMPLETE);
                                                        }
                                                    });
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
                                enabledItems.forEach(auto -> downloadFromDatabase(auto.telegramId));
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

    /**
     * @deprecated Replaced by HistoryDiscoveryService.discoverHistory()
     */
    @Deprecated
    private void addHistoryMessage(SettingAutoRecords.Automation auto) {
        ScanParams params = new ScanParams(auto.uniqueKey(),
                auto.download.rule,
                auto.telegramId,
                auto.chatId,
                auto.download.nextFileType,
                auto.download.nextFromMessageId);
        
        // Compute sentinel message date if historySince is provided
        if (auto.download.rule.historySince != null && auto.download.rule.historySince > 0) {
            try {
                TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(auto.telegramId);
                TdApi.Message sentinelMessage = Future.await(
                    telegramVerticle.client.execute(
                        new TdApi.GetChatMessageByDate(auto.chatId, auto.download.rule.historySince)
                    )
                );
                if (sentinelMessage != null) {
                    params.sentinelMessageId = sentinelMessage.id;
                    params.sentinelMessageDate = sentinelMessage.date;
                    log.info("History cutoff enabled for chat %d: sentinel message ID = %d, date = %d (%s) (cutoff date: %d (%s))"
                        .formatted(auto.chatId, params.sentinelMessageId, params.sentinelMessageDate,
                            DateUtils.formatTelegramDate((long) params.sentinelMessageDate),
                            auto.download.rule.historySince, DateUtils.formatTelegramDate((long) auto.download.rule.historySince)));
                }
            } catch (Exception e) {
                log.warn("Failed to get sentinel message for history cutoff: %s".formatted(e.getMessage()));
            }
        }
        
        addHistoryMessage(params,
                result -> {
                    auto.download.nextFileType = result.nextFileType;
                    auto.download.nextFromMessageId = result.nextFromMessageId;
                    if (result.isComplete) {
                        auto.complete(AutomationState.HISTORY_DOWNLOAD_SCAN_COMPLETE);
                    }
                },
                System.currentTimeMillis()
        );
    }

    /**
     * @deprecated Replaced by HistoryDiscoveryService.discoverHistory()
     */
    @Deprecated
    private void addHistoryMessage(ScanParams params,
                                   Consumer<ScanResult> callback,
                                   long currentTimeMillis) {
        String uniqueKey = params.uniqueKey;
        long telegramId = params.telegramId;
        long chatId = params.chatId;
        long nextFromMessageId = params.nextFromMessageId;
        String nextFileType = params.nextFileType;
        Tuple3<String, List<String>, String> rule = handleRule(params.rule);
        if (StrUtil.isBlank(nextFileType)) {
            nextFileType = rule.v2.getFirst();
        }

        log.debug("Start scan history! TelegramId: %d ChatId: %d FileType: %s".formatted(telegramId, chatId, nextFileType));
        if (System.currentTimeMillis() - currentTimeMillis > MAX_HISTORY_SCAN_TIME) {
            log.debug("Scan history timeout! TelegramId: %d ChatId: %d".formatted(telegramId, chatId));
            callback.accept(new ScanResult(nextFileType, nextFromMessageId, false));
            return;
        }
        if (isExceedLimit(telegramId)) {
            log.debug("Scan history exceed per telegram account limit! TelegramId: %d ChatId: %d".formatted(telegramId, chatId));
            callback.accept(new ScanResult(nextFileType, nextFromMessageId, false));
            return;
        }

        TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(telegramId);
        if (!telegramVerticle.authorized) {
            callback.accept(new ScanResult(nextFileType, nextFromMessageId, false));
            return;
        }
        TdApi.SearchChatMessages searchChatMessages = new TdApi.SearchChatMessages();
        searchChatMessages.query = rule.v1;
        searchChatMessages.chatId = chatId;
        
        // Handle reverse order (oldest to newest)
        boolean downloadOldestFirst = params.rule != null && params.rule.downloadOldestFirst;
        if (downloadOldestFirst) {
            // For oldest-to-newest: start from message ID 1 if beginning, use negative offset to scan forward
            searchChatMessages.fromMessageId = nextFromMessageId == 0 ? 1 : nextFromMessageId;
            // Use negative offset to scan forward, but ensure limit > -offset (Telegram API requirement)
            // Start with smaller offset to avoid API error, will paginate if needed
            searchChatMessages.offset = -10;  // Negative offset scans forward (toward newer messages)
        } else {
            // For newest-to-oldest (default): start from 0 (newest), scan backward
            searchChatMessages.fromMessageId = nextFromMessageId;
            searchChatMessages.offset = 0;  // Default behavior
        }
        
        // Ensure limit is always greater than -offset (Telegram API requirement)
        int desiredLimit = Math.min(MAX_WAITING_LENGTH, 100);
        if (searchChatMessages.offset < 0) {
            // When using negative offset, limit must be > -offset
            searchChatMessages.limit = Math.max(desiredLimit, Math.abs(searchChatMessages.offset) + 1);
        } else {
            searchChatMessages.limit = desiredLimit;
        }
        searchChatMessages.filter = TdApiHelp.getSearchMessagesFilter(nextFileType);
        searchChatMessages.topicId = params.messageThreadId > 0 ? new TdApi.MessageTopicThread(params.messageThreadId) : null;
        final String finalNextFileType = nextFileType;
        final long finalNextFromMessageId = nextFromMessageId;
        TdApi.FoundChatMessages foundChatMessages = Future.await(telegramVerticle.client.execute(searchChatMessages)
                .onFailure(r -> {
                    log.warn("Search chat messages failed! TelegramId: %d ChatId: %d".formatted(telegramId, chatId), r);
                    if (r instanceof TelegramRunException tre) {
                        TdApi.Error error = tre.getError();
                        if (error.code == 400 && ("Can't access the chat".equals(error.message))) {
                            log.error("%s Can't access the chat, stop auto download!".formatted(uniqueKey));
                            callback.accept(new ScanResult(finalNextFileType, nextFromMessageId, true));
                        }
                    }
                })
        );
        if (foundChatMessages == null) {
            callback.accept(new ScanResult(nextFileType, nextFromMessageId, false));
            return;
        }
        if (foundChatMessages.messages.length == 0) {
            List<String> fileTypes = rule.v2;
            int nextTypeIndex = fileTypes.indexOf(nextFileType) + 1;
            if (nextTypeIndex < fileTypes.size()) {
                params.nextFileType = fileTypes.get(nextTypeIndex);
                // Reset to appropriate starting position based on download order (reuse variable from line 286)
                params.nextFromMessageId = downloadOldestFirst ? 1 : 0;
                log.debug("%s No more %s files found! Switch to %s".formatted(uniqueKey, nextFileType, params.nextFileType));
                addHistoryMessage(params, callback, currentTimeMillis);
            } else {
                // Check if nextFromMessageId is beyond newest message (should scan backwards)
                // If we have a high nextFromMessageId but no messages, try scanning backwards
                Long oldestMsgId = Future.await(
                    DataVerticle.fileRepository.getMinMessageId(telegramId, chatId)
                );
                
                // If we have files downloaded and nextFromMessageId is beyond the newest, reset to scan backwards
                if (oldestMsgId != null && oldestMsgId > 0 && nextFromMessageId > oldestMsgId) {
                    // We're beyond the newest message, reset to scan backwards from oldest
                    log.info("%s No messages found at nextFromMessageId %d (beyond newest). Resetting to scan backwards from oldest message %d"
                        .formatted(uniqueKey, nextFromMessageId, oldestMsgId));
                    params.nextFileType = rule.v2.getFirst(); // Reset to first file type
                    params.nextFromMessageId = Math.max(0, oldestMsgId - 1000); // Start slightly before oldest
                    addHistoryMessage(params, callback, currentTimeMillis);
                    return;
                }
                
                // If nextFromMessageId is 0 and no files exist, start scanning from 0
                // This handles the case when history scanning is first enabled
                if (nextFromMessageId == 0 && (oldestMsgId == null || oldestMsgId == 0)) {
                    log.debug("%s No files found yet, starting history scan from beginning. TelegramId: %d ChatId: %d"
                        .formatted(uniqueKey, telegramId, chatId));
                    // Keep nextFromMessageId at 0 to start scanning from the beginning
                    callback.accept(new ScanResult(nextFileType, 0, false));
                    return;
                }
                
                // Only mark complete if we've truly exhausted all messages
                log.debug("%s No more history files found! TelegramId: %d ChatId: %d".formatted(uniqueKey, telegramId, chatId));
                callback.accept(new ScanResult(nextFileType, nextFromMessageId, true));
            }
        } else {
            Predicate<TdApi.Message> predicate = MessageFilter.filter(rule.v3);
            
            // Check if we've reached the history cutoff date
            final boolean reachedCutoff;
            if (params.sentinelMessageDate != null && foundChatMessages.messages.length > 0) {
                // Check the oldest message date (last in array since messages are sorted newest first)
                int oldestMessageDate = foundChatMessages.messages[foundChatMessages.messages.length - 1].date;
                if (oldestMessageDate < params.sentinelMessageDate) {
                    reachedCutoff = true;
                    log.info("Reached history cutoff at message date %d (sentinel date: %d) for chat %d"
                        .formatted(oldestMessageDate, params.sentinelMessageDate, chatId));
                } else {
                    reachedCutoff = false;
                }
            } else {
                reachedCutoff = false;
            }
            
            DataVerticle.fileRepository.getFilesByUniqueId(TdApiHelp.getFileUniqueIds(Arrays.asList(foundChatMessages.messages)))
                    .onSuccess(existFiles -> {
                        List<TdApi.Message> messages = Stream.of(foundChatMessages.messages)
                                .parallel()
                                .filter(predicate)
                                .filter(message -> {
                                    // Filter out messages older than the sentinel date (use date, not ID)
                                    if (params.sentinelMessageDate != null && message.date < params.sentinelMessageDate) {
                                        return false;
                                    }
                                    
                                    String uniqueId = TdApiHelp.getFileUniqueId(message);
                                    if (!existFiles.containsKey(uniqueId)) {
                                        return true;
                                    } else {
                                        FileRecord fileRecord = existFiles.get(uniqueId);
                                        return fileRecord.isDownloadStatus(FileRecord.DownloadStatus.idle);
                                    }
                                })
                                .toList();
                        
                        if (CollUtil.isEmpty(messages)) {
                            if (reachedCutoff) {
                                // We've reached the cutoff - check if there are existing idle files to queue
                                log.info("History scan complete due to cutoff date for chat %d - checking for existing idle files".formatted(chatId));
                                
                                // Query database for existing idle files post-cutoff and queue them for download
                                if (params.sentinelMessageDate != null) {
                                    DataVerticle.fileRepository.getFiles(chatId, Map.of(
                                        "downloadStatus", "idle",
                                        "types", "audio,file",
                                        "limit", "50"  // Queue up to 50 files at a time to avoid overwhelming
                                    )).onSuccess(result -> {
                                        List<FileRecord> idleFiles = result.v1;
                                        if (!CollUtil.isEmpty(idleFiles)) {
                                            // Queue idle files for download (don't filter by date as dates may be incorrect)
                                            List<FileRecord> filesToQueue = idleFiles.stream()
                                                .limit(50)  // Limit batch size to avoid overwhelming
                                                .toList();
                                            
                                            if (!CollUtil.isEmpty(filesToQueue)) {
                                                log.info("Found %d existing idle files for chat %d - fetching messages and queueing for download"
                                                    .formatted(filesToQueue.size(), chatId));
                                                
                                                // Fetch messages from Telegram API and queue them
                                                TelegramVerticle tgVerticle = TelegramVerticles.get(telegramId).orElse(null);
                                                if (tgVerticle != null) {
                                                    // Fetch messages in batches
                                                    List<Long> messageIds = filesToQueue.stream()
                                                        .map(FileRecord::messageId)
                                                        .toList();
                                                    
                                                    // Use GetMessages for batch fetching
                                                    long[] messageIdArray = messageIds.stream().mapToLong(Long::longValue).toArray();
                                                    tgVerticle.client.execute(new TdApi.GetMessages(chatId, messageIdArray))
                                                        .onSuccess(fetchedMessages -> {
                                                            if (fetchedMessages != null && fetchedMessages.messages.length > 0) {
                                                                List<TdApi.Message> messagesToQueue = Arrays.asList(fetchedMessages.messages);
                                                                boolean queued = addWaitingDownloadMessages(telegramId, messagesToQueue, false, true);
                                                                if (queued) {
                                                                    log.info("Successfully queued %d idle files for download from chat %d"
                                                                        .formatted(messagesToQueue.size(), chatId));
                                                                }
                                                            }
                                                            callback.accept(new ScanResult(finalNextFileType, finalNextFromMessageId, true));
                                                        })
                                                        .onFailure(err -> {
                                                            log.warn("Failed to fetch messages for idle files: %s".formatted(err.getMessage()));
                                                            callback.accept(new ScanResult(finalNextFileType, finalNextFromMessageId, true));
                                                        });
                                                } else {
                                                    log.warn("Telegram verticle not found for telegramId %d".formatted(telegramId));
                                                    callback.accept(new ScanResult(finalNextFileType, finalNextFromMessageId, true));
                                                }
                                            } else {
                                                callback.accept(new ScanResult(finalNextFileType, finalNextFromMessageId, true));
                                            }
                                        } else {
                                            callback.accept(new ScanResult(finalNextFileType, finalNextFromMessageId, true));
                                        }
                                    }).onFailure(err -> {
                                        log.warn("Failed to query idle files: %s".formatted(err.getMessage()));
                                        callback.accept(new ScanResult(finalNextFileType, finalNextFromMessageId, true));
                                    });
                                } else {
                                    callback.accept(new ScanResult(finalNextFileType, finalNextFromMessageId, true));
                                }
                            } else {
                                // Check if nextFromMessageId from API is 0 (no more messages in this direction)
                                // If we're scanning forwards and hit 0, try scanning backwards
                                if (foundChatMessages.nextFromMessageId == 0 && nextFromMessageId > 0) {
                                    Long oldestMsgId = Future.await(
                                        DataVerticle.fileRepository.getMinMessageId(telegramId, chatId)
                                    );
                                    
                                    if (oldestMsgId != null && oldestMsgId > 0 && nextFromMessageId > oldestMsgId) {
                                        log.info("%s Hit end of forward scan at %d. Resetting to scan backwards from %d"
                                            .formatted(uniqueKey, nextFromMessageId, oldestMsgId));
                                        params.nextFileType = rule.v2.getFirst();
                                        params.nextFromMessageId = Math.max(0, oldestMsgId - 1000);
                                        addHistoryMessage(params, callback, currentTimeMillis);
                                        return;
                                    }
                                }
                                params.nextFromMessageId = foundChatMessages.nextFromMessageId;
                                addHistoryMessage(params, callback, currentTimeMillis);
                            }
                        } else {
                            boolean shouldContinue = addWaitingDownloadMessages(telegramId, messages, false, true);
                            if (reachedCutoff) {
                                // We've reached the cutoff - also check for existing idle files to queue
                                log.info("History scan complete due to cutoff date for chat %d - checking for existing idle files".formatted(chatId));
                                
                                // Query database for existing idle files post-cutoff and queue them for download
                                if (params.sentinelMessageDate != null) {
                                    DataVerticle.fileRepository.getFiles(chatId, Map.of(
                                        "downloadStatus", "idle",
                                        "types", "audio,file",
                                        "limit", "50"
                                    )).onSuccess(result -> {
                                        List<FileRecord> idleFiles = result.v1;
                                        if (!CollUtil.isEmpty(idleFiles)) {
                                            // Queue idle files for download (don't filter by date as dates may be incorrect)
                                            List<FileRecord> filesToQueue = idleFiles.stream()
                                                .limit(50)
                                                .toList();
                                            
                                            if (!CollUtil.isEmpty(filesToQueue)) {
                                                log.info("Found %d existing idle files for chat %d - fetching messages and queueing for download"
                                                    .formatted(filesToQueue.size(), chatId));
                                                
                                                TelegramVerticle tgVerticle = TelegramVerticles.get(telegramId).orElse(null);
                                                if (tgVerticle != null) {
                                                    List<Long> messageIds = filesToQueue.stream()
                                                        .map(FileRecord::messageId)
                                                        .toList();
                                                    
                                                    long[] messageIdArray = messageIds.stream().mapToLong(Long::longValue).toArray();
                                                    tgVerticle.client.execute(new TdApi.GetMessages(chatId, messageIdArray))
                                                        .onSuccess(fetchedMessages -> {
                                                            if (fetchedMessages != null && fetchedMessages.messages.length > 0) {
                                                                List<TdApi.Message> messagesToQueue = Arrays.asList(fetchedMessages.messages);
                                                                boolean queued = addWaitingDownloadMessages(telegramId, messagesToQueue, false, true);
                                                                if (queued) {
                                                                    log.info("Successfully queued %d idle files for download from chat %d"
                                                                        .formatted(messagesToQueue.size(), chatId));
                                                                }
                                                            }
                                                            callback.accept(new ScanResult(finalNextFileType, finalNextFromMessageId, true));
                                                        })
                                                        .onFailure(err -> {
                                                            log.warn("Failed to fetch messages for idle files: %s".formatted(err.getMessage()));
                                                            callback.accept(new ScanResult(finalNextFileType, finalNextFromMessageId, true));
                                                        });
                                                } else {
                                                    log.warn("Telegram verticle not found for telegramId %d".formatted(telegramId));
                                                    callback.accept(new ScanResult(finalNextFileType, finalNextFromMessageId, true));
                                                }
                                            } else {
                                                callback.accept(new ScanResult(finalNextFileType, finalNextFromMessageId, true));
                                            }
                                        } else {
                                            callback.accept(new ScanResult(finalNextFileType, finalNextFromMessageId, true));
                                        }
                                    }).onFailure(err -> {
                                        log.warn("Failed to query idle files: %s".formatted(err.getMessage()));
                                        callback.accept(new ScanResult(finalNextFileType, finalNextFromMessageId, true));
                                    });
                                } else {
                                    callback.accept(new ScanResult(finalNextFileType, finalNextFromMessageId, true));
                                }
                            } else if (shouldContinue) {
                                // Check if nextFromMessageId from API is 0 (no more messages in this direction)
                                // If we're scanning forwards and hit 0, try scanning backwards
                                if (foundChatMessages.nextFromMessageId == 0 && nextFromMessageId > 0) {
                                    Long oldestMsgId = Future.await(
                                        DataVerticle.fileRepository.getMinMessageId(telegramId, chatId)
                                    );
                                    
                                    if (oldestMsgId != null && oldestMsgId > 0 && nextFromMessageId > oldestMsgId) {
                                        log.info("%s Hit end of forward scan at %d. Resetting to scan backwards from %d"
                                            .formatted(uniqueKey, nextFromMessageId, oldestMsgId));
                                        params.nextFileType = rule.v2.getFirst();
                                        params.nextFromMessageId = Math.max(0, oldestMsgId - 1000);
                                        addHistoryMessage(params, callback, currentTimeMillis);
                                        return;
                                    }
                                }
                                params.nextFromMessageId = foundChatMessages.nextFromMessageId;
                                addHistoryMessage(params, callback, currentTimeMillis);
                            }
                        }
                    });
        }
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
        List<MessageWrapper> waitingMessages = this.waitingDownloadMessages.get(telegramId);
        return getSurplusSize(telegramId) <= 0 || (waitingMessages != null && waitingMessages.size() > limit);
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
     * @deprecated Replaced by database-driven queue.
     */
    @Deprecated
    private boolean addWaitingDownloadMessages(long telegramId,
                                               List<TdApi.Message> messages,
                                               boolean force,
                                               boolean isHistorical) {
        if (CollUtil.isEmpty(messages)) {
            return false;
        }
        LinkedList<MessageWrapper> waitingMessages = this.waitingDownloadMessages.get(telegramId);
        if (waitingMessages == null) {
            waitingMessages = new LinkedList<>();
        }
        if (!force && waitingMessages.size() > MAX_WAITING_LENGTH) {
            return false;
        } else {
            log.debug("Add waiting download messages: %d".formatted(messages.size()));
            waitingMessages.addAll(TdApiHelp.filterUniqueMessages(messages)
                    .stream()
                    .map(message -> new MessageWrapper(message, isHistorical))
                    .toList()
            );
        }
        this.waitingDownloadMessages.put(telegramId, waitingMessages);
        return true;
    }
    
    /**
     * Queue new messages for download in database.
     * Files should already be in database from discovery, so we just mark them as queued.
     */
    private Future<Boolean> queueNewMessagesForDownload(long telegramId, long chatId, int limit) {
        return queueService.queueFilesForDownload(telegramId, chatId, limit, null, null)
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
     * @deprecated Replaced by downloadFromDatabase()
     */
    @Deprecated
    private void download(long telegramId) {
        if (CollUtil.isEmpty(waitingDownloadMessages)) {
            return;
        }
        LinkedList<MessageWrapper> messages = waitingDownloadMessages.get(telegramId);
        if (CollUtil.isEmpty(messages)) {
            return;
        }
        log.debug("Download start! TelegramId: %d size: %d".formatted(telegramId, messages.size()));
        TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(telegramId);
        if (!telegramVerticle.authorized) {
            return;
        }
        int surplusSize = getSurplusSize(telegramId);
        if (surplusSize <= 0) {
            return;
        }

        List<MessageWrapper> downloadMessages = IntStream.range(0, Math.min(surplusSize, messages.size()))
                .mapToObj(_ -> messages.poll())
                .toList();
        downloadMessages.forEach(messageWrapper -> {
            TdApi.Message message = messageWrapper.message;
            Integer fileId = TdApiHelp.getFileId(message);
            log.debug("Start download file: %s".formatted(fileId));
            telegramVerticle.startDownload(message.chatId, message.id, fileId)
                    .onSuccess(fileRecord -> {
                        log.debug("Start download file success! ChatId: %d MessageId:%d FileId:%d"
                                .formatted(message.chatId, message.id, fileId));
                        if (fileRecord.threadChatId() != 0
                            && fileRecord.messageThreadId() != 0
                            && fileRecord.threadChatId() != fileRecord.chatId()) {
                            waitingScanThreads.computeIfAbsent(telegramId, _ -> new LinkedList<>())
                                    .add(new WaitingScanThread(telegramId, fileRecord.threadChatId(), fileRecord.messageThreadId()));
                        }
                    })
                    .onFailure(e -> log.error("Download file failed! ChatId: %d MessageId:%d FileId:%d"
                            .formatted(message.chatId, message.id, fileId), e));
        });
        log.debug("Remaining download messages: %d".formatted(messages.size()));
    }
    
    /**
     * Download files from database-driven queue.
     * Queries database for files ready to download and starts downloads.
     */
    private void downloadFromDatabase(long telegramId) {
        int queueLimit = limit > Integer.MAX_VALUE / 2 
            ? Integer.MAX_VALUE 
            : limit * 2;
        // Get cutoff date from automation settings
        SettingAutoRecords.Automation automation = autoRecords.getDownloadEnabledItems().stream()
            .filter(auto -> auto.telegramId == telegramId)
            .findFirst()
            .orElse(null);
        
        if (automation != null && automation.download != null && automation.download.rule != null 
            && automation.download.rule.historySince != null && automation.download.rule.historySince > 0) {
            // Get sentinel message date for cutoff
            Optional<TelegramVerticle> verticleOpt = TelegramVerticles.get(telegramId);
            if (verticleOpt.isPresent()) {
                verticleOpt.get().client.execute(
                    new TdApi.GetChatMessageByDate(automation.chatId, automation.download.rule.historySince)
                ).onSuccess(sentinelMessage -> {
                    Integer cutoff = sentinelMessage != null ? sentinelMessage.date : null;
                    queueAndDownload(telegramId, queueLimit, cutoff);
                }).onFailure(err -> {
                    log.warn("Failed to get sentinel message for cutoff, queueing without cutoff: %s".formatted(err.getMessage()));
                    queueAndDownload(telegramId, queueLimit, null);
                });
                return;
            } else {
                log.warn("Telegram verticle not found for telegramId %d, queueing without cutoff".formatted(telegramId));
            }
        }
        
        // No cutoff date found, queue without filtering
        queueAndDownload(telegramId, queueLimit, null);
    }
    
    private void queueAndDownload(long telegramId, int queueLimit, Integer cutoffDateSeconds) {
        // Get downloadOldestFirst setting from automation
        SettingAutoRecords.Automation automation = autoRecords.getDownloadEnabledItems().stream()
            .filter(auto -> auto.telegramId == telegramId)
            .findFirst()
            .orElse(null);
        Boolean downloadOldestFirst = automation != null && automation.download != null && automation.download.rule != null
            ? automation.download.rule.downloadOldestFirst : null;
        
        queueService.queueFilesForDownload(telegramId, 0, queueLimit, cutoffDateSeconds, downloadOldestFirst)
            .compose(queuedCount -> {
                if (queuedCount > 0) {
                    log.debug("Queued %d idle files for download. TelegramId: %d".formatted(queuedCount, telegramId));
                }
                // Then get files to download
                return queueService.getFilesForDownload(telegramId, limit);
            })
            .onSuccess(files -> processDownloadFiles(telegramId, files))
            .onFailure(err -> log.error("Failed to queue/get files for download from database: %s".formatted(err.getMessage())));
    }
    
    private void processDownloadFiles(long telegramId, List<FileRecord> files) {
        if (CollUtil.isEmpty(files)) {
            return;
        }
        
        log.debug("Download start from database! TelegramId: %d size: %d".formatted(telegramId, files.size()));
        TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(telegramId);
        
        List<Future<FileRecord>> downloadFutures = files.stream()
            .map(fileRecord -> {
                // Get the Telegram file ID from the message first
                // startDownload expects the Telegram file ID, not the database record ID
                return telegramVerticle.client.execute(new TdApi.GetMessage(fileRecord.chatId(), fileRecord.messageId()))
                    .compose(message -> {
                        Optional<TdApiHelp.FileHandler<?>> handlerOpt = TdApiHelp.getFileHandler(message);
                        if (handlerOpt.isEmpty()) {
                            log.warn("Cannot get file handler for message %d in chat %d".formatted(fileRecord.messageId(), fileRecord.chatId()));
                            return Future.failedFuture("No file handler for message");
                        }
                        TdApiHelp.FileHandler<?> handler = handlerOpt.get();
                        Integer telegramFileId = handler.getFileId();
                        log.debug("Start download file from database: DB ID=%d, Telegram File ID=%d, Message ID=%d".formatted(fileRecord.id(), telegramFileId, fileRecord.messageId()));
                        return telegramVerticle.startDownload(fileRecord.chatId(), fileRecord.messageId(), telegramFileId);
                    })
                    .onSuccess(updatedRecord -> {
                        log.debug("Start download file success! ChatId: %d MessageId:%d"
                            .formatted(fileRecord.chatId(), fileRecord.messageId()));
                        if (updatedRecord.threadChatId() != 0
                            && updatedRecord.messageThreadId() != 0
                            && updatedRecord.threadChatId() != updatedRecord.chatId()) {
                            waitingScanThreads.computeIfAbsent(telegramId, k -> new LinkedList<>())
                                .add(new WaitingScanThread(telegramId, updatedRecord.threadChatId(), updatedRecord.messageThreadId()));
                        }
                    })
                    .onFailure(e -> log.error("Download file failed! ChatId: %d MessageId:%d DB ID:%d"
                        .formatted(fileRecord.chatId(), fileRecord.messageId(), fileRecord.id()), e));
            })
            .toList();
        
        Future.all(downloadFutures)
            .onSuccess(results -> {
                log.debug("Started %d downloads. TelegramId: %d".formatted(files.size(), telegramId));
            })
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
                                            return queueService.queueFilesForDownload(telegramId, chatId, 5, null, null);
                                        })
                                        .onSuccess(queued -> {
                                            if (queued > 0) {
                                                log.debug("Queued %d new file(s) for download in chat %d".formatted(queued, chatId));
                                            }
                                        })
                                        .onFailure(err -> {
                                            log.warn("Failed to persist/queue new message, falling back to in-memory queue: %s".formatted(err.getMessage()));
                                            addWaitingDownloadMessages(telegramId, List.of(message), true, false);
                                        });
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
