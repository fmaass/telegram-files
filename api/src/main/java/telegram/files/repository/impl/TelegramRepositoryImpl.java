package telegram.files.repository.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.templates.SqlTemplate;
import telegram.files.core.Config;
import telegram.files.repository.TelegramRecord;
import telegram.files.repository.TelegramRepository;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class TelegramRepositoryImpl extends AbstractSqlRepository implements TelegramRepository {

    private static final Log log = LogFactory.get();

    public TelegramRepositoryImpl(SqlClient sqlClient) {
        super(sqlClient);
    }

    @Override
    public String getRootPath() {
        return Config.TELEGRAM_ROOT + File.separator + UUID.randomUUID();
    }

    @Override
    public Future<TelegramRecord> create(TelegramRecord telegramRecord) {
        return SqlTemplate
                .forUpdate(sqlClient, "INSERT INTO telegram_record(id, first_name, root_path, proxy) VALUES (#{id}, #{first_name}, #{root_path}, #{proxy})")
                .mapFrom(TelegramRecord.PARAM_MAPPER)
                .execute(telegramRecord)
                .map(_ -> telegramRecord)
                .onSuccess(_ -> log.trace("Successfully created telegram record: %s".formatted(telegramRecord.id())))
                .onFailure(
                        err -> log.error("Failed to create telegram record: %s".formatted(err.getMessage()))
                );
    }

    @Override
    public Future<TelegramRecord> update(TelegramRecord telegramRecord) {
        return SqlTemplate
                .forUpdate(sqlClient, "UPDATE telegram_record SET first_name = #{first_name}, root_path = #{root_path}, proxy = #{proxy} WHERE id = #{id}")
                .mapFrom(TelegramRecord.PARAM_MAPPER)
                .execute(telegramRecord)
                .map(_ -> telegramRecord)
                .onSuccess(_ -> log.debug("Successfully updated telegram record: %s".formatted(telegramRecord.id())))
                .onFailure(
                        err -> log.error("Failed to update telegram record: %s".formatted(err.getMessage()))
                );
    }

    @Override
    public Future<Void> delete(long id) {
        return SqlTemplate
                .forUpdate(sqlClient, "DELETE FROM telegram_record WHERE id = #{id}")
                .execute(MapUtil.of("id", id))
                .onFailure(err -> log.error("Failed to delete telegram record: %s".formatted(err.getMessage())))
                .mapEmpty();
    }

    @Override
    public Future<TelegramRecord> getById(long id) {
        return SqlTemplate
                .forQuery(sqlClient, "SELECT * FROM telegram_record WHERE id = #{id} limit 1")
                .mapTo(TelegramRecord.ROW_MAPPER)
                .execute(MapUtil.of("id", id))
                .map(rs -> rs.size() == 0 ? null : rs.iterator().next());
    }

    @Override
    public Future<List<TelegramRecord>> getAll() {
        return SqlTemplate
                .forQuery(sqlClient, "SELECT * FROM telegram_record ORDER BY id")
                .mapTo(TelegramRecord.ROW_MAPPER)
                .execute(Collections.emptyMap())
                .map(CollUtil::newArrayList);
    }
}
