package telegram.files.repository;

import cn.hutool.core.lang.Version;
import cn.hutool.core.map.MapUtil;
import io.vertx.sqlclient.templates.RowMapper;

import java.util.TreeMap;

/**
 * Additive-only companion table to {@code file_record}. It does NOT change the frozen
 * {@code file_record} contract (see {@code docs/file_record-CONTRACT.md}); external consumers
 * neither read nor write this table.
 * <p>
 * There is deliberately no SQL foreign key to {@code file_record} (see {@link #SCHEME}); the
 * ON DELETE CASCADE intent is enforced in application code instead.
 * <p>
 * One row tracks a single download attempt for a {@code unique_id}. The invariant
 * <b>at most one {@code status='active'} attempt per {@code unique_id}</b> is what makes a claim
 * ({@code idle -> downloading} plus the owning attempt INSERT) atomic and idempotent: the partial
 * unique index {@link #ACTIVE_ATTEMPT_UNIQUE_INDEX} rejects a second active attempt.
 * <p>
 * The partial unique index is applied through the ordinary migration path (fail-loud on real
 * errors; see {@link Definition#migrate}) AND as a boot-time verification in
 * {@code DataVerticle.verifyDownloadAttemptUniqueIndex} that aborts startup if it is absent, so a
 * silently-swallowed index-creation failure cannot ship a database without the guarantee.
 * <p>
 * {@code attempt_id} is an app-generated UUID string (never a DB auto-increment), keeping the table
 * portable across SQLite / PostgreSQL / MySQL with no identity-column dialect differences.
 */
public record DownloadAttemptRecord(
        String attemptId,
        String uniqueId,
        String leaseOwner,
        Long leaseExpiresAt,
        String status, // 'active' | 'retired' | 'failed' | 'completed'
        Long createdAt,
        Long updatedAt
) {

    public enum AttemptStatus {
        active, retired, failed, completed
    }

    /** Name of the partial unique index enforcing one ACTIVE attempt per unique_id. */
    public static final String ACTIVE_ATTEMPT_UNIQUE_INDEX = "uq_download_attempt_active";

    // NOTE: intentionally NO SQL FOREIGN KEY to file_record. Definitions' createTable() runs
    // CONCURRENTLY (Future.all) and in no guaranteed order, so a DB-level FK to file_record would
    // (a) fail to create when download_attempt is built before file_record, and (b) block
    // file_record's 0.3.3 PK-swap migration ("cannot drop constraint ... other objects depend on
    // it"). The ON DELETE CASCADE intent is instead enforced in application code: deleteByUniqueId
    // clears attempts, and reconciliation's retireOrphanedAttempts() retires any attempt whose
    // file_record is no longer 'downloading' (including a deleted row). This keeps the table purely
    // additive to the frozen file_record contract.
    public static final String SCHEME = """
            CREATE TABLE IF NOT EXISTS download_attempt
            (
                attempt_id        VARCHAR(64) PRIMARY KEY,
                unique_id         VARCHAR(255) NOT NULL,
                lease_owner       VARCHAR(255),
                lease_expires_at  BIGINT,
                status            VARCHAR(16) NOT NULL,
                created_at        BIGINT,
                updated_at        BIGINT
            )
            """;

    /**
     * The one-active-attempt-per-unique_id guarantee. Postgres/SQLite support partial unique
     * indexes directly; MySQL 8 does not, so a functional expression index emulates the partial
     * predicate (NULL for non-active rows is excluded from uniqueness).
     */
    private static final String PG_SQLITE_ACTIVE_INDEX =
            "CREATE UNIQUE INDEX %s ON download_attempt (unique_id) WHERE status = 'active'"
                    .formatted(ACTIVE_ATTEMPT_UNIQUE_INDEX);

    private static final String MYSQL_ACTIVE_INDEX =
            "CREATE UNIQUE INDEX %s ON download_attempt ((CASE WHEN status = 'active' THEN unique_id ELSE NULL END))"
                    .formatted(ACTIVE_ATTEMPT_UNIQUE_INDEX);

    public static String activeAttemptUniqueIndexSql(boolean isMysql) {
        return isMysql ? MYSQL_ACTIVE_INDEX : PG_SQLITE_ACTIVE_INDEX;
    }

    public static final String IDX_UNIQUE_ID =
            "CREATE INDEX idx_download_attempt_unique_id ON download_attempt (unique_id)";

    public static class DownloadAttemptDefinition implements Definition {

        private final boolean isMysql;

        public DownloadAttemptDefinition(boolean isMysql) {
            this.isMysql = isMysql;
        }

        @Override
        public String getScheme() {
            return SCHEME;
        }

        @Override
        public String[] getIndexes() {
            // Fresh installs skip migrations and only run createTable() + getIndexes(), so the
            // ACTIVE-unique index MUST also be attempted here to exist on a brand-new database.
            // getIndexes() swallows failures, which is why boot verification
            // (DataVerticle.verifyDownloadAttemptUniqueIndex) is the fail-loud backstop for BOTH the
            // fresh-install (this) path and the upgrade (migration) path.
            return new String[]{
                    IDX_UNIQUE_ID,
                    activeAttemptUniqueIndexSql(isMysql),
            };
        }

        @Override
        public TreeMap<Version, String[]> getMigrations() {
            // Applied through executeStatementsTolerant (fail-loud on non-"already exists" errors).
            // Fresh installs skip migrations entirely, so the boot verification below covers them.
            TreeMap<Version, String[]> migrations = new TreeMap<>();
            migrations.put(new Version("0.4.7"), new String[]{
                    activeAttemptUniqueIndexSql(isMysql),
            });
            return migrations;
        }
    }

    public static RowMapper<DownloadAttemptRecord> ROW_MAPPER = row ->
            new DownloadAttemptRecord(
                    row.getString("attempt_id"),
                    row.getString("unique_id"),
                    row.getString("lease_owner"),
                    row.getLong("lease_expires_at"),
                    row.getString("status"),
                    row.getLong("created_at"),
                    row.getLong("updated_at")
            );
}
