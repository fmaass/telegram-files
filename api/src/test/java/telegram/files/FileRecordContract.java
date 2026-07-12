package telegram.files;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Machine-checkable manifest of the {@code file_record} contract (v1).
 * <p>
 * The {@code file_record} table is a SHARED CONTRACT that three external telegram-postproc
 * services read AND write directly (see {@code docs/file_record-CONTRACT.md}). This class is the
 * single source of truth the contract-guard integration test asserts the LIVE migrated Postgres
 * schema against, so any future migration that renames, drops, or retypes a contract column fails
 * CI instead of silently breaking an external consumer.
 * <p>
 * The prose spec, per-column ownership, and every external-query citation live in
 * {@code docs/file_record-CONTRACT.md}. Keep this manifest and that document in lock-step: a change
 * to one without the other is the drift this guard exists to catch.
 * <p>
 * PostgreSQL {@code information_schema.columns.data_type} strings are used (VARCHAR ->
 * {@code character varying}, INT -> {@code integer}, BIGINT -> {@code bigint}, BOOLEAN ->
 * {@code boolean}). These are the authoritative names the SQL types in
 * {@code FileRecord.SCHEME} resolve to on Postgres 17.
 */
public final class FileRecordContract {

    private FileRecordContract() {
    }

    /** The table's primary key (frozen v1 contract: single-column PK on {@code unique_id}). */
    public static final String PRIMARY_KEY = "unique_id";

    /**
     * The 29 contract columns in schema-declaration order, mapped to their Postgres
     * {@code information_schema.columns.data_type}. Insertion order is preserved (LinkedHashMap)
     * so a diff reads in the same order as {@code FileRecord.SCHEME}.
     */
    public static final Map<String, String> COLUMNS;

    static {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("id", "integer");                       // INT
        c.put("unique_id", "character varying");      // VARCHAR(255) -- PK
        c.put("telegram_id", "bigint");               // BIGINT
        c.put("chat_id", "bigint");                   // BIGINT (can be large negative for groups/channels)
        c.put("message_id", "bigint");                // BIGINT
        c.put("media_album_id", "bigint");            // BIGINT
        c.put("date", "integer");                     // INT -- Telegram upload time, epoch SECONDS
        c.put("has_sensitive_content", "boolean");    // BOOLEAN
        c.put("size", "bigint");                      // BIGINT
        c.put("downloaded_size", "bigint");           // BIGINT
        c.put("type", "character varying");           // VARCHAR(255)
        c.put("mime_type", "character varying");      // VARCHAR(255)
        c.put("file_name", "character varying");      // VARCHAR(255)
        c.put("thumbnail", "character varying");      // VARCHAR(2056)
        c.put("thumbnail_unique_id", "character varying"); // VARCHAR(255)
        c.put("caption", "character varying");        // VARCHAR(4096)
        c.put("extra", "character varying");          // VARCHAR(4096)
        c.put("local_path", "character varying");     // VARCHAR(1024)
        c.put("download_status", "character varying"); // VARCHAR(255)
        c.put("transfer_status", "character varying"); // VARCHAR(255)
        c.put("start_date", "bigint");                // BIGINT -- epoch MILLISECONDS
        c.put("completion_date", "bigint");           // BIGINT -- epoch MILLISECONDS
        c.put("tags", "character varying");           // VARCHAR(2056)
        c.put("thread_chat_id", "bigint");            // BIGINT
        c.put("message_thread_id", "bigint");         // BIGINT
        c.put("reaction_count", "bigint");            // BIGINT DEFAULT 0
        c.put("scan_state", "character varying");     // VARCHAR(20) DEFAULT 'idle'
        c.put("download_priority", "integer");        // INT DEFAULT 0
        c.put("queued_at", "bigint");                 // BIGINT -- epoch MILLISECONDS
        COLUMNS = java.util.Collections.unmodifiableMap(c);
    }

    /**
     * The canonical {@code download_status} enum. External writers MUST stay within this set. The
     * contract-guard behavioral test asserts this enum is a superset of every value observed in the
     * external consumers' writes (see {@code docs/file_record-CONTRACT.md}). NB: {@code 'downloaded'}
     * is NOT a member -- it is a phantom value some external READERS filter on but no writer emits.
     */
    public static final java.util.Set<String> DOWNLOAD_STATUS_VALUES = java.util.Set.of(
            "idle", "downloading", "paused", "completed", "processed", "imported", "error");

    /** Canonical {@code transfer_status} enum. */
    public static final java.util.Set<String> TRANSFER_STATUS_VALUES = java.util.Set.of(
            "idle", "transferring", "completed", "error");

    /** Canonical {@code scan_state} values (discovery lifecycle). */
    public static final java.util.Set<String> SCAN_STATE_VALUES = java.util.Set.of(
            "idle", "scanning", "complete");

    /**
     * {@code download_status} values that, once set by an EXTERNAL service, telegram-files must
     * never downgrade (see {@code TelegramVerticle.updateFile} never-downgrade guard). This is the
     * load-bearing write-ownership rule between the two systems.
     */
    public static final java.util.Set<String> EXTERNAL_TERMINAL_STATUSES = java.util.Set.of(
            "processed", "imported");
}
