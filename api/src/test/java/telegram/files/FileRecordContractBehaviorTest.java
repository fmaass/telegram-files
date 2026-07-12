package telegram.files;

import io.vertx.core.Vertx;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Row;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import telegram.files.repository.FileRecord;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Behavioral contract fixtures for {@code file_record} -- the parts of the contract that column
 * shape alone cannot catch. See {@code docs/file_record-CONTRACT.md}.
 * <p>
 * These pin the runtime semantics external telegram-postproc consumers depend on:
 * <ul>
 *   <li>the exact {@code download_status} / {@code transfer_status} / {@code scan_state} string
 *       VALUES external writers use (the canonical enum must be a SUPERSET of the observed set);</li>
 *   <li>NULLABILITY of columns external readers depend on (a future NOT NULL / DEFAULT change that
 *       silently altered these would break external inserts or reads);</li>
 *   <li>TIMESTAMP UNITS: {@code start_date}/{@code completion_date}/{@code queued_at} are epoch
 *       MILLISECONDS while {@code date} is Telegram SECONDS -- a future unit change is a silent
 *       data-corruption bug across every external reader;</li>
 *   <li>the write-ownership rule that telegram-files must never downgrade an external-owned terminal
 *       status ({@code processed}/{@code imported}).</li>
 * </ul>
 * Runs on real Postgres 17 ({@link PostgresIntegrationTest}); silent SQLite fallback fails loudly.
 */
class FileRecordContractBehaviorTest extends PostgresIntegrationTest {

    // Exact status VALUES observed being WRITTEN by external telegram-postproc consumers.
    // Cited in docs/file_record-CONTRACT.md against telegram-postproc/<file>:<line>.
    private static final Set<String> EXTERNAL_WRITTEN_DOWNLOAD_STATUSES = Set.of(
            "processed",  // file_processor_worker.py:600, telegram_status_updater.py, verify_dubtechno_completed.py:90, backfill_processed_files.py:112
            "imported",   // backfill_completed_to_imported.{py:96,sql:24}, telegram_status_updater.py
            "idle"        // telegram_health_monitor.py:225, reset_stuck_downloads.sh:46 (reset-stuck)
    );

    // download_status VALUES external consumers READ / filter on (superset of written; includes the
    // phantom 'downloaded' which NO writer emits -- see docs/file_record-CONTRACT.md Finding A).
    private static final Set<String> EXTERNAL_READ_DOWNLOAD_STATUSES = Set.of(
            "processed", "completed", "downloaded", "downloading", "idle", "imported");

    @Test
    @DisplayName("Contract: canonical download_status enum is a superset of every value external writers set")
    void enumSupersetsExternalWrittenStatuses() {
        Set<String> enumValues = Arrays.stream(FileRecord.DownloadStatus.values())
                .map(Enum::name).collect(Collectors.toSet());

        Assertions.assertEquals(FileRecordContract.DOWNLOAD_STATUS_VALUES, enumValues,
                "FileRecord.DownloadStatus enum drifted from the contract manifest's DOWNLOAD_STATUS_VALUES");

        for (String written : EXTERNAL_WRITTEN_DOWNLOAD_STATUSES) {
            Assertions.assertTrue(enumValues.contains(written),
                    "External writer sets download_status='" + written + "' which is NOT in the "
                            + "canonical enum " + enumValues + ". Either the enum must add it or the "
                            + "external writer is out of contract (see docs/file_record-CONTRACT.md).");
        }
    }

    @Test
    @DisplayName("Contract: 'downloaded' is a phantom read-only status -- no enum member, no writer emits it")
    void downloadedIsPhantomReadOnlyStatus() {
        Set<String> enumValues = Arrays.stream(FileRecord.DownloadStatus.values())
                .map(Enum::name).collect(Collectors.toSet());
        // External READERS filter on 'downloaded' (Finding A) but it is NOT a canonical value and no
        // writer produces it. Pinning this keeps the discrepancy visible: if telegram-files ever
        // started emitting 'downloaded', this assertion breaks and forces reconciliation.
        Assertions.assertFalse(enumValues.contains("downloaded"),
                "'downloaded' unexpectedly became a canonical status. External readers assume it is a "
                        + "phantom (never written); adding it to the enum changes that contract.");
        Assertions.assertFalse(EXTERNAL_WRITTEN_DOWNLOAD_STATUSES.contains("downloaded"),
                "No external writer should emit 'downloaded'");
        Assertions.assertTrue(EXTERNAL_READ_DOWNLOAD_STATUSES.contains("downloaded"),
                "'downloaded' is expected in the external READ set (documents the phantom)");
    }

    @Test
    @DisplayName("Contract: transfer_status enum matches the manifest")
    void transferStatusEnumMatchesManifest() {
        Set<String> transfer = Arrays.stream(FileRecord.TransferStatus.values())
                .map(Enum::name).collect(Collectors.toSet());
        Assertions.assertEquals(FileRecordContract.TRANSFER_STATUS_VALUES, transfer,
                "FileRecord.TransferStatus enum drifted from the contract manifest");
    }

    @Test
    @DisplayName("Contract: scan_state manifest values derive from the production FileRecord field contract")
    void scanStateValuesMatchProductionSource() throws java.io.IOException {
        // scan_state has NO production Java enum -- it is a VARCHAR(20) whose canonical values are
        // DECLARED in production source as the state-contract comment on FileRecord.scanState:
        //   String scanState, // Discovery state: 'idle', 'scanning', 'complete'
        // That comment IS the production source of truth (all runtime writers/readers use only the
        // 'idle' literal; 'scanning'/'complete' are the declared discovery-lifecycle states). We parse
        // the distinct single-quoted literals from that authoritative production line and assert the
        // manifest equals it EXACTLY. Adding/removing a scan_state value in production (that comment)
        // therefore fails this test until docs/file_record-CONTRACT.md + the manifest are updated.
        // This is NOT a tautology: expected is derived from src/main, actual is the test manifest.
        Set<String> productionScanStates = parseScanStateContractFromSource();
        Assertions.assertEquals(Set.of("idle", "scanning", "complete"), productionScanStates,
                "Parsed production scan_state contract changed at FileRecord.scanState -- if this is "
                        + "intentional, update FileRecordContract.SCAN_STATE_VALUES and the contract doc");
        Assertions.assertEquals(productionScanStates, FileRecordContract.SCAN_STATE_VALUES,
                "scan_state manifest (FileRecordContract.SCAN_STATE_VALUES) drifted from the production "
                        + "FileRecord.scanState state-contract comment -- reconcile the manifest + "
                        + "docs/file_record-CONTRACT.md");
    }

    /**
     * Reads the production {@code FileRecord.java} and extracts the distinct single-quoted state
     * literals from the authoritative {@code scanState} field comment
     * ({@code // Discovery state: 'idle', 'scanning', 'complete'}). Deterministic: it locates the
     * unique {@code scanState,} field line and parses the quoted tokens from its trailing comment.
     */
    private static Set<String> parseScanStateContractFromSource() throws java.io.IOException {
        // Tests run with cwd = api/, so the production source is at this stable relative path.
        java.nio.file.Path src = java.nio.file.Path.of(
                "src/main/java/telegram/files/repository/FileRecord.java");
        Assertions.assertTrue(java.nio.file.Files.exists(src),
                "Production FileRecord.java not found at " + src.toAbsolutePath()
                        + " -- scan_state source anchor moved; update this test");
        String line = java.nio.file.Files.readAllLines(src).stream()
                .filter(l -> l.contains("String scanState,") && l.contains("Discovery state:"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Could not find the authoritative 'String scanState, // Discovery state: ...' "
                                + "line in FileRecord.java -- the scan_state contract anchor moved"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("'([a-z]+)'").matcher(line);
        Set<String> found = new java.util.LinkedHashSet<>();
        while (m.find()) {
            found.add(m.group(1));
        }
        Assertions.assertFalse(found.isEmpty(),
                "No quoted scan_state literals parsed from the FileRecord.scanState comment: " + line);
        return found;
    }

    @Test
    @DisplayName("PG: live scan_state column DEFAULT is 'idle' (production schema fact)")
    void scanStateColumnDefaultIsIdle(Vertx vertx, VertxTestContext testContext) {
        // A second, independent production-fact pin: the live migrated column DEFAULT. This can only
        // pass if FileRecord.SCHEME/migration actually declares DEFAULT 'idle' on Postgres -- it is
        // read from PG catalog metadata, not from any test constant.
        assertReachedPostgres(vertx)
                .compose(v -> vertx.deployVerticle(new DataVerticle()))
                .compose(id -> DataVerticle.pool.getConnection())
                .compose(conn -> conn.query("""
                                SELECT column_default
                                FROM information_schema.columns
                                WHERE table_schema = 'public' AND table_name = 'file_record'
                                  AND column_name = 'scan_state'
                                """).execute()
                        .compose(rs -> {
                            String def = rs.iterator().next().getString("column_default");
                            testContext.verify(() ->
                                    // Postgres renders it as: 'idle'::character varying
                                    Assertions.assertTrue(def != null && def.startsWith("'idle'"),
                                            "Live scan_state column DEFAULT must be 'idle' (contract), was: " + def));
                            return conn.close();
                        }))
                .onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }

    @Test
    @DisplayName("Contract: never-downgrade terminal statuses are exactly {processed, imported}")
    void externalTerminalStatusesAreProcessedAndImported() {
        // These are the statuses telegram-files must never downgrade once an external service sets
        // them (TelegramVerticle never-downgrade guard). If this set changes, the guard's scope and
        // the external write-ownership contract change with it.
        Assertions.assertEquals(Set.of("processed", "imported"),
                FileRecordContract.EXTERNAL_TERMINAL_STATUSES,
                "External-owned terminal status set changed -- reconcile the TelegramVerticle "
                        + "never-downgrade guard and docs/file_record-CONTRACT.md");
        // And they must be genuine enum members that report isTerminal().
        Assertions.assertTrue(FileRecord.DownloadStatus.processed.isTerminal());
        Assertions.assertTrue(FileRecord.DownloadStatus.imported.isTerminal());
        // The never-downgrade direction is encoded in the transition table: imported is a dead end,
        // and nothing transitions OUT of processed except forward to imported.
        Assertions.assertFalse(FileRecord.DownloadStatus.imported.canTransitionTo(FileRecord.DownloadStatus.completed),
                "imported must never transition back to completed (never-downgrade)");
        Assertions.assertFalse(FileRecord.DownloadStatus.processed.canTransitionTo(FileRecord.DownloadStatus.completed),
                "processed must never transition back to completed (never-downgrade)");
    }

    @Test
    @DisplayName("PG: columns external readers depend on are NULLABLE in the live schema")
    void externalReadDependencyColumnsAreNullable(Vertx vertx, VertxTestContext testContext) {
        // External readers explicitly handle NULLs on these (completion_date via COALESCE / NULLS
        // LAST / IS NULL guards; start_date/downloaded_size via IS NULL predicates and reset-to-NULL
        // writes; local_path via 'IS NULL OR = empty'). A future NOT NULL constraint on any of these
        // would break external INSERT/reset paths. See docs/file_record-CONTRACT.md.
        Set<String> mustBeNullable = Set.of(
                "completion_date", "start_date", "downloaded_size", "local_path",
                "message_thread_id", "download_status", "date");
        assertReachedPostgres(vertx)
                .compose(v -> vertx.deployVerticle(new DataVerticle()))
                .compose(id -> DataVerticle.pool.getConnection())
                .compose(conn -> conn.query("""
                                SELECT column_name, is_nullable
                                FROM information_schema.columns
                                WHERE table_schema = 'public' AND table_name = 'file_record'
                                """).execute()
                        .compose(rs -> {
                            Map<String, String> nullable = new HashMap<>();
                            for (Row row : rs) {
                                nullable.put(row.getString("column_name"), row.getString("is_nullable"));
                            }
                            testContext.verify(() -> {
                                for (String col : mustBeNullable) {
                                    Assertions.assertEquals("YES", nullable.get(col),
                                            "Contract column '" + col + "' must remain NULLABLE -- external "
                                                    + "consumers depend on it (see docs/file_record-CONTRACT.md). "
                                                    + "is_nullable=" + nullable.get(col));
                                }
                            });
                            return conn.close();
                        }))
                .onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }

    @Test
    @DisplayName("Contract: timestamp units are pinned (millis for start/completion/queued_at, seconds for date)")
    void timestampUnitsArePinned() {
        // This fixture DOCUMENTS and PINS the unit contract every external reader assumes:
        //   - completion_date, start_date, queued_at: BIGINT epoch MILLISECONDS
        //   - date: INT Telegram epoch SECONDS
        // The production writers back this: TelegramVerticle sets completion_date =
        // System.currentTimeMillis() (millis) and derives it from date*1000L (seconds->millis);
        // AutoDownloadVerticle/FileRepositoryImpl set queued_at = System.currentTimeMillis().
        // External readers divide completion_date/start_date/date by 1000 before comparing to epoch
        // seconds, and external writers multiply by 1000 (int(time.time()*1000)).
        //
        // The guard: the millis columns are BIGINT (can't hold epoch-millis in an INT), and `date`
        // is INT (Telegram seconds fit). A future migration that swapped these widths would flip the
        // unit contract; the contract-guard schema test asserts these exact types, and this fixture
        // records WHY those widths are load-bearing.
        Map<String, String> millisColumnsMustBeBigint = Map.of(
                "completion_date", "bigint",
                "start_date", "bigint",
                "queued_at", "bigint");
        for (Map.Entry<String, String> e : millisColumnsMustBeBigint.entrySet()) {
            Assertions.assertEquals(e.getValue(), FileRecordContract.COLUMNS.get(e.getKey()),
                    "Epoch-millisecond column '" + e.getKey() + "' must be BIGINT (a narrower type would "
                            + "overflow epoch millis and flip the unit contract external readers assume)");
        }
        Assertions.assertEquals("integer", FileRecordContract.COLUMNS.get("date"),
                "'date' (Telegram epoch SECONDS) is INT by contract; changing its width or unit breaks "
                        + "external date/1000 arithmetic (see docs/file_record-CONTRACT.md)");
    }
}
