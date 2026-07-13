package telegram.files.http;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import io.vertx.sqlclient.templates.SqlTemplate;
import telegram.files.DataVerticle;
import telegram.files.TelegramVerticle;
import telegram.files.TelegramVerticles;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prometheus text-exposition renderer for {@code GET /api/metrics} (and the {@code /metrics} alias).
 * <p>
 * Cardinality is BOUNDED by construction: the only label used is {@code status}, whose domain is the
 * fixed {@link telegram.files.repository.FileRecord.DownloadStatus} enum (7 values). There is NO
 * per-file, per-message, or per-account label — a metric with unbounded label cardinality is the
 * classic Prometheus footgun and is deliberately avoided. Account-level gauges are emitted WITHOUT a
 * label (aggregate counts), so the series count is constant regardless of how many files or accounts
 * exist. The content type is the Prometheus text format {@code text/plain; version=0.0.4}.
 */
public final class MetricsService {

    private static final Log log = LogFactory.get();

    public static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    // Fixed metric NAMES (explicit, stable). Bounded set — no dynamic metric names.
    static final String M_FILES_BY_STATUS = "telegram_files_download_files";
    static final String M_ACCOUNTS_TOTAL = "telegram_files_accounts_total";
    static final String M_ACCOUNTS_AUTHORIZED = "telegram_files_accounts_authorized";
    static final String M_UP = "telegram_files_up";

    private MetricsService() {
    }

    /** Render the Prometheus exposition text. Never fails: on a DB error, status gauges are omitted. */
    public static Future<String> render() {
        return statusCounts()
                .otherwise(err -> {
                    log.warn("Metrics status query failed: %s".formatted(err.getMessage()));
                    return new LinkedHashMap<>();
                })
                .map(MetricsService::format);
    }

    private static String format(Map<String, Long> statusCounts) {
        StringBuilder sb = new StringBuilder();

        sb.append("# HELP ").append(M_UP).append(" 1 if the telegram-files app is up.\n");
        sb.append("# TYPE ").append(M_UP).append(" gauge\n");
        sb.append(M_UP).append(" 1\n");

        sb.append("# HELP ").append(M_FILES_BY_STATUS)
                .append(" Number of file_record rows by download_status (thumbnails excluded).\n");
        sb.append("# TYPE ").append(M_FILES_BY_STATUS).append(" gauge\n");
        // Emit EVERY enum status (0 when absent) so the series set is fixed/bounded and stable.
        for (var st : telegram.files.repository.FileRecord.DownloadStatus.values()) {
            long v = statusCounts.getOrDefault(st.name(), 0L);
            sb.append(M_FILES_BY_STATUS).append("{status=\"").append(st.name()).append("\"} ")
                    .append(v).append('\n');
        }

        long total = 0;
        long authorized = 0;
        for (TelegramVerticle tv : TelegramVerticles.getAll()) {
            total++;
            if (tv.authorized) {
                authorized++;
            }
        }
        sb.append("# HELP ").append(M_ACCOUNTS_TOTAL).append(" Number of configured Telegram accounts.\n");
        sb.append("# TYPE ").append(M_ACCOUNTS_TOTAL).append(" gauge\n");
        sb.append(M_ACCOUNTS_TOTAL).append(' ').append(total).append('\n');

        sb.append("# HELP ").append(M_ACCOUNTS_AUTHORIZED).append(" Number of authorized Telegram accounts.\n");
        sb.append("# TYPE ").append(M_ACCOUNTS_AUTHORIZED).append(" gauge\n");
        sb.append(M_ACCOUNTS_AUTHORIZED).append(' ').append(authorized).append('\n');

        return sb.toString();
    }

    /** Bounded status → count map (thumbnails excluded). Groups by the fixed download_status domain. */
    private static Future<Map<String, Long>> statusCounts() {
        if (DataVerticle.pool == null) {
            return Future.succeededFuture(new LinkedHashMap<>());
        }
        return SqlTemplate.forQuery(DataVerticle.pool, """
                        SELECT download_status AS status, COUNT(*) AS c
                        FROM file_record
                        WHERE type != 'thumbnail'
                        GROUP BY download_status
                        """)
                .execute(Map.of())
                .map(rs -> {
                    Map<String, Long> out = new LinkedHashMap<>();
                    rs.forEach(row -> {
                        String status = row.getString("status");
                        if (status != null) {
                            out.put(status, row.getLong("c"));
                        }
                    });
                    return out;
                });
    }
}
