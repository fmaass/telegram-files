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
        apiRouter.post("/test/gateway/automation").handler(HermeticGateway::seedAutomation);
        apiRouter.post("/test/gateway/seed").handler(HermeticGateway::seedDownloadingFile);
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
    private static void seedAutomation(RoutingContext ctx) {
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
        tvOpt.get().updateAutoSettings(chatId, automation)
                .onSuccess(v -> ctx.json(new JsonObject()
                        .put("automation", true).put("telegramId", telegramId)
                        .put("chatId", chatId).put("destination", destination)))
                .onFailure(err -> ctx.response().setStatusCode(500)
                        .end(new JsonObject().put("error", err.getMessage()).encode()));
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

        FileRecord seed = new FileRecord(
                fileId, uniqueId, telegramId, chatId, messageId, 0L, (int) (System.currentTimeMillis() / 1000),
                false, 1024L, 0L, "file", "application/octet-stream", fileName, null, null, null, null, null,
                FileRecord.DownloadStatus.downloading.name(), FileRecord.TransferStatus.idle.name(),
                System.currentTimeMillis(), null, null, 0L, 0L, 0L, "idle", 0, System.currentTimeMillis());

        DataVerticle.fileRepository.create(seed)
                .onSuccess(r -> ctx.json(new JsonObject()
                        .put("seeded", true).put("uniqueId", uniqueId).put("downloadStatus", "downloading")))
                .onFailure(err -> ctx.response().setStatusCode(500)
                        .end(new JsonObject().put("error", err.getMessage()).encode()));
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
                        // Most faithful path: drive the REAL update handler on the owning verticle.
                        tvOpt.get().injectUpdateForGateway(buildCompletedUpdate(record, finalLocalPath));
                        ctx.json(new JsonObject().put("injected", true).put("via", "verticle")
                                .put("uniqueId", uniqueId).put("localPath", finalLocalPath));
                        return io.vertx.core.Future.succeededFuture();
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
