package telegram.files.repository;

import io.vertx.sqlclient.templates.RowMapper;
import io.vertx.sqlclient.templates.TupleMapper;
import telegram.files.core.Config;

import java.util.Map;

public record SettingRecord(String key, String value) {

    public static final String KEY_FIELD = Config.isMysql() ? "`key`" : "key";

    public static final String SCHEME = """
            CREATE TABLE IF NOT EXISTS setting_record
            (
                %s      VARCHAR(255) PRIMARY KEY,
                value   TEXT
            )
            """.formatted(KEY_FIELD);

    public static class SettingRecordDefinition implements Definition {
        @Override
        public String getScheme() {
            return SCHEME;
        }
    }

    public static RowMapper<SettingRecord> ROW_MAPPER = row ->
            new SettingRecord(row.getString("key"),
                    row.getString("value")
            );

    public static TupleMapper<SettingRecord> PARAM_MAPPER = TupleMapper.mapper(r ->
            Map.ofEntries(Map.entry("key", r.key()),
                    Map.entry("value", r.value())
            ));
}
