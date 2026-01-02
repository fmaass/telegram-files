package telegram.files.maintains;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.IterUtil;
import cn.hutool.core.map.MapUtil;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.sqlclient.templates.SqlTemplate;
import org.drinkless.tdlib.TdApi;
import telegram.files.DataVerticle;
import telegram.files.core.TelegramVerticle;
import telegram.files.core.TelegramVerticles;
import telegram.files.repository.FileRecord;

import java.util.List;
import java.util.Optional;

/**
 * This verticle is responsible for maintaining the captions of the media files.
 */
public class AlbumCaptionMaintainVerticle extends MaintainVerticle {

    @Override
    public void start(Promise<Void> startPromise) {
        super.start(startPromise, this::handleAlbumCaption);
    }

    public void handleAlbumCaption() {
        log.info("🔨 Start to handle album caption");
        try {
            log.trace("🔨 1.Scan all file records and update the media_album_id of file records");
            long fromMessageId = 0;
            long count = 0;
            long page = 1;
            while (true) {
                log.trace("🔨 Scan page %d, limit 100".formatted(page));
                List<FileRecord> rows = Future.await(SqlTemplate.forQuery(DataVerticle.pool, """
                                SELECT * FROM file_record WHERE media_album_id is null %s ORDER BY message_id desc LIMIT 100
                                """.formatted(fromMessageId == 0 ? "" : " AND message_id < #{fromMessageId}")
                        )
                        .mapTo(FileRecord.ROW_MAPPER)
                        .execute(MapUtil.of("fromMessageId", fromMessageId))
                        .map(IterUtil::toList));

                if (CollUtil.isEmpty(rows)) {
                    log.trace("🔨 No more file records found, update finished");
                    break;
                }

                for (FileRecord fileRecord : rows) {
                    if (updateMediaAlbumId(fileRecord)) {
                        count++;
                    }
                }

                fromMessageId = rows.getLast().messageId();
                page++;
            }
            log.info("✅ Updated %d file records with media album id".formatted(count));

            log.trace("🔨 2.Update the caption of the media album");
            fromMessageId = 0;
            count = 0;
            page = 1;
            while (true) {
                log.trace("🔨 Scan page %d, limit 100".formatted(page));
                List<FileRecord> rows = Future.await(SqlTemplate.forQuery(DataVerticle.pool, """
                                SELECT * FROM file_record WHERE media_album_id is not null AND caption != '' %s ORDER BY message_id desc LIMIT 100
                                """.formatted(fromMessageId == 0 ? "" : " AND message_id < #{fromMessageId}")
                        )
                        .mapTo(FileRecord.ROW_MAPPER)
                        .execute(MapUtil.of("fromMessageId", fromMessageId))
                        .map(IterUtil::toList));

                if (CollUtil.isEmpty(rows)) {
                    log.trace("🔨 No more file records found, update finished");
                    break;
                }

                for (FileRecord fileRecord : rows) {
                    int updated = Future.await(DataVerticle.fileRepository.updateAlbumDataByMediaAlbumId(fileRecord.mediaAlbumId(), fileRecord.caption(), fileRecord.reactionCount()));
                    count += updated;
                }

                fromMessageId = rows.getLast().messageId();
                page++;
            }
            log.info("✅ Updated %d media albums with caption".formatted(count));
            log.info("✅ Finished handling album caption");
            super.end(true, null);
        } catch (Exception e) {
            log.error("🔨 Failed to handle album caption", e);
            super.end(false, e);
        }
    }

    private boolean updateMediaAlbumId(FileRecord fileRecord) {
        try {
            Optional<TelegramVerticle> telegramVerticleOptional = TelegramVerticles.get(fileRecord.telegramId());
            if (telegramVerticleOptional.isEmpty()) {
                log.error("🔨 Telegram verticle not found for telegram id: %d".formatted(fileRecord.telegramId()));
                return false;
            }
            TelegramVerticle telegramVerticle = telegramVerticleOptional.get();
            TdApi.Message message = Future.await(telegramVerticle.client.execute(new TdApi.GetMessage(fileRecord.chatId(), fileRecord.messageId())));
            if (message != null && message.mediaAlbumId != 0) {
                Future.await(SqlTemplate.forUpdate(DataVerticle.pool, """
                                UPDATE file_record
                                SET media_album_id = #{mediaAlbumId}
                                WHERE unique_id = #{uniqueId}
                                """)
                        .execute(MapUtil.ofEntries(
                                MapUtil.entry("uniqueId", fileRecord.uniqueId()),
                                MapUtil.entry("mediaAlbumId", message.mediaAlbumId)
                        ))
                        .onFailure(err -> log.error("🔨 Failed to update media album id: %s".formatted(err.getMessage())))
                        .map(true));
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error(e, "🔨 Failed to update media album id, unique id: %s".formatted(fileRecord.uniqueId()));
            return false;
        }
    }

}
