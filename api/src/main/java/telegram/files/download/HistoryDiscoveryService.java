package telegram.files;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import org.drinkless.tdlib.TdApi;
import org.jooq.lambda.tuple.Tuple2;
import telegram.files.repository.FileRecord;
import telegram.files.repository.SettingAutoRecords;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Service for discovering files from Telegram API and persisting them to database.
 * 
 * Responsibility: Discover files from Telegram API and persist to database.
 * Does NOT queue downloads - that's handled by DownloadQueueService.
 * 
 * Key Features:
 * - Scans Telegram history based on rules
 * - Inserts/updates file_record entries with scan_state='idle'
 * - Respects cutoff dates
 * - Handles reverse download order (oldest-first)
 * - Updates nextFromMessageId and nextFileType for continuation
 */
public class HistoryDiscoveryService {
    
    private static final Log log = LogFactory.get();
    
    private static final int MAX_HISTORY_SCAN_TIME = 10 * 1000;
    private static final int MAX_BATCH_SIZE = 100;
    private static final int MAX_IDLE_FILES_TO_QUEUE = 1000;
    private static final List<String> DEFAULT_FILE_TYPE_ORDER = List.of("photo", "video", "audio", "file");
    
    /**
     * Scan history and discover files, persisting them to database.
     * 
     * @param automation The automation configuration
     * @param callback Called with scan result (nextFileType, nextFromMessageId, isComplete)
     * @param currentTimeMillis Start time for timeout checking
     */
    public static void discoverHistory(SettingAutoRecords.Automation automation,
                                       Consumer<DiscoveryResult> callback,
                                       long currentTimeMillis) {
        DiscoveryParams params = new DiscoveryParams(
            automation.uniqueKey(),
            automation.download.rule,
            automation.telegramId,
            automation.chatId,
            automation.download.nextFileType,
            automation.download.nextFromMessageId
        );
        
        // Compute sentinel message date if historySince is provided
        if (automation.download.rule.historySince != null && automation.download.rule.historySince > 0) {
            Optional<TelegramVerticle> verticleOpt = TelegramVerticles.get(automation.telegramId);
            if (verticleOpt.isEmpty()) {
                log.warn("Telegram verticle not found for telegramId %d, skipping history cutoff setup".formatted(automation.telegramId));
                discoverHistoryInternal(params, callback, currentTimeMillis);
                return;
            }
            TelegramVerticle telegramVerticle = verticleOpt.get();
            telegramVerticle.client.execute(
                new TdApi.GetChatMessageByDate(automation.chatId, automation.download.rule.historySince)
            ).onSuccess(sentinelMessage -> {
                if (sentinelMessage != null) {
                    params.sentinelMessageId = sentinelMessage.id;
                    params.sentinelMessageDate = sentinelMessage.date;
                    log.info("History cutoff enabled for chat %d: sentinel message ID = %d, date = %d (%s) (cutoff date: %d (%s))"
                        .formatted(automation.chatId, params.sentinelMessageId, params.sentinelMessageDate,
                            DateUtils.formatTelegramDate(params.sentinelMessageDate),
                            automation.download.rule.historySince, DateUtils.formatTelegramDate(automation.download.rule.historySince)));
                }
                discoverHistoryInternal(params, callback, currentTimeMillis);
            }).onFailure(err -> {
                log.warn("Failed to get sentinel message for history cutoff: %s".formatted(err.getMessage()));
                discoverHistoryInternal(params, callback, currentTimeMillis);
            });
        } else {
            discoverHistoryInternal(params, callback, currentTimeMillis);
        }
    }
    
    /**
     * Overloaded method that accepts DiscoveryParams directly (for comment threads)
     */
    public static void discoverHistory(DiscoveryParams params,
                                       Consumer<DiscoveryResult> callback,
                                       long currentTimeMillis) {
        discoverHistoryInternal(params, callback, currentTimeMillis);
    }
    
    private static void discoverHistoryInternal(DiscoveryParams params,
                                       Consumer<DiscoveryResult> callback,
                                       long currentTimeMillis) {
        String uniqueKey = params.uniqueKey;
        long telegramId = params.telegramId;
        long chatId = params.chatId;
        final long nextFromMessageId = params.nextFromMessageId;
        String nextFileTypeValue = params.nextFileType;
        Tuple2<String, List<String>> rule = handleRule(params.rule);
        
        if (StrUtil.isBlank(nextFileTypeValue)) {
            nextFileTypeValue = rule.v2.getFirst();
        }
        
        final String nextFileType = nextFileTypeValue; // Make final for lambda
        
        log.debug("Start discovery scan! TelegramId: %d ChatId: %d FileType: %s".formatted(telegramId, chatId, nextFileType));
        
        // Check timeout
        if (System.currentTimeMillis() - currentTimeMillis > MAX_HISTORY_SCAN_TIME) {
            log.debug("Discovery scan timeout! TelegramId: %d ChatId: %d".formatted(telegramId, chatId));
            callback.accept(new DiscoveryResult(nextFileType, nextFromMessageId, false));
            return;
        }
        
        Optional<TelegramVerticle> verticleOpt = TelegramVerticles.get(telegramId);
        if (verticleOpt.isEmpty()) {
            log.warn("Telegram verticle not found for telegramId %d, skipping discovery scan".formatted(telegramId));
            callback.accept(new DiscoveryResult(nextFileType, nextFromMessageId, false));
            return;
        }
        TelegramVerticle telegramVerticle = verticleOpt.get();
        TdApi.SearchChatMessages searchChatMessages = new TdApi.SearchChatMessages();
        searchChatMessages.query = rule.v1;
        searchChatMessages.chatId = chatId;
        
        // Handle reverse order (oldest to newest)
        boolean downloadOldestFirst = params.rule != null && params.rule.downloadOldestFirst;
        if (downloadOldestFirst) {
            // When nextFromMessageId is 0, we need to start from a recent message and scan backwards
            // Get the max message ID from database to start scanning backwards from there
            if (nextFromMessageId == 0) {
                // Start from a very large message ID to scan backwards from recent messages
                // The Telegram API will return messages starting from the most recent matching the filter
                searchChatMessages.fromMessageId = 0;  // 0 means start from most recent
                searchChatMessages.offset = 0;  // Start from most recent, then use nextFromMessageId to paginate
            } else {
                searchChatMessages.fromMessageId = nextFromMessageId;
                searchChatMessages.offset = -10;  // Negative offset scans forward (towards older messages)
            }
        } else {
            searchChatMessages.fromMessageId = nextFromMessageId;
            searchChatMessages.offset = 0;
        }
        
        // Ensure limit is always greater than -offset (Telegram API requirement)
        int desiredLimit = MAX_BATCH_SIZE;
        if (searchChatMessages.offset < 0) {
            searchChatMessages.limit = Math.max(desiredLimit, Math.abs(searchChatMessages.offset) + 1);
        } else {
            searchChatMessages.limit = desiredLimit;
        }
        
        searchChatMessages.filter = TdApiHelp.getSearchMessagesFilter(nextFileType);
        searchChatMessages.topicId = params.messageThreadId > 0 ? new TdApi.MessageTopicThread(params.messageThreadId) : null;
        
        telegramVerticle.client.execute(searchChatMessages)
            .onSuccess(foundChatMessages -> {
                if (foundChatMessages == null) {
                    callback.accept(new DiscoveryResult(nextFileType, nextFromMessageId, false));
                    return;
                }
                
                if (foundChatMessages.messages.length == 0) {
                    handleNoMessagesFound(params, rule, nextFileType, downloadOldestFirst, telegramId, chatId, uniqueKey, callback, currentTimeMillis);
                    return;
                }
                
                // Process discovered messages
                processDiscoveredMessages(params, foundChatMessages, nextFileType, nextFromMessageId, telegramId, chatId, uniqueKey, callback, currentTimeMillis);
            })
            .onFailure(err -> {
                log.error("Search chat messages failed! TelegramId: %d ChatId: %d".formatted(telegramId, chatId), err);
                callback.accept(new DiscoveryResult(nextFileType, nextFromMessageId, false));
            });
    }
    
    private static void handleNoMessagesFound(DiscoveryParams params,
                                             Tuple2<String, List<String>> rule,
                                             String nextFileType,
                                             boolean downloadOldestFirst,
                                             long telegramId,
                                             long chatId,
                                             String uniqueKey,
                                             Consumer<DiscoveryResult> callback,
                                             long currentTimeMillis) {
        List<String> fileTypes = rule.v2;
        int nextTypeIndex = fileTypes.indexOf(nextFileType) + 1;
        
        if (nextTypeIndex < fileTypes.size()) {
            // Switch to next file type
            params.nextFileType = fileTypes.get(nextTypeIndex);
            params.nextFromMessageId = downloadOldestFirst ? 1 : 0;
            log.debug("%s No more %s files found! Switch to %s".formatted(uniqueKey, nextFileType, params.nextFileType));
            discoverHistoryInternal(params, callback, currentTimeMillis);
        } else {
            // All file types exhausted - check if we should reset or mark complete
            DataVerticle.fileRepository.getMinMessageId(telegramId, chatId)
                .onSuccess(oldestMsgId -> {
                    if (oldestMsgId != null && oldestMsgId > 0 && params.nextFromMessageId > oldestMsgId) {
                        // Beyond newest message, reset to scan backwards
                        log.info("%s No messages found at nextFromMessageId %d (beyond newest). Resetting to scan backwards from oldest message %d"
                            .formatted(uniqueKey, params.nextFromMessageId, oldestMsgId));
                        params.nextFileType = rule.v2.getFirst();
                        params.nextFromMessageId = Math.max(0, oldestMsgId - 1000);
                        discoverHistoryInternal(params, callback, currentTimeMillis);
                        return;
                    }
                    
                    if (params.nextFromMessageId == 0 && (oldestMsgId == null || oldestMsgId == 0)) {
                        log.debug("%s No files found yet, starting discovery from beginning. TelegramId: %d ChatId: %d"
                            .formatted(uniqueKey, telegramId, chatId));
                        callback.accept(new DiscoveryResult(nextFileType, 0, false));
                        return;
                    }
                    
                    log.debug("%s No more history files found! TelegramId: %d ChatId: %d".formatted(uniqueKey, telegramId, chatId));
                    callback.accept(new DiscoveryResult(nextFileType, params.nextFromMessageId, true));
                })
                .onFailure(err -> {
                    log.error("%s Failed to get min message ID: %s".formatted(uniqueKey, err.getMessage()));
                    callback.accept(new DiscoveryResult(nextFileType, params.nextFromMessageId, true));
                });
        }
    }
    
    private static void processDiscoveredMessages(DiscoveryParams params,
                                                 TdApi.FoundChatMessages foundChatMessages,
                                                 String nextFileType,
                                                 long nextFromMessageId,
                                                 long telegramId,
                                                 long chatId,
                                                 String uniqueKey,
                                                 Consumer<DiscoveryResult> callback,
                                                 long currentTimeMillis) {
        // Check if we've reached the history cutoff date
        // Use historySince (configured cutoff) for stopping, not sentinelMessageDate
        final boolean reachedCutoff;
        Integer cutoffDateForStopping = params.rule != null && params.rule.historySince != null && params.rule.historySince > 0 
            ? params.rule.historySince : params.sentinelMessageDate;
        if (cutoffDateForStopping != null && foundChatMessages.messages.length > 0) {
            int oldestMessageDate = foundChatMessages.messages[foundChatMessages.messages.length - 1].date;
            if (DateUtils.isBeforeCutoff((long) oldestMessageDate, (long) cutoffDateForStopping)) {
                reachedCutoff = true;
                log.info("Reached history cutoff at message date %d (%s) (cutoff date: %d (%s)) for chat %d"
                    .formatted(oldestMessageDate, DateUtils.formatTelegramDate((long) oldestMessageDate),
                        cutoffDateForStopping, DateUtils.formatTelegramDate((long) cutoffDateForStopping), chatId));
            } else {
                reachedCutoff = false;
            }
        } else {
            reachedCutoff = false;
        }
        
        // Check which messages already exist in database
        DataVerticle.fileRepository.getFilesByUniqueId(TdApiHelp.getFileUniqueIds(Arrays.asList(foundChatMessages.messages)))
            .onSuccess(existFiles -> {
                // Filter messages: new ones or existing ones with idle status
                // Use historySince (configured cutoff date) for filtering, not sentinelMessageDate
                final Integer cutoffDateForFiltering = params.rule != null ? params.rule.historySince : params.sentinelMessageDate;
                final List<TdApi.Message> messagesToProcess = Stream.of(foundChatMessages.messages)
                    .filter(message -> {
                        if (cutoffDateForFiltering != null && cutoffDateForFiltering > 0 && DateUtils.isBeforeCutoff((long) message.date, (long) cutoffDateForFiltering)) {
                            return false;
                        }
                        
                        String uniqueId = TdApiHelp.getFileUniqueId(message);
                        if (!existFiles.containsKey(uniqueId)) {
                            return true;  // New file, needs to be inserted
                        } else {
                            FileRecord fileRecord = existFiles.get(uniqueId);
                            // Update if status is not idle (e.g., if it was error, we want to retry)
                            return !fileRecord.isDownloadStatus(FileRecord.DownloadStatus.idle);
                        }
                    })
                    .toList();
                
                if (CollUtil.isEmpty(messagesToProcess)) {
                    // All messages already exist and are idle - continue scanning
                    if (reachedCutoff) {
                        log.info("History discovery complete due to cutoff date for chat %d. Queueing existing idle files for download.".formatted(chatId));
                        // Queue existing idle files when discovery completes
                        // Use historySince (configured cutoff date) for filtering, not sentinelMessageDate
                        Integer cutoffForQueueing = params.rule != null && params.rule.historySince != null && params.rule.historySince > 0 
                            ? params.rule.historySince : params.sentinelMessageDate;
                        DownloadQueueService.queueFilesForDownload(telegramId, chatId, MAX_IDLE_FILES_TO_QUEUE, cutoffForQueueing, params.rule != null ? params.rule.downloadOldestFirst : null)
                            .onSuccess(queued -> {
                                if (queued > 0) {
                                    log.info("Queued %d existing idle files for download after discovery complete. ChatId: %d".formatted(queued, chatId));
                                }
                                callback.accept(new DiscoveryResult(nextFileType, nextFromMessageId, true));
                            })
                            .onFailure(err -> {
                                log.error("Failed to queue existing idle files after discovery complete: %s".formatted(err.getMessage()));
                                callback.accept(new DiscoveryResult(nextFileType, nextFromMessageId, true));
                            });
                    } else {
                        params.nextFromMessageId = foundChatMessages.nextFromMessageId;
                        discoverHistoryInternal(params, callback, currentTimeMillis);
                    }
                    return;
                }
                
                // Persist discovered files to database, then queue them for download
                persistDiscoveredFiles(telegramId, messagesToProcess, uniqueKey)
                    .compose(count -> {
                        log.info("%s Discovered and persisted %d files to database".formatted(uniqueKey, count));
                        
                        if (count > 0 && !CollUtil.isEmpty(messagesToProcess)) {
                            // Use historySince (configured cutoff date) for filtering, not sentinelMessageDate
                            Integer cutoffForQueueing = params.rule != null && params.rule.historySince != null && params.rule.historySince > 0 
                                ? params.rule.historySince : params.sentinelMessageDate;
                            return DownloadQueueService.queueFilesForDownload(telegramId, chatId, messagesToProcess.size(), cutoffForQueueing, params.rule != null ? params.rule.downloadOldestFirst : null)
                                .map(queued -> {
                                    log.info("%s Queued %d of %d files for download".formatted(uniqueKey, queued, messagesToProcess.size()));
                                    return count;
                                });
                        }
                        return Future.succeededFuture(count);
                    })
                    .onSuccess(count -> {
                        if (reachedCutoff) {
                            log.info("History discovery complete due to cutoff date for chat %d".formatted(chatId));
                            callback.accept(new DiscoveryResult(nextFileType, nextFromMessageId, true));
                        } else {
                            params.nextFromMessageId = foundChatMessages.nextFromMessageId;
                            discoverHistoryInternal(params, callback, currentTimeMillis);
                        }
                    })
                    .onFailure(err -> {
                        log.error("%s Failed to persist/queue discovered files: %s".formatted(uniqueKey, err.getMessage()));
                        callback.accept(new DiscoveryResult(nextFileType, nextFromMessageId, false));
                    });
            })
            .onFailure(err -> {
                log.error("%s Failed to check existing files: %s".formatted(uniqueKey, err.getMessage()));
                callback.accept(new DiscoveryResult(nextFileType, nextFromMessageId, false));
            });
    }
    
    /**
     * Persist discovered files to database with scan_state='idle'.
     * Files are inserted or updated, but NOT queued for download.
     */
    private static Future<Integer> persistDiscoveredFiles(long telegramId, List<TdApi.Message> messages, String uniqueKey) {
        java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(0);
        
        return Future.all(messages.stream()
            .map(message -> {
                // Convert message to FileRecord using TdApiHelp
                Optional<TdApiHelp.FileHandler<?>> handlerOpt = TdApiHelp.getFileHandler(message);
                if (handlerOpt.isEmpty()) {
                    return Future.succeededFuture();
                }
                
                FileRecord fileRecord = handlerOpt.get().convertFileRecord(telegramId);
                if (fileRecord == null) {
                    log.debug("%s Failed to convert message to FileRecord (messageId: %d)".formatted(
                        uniqueKey, message.id));
                    return Future.succeededFuture();
                }
                
                return DataVerticle.fileRepository.createIfNotExist(fileRecord)
                    .compose(created -> {
                        if (created) {
                            count.incrementAndGet();
                            log.trace("%s Created file record: %s".formatted(uniqueKey, fileRecord.fileName()));
                            return Future.succeededFuture();
                        } else {
                            // File exists - check if we need to update it
                            return DataVerticle.fileRepository.getByUniqueId(fileRecord.uniqueId())
                                .compose(existing -> {
                                    if (existing == null) {
                                        return Future.succeededFuture();
                                    }
                                    
                                    FileRecord.DownloadStatus currentStatus;
                                    try {
                                        currentStatus = FileRecord.DownloadStatus.valueOf(existing.downloadStatus());
                                    } catch (IllegalArgumentException e) {
                                        log.warn("%s Invalid download status '%s' for file %s, treating as idle".formatted(
                                            uniqueKey, existing.downloadStatus(), existing.fileName()));
                                        currentStatus = FileRecord.DownloadStatus.idle;
                                    }
                                    if (currentStatus != FileRecord.DownloadStatus.idle && currentStatus != FileRecord.DownloadStatus.completed) {
                                        // Reset non-idle/non-completed files to idle for retry
                                        return DataVerticle.fileRepository.updateDownloadStatus(
                                            existing.id(),
                                            existing.uniqueId(),
                                            null,
                                            FileRecord.DownloadStatus.idle,
                                            null
                                        ).onSuccess(v -> {
                                            count.incrementAndGet();
                                            log.trace("%s Reset file record to idle: %s".formatted(uniqueKey, fileRecord.fileName()));
                                        });
                                    }
                                    return Future.succeededFuture();
                                });
                        }
                    });
            })
            .toList())
            .map(count.get());
    }
    
    private static Tuple2<String, List<String>> handleRule(SettingAutoRecords.DownloadRule rule) {
        String query = null;
        List<String> fileTypes = DEFAULT_FILE_TYPE_ORDER;
        if (rule != null) {
            if (StrUtil.isNotBlank(rule.query)) {
                query = rule.query;
            }
            if (CollUtil.isNotEmpty(rule.fileTypes)) {
                fileTypes = rule.fileTypes;
            }
        }
        return new Tuple2<>(query, fileTypes);
    }
    
    /**
     * Parameters for discovery scan
     */
    public static class DiscoveryParams {
        public final String uniqueKey;
        public final SettingAutoRecords.DownloadRule rule;
        public final long telegramId;
        public final long chatId;
        public String nextFileType;
        public long nextFromMessageId;
        public Long sentinelMessageId;
        public Integer sentinelMessageDate;
        public long messageThreadId;
        
        public DiscoveryParams(String uniqueKey,
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
            this.messageThreadId = 0;
        }
    }
    
    /**
     * Result of discovery scan
     */
    public static class DiscoveryResult {
        public final String nextFileType;
        public final long nextFromMessageId;
        public final boolean isComplete;
        
        public DiscoveryResult(String nextFileType, long nextFromMessageId, boolean isComplete) {
            this.nextFileType = nextFileType;
            this.nextFromMessageId = nextFromMessageId;
            this.isComplete = isComplete;
        }
    }
}

