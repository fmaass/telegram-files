package telegram.files.util;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;

/**
 * Unified error handling strategy for async operations.
 * Provides consistent patterns for critical, recoverable, and optional operations.
 */
public class ErrorHandling {
    
    private static final Log log = LogFactory.get();
    
    /**
     * Critical operation - fail fast, propagate error, log at ERROR level.
     * Use for operations that must succeed (e.g., database writes, essential API calls).
     * 
     * @param future The future to wrap
     * @param context Description for logging (e.g., "Fetching message for download")
     * @return The same future with error logging
     */
    public static <T> Future<T> critical(Future<T> future, String context) {
        return future.onFailure(err -> 
            log.error("[CRITICAL] {}: {} - {}", context, err.getClass().getSimpleName(), err.getMessage())
        );
    }
    
    /**
     * Recoverable operation - log warning and provide fallback value.
     * Use for operations that can fail gracefully (e.g., settings fetch, optional features).
     * 
     * @param future The future to wrap
     * @param defaultValue Fallback value if operation fails
     * @param context Description for logging
     * @return Future that resolves to result or default value
     */
    public static <T> Future<T> recoverable(Future<T> future, T defaultValue, String context) {
        return future.recover(err -> {
            log.warn("[RECOVERABLE] {}: {} - using default", context, err.getMessage());
            return Future.succeededFuture(defaultValue);
        });
    }
    
    /**
     * Optional operation - log at debug level, don't propagate error.
     * Use for non-essential operations (e.g., thumbnail downloads, statistics updates).
     * 
     * @param future The future to wrap
     * @param context Description for logging
     * @return The same future with debug logging
     */
    public static <T> Future<T> optional(Future<T> future, String context) {
        return future.onFailure(err -> 
            log.debug("[OPTIONAL] {}: {}", context, err.getMessage())
        );
    }
    
    /**
     * Silent operation - no logging, just swallow errors.
     * Use sparingly, only for truly ignorable operations.
     * 
     * @param future The future to wrap
     * @return Future that never fails (errors converted to null)
     */
    public static <T> Future<T> silent(Future<T> future) {
        return future.recover(err -> Future.succeededFuture(null));
    }
}

