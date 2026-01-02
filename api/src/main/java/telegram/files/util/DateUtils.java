package telegram.files.util;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;

/**
 * Centralizes date handling and provides conversion utilities.
 * 
 * Date Standards:
 * - Telegram API dates: seconds since epoch (int)
 * - Database date field: seconds since epoch (int) - stored as-is from Telegram
 * - Database timestamp fields (startDate, completionDate, queuedAt): milliseconds since epoch (long)
 * - Cutoff dates (historySince): seconds since epoch (int) - matches Telegram API format
 * 
 * Key Principle: When comparing dates, ensure both are in the same unit (seconds or milliseconds).
 */
public class DateUtils {
    
    private static final Log log = LogFactory.get();
    
    /**
     * Convert Telegram API date (seconds) to database timestamp (milliseconds).
     * Used for storing timestamps like startDate, completionDate, queuedAt.
     * 
     * @param telegramSeconds Telegram API date in seconds since epoch
     * @return Database timestamp in milliseconds since epoch
     */
    public static long telegramSecondsToDbMillis(int telegramSeconds) {
        return (long) telegramSeconds * 1000L;
    }
    
    /**
     * Convert Telegram API date (seconds) to database timestamp (milliseconds).
     * Overload for long to prevent Year 2038 overflow.
     * 
     * @param telegramSeconds Telegram API date in seconds since epoch
     * @return Database timestamp in milliseconds since epoch
     */
    public static long telegramSecondsToDbMillis(long telegramSeconds) {
        return telegramSeconds * 1000L;
    }
    
    /**
     * Convert Telegram API date (seconds) to database timestamp (milliseconds).
     * Overload for Integer (nullable).
     * 
     * @param telegramSeconds Telegram API date in seconds since epoch (nullable)
     * @return Database timestamp in milliseconds since epoch, or null if input is null
     */
    public static Long telegramSecondsToDbMillis(Integer telegramSeconds) {
        if (telegramSeconds == null) {
            return null;
        }
        return telegramSecondsToDbMillis(telegramSeconds.intValue());
    }
    
    /**
     * Convert database timestamp (milliseconds) to Telegram API date (seconds).
     * Used when comparing database timestamps with Telegram API dates.
     * 
     * @param dbMillis Database timestamp in milliseconds since epoch
     * @return Telegram API date in seconds since epoch
     */
    public static long dbMillisToTelegramSeconds(long dbMillis) {
        return dbMillis / 1000L;
    }
    
    /**
     * Convert database timestamp (milliseconds) to Telegram API date (seconds).
     * Overload for Long (nullable).
     * 
     * @param dbMillis Database timestamp in milliseconds since epoch (nullable)
     * @return Telegram API date in seconds since epoch, or null if input is null
     */
    public static Long dbMillisToTelegramSeconds(Long dbMillis) {
        if (dbMillis == null) {
            return null;
        }
        return dbMillisToTelegramSeconds(dbMillis.longValue());
    }
    
    /**
     * Check if a Telegram API date (seconds) is after or equal to a cutoff date (seconds).
     * Both dates must be in seconds for correct comparison.
     * 
     * @param messageDate Telegram API message date in seconds
     * @param cutoffDate Cutoff date in seconds (from historySince)
     * @return true if messageDate >= cutoffDate
     */
    public static boolean isAfterOrEqualCutoff(long messageDate, long cutoffDate) {
        return messageDate >= cutoffDate;
    }
    
    /**
     * Check if a Telegram API date (seconds) is before a cutoff date (seconds).
     * Both dates must be in seconds for correct comparison.
     * 
     * @param messageDate Telegram API message date in seconds
     * @param cutoffDate Cutoff date in seconds (from historySince)
     * @return true if messageDate < cutoffDate
     */
    public static boolean isBeforeCutoff(long messageDate, long cutoffDate) {
        return messageDate < cutoffDate;
    }
    
    /**
     * Check if a database timestamp (milliseconds) is after or equal to a cutoff date (seconds).
     * Converts database timestamp to seconds for comparison.
     * 
     * @param dbMillis Database timestamp in milliseconds
     * @param cutoffDate Cutoff date in seconds (from historySince)
     * @return true if dbMillis >= cutoffDate (after conversion)
     */
    public static boolean isDbTimestampAfterOrEqualCutoff(long dbMillis, long cutoffDate) {
        long dbSeconds = dbMillisToTelegramSeconds(dbMillis);
        return isAfterOrEqualCutoff(dbSeconds, cutoffDate);
    }
    
    /**
     * Check if a database timestamp (milliseconds) is before a cutoff date (seconds).
     * Converts database timestamp to seconds for comparison.
     * 
     * @param dbMillis Database timestamp in milliseconds
     * @param cutoffDate Cutoff date in seconds (from historySince)
     * @return true if dbMillis < cutoffDate (after conversion)
     */
    public static boolean isDbTimestampBeforeCutoff(long dbMillis, long cutoffDate) {
        long dbSeconds = dbMillisToTelegramSeconds(dbMillis);
        return isBeforeCutoff(dbSeconds, cutoffDate);
    }
    
    /**
     * Get current time in Telegram API format (seconds since epoch).
     * 
     * @return Current time in seconds since epoch
     */
    public static long currentTelegramSeconds() {
        return System.currentTimeMillis() / 1000L;
    }
    
    /**
     * Get current time in database timestamp format (milliseconds since epoch).
     * 
     * @return Current time in milliseconds since epoch
     */
    public static long currentDbMillis() {
        return System.currentTimeMillis();
    }
    
    /**
     * Validate that a Telegram API date is reasonable (not in the future, not before 2000).
     * Useful for detecting incorrect date conversions.
     * 
     * @param telegramSeconds Telegram API date in seconds
     * @return true if date is reasonable
     */
    public static boolean isValidTelegramDate(long telegramSeconds) {
        // Telegram was created in 2013, so dates before 2000 are likely wrong
        long minDate = 946684800L; // 2000-01-01 00:00:00 UTC
        // Allow dates up to 1 day in the future (timezone/clock skew)
        long maxDate = currentTelegramSeconds() + 86400L;
        return telegramSeconds >= minDate && telegramSeconds <= maxDate;
    }
    
    /**
     * Format a Telegram API date (seconds) as a human-readable string.
     * 
     * @param telegramSeconds Telegram API date in seconds
     * @return Formatted date string
     */
    public static String formatTelegramDate(long telegramSeconds) {
        long millis = telegramSecondsToDbMillis(telegramSeconds);
        return java.time.Instant.ofEpochMilli(millis)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
    
    /**
     * Format a database timestamp (milliseconds) as a human-readable string.
     * 
     * @param dbMillis Database timestamp in milliseconds
     * @return Formatted date string
     */
    public static String formatDbTimestamp(long dbMillis) {
        return java.time.Instant.ofEpochMilli(dbMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}

