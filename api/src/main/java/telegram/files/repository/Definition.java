package telegram.files.repository;

import cn.hutool.core.lang.Version;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;

import java.util.TreeMap;
import java.util.stream.Stream;

public interface Definition {

    Log log = LogFactory.get();

    String getScheme();

    default TreeMap<Version, String[]> getMigrations() {
        return new TreeMap<>();
    }

    default String[] getIndexes() {
        return new String[0];
    }

    default Future<Void> createTable(SqlClient sqlClient) {
        return sqlClient
                .query(getScheme())
                .execute()
                .onFailure(err -> log.error("Failed to create table: %s".formatted(err.getMessage())))
                .mapEmpty()
                .compose(v -> executeIndexes(sqlClient, getIndexes()));
    }

    default Future<Void> migrate(SqlClient sqlClient, Version lastVersion, Version currentVersion) {
        TreeMap<Version, String[]> migrations = getMigrations();
        if (migrations.isEmpty()) {
            return Future.succeededFuture();
        }
        String[] statements = migrations.subMap(lastVersion, false, currentVersion, true).values()
                .stream()
                .flatMap(Stream::of)
                .toArray(String[]::new);
        return executeStatementsTolerant(sqlClient, statements)
                .compose(v -> executeIndexes(sqlClient, getIndexes()));
    }

    private Future<Void> executeIndexes(SqlClient sqlClient, String[] statements) {
        Future<Void> chain = Future.succeededFuture();
        for (String sql : statements) {
            chain = chain.compose(v -> sqlClient.query(sql)
                    .execute()
                    .<Void>mapEmpty()
                    .recover(err -> {
                        log.debug("Index creation skipped (will retry in migration): %s".formatted(sql));
                        return Future.succeededFuture();
                    }));
        }
        return chain;
    }

    private Future<Void> executeStatementsTolerant(SqlClient sqlClient, String[] statements) {
        Future<Void> chain = Future.succeededFuture();
        for (String sql : statements) {
            chain = chain.compose(v -> sqlClient.query(sql)
                    .execute()
                    .<Void>mapEmpty()
                    .recover(err -> {
                        String msg = err.getMessage() != null ? err.getMessage().toLowerCase() : "";
                        if (msg.contains("duplicate column") || msg.contains("duplicate key name")
                                || msg.contains("already exists")) {
                            log.debug("Migration statement already applied (tolerating): %s".formatted(sql));
                            return Future.succeededFuture();
                        }
                        log.error("Failed to apply migration: %s".formatted(sql), err);
                        return Future.failedFuture(err);
                    }));
        }
        return chain;
    }
}
