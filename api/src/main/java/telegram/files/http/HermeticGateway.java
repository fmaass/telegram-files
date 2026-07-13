package telegram.files.http;

import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.core.Vertx;
import org.drinkless.tdlib.TdApi;
import telegram.files.Config;
import telegram.files.DataVerticle;
import telegram.files.TelegramVerticle;
import telegram.files.TelegramVerticles;
import telegram.files.repository.FileRecord;
import telegram.files.repository.SettingAutoRecords;

import java.util.Optional;

/**
 * Hermetic, TEST-ONLY gateway/event injector used by the E2E harness (and reused by Phase-6 for the
 * websocket exactly-once probe). It lets a test drive a FAKE download-complete event through the REAL
 * pipeline — the Phase-2 completion CAS ({@code completeDownloadAndRetireAttempt}) and the Phase-3
 * transfer handling — instead of mutating the frontend directly.
 * <p>
 * <b>Prod safety is a hard gate.</b> {@link #isEnabled()} returns {@code false} whenever
 * {@code APP_ENV=prod} (regardless of the opt-in flag), so {@link #mount(Router, Vertx)} installs NO
 * routes in a prod build — the injector surface is absent and unreachable in production. The injector
 * is enabled ONLY when {@code APP_ENV != prod} AND {@code HERMETIC_GATEWAY=1}.
 */
public final class HermeticGateway {

    private static final Log log = LogFactory.get();

    private HermeticGateway() {
    }

    /**
     * The single enablement predicate. Hard-off under prod: even with the opt-in flag set, a prod
     * build never enables the gateway. Read at mount time and at each request.
     */
    public static boolean isEnabled() {
        return enabledFor(Config.isProd(), System.getenv("HERMETIC_GATEWAY"));
    }

    /**
     * Pure enablement decision (package-visible so the prod-safety test can assert the prod gate
     * WITHOUT mutating the static-final {@code Config.APP_ENV}). Hard-off under prod: when
     * {@code isProd} is true the result is ALWAYS false, regardless of the opt-in flag. Otherwise the
     * gateway is enabled only when the flag is {@code "1"}/{@code "true"}.
     */
    public static boolean enabledFor(boolean isProd, String flag) {
        if (isProd) {
            return false;
        }
        return "1".equals(flag) || "true".equalsIgnoreCase(flag);
    }

    /**
     * Mount the injector routes onto {@code apiRouter} IFF {@link #isEnabled()}. Under prod this is a
     * no-op (no routes added), so the endpoints are absent from the running server.
     */
    public static void mount(Router apiRouter, Vertx vertx) {
        if (!isEnabled()) {
            log.info("Hermetic gateway DISABLED (APP_ENV=%s) — no test-injection routes mounted"
                    .formatted(Config.APP_ENV));
            return;
        }
        log.warn("Hermetic gateway ENABLED — test-injection routes mounted. This MUST NOT happen in prod.");
        apiRouter.post("/test/gateway/account").handler(ctx -> seedAccount(ctx, vertx));
        apiRouter.post("/test/gateway/automation").handler(ctx -> seedAutomation(ctx, vertx));
        apiRouter.post("/test/gateway/seed").handler(HermeticGateway::seedDownloadingFile);
        apiRouter.post("/test/gateway/progress").handler(ctx -> progressDownload(ctx, vertx));
        apiRouter.post("/test/gateway/complete").handler(ctx -> completeDownload(ctx, vertx));
        apiRouter.get("/test/gateway/status").handler(ctx ->
                ctx.json(new JsonObject().put("enabled", true).put("appEnv", Config.APP_ENV)));
    }

    /**
     * Enable a REAL transfer automation for (telegramId, chatId) with a real {@code destination} dir,
     * via the SAME {@link TelegramVerticle#updateAutoSettings} path the frontend uses — so the Phase-3
     * {@link telegram.files.TransferVerticle} picks up completed downloads for that chat and runs the
     * durable transfer. Body: {@code {telegramId?, chatId?, destination}}.
     */
    private static void seedAutomation(RoutingContext ctx, Vertx vertx) {
        if (!isEnabled()) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        JsonObject body = ctx.body().asJsonObject();
        long telegramId = body == null ? 1L : body.getLong("telegramId", 1L);
        long chatId = body == null ? 100L : body.getLong("chatId", 100L);
        String destination = body == null ? null : body.getString("destination");
        if (StrUtil.isBlank(destination)) {
            ctx.response().setStatusCode(400)
                    .end(new JsonObject().put("error", "destination is required").encode());
            return;
        }
        new java.io.File(destination).mkdirs();
        Optional<TelegramVerticle> tvOpt = TelegramVerticles.get(telegramId);
        if (tvOpt.isEmpty()) {
            ctx.response().setStatusCode(404)
                    .end(new JsonObject().put("error", "Account not found: " + telegramId).encode());
            return;
        }
        // The Automation shape updateAutoSettings expects (preload/download disabled, transfer enabled).
        JsonObject automation = new JsonObject()
                .put("preload", new JsonObject().put("enabled", false))
                .put("download", new JsonObject().put("enabled", false))
                .put("transfer", new JsonObject()
                        .put("enabled", true)
                        .put("rule", new JsonObject()
                                .put("transferHistory", false)
                                .put("destination", destination)
                                .put("transferPolicy", "DIRECT")
                                .put("duplicationPolicy", "OVERWRITE")
                                .put("useCaptionName", false)));
        final long theChatId = chatId;
        final long theTelegramId = telegramId;
        tvOpt.get().updateAutoSettings(chatId, automation)
                // Do NOT return 200 until the transfer automation is EFFECTIVE in the TransferVerticle's
                // live snapshot (AutomationsHolder.AUTO_RECORDS). updateAutoSettings persists + PUBLISHES
                // an AUTO_DOWNLOAD_UPDATE event that a SEPARATE consumer applies ASYNCHRONOUSLY; if the
                // test proceeds to inject a completion before that lands, the event-driven transfer sees
                // no automation and never queues the file (the periodic scan won't rescue a non-history
                // transfer) — the exact intermittent "transfer never runs" flake. This deterministic
                // await removes that race (it is synchronization, not a weakened assertion).
                .compose(v -> awaitAutomationEffective(vertx, theTelegramId, theChatId, 5000))
                .onSuccess(v -> ctx.json(new JsonObject()
                        .put("automation", true).put("telegramId", theTelegramId)
                        .put("chatId", theChatId).put("destination", destination)))
                .onFailure(err -> ctx.response().setStatusCode(500)
                        .end(new JsonObject().put("error", err.getMessage()).encode()));
    }

    /**
     * Poll until the transfer automation for (telegramId, chatId) is present AND transfer-enabled in the
     * live {@link telegram.files.AutomationsHolder} snapshot the TransferVerticle reads, or fail after
     * {@code timeoutMs}. Deterministically closes the async AUTO_DOWNLOAD_UPDATE propagation gap.
     */
    private static io.vertx.core.Future<Void> awaitAutomationEffective(Vertx vertx, long telegramId,
                                                                       long chatId, long timeoutMs) {
        io.vertx.core.Promise<Void> promise = io.vertx.core.Promise.promise();
        long deadline = System.currentTimeMillis() + timeoutMs;
        checkAutomationEffective(vertx, telegramId, chatId, deadline, promise);
        return promise.future();
    }

    private static void checkAutomationEffective(Vertx vertx, long telegramId, long chatId,
                                                 long deadline, io.vertx.core.Promise<Void> promise) {
        SettingAutoRecords.Automation a =
                telegram.files.AutomationsHolder.INSTANCE.autoRecords().getItem(telegramId, chatId);
        if (a != null && a.transfer != null && a.transfer.enabled) {
            promise.complete();
            return;
        }
        if (System.currentTimeMillis() >= deadline) {
            promise.fail("transfer automation for (%d,%d) did not become effective before timeout"
                    .formatted(telegramId, chatId));
            return;
        }
        vertx.setTimer(50, _ -> checkAutomationEffective(vertx, telegramId, chatId, deadline, promise));
    }

    /**
     * Deploy a HERMETIC telegram account (fake sender, no native TDLib) so the file-list enrichment
     * and download trigger have a live account to resolve against. Body: {@code {telegramId?}}.
     * Idempotent: a second call for an already-registered id is a no-op.
     */
    private static void seedAccount(RoutingContext ctx, Vertx vertx) {
        if (!isEnabled()) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        JsonObject body = ctx.body().asJsonObject();
        long telegramId = body == null ? 1L : body.getLong("telegramId", 1L);
        if (TelegramVerticles.get(telegramId).isPresent()) {
            ctx.json(new JsonObject().put("account", telegramId).put("created", false));
            return;
        }
        String rootPath = Config.TELEGRAM_ROOT + java.io.File.separator + "hermetic-" + telegramId;
        new java.io.File(rootPath).mkdirs();
        telegram.files.HermeticTelegramVerticle hv =
                new telegram.files.HermeticTelegramVerticle(rootPath, telegramId);
        TelegramVerticles.add(hv);
        vertx.deployVerticle(hv)
                .onSuccess(id -> ctx.json(new JsonObject().put("account", telegramId).put("created", true)))
                .onFailure(err -> {
                    TelegramVerticles.remove(hv);
                    ctx.response().setStatusCode(500)
                            .end(new JsonObject().put("error", err.getMessage()).encode());
                });
    }

    /**
     * Seed a {@code downloading} file_record (no TDLib) so the E2E harness has a live download to
     * observe and later complete. Body: {@code {uniqueId, telegramId?, chatId?, messageId?, fileName?}}.
     */
    private static void seedDownloadingFile(RoutingContext ctx) {
        if (!isEnabled()) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        JsonObject body = ctx.body().asJsonObject();
        String uniqueId = body == null ? null : body.getString("uniqueId");
        if (StrUtil.isBlank(uniqueId)) {
            ctx.response().setStatusCode(400)
                    .end(new JsonObject().put("error", "uniqueId is required").encode());
            return;
        }
        long telegramId = body.getLong("telegramId", 1L);
        long chatId = body.getLong("chatId", 100L);
        long messageId = body.getLong("messageId", 200L);
        String fileName = body.getString("fileName", "hermetic-" + uniqueId + ".bin");
        int fileId = body.getInteger("fileId", Math.abs(uniqueId.hashCode()));
        // Optional seed status so a probe can start a row at `idle` and drive a REAL idle->downloading
        // progress transition (which produces a real DB write + real FILE_STATUS event). Defaults to
        // `downloading` for backward compatibility with the existing download E2E.
        String seedStatus = body.getString("downloadStatus",
                FileRecord.DownloadStatus.downloading.name());
        // Optional media type so a probe's row survives the frontend's default `type=media` filter
        // (media == photo|video). Defaults to `file` for backward compatibility.
        String seedType = body.getString("type", "file");

        FileRecord seed = new FileRecord(
                fileId, uniqueId, telegramId, chatId, messageId, 0L, (int) (System.currentTimeMillis() / 1000),
                false, 1024L, 0L, seedType, "application/octet-stream", fileName, null, null, null, null, null,
                seedStatus, FileRecord.TransferStatus.idle.name(),
                System.currentTimeMillis(), null, null, 0L, 0L, 0L, "idle", 0, System.currentTimeMillis());

        DataVerticle.fileRepository.create(seed)
                .onSuccess(r -> ctx.json(new JsonObject()
                        .put("seeded", true).put("uniqueId", uniqueId).put("downloadStatus", seedStatus)))
                .onFailure(err -> ctx.response().setStatusCode(500)
                        .end(new JsonObject().put("error", err.getMessage()).encode()));
    }

    /**
     * Inject a NON-TERMINAL download-PROGRESS update for {@code uniqueId} through the REAL pipeline, so a
     * genuine progress event exists for the websocket-render probe. Drives the owning verticle's REAL
     * {@code onFileUpdated} with a {@code downloading} (isDownloadingActive=true, partial downloadedSize)
     * {@code UpdateFile} — which emits the REAL {@code TYPE_FILE_STATUS} event carrying the partial
     * {@code downloadedSize} (downloadStatus stays {@code downloading}). It never completes the download
     * and never mutates the frontend directly. Body: {@code {uniqueId, downloadedSize?}}.
     */
    private static void progressDownload(RoutingContext ctx, Vertx vertx) {
        if (!isEnabled()) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        JsonObject body = ctx.body().asJsonObject();
        String uniqueId = body == null ? null : body.getString("uniqueId");
        if (StrUtil.isBlank(uniqueId)) {
            ctx.response().setStatusCode(400)
                    .end(new JsonObject().put("error", "uniqueId is required").encode());
            return;
        }
        DataVerticle.fileRepository.getByUniqueId(uniqueId)
                .onSuccess(record -> {
                    if (record == null) {
                        ctx.response().setStatusCode(404)
                                .end(new JsonObject().put("error", "File not found: " + uniqueId).encode());
                        return;
                    }
                    // Partial progress: default to half the recorded size (or 1 byte if size is 0/unknown).
                    long defaultProgress = record.size() > 0 ? Math.max(1L, record.size() / 2) : 1L;
                    long downloadedSize = body.getLong("downloadedSize", defaultProgress);
                    Optional<TelegramVerticle> tvOpt = TelegramVerticles.get(record.telegramId());
                    if (tvOpt.isEmpty()) {
                        ctx.response().setStatusCode(404)
                                .end(new JsonObject().put("error",
                                        "No live verticle for telegramId " + record.telegramId()).encode());
                        return;
                    }
                    tvOpt.get().injectUpdateForGateway(buildProgressUpdate(record, downloadedSize));
                    ctx.json(new JsonObject().put("injected", true).put("phase", "progress")
                            .put("uniqueId", uniqueId).put("downloadedSize", downloadedSize));
                })
                .onFailure(err -> {
                    if (!ctx.response().ended()) {
                        ctx.response().setStatusCode(500)
                                .end(new JsonObject().put("error", err.getMessage()).encode());
                    }
                });
    }

    /**
     * Inject a fake DOWNLOAD-COMPLETE for {@code uniqueId} through the REAL Phase-2/3 pipeline. When a
     * live verticle owns the file, the fake {@code UpdateFile} is fed to its REAL {@code onFileUpdated}
     * (the most faithful path). When no verticle is registered (hermetic E2E with no native TDLib), the
     * SAME Phase-2 completion CAS ({@code completeDownloadAndRetireAttempt}) is invoked and the SAME
     * {@code TELEGRAM_EVENT} FILE_STATUS event is published — so the frontend websocket updates and the
     * Phase-3 TransferVerticle consumer fires exactly as in production. It never mutates the frontend
     * directly.
     */
    private static void completeDownload(RoutingContext ctx, Vertx vertx) {
        if (!isEnabled()) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        JsonObject body = ctx.body().asJsonObject();
        String uniqueId = body == null ? null : body.getString("uniqueId");
        if (StrUtil.isBlank(uniqueId)) {
            ctx.response().setStatusCode(400)
                    .end(new JsonObject().put("error", "uniqueId is required").encode());
            return;
        }
        // The completed download's on-disk artifact. Write a REAL file here so the Phase-3 durable
        // transfer has an actual source to move (transfer_status/local_path then reflect a real move,
        // not just a status flip). Default to a plausible inbox path under the telegram root.
        String inbox = Config.TELEGRAM_ROOT + java.io.File.separator + "hermetic-inbox";
        String localPath = body.getString("localPath",
                inbox + java.io.File.separator + uniqueId + ".bin");
        try {
            java.io.File f = new java.io.File(localPath);
            if (f.getParentFile() != null) {
                f.getParentFile().mkdirs();
            }
            java.nio.file.Files.write(f.toPath(), ("hermetic-payload-" + uniqueId).getBytes());
        } catch (java.io.IOException e) {
            ctx.response().setStatusCode(500)
                    .end(new JsonObject().put("error", "failed to write artifact: " + e.getMessage()).encode());
            return;
        }
        final String finalLocalPath = localPath;

        DataVerticle.fileRepository.getByUniqueId(uniqueId)
                .compose(record -> {
                    if (record == null) {
                        ctx.response().setStatusCode(404)
                                .end(new JsonObject().put("error", "File not found: " + uniqueId).encode());
                        return io.vertx.core.Future.succeededFuture();
                    }
                    Optional<TelegramVerticle> tvOpt = TelegramVerticles.get(record.telegramId());
                    if (tvOpt.isPresent()) {
                        // Most faithful path: drive the REAL update handler on the owning verticle. That
                        // handler's persist is FIRE-AND-FORGET and SWALLOWS a failed write at debug level
                        // (correct for progress: the next update recovers). But a hermetic COMPLETION has
                        // no "next update", so a transient SQLite-WAL BUSY_SNAPSHOT on the completion write
                        // would be silently dropped and the row would stay `downloading` — the exact
                        // intermittent flake. So CONFIRM the completion durably applied (poll the row) and
                        // RE-INJECT on a miss, bounded, before returning 200. This is test-gateway
                        // robustness (deterministic completion), NOT a change to prod onFileUpdated logic.
                        TelegramVerticle tv = tvOpt.get();
                        tv.injectUpdateForGateway(buildCompletedUpdate(record, finalLocalPath));
                        return confirmCompleted(vertx, tv, record, finalLocalPath,
                                System.currentTimeMillis() + 8000)
                                .onSuccess(applied -> ctx.json(new JsonObject()
                                        .put("injected", true).put("via", "verticle")
                                        .put("uniqueId", uniqueId).put("localPath", finalLocalPath)))
                                .mapEmpty();
                    }
                    // Verticle-less hermetic path: SAME Phase-2 CAS + SAME real event publish.
                    long completionDate = System.currentTimeMillis();
                    return DataVerticle.fileRepository.completeDownloadAndRetireAttempt(
                                    record.id(), uniqueId, localPath, completionDate)
                            .onSuccess(applied -> {
                                publishFileStatusEvent(vertx, record, finalLocalPath, completionDate, applied);
                                ctx.json(new JsonObject()
                                        .put("injected", true).put("via", "repository")
                                        .put("applied", applied != null)
                                        .put("uniqueId", uniqueId).put("localPath", finalLocalPath));
                            })
                            .mapEmpty();
                })
                .onFailure(err -> {
                    if (!ctx.response().ended()) {
                        ctx.response().setStatusCode(500)
                                .end(new JsonObject().put("error", err.getMessage()).encode());
                    }
                });
    }

    /**
     * Poll until the file row is durably {@code download_status=completed}, RE-INJECTING the completion
     * update on a miss (the verticle persist can silently swallow a transient SQLite BUSY_SNAPSHOT). Fails
     * past {@code deadlineMs}. Deterministically closes the hermetic completion-write race so the gateway
     * returns 200 only when the completion has actually landed.
     */
    private static io.vertx.core.Future<Boolean> confirmCompleted(Vertx vertx, TelegramVerticle tv,
                                                                  FileRecord record, String localPath,
                                                                  long deadlineMs) {
        io.vertx.core.Promise<Boolean> promise = io.vertx.core.Promise.promise();
        pollCompleted(vertx, tv, record, localPath, deadlineMs, promise);
        return promise.future();
    }

    private static void pollCompleted(Vertx vertx, TelegramVerticle tv, FileRecord record, String localPath,
                                      long deadlineMs, io.vertx.core.Promise<Boolean> promise) {
        DataVerticle.fileRepository.getByUniqueId(record.uniqueId())
                .onSuccess(row -> {
                    if (row != null && row.isDownloadStatus(FileRecord.DownloadStatus.completed)) {
                        promise.complete(true);
                        return;
                    }
                    if (System.currentTimeMillis() >= deadlineMs) {
                        promise.fail("hermetic completion for %s did not durably apply before timeout"
                                .formatted(record.uniqueId()));
                        return;
                    }
                    // Re-inject: the prior persist may have swallowed a transient BUSY_SNAPSHOT.
                    tv.injectUpdateForGateway(buildCompletedUpdate(record, localPath));
                    vertx.setTimer(200, _ -> pollCompleted(vertx, tv, record, localPath, deadlineMs, promise));
                })
                .onFailure(err -> {
                    if (System.currentTimeMillis() >= deadlineMs) {
                        promise.fail(err);
                    } else {
                        vertx.setTimer(200, _ -> pollCompleted(vertx, tv, record, localPath, deadlineMs, promise));
                    }
                });
    }

    /**
     * Publish the REAL {@code TELEGRAM_EVENT} FILE_STATUS event (the exact shape HttpVerticle fans out
     * to websockets and TransferVerticle consumes) for a completed download. Used only by the
     * verticle-less hermetic completion path.
     */
    private static void publishFileStatusEvent(Vertx vertx, FileRecord record, String localPath,
                                               long completionDate, JsonObject applied) {
        JsonObject statusData = new JsonObject()
                .put("fileId", record.id())
                .put("uniqueId", record.uniqueId())
                .put("downloadStatus", FileRecord.DownloadStatus.completed.name())
                .put("localPath", localPath)
                .put("completionDate", completionDate)
                .put("downloadedSize", record.size())
                .put("type", record.type());
        telegram.files.EventPayload payload = telegram.files.EventPayload.build(
                telegram.files.EventPayload.TYPE_FILE_STATUS, statusData);
        vertx.eventBus().publish(telegram.files.EventEnum.TELEGRAM_EVENT.address(),
                JsonObject.of("telegramId", record.telegramId(), "payload", JsonObject.mapFrom(payload)));
    }

    /**
     * Build a NON-TERMINAL (still-downloading) {@code UpdateFile} for a file_record — the shape TDLib
     * emits mid-download: {@code isDownloadingActive=true}, {@code isDownloadingCompleted=false}, and a
     * partial {@code downloadedSize}. {@code onFileUpdated} classifies this as {@code downloading} and
     * emits a FILE_STATUS event carrying the partial size (no completion, no transfer).
     */
    static TdApi.UpdateFile buildProgressUpdate(FileRecord record, long downloadedSize) {
        TdApi.File file = new TdApi.File();
        file.id = record.id();

        TdApi.RemoteFile remote = new TdApi.RemoteFile();
        remote.uniqueId = record.uniqueId();
        remote.id = "hermetic-remote-" + record.uniqueId();
        file.remote = remote;

        TdApi.LocalFile local = new TdApi.LocalFile();
        local.path = "";
        local.isDownloadingActive = true;
        local.isDownloadingCompleted = false;
        local.downloadedSize = downloadedSize;
        local.canBeDownloaded = true;
        file.local = local;

        file.size = record.size();
        file.expectedSize = record.size();

        TdApi.UpdateFile update = new TdApi.UpdateFile();
        update.file = file;
        return update;
    }

    /** Build a completed {@code UpdateFile} for a file_record (the shape TDLib emits on completion). */
    static TdApi.UpdateFile buildCompletedUpdate(FileRecord record, String localPath) {
        TdApi.File file = new TdApi.File();
        file.id = record.id();

        TdApi.RemoteFile remote = new TdApi.RemoteFile();
        remote.uniqueId = record.uniqueId();
        remote.id = "hermetic-remote-" + record.uniqueId();
        file.remote = remote;

        TdApi.LocalFile local = new TdApi.LocalFile();
        local.path = localPath;
        local.isDownloadingActive = false;
        local.isDownloadingCompleted = true;
        local.downloadedSize = record.size();
        local.canBeDownloaded = true;
        file.local = local;

        file.size = record.size();
        file.expectedSize = record.size();

        TdApi.UpdateFile update = new TdApi.UpdateFile();
        update.file = file;
        return update;
    }
}
