package telegram.files;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

/**
 * Base class for PostgreSQL-backed integration tests.
 * <p>
 * DB selection lives in {@link Config}'s static-final fields, which are frozen at class-load from
 * {@code System.getenv}. JVM system properties do NOT populate {@code System.getenv}, so a
 * testcontainers Postgres whose port is assigned at runtime cannot be injected via {@code -D}
 * sysprops, and a misconfigured fixture would silently fall back to SQLite (a false green).
 * <p>
 * The {@code pgIntegrationTest} Gradle task therefore starts a fixed-port PostgreSQL 17 container
 * BEFORE this JVM forks (the "build-launched container whose coordinates are exported as env"
 * mechanism) and exports the connection coordinates as REAL environment variables the test JVM's
 * {@code System.getenv} sees. {@link #assertReachedPostgres()} runs as the FIRST assertion in every
 * PG test so a silent SQLite fallback fails loudly instead of passing on the wrong backend.
 */
@Tag("pg-integration")
@ExtendWith(VertxExtension.class)
public abstract class PostgresIntegrationTest {

    private static final Log log = LogFactory.get();

    @BeforeAll
    static void requirePostgresBackend() {
        // Fail the whole PG suite loudly if the harness did not select Postgres.
        Assertions.assertTrue(Config.isPostgres(),
                "PG integration suite reached a non-Postgres backend (DB_TYPE=" + Config.DB_TYPE
                        + "). The build-launched Postgres container did not export its env correctly, "
                        + "or the suite silently fell back to SQLite.");
        Assertions.assertFalse(Config.isSqlite(),
                "PG integration suite unexpectedly on SQLite -- silent fallback would produce a false green.");
    }

    /**
     * Asserts the running test actually reached the testcontainers Postgres (not a silent SQLite
     * fallback): {@code Config.isPostgres()} is true AND the live connection URL points at the
     * container's host/port. Call this at the top of every PG integration test body.
     */
    protected Future<Void> assertReachedPostgres(Vertx vertx) {
        Assertions.assertTrue(Config.isPostgres(), "Config.isPostgres() must be true in the PG suite");
        Assertions.assertEquals("postgres", Config.DB_TYPE);
        // The client is configured to reach the container via the exported host/port. Prove that
        // configuration IS the container's coordinates before we even talk to it.
        Assertions.assertEquals("127.0.0.1", Config.DB_HOST,
                "Client must be configured for the container host exported by the Gradle task");
        Assertions.assertTrue(Config.DB_PORT >= 1024,
                "Client must be configured for the container's exported host port, was: " + Config.DB_PORT);

        SqlClient sqlClient = DataVerticle.createSqlClient(vertx, DataVerticle.getSqlConnectOptions());
        return sqlClient.query("""
                        SELECT current_database() AS db,
                               version() AS version
                        """).execute()
                .map(rs -> {
                    Row row = rs.iterator().next();
                    // version() proves a real Postgres server (SQLite has no such function, so a
                    // silent SQLite fallback could never reach this row).
                    Assertions.assertEquals(Config.DB_NAME, row.getString("db"),
                            "Connected database must match the container's DB_NAME");
                    String version = row.getString("version");
                    Assertions.assertTrue(version != null && version.startsWith("PostgreSQL 17"),
                            "Must be the testcontainers PostgreSQL 17, was: " + version);
                    log.info("PG integration reached: host={} port={} db={} server={}",
                            Config.DB_HOST, Config.DB_PORT, row.getString("db"), version);
                    return (Void) null;
                })
                .eventually(sqlClient::close);
    }

    /**
     * Truncates all telegram-files tables between tests so each test starts from a clean schema-
     * present state (tables were created by the DataVerticle deploy).
     */
    @AfterEach
    void truncateBetweenTests(Vertx vertx, VertxTestContext testContext) {
        SqlClient sqlClient = DataVerticle.createSqlClient(vertx, DataVerticle.getSqlConnectOptions());
        // Drop every public table so the next test's DataVerticle deploy re-creates and re-migrates
        // from a genuinely empty database, exercising the fresh-init path each time.
        sqlClient.query("""
                        SELECT tablename FROM pg_tables WHERE schemaname = 'public'
                        """).execute()
                .compose(rs -> {
                    List<String> tables = new java.util.ArrayList<>();
                    rs.forEach(row -> tables.add(row.getString("tablename")));
                    if (tables.isEmpty()) {
                        return Future.succeededFuture();
                    }
                    String drop = "DROP TABLE IF EXISTS "
                            + String.join(", ", tables.stream().map(t -> "\"" + t + "\"").toList())
                            + " CASCADE";
                    return sqlClient.query(drop).execute().mapEmpty();
                })
                .eventually(sqlClient::close)
                .onComplete(testContext.succeedingThenComplete());
    }

    /**
     * Sanity self-check that the harness is genuinely on Postgres 17. This is not a ported test --
     * it is the realness canary for the PG fixture itself.
     */
    @Test
    void harnessReachesRealPostgres17(Vertx vertx, VertxTestContext testContext) {
        assertReachedPostgres(vertx).onComplete(testContext.succeedingThenComplete());
    }
}
