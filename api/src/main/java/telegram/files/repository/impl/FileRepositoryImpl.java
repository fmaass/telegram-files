package telegram.files.repository.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.IterUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.SqlResult;
import io.vertx.sqlclient.templates.SqlTemplate;
import org.jooq.lambda.tuple.Tuple;
import org.jooq.lambda.tuple.Tuple3;
import telegram.files.Config;
import telegram.files.DataVerticle;
import telegram.files.MessyUtils;
import telegram.files.TelegramVerticle;
import telegram.files.TelegramVerticles;
import telegram.files.repository.FileRecord;
import telegram.files.repository.FileRepository;
import telegram.files.repository.SettingAutoRecords;
import telegram.files.repository.SettingKey;
import telegram.files.repository.TransferOperationRecord;
import org.drinkless.tdlib.TdApi;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class FileRepositoryImpl extends AbstractSqlRepository implements FileRepository {

    private static final Log log = LogFactory.get();

    private static final Map<String, String> SORT_COLUMN_MAP = Map.of(
            "id", "id",
            "message_id", "message_id",
            "date", "date",
            "size", "size",
            "file_name", "file_name",
            "completion_date", "completion_date",
            "download_status", "download_status",
            "type", "type",
            "reaction_count", "reaction_count"
    );

    private final Pool pool;

    public FileRepositoryImpl(SqlClient sqlClient) {
        super(sqlClient);
        // Transactions (atomic claim) require a Pool. In production and every test the repository is
        // constructed with DataVerticle.pool (a Pool); guard so a non-Pool client fails loudly at
        // claim time rather than silently skipping the transaction.
        this.pool = (sqlClient instanceof Pool p) ? p : null;
    }

    @Override
    public Future<FileRecord> create(FileRecord fileRecord) {
        return SqlTemplate
                .forUpdate(sqlClient, """
                        INSERT INTO file_record(id, unique_id, telegram_id, chat_id, message_id, media_album_id, date, has_sensitive_content,
                                                size, downloaded_size,
                                                type, mime_type,
                                                file_name, thumbnail, thumbnail_unique_id, caption, extra, local_path,
                                                download_status, start_date, transfer_status, tags, thread_chat_id, message_thread_id, reaction_count,
                                                scan_state, download_priority, queued_at)
                        values (#{id}, #{unique_id}, #{telegram_id}, #{chat_id}, #{message_id}, #{media_album_id}, #{date},
                                #{has_sensitive_content}, #{size}, #{downloaded_size}, #{type},
                                #{mime_type}, #{file_name}, #{thumbnail}, #{thumbnail_unique_id}, #{caption}, #{extra}, #{local_path},
                                #{download_status}, #{start_date}, #{transfer_status}, #{tags}, #{thread_chat_id}, #{message_thread_id}, #{reaction_count},
                                #{scan_state}, #{download_priority}, #{queued_at})
                        """)
                .mapFrom(FileRecord.PARAM_MAPPER)
                .execute(fileRecord)
                .map(r -> fileRecord)
                .compose(r -> {
                    if (Objects.equals(r.type(), "thumbnail")) {
                        return Future.succeededFuture(r);
                    } else {
                        return this.updateAlbumDataByMediaAlbumId(fileRecord.mediaAlbumId(), fileRecord.caption(), fileRecord.reactionCount()).map(r);
                    }
                })
                .onSuccess(r -> log.trace("Successfully created file record: %s".formatted(fileRecord.id())))
                .onFailure(err -> {
                    String msg = err.getMessage();
                    if (msg != null && (msg.contains("constraint") || msg.toLowerCase().contains("duplicate"))) {
                        log.debug("File record already exists (create race): %s".formatted(fileRecord.uniqueId()));
                    } else {
                        log.error(err, "Failed to create file record: %s".formatted(msg));
                    }
                });
    }

    @Override
    public Future<Boolean> createIfNotExist(FileRecord fileRecord) {
        return this.getByUniqueId(fileRecord.uniqueId())
                .compose(record -> {
                    if (record != null) {
                        // Backfill thread info if existing row has 0 but new record has it
                        if (record.messageThreadId() == 0 && fileRecord.messageThreadId() != 0) {
                            return SqlTemplate
                                    .forUpdate(sqlClient, """
                                            UPDATE file_record
                                            SET message_thread_id = #{messageThreadId},
                                                thread_chat_id = #{threadChatId}
                                            WHERE unique_id = #{uniqueId}
                                              AND message_thread_id = 0
                                            """)
                                    .execute(MapUtil.ofEntries(
                                            MapUtil.entry("uniqueId", record.uniqueId()),
                                            MapUtil.entry("messageThreadId", fileRecord.messageThreadId()),
                                            MapUtil.entry("threadChatId", fileRecord.threadChatId())
                                    ))
                                    .map(false)
                                    .onFailure(err -> log.error("Failed to backfill thread info: %s".formatted(err.getMessage())));
                        }
                        return Future.succeededFuture(false);
                    }
                    return this.create(fileRecord).map(true)
                            .recover(err -> this.getByUniqueId(fileRecord.uniqueId())
                                    .compose(existing -> existing != null
                                            ? Future.succeededFuture(false)
                                            : Future.failedFuture(err)));
                });
    }

    @Override
    public Future<Tuple3<List<FileRecord>, Long, Long>> getFiles(long chatId, Map<String, String> filter) {
        log.trace("FileRepositoryImpl.getFiles received filter: %s".formatted(filter));
        String search = filter.get("search");
        String type = filter.get("type");
        String downloadStatus = filter.get("downloadStatus");
        String downloadStatuses = filter.get("downloadStatuses"); // Multi-select statuses
        log.trace("FileRepositoryImpl.getFiles extracted downloadStatuses: %s".formatted(downloadStatuses));
        String transferStatus = filter.get("transferStatus");
        List<String> tags = StrUtil.split(filter.get("tags"), ",");
        long messageThreadId = Convert.toLong(filter.get("messageThreadId"), 0L);
        String dateType = filter.get("dateType");
        String dateRange = filter.get("dateRange");
        String sizeRange = filter.get("sizeRange");
        String sizeUnit = filter.get("sizeUnit");
        String sortRaw = filter.get("sort");
        String sortColumn = sortRaw != null ? SORT_COLUMN_MAP.get(sortRaw) : null;
        String orderRaw = filter.get("order");
        boolean ascending = orderRaw != null && orderRaw.equalsIgnoreCase("asc");

        Long fromMessageId = Convert.toLong(filter.get("fromMessageId"), 0L);
        int limit = Math.min(Math.max(Convert.toInt(filter.get("limit"), 20), 1), 1000);

        String whereClause = "type != 'thumbnail'";
        Map<String, Object> params = new HashMap<>();
        params.put("limit", limit);
        if (chatId != 0) {
            whereClause += " AND chat_id = #{chatId}";
            params.put("chatId", chatId);
        }
        if (StrUtil.isNotBlank(search)) {
            whereClause += " AND (file_name LIKE #{search} OR caption LIKE #{search})";
            params.put("search", "%%" + search + "%%");
        }
        if (StrUtil.isNotBlank(type) && !Objects.equals(type, "all")) {
            if (Objects.equals(type, "media")) {
                whereClause += " AND type IN ('photo', 'video')";
            } else {
                whereClause += " AND type = #{type}";
                params.put("type", type);
            }
        }
        // Handle multi-select download statuses (validated against enum to prevent SQL injection)
        if (StrUtil.isNotBlank(downloadStatuses)) {
            List<String> statusList = StrUtil.split(downloadStatuses, ",").stream()
                    .map(String::trim)
                    .filter(s -> {
                        try { FileRecord.DownloadStatus.valueOf(s); return true; }
                        catch (IllegalArgumentException e) { return false; }
                    })
                    .toList();
            if (CollUtil.isNotEmpty(statusList)) {
                String statusClause = statusList.stream()
                        .map(s -> "'" + s + "'")
                        .collect(Collectors.joining(", "));
                whereClause += " AND download_status IN (%s)".formatted(statusClause);
            }
        } else if (StrUtil.isNotBlank(downloadStatus)) {
            // Fallback to single status filter for backward compatibility
            whereClause += " AND download_status = #{downloadStatus}";
            params.put("downloadStatus", downloadStatus);
        }
        if (StrUtil.isNotBlank(transferStatus)) {
            whereClause += " AND transfer_status = #{transferStatus}";
            params.put("transferStatus", transferStatus);
        }
        if (CollUtil.isNotEmpty(tags)) {
            List<String> tagConditions = new ArrayList<>();
            for (int i = 0; i < tags.size(); i++) {
                String tag = tags.get(i);
                if (StrUtil.isNotBlank(tag)) {
                    String paramName = "tag" + i;
                    tagConditions.add("tags LIKE #{" + paramName + "}");
                    params.put(paramName, "%%" + tag + "%%");
                }
            }
            if (!tagConditions.isEmpty()) {
                whereClause += " AND (" + String.join(" OR ", tagConditions) + ")";
            }
        }
        if (messageThreadId != 0) {
            whereClause += " AND message_thread_id = #{messageThreadId}";
            params.put("messageThreadId", messageThreadId);
        }
        if (StrUtil.isNotBlank(dateType) && StrUtil.isNotBlank(dateRange)) {
            String[] dates = dateRange.split(",");
            if (dates.length == 2) {
                long startTime = LocalDate.parse(dates[0], DateTimeFormatter.ISO_DATE)
                        .atStartOfDay()
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                long endTime = LocalDate.parse(dates[1], DateTimeFormatter.ISO_DATE)
                        .atTime(LocalTime.MAX)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                if (Objects.equals(dateType, "sent")) {
                    whereClause += " AND date >= #{startTime} AND date <= #{endTime}";
                    startTime = startTime / 1000;
                    endTime = endTime / 1000;
                } else {
                    whereClause += " AND completion_date >= #{startTime} AND completion_date <= #{endTime}";
                }
                params.put("startTime", startTime);
                params.put("endTime", endTime);
            }
        }
        if (StrUtil.isNotBlank(sizeRange) && StrUtil.isNotBlank(sizeUnit)) {
            String[] sizes = sizeRange.split(",");
            if (sizes.length == 2) {
                long minSize = MessyUtils.convertToByte(Convert.toLong(sizes[0]), sizeUnit);
                long maxSize = MessyUtils.convertToByte(Convert.toLong(sizes[1]), sizeUnit);
                whereClause += " AND size >= #{minSize} AND size <= #{maxSize}";
                params.put("minSize", minSize);
                params.put("maxSize", maxSize);
            }
        }
        String orderDir = ascending ? "ASC" : "DESC";
        String orderBy = "message_id DESC";
        boolean customSort = sortColumn != null && orderRaw != null;
        if (customSort) {
            orderBy = "%s %s".formatted(sortColumn, orderDir);
            if (Objects.equals(sortColumn, "completion_date")) {
                whereClause += " AND completion_date IS NOT NULL";
            }
        }
        String countClause = whereClause;
        if (fromMessageId > 0) {
            params.put("fromMessageId", fromMessageId);
            if (customSort) {
                long fromSortField = Convert.toLong(filter.get("fromSortField"));
                params.put("fromSortField", fromSortField);
                String cmp = ascending ? ">" : "<";
                whereClause += " AND (%s %s #{fromSortField} OR (%s = #{fromSortField} AND message_id < #{fromMessageId}))".formatted(
                        sortColumn, cmp, sortColumn);
            } else {
                whereClause += " AND message_id < #{fromMessageId}";
            }
        }
        log.trace("Get files with where: %s params: %s".formatted(whereClause, params));
        return Future.all(
                SqlTemplate
                        .forQuery(sqlClient, """
                                SELECT * FROM file_record WHERE %s ORDER BY %s LIMIT #{limit}
                                """.formatted(whereClause, orderBy))
                        .mapTo(FileRecord.ROW_MAPPER)
                        .execute(params)
                        .onFailure(err -> log.error("Failed to get file record: %s".formatted(err.getMessage())))
                        .map(IterUtil::toList)
                ,
                SqlTemplate
                        .forQuery(sqlClient, """
                                SELECT COUNT(*) FROM file_record WHERE %s
                                """.formatted(countClause))
                        .mapTo(rs -> rs.getLong(0))
                        .execute(params)
                        .onFailure(err -> log.error("Failed to get file record count: %s".formatted(err.getMessage())))
                        .map(rs -> rs.size() > 0 ? rs.iterator().next() : 0L)
        ).map(r -> {
            List<FileRecord> fileRecords = r.resultAt(0);
            long nextFromMessageId = CollUtil.isEmpty(fileRecords) ? 0 : fileRecords.getLast().messageId();
            return Tuple.tuple(fileRecords, nextFromMessageId, r.resultAt(1));
        });
    }

    @Override
    public Future<Map<String, FileRecord>> getFilesByUniqueId(List<String> uniqueIds) {
        uniqueIds = uniqueIds.stream()
                .filter(StrUtil::isNotBlank)
                .distinct().collect(Collectors.toList());
        if (CollUtil.isEmpty(uniqueIds)) {
            return Future.succeededFuture(new HashMap<>());
        }
        String uniqueIdPlaceholders = IntStream.range(0, uniqueIds.size())
                .mapToObj(i -> "#{uniqueId" + i + "}")
                .collect(Collectors.joining(","));
        Map<String, Object> params = new HashMap<>();
        for (int i = 0; i < uniqueIds.size(); i++) {
            params.put("uniqueId" + i, uniqueIds.get(i));
        }
        return SqlTemplate
                .forQuery(sqlClient, """
                        SELECT * FROM file_record WHERE unique_id IN (%s)
                        """.formatted(uniqueIdPlaceholders))
                .mapTo(FileRecord.ROW_MAPPER)
                .execute(params)
                .onFailure(err -> log.error("Failed to get file record: %s".formatted(err.getMessage())))
                .map(rs -> {
                    Map<String, FileRecord> map = new HashMap<>();
                    for (FileRecord record : rs) {
                        map.put(record.uniqueId(), record);
                    }
                    return map;
                });
    }

    @Override
    public Future<FileRecord> getByPrimaryKey(int fileId, String uniqueId) {
        return SqlTemplate
                .forQuery(sqlClient, """
                        SELECT * FROM file_record WHERE unique_id = #{uniqueId}
                        """)
                .mapTo(FileRecord.ROW_MAPPER)
                .execute(Map.of("uniqueId", uniqueId))
                .onFailure(err -> log.error("Failed to get file record: %s".formatted(err.getMessage()))
                )
                .map(rs -> rs.size() > 0 ? rs.iterator().next() : null);
    }

    @Override
    public Future<FileRecord> getByUniqueId(String uniqueId) {
        return SqlTemplate
                .forQuery(sqlClient, """
                        SELECT * FROM file_record WHERE unique_id = #{uniqueId} LIMIT 1
                        """)
                .mapTo(FileRecord.ROW_MAPPER)
                .execute(Map.of("uniqueId", uniqueId))
                .onFailure(err -> log.error("Failed to get file record: %s".formatted(err.getMessage()))
                )
                .map(rs -> rs.size() > 0 ? rs.iterator().next() : null);
    }

    @Override
    public Future<FileRecord> getMainFileByThread(long telegramId, long threadChatId, long messageThreadId) {
        return SqlTemplate
                .forQuery(sqlClient, """
                        SELECT *
                        FROM file_record
                        WHERE telegram_id = #{telegramId}
                          AND thread_chat_id = #{threadChatId}
                          AND message_thread_id = #{messageThreadId}
                          AND chat_id != #{threadChatId}
                          AND type != 'thumbnail'
                        LIMIT 1
                        """)
                .mapTo(FileRecord.ROW_MAPPER)
                .execute(Map.of("telegramId", telegramId, "threadChatId", threadChatId, "messageThreadId", messageThreadId))
                .onFailure(err -> log.error("Failed to get main file record: %s".formatted(err.getMessage()))
                )
                .map(rs -> rs.size() > 0 ? rs.iterator().next() : null);
    }

    @Override
    public Future<String> getCaptionByMediaAlbumId(long mediaAlbumId) {
        if (mediaAlbumId <= 0) {
            return Future.succeededFuture(null);
        }
        return SqlTemplate
                .forQuery(sqlClient, """
                        SELECT caption FROM file_record WHERE media_album_id = #{mediaAlbumId} LIMIT 1
                        """)
                .mapTo(row -> row.getString("caption"))
                .execute(Map.of("mediaAlbumId", mediaAlbumId))
                .map(rs -> rs.size() > 0 ? rs.iterator().next() : null)
                .onFailure(err -> log.error("Failed to get caption: %s".formatted(err.getMessage())));
    }

    @Override
    public Future<Long> getReactionCountByMediaAlbumId(long mediaAlbumId) {
        if (mediaAlbumId <= 0) {
            return Future.succeededFuture(0L);
        }
        return SqlTemplate
                .forQuery(sqlClient, """
                        SELECT reaction_count FROM file_record WHERE media_album_id = #{mediaAlbumId} LIMIT 1
                        """)
                .mapTo(row -> row.getLong("reaction_count"))
                .execute(Map.of("mediaAlbumId", mediaAlbumId))
                .map(rs -> rs.size() > 0 ? rs.iterator().next() : 0L)
                .onFailure(err -> log.error("Failed to get reaction count: %s".formatted(err.getMessage())));
    }

    @Override
    public Future<JsonObject> getDownloadStatistics(long telegramId) {
        return SqlTemplate
                .forQuery(sqlClient, """
                        SELECT COUNT(*)                                                                     AS total,
                               COUNT(CASE WHEN download_status = 'downloading' THEN 1 END)                  AS downloading,
                               COUNT(CASE WHEN download_status = 'paused' THEN 1 END)                       AS paused,
                               COUNT(CASE WHEN download_status = 'completed' OR download_status = 'downloaded' THEN 1 END) AS completed,
                               COUNT(CASE WHEN download_status = 'error' THEN 1 END)                        AS error,
                               COUNT(CASE WHEN download_status = 'idle' THEN 1 END)                         AS idle,
                               COUNT(CASE WHEN download_status = 'completed' and type = 'photo' THEN 1 END) AS photo,
                               COUNT(CASE WHEN download_status = 'completed' and type = 'video' THEN 1 END) AS video,
                               COUNT(CASE WHEN download_status = 'completed' and type = 'audio' THEN 1 END) AS audio,
                               COUNT(CASE WHEN download_status = 'completed' and type = 'file' THEN 1 END)  AS file
                        FROM file_record
                        WHERE telegram_id = #{telegramId} and type != 'thumbnail'
                        """)
                .mapTo(row -> {
                    JsonObject result = JsonObject.of();
                    result.put("total", row.getInteger("total"));
                    result.put("downloading", row.getInteger("downloading"));
                    result.put("paused", row.getInteger("paused"));
                    result.put("completed", row.getInteger("completed"));
                    result.put("error", row.getInteger("error"));
                    result.put("idle", row.getInteger("idle"));
                    result.put("photo", row.getInteger("photo"));
                    result.put("video", row.getInteger("video"));
                    result.put("audio", row.getInteger("audio"));
                    result.put("file", row.getInteger("file"));
                    return result;
                })
                .execute(Map.of("telegramId", telegramId))
                .map(rs -> rs.size() > 0 ? rs.iterator().next() : JsonObject.of())
                .onFailure(err -> log.error("Failed to get download statistics: %s".formatted(err.getMessage())));
    }

    @Override
    public Future<JsonObject> getChatDownloadStatistics(long telegramId, long chatId, Integer historySince) {
        // Validate parameters
        if (telegramId <= 0 || chatId == 0) {
            return Future.failedFuture(new IllegalArgumentException("Invalid telegramId or chatId"));
        }
        if (historySince != null && historySince < 0) {
            return Future.failedFuture(new IllegalArgumentException("historySince cannot be negative"));
        }
        
        // Build query with optional history cutoff filter (DRY - no duplication)
        String baseQuery = """
                SELECT COUNT(*)                                                                     AS total,
                       COUNT(CASE WHEN download_status = 'downloading' THEN 1 END)                  AS downloading,
                       COUNT(CASE WHEN download_status = 'paused' THEN 1 END)                       AS paused,
                       COUNT(CASE WHEN download_status = 'completed' OR download_status = 'downloaded' THEN 1 END) AS completed,
                       COUNT(CASE WHEN download_status = 'error' THEN 1 END)                        AS error,
                       COUNT(CASE WHEN download_status = 'idle' OR download_status = 'queued' THEN 1 END) AS idle
                FROM file_record
                WHERE telegram_id = #{telegramId} AND chat_id = #{chatId} AND type != 'thumbnail'
                """;
        String query = baseQuery + (historySince != null ? " AND date >= #{historySince}" : "");
        
        Map<String, Object> params = historySince != null ?
                Map.of("telegramId", telegramId, "chatId", chatId, "historySince", historySince) :
                Map.of("telegramId", telegramId, "chatId", chatId);
        
        return SqlTemplate
                .forQuery(sqlClient, query)
                .mapTo(row -> {
                    JsonObject result = JsonObject.of();
                    result.put("total", row.getInteger("total"));
                    result.put("downloading", row.getInteger("downloading"));
                    result.put("paused", row.getInteger("paused"));
                    result.put("completed", row.getInteger("completed"));
                    result.put("error", row.getInteger("error"));
                    result.put("idle", row.getInteger("idle"));
                    return result;
                })
                .execute(params)
                .map(rs -> rs.size() > 0 ? rs.iterator().next() : JsonObject.of())
                .onFailure(err -> log.error("Failed to get chat download statistics: %s".formatted(err.getMessage())));
    }

    @Override
    public Future<JsonObject> getDownloadStatistics() {
        return SqlTemplate
                .forQuery(sqlClient, """
                        SELECT COUNT(CASE WHEN download_status = 'downloading' OR download_status = 'queued' THEN 1 END) AS downloading,
                               COUNT(CASE WHEN download_status = 'completed' OR download_status = 'downloaded' THEN 1 END) AS completed,
                               SUM(CASE WHEN download_status = 'completed' THEN size ELSE 0 END)            AS downloaded_size
                        FROM file_record
                        WHERE type != 'thumbnail'
                        """)
                .mapTo(row -> {
                    JsonObject result = JsonObject.of();
                    result.put("downloading", row.getInteger("downloading"));
                    result.put("completed", row.getInteger("completed"));
                    result.put("downloadedSize", Objects.requireNonNullElse(row.getLong("downloaded_size"), 0));
                    return result;
                })
                .execute(Map.of())
                .map(rs -> rs.size() > 0 ? rs.iterator().next() : JsonObject.of())
                .onFailure(err -> log.error("Failed to get download statistics: %s".formatted(err.getMessage())));
    }

    @Override
    public Future<JsonArray> getCompletedRangeStatistics(long telegramId, long startTime, long endTime, int timeRange) {
        String query;
        if (Config.isSqlite()) {
            query = """
                    SELECT strftime(
                                       CASE
                                           WHEN #{timeRange} = 1 THEN '%Y-%m-%d %H:%M'
                                           WHEN #{timeRange} = 2 THEN '%Y-%m-%d %H:00'
                                           WHEN #{timeRange} IN (3, 4) THEN '%Y-%m-%d'
                                       END,
                                       datetime(completion_date / 1000, 'unixepoch'),
                                       'localtime'
                               )        AS time,
                               COUNT(*) AS total
                        FROM file_record
                        WHERE telegram_id = #{telegramId}
                          AND completion_date IS NOT NULL
                          AND completion_date >= #{startTime}
                          AND completion_date <= #{endTime}
                          AND type != 'thumbnail'
                        GROUP BY time
                        ORDER BY time;
                    """;
        } else if (Config.isPostgres()) {
            query = """
                    SELECT TO_CHAR(
                               TO_TIMESTAMP(completion_date / 1000),
                               CASE
                                   WHEN #{timeRange} = 1 THEN 'YYYY-MM-DD HH24:MI'
                                   WHEN #{timeRange} = 2 THEN 'YYYY-MM-DD HH24:00'
                                   WHEN #{timeRange} IN (3, 4) THEN 'YYYY-MM-DD'
                               END
                           ) AS time,
                           COUNT(*) AS total
                    FROM file_record
                    WHERE telegram_id = #{telegramId}
                      AND completion_date IS NOT NULL
                      AND completion_date >= #{startTime}
                      AND completion_date <= #{endTime}
                      AND type != 'thumbnail'
                    GROUP BY time
                    ORDER BY time;
                    """;
        } else {
            query = """
                    SELECT DATE_FORMAT(
                               FROM_UNIXTIME(completion_date / 1000),
                               CASE
                                   WHEN #{timeRange} = 1 THEN '%Y-%m-%d %H:%i'
                                   WHEN #{timeRange} = 2 THEN '%Y-%m-%d %H:00'
                                   WHEN #{timeRange} IN (3, 4) THEN '%Y-%m-%d'
                               END
                           ) AS time,
                           COUNT(*) AS total
                    FROM file_record
                    WHERE telegram_id = #{telegramId}
                      AND completion_date IS NOT NULL
                      AND completion_date >= #{startTime}
                      AND completion_date <= #{endTime}
                      AND type != 'thumbnail'
                    GROUP BY time
                    ORDER BY time;
                    """;
        }
        return SqlTemplate
                .forQuery(sqlClient, query)
                .mapTo(row -> new JsonObject()
                        .put("time", row.getString("time"))
                        .put("total", row.getInteger("total"))
                )
                .execute(Map.of("telegramId", telegramId, "startTime", startTime, "endTime", endTime, "timeRange", timeRange))
                .map(IterUtil::toList)
                .map(rs -> {
                    if (CollUtil.isEmpty(rs)) {
                        return JsonArray.of();
                    }
                    if (timeRange == 1) {
                        // Statistics grouped by five minutes
                        return rs.stream()
                                .peek(c -> c.put("time", MessyUtils.withGrouping5Minutes(
                                        DateUtil.parseLocalDateTime(c.getString("time"), DatePattern.NORM_DATETIME_MINUTE_PATTERN)
                                ).format(DatePattern.NORM_DATETIME_MINUTE_FORMATTER)))
                                .collect(Collectors.groupingBy(c -> c.getString("time"),
                                        Collectors.summingInt(c -> c.getInteger("total"))
                                ))
                                .entrySet().stream()
                                .map(e -> new JsonObject()
                                        .put("time", e.getKey())
                                        .put("total", e.getValue())
                                )
                                .sorted(Comparator.comparing(o -> o.getString("time")))
                                .collect(JsonArray::new, JsonArray::add, JsonArray::addAll);
                    } else {
                        JsonArray jsonArray = new JsonArray();
                        rs.forEach(jsonArray::add);
                        return jsonArray;
                    }
                })
                .onFailure(err -> log.error("Failed to get completed statistics: %s".formatted(err.getMessage())));
    }

    @Override
    public Future<Integer> countByStatus(long telegramId, FileRecord.DownloadStatus downloadStatus) {
        return SqlTemplate
                .forQuery(sqlClient, """
                        SELECT COUNT(*)
                        FROM file_record
                        WHERE telegram_id = #{telegramId}
                          AND download_status = #{downloadStatus}
                          AND type != 'thumbnail'
                        """)
                .mapTo(rs -> rs.getInteger(0))
                .execute(Map.of("telegramId", telegramId, "downloadStatus", downloadStatus.name()))
                .map(rs -> rs.size() > 0 ? rs.iterator().next() : 0)
                .onFailure(err -> log.error("Failed to count file record: %s".formatted(err.getMessage())));
    }

    @Override
    public Future<List<FileRecord>> getByDownloadStatus(long telegramId, FileRecord.DownloadStatus downloadStatus) {
        return SqlTemplate
                .forQuery(sqlClient, """
                        SELECT * FROM file_record
                        WHERE telegram_id = #{telegramId}
                          AND download_status = #{downloadStatus}
                          AND type != 'thumbnail'
                        """)
                .mapTo(FileRecord.ROW_MAPPER)
                .execute(Map.of("telegramId", telegramId, "downloadStatus", downloadStatus.name()))
                .map(IterUtil::toList)
                .onFailure(err -> log.error("Failed to get file records by download status: %s".formatted(err.getMessage())));
    }

    @Override
    public Future<JsonObject> countWithType(long telegramId, long chatId) {
        String whereClause = "type != 'thumbnail'";
        Map<String, Object> params = new HashMap<>();
        if (telegramId != -1L) {
            whereClause += " AND telegram_id = #{telegramId}";
            params.put("telegramId", telegramId);
        }
        if (chatId != -1L) {
            whereClause += " AND chat_id = #{chatId}";
            params.put("chatId", chatId);
        }
        return SqlTemplate
                .forQuery(sqlClient, """
                        SELECT type, COUNT(*) AS count
                        FROM file_record
                        WHERE %s
                        GROUP BY type
                        """.formatted(whereClause))
                .mapTo(row -> new JsonObject()
                        .put("type", row.getString("type"))
                        .put("count", row.getInteger("count"))
                )
                .execute(params)
                .map(rs -> {
                    JsonObject result = new JsonObject();
                    rs.forEach(item -> result.put(item.getString("type"), item.getInteger("count")));
                    // Calculate media types, which includes photo, video.
                    int mediaCount = rs.stream()
                            .filter(item -> Objects.equals(item.getString("type"), "photo") || Objects.equals(item.getString("type"), "video"))
                            .mapToInt(item -> item.getInteger("count"))
                            .sum();
                    result.put("media", mediaCount);
                    return result;
                })
                .onFailure(err -> log.error("Failed to count file record by type: %s".formatted(err.getMessage())));
    }

    @Override
    public Future<JsonObject> updateDownloadStatus(int fileId,
                                                   String uniqueId,
                                                   String localPath,
                                                   FileRecord.DownloadStatus downloadStatus,
                                                   Long completionDate) {
        if (StrUtil.isBlank(localPath) && downloadStatus == null) {
            return Future.succeededFuture(null);
        }
        return getByUniqueId(uniqueId)
                .compose(record -> {
                    if (record == null) {
                        return Future.succeededFuture(null);
                    }
                    boolean pathUpdated = !Objects.equals(record.localPath(), localPath);
                    boolean downloadStatusUpdated = downloadStatus != null && !record.isDownloadStatus(downloadStatus);
                    // A completion_date-only change (same status/path) must still persist — the startup
                    // completed-file sync sets a missing completion_date without changing status.
                    boolean completionDateUpdated = completionDate != null
                            && !Objects.equals(record.completionDate(), completionDate);
                    if (!pathUpdated && !downloadStatusUpdated && !completionDateUpdated) {
                        return Future.succeededFuture(null);
                    }

                    // Enforce the state machine: reject illegal download_status transitions at the
                    // boundary (canTransitionTo is authoritative; previously defined but unenforced).
                    // A path-only update (no status change) is always allowed.
                    if (downloadStatusUpdated && !isLegalTransition(record.downloadStatus(), downloadStatus)) {
                        log.warn("Rejected illegal download_status transition %s -> %s for %s"
                                .formatted(record.downloadStatus(), downloadStatus.name(), uniqueId));
                        return Future.succeededFuture(null);
                    }

                    String targetStatus = downloadStatusUpdated ? downloadStatus.name() : record.downloadStatus();
                    String targetLocalPath = pathUpdated ? localPath : record.localPath();

                    // Exact-state CAS: pin the EXACT observed prior status. If an external service (or a
                    // concurrent app writer) already moved the row off that state, rowCount is 0 and we
                    // do NOT clobber. downloadStatusPrior is nullable (legacy rows) so use IS NOT
                    // DISTINCT FROM semantics via a null-safe predicate.
                    // completion_date (D10): only overwrite when the caller supplies one, OR when
                    // transitioning INTO a terminal state; otherwise preserve the existing value so a
                    // completed row re-reported as e.g. downloading does not lose its date.
                    boolean writeCompletionDate = completionDate != null
                            || (downloadStatusUpdated && downloadStatus.isTerminal());
                    Long completionDateToWrite = completionDate != null ? completionDate : record.completionDate();

                    Map<String, Object> params = new HashMap<>();
                    params.put("fileId", fileId);
                    params.put("uniqueId", uniqueId);
                    params.put("localPath", targetLocalPath);
                    params.put("downloadStatus", targetStatus);
                    params.put("priorStatus", record.downloadStatus());
                    String casPredicate = record.downloadStatus() == null
                            ? "download_status IS NULL"
                            : "download_status = #{priorStatus}";
                    String completionAssign;
                    if (writeCompletionDate) {
                        params.put("completionDate", completionDateToWrite);
                        completionAssign = "completion_date = #{completionDate},";
                    } else {
                        completionAssign = "";
                    }

                    return SqlTemplate
                            .forUpdate(sqlClient, """
                                    UPDATE file_record SET id = #{fileId},
                                                           local_path = #{localPath},
                                                           %s
                                                           download_status = #{downloadStatus}
                                    WHERE unique_id = #{uniqueId} AND %s
                                    """.formatted(completionAssign, casPredicate))
                            .execute(params)
                            .onFailure(err ->
                                    log.error("Failed to update file record: %s".formatted(err.getMessage()))
                            )
                            .map(r -> {
                                if (r.rowCount() == 0) {
                                    // Lost the CAS (external reset / concurrent writer). No clobber.
                                    log.debug("updateDownloadStatus CAS no-op (prior state changed) for %s (expected %s)"
                                            .formatted(uniqueId, record.downloadStatus()));
                                    return null;
                                }
                                JsonObject result = JsonObject.of();
                                if (pathUpdated) {
                                    result.put("localPath", localPath);
                                    if (writeCompletionDate) {
                                        result.put("completionDate", completionDateToWrite);
                                    }
                                }
                                if (downloadStatusUpdated) {
                                    result.put("downloadStatus", downloadStatus.name());
                                }
                                log.debug("Successfully updated file record: %s, path: %s, status: %s, before: %s, %s"
                                        .formatted(uniqueId, localPath, targetStatus, record.localPath(), record.downloadStatus()));
                                return result;
                            });
                });
    }

    /**
     * Null-tolerant {@link FileRecord.DownloadStatus#canTransitionTo}. A null/unknown prior status is
     * treated as {@code idle} (mirrors the discovery/reconcile fallback) so genuinely legal recovery
     * paths are not blocked; an unknown TARGET can never occur (callers pass enum values).
     */
    private static boolean isLegalTransition(String priorStatusName, FileRecord.DownloadStatus target) {
        FileRecord.DownloadStatus prior;
        if (priorStatusName == null) {
            prior = FileRecord.DownloadStatus.idle;
        } else {
            try {
                prior = FileRecord.DownloadStatus.valueOf(priorStatusName);
            } catch (IllegalArgumentException e) {
                // Legacy/phantom value in DB (e.g. 'downloaded'): treat as idle for transition legality.
                prior = FileRecord.DownloadStatus.idle;
            }
        }
        return prior.canTransitionTo(target);
    }

    @Override
    public Future<JsonObject> updateTransferStatus(String uniqueId,
                                                   FileRecord.TransferStatus transferStatus,
                                                   String localPath) {
        if (StrUtil.isBlank(localPath) && transferStatus == null) {
            return Future.succeededFuture(null);
        }
        return getByUniqueId(uniqueId)
                .compose(record -> {
                    if (record == null) {
                        return Future.succeededFuture(null);
                    }
                    boolean pathUpdated = StrUtil.isNotBlank(localPath) && !Objects.equals(record.localPath(), localPath);
                    boolean transferStatusUpdated = !record.isTransferStatus(transferStatus);
                    if (!pathUpdated && !transferStatusUpdated) {
                        return Future.succeededFuture(null);
                    }

                    return SqlTemplate
                            .forUpdate(sqlClient, """
                                    UPDATE file_record
                                    SET transfer_status = #{transferStatus},
                                        local_path = #{localPath}
                                    WHERE unique_id = #{uniqueId}
                                    """)
                            .execute(MapUtil.ofEntries(MapUtil.entry("uniqueId", uniqueId),
                                    MapUtil.entry("localPath", pathUpdated ? localPath : record.localPath()),
                                    MapUtil.entry("transferStatus", transferStatusUpdated ? transferStatus.name() : record.transferStatus())
                            ))
                            .onFailure(err ->
                                    log.error("Failed to update file record: %s".formatted(err.getMessage()))
                            )
                            .map(r -> {
                                JsonObject result = JsonObject.of();
                                if (pathUpdated) {
                                    result.put("localPath", localPath);
                                }
                                if (transferStatusUpdated) {
                                    result.put("transferStatus", transferStatus.name());
                                }
                                log.debug("Successfully updated file record: %s, path: %s, transfer status: %s, before: %s %s"
                                        .formatted(uniqueId, localPath, transferStatus.name(), record.localPath(), record.transferStatus()));
                                return result;
                            });
                });
    }

    @Override
    public Future<JsonObject> finalizeTransfer(String uniqueId, String localPath) {
        if (StrUtil.isBlank(uniqueId)) {
            return Future.succeededFuture(null);
        }
        if (pool == null) {
            return Future.failedFuture(new IllegalStateException("finalizeTransfer requires a transactional Pool"));
        }
        // Exact-state CAS: transfer_status 'transferring' -> 'completed' (+ new local_path) in ONE
        // statement. A crash-replay / concurrent finalize whose row already moved off 'transferring'
        // matches no row (rowCount 0) => NO-OP, never double-applies (D6).
        // TOCTOU guard: download_status NOT IN ('processed','imported') so an external terminal write
        // that lands between reconciliation's select and this finalize is never clobbered.
        Map<String, Object> params = new HashMap<>();
        params.put("uniqueId", uniqueId);
        String localPathAssign = "";
        if (StrUtil.isNotBlank(localPath)) {
            params.put("localPath", localPath);
            localPathAssign = "local_path = #{localPath},";
        }
        String updateSql = """
                UPDATE file_record
                SET %s
                    transfer_status = 'completed'
                WHERE unique_id = #{uniqueId}
                  AND transfer_status = 'transferring'
                  AND download_status NOT IN ('processed', 'imported')
                """.formatted(localPathAssign);
        // ONE transaction: finalize the row AND delete its durable transfer_operation record together
        // (the operation is done — no crash residue left to reconcile).
        return pool.withTransaction(conn ->
                SqlTemplate.forUpdate(conn, updateSql)
                        .execute(params)
                        .compose(r -> {
                            if (r.rowCount() == 0) {
                                log.debug("finalizeTransfer CAS no-op (not transferring, or externally terminal) for %s".formatted(uniqueId));
                                return Future.succeededFuture((JsonObject) null);
                            }
                            return SqlTemplate.forUpdate(conn, """
                                            DELETE FROM transfer_operation WHERE unique_id = #{uniqueId}
                                            """)
                                    .execute(Map.of("uniqueId", uniqueId))
                                    .map(ignore -> {
                                        JsonObject result = JsonObject.of().put("transferStatus", "completed");
                                        if (StrUtil.isNotBlank(localPath)) {
                                            result.put("localPath", localPath);
                                        }
                                        return result;
                                    });
                        })
        ).onFailure(err -> log.error("finalizeTransfer failed for %s: %s".formatted(uniqueId, err.getMessage())));
    }

    @Override
    public Future<Void> recordTransferOperation(TransferOperationRecord operation) {
        if (operation == null || StrUtil.isBlank(operation.uniqueId())) {
            return Future.succeededFuture();
        }
        // Upsert on unique_id. Portable: DELETE-then-INSERT in one transaction (avoids dialect-specific
        // ON CONFLICT / ON DUPLICATE KEY syntax across SQLite/Postgres/MySQL).
        if (pool == null) {
            return Future.failedFuture(new IllegalStateException("recordTransferOperation requires a transactional Pool"));
        }
        Map<String, Object> params = new HashMap<>();
        params.put("uniqueId", operation.uniqueId());
        params.put("finalDestPath", operation.finalDestPath());
        params.put("stagingTempPath", operation.stagingTempPath());
        params.put("sourcePath", operation.sourcePath());
        params.put("sourceSize", operation.sourceSize());
        params.put("overwritePolicy", operation.overwritePolicy());
        params.put("createdAt", operation.createdAt() != null ? operation.createdAt() : System.currentTimeMillis());
        return pool.withTransaction(conn ->
                SqlTemplate.forUpdate(conn, "DELETE FROM transfer_operation WHERE unique_id = #{uniqueId}")
                        .execute(Map.of("uniqueId", operation.uniqueId()))
                        .compose(ignore -> SqlTemplate.forUpdate(conn, """
                                        INSERT INTO transfer_operation
                                            (unique_id, final_dest_path, staging_temp_path, source_path, source_size, overwrite_policy, created_at)
                                        VALUES
                                            (#{uniqueId}, #{finalDestPath}, #{stagingTempPath}, #{sourcePath}, #{sourceSize}, #{overwritePolicy}, #{createdAt})
                                        """)
                                .execute(params))
                        .<Void>mapEmpty()
        ).onFailure(err -> log.error("recordTransferOperation failed for %s: %s".formatted(operation.uniqueId(), err.getMessage())));
    }

    @Override
    public Future<TransferOperationRecord> getTransferOperation(String uniqueId) {
        if (StrUtil.isBlank(uniqueId)) {
            return Future.succeededFuture(null);
        }
        return SqlTemplate
                .forQuery(sqlClient, "SELECT * FROM transfer_operation WHERE unique_id = #{uniqueId}")
                .mapTo(TransferOperationRecord.ROW_MAPPER)
                .execute(Map.of("uniqueId", uniqueId))
                .map(rs -> rs.size() > 0 ? rs.iterator().next() : null)
                .onFailure(err -> log.error("getTransferOperation failed for %s: %s".formatted(uniqueId, err.getMessage())));
    }

    @Override
    public Future<Void> deleteTransferOperation(String uniqueId) {
        if (StrUtil.isBlank(uniqueId)) {
            return Future.succeededFuture();
        }
        return SqlTemplate
                .forUpdate(sqlClient, "DELETE FROM transfer_operation WHERE unique_id = #{uniqueId}")
                .execute(Map.of("uniqueId", uniqueId))
                .<Void>mapEmpty()
                .onFailure(err -> log.error("deleteTransferOperation failed for %s: %s".formatted(uniqueId, err.getMessage())));
    }

    @Override
    public Future<List<FileRecord>> getStuckTransfers() {
        // At boot the single-threaded TransferVerticle is not yet running, so any 'transferring' row is
        // the residue of a crashed transfer. Return them for per-row filesystem classification (a blanket
        // reset would misclassify a crash-AFTER-rename as a loss). download_status='completed' guard
        // excludes processed/imported (external-owned).
        return SqlTemplate
                .forQuery(sqlClient, """
                        SELECT * FROM file_record
                        WHERE transfer_status = 'transferring'
                          AND download_status = 'completed'
                        """)
                .mapTo(FileRecord.ROW_MAPPER)
                .execute(Map.of())
                .map(IterUtil::toList)
                .onFailure(err -> log.error("getStuckTransfers failed: %s".formatted(err.getMessage())));
    }

    @Override
    public Future<Boolean> resetTransferToIdle(String uniqueId) {
        if (StrUtil.isBlank(uniqueId)) {
            return Future.succeededFuture(false);
        }
        // Exact-state CAS: transfer_status 'transferring' -> 'idle'. download_status='completed' guard
        // means this can never touch processed/imported. Used for retry-transfer (NOT re-download).
        return SqlTemplate
                .forUpdate(sqlClient, """
                        UPDATE file_record
                        SET transfer_status = 'idle'
                        WHERE unique_id = #{uniqueId}
                          AND transfer_status = 'transferring'
                          AND download_status = 'completed'
                        """)
                .execute(Map.of("uniqueId", uniqueId))
                .map(r -> r.rowCount() > 0)
                .onFailure(err -> log.error("resetTransferToIdle failed for %s: %s".formatted(uniqueId, err.getMessage())));
    }

    @Override
    public Future<Integer> requeueCompletedMissingArtifact(List<String> uniqueIdsWithMissingArtifact) {
        if (CollUtil.isEmpty(uniqueIdsWithMissingArtifact)) {
            return Future.succeededFuture(0);
        }
        String inClause = IntStream.range(0, uniqueIdsWithMissingArtifact.size())
                .mapToObj(i -> "#{uid" + i + "}")
                .collect(Collectors.joining(", "));
        Map<String, Object> params = new HashMap<>();
        for (int i = 0; i < uniqueIdsWithMissingArtifact.size(); i++) {
            params.put("uid" + i, uniqueIdsWithMissingArtifact.get(i));
        }
        // Recovery reset: completed-but-missing (verified absent by caller) -> idle so it re-downloads.
        // download_status='completed' guard makes this a NO-OP on processed/imported/idle rows even if a
        // stale unique_id is passed (defence-in-depth: never touch external-owned terminal states).
        // Also clear the download artifact bookkeeping (local_path, completion_date, start_date,
        // downloaded_size) so the re-download starts clean and the reset-stuck signature matches §4.
        return SqlTemplate
                .forUpdate(sqlClient, """
                        UPDATE file_record
                        SET download_status = 'idle',
                            transfer_status = 'idle',
                            local_path = NULL,
                            completion_date = NULL,
                            start_date = NULL,
                            downloaded_size = 0
                        WHERE unique_id IN (%s)
                          AND download_status = 'completed'
                        """.formatted(inClause))
                .execute(params)
                .map(SqlResult::rowCount)
                .onSuccess(n -> {
                    if (n > 0) {
                        log.info("Recovery re-queued %d completed-but-missing file(s) for re-download".formatted(n));
                    }
                })
                .onFailure(err -> log.error("requeueCompletedMissingArtifact failed: %s".formatted(err.getMessage())));
    }

    @Override
    public Future<Void> updateFileId(int fileId, String uniqueId) {
        if (fileId <= 0 || StrUtil.isBlank(uniqueId)) {
            return Future.succeededFuture();
        }
        return this.getByUniqueId(uniqueId)
                .compose(record -> {
                    if (record == null || record.id() == fileId) {
                        return Future.succeededFuture();
                    }
                    return SqlTemplate
                            .forUpdate(sqlClient, """
                                    UPDATE file_record SET id = #{fileId} WHERE unique_id = #{uniqueId}
                                    """)
                            .execute(Map.of("fileId", fileId, "uniqueId", uniqueId))
                            .onFailure(err ->
                                    log.error("Failed to update file record: %s".formatted(err.getMessage()))
                            )
                            .mapEmpty();
                });
    }

    @Override
    public Future<Integer> updateAlbumDataByMediaAlbumId(long mediaAlbumId, String caption, long reactionCount) {
        if (mediaAlbumId <= 0) {
            return Future.succeededFuture(0);
        }

        return Future.all(Future.future(promise -> {
                    if (StrUtil.isBlank(caption)) {
                        this.getCaptionByMediaAlbumId(mediaAlbumId)
                                .onComplete(result -> {
                                    if (result.succeeded()) {
                                        promise.complete(result.result());
                                    } else {
                                        promise.complete(null);
                                    }
                                });
                    } else {
                        promise.complete(caption);
                    }
                }), Future.future(promise -> {
                    if (reactionCount > 0) {
                        promise.complete(reactionCount);
                    } else {
                        this.getReactionCountByMediaAlbumId(mediaAlbumId)
                                .onComplete(result -> {
                                    if (result.succeeded()) {
                                        promise.complete(result.result());
                                    } else {
                                        promise.complete(0L);
                                    }
                                });
                    }
                })
        ).compose(r -> {
            String theCaption = r.resultAt(0);
            long theReactionCount = r.resultAt(1);
            if (StrUtil.isBlank(theCaption) && theReactionCount <= 0) {
                return Future.succeededFuture(0);
            }
            return SqlTemplate
                    .forUpdate(sqlClient, """
                            UPDATE file_record SET caption = #{caption},
                                                   reaction_count = #{reactionCount}
                                               WHERE media_album_id = #{mediaAlbumId}
                            """)
                    .execute(Map.of("mediaAlbumId", mediaAlbumId, "caption", theCaption, "reactionCount", theReactionCount))
                    .onFailure(err -> log.error("Failed to update file record: %s".formatted(err.getMessage())))
                    .map(SqlResult::rowCount);
        });
    }

    @Override
    public Future<Void> updateTags(String uniqueId, String tags) {
        if (StrUtil.isBlank(uniqueId)) {
            return Future.succeededFuture();
        }
        return SqlTemplate
                .forUpdate(sqlClient, """
                        UPDATE file_record SET tags = #{tags} WHERE unique_id = #{uniqueId}
                        """)
                .execute(Map.of("uniqueId", uniqueId, "tags", tags))
                .onFailure(err -> log.error("Failed to update file record: %s".formatted(err.getMessage())))
                .mapEmpty();
    }

    @Override
    public Future<Void> deleteByUniqueId(String uniqueId) {
        if (StrUtil.isBlank(uniqueId)) {
            return Future.succeededFuture();
        }
        // Application-enforced CASCADE: download_attempt and transfer_operation have no SQL FK
        // (concurrent createTable + file_record's PK-swap migration preclude one), so clear them here.
        return SqlTemplate
                .forUpdate(sqlClient, """
                        DELETE FROM download_attempt WHERE unique_id = #{uniqueId}
                        """)
                .execute(Map.of("uniqueId", uniqueId))
                .recover(err -> {
                    log.warn("Failed to delete download attempts for %s: %s".formatted(uniqueId, err.getMessage()));
                    return Future.succeededFuture(null);
                })
                .compose(ignore -> SqlTemplate
                        .forUpdate(sqlClient, """
                                DELETE FROM transfer_operation WHERE unique_id = #{uniqueId}
                                """)
                        .execute(Map.of("uniqueId", uniqueId))
                        .recover(err -> {
                            log.warn("Failed to delete transfer operation for %s: %s".formatted(uniqueId, err.getMessage()));
                            return Future.succeededFuture(null);
                        }))
                .compose(ignore -> SqlTemplate
                        .forUpdate(sqlClient, """
                                DELETE FROM file_record WHERE unique_id = #{uniqueId}
                                """)
                        .execute(Map.of("uniqueId", uniqueId))
                        .onFailure(err -> log.error("Failed to delete file record: %s".formatted(err.getMessage())))
                )
                .mapEmpty();
    }

    @Override
    public Future<Long> getMinMessageId(long telegramId, long chatId) {
        return SqlTemplate
                .forQuery(sqlClient, """
                        SELECT MIN(message_id) as min_msg_id
                        FROM file_record
                        WHERE telegram_id = #{telegramId}
                          AND chat_id = #{chatId}
                        """)
                .mapTo(row -> {
                    Long minMsgId = row.getLong("min_msg_id");
                    return minMsgId != null ? minMsgId : 0L;
                })
                .execute(Map.of("telegramId", telegramId, "chatId", chatId))
                .map(rs -> rs.size() > 0 ? rs.iterator().next() : 0L)
                .onFailure(err -> log.error("Failed to get min message ID: %s".formatted(err.getMessage())));
    }
    
    @Override
    public Future<List<FileRecord>> getFilesReadyForDownload(long telegramId, long chatId, int limit, Integer cutoffDateSeconds, Boolean downloadOldestFirst) {
        // Get automation settings to determine cutoff and ordering if not provided
        return DataVerticle.settingRepository.<SettingAutoRecords>getByKey(SettingKey.automation)
            .compose(autoRecords -> {
                // Determine downloadOldestFirst from automation settings if not provided
                Boolean resolvedDownloadOldestFirst = downloadOldestFirst;
                if (resolvedDownloadOldestFirst == null && autoRecords != null && autoRecords.automations != null) {
                    for (SettingAutoRecords.Automation auto : autoRecords.automations) {
                        if (auto.telegramId == telegramId 
                            && (chatId == 0 || auto.chatId == chatId)
                            && auto.download != null 
                            && auto.download.rule != null) {
                            resolvedDownloadOldestFirst = auto.download.rule.downloadOldestFirst;
                            break;
                        }
                    }
                }
                final Boolean finalDownloadOldestFirst = resolvedDownloadOldestFirst;
                
                // Determine cutoff date from automation settings if not provided
                if (cutoffDateSeconds == null && autoRecords != null && autoRecords.automations != null) {
                    for (SettingAutoRecords.Automation auto : autoRecords.automations) {
                        if (auto.telegramId == telegramId 
                            && (chatId == 0 || auto.chatId == chatId)
                            && auto.download != null 
                            && auto.download.rule != null 
                            && auto.download.rule.historySince != null 
                            && auto.download.rule.historySince > 0) {
                            // Get sentinel message date
                            Optional<TelegramVerticle> verticleOpt = TelegramVerticles.get(telegramId);
                            if (verticleOpt.isPresent()) {
                                return verticleOpt.get().client.execute(
                                    new TdApi.GetChatMessageByDate(auto.chatId, auto.download.rule.historySince)
                                ).compose(sentinelMessage -> {
                                    Integer resolvedCutoff = sentinelMessage != null ? sentinelMessage.date : null;
                                    return queryFilesReadyForDownload(telegramId, chatId, limit, resolvedCutoff, finalDownloadOldestFirst);
                                });
                            }
                        }
                    }
                }
                
                return queryFilesReadyForDownload(telegramId, chatId, limit, cutoffDateSeconds, finalDownloadOldestFirst);
            });
    }
    
    private Future<List<FileRecord>> queryFilesReadyForDownload(long telegramId, long chatId, int limit, Integer cutoffDateSeconds, Boolean downloadOldestFirst) {
        Map<String, Object> params = new HashMap<>();
        params.put("telegramId", telegramId);
        params.put("limit", limit);
        
        StringBuilder queryBuilder = new StringBuilder("""
            SELECT * FROM file_record
            WHERE telegram_id = #{telegramId}
              AND download_status = 'idle'
              AND (scan_state = 'idle' OR scan_state IS NULL)
              AND type != 'thumbnail'
            """);
        
        if (chatId != 0) {
            queryBuilder.append("  AND chat_id = #{chatId}\n");
            params.put("chatId", chatId);
        }
        
        if (cutoffDateSeconds != null && cutoffDateSeconds > 0) {
            queryBuilder.append("  AND date >= #{cutoffDateSeconds}\n");
            params.put("cutoffDateSeconds", cutoffDateSeconds);
        }
        
        // Order by: date (Telegram upload date) first for chronological ordering, then queued_at
        // When downloadOldestFirst is true, prioritize oldest files by date, regardless of when they were queued
        // message_id is not reliable for chronological ordering, use date instead
        if (Boolean.TRUE.equals(downloadOldestFirst)) {
            queryBuilder.append("  ORDER BY date ASC, COALESCE(queued_at, ").append(Long.MAX_VALUE).append(") ASC, message_id ASC\n");
        } else {
            queryBuilder.append("  ORDER BY date DESC, COALESCE(queued_at, ").append(Long.MAX_VALUE).append(") ASC, message_id DESC\n");
        }
        
        queryBuilder.append("  LIMIT #{limit}\n");
        
        return SqlTemplate
            .forQuery(sqlClient, queryBuilder.toString())
            .mapTo(FileRecord.ROW_MAPPER)
            .execute(params)
            .onFailure(err -> log.error("Failed to get files ready for download: %s".formatted(err.getMessage())))
            .map(IterUtil::toList);
    }
    
    @Override
    public Future<Integer> queueFilesForDownload(long telegramId, long chatId, int limit, Integer cutoffDateSeconds, Boolean downloadOldestFirst) {
        Map<String, Object> params = new HashMap<>();
        params.put("telegramId", telegramId);
        params.put("limit", limit);
        params.put("queuedAt", System.currentTimeMillis());
        
        // D2: key the claim on unique_id (the PRIMARY KEY), NOT the non-unique telegram file `id`.
        // Selecting/matching on `id` mass-queues every row that happens to share a telegram file id.
        // D9: renew queued_at for (a) never-queued rows (queued_at IS NULL) AND (b) rows an external
        // service reset downloading->idle whose stale queued_at from the FIRST attempt is still
        // populated. A reset row's signature (contract §4) is idle + start_date NULL + downloaded_size
        // 0; renewing its queued_at makes reconciliation measure the CURRENT retry, not the first queue
        // event. Already-queued idle rows that were never reset keep their position (not re-timestamped).
        StringBuilder queryBuilder = new StringBuilder("""
            WITH files_to_queue AS (
                SELECT unique_id FROM file_record
                WHERE telegram_id = #{telegramId}
                  AND download_status = 'idle'
                  AND (scan_state = 'idle' OR scan_state IS NULL)
                  AND type != 'thumbnail'
                  AND (queued_at IS NULL
                       OR (start_date IS NULL AND (downloaded_size = 0 OR downloaded_size IS NULL)))
            """);
        
        if (chatId != 0) {
            queryBuilder.append("  AND chat_id = #{chatId}\n");
            params.put("chatId", chatId);
        }
        
        if (cutoffDateSeconds != null && cutoffDateSeconds > 0) {
            queryBuilder.append("  AND date >= #{cutoffDateSeconds}\n");
            params.put("cutoffDateSeconds", cutoffDateSeconds);
        }
        
        // Order by date (Telegram upload date) for correct chronological ordering
        // message_id is not reliable for chronological ordering
        if (Boolean.TRUE.equals(downloadOldestFirst)) {
            queryBuilder.append("  ORDER BY date ASC, message_id ASC\n");
        } else {
            queryBuilder.append("  ORDER BY date DESC, message_id DESC\n");
        }
        
        queryBuilder.append("""
                LIMIT #{limit}
            )
            UPDATE file_record
            SET queued_at = #{queuedAt}
            WHERE unique_id IN (SELECT unique_id FROM files_to_queue)
            """);
        
        String finalQuery = queryBuilder.toString();
        log.debug("Queueing files with query: %s".formatted(finalQuery.replaceAll("#\\{[^}]+\\}", "?")));
        return SqlTemplate
            .forUpdate(sqlClient, finalQuery)
            .execute(params)
            .onSuccess(count -> {
                if (count.rowCount() > 0) {
                    log.info("Successfully queued %d files for download. TelegramId: %d, ChatId: %d".formatted(count.rowCount(), telegramId, chatId));
                }
            })
            .onFailure(err -> log.error("Failed to queue files for download: %s".formatted(err.getMessage())))
            .map(SqlResult::rowCount);
    }

    @Override
    public Future<Integer> queueFilesByUniqueIds(List<String> uniqueIds) {
        if (CollUtil.isEmpty(uniqueIds)) {
            return Future.succeededFuture(0);
        }
        long queuedAt = System.currentTimeMillis();
        String inClause = IntStream.range(0, uniqueIds.size())
                .mapToObj(i -> "#{uid" + i + "}")
                .collect(Collectors.joining(", "));
        Map<String, Object> params = new HashMap<>();
        params.put("queuedAt", queuedAt);
        for (int i = 0; i < uniqueIds.size(); i++) {
            params.put("uid" + i, uniqueIds.get(i));
        }
        // D9: renew queued_at for never-queued idle rows AND externally-reset idle rows (reset
        // signature: start_date IS NULL AND downloaded_size 0/NULL, contract §4) so a manual re-download
        // measures the current attempt, not the first queue event.
        String sql = """
                UPDATE file_record SET queued_at = #{queuedAt}
                WHERE unique_id IN (%s) AND download_status = 'idle'
                  AND (queued_at IS NULL
                       OR (start_date IS NULL AND (downloaded_size = 0 OR downloaded_size IS NULL)))
                """.formatted(inClause);
        return SqlTemplate.forUpdate(sqlClient, sql)
                .execute(params)
                .map(SqlResult::rowCount);
    }

    // ------------------------------------------------------------------------------------------
    // Download-attempt state machine (Phase 2): atomic claim + scoped attempt ownership.
    // ------------------------------------------------------------------------------------------

    @Override
    public Future<String> claimForDownload(int fileId, String uniqueId, String leaseOwner) {
        // The idle-only claim is exactly the generalized claim restricted to {idle}, and it must NOT
        // retire a pre-existing active attempt (a lingering one means a concurrent claim is in flight
        // and THIS call must lose — the idle single-winner invariant).
        return claimForDownloadFrom(fileId, uniqueId, leaseOwner,
                java.util.Set.of(FileRecord.DownloadStatus.idle), false);
    }

    @Override
    public Future<String> claimForDownloadFrom(int fileId, String uniqueId, String leaseOwner,
                                               java.util.Set<FileRecord.DownloadStatus> legalFromStates,
                                               boolean retireExistingActive) {
        if (StrUtil.isBlank(uniqueId)) {
            return Future.succeededFuture(null);
        }
        if (legalFromStates == null || legalFromStates.isEmpty()) {
            return Future.succeededFuture(null);
        }
        // Every from-state must legally transition to downloading (defense-in-depth; the boundary
        // guard also checks canTransitionTo). Reject an illegal predecessor rather than claim from it.
        for (FileRecord.DownloadStatus from : legalFromStates) {
            if (from != FileRecord.DownloadStatus.downloading
                && !from.canTransitionTo(FileRecord.DownloadStatus.downloading)) {
                return Future.failedFuture(new IllegalArgumentException(
                        "claimForDownloadFrom: %s cannot transition to downloading".formatted(from)));
            }
        }
        if (pool == null) {
            return Future.failedFuture(new IllegalStateException(
                    "claimForDownload requires a transactional Pool; repository was constructed with a non-Pool client"));
        }
        // Build the IN-list from the ENUM names only (never user input) — SQL-injection-safe whitelist.
        String inList = legalFromStates.stream()
                .map(s -> "'" + s.name() + "'")
                .collect(java.util.stream.Collectors.joining(", "));
        String attemptId = java.util.UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        // ONE transaction: exact-state CAS (download_status IN legalFromStates)->downloading AND INSERT
        // the owning active attempt. Both-or-neither. The partial unique index (one active attempt per
        // unique_id) makes the INSERT fail if another active attempt exists, rolling the whole claim
        // back — so two concurrent claims (idle OR paused/error re-download) yield exactly one winner.
        return pool.withTransaction(conn ->
                SqlTemplate.forUpdate(conn, """
                                UPDATE file_record
                                SET download_status = 'downloading',
                                    id = #{fileId},
                                    start_date = #{now},
                                    queued_at = COALESCE(queued_at, #{now})
                                WHERE unique_id = #{uniqueId} AND download_status IN (%s)
                                """.formatted(inList))
                        .execute(MapUtil.ofEntries(
                                MapUtil.entry("fileId", fileId),
                                MapUtil.entry("uniqueId", uniqueId),
                                MapUtil.entry("now", now)
                        ))
                        .compose(r -> {
                            if (r.rowCount() == 0) {
                                // Not in a claimable state (already claimed / terminal / externally reset
                                // mid-claim). Roll back with a sentinel; caller sees null.
                                return Future.failedFuture(new ClaimNotIdleException());
                            }
                            // For a paused/error RE-download, retire the row's own lingering active
                            // attempt WITHIN this transaction (gated by the file_record CAS above, so only
                            // the single winner reaches here) BEFORE minting the fresh one — otherwise the
                            // one-active-attempt index would reject the INSERT and roll the claim back.
                            Future<Void> retireFirst = retireExistingActive
                                    ? SqlTemplate.forUpdate(conn, """
                                            UPDATE download_attempt
                                            SET status = 'retired', updated_at = #{now}
                                            WHERE unique_id = #{uniqueId} AND status = 'active'
                                            """)
                                    .execute(MapUtil.ofEntries(
                                            MapUtil.entry("uniqueId", uniqueId),
                                            MapUtil.entry("now", now)
                                    )).mapEmpty()
                                    : Future.succeededFuture();
                            return retireFirst.compose(v -> SqlTemplate.forUpdate(conn, """
                                            INSERT INTO download_attempt
                                                (attempt_id, unique_id, lease_owner, lease_expires_at, status, created_at, updated_at)
                                            VALUES
                                                (#{attemptId}, #{uniqueId}, #{leaseOwner}, NULL, 'active', #{now}, #{now})
                                            """)
                                    .execute(MapUtil.ofEntries(
                                            MapUtil.entry("attemptId", attemptId),
                                            MapUtil.entry("uniqueId", uniqueId),
                                            MapUtil.entry("leaseOwner", leaseOwner),
                                            MapUtil.entry("now", now)
                                    ))
                                    .map(attemptId));
                        })
        ).recover(err -> {
            if (err instanceof ClaimNotIdleException) {
                log.debug("claimForDownload no-op (not claimable) for %s".formatted(uniqueId));
                return Future.succeededFuture(null);
            }
            // A duplicate-active-attempt unique violation also means "someone else owns the claim".
            String msg = err.getMessage() != null ? err.getMessage().toLowerCase() : "";
            if (msg.contains("uq_download_attempt_active") || msg.contains("unique") || msg.contains("duplicate")) {
                log.debug("claimForDownload lost race (active attempt exists) for %s".formatted(uniqueId));
                return Future.succeededFuture(null);
            }
            log.error("claimForDownload failed for %s: %s".formatted(uniqueId, err.getMessage()));
            return Future.failedFuture(err);
        });
    }

    private static final class ClaimNotIdleException extends RuntimeException {
        ClaimNotIdleException() {
            super("file_record not idle", null, false, false);
        }
    }

    @Override
    public Future<Boolean> transitionOwned(int fileId,
                                           String uniqueId,
                                           String attemptId,
                                           FileRecord.DownloadStatus expectedFrom,
                                           FileRecord.DownloadStatus target,
                                           String localPath,
                                           Long completionDate) {
        if (StrUtil.isBlank(uniqueId) || StrUtil.isBlank(attemptId) || expectedFrom == null || target == null) {
            return Future.succeededFuture(false);
        }
        if (!expectedFrom.canTransitionTo(target)) {
            log.warn("Rejected illegal owned transition %s -> %s for %s".formatted(expectedFrom, target, uniqueId));
            return Future.succeededFuture(false);
        }
        if (pool == null) {
            return Future.failedFuture(new IllegalStateException("transitionOwned requires a transactional Pool"));
        }
        long now = System.currentTimeMillis();
        boolean writeCompletionDate = completionDate != null || target.isTerminal();
        boolean retireAttempt = target.isTerminal() || target == FileRecord.DownloadStatus.idle;

        Map<String, Object> params = new HashMap<>();
        params.put("fileId", fileId);
        params.put("uniqueId", uniqueId);
        params.put("attemptId", attemptId);
        params.put("expectedFrom", expectedFrom.name());
        params.put("target", target.name());
        params.put("localPath", localPath);
        String completionAssign = "";
        if (writeCompletionDate) {
            // Preserve an existing completion_date when the caller passes none (COALESCE): a terminal
            // transition without an explicit date must not null an already-set date (D10).
            params.put("completionDate", completionDate);
            completionAssign = "completion_date = COALESCE(#{completionDate}, completion_date),";
        }
        String localPathAssign = localPath != null ? "local_path = #{localPath}," : "";

        // Exact-state CAS scoped to the owning attempt: transition only if the row is EXACTLY in
        // expectedFrom AND this attempt is still the active one. A stale writer or a superseded attempt
        // gets rowCount 0 and does not clobber. Tolerates external reset (row no longer in expectedFrom).
        String updateSql = """
                UPDATE file_record
                SET id = #{fileId},
                    %s
                    %s
                    download_status = #{target}
                WHERE unique_id = #{uniqueId}
                  AND download_status = #{expectedFrom}
                  AND EXISTS (SELECT 1 FROM download_attempt da
                              WHERE da.attempt_id = #{attemptId}
                                AND da.unique_id = #{uniqueId}
                                AND da.status = 'active')
                """.formatted(localPathAssign, completionAssign);

        return pool.withTransaction(conn ->
                SqlTemplate.forUpdate(conn, updateSql)
                        .execute(params)
                        .compose(r -> {
                            if (r.rowCount() == 0) {
                                return Future.succeededFuture(false);
                            }
                            if (!retireAttempt) {
                                return Future.succeededFuture(true);
                            }
                            String retiredStatus = target.isTerminal()
                                    ? (target == FileRecord.DownloadStatus.completed ? "completed"
                                       : target == FileRecord.DownloadStatus.error ? "failed" : "completed")
                                    : "retired";
                            return SqlTemplate.forUpdate(conn, """
                                            UPDATE download_attempt
                                            SET status = #{retiredStatus}, updated_at = #{now}
                                            WHERE attempt_id = #{attemptId} AND status = 'active'
                                            """)
                                    .execute(MapUtil.ofEntries(
                                            MapUtil.entry("attemptId", attemptId),
                                            MapUtil.entry("retiredStatus", retiredStatus),
                                            MapUtil.entry("now", now)
                                    ))
                                    .map(true);
                        })
        ).onFailure(err -> log.error("transitionOwned failed for %s: %s".formatted(uniqueId, err.getMessage())));
    }

    @Override
    public Future<JsonObject> completeDownloadAndRetireAttempt(int fileId,
                                                               String uniqueId,
                                                               String localPath,
                                                               Long completionDate) {
        if (StrUtil.isBlank(uniqueId)) {
            return Future.succeededFuture(null);
        }
        if (pool == null) {
            return Future.failedFuture(new IllegalStateException(
                    "completeDownloadAndRetireAttempt requires a transactional Pool"));
        }
        long now = System.currentTimeMillis();
        Map<String, Object> params = new HashMap<>();
        params.put("fileId", fileId);
        params.put("uniqueId", uniqueId);
        // completion_date is terminal-owned: always write it on completion (COALESCE preserves an
        // existing date when the caller omits one — D10).
        params.put("completionDate", completionDate);
        String localPathAssign = localPath != null ? "local_path = #{localPath}," : "";
        if (localPath != null) {
            params.put("localPath", localPath);
        }
        // TDLib DEDUP (see FileRepository#completeDownloadAndRetireAttempt): one completion per file,
        // always reporting the CURRENT file. So NO per-attempt attribution — the exact-state CAS on
        // download_status='downloading' is the whole guard. External reset-to-idle => rowCount 0
        // (no clobber); a downloading row IS the current file finishing.
        String updateSql = """
                UPDATE file_record
                SET id = #{fileId},
                    %s
                    completion_date = COALESCE(#{completionDate}, completion_date),
                    download_status = 'completed'
                WHERE unique_id = #{uniqueId}
                  AND download_status = 'downloading'
                """.formatted(localPathAssign);
        // ONE transaction: complete the row AND retire its active attempt together. No separate
        // active-attempt lookup, so no window in which a new claim could slip between a lookup and the
        // update (bug 3 fixed). Retiring by unique_id is safe: the partial unique index guarantees at
        // most one active attempt per unique_id, and the 'downloading' guard means that attempt is the
        // current one.
        return pool.withTransaction(conn ->
                SqlTemplate.forUpdate(conn, updateSql)
                        .execute(params)
                        .compose(r -> {
                            if (r.rowCount() == 0) {
                                log.debug("completeDownloadAndRetireAttempt no-op (row not downloading) for %s"
                                        .formatted(uniqueId));
                                return Future.succeededFuture((JsonObject) null);
                            }
                            return SqlTemplate.forUpdate(conn, """
                                            UPDATE download_attempt
                                            SET status = 'completed', updated_at = #{now}
                                            WHERE unique_id = #{uniqueId} AND status = 'active'
                                            """)
                                    .execute(MapUtil.ofEntries(
                                            MapUtil.entry("uniqueId", uniqueId),
                                            MapUtil.entry("now", now)
                                    ))
                                    .map(ignore -> {
                                        JsonObject result = JsonObject.of().put("downloadStatus", "completed");
                                        if (localPath != null) {
                                            result.put("localPath", localPath);
                                        }
                                        if (completionDate != null) {
                                            result.put("completionDate", completionDate);
                                        }
                                        return result;
                                    });
                        })
        ).onFailure(err -> log.error("completeDownloadAndRetireAttempt failed for %s: %s"
                .formatted(uniqueId, err.getMessage())));
    }

    @Override
    public Future<Integer> retireActiveAttempts(String uniqueId) {
        if (StrUtil.isBlank(uniqueId)) {
            return Future.succeededFuture(0);
        }
        long now = System.currentTimeMillis();
        return SqlTemplate.forUpdate(sqlClient, """
                        UPDATE download_attempt
                        SET status = 'retired', updated_at = #{now}
                        WHERE unique_id = #{uniqueId} AND status = 'active'
                        """)
                .execute(MapUtil.ofEntries(MapUtil.entry("uniqueId", uniqueId), MapUtil.entry("now", now)))
                .map(SqlResult::rowCount)
                .onFailure(err -> log.error("Failed to retire active attempts for %s: %s".formatted(uniqueId, err.getMessage())));
    }

    @Override
    public Future<Integer> retireOrphanedAttempts() {
        long now = System.currentTimeMillis();
        // An attempt is orphaned when it is still 'active' but its file_record is no longer
        // 'downloading' (idle after an external reset, or a terminal state) — including the case where
        // the file_record row is gone. Retiring it lets a fresh claim proceed without being blocked by
        // the one-active-attempt constraint. Portable predicate: NOT EXISTS a still-downloading row
        // (no IS DISTINCT FROM, no self-referential correlated subquery).
        return SqlTemplate.forUpdate(sqlClient, """
                        UPDATE download_attempt
                        SET status = 'retired', updated_at = #{now}
                        WHERE status = 'active'
                          AND NOT EXISTS (
                              SELECT 1 FROM file_record fr
                              WHERE fr.unique_id = download_attempt.unique_id
                                AND fr.download_status = 'downloading'
                          )
                        """)
                .execute(MapUtil.ofEntries(MapUtil.entry("now", now)))
                .map(SqlResult::rowCount)
                .onFailure(err -> log.error("Failed to retire orphaned attempts: %s".formatted(err.getMessage())));
    }
}
