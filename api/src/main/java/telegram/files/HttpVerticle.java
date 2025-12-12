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
import telegram.files.repository.AutomationControlState;
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

    // session id -> ws handler id
    private static final Map<String, String> clients = new ConcurrentHashMap<>();

    // session id -> telegram verticle
    private final Map<String, TelegramVerticle> sessionTelegramVerticles = new ConcurrentHashMap<>();
    
    // telegramId -> queue of files waiting to download
    private final Map<Long, Queue<JsonObject>> downloadQueues = new ConcurrentHashMap<>();
    
    // telegramId -> timer ID for processing queue
    private final Map<Long, Long> queueTimerIds = new ConcurrentHashMap<>();
    
    // telegramId -> current batch being processed (file uniqueIds)
    private final Map<Long, Set<String>> currentBatches = new ConcurrentHashMap<>();
    
    private static final int BATCH_SIZE = 10;

    private final List<String> unboundClients = new ArrayList<>();

    private final FileRouteHandler fileRouteHandler = new FileRouteHandler();

    private static final String SESSION_COOKIE_NAME = "tf";

    @Override
    public void start(Promise<Void> startPromise) {
        initHttpServer()
                .compose(r -> initTelegramVerticles())
                .compose(r -> AutomationsHolder.INSTANCE.init())
                .compose(r -> initAutoDownloadVerticle())
                .compose(r -> initTransferVerticle())
                .compose(r -> initPreloadMessageVerticle())
                .compose(r -> initEventConsumer())
                .onSuccess(startPromise::complete)
                .onFailure(startPromise::fail);
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        AutomationsHolder.INSTANCE.saveAutoRecords()
                .onComplete(ignore -> {
                    log.info("Http verticle stopped!");
                    stopPromise.complete();
                });
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
                .onSuccess(server -> log.info("API server started on port " + port))
                .onFailure(err -> log.error("Failed to start API server: %s".formatted(err.getMessage())))
                .mapEmpty();
    }

    public Router initRouter() {
        Router router = Router.router(vertx);

        SessionStore sessionStore = LocalSessionStore.create(vertx, SESSION_COOKIE_NAME);
        SessionHandler sessionHandler = SessionHandler.create(sessionStore)
                .setSessionCookieName(SESSION_COOKIE_NAME);
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
                .handler(BodyHandler.create());

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

        // Automation control API endpoints
        router.get("/api/automations").handler(this::handleGetAutomations);
        router.get("/api/automations/:chatId").handler(this::handleGetAutomation);
        router.post("/api/automations/:chatId/start").handler(this::handleStartAutomation);
        router.post("/api/automations/:chatId/stop").handler(this::handleStopAutomation);
        router.post("/api/automations/:chatId/state").handler(this::handleSetAutomationState);
        router.get("/api/automations/:chatId/health").handler(this::handleGetAutomationHealth);

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
                        ctx.response().setStatusCode(statusCode).end();
                        return;
                    }
                    Throwable throwable = ctx.failure();
                    log.error("route: %s statusCode: %d".formatted(
                            ctx.currentRoute().getName(),
                            statusCode), throwable);
                    HttpServerResponse response = ctx.response();
                    response.setStatusCode(statusCode)
                            .putHeader("Content-Type", "application/json")
                            .end(JsonObject.of("error", throwable == null ? "☹️Sorry! Not today." : throwable.getMessage()).encode());
                });
        return router;
    }

    public Future<Void> initTelegramVerticles() {
        return TelegramVerticles.initTelegramVerticles(vertx);
    }

    public Future<Void> initAutoDownloadVerticle() {
        return vertx.deployVerticle(new AutoDownloadVerticle(), Config.VIRTUAL_THREAD_DEPLOYMENT_OPTIONS)
                .mapEmpty();
    }

    public Future<Void> initTransferVerticle() {
        return vertx.deployVerticle(new TransferVerticle(), Config.VIRTUAL_THREAD_DEPLOYMENT_OPTIONS)
                .mapEmpty();
    }

    public Future<Void> initPreloadMessageVerticle() {
        return vertx.deployVerticle(new PreloadMessageVerticle(), Config.VIRTUAL_THREAD_DEPLOYMENT_OPTIONS)
                .mapEmpty();
    }

    private Future<Void> initEventConsumer() {
        vertx.eventBus().consumer(EventEnum.TELEGRAM_EVENT.address(), message -> {
            log.debug("Received telegram event: %s".formatted(message.body()));
            JsonObject jsonObject = (JsonObject) message.body();
            String telegramId = jsonObject.getString("telegramId");
            EventPayload payload = jsonObject.getJsonObject("payload").mapTo(EventPayload.class);

            Set<String> sentSessionIds = new HashSet<>();
            sessionTelegramVerticles.entrySet().stream()
                    .filter(e -> Objects.equals(Convert.toStr(e.getValue().getId()), telegramId))
                    .map(Map.Entry::getKey)
                    .forEach(sessionId -> {
                        String wsHandlerId = clients.get(sessionId);
                        if (StrUtil.isNotBlank(wsHandlerId)) {
                            vertx.eventBus().send(wsHandlerId, Json.encode(payload));
                        }
                        sentSessionIds.add(sessionId);
                    });

            unboundClients.forEach(sessionId -> {
                if (sentSessionIds.contains(sessionId)) {
                    return;
                }
                String wsHandlerId = clients.get(sessionId);
                if (StrUtil.isNotBlank(wsHandlerId)) {
                    vertx.eventBus().send(wsHandlerId, Json.encode(payload));
                }
            });
        });

        vertx.eventBus().consumer(EventEnum.AUTO_DOWNLOAD_UPDATE.address(), message -> {
            log.debug("Auto settings update: %s".formatted(message.body()));
            AutomationsHolder.INSTANCE.onAutoRecordsUpdate(Json.decodeValue(message.body().toString(), SettingAutoRecords.class));
        });
        return Future.succeededFuture();
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

                    long timerId = vertx.setPeriodic(30000, id -> {
                        if (!ws.isClosed()) {
                            ws.writePing(Buffer.buffer("👀"));
                            log.trace("Ping Client: %s".formatted(sessionId));
                        }
                    });

                    ws.exceptionHandler(throwable -> log.error("WebSocket error: %s".formatted(throwable.getMessage())));
                    ws.closeHandler(e -> {
                        clients.remove(sessionId);
                        sessionTelegramVerticles.remove(sessionId);
                        vertx.cancelTimer(timerId);
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
        if (telegramVerticle != null && !telegramVerticle.authorized) {
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
                .onSuccess(id -> ctx.json(new JsonObject()
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
                .onSuccess(r -> {
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
        String chatId = ctx.pathParam("chatId");
        if (StrUtil.isBlank(chatId)) {
            ctx.fail(400);
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
        filter.put("search", URLUtil.decode(filter.get("search")));

        telegramVerticle.getChatFiles(Convert.toLong(chatId), filter)
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
        String chatId = ctx.pathParam("chatId");
        if (StrUtil.isBlank(chatId)) {
            ctx.fail(400);
            return;
        }

        telegramVerticle.getChatDownloadStatistics(Convert.toLong(chatId))
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
                .onFailure(throwable -> {
                    // Check if it's a "file not found" error (normal condition - file was moved)
                    String message = throwable.getMessage();
                    if (message != null && message.contains("File not found")) {
                        // Return 404 silently (log at DEBUG level only)
                        if (log.isDebugEnabled()) {
                            log.debug("File not found (likely moved): %s".formatted(uniqueId));
                        }
                        ctx.response()
                                .setStatusCode(404)
                                .putHeader("Content-Type", "application/json")
                                .end(JsonObject.of(
                                        "error", "File not found",
                                        "uniqueId", uniqueId,
                                        "reason", "File has been moved by post-processing"
                                ).encode());
                    } else {
                        // Other errors are real server errors (500)
                        ctx.fail(throwable);
                    }
                });
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
                .onSuccess(r -> ctx.end())
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
                .onSuccess(r -> ctx.end())
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
                .onSuccess(r -> ctx.end())
                .onFailure(ctx::fail);
    }

    private void handleFileStartDownloadMultiple(RoutingContext ctx) {
        JsonObject jsonObject = ctx.body().asJsonObject();
        JsonArray files = jsonObject.getJsonArray("files");
        if (CollUtil.isEmpty(files)) {
            ctx.fail(400);
            return;
        }
        
        // Group files by telegramId
        Map<Long, List<JsonObject>> groupingByTelegramId = files.stream()
                .map(f -> (JsonObject) f)
                .collect(Collectors.groupingBy(f -> f.getLong("telegramId")));
        
        // Process each telegram account with concurrency control
        List<Future<?>> accountFutures = new ArrayList<>();
        
        for (Map.Entry<Long, List<JsonObject>> entry : groupingByTelegramId.entrySet()) {
            Long telegramId = entry.getKey();
            List<JsonObject> accountFiles = entry.getValue();
            
            TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(telegramId);
            
            // Get the download limit and current downloading count asynchronously
            Future<Integer> limitFuture = DataVerticle.settingRepository.<Integer>getByKey(SettingKey.autoDownloadLimit)
                    .map(limit -> limit != null && limit > 0 ? limit : 5)
                    .otherwise(5);
            
            Future<Integer> downloadingFuture = DataVerticle.fileRepository.countByStatus(telegramId, FileRecord.DownloadStatus.downloading)
                    .otherwise(0);
            
            Future<?> accountFuture = Future.all(limitFuture, downloadingFuture)
                    .compose(results -> {
                        int limit = results.resultAt(0);
                        Integer downloading = results.resultAt(1);
                        int surplusSize = Math.max(0, limit - (downloading != null ? downloading : 0));
                        
                        // Start downloads respecting the concurrency limit
                        List<JsonObject> filesToDownloadNow = accountFiles.stream()
                                .limit(Math.min(surplusSize, accountFiles.size()))
                                .collect(Collectors.toList());
                        
                        List<Future<?>> downloadFutures = new ArrayList<>();
                        for (JsonObject file : filesToDownloadNow) {
                            Long chatId = file.getLong("chatId");
                            Long messageId = file.getLong("messageId");
                            Integer fileId = file.getInteger("fileId");
                            if (chatId == null || messageId == null || fileId == null) {
                                continue;
                            }
                            downloadFutures.add(telegramVerticle.startDownload(chatId, messageId, fileId));
                        }
                        
                        // Queue remaining files
                        int queuedCount = accountFiles.size() - filesToDownloadNow.size();
                        if (queuedCount > 0) {
                            Queue<JsonObject> queue = downloadQueues.computeIfAbsent(telegramId, k -> new LinkedList<>());
                            queue.addAll(accountFiles.subList(filesToDownloadNow.size(), accountFiles.size()));
                            log.debug("Queued %d files for telegramId %d (limit: %d, currently downloading: %d, started: %d)"
                                    .formatted(queuedCount, telegramId, limit, downloading != null ? downloading : 0, downloadFutures.size()));
                            
                            // Start processing queue if not already running
                            if (!queueTimerIds.containsKey(telegramId)) {
                                startBatchProcessor(telegramId);
                            }
                        }
                        
                        return Future.all(downloadFutures);
                    });
            
            accountFutures.add(accountFuture);
        }
        
        // Wait for all account processing to complete
        Future.all(accountFutures)
                .onSuccess(r -> {
                    JsonObject response = new JsonObject();
                    response.put("message", "Downloads started with concurrency control");
                    ctx.json(response);
                })
                .onFailure(r -> {
                    log.error(r, "Failed to start batch downloads: %s".formatted(r.getMessage()));
                    ctx.response()
                            .setStatusCode(400)
                            .end(JsonObject.of("error", "Part of the files failed to start: %s".formatted(r.getMessage())).encode());
                });
    }
    
    private void startBatchProcessor(Long telegramId) {
        TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(telegramId);
        long timerId = vertx.setPeriodic(5000, id -> {
            Queue<JsonObject> queue = downloadQueues.get(telegramId);
            Set<String> currentBatch = currentBatches.get(telegramId);
            
            // If queue is empty and no current batch, stop processing
            if ((queue == null || queue.isEmpty()) && (currentBatch == null || currentBatch.isEmpty())) {
                vertx.cancelTimer(id);
                queueTimerIds.remove(telegramId);
                downloadQueues.remove(telegramId);
                currentBatches.remove(telegramId);
                return;
            }
            
            // If we have a current batch, check if all files are completed
            if (currentBatch != null && !currentBatch.isEmpty()) {
                checkBatchCompletion(telegramId, currentBatch, queue, id);
                return;
            }
            
            // No current batch, start a new one
            if (queue != null && !queue.isEmpty()) {
                startNewBatch(telegramId, telegramVerticle, queue);
            }
        });
        queueTimerIds.put(telegramId, timerId);
    }
    
    private void startNewBatch(Long telegramId, TelegramVerticle telegramVerticle, Queue<JsonObject> queue) {
        List<JsonObject> batchFiles = new ArrayList<>();
        
        // Take up to BATCH_SIZE files from queue
        int batchCount = Math.min(BATCH_SIZE, queue.size());
        for (int i = 0; i < batchCount; i++) {
            JsonObject file = queue.poll();
            if (file == null) break;
            batchFiles.add(file);
        }
        
        if (batchFiles.isEmpty()) {
            return;
        }
        
        log.info("Starting batch of %d files for telegramId %d".formatted(batchFiles.size(), telegramId));
        
        // Start all downloads in the batch and track them by uniqueId
        for (JsonObject file : batchFiles) {
            Long chatId = file.getLong("chatId");
            Long messageId = file.getLong("messageId");
            Integer fileId = file.getInteger("fileId");
            if (chatId == null || messageId == null || fileId == null) {
                continue;
            }
            
            telegramVerticle.startDownload(chatId, messageId, fileId)
                    .onSuccess(fileRecord -> {
                        // Add to batch tracking using the uniqueId from the file record
                        String uniqueId = fileRecord.uniqueId();
                        if (StrUtil.isNotBlank(uniqueId)) {
                            Set<String> currentBatch = currentBatches.computeIfAbsent(telegramId, k -> new HashSet<>());
                            currentBatch.add(uniqueId);
                            log.debug("Added to batch tracking: %s".formatted(uniqueId));
                        }
                    })
                    .onFailure(e -> {
                        log.error("Failed to start download in batch (chatId: %d, messageId: %d, fileId: %d): %s"
                                .formatted(chatId, messageId, fileId, e.getMessage()));
                        // If we can't start it, we should still mark it as "done" so the batch can proceed
                        // But we need to track it somehow - use a temporary identifier
                        String tempId = String.format("failed-%d-%d-%d", chatId, messageId, fileId);
                        Set<String> currentBatch = currentBatches.computeIfAbsent(telegramId, k -> new HashSet<>());
                        currentBatch.add(tempId);
                        // Remove failed items immediately so they don't block the batch
                        vertx.setTimer(1000, timerId -> {
                            Set<String> batchSet = currentBatches.get(telegramId);
                            if (batchSet != null) {
                                batchSet.remove(tempId);
                            }
                        });
                    });
        }
    }
    
    private void checkBatchCompletion(Long telegramId, Set<String> batch, Queue<JsonObject> queue, long timerId) {
        if (batch == null || batch.isEmpty()) {
            // Batch is empty, start next one if available
            if (queue != null && !queue.isEmpty()) {
                TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(telegramId);
                startNewBatch(telegramId, telegramVerticle, queue);
            }
            return;
        }
        
        // Check status of all files in the current batch
        DataVerticle.fileRepository.getFiles(0, Map.of(
                "telegramId", String.valueOf(telegramId),
                "limit", "1000"
        ))
                .onSuccess(result -> {
                    List<FileRecord> allFiles = result.v1();
                    Set<String> fileUniqueIds = new HashSet<>();
                    for (FileRecord fileRecord : allFiles) {
                        fileUniqueIds.add(fileRecord.uniqueId());
                    }
                    
                    Set<String> completedOrFailed = new HashSet<>();
                    
                    // Check each file in the batch
                    for (String uniqueId : new HashSet<>(batch)) {
                        // Find the file record if it exists
                        FileRecord fileRecord = allFiles.stream()
                                .filter(f -> uniqueId.equals(f.uniqueId()))
                                .findFirst()
                                .orElse(null);
                        
                        if (fileRecord == null) {
                            // File not found in database - might have failed to start
                            // Check if it's a failed temp ID
                            if (uniqueId.startsWith("failed-")) {
                                // This was a failed start, remove it
                                completedOrFailed.add(uniqueId);
                            }
                            // Otherwise, keep waiting (file might not be in DB yet)
                            continue;
                        }
                        
                        // Check if file is completed, failed, or idle (meaning it finished)
                        // A file is "done" if it's NOT actively downloading or paused
                        FileRecord.DownloadStatus status = FileRecord.DownloadStatus.valueOf(fileRecord.downloadStatus());
                        if (status != FileRecord.DownloadStatus.downloading && 
                            status != FileRecord.DownloadStatus.paused) {
                            // File is completed, failed, or idle - consider it done
                            completedOrFailed.add(uniqueId);
                        }
                    }
                    
                    // Remove completed/failed files from batch
                    batch.removeAll(completedOrFailed);
                    
                    // If batch is empty, all files are done
                    if (batch.isEmpty()) {
                        log.info("Batch completed for telegramId %d. %d files finished. Starting next batch if available."
                                .formatted(telegramId, completedOrFailed.size()));
                        currentBatches.remove(telegramId);
                        
                        // If queue has more files, start next batch immediately
                        if (queue != null && !queue.isEmpty()) {
                            TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(telegramId);
                            startNewBatch(telegramId, telegramVerticle, queue);
                        }
                    } else {
                        log.debug("Batch in progress for telegramId %d: %d/%d files remaining"
                                .formatted(telegramId, batch.size(), BATCH_SIZE));
                    }
                })
                .onFailure(e -> log.error("Failed to check batch completion for telegramId %d: %s"
                        .formatted(telegramId, e.getMessage())));
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
        handleFileMultiple(ctx, (telegramVerticle, file) -> {
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

                            return files.stream()
                                    .map(f -> {
                                        JsonObject file = (JsonObject) f;
                                        return handler.apply(telegramVerticle, file);
                                    });
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
                .onSuccess(r -> ctx.end())
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
                .onSuccess(r -> ctx.end())
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
    
    // ========== Automation Control API Endpoints ==========
    
    /**
     * GET /api/automations
     * List all automations with their control states.
     */
    private void handleGetAutomations(RoutingContext ctx) {
        SettingAutoRecords autoRecords = AutomationsHolder.INSTANCE.autoRecords();
        JsonArray automations = new JsonArray();
        
        for (SettingAutoRecords.Automation automation : autoRecords.automations) {
            automation.validateControlState();
            JsonObject autoJson = new JsonObject()
                    .put("chatId", automation.chatId)
                    .put("telegramId", automation.telegramId)
                    .put("state", automation.controlState)
                    .put("stateText", automation.getControlState().getText())
                    .put("enabled", automation.download != null && automation.download.enabled);
            automations.add(autoJson);
        }
        
        ctx.json(new JsonObject().put("automations", automations));
    }
    
    /**
     * GET /api/automations/:chatId
     * Get automation status for a specific chat.
     */
    private void handleGetAutomation(RoutingContext ctx) {
        String chatIdStr = ctx.pathParam("chatId");
        if (StrUtil.isBlank(chatIdStr)) {
            ctx.fail(400);
            return;
        }
        
        long chatId = Convert.toLong(chatIdStr);
        SettingAutoRecords autoRecords = AutomationsHolder.INSTANCE.autoRecords();
        SettingAutoRecords.Automation automation = autoRecords.automations.stream()
                .filter(a -> a.chatId == chatId)
                .findFirst()
                .orElse(null);
        
        if (automation == null) {
            ctx.fail(404);
            return;
        }
        
        automation.validateControlState();
        JsonObject response = new JsonObject()
                .put("chatId", automation.chatId)
                .put("telegramId", automation.telegramId)
                .put("state", automation.controlState)
                .put("stateText", automation.getControlState().getText())
                .put("enabled", automation.download != null && automation.download.enabled);
        
        ctx.json(response);
    }
    
    /**
     * POST /api/automations/:chatId/start
     * Start automation (set state to ACTIVE).
     */
    private void handleStartAutomation(RoutingContext ctx) {
        String chatIdStr = ctx.pathParam("chatId");
        if (StrUtil.isBlank(chatIdStr)) {
            ctx.fail(400);
            return;
        }
        
        long chatId = Convert.toLong(chatIdStr);
        SettingAutoRecords autoRecords = AutomationsHolder.INSTANCE.autoRecords();
        SettingAutoRecords.Automation automation = autoRecords.automations.stream()
                .filter(a -> a.chatId == chatId)
                .findFirst()
                .orElse(null);
        
        if (automation == null) {
            ctx.fail(404);
            return;
        }
        
        if (automation.download == null || !automation.download.enabled) {
            ctx.response()
                    .setStatusCode(400)
                    .end(JsonObject.of("error", "Automation is not enabled. Enable download first.").encode());
            return;
        }
        
        automation.setControlState(AutomationControlState.ACTIVE);
        automation.validateControlState();
        
        AutomationsHolder.INSTANCE.saveAutoRecords()
                .onSuccess(v -> {
                    log.info("Started automation for chatId: %d".formatted(chatId));
                    JsonObject response = new JsonObject()
                            .put("chatId", automation.chatId)
                            .put("state", automation.controlState)
                            .put("stateText", automation.getControlState().getText())
                            .put("message", "Automation started");
                    ctx.json(response);
                })
                .onFailure(ctx::fail);
    }
    
    /**
     * POST /api/automations/:chatId/stop
     * Stop automation (set state to STOPPED).
     */
    private void handleStopAutomation(RoutingContext ctx) {
        String chatIdStr = ctx.pathParam("chatId");
        if (StrUtil.isBlank(chatIdStr)) {
            ctx.fail(400);
            return;
        }
        
        long chatId = Convert.toLong(chatIdStr);
        SettingAutoRecords autoRecords = AutomationsHolder.INSTANCE.autoRecords();
        SettingAutoRecords.Automation automation = autoRecords.automations.stream()
                .filter(a -> a.chatId == chatId)
                .findFirst()
                .orElse(null);
        
        if (automation == null) {
            ctx.fail(404);
            return;
        }
        
        automation.setControlState(AutomationControlState.STOPPED);
        automation.validateControlState();
        
        AutomationsHolder.INSTANCE.saveAutoRecords()
                .onSuccess(v -> {
                    log.info("Stopped automation for chatId: %d".formatted(chatId));
                    JsonObject response = new JsonObject()
                            .put("chatId", automation.chatId)
                            .put("state", automation.controlState)
                            .put("stateText", automation.getControlState().getText())
                            .put("message", "Automation stopped");
                    ctx.json(response);
                })
                .onFailure(ctx::fail);
    }
    
    /**
     * POST /api/automations/:chatId/state
     * Set automation state directly (0=STOPPED, 1=IDLE, 2=ACTIVE).
     */
    private void handleSetAutomationState(RoutingContext ctx) {
        String chatIdStr = ctx.pathParam("chatId");
        if (StrUtil.isBlank(chatIdStr)) {
            ctx.fail(400);
            return;
        }
        
        JsonObject body = ctx.body().asJsonObject();
        Integer stateValue = body.getInteger("state");
        if (stateValue == null) {
            ctx.response()
                    .setStatusCode(400)
                    .end(JsonObject.of("error", "Missing 'state' parameter").encode());
            return;
        }
        
        if (!AutomationControlState.isValid(stateValue)) {
            ctx.response()
                    .setStatusCode(400)
                    .end(JsonObject.of("error", "Invalid state value. Must be 0 (STOPPED), 1 (IDLE), or 2 (ACTIVE)").encode());
            return;
        }
        
        long chatId = Convert.toLong(chatIdStr);
        SettingAutoRecords autoRecords = AutomationsHolder.INSTANCE.autoRecords();
        SettingAutoRecords.Automation automation = autoRecords.automations.stream()
                .filter(a -> a.chatId == chatId)
                .findFirst()
                .orElse(null);
        
        if (automation == null) {
            ctx.fail(404);
            return;
        }
        
        AutomationControlState newState = AutomationControlState.fromValue(stateValue);
        
        // Validate: can't set ACTIVE if disabled
        if (newState == AutomationControlState.ACTIVE && (automation.download == null || !automation.download.enabled)) {
            ctx.response()
                    .setStatusCode(400)
                    .end(JsonObject.of("error", "Cannot set state to ACTIVE when automation is disabled").encode());
            return;
        }
        
        automation.setControlState(newState);
        automation.validateControlState();
        
        AutomationsHolder.INSTANCE.saveAutoRecords()
                .onSuccess(v -> {
                    log.info("Set automation state to %s for chatId: %d".formatted(newState.getText(), chatId));
                    JsonObject response = new JsonObject()
                            .put("chatId", automation.chatId)
                            .put("state", automation.controlState)
                            .put("stateText", automation.getControlState().getText())
                            .put("message", "Automation state updated");
                    ctx.json(response);
                })
                .onFailure(ctx::fail);
    }
    
    /**
     * GET /api/automations/:chatId/health
     * Get automation health status including activity tracking.
     */
    private void handleGetAutomationHealth(RoutingContext ctx) {
        String chatIdStr = ctx.pathParam("chatId");
        if (StrUtil.isBlank(chatIdStr)) {
            ctx.fail(400);
            return;
        }
        
        long chatId = Convert.toLong(chatIdStr);
        SettingAutoRecords autoRecords = AutomationsHolder.INSTANCE.autoRecords();
        SettingAutoRecords.Automation automation = autoRecords.automations.stream()
                .filter(a -> a.chatId == chatId)
                .findFirst()
                .orElse(null);
        
        if (automation == null) {
            ctx.fail(404);
            return;
        }
        
        automation.validateControlState();
        
        // Get file statistics
        DataVerticle.fileRepository.countByStatus(automation.telegramId, FileRecord.DownloadStatus.downloading)
                .compose(downloadingCount -> {
                    return DataVerticle.fileRepository.getFiles(automation.chatId, Map.of(
                            "downloadStatus", "idle",
                            "limit", "1"
                    )).map(idleResult -> {
                        long idleCount = idleResult.v3(); // total count
                        return Tuple2.of(downloadingCount != null ? downloadingCount : 0, idleCount);
                    });
                })
                .onSuccess(result -> {
                    int downloading = result.v1();
                    long pending = result.v2();
                    
                    JsonObject response = new JsonObject()
                            .put("chatId", automation.chatId)
                            .put("state", automation.controlState)
                            .put("stateText", automation.getControlState().getText())
                            .put("isRunning", automation.isActive())
                            .put("pendingFiles", pending)
                            .put("downloadingFiles", downloading);
                    
                    ctx.json(response);
                })
                .onFailure(ctx::fail);
    }
}
