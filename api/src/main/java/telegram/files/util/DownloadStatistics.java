package telegram.files.util;

import io.vertx.core.json.JsonObject;

/**
 * Domain object representing download statistics for a chat or account.
 * Provides type-safe access to download counts by status.
 */
public record DownloadStatistics(
    int total,
    int downloading,
    int paused,
    int completed,
    int error,
    int idle
) {
    
    /**
     * Calculate number of pending files (not yet completed).
     */
    public int pending() {
        return idle + downloading + paused;
    }
    
    /**
     * Calculate progress percentage.
     * @return Progress as integer 0-100
     */
    public int progressPercent() {
        if (total == 0) return 0;
        return (int) Math.round((completed * 100.0) / total);
    }
    
    /**
     * Check if all files are completed.
     */
    public boolean isComplete() {
        return pending() == 0 && total > 0;
    }
    
    /**
     * Convert to JSON for API response.
     */
    public JsonObject toJson() {
        return JsonObject.of(
            "total", total,
            "downloading", downloading,
            "paused", paused,
            "completed", completed,
            "error", error,
            "idle", idle
        );
    }
    
    /**
     * Create from JSON (e.g., from API response).
     */
    public static DownloadStatistics fromJson(JsonObject json) {
        return new DownloadStatistics(
            json.getInteger("total", 0),
            json.getInteger("downloading", 0),
            json.getInteger("paused", 0),
            json.getInteger("completed", 0),
            json.getInteger("error", 0),
            json.getInteger("idle", 0)
        );
    }
    
    /**
     * Create from database row (safe with null handling).
     */
    public static DownloadStatistics fromRow(
        Integer total,
        Integer downloading,
        Integer paused,
        Integer completed,
        Integer error,
        Integer idle
    ) {
        return new DownloadStatistics(
            total != null ? total : 0,
            downloading != null ? downloading : 0,
            paused != null ? paused : 0,
            completed != null ? completed : 0,
            error != null ? error : 0,
            idle != null ? idle : 0
        );
    }
}

