package telegram.files;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.CookieSameSite;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.healthchecks.HealthChecks;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.SessionHandler;
import io.vertx.ext.web.healthchecks.HealthCheckHandler;
import io.vertx.ext.web.sstore.LocalSessionStore;
import io.vertx.ext.web.sstore.SessionStore;
import org.drinkless.tdlib.TdApi;
import org.jooq.lambda.function.Function2;
import telegram.files.repository.FileRecord;
import telegram.files.repository.SettingAutoRecords;
import telegram.files.repository.SettingKey;
import telegram.files.repository.SettingRecord;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class HttpVerticle extends AbstractVerticle {

    private static final Log log = LogFactory.get();

    // session id -> ws handler id (package-private for fanout unit tests)
    static final Map<String, String> clients = new ConcurrentHashMap<>();

    // session id -> telegram verticle (package-private for fanout unit tests)
    final Map<String, TelegramVerticle> sessionTelegramVerticles = new ConcurrentHashMap<>();
    
    static final Set<String> ALLOWED_TDLIB_METHODS = Set.of(
            "SetAuthenticationPhoneNumber",
            "CheckAuthenticationCode",
            "CheckAuthenticationPassword",
            "RequestQrCodeAuthentication",
            "GetMessageThread",
            "ResetNetworkStatistics"
    );

    // Websocket sessions with no bound telegramId. A Set (not a List) so a reconnect
    // that re-adds the same sessionId cannot create duplicate event deliveries.
    // Package-private for fanout unit tests.
    final Set<String> unboundClients = ConcurrentHashMap.newKeySet();

    private final FileRouteHandler fileRouteHandler = new FileRouteHandler();

    private static final String SESSION_COOKIE_NAME = "tf";

    // Deployment IDs of the child verticles this verticle owns (D-shutdown). Undeployed in a
    // controlled order in stop() BEFORE Start undeploys DataVerticle (the pool), so no scheduler /
    // transfer / callback write can hit a closed pool.
    private volatile String autoDownloadDeploymentId;

    private volatile String transferDeploymentId;

    private volatile String preloadDeploymentId;

    @Override
    public void start(Promise<Void> startPromise) {
        initHttpServer()
                .compose(_ -> initTelegramVerticles())
                .compose(_ -> AutomationsHolder.INSTANCE.init())
                .compose(_ -> initAutoDownloadVerticle())
                .compose(_ -> initTransferVerticle())
                .compose(_ -> initPreloadMessageVerticle())
                .compose(_ -> initEventConsumer())
                .onSuccess(startPromise::complete)
                .onFailure(startPromise::fail);
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        // Ordered drain (D-shutdown). This runs BEFORE Start undeploys DataVerticle (the pool), so
        // every in-flight transfer and Telegram callback finishes while the pool is still ALIVE:
        //   1. schedulers (AutoDownload, Preload) — stop generating new download/preload work;
        //   2. TelegramVerticles — close TDLib clients, which drains outstanding request callbacks
        //      (rejectAllOutstanding) and stops new file-completed events from reaching Transfer;
        //   3. TransferVerticle — its stop() blocks until the in-flight transfer drains.
        // Failures are logged, never swallowed, and never abort the drain.
        undeployChild(autoDownloadDeploymentId, "AutoDownloadVerticle")
                .compose(_ -> undeployChild(preloadDeploymentId, "PreloadMessageVerticle"))
                .compose(_ -> closeTelegramVerticles())
                .compose(_ -> undeployChild(transferDeploymentId, "TransferVerticle"))
                .compose(_ -> AutomationsHolder.INSTANCE.saveAutoRecords())
                .onComplete(ignore -> {
                    log.info("Http verticle stopped!");
                    stopPromise.complete();
                });
    }

    private Future<Void> undeployChild(String deploymentId, String name) {
        if (StrUtil.isBlank(deploymentId)) {
            return Future.succeededFuture();
        }
        return vertx.undeploy(deploymentId)
                .onSuccess(_ -> log.info("Undeployed %s during shutdown".formatted(name)))
                .recover(e -> {
                    log.error("Failed to undeploy %s during shutdown: %s".formatted(name, e.getMessage()));
                    return Future.succeededFuture();
                });
    }

    // Undeploy every deployed TelegramVerticle. Its stop() calls close(false), which closes the
    // TDLib client (bounded) and drains outstanding request callbacks (rejectAllOutstanding) — so
    // no marshaled callback fires after the pool closes. Runs in parallel across accounts; per
    // account failures are logged, not fatal, and never abort the drain.
    private Future<Void> closeTelegramVerticles() {
        List<Future<Void>> closes = new ArrayList<>();
        for (TelegramVerticle telegramVerticle : TelegramVerticles.getAll()) {
            String deploymentId = telegramVerticle.deploymentID();
            if (StrUtil.isBlank(deploymentId)) {
                continue;
            }
            Future<Void> f = vertx.undeploy(deploymentId)
                    .recover(e -> {
                        log.error("Failed to undeploy telegram verticle %s during shutdown: %s"
                                .formatted(telegramVerticle.getId(), e.getMessage()));
                        return Future.succeededFuture();
                    });
            closes.add(f);
        }
        return Future.join(new ArrayList<>(closes)).mapEmpty();
    }

    public Future<Void> initHttpServer() {
        int port = config().getInteger("http.port", 8080);
        HttpServerOptions options = new HttpServerOptions()
                .setLogActivity(true)
                .setRegisterWebSocketWriteHandlers(true)
                .setMaxWebSocketMessageSize(1024 * 1024)
                .setIdleTimeout(60)
                .setIdleTimeoutUnit(TimeUnit.SECONDS)
                .setPort(port);

        return vertx.createHttpServer(options)
                .requestHandler(initRouter())
                .listen()
                .onSuccess(_ -> log.info("API server started on port " + port))
                .onFailure(err -> log.error("Failed to start API server: %s".formatted(err.getMessage())))
                .mapEmpty();
    }

    public Router initRouter() {
        Router router = Router.router(vertx);

        SessionStore sessionStore = LocalSessionStore.create(vertx, SESSION_COOKIE_NAME);
        SessionHandler sessionHandler = SessionHandler.create(sessionStore)
                .setSessionCookieName(SESSION_COOKIE_NAME);
        sessionHandler.setCookieHttpOnlyFlag(true);
        if (Config.isProd()) {
            sessionHandler
                    .setCookieSameSite(CookieSameSite.STRICT);
        } else {
            sessionHandler
                    .setCookieSameSite(CookieSameSite.NONE)
                    .setCookieSecureFlag(true);
        }
        router.route()
                .handler(sessionHandler)
                .handler(BodyHandler.create().setBodyLimit(1024 * 1024));

        if (!Config.isProd()) {
            router.route()
                    .handler(CorsHandler.create()
                            .addOrigin("http://localhost:3000")
                            .allowedMethod(HttpMethod.GET)
                            .allowedMethod(HttpMethod.POST)
                            .allowedMethod(HttpMethod.PUT)
                            .allowedMethod(HttpMethod.DELETE)
                            .allowedMethod(HttpMethod.OPTIONS)
                            .allowCredentials(true)
                            .allowedHeader("Access-Control-Request-Method")
                            .allowedHeader("Access-Control-Allow-Credentials")
                            .allowedHeader("Access-Control-Allow-Origin")
                            .allowedHeader("Access-Control-Allow-Headers")
                            .allowedHeader("Content-Type")
                    );
        }

        HealthChecks hc = HealthChecks.create(vertx);
        hc.register("http-server", Promise::complete);

        router.get("/").handler(ctx -> ctx.response().end("Hello World!"));
        router.get("/health").handler(HealthCheckHandler.createWithHealthChecks(hc));
        router.get("/version").handler(ctx -> ctx.json(new JsonObject().put("version", Start.VERSION)));
        router.route("/ws").handler(this::handleWebSocket);

        router.get("/settings").handler(this::handleSettings);
        router.post("/settings/create").handler(this::handleSettingsCreate);

        router.post("/telegram/create").handler(this::handleTelegramCreate);
        router.post("/telegram/:telegramId/delete").handler(this::handleTelegramDelete);
        router.get("/telegram/api/methods").handler(this::handleTelegramApiMethods);
        router.get("/telegram/api/:method/parameters").handler(this::handleTelegramApiMethodParameters);
        router.post("/telegram/api/:method").handler(this::handleTelegramApi);
        router.get("/telegrams").handler(this::handleTelegrams);
        router.get("/telegram/:telegramId/chats").handler(this::handleTelegramChats);
        router.get("/telegram/:telegramId/chat/:chatId/files").handler(this::handleTelegramFiles);
        router.get("/telegram/:telegramId/chat/:chatId/files/count").handler(this::handleTelegramFilesCount);
        router.get("/telegram/:telegramId/chat/:chatId/statistics").handler(this::handleTelegramChatDownloadStatistics);
        router.get("/telegram/:telegramId/download-statistics").handler(this::handleTelegramDownloadStatistics);
        router.post("/telegrams/change").handler(this::handleTelegramChange);
        router.post("/telegram/:telegramId/toggle-proxy").handler(this::handleTelegramToggleProxy);
        router.get("/telegram/:telegramId/ping").handler(this::handleTelegramPing);
        router.get("/telegram/:telegramId/test-network").handler(this::handleTelegramTestNetwork);

        router.get("/:telegramId/file/:uniqueId").handler(this::handleFilePreview);
        router.post("/:telegramId/file/start-download").handler(this::handleFileStartDownload);
        router.post("/:telegramId/file/cancel-download").handler(this::handleFileCancelDownload);
        router.post("/:telegramId/file/toggle-pause-download").handler(this::handleFileTogglePauseDownload);
        router.post("/:telegramId/file/remove").handler(this::handleFileRemove);
        router.post("/:telegramId/file/update-auto-settings").handler(this::handleAutoSettingsUpdate);

        router.get("/files/count").handler(this::handleFilesCount);
        router.get("/files").handler(this::handleFiles);
        router.post("/files/start-download-multiple").handler(this::handleFileStartDownloadMultiple);
        router.post("/files/cancel-download-multiple").handler(this::handleFileCancelDownloadMultiple);
        router.post("/files/toggle-pause-download-multiple").handler(this::handleFileTogglePauseDownloadMultiple);
        router.post("/files/remove-multiple").handler(this::handleFileRemoveMultiple);
        router.post("/files/update-tags").handler(this::handleFileTagsUpdateMultiple);
        router.post("/file/:uniqueId/update-tags").handler(this::handleFileTagsUpdate);

        router.route()
                .failureHandler(ctx -> {
                    int statusCode = ctx.statusCode();
                    if (statusCode < 500) {
                        if (ctx.response().ended()) {
                            return;
                        }
                        Throwable cause = ctx.failure();
                        if (cause != null && cause.getMessage() != null) {
                            ctx.response().setStatusCode(statusCode)
                                    .putHeader("Content-Type", "application/json")
                                    .end(JsonObject.of("error", cause.getMessage()).encode());
                        } else {
                            ctx.response().setStatusCode(statusCode).end();
                        }
                        return;
                    }
                    Throwable throwable = ctx.failure();
                    log.trace("route: %s, statusCode: %d".formatted(
                            ctx.request().path(),
                            statusCode), throwable);
                    HttpServerResponse response = ctx.response();
                    response.setStatusCode(statusCode)
                            .putHeader("Content-Type", "application/json")
                            .end(JsonObject.of("error", "Internal server error").encode());
                });
        return router;
    }

    public Future<Void> initTelegramVerticles() {
        return TelegramVerticles.initTelegramVerticles(vertx);
    }

    public Future<Void> initAutoDownloadVerticle() {
        return vertx.deployVerticle(new AutoDownloadVerticle(), Config.VIRTUAL_THREAD_DEPLOYMENT_OPTIONS)
                .onSuccess(id -> autoDownloadDeploymentId = id)
                .mapEmpty();
    }

    public Future<Void> initTransferVerticle() {
        return vertx.deployVerticle(new TransferVerticle(), Config.VIRTUAL_THREAD_DEPLOYMENT_OPTIONS)
                .onSuccess(id -> transferDeploymentId = id)
                .mapEmpty();
    }

    public Future<Void> initPreloadMessageVerticle() {
        return vertx.deployVerticle(new PreloadMessageVerticle(), Config.VIRTUAL_THREAD_DEPLOYMENT_OPTIONS)
                .onSuccess(id -> preloadDeploymentId = id)
                .mapEmpty();
    }

    private Future<Void> initEventConsumer() {
        vertx.eventBus().consumer(EventEnum.TELEGRAM_EVENT.address(), message -> {
            // Guard the debug log: TDLib publishes a large UpdateFile ~1/s, and
            // "...".formatted(body) builds the full event-body string for EVERY
            // event regardless of log level. Eagerly formatting it on the event
            // loop backlogs this consumer until Vert.x pauses it and discards
            // download events (downloads stall, only thumbnails land).
            if (log.isDebugEnabled()) {
                log.debug("Received telegram event: %s".formatted(message.body()));
            }
            JsonObject jsonObject = (JsonObject) message.body();
            String telegramId = jsonObject.getString("telegramId");

            // Resolve live websocket targets FIRST and bail out before the
            // payload encode when nobody is listening (the common case: no
            // dashboard tab open).
            List<String> targets = resolveEventTargets(telegramId);
            if (targets.isEmpty()) {
                return;
            }

            // The published payload is already a Jackson-serialized EventPayload;
            // encode the JsonObject directly (once, for the fanout) instead of the
            // redundant mapTo(EventPayload)/Json.encode() databind round-trip. The
            // wire output is identical and the frontend parses it key-order-agnostically.
            String encoded = jsonObject.getJsonObject("payload").encode();
            for (String wsHandlerId : targets) {
                vertx.eventBus().send(wsHandlerId, encoded);
            }
        });

        vertx.eventBus().consumer(EventEnum.AUTO_DOWNLOAD_UPDATE.address(), message -> {
            log.debug("Auto settings update: {}", message.body());
            AutomationsHolder.INSTANCE.onAutoRecordsUpdate(Json.decodeValue(message.body().toString(), SettingAutoRecords.class));
        });
        return Future.succeededFuture();
    }

    /**
     * Resolve the live websocket handler ids that should receive an event for
     * {@code telegramId}: every session bound to that telegram plus every
     * unbound session, each at most once. Package-private and side-effect-free
     * so the fanout can be unit-tested without a running verticle.
     */
    List<String> resolveEventTargets(String telegramId) {
        Set<String> sentSessionIds = new HashSet<>();
        List<String> targets = new ArrayList<>();
        sessionTelegramVerticles.entrySet().stream()
                .filter(e -> Objects.equals(Convert.toStr(e.getValue().getId()), telegramId))
                .map(Map.Entry::getKey)
                .forEach(sessionId -> {
                    sentSessionIds.add(sessionId);
                    String wsHandlerId = clients.get(sessionId);
                    if (StrUtil.isNotBlank(wsHandlerId)) {
                        targets.add(wsHandlerId);
                    }
                });
        for (String sessionId : unboundClients) {
            if (sentSessionIds.contains(sessionId)) {
                continue;
            }
            String wsHandlerId = clients.get(sessionId);
            if (StrUtil.isNotBlank(wsHandlerId)) {
                targets.add(wsHandlerId);
            }
        }
        return targets;
    }

    /**
     * Tear down the shared session registration when a websocket closes, but
     * ONLY if the closing socket is still the live one. {@code clients} is keyed
     * by the stable HTTP session id and overwritten on reconnect, so a late
     * close from an old socket after a same-session reconnect must not evict the
     * newer registration. The compare-and-remove on the ws handler id makes a
     * stale close a no-op. Package-private for unit testing.
     */
    void handleWebSocketClose(String sessionId, String wsHandlerId) {
        if (clients.remove(sessionId, wsHandlerId)) {
            sessionTelegramVerticles.remove(sessionId);
            unboundClients.remove(sessionId);
        }
    }

    private void handleWebSocket(RoutingContext ctx) {
        String sessionId = ctx.session().id();
        String telegramId = ctx.request().getParam("telegramId");
        ctx.request().toWebSocket()
                .onSuccess(ws -> {
                    log.debug("Upgraded to WebSocket. SessionId: %s".formatted(sessionId));
                    clients.put(sessionId, ws.textHandlerID());
                    if (!handleTelegramChange(sessionId, telegramId)) {
                        log.debug("Failed to change telegram verticle. SessionId: %s".formatted(sessionId));
                    }
                    if (StrUtil.isBlank(telegramId)) {
                        unboundClients.add(sessionId);
                    } else {
                        unboundClients.remove(sessionId);
                    }

                    long timerId = vertx.setPeriodic(30000, _ -> {
                        if (!ws.isClosed()) {
                            ws.writePing(Buffer.buffer("👀"));
                            log.trace("Ping Client: %s".formatted(sessionId));
                        }
                    });

                    ws.exceptionHandler(throwable -> log.error("WebSocket error: %s".formatted(throwable.getMessage())));
                    ws.closeHandler(_ -> {
                        // Always cancel THIS socket's own ping timer, then tear down
                        // the shared session registration only if this socket is still
                        // the live one (stale close after a reconnect is a no-op).
                        vertx.cancelTimer(timerId);
                        handleWebSocketClose(sessionId, ws.textHandlerID());
                        log.debug("WebSocket closed. SessionId: %s".formatted(sessionId));
                    });

                    ws.textMessageHandler(text -> log.debug("Received WebSocket message: " + text));
                })
                .onFailure(err -> log.warn("Failed to upgrade to WebSocket: %s".formatted(err.getMessage())));
    }

    private void handleSettingsCreate(RoutingContext ctx) {
        JsonObject object = ctx.body().asJsonObject();
        if (CollUtil.isEmpty(object)) {
            ctx.fail(400);
            return;
        }

        Future.all(object.stream()
                        .map(setting -> DataVerticle.settingRepository.createOrUpdate(setting.getKey(),
                                Convert.toStr(setting.getValue(), "")))
                        .toList())
                .map(CompositeFuture::<SettingRecord>list)
                .onSuccess(records -> {
                    records.forEach(record ->
                            vertx.eventBus().publish(EventEnum.SETTING_UPDATE.address(record.key()), record.value()));
                    ctx.end();
                })
                .onFailure(ctx::fail);
    }

    private void handleSettings(RoutingContext ctx) {
        String keysStr = ctx.request().getParam("keys");
        if (StrUtil.isBlank(keysStr)) {
            ctx.fail(400);
            return;
        }
        List<String> keys = Arrays.asList(keysStr.split(","));
        DataVerticle.settingRepository
                .getByKeys(keys)
                .onSuccess(settings -> {
                    JsonObject object = new JsonObject();
                    for (SettingRecord record : settings) {
                        object.put(record.key(), record.value());
                    }
                    for (String key : keys) {
                        if (object.containsKey(key)) {
                            continue;
                        }
                        object.put(key, SettingKey.valueOf(key).defaultValue);
                    }
                    ctx.json(object);
                })
                .onFailure(ctx::fail);
    }

    private void handleTelegramCreate(RoutingContext ctx) {
        String sessionId = ctx.session().id();
        TelegramVerticle telegramVerticle = sessionTelegramVerticles.get(sessionId);
        boolean isClosedOrClosing = telegramVerticle != null &&
                telegramVerticle.lastAuthorizationState != null &&
                (telegramVerticle.lastAuthorizationState.getConstructor() == TdApi.AuthorizationStateClosed.CONSTRUCTOR ||
                        telegramVerticle.lastAuthorizationState.getConstructor() == TdApi.AuthorizationStateClosing.CONSTRUCTOR);
        if (telegramVerticle != null && !telegramVerticle.authorized && !isClosedOrClosing) {
            ctx.json(new JsonObject()
                    .put("id", telegramVerticle.getId())
                    .put("lastState", telegramVerticle.lastAuthorizationState)
            );
            return;
        }
        JsonObject jsonObject = ctx.body().asJsonObject();
        String proxyName = jsonObject.getString("proxyName");

        TelegramVerticle newTelegramVerticle = new TelegramVerticle(DataVerticle.telegramRepository.getRootPath());
        newTelegramVerticle.setProxy(proxyName);
        sessionTelegramVerticles.put(sessionId, newTelegramVerticle);
        TelegramVerticles.add(newTelegramVerticle);
        vertx.deployVerticle(newTelegramVerticle)
                .onSuccess(_ -> ctx.json(new JsonObject()
                        .put("id", newTelegramVerticle.getId())
                        .put("lastState", newTelegramVerticle.lastAuthorizationState)
                ))
                .onFailure(ctx::fail);
    }

    private void handleTelegramDelete(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = getTelegramVerticleByPath(ctx);
        if (telegramVerticle == null) {
            return;
        }
        telegramVerticle.close(true)
                .onSuccess(_ -> {
                    TelegramVerticles.remove(telegramVerticle);
                    sessionTelegramVerticles.entrySet().removeIf(e -> e.getValue().equals(telegramVerticle));
                    ctx.end();
                });
    }

    private void handleTelegrams(RoutingContext ctx) {
        Boolean authorized = Convert.toBool(ctx.request().getParam("authorized"));
        Future.all(TelegramVerticles.getAll().stream()
                        .filter(c -> authorized == null || c.authorized == authorized)
                        .map(TelegramVerticle::getTelegramAccount)
                        .toList()
                )
                .map(CompositeFuture::list)
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    private void handleTelegramChats(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = getTelegramVerticleByPath(ctx);
        if (telegramVerticle == null) {
            return;
        }
        String query = ctx.request().getParam("query");
        String chatId = ctx.request().getParam("chatId");
        String archived = ctx.request().getParam("archived");
        telegramVerticle.getChats(Convert.toLong(chatId), query, Convert.toBool(archived, false))
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    private void handleTelegramFiles(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = getTelegramVerticleByPath(ctx);
        if (telegramVerticle == null) {
            return;
        }
        Long chatId = Convert.toLong(ctx.pathParam("chatId"));
        if (chatId == null || chatId == 0) {
            ctx.response().setStatusCode(400).end(JsonObject.of("error", "Invalid chatId parameter").encode());
            return;
        }
        String link = URLUtil.decode(ctx.queryParams().get("link"));
        if (StrUtil.isNotBlank(link)) {
            telegramVerticle.parseLink(link)
                    .onSuccess(ctx::json)
                    .onFailure(ctx::fail);
            return;
        }

        Map<String, String> filter = new HashMap<>();
        ctx.request().params().forEach(filter::put);
        ctx.queryParams().names().forEach(name -> {
            if (!filter.containsKey(name)) {
                filter.put(name, ctx.queryParams().get(name));
            }
        });
        filter.put("search", URLUtil.decode(filter.get("search")));
        String downloadStatuses = filter.get("downloadStatuses");
        if (StrUtil.isNotBlank(downloadStatuses)) {
            filter.put("downloadStatuses", URLUtil.decode(downloadStatuses));
        }
        log.info("handleTelegramFiles filter params: %s downloadStatuses: %s".formatted(filter, downloadStatuses));

        telegramVerticle.getChatFiles(chatId, filter)
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    private void handleTelegramFilesCount(RoutingContext ctx) {
        boolean offline = Convert.toBool(ctx.queryParams().get("offline"), false);
        Long telegramId = Convert.toLong(ctx.pathParam("telegramId"), -1L);
        Long chatId = Convert.toLong(ctx.pathParam("chatId"), -1L);
        if (offline) {
            DataVerticle.fileRepository.countWithType(telegramId, chatId)
                    .onSuccess(ctx::json)
                    .onFailure(ctx::fail);
            return;
        }

        TelegramVerticle telegramVerticle = getTelegramVerticleByPath(ctx);
        if (telegramVerticle == null) {
            return;
        }
        telegramVerticle.getChatFilesCount(chatId)
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    private void handleTelegramDownloadStatistics(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = getTelegramVerticleByPath(ctx);
        if (telegramVerticle == null) {
            return;
        }

        String type = ctx.request().getParam("type");
        String timeRange = ctx.request().getParam("timeRange");
        (Objects.equals(type, "phase") ? telegramVerticle.getDownloadStatisticsByPhase(Convert.toInt(timeRange, 1)) :
                telegramVerticle.getDownloadStatistics())
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    private void handleTelegramChatDownloadStatistics(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = getTelegramVerticleByPath(ctx);
        if (telegramVerticle == null) {
            return;
        }
        Long chatId = Convert.toLong(ctx.pathParam("chatId"));
        if (chatId == null || chatId == 0) {
            ctx.response().setStatusCode(400).end(JsonObject.of("error", "Invalid chatId parameter").encode());
            return;
        }

        telegramVerticle.getChatDownloadStatistics(chatId)
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    private void handleTelegramChange(RoutingContext ctx) {
        String sessionId = ctx.session().id();
        String telegramId = ctx.request().getParam("telegramId");
        if (handleTelegramChange(sessionId, telegramId)) {
            ctx.end();
        } else {
            ctx.fail(400);
        }
    }

    private boolean handleTelegramChange(String sessionId, String telegramId) {
        if (StrUtil.isBlank(telegramId)) {
            sessionTelegramVerticles.remove(sessionId);
            return true;
        }
        Optional<TelegramVerticle> optionalTelegramVerticle = TelegramVerticles.get(telegramId);
        if (optionalTelegramVerticle.isEmpty()) {
            return false;
        }
        sessionTelegramVerticles.put(sessionId, optionalTelegramVerticle.get());
        return true;
    }

    private void handleTelegramToggleProxy(RoutingContext ctx) {
        String telegramId = ctx.request().getParam("telegramId");
        TelegramVerticles.get(telegramId)
                .ifPresentOrElse(telegramVerticle ->
                        telegramVerticle.toggleProxy(ctx.body().asJsonObject())
                                .onSuccess(r -> ctx.json(JsonObject.of("proxy", r)))
                                .onFailure(ctx::fail), () -> ctx.fail(404));
    }

    private void handleTelegramPing(RoutingContext ctx) {
        String telegramId = ctx.pathParam("telegramId");
        if (StrUtil.isBlank(telegramId)) {
            ctx.fail(400);
            return;
        }
        TelegramVerticles.get(telegramId)
                .ifPresentOrElse(telegramVerticle ->
                        telegramVerticle.ping()
                                .onSuccess(r -> ctx.json(JsonObject.of("ping", r)))
                                .onFailure(ctx::fail), () -> ctx.fail(404)
                );
    }

    private void handleTelegramTestNetwork(RoutingContext ctx) {
        String telegramId = ctx.pathParam("telegramId");
        if (StrUtil.isBlank(telegramId)) {
            ctx.fail(400);
            return;
        }
        TelegramVerticles.get(telegramId)
                .ifPresentOrElse(telegramVerticle ->
                                telegramVerticle.client.execute(new TdApi.TestNetwork(), 10000, vertx)
                                        .onComplete(r ->
                                                ctx.json(JsonObject.of("success", r.succeeded()))),
                        () -> ctx.fail(404)
                );
    }

    private void handleTelegramApiMethods(RoutingContext ctx) {
        Map<String, Class<TdApi.Function<?>>> functions = TdApiHelp.getFunctions();
        ctx.json(JsonObject.of("methods", functions.keySet()));
    }

    private void handleTelegramApiMethodParameters(RoutingContext ctx) {
        String method = ctx.pathParam("method");
        ctx.json(JsonObject.of("parameters", TdApiHelp.getFunction(method, null)));
    }

    private void handleTelegramApi(RoutingContext ctx) {
        String method = ctx.pathParam("method");
        if (method == null) {
            ctx.fail(400);
            return;
        }
        if (!ALLOWED_TDLIB_METHODS.contains(method)) {
            log.warn("Rejected TDLib method call: %s".formatted(method));
            ctx.response().setStatusCode(403)
                    .end(JsonObject.of("error", "Method not allowed: " + method).encode());
            return;
        }
        TelegramVerticle telegramVerticle = getTelegramVerticleBySession(ctx);
        if (telegramVerticle == null) {
            return;
        }
        JsonObject params = ctx.body().asJsonObject();
        telegramVerticle.execute(method, params == null ? null : params.getMap())
                .onSuccess(code -> ctx.json(JsonObject.of("code", code)))
                .onFailure(ctx::fail);
    }

    private void handleFilePreview(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = getTelegramVerticleByPath(ctx);
        if (telegramVerticle == null) {
            return;
        }
        String uniqueId = ctx.pathParam("uniqueId");
        if (StrUtil.isBlank(uniqueId)) {
            ctx.fail(404);
            return;
        }

        telegramVerticle.loadPreview(uniqueId)
                .onSuccess(tuple -> {
                    String mimeType = tuple.v2;
                    if (StrUtil.isBlank(mimeType)) {
                        mimeType = FileUtil.getMimeType(tuple.v1);
                    }

                    fileRouteHandler.handle(ctx, tuple.v1, mimeType);
                })
                .onFailure(ctx::fail);
    }

    private void handleFileStartDownload(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(ctx.pathParam("telegramId"));

        JsonObject jsonObject = ctx.body().asJsonObject();
        Long chatId = jsonObject.getLong("chatId");
        Long messageId = jsonObject.getLong("messageId");
        Integer fileId = jsonObject.getInteger("fileId");
        if (chatId == null || messageId == null || fileId == null) {
            ctx.fail(400);
            return;
        }

        telegramVerticle.startDownload(chatId, messageId, fileId)
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    private void handleFileCancelDownload(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(ctx.pathParam("telegramId"));

        JsonObject jsonObject = ctx.body().asJsonObject();
        Integer fileId = jsonObject.getInteger("fileId");
        if (fileId == null) {
            ctx.fail(400);
            return;
        }

        telegramVerticle.cancelDownload(fileId)
                .onSuccess(_ -> ctx.end())
                .onFailure(ctx::fail);
    }

    private void handleFileTogglePauseDownload(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(ctx.pathParam("telegramId"));

        JsonObject jsonObject = ctx.body().asJsonObject();
        Integer fileId = jsonObject.getInteger("fileId");
        Boolean isPaused = jsonObject.getBoolean("isPaused");
        if (fileId == null || isPaused == null) {
            ctx.fail(400);
            return;
        }

        telegramVerticle.togglePauseDownload(fileId, isPaused)
                .onSuccess(_ -> ctx.end())
                .onFailure(ctx::fail);
    }

    private void handleFileRemove(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(ctx.pathParam("telegramId"));
        if (telegramVerticle == null) {
            return;
        }

        JsonObject jsonObject = ctx.body().asJsonObject();
        Integer fileId = jsonObject.getInteger("fileId");
        String uniqueId = jsonObject.getString("uniqueId");
        if (fileId == null && StrUtil.isBlank(uniqueId)) {
            ctx.fail(400);
            return;
        }

        telegramVerticle.removeFile(fileId, uniqueId)
                .onSuccess(_ -> ctx.end())
                .onFailure(ctx::fail);
    }

    private void handleFileStartDownloadMultiple(RoutingContext ctx) {
        JsonObject jsonObject = ctx.body().asJsonObject();
        JsonArray files = jsonObject.getJsonArray("files");
        if (CollUtil.isEmpty(files)) {
            ctx.fail(400);
            return;
        }

        List<String> uniqueIds = files.stream()
                .map(f -> ((JsonObject) f).getString("uniqueId"))
                .filter(StrUtil::isNotBlank)
                .toList();

        if (uniqueIds.isEmpty()) {
            ctx.fail(400);
            return;
        }

        DataVerticle.fileRepository.queueFilesByUniqueIds(uniqueIds)
                .onSuccess(count -> ctx.json(JsonObject.of("queued", count)))
                .onFailure(err -> {
                    log.error("Failed to queue files for download: %s".formatted(err.getMessage()));
                    ctx.response().setStatusCode(500)
                            .end(JsonObject.of("error", err.getMessage()).encode());
                });
    }

    private void handleFileCancelDownloadMultiple(RoutingContext ctx) {
        handleFileMultiple(ctx, (telegramVerticle, file) -> {
            Integer fileId = file.getInteger("fileId");
            if (fileId == null) {
                return Future.failedFuture("Invalid parameters");
            }
            return telegramVerticle.cancelDownload(fileId);
        });
    }

    private void handleFileTogglePauseDownloadMultiple(RoutingContext ctx) {
        JsonObject jsonObject = ctx.body().asJsonObject();
        Boolean isPaused = jsonObject.getBoolean("isPaused");
        if (isPaused == null) {
            ctx.fail(400);
            return;
        }

        handleFileMultiple(ctx, (telegramVerticle, file) -> {
            Integer fileId = file.getInteger("fileId");
            if (fileId == null) {
                return Future.failedFuture("Invalid parameters");
            }
            return telegramVerticle.togglePauseDownload(fileId, isPaused);
        });
    }

    private void handleFileRemoveMultiple(RoutingContext ctx) {
        handleFileMultiple(ctx, (telegramVerticle, file) -> {
            Integer fileId = file.getInteger("fileId");
            String uniqueId = file.getString("uniqueId");
            if (fileId == null && StrUtil.isBlank(uniqueId)) {
                return Future.failedFuture("Invalid parameters");
            }
            return telegramVerticle.removeFile(fileId, uniqueId);
        });
    }

    private void handleFileTagsUpdateMultiple(RoutingContext ctx) {
        JsonObject jsonObject = ctx.body().asJsonObject();
        String tags = jsonObject.getString("tags");
        if (StrUtil.isBlank(tags)) {
            ctx.fail(400);
            return;
        }
        handleFileMultiple(ctx, (_, file) -> {
            String uniqueId = file.getString("uniqueId");
            if (StrUtil.isBlank(uniqueId)) {
                return Future.failedFuture("Invalid parameters");
            }
            return DataVerticle.fileRepository.updateTags(uniqueId, tags);
        });
    }

    private void handleFileMultiple(RoutingContext ctx, Function2<TelegramVerticle, JsonObject, Future<?>> handler) {
        JsonObject jsonObject = ctx.body().asJsonObject();
        JsonArray files = jsonObject.getJsonArray("files");
        if (CollUtil.isEmpty(files)) {
            ctx.fail(400);
            return;
        }
        Map<Long, List<Object>> groupingByTelegramId = files.stream()
                .collect(Collectors.groupingBy(f -> ((JsonObject) f).getLong("telegramId")));

        Future.all(groupingByTelegramId.entrySet()
                        .stream()
                        .flatMap(entry -> {
                            TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(entry.getKey());

                            return entry.getValue().stream()
                                    .map(f -> handler.apply(telegramVerticle, (JsonObject) f));
                        })
                        .toList()
                )
                .onSuccess(ctx::json).onFailure(r -> {
                    log.error(r, "Failed to handle multiple files: %s".formatted(r.getMessage()));
                    ctx.response()
                            .setStatusCode(400)
                            .end(JsonObject.of("error", "Part of the files failed to process: %s".formatted(r.getMessage())).encode());
                });
    }

    private void handleAutoSettingsUpdate(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(ctx.pathParam("telegramId"));

        String chatId = ctx.request().getParam("chatId");
        if (StrUtil.isBlank(chatId)) {
            ctx.fail(400);
            return;
        }
        JsonObject params = ctx.body().asJsonObject();
        telegramVerticle.updateAutoSettings(Convert.toLong(chatId), params)
                .onSuccess(_ -> ctx.end())
                .onFailure(ctx::fail);
    }

    private void handleFilesCount(RoutingContext ctx) {
        DataVerticle.fileRepository.getDownloadStatistics()
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    private void handleFiles(RoutingContext ctx) {
        Map<String, String> filter = new HashMap<>();
        ctx.request().params().forEach(filter::put);
        filter.put("search", URLUtil.decode(filter.get("search")));
        // URL decode downloadStatuses if present (may contain URL-encoded commas)
        String downloadStatuses = filter.get("downloadStatuses");
        if (StrUtil.isNotBlank(downloadStatuses)) {
            filter.put("downloadStatuses", URLUtil.decode(downloadStatuses));
        }

        FileRecordRetriever.getFiles(0, filter)
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    private void handleFileTagsUpdate(RoutingContext ctx) {
        String uniqueId = ctx.pathParam("uniqueId");
        if (StrUtil.isBlank(uniqueId)) {
            ctx.fail(400);
            return;
        }

        JsonObject params = ctx.body().asJsonObject();
        String tags = params.getString("tags");
        DataVerticle.fileRepository.updateTags(uniqueId, tags)
                .onSuccess(_ -> ctx.end())
                .onFailure(ctx::fail);
    }

    private TelegramVerticle getTelegramVerticleBySession(RoutingContext ctx) {
        String sessionId = ctx.session().id();
        TelegramVerticle telegramVerticle = sessionTelegramVerticles.get(sessionId);
        if (telegramVerticle == null) {
            ctx.response().setStatusCode(400)
                    .end(JsonObject.of("error", "Your session not link any telegram!").encode());
            return null;
        }
        return telegramVerticle;
    }

    private TelegramVerticle getTelegramVerticleByPath(RoutingContext ctx) {
        String telegramId = ctx.pathParam("telegramId");
        if (StrUtil.isBlank(telegramId)) {
            ctx.fail(400);
            return null;
        }
        Optional<TelegramVerticle> telegramVerticleOptional = TelegramVerticles.get(telegramId);
        if (telegramVerticleOptional.isEmpty()) {
            ctx.fail(404);
            return null;
        }
        return telegramVerticleOptional.get();
    }
}
