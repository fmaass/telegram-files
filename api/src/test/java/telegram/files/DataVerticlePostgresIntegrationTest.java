package telegram.files;

import cn.hutool.core.collection.IterUtil;
import cn.hutool.core.lang.Version;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import telegram.files.repository.SettingKey;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * PostgreSQL parity for the DataVerticle initialization/migration path. This ports
 * {@code DataVerticleMigrationTest.testNewDatabaseInitialization} onto the testcontainers Postgres
 * 17 fixture to prove the {@code isPostgres()} branch reaches parity with the default SQLite suite.
 * <p>
 * Every test first asserts it actually reached Postgres (via {@link #assertReachedPostgres}); a
 * silent SQLite fallback fails loudly here rather than passing on the wrong backend.
 */
class DataVerticlePostgresIntegrationTest extends PostgresIntegrationTest {

    @Test
    @DisplayName("PG: new database initialization creates all tables at Start.VERSION")
    void testNewDatabaseInitializationOnPostgres(Vertx vertx, VertxTestContext testContext) {
        assertReachedPostgres(vertx)
                .compose(v -> vertx.deployVerticle(new DataVerticle()))
                .compose(id -> DataVerticle.pool.getConnection())
                .compose(conn -> conn.query("""
                                SELECT table_name FROM information_schema.tables
                                WHERE table_schema = 'public'
                                  AND table_name IN ('setting_record', 'telegram_record', 'file_record', 'statistic_record')
                                """).execute()
                        .compose(result -> {
                            testContext.verify(() ->
                                    Assertions.assertEquals(DataVerticle.definitions.size(), result.size(),
                                            "All four record tables must be created on Postgres"));
                            return conn.close();
                        }))
                .compose(v -> DataVerticle.settingRepository.<Version>getByKey(SettingKey.version))
                .onComplete(testContext.succeeding(version -> testContext.verify(() -> {
                    Assertions.assertEquals(new Version(Start.VERSION), version,
                            "Version row must equal Start.VERSION after PG migration");
                    testContext.completeNow();
                })));
    }

    @Test
    @DisplayName("PG: migrated file_record has queue columns (parity with SQLite migration)")
    void testFileRecordQueueColumnsOnPostgres(Vertx vertx, VertxTestContext testContext) {
        assertReachedPostgres(vertx)
                .compose(v -> vertx.deployVerticle(new DataVerticle()))
                .compose(id -> DataVerticle.pool.getConnection())
                .compose(conn -> conn.query("""
                                SELECT column_name AS name FROM information_schema.columns
                                WHERE table_name = 'file_record'
                                """).execute()
                        .compose(result -> {
                            testContext.verify(() -> {
                                Set<String> columnNames = IterUtil.toList(result).stream()
                                        .map(row -> row.getString("name"))
                                        .collect(Collectors.toSet());
                                Assertions.assertTrue(columnNames.contains("scan_state"));
                                Assertions.assertTrue(columnNames.contains("download_priority"));
                                Assertions.assertTrue(columnNames.contains("queued_at"));
                                Assertions.assertTrue(columnNames.contains("start_date"));
                                Assertions.assertTrue(columnNames.contains("completion_date"));
                            });
                            return conn.close();
                        }))
                .onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }
}
