package telegram.files.http;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import telegram.files.DataVerticle;
import telegram.files.TelegramVerticle;
import telegram.files.TelegramVerticles;
import telegram.files.repository.FileRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resource-oriented handlers for the new {@code /api/*} download surface (Phase-5). These are the
 * "proper API" front of TDLib: they operate on the {@link FileRecord} + the Phase-2 state machine and
 * NEVER expose raw {@code TdApi} types.
 * <p>
 * Every state mutation delegates to the Phase-2/3 methods on {@link TelegramVerticle} (the state logic
 * is owned there); this layer only resolves stable identity, enforces the transition/ownership guards
 * at the boundary, and marshals the result to JSON with a meaningful HTTP status.
 */
public final class ApiResourceHandlers {

    private static final Log log = LogFactory.get();

    private ApiResourceHandlers() {
    }

    /**
     * {@code POST /api/downloads} — trigger ONE download keyed on STABLE identity.
     * Body: {@code {telegramId, chatId, messageId}} (+ optional {@code uniqueId}, {@code fileId} hints).
     * The volatile TDLib fileId is resolved internally; the Phase-2 atomic claim runs immediately
     * (never a queue). A lost claim / not-idle row returns the REAL current file_record (never a false
     * success); an illegal state yields 409 via {@link ApiException}.
     */
    public static void triggerDownload(RoutingContext ctx) {
        JsonObject body = ctx.body().asJsonObject();
        if (body == null) {
            fail(ctx, 400, "Missing request body");
            return;
        }
        String telegramId = body.getValue("telegramId") == null ? null : Convert.toStr(body.getValue("telegramId"));
        Long chatId = body.getLong("chatId");
        Long messageId = body.getLong("messageId");
        Integer fileIdHint = body.getInteger("fileId");
        if (StrUtil.isBlank(telegramId) || chatId == null || messageId == null) {
            fail(ctx, 400, "telegramId, chatId and messageId are required");
            return;
        }
        Optional<TelegramVerticle> tvOpt = TelegramVerticles.get(telegramId);
        if (tvOpt.isEmpty()) {
            fail(ctx, 404, "Telegram account not found: " + telegramId);
            return;
        }
        TelegramVerticle tv = tvOpt.get();

        tv.triggerDownload(chatId, messageId, fileIdHint)
                .compose(trigger ->
                        // Re-read for the FRESH file_record to return, but the claim verdict comes from
                        // THIS call's outcome, NOT from the row state (which, after a lost race, reads
                        // 'downloading' because the WINNER set it — inferring success from that would be
                        // the false-success bug).
                        DataVerticle.fileRepository.getByUniqueId(trigger.record().uniqueId())
                                .map(fresh -> new TelegramVerticle.DownloadTrigger(
                                        fresh != null ? fresh : trigger.record(), trigger.outcome())))
                .onSuccess(trigger -> {
                    boolean claimed = trigger.claimed();
                    // 202 Accepted ONLY when THIS call won the atomic claim (or synced a done file).
                    // A lost claim / already-active row -> 200 with the REAL state and claimed=false —
                    // never a false 202. The claim verdict is THIS request's own outcome.
                    boolean started = claimed || trigger.outcome() == TelegramVerticle.ClaimOutcome.SYNCED;
                    ctx.response().setStatusCode(started ? 202 : 200);
                    ctx.json(fileRecordJson(trigger.record())
                            .put("claimed", claimed)
                            .put("outcome", trigger.outcome().name()));
                })
                .onFailure(err -> {
                    // startDownload FAILS for an already-downloading / already-completed file (a genuine
                    // conflict, not a bad request). Surface the REAL current state with 409 rather than a
                    // blanket 400, so the client sees the authoritative state, never a false success.
                    if (err instanceof ApiException) {
                        failFrom(ctx, err);
                        return;
                    }
                    String msg = err.getMessage() == null ? "" : err.getMessage();
                    boolean conflict = msg.contains("already downloading")
                                       || msg.contains("is downloading")
                                       || msg.contains("already downloaded")
                                       || msg.contains("already downloading or completed");
                    if (conflict) {
                        failFrom(ctx, ApiException.conflict("Download not started (conflict): " + msg));
                    } else {
                        failFrom(ctx, err);
                    }
                });
    }

    /**
     * {@code POST /api/downloads:batch} — queue a batch of already-known files (unique_id-keyed)
     * atomically. Each item's outcome is reported. Body: {@code {files: [{uniqueId}, ...]}}. Unlike
     * the single trigger, the batch may QUEUE (defer the claim to the scheduler) — but the per-item
     * result is explicit, not a silent count.
     */
    public static void triggerDownloadBatch(RoutingContext ctx) {
        JsonObject body = ctx.body().asJsonObject();
        JsonArray files = body == null ? null : body.getJsonArray("files");
        if (files == null || files.isEmpty()) {
            fail(ctx, 400, "files array is required");
            return;
        }
        List<String> uniqueIds = new ArrayList<>();
        for (Object o : files) {
            if (o instanceof JsonObject jo) {
                String uid = jo.getString("uniqueId");
                if (StrUtil.isNotBlank(uid)) {
                    uniqueIds.add(uid);
                }
            }
        }
        if (uniqueIds.isEmpty()) {
            fail(ctx, 400, "no valid uniqueId entries in files");
            return;
        }
        DataVerticle.fileRepository.queueFilesByUniqueIds(uniqueIds)
                .compose(queued -> DataVerticle.fileRepository.getFilesByUniqueId(uniqueIds)
                        .map(byId -> {
                            JsonArray results = new JsonArray();
                            for (String uid : uniqueIds) {
                                FileRecord rec = byId.get(uid);
                                JsonObject item = new JsonObject().put("uniqueId", uid);
                                if (rec == null) {
                                    item.put("outcome", "not_found");
                                } else {
                                    item.put("downloadStatus", rec.downloadStatus());
                                    item.put("queued", rec.queuedAt() != null);
                                    item.put("outcome", rec.queuedAt() != null ? "queued" : "unchanged");
                                }
                                results.add(item);
                            }
                            return new JsonObject().put("queued", queued).put("items", results);
                        }))
                .onSuccess(ctx::json)
                .onFailure(err -> failFrom(ctx, err));
    }

    /** {@code POST /api/files/{uniqueId}/pause} — guarded downloading->paused (409 on illegal). */
    public static void pause(RoutingContext ctx) {
        transition(ctx, FileRecord.DownloadStatus.paused, false, (tv, record) ->
                tv.togglePauseDownload(record.id(), true));
    }

    /** {@code POST /api/files/{uniqueId}/resume} — guarded paused->downloading (409 on illegal). */
    public static void resume(RoutingContext ctx) {
        transition(ctx, FileRecord.DownloadStatus.downloading, false, (tv, record) ->
                tv.togglePauseDownload(record.id(), false));
    }

    /**
     * {@code POST /api/files/{uniqueId}/cancel} — guarded downloading/paused->idle; DESTRUCTIVE
     * (refuses processed/imported). 409 on an illegal transition.
     */
    public static void cancel(RoutingContext ctx) {
        transition(ctx, FileRecord.DownloadStatus.idle, true, (tv, record) ->
                tv.cancelDownload(record.id()));
    }

    /**
     * {@code DELETE /api/files/{uniqueId}} — remove the file; DESTRUCTIVE (refuses processed/imported).
     * No download_status transition target (removal deletes the row), only the externally-owned guard.
     */
    public static void remove(RoutingContext ctx) {
        transition(ctx, null, true, (tv, record) ->
                tv.removeFile(record.id(), record.uniqueId()));
    }

    // ---- shared plumbing -----------------------------------------------------------------------

    private interface TvAction {
        Future<?> apply(TelegramVerticle tv, FileRecord record);
    }

    /**
     * Resolve the owning account for the file, run the boundary guard
     * ({@link TelegramVerticle#guardedFileOp}) with {@code target}/{@code destructive}, and on success
     * return the fresh file_record (or {@code {removed:true}} for a delete). All guard failures carry
     * their HTTP status via {@link ApiException}.
     */
    private static void transition(RoutingContext ctx, FileRecord.DownloadStatus target,
                                   boolean destructive, TvAction action) {
        String uniqueId = ctx.pathParam("uniqueId");
        if (StrUtil.isBlank(uniqueId)) {
            fail(ctx, 400, "uniqueId is required");
            return;
        }
        DataVerticle.fileRepository.getByUniqueId(uniqueId)
                .compose(record -> {
                    if (record == null) {
                        return Future.failedFuture(ApiException.notFound("File not found: " + uniqueId));
                    }
                    Optional<TelegramVerticle> tvOpt = TelegramVerticles.get(record.telegramId());
                    if (tvOpt.isEmpty()) {
                        return Future.failedFuture(ApiException.notFound(
                                "Telegram account not found for file " + uniqueId));
                    }
                    TelegramVerticle tv = tvOpt.get();
                    return tv.guardedFileOp(uniqueId, target, destructive,
                            rec -> action.apply(tv, rec).mapEmpty());
                })
                .compose(v -> DataVerticle.fileRepository.getByUniqueId(uniqueId))
                .onSuccess(fresh -> {
                    if (fresh == null) {
                        ctx.json(new JsonObject().put("uniqueId", uniqueId).put("removed", true));
                    } else {
                        ctx.json(fileRecordJson(fresh));
                    }
                })
                .onFailure(err -> failFrom(ctx, err));
    }

    /** Project a {@link FileRecord} to the API JSON shape (no raw TdApi types). */
    static JsonObject fileRecordJson(FileRecord r) {
        return new JsonObject()
                .put("id", r.id())
                .put("uniqueId", r.uniqueId())
                .put("telegramId", r.telegramId())
                .put("chatId", r.chatId())
                .put("messageId", r.messageId())
                .put("fileName", r.fileName())
                .put("type", r.type())
                .put("mimeType", r.mimeType())
                .put("size", r.size())
                .put("downloadStatus", r.downloadStatus())
                .put("transferStatus", r.transferStatus())
                .put("localPath", r.localPath())
                .put("completionDate", r.completionDate())
                .put("tags", r.tags());
    }

    private static void fail(RoutingContext ctx, int status, String message) {
        if (ctx.response().ended()) {
            return;
        }
        ctx.response().setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", message).encode());
    }

    /** Map a failure to its HTTP status: {@link ApiException} carries one, everything else is 400. */
    private static void failFrom(RoutingContext ctx, Throwable err) {
        int status = 400;
        if (err instanceof ApiException ae) {
            status = ae.getStatusCode();
        }
        String message = err.getMessage() == null ? "Request failed" : err.getMessage();
        log.debug("API resource op failed (%d): %s".formatted(status, message));
        fail(ctx, status, message);
    }
}
