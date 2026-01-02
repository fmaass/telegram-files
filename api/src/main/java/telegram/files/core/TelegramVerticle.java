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

    public TelegramClient client;

    private TelegramChats telegramChats;

    public boolean authorized = false;

    public TdApi.AuthorizationState lastAuthorizationState;

    public String rootPath;

    private String proxyName;

    private String rootId;

    private boolean needDelete = false;

    public TelegramRecord telegramRecord;

    private AvgSpeed avgSpeed = new AvgSpeed();

    private long avgSpeedPersistenceTimerId;

    private long lastFileEventTime;

    private long lastFileDownloadEventTime;

    public TelegramVerticle(String rootPath) {
        this.rootPath = rootPath;
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

        client.initialize(telegramUpdateHandler, this::handleException, this::handleException);
        Future.all(initEventConsumer(), initAvgSpeed())
                .compose(_ -> this.enableProxy(this.proxyName))
                .onSuccess(_ -> startPromise.complete())
                .onFailure(startPromise::fail);
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        this.close(false)
                .onComplete(stopPromise);
    }

    public Future<Void> close(boolean needDelete) {
        return client.execute(new TdApi.Close())
                .onSuccess(_ -> {
                    log.info("[%s] Telegram account closed".formatted(this.getRootId()));
                    this.needDelete = needDelete;
                })
                .onFailure(e -> log.error("[%s] Failed to close telegram account: %s".formatted(this.getRootId(), e.getMessage())))
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
                    .compose(t -> TelegramConverter.convertFiles(this.telegramRecord.id(), t));
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

    public Future<FileRecord> startDownload(Long chatId, Long messageId, Integer fileId) {
        return ErrorHandling.critical(
                Future.all(
                        client.execute(new TdApi.GetFile(fileId)),
                        client.execute(new TdApi.GetMessage(chatId, messageId)),
                        client.execute(new TdApi.GetMessageThread(chatId, messageId), true)
                ),
                String.format("Start download for file %d in chat %d", fileId, chatId)
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
                                    .compose(_ -> DataVerticle.fileRepository.getByUniqueId(file.remote.uniqueId));
                        }
                        if (file.local.isDownloadingActive) {
                            return Future.failedFuture("File is downloading");
                        }
//                        return Future.failedFuture("Unknown file download status");
                    }
                    if (dbFileRecord != null && !dbFileRecord.isDownloadStatus(FileRecord.DownloadStatus.idle)) {
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
                                // Don't start if already downloading or completed
                                if (record.isDownloadStatus(FileRecord.DownloadStatus.downloading) ||
                                    record.isDownloadStatus(FileRecord.DownloadStatus.completed)) {
                                    return Future.succeededFuture(record);
                                }
                                
                                // Update status to downloading before starting (if it was idle)
                                Future<FileRecord> statusUpdateFuture;
                                if (record.isDownloadStatus(FileRecord.DownloadStatus.idle)) {
                                    statusUpdateFuture = DataVerticle.fileRepository.updateDownloadStatus(
                                            record.id(),
                                            record.uniqueId(),
                                            null,
                                            FileRecord.DownloadStatus.downloading,
                                            null
                                    ).map(ignore -> record);
                                } else {
                                    statusUpdateFuture = Future.succeededFuture(record);
                                }
                                
                                // Start the download
                                return statusUpdateFuture
                                        .compose(updatedRecord -> client.execute(new TdApi.AddFileToDownloads(fileId, chatId, messageId, 32))
                                                .onSuccess(ignore -> {
                                                    sendEvent(EventPayload.build(EventPayload.TYPE_FILE_STATUS, new JsonObject()
                                                            .put("fileId", fileId)
                                                            .put("uniqueId", updatedRecord.uniqueId())
                                                            .put("downloadStatus", FileRecord.DownloadStatus.downloading)
                                                    ));

                                                    downloadThumbnail(chatId, messageId, fileHandler.convertThumbnailRecord(telegramRecord.id()));
                                                })
                                                .map(ignore -> updatedRecord));
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
        log.trace("[%s] Execute code: %s method: %s, params: %s".formatted(getRootId(), code, method, params));
        return Future.future(promise -> {
            TdApi.Function<?> func = TdApiHelp.getFunction(method, params);
            if (func == null) {
                promise.fail("Unsupported method: " + method);
                return;
            }
            client.getNativeClient().send(func, object -> {
                log.debug("[%s] Execute: [%s] Receive result: %s".formatted(getRootId(), code, object));
                handleDefaultResult(object, code);
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
    
    private boolean isTrackDownloadedStateEnabled() {
        Boolean setting = Future.await(DataVerticle.settingRepository.<Boolean>getByKey(SettingKey.trackDownloadedState));
        return setting != null && setting;
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
        DataVerticle.statisticRepository.create(new StatisticRecord(Convert.toStr(telegramRecord.id()),
                StatisticRecord.Type.speed,
                System.currentTimeMillis(),
                data.encode()));

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
                sendEvent(EventPayload.build(EventPayload.TYPE_AUTHORIZATION, authorizationState));
                break;
            case TdApi.AuthorizationStateReady.CONSTRUCTOR:
                authorized = true;
                if (telegramRecord == null) {
                    client.execute(new TdApi.GetMe())
                            .compose(user ->
                                    DataVerticle.telegramRepository.create(new TelegramRecord(user.id, user.firstName, this.rootPath, this.proxyName))
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
                // Sync download status for files marked as completed in database
                syncCompletedFilesStatus();
                break;
            case TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR:
                break;
            case TdApi.AuthorizationStateClosing.CONSTRUCTOR:
                break;
            case TdApi.AuthorizationStateClosed.CONSTRUCTOR:
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
        log.trace("📃[%s] Receive file update: %s".formatted(getRootId(), updateFile));
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
            DataVerticle.fileRepository.getByUniqueId(file.remote.uniqueId)
                    .onSuccess(fileRecord -> {
                        FileRecord.DownloadStatus downloadStatus = TdApiHelp.getDownloadStatus(file);

                        if (fileRecord != null) {
                            if (fileRecord.isDownloadStatus(FileRecord.DownloadStatus.completed) &&
                                fileRecord.isTransferStatus(FileRecord.TransferStatus.completed) &&
                                FileUtil.exist(fileRecord.localPath())) {
                                return;
                            }
                            if (downloadStatus == null) {
                                downloadStatus = FileRecord.DownloadStatus.idle;
                            }
                            // Determine final status based on trackDownloadedState setting
                            FileRecord.DownloadStatus finalStatus = downloadStatus;
                            if (downloadStatus == FileRecord.DownloadStatus.completed && 
                                finalLocalPath != null && 
                                isTrackDownloadedStateEnabled()) {
                                // When setting is enabled and file exists, use "downloaded" status
                                finalStatus = FileRecord.DownloadStatus.downloaded;
                            }
                            
                            DataVerticle.fileRepository.updateDownloadStatus(file.id,
                                            file.remote.uniqueId,
                                            finalLocalPath,
                                            finalStatus,
                                            finalCompletionDate)
                                    .onSuccess(r -> {
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
                                    });
                        }
                    });

            if (completionDate != null || lastFileEventTime == 0 || System.currentTimeMillis() - lastFileEventTime > 1000) {
                sendEvent(EventPayload.build(EventPayload.TYPE_FILE, updateFile));
                lastFileEventTime = System.currentTimeMillis();
            }
        }
    }

    private void onFileDownloadsUpdated(TdApi.UpdateFileDownloads updateFileDownloads) {
        log.trace("[%s] Receive file downloads update: %s".formatted(getRootId(), updateFileDownloads));
        avgSpeed.update(updateFileDownloads.downloadedSize, System.currentTimeMillis());
        if (lastFileDownloadEventTime == 0 || System.currentTimeMillis() - lastFileDownloadEventTime > 1000) {
            sendEvent(EventPayload.build(EventPayload.TYPE_FILE_DOWNLOAD, updateFileDownloads));
            lastFileDownloadEventTime = System.currentTimeMillis();
        }
    }

    private void onMessageReceived(TdApi.Message message) {
        log.trace("[%s] Receive message: %s".formatted(getRootId(), message));
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
                        // Use "downloaded" if setting enabled, otherwise "completed"
                        FileRecord.DownloadStatus finalStatus = isTrackDownloadedStateEnabled() 
                            ? FileRecord.DownloadStatus.downloaded 
                            : FileRecord.DownloadStatus.completed;
                        
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
    
    private void syncCompletedFilesStatus() {
        if (telegramRecord == null) {
            return;
        }
        
        log.info("[%s] Starting sync of completed files status...".formatted(getRootId()));
        
        // Get completed files in batches to avoid loading too many at once
        Map<String, String> filter = new HashMap<>();
        filter.put("downloadStatus", FileRecord.DownloadStatus.completed.name());
        filter.put("limit", "100"); // Process in batches of 100
        
        DataVerticle.fileRepository.getFiles(0, filter)
                .onSuccess(result -> {
                    List<FileRecord> completedFiles = result.v1();
                    if (completedFiles.isEmpty()) {
                        log.debug("[%s] No completed files to sync".formatted(getRootId()));
                        return;
                    }
                    
                    log.info("[%s] Syncing %d completed files...".formatted(getRootId(), completedFiles.size()));
                    
                    // Use AtomicInteger for thread-safe counting in async callbacks
                    java.util.concurrent.atomic.AtomicInteger synced = new java.util.concurrent.atomic.AtomicInteger(0);
                    java.util.concurrent.atomic.AtomicInteger notFound = new java.util.concurrent.atomic.AtomicInteger(0);
                    java.util.concurrent.atomic.AtomicInteger processed = new java.util.concurrent.atomic.AtomicInteger(0);
                    
                    for (FileRecord fileRecord : completedFiles) {
                        // Skip if file doesn't belong to this telegram account
                        if (fileRecord.telegramId() != telegramRecord.id()) {
                            processed.incrementAndGet();
                            continue;
                        }
                        
                        // Check if file exists on disk
                        if (StrUtil.isBlank(fileRecord.localPath()) || !FileUtil.exist(fileRecord.localPath())) {
                            // File marked as completed but doesn't exist
                            // Preserve "completed" status for files that were downloaded in the past
                            // (user may have moved/deleted the file, but we want to preserve the "Downloaded" status)
                            Long completionDate = fileRecord.completionDate();
                            if (completionDate == null || completionDate == 0) {
                                // File doesn't have completionDate - set one based on file date or use a reasonable default
                                // Use the file's date (when it was sent) as a proxy for when it was downloaded
                                // This ensures older downloads without completionDate still show as "Downloaded"
                                if (fileRecord.date() > 0) {
                                    // Use file date converted to milliseconds (file.date is in seconds)
                                    completionDate = fileRecord.date() * 1000L;
                                } else {
                                    // Fallback: use a timestamp from the past (e.g., 1 year ago)
                                    // This ensures it shows as "Downloaded" (before current session)
                                    completionDate = System.currentTimeMillis() - (365L * 24 * 60 * 60 * 1000);
                                }
                                // Update the record to set completionDate so it persists
                                DataVerticle.fileRepository.updateDownloadStatus(
                                        fileRecord.id(),
                                        fileRecord.uniqueId(),
                                        fileRecord.localPath(), // Keep existing path even though file is gone
                                        FileRecord.DownloadStatus.completed,
                                        completionDate
                                ).onSuccess(r -> {
                                    log.debug("[%s] Set completionDate for deleted file (preserving 'Downloaded' status): %s"
                                            .formatted(getRootId(), fileRecord.uniqueId()));
                                    synced.incrementAndGet();
                                    checkSyncComplete(processed.incrementAndGet(), completedFiles.size(), synced.get(), notFound.get());
                                })
                                .onFailure(e -> {
                                    log.debug("[%s] Failed to set completionDate for deleted file: %s"
                                            .formatted(getRootId(), e.getMessage()));
                                    synced.incrementAndGet();
                                    checkSyncComplete(processed.incrementAndGet(), completedFiles.size(), synced.get(), notFound.get());
                                });
                            } else {
                                // File already has completionDate - keep status as completed
                                log.debug("[%s] File was downloaded in the past but not found on disk (likely moved/deleted) - keeping as completed: %s"
                                        .formatted(getRootId(), fileRecord.uniqueId()));
                                synced.incrementAndGet();
                                checkSyncComplete(processed.incrementAndGet(), completedFiles.size(), synced.get(), notFound.get());
                            }
                            continue;
                        }
                        
                        // File exists - verify with Telegram client
                        // Query the file to sync its status
                        client.execute(new TdApi.GetFile(fileRecord.id()))
                                .onSuccess(file -> {
                                    if (file != null && file.local != null && file.local.isDownloadingCompleted) {
                                        // File is actually completed - sync status
                                        syncFileDownloadStatus(file, null, null)
                                                .onSuccess(r -> {
                                                    synced.incrementAndGet();
                                                    checkSyncComplete(processed.incrementAndGet(), completedFiles.size(), synced.get(), notFound.get());
                                                })
                                                .onFailure(e -> {
                                                    log.debug("[%s] Failed to sync file status: %s"
                                                            .formatted(getRootId(), fileRecord.uniqueId()));
                                                    checkSyncComplete(processed.incrementAndGet(), completedFiles.size(), synced.get(), notFound.get());
                                                });
                                    } else {
                                        // File not completed in Telegram cache, but exists on disk
                                        // Keep it as completed since the file is actually there
                                        log.debug("[%s] File exists on disk but not in Telegram cache - keeping as completed: %s"
                                                .formatted(getRootId(), fileRecord.uniqueId()));
                                        // Ensure completionDate is set if missing
                                        Long completionDate = fileRecord.completionDate();
                                        if (completionDate == null || completionDate == 0) {
                                            // Set completionDate to file modification time or current time
                                            try {
                                                Path filePath = Path.of(fileRecord.localPath());
                                                if (Files.exists(filePath)) {
                                                    completionDate = Files.getLastModifiedTime(filePath).toMillis();
                                                } else {
                                                    completionDate = System.currentTimeMillis();
                                                }
                                                DataVerticle.fileRepository.updateDownloadStatus(
                                                    fileRecord.id(),
                                                    fileRecord.uniqueId(),
                                                    fileRecord.localPath(),
                                                    FileRecord.DownloadStatus.completed,
                                                    completionDate
                                                );
                                            } catch (Exception ex) {
                                                log.debug("[%s] Failed to set completionDate: %s"
                                                        .formatted(getRootId(), ex.getMessage()));
                                            }
                                        }
                                        synced.incrementAndGet();
                                        checkSyncComplete(processed.incrementAndGet(), completedFiles.size(), synced.get(), notFound.get());
                                    }
                                })
                                .onFailure(e -> {
                                    // File might not be accessible from Telegram, but exists on disk
                                    // Keep it as completed since the file is actually there
                                    log.debug("[%s] Could not query file from Telegram (file exists on disk) - keeping as completed: %s"
                                            .formatted(getRootId(), fileRecord.uniqueId()));
                                    // Ensure completionDate is set if missing
                                    Long completionDate = fileRecord.completionDate();
                                    if (completionDate == null || completionDate == 0) {
                                        try {
                                            Path filePath = Path.of(fileRecord.localPath());
                                            if (Files.exists(filePath)) {
                                                completionDate = Files.getLastModifiedTime(filePath).toMillis();
                                            } else {
                                                completionDate = System.currentTimeMillis();
                                            }
                                            DataVerticle.fileRepository.updateDownloadStatus(
                                                fileRecord.id(),
                                                fileRecord.uniqueId(),
                                                fileRecord.localPath(),
                                                FileRecord.DownloadStatus.completed,
                                                completionDate
                                            );
                                        } catch (Exception ex) {
                                            log.debug("[%s] Failed to set completionDate: %s"
                                                    .formatted(getRootId(), ex.getMessage()));
                                        }
                                    }
                                    synced.incrementAndGet();
                                    checkSyncComplete(processed.incrementAndGet(), completedFiles.size(), synced.get(), notFound.get());
                                });
                    }
                })
                .onFailure(e -> log.error("[%s] Failed to sync completed files status: %s"
                        .formatted(getRootId(), e.getMessage())));
    }
    
    private void checkSyncComplete(int processed, int total, int synced, int notFound) {
        if (processed >= total) {
            log.info("[%s] Completed files sync finished. Synced: %d, Not found: %d"
                    .formatted(getRootId(), synced, notFound));
        }
    }
}
