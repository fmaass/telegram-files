package telegram.files;

import io.vertx.core.Vertx;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Row;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Contract guard for the {@code file_record} table (v1). See {@code docs/file_record-CONTRACT.md}.
 * <p>
 * {@code file_record} is a SHARED CONTRACT that three external telegram-postproc services read AND
 * write directly. These tests assert that the LIVE, migrated Postgres schema matches
 * {@link FileRecordContract} EXACTLY -- column names, Postgres data types, and the primary key. A
 * future migration that renames, drops, or retypes a contract column, or changes the PK, fails here
 * BEFORE it can silently break an external consumer's SELECT/UPDATE.
 * <p>
 * Runs on the real Postgres 17 fixture ({@link PostgresIntegrationTest}); a silent SQLite fallback
 * fails loudly via {@link #assertReachedPostgres}.
 */
class FileRecordContractGuardTest extends PostgresIntegrationTest {

    @Test
    @DisplayName("PG: live file_record column set + types match the frozen contract manifest EXACTLY")
    void fileRecordSchemaMatchesContractManifest(Vertx vertx, VertxTestContext testContext) {
        assertReachedPostgres(vertx)
                .compose(v -> vertx.deployVerticle(new DataVerticle()))
                .compose(id -> DataVerticle.pool.getConnection())
                .compose(conn -> conn.query("""
                                SELECT column_name, data_type
                                FROM information_schema.columns
                                WHERE table_schema = 'public' AND table_name = 'file_record'
                                ORDER BY ordinal_position
                                """).execute()
                        .compose(rs -> {
                            Map<String, String> live = new LinkedHashMap<>();
                            for (Row row : rs) {
                                live.put(row.getString("column_name"), row.getString("data_type"));
                            }
                            testContext.verify(() -> {
                                // 1. Exact column-name set: no missing (dropped/renamed) columns, no
                                //    undocumented additions. Both directions matter for a frozen contract.
                                TreeSet<String> expectedNames = new TreeSet<>(FileRecordContract.COLUMNS.keySet());
                                TreeSet<String> actualNames = new TreeSet<>(live.keySet());
                                Assertions.assertEquals(expectedNames, actualNames,
                                        "file_record columns drifted from the frozen contract manifest. "
                                                + "A renamed/dropped/added column breaks external consumers "
                                                + "(see docs/file_record-CONTRACT.md). Missing="
                                                + minus(expectedNames, actualNames)
                                                + " Unexpected=" + minus(actualNames, expectedNames));

                                // 2. Exact Postgres data_type per column: a retype (e.g. BIGINT->INT,
                                //    VARCHAR->TEXT) changes the wire contract external readers depend on.
                                for (Map.Entry<String, String> e : FileRecordContract.COLUMNS.entrySet()) {
                                    Assertions.assertEquals(e.getValue(), live.get(e.getKey()),
                                            "file_record column '" + e.getKey() + "' retyped: contract expects '"
                                                    + e.getValue() + "' but live schema has '" + live.get(e.getKey())
                                                    + "'. This breaks the external-consumer contract.");
                                }

                                Assertions.assertEquals(FileRecordContract.COLUMNS.size(), live.size(),
                                        "file_record must have exactly " + FileRecordContract.COLUMNS.size()
                                                + " contract columns");
                            });
                            return conn.close();
                        }))
                .onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }

    @Test
    @DisplayName("PG: live file_record primary key is exactly (unique_id)")
    void fileRecordPrimaryKeyMatchesContract(Vertx vertx, VertxTestContext testContext) {
        assertReachedPostgres(vertx)
                .compose(v -> vertx.deployVerticle(new DataVerticle()))
                .compose(id -> DataVerticle.pool.getConnection())
                .compose(conn -> conn.query("""
                                SELECT a.attname AS column_name
                                FROM pg_index i
                                JOIN pg_attribute a
                                  ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
                                WHERE i.indrelid = 'file_record'::regclass AND i.indisprimary
                                ORDER BY a.attname
                                """).execute()
                        .compose(rs -> {
                            java.util.List<String> pkCols = new java.util.ArrayList<>();
                            for (Row row : rs) {
                                pkCols.add(row.getString("column_name"));
                            }
                            testContext.verify(() ->
                                    Assertions.assertEquals(
                                            java.util.List.of(FileRecordContract.PRIMARY_KEY), pkCols,
                                            "file_record PRIMARY KEY must be exactly (" + FileRecordContract.PRIMARY_KEY
                                                    + "). A PK change breaks external UPSERT/UPDATE ... WHERE "
                                                    + "unique_id semantics (see docs/file_record-CONTRACT.md). "
                                                    + "Live PK columns=" + pkCols));
                            return conn.close();
                        }))
                .onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }

    private static TreeSet<String> minus(java.util.Set<String> a, java.util.Set<String> b) {
        TreeSet<String> r = new TreeSet<>(a);
        r.removeAll(b);
        return r;
    }
}
