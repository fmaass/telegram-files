package telegram.files.download;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import io.vertx.core.VertxException;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import org.drinkless.tdlib.TdApi;
import org.jooq.lambda.tuple.Tuple;
import telegram.files.ServiceContext;
import telegram.files.TdApiHelp;
import telegram.files.FileRecordRetriever;
import telegram.files.core.EventPayload;
import telegram.files.core.EventEnum;
import telegram.files.core.TelegramClient;
import telegram.files.repository.FileRecord;
import telegram.files.repository.SettingKey;
import telegram.files.util.ErrorHandling;
import telegram.files.util.DateUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

/**
 * Service responsible for file download operations.
 * Extracted from TelegramVerticle to follow Single Responsibility Principle.
 */
public class FileDownloadService {
    
    private static final Log log = LogFactory.get();
    
    private final TelegramClient client;
    private final ServiceContext context;
    private final long telegramId;
    private final Vertx vertx;
    private final String rootId;
    
    // Cache for trackDownloadedState setting
    private volatile Boolean trackDownloadedStateCache = null;
    private volatile long trackDownloadedStateCacheTime = 0;
    private static final long CACHE_TTL_MS = 60000;
    
    public FileDownloadService(
        TelegramClient client,
        ServiceContext context,
        long telegramId,
        Vertx vertx,
        String rootId
    ) {
        this.client = client;
        this.context = context;
        this.telegramId = telegramId;
        this.vertx = vertx;
        this.rootId = rootId;
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
                    return context.fileRepository().getByUniqueId(file.remote.uniqueId)
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
                                    .compose(_ -> context.fileRepository().getByUniqueId(file.remote.uniqueId));
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
                    FileRecord fileRecord = fileHandler.convertFileRecord(telegramId).withThreadInfo(messageThreadInfo);
                    return context.fileRepository().createIfNotExist(fileRecord)
                            .compose(created -> {
                                if (!created) {
                                    // FileRecord already exists, get it and update file ID if needed
                                    return context.fileRepository().getByUniqueId(fileRecord.uniqueId())
                                            .compose(existingRecord -> {
                                                if (existingRecord == null) {
                                                    return Future.succeededFuture(fileRecord);
                                                }
                                                // Update file ID if needed
                                                return context.fileRepository().updateFileId(fileRecord.id(), fileRecord.uniqueId())
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
                                    statusUpdateFuture = context.fileRepository().updateDownloadStatus(
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

                                                    downloadThumbnail(chatId, messageId, fileHandler.convertThumbnailRecord(telegramId));
                                                })
                                                .map(ignore -> updatedRecord));
                            });
                });
    }
    
    /**
     * Send event to event bus.
     */
    private void sendEvent(EventPayload payload) {
        vertx.eventBus().publish(EventEnum.TELEGRAM_EVENT.address(),
                JsonObject.of("telegramId", this.telegramId, "payload", JsonObject.mapFrom(payload)));
    }
    
    public Future<Void> cancelDownload(Integer fileId) {
        return client.execute(new TdApi.GetFile(fileId))
                .compose(file -> context.fileRepository()
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
                .compose(file -> context.fileRepository().deleteByUniqueId(file.remote.uniqueId).map(file))
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
                .compose(file -> context.fileRepository()
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
                        return context.fileRepository().getByUniqueId(file.remote.uniqueId)
                                .compose(fileRecord ->
                                        client.execute(new TdApi.AddFileToDownloads(fileId, fileRecord.chatId(), fileRecord.messageId(), 32)))
                                .mapEmpty();
                    }

                    return client.execute(new TdApi.ToggleDownloadIsPaused(fileId, isPaused));
                })
                .mapEmpty();
    }
    
    public Future<Boolean> downloadThumbnail(Long chatId, Long messageId, FileRecord thumbnailRecord) {
        if (thumbnailRecord == null) {
            return Future.succeededFuture(false);
        }
        return context.fileRepository().createIfNotExist(thumbnailRecord)
                .compose(created -> {
                    if (!created) {
                        return context.fileRepository().updateFileId(thumbnailRecord.id(), thumbnailRecord.uniqueId());
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
                        log.debug("[%s] Download thumbnail: %s".formatted(this.rootId, thumbnailRecord.uniqueId()));
                    }
                });
    }
    
    public Future<Void> syncFileDownloadStatus(TdApi.File file, TdApi.Message message, TdApi.MessageThreadInfo messageThreadInfo) {
        return context.fileRepository()
                .getByUniqueId(file.remote.uniqueId)
                .compose(fileRecord -> {
                    if (fileRecord != null) {
                        FileRecord finalFileRecord = fileRecord;
                        // Use "downloaded" if setting enabled, otherwise "completed" (async)
                        return isTrackDownloadedStateEnabled().compose(trackEnabled -> {
                            FileRecord.DownloadStatus finalStatus = trackEnabled 
                                ? FileRecord.DownloadStatus.downloaded 
                                : FileRecord.DownloadStatus.completed;
                            
                            return context.fileRepository().updateDownloadStatus(
                                    file.id,
                                    file.remote.uniqueId,
                                    file.local.path,
                                    finalStatus,
                                    System.currentTimeMillis()
                            );
                        }).onSuccess(r -> {
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
                            .convertFileRecord(telegramId)
                            .withThreadInfo(messageThreadInfo);

                    return context.fileRepository().create(newFileRecord)
                            .compose(r -> context.fileRepository().updateDownloadStatus(
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
    
    private Future<Boolean> isTrackDownloadedStateEnabled() {
        // Check cache first
        long now = System.currentTimeMillis();
        if (trackDownloadedStateCache != null && (now - trackDownloadedStateCacheTime) < CACHE_TTL_MS) {
            return Future.succeededFuture(trackDownloadedStateCache);
        }
        
        // Cache miss - fetch from DB (non-blocking)
        return context.settingRepository().<Boolean>getByKey(SettingKey.trackDownloadedState)
                .map(setting -> {
                    trackDownloadedStateCache = setting != null && setting;
                    trackDownloadedStateCacheTime = now;
                    return trackDownloadedStateCache;
                })
                .recover(err -> {
                    log.warn("Failed to fetch trackDownloadedState setting: {}", err.getMessage());
                    trackDownloadedStateCache = false;
                    trackDownloadedStateCacheTime = now;
                    return Future.succeededFuture(false);
                });
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
            context.fileRepository().getByUniqueId(file.remote.uniqueId)
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
}
