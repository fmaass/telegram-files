package telegram.files;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import telegram.files.repository.FileRecord;

import java.util.List;

/**
 * Service for querying database for files ready to download and managing the download queue.
 * 
 * Key Features:
 * - Queries database for files with download_status='idle' and scan_state='idle'
 * - Orders by download_priority DESC, queued_at ASC
 * - Marks files as queued (sets queued_at timestamp)
 * - Respects per-telegram account limits
 */
public class DownloadQueueService {
    
    private static final Log log = LogFactory.get();
    
    /**
     * Get files ready for download from the database.
     * Files are ordered by priority (higher first) and queued_at (older first).
     * 
     * @param telegramId Telegram account ID
     * @param limit Maximum number of files to return
     * @param cutoffDateSeconds Optional cutoff date in seconds. Only return files with date >= cutoffDate.
     * @param downloadOldestFirst If true, order by message_id ASC (oldest first), else DESC (newest first).
     * @return List of FileRecord ready for download
     */
    public static Future<List<FileRecord>> getFilesReadyForDownload(long telegramId, int limit, Integer cutoffDateSeconds, Boolean downloadOldestFirst) {
        return DataVerticle.fileRepository.getFilesReadyForDownload(telegramId, limit, cutoffDateSeconds, downloadOldestFirst);
    }
    
    /**
     * Mark files as queued by setting their queued_at timestamp.
     * This is called when files are discovered and ready to be queued.
     * 
     * @param telegramId Telegram account ID
     * @param chatId Chat ID (optional, 0 for all chats)
     * @param limit Maximum number of files to queue
     * @param cutoffDateSeconds Optional cutoff date in seconds (Telegram API format). Only queue files with date >= cutoffDate.
     * @param downloadOldestFirst If true, order by message_id ASC (oldest first), else DESC (newest first). If null, defaults to true.
     * @return Number of files queued
     */
    public static Future<Integer> queueFilesForDownload(long telegramId, long chatId, int limit, Integer cutoffDateSeconds, Boolean downloadOldestFirst) {
        return DataVerticle.fileRepository.queueFilesForDownload(telegramId, chatId, limit, cutoffDateSeconds, downloadOldestFirst != null ? downloadOldestFirst : true);
    }
    
    /**
     * Get count of files currently downloading for a telegram account.
     * Used to determine how many more files can be queued.
     * 
     * @param telegramId Telegram account ID
     * @return Count of files with download_status='downloading'
     */
    public static Future<Integer> getDownloadingCount(long telegramId) {
        return DataVerticle.fileRepository.countByStatus(telegramId, FileRecord.DownloadStatus.downloading);
    }
    
    /**
     * Get files ready for download, respecting the limit of concurrent downloads.
     * 
     * @param telegramId Telegram account ID
     * @param maxConcurrent Maximum concurrent downloads allowed
     * @return List of FileRecord ready for download
     */
    public static Future<List<FileRecord>> getFilesForDownload(long telegramId, int maxConcurrent) {
        return getDownloadingCount(telegramId)
            .compose(downloadingCount -> {
                int surplus = Math.max(0, maxConcurrent - downloadingCount);
                if (surplus <= 0) {
                    log.debug("No surplus download slots available for telegramId %d (downloading: %d, max: %d)"
                        .formatted(telegramId, downloadingCount, maxConcurrent));
                    return Future.succeededFuture(List.of());
                }
                
                // Get files ready for download (already queued or need to be queued)
                // Note: cutoffDateSeconds and downloadOldestFirst will be determined by the repository
                // based on automation settings
                return getFilesReadyForDownload(telegramId, surplus, null, null);
            });
    }
}

