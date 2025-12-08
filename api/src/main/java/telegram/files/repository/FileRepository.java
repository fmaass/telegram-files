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

    Future<JsonObject> countWithType(long telegramId, long chatId);

    Future<JsonObject> updateDownloadStatus(int fileId,
                                            String uniqueId,
                                            String localPath,
                                            FileRecord.DownloadStatus downloadStatus,
                                            Long completionDate);

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
     * @param limit Maximum number of files to return
     * @return List of FileRecord ready for download
     */
    Future<List<FileRecord>> getFilesReadyForDownload(long telegramId, int limit, Integer cutoffDateSeconds, Boolean downloadOldestFirst);
    
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
}
