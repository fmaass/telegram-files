package telegram.files.repository;

import cn.hutool.core.lang.Version;
import io.vertx.sqlclient.templates.RowMapper;

import java.util.TreeMap;

/**
 * Additive-only companion table to {@code file_record} recording the DURABLE TRUTH of an in-flight
 * transfer operation (Phase 3). It does NOT change the frozen {@code file_record} contract (see
 * {@code docs/file_record-CONTRACT.md}); external consumers neither read nor write this table.
 * <p>
 * <b>Core invariant this table upholds:</b> at every crash point of a transfer, at least one COMPLETE
 * copy of the payload exists AND is discoverable from this record. The transfer protocol
 * (see {@link telegram.files.DurableTransfer}) is:
 * <ol>
 *   <li>PERSIST this record FIRST, before any filesystem mutation;</li>
 *   <li>COPY (never move) the source into a dest-local temp — the SOURCE is the durable anchor and
 *       survives until the payload is confirmed at the destination;</li>
 *   <li>size-verify + fsync the temp, atomic-RENAME it onto {@code final_dest_path};</li>
 *   <li>fsync the destination directory;</li>
 *   <li>CAS-finalize {@code transfer_status} AND delete this record in one transaction;</li>
 *   <li>delete the SOURCE only AFTER that commit.</li>
 * </ol>
 * Reconciliation reads this record and verifies payload IDENTITY by {@code source_size} (a wrong file
 * sitting at {@code final_dest_path} is NOT treated as ours), recovers forward when the payload is at
 * the destination, else from the temp, else retries from the surviving source — never re-downloading a
 * file that is verifiably at the destination and never deleting a sole copy.
 * <p>
 * One row per {@code unique_id} (PK). A row present at boot marks a transfer that crashed mid-flight.
 * Fields:
 * <ul>
 *   <li>{@code final_dest_path} — the EXACT destination the RENAME/duplication policy chose (incl. any
 *       RENAME suffix), baked in at plan time so recovery targets the same path;</li>
 *   <li>{@code staging_temp_path} — the dest-local temp the bytes were copied into;</li>
 *   <li>{@code source_path} — the download-inbox source path (the durable anchor);</li>
 *   <li>{@code source_size} — the payload identity in BYTES; recovery finalizes/renames only when the
 *       on-disk candidate's size matches this;</li>
 *   <li>{@code overwrite_policy} — the duplication policy ({@link telegram.files.Transfer.DuplicationPolicy})
 *       so recovery honors SKIP/RENAME/OVERWRITE/HASH rather than blindly clobbering an external file.</li>
 * </ul>
 * There is deliberately no SQL foreign key to {@code file_record} (mirrors {@code download_attempt}:
 * concurrent {@code createTable} ordering + the 0.3.3 PK-swap preclude one); the cascade intent is
 * enforced in application code ({@code deleteByUniqueId} clears it).
 */
public record TransferOperationRecord(
        String uniqueId,
        String finalDestPath,
        String stagingTempPath,
        String sourcePath,
        long sourceSize,
        String overwritePolicy,
        Long createdAt
) {

    public static final String SCHEME = """
            CREATE TABLE IF NOT EXISTS transfer_operation
            (
                unique_id          VARCHAR(255) PRIMARY KEY,
                final_dest_path    VARCHAR(1024),
                staging_temp_path  VARCHAR(1024),
                source_path        VARCHAR(1024),
                source_size        BIGINT,
                overwrite_policy   VARCHAR(32),
                created_at         BIGINT
            )
            """;

    public static class TransferOperationDefinition implements Definition {

        @Override
        public String getScheme() {
            return SCHEME;
        }

        @Override
        public TreeMap<Version, String[]> getMigrations() {
            // Additive table introduced in 0.4.7. Fresh installs create it via getScheme(); upgrades
            // create it here (tolerant path: "already exists" is swallowed by Definition.migrate).
            TreeMap<Version, String[]> migrations = new TreeMap<>();
            migrations.put(new Version("0.4.7"), new String[]{SCHEME});
            return migrations;
        }
    }

    public static RowMapper<TransferOperationRecord> ROW_MAPPER = row ->
            new TransferOperationRecord(
                    row.getString("unique_id"),
                    row.getString("final_dest_path"),
                    row.getString("staging_temp_path"),
                    row.getString("source_path"),
                    row.getLong("source_size") == null ? -1L : row.getLong("source_size"),
                    row.getString("overwrite_policy"),
                    row.getLong("created_at")
            );
}
