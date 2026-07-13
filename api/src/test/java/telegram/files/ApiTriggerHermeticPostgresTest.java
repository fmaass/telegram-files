package telegram.files;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.templates.SqlTemplate;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import telegram.files.repository.FileRecord;

import java.util.HashMap;
import java.util.Map;

/**
 * Real-Postgres tests for the Phase-5 {@code POST /api/downloads} trigger logic that drive the REAL
 * {@link TelegramVerticle#resolveCurrentFileId}/{@link TelegramVerticle#triggerDownload}/
 * {@link TelegramVerticle#startDownload} path via a HERMETIC verticle (a fake {@link TelegramClient.Sender},
 * no native TDLib). Covers:
 * <ul>
 *   <li><b>stable identity</b>: the trigger resolves the CURRENT fileId from chat+message internally,
 *       and it works even when the caller's remembered fileId is stale (a different value);</li>
 *   <li><b>lost-claim → NOT a false success</b>: triggering a download for a row that is already
 *       {@code downloading} does not falsely re-claim — it surfaces the real state (a conflict).</li>
 * </ul>
 */
class ApiTriggerHermeticPostgresTest extends PostgresIntegrationTest {

    private static final long TG = 7L;
    private static final long CHAT = 100L;
    private static final long MSG = 200L;
    private static final int CURRENT_FILE_ID = 555;
    private static final String UID = "uid-photo";

    /** A fake sender that answers the TDLib calls startDownload / resolveCurrentFileId make. */
    @SuppressWarnings("rawtypes")
    private static TelegramClient.Sender photoSender() {
        return (TdApi.Function query, Client.ResultHandler handler) -> {
            TdApi.Object result;
            switch (query.getConstructor()) {
                case TdApi.GetMessage.CONSTRUCTOR, TdApi.GetChatHistory.CONSTRUCTOR ->
                        result = photoMessageOrHistory(query);
                case TdApi.GetFile.CONSTRUCTOR -> result = fileFor(((TdApi.GetFile) query).fileId);
                case TdApi.GetMessageThread.CONSTRUCTOR -> result = new TdApi.Error(404, "no thread");
                case TdApi.AddFileToDownloads.CONSTRUCTOR -> result = fileFor(CURRENT_FILE_ID);
                default -> result = new TdApi.Ok();
            }
            handler.onResult(result);
        };
    }

    private static TdApi.Object photoMessageOrHistory(TdApi.Function query) {
        TdApi.Message message = photoMessage();
        if (query.getConstructor() == TdApi.GetChatHistory.CONSTRUCTOR) {
            TdApi.Messages messages = new TdApi.Messages();
            messages.messages = new TdApi.Message[]{message};
            messages.totalCount = 1;
            return messages;
        }
        return message;
    }

    private static TdApi.File fileFor(int id) {
        TdApi.File f = new TdApi.File();
        f.id = id;
        f.size = 1024;
        f.expectedSize = 1024;
        TdApi.RemoteFile remote = new TdApi.RemoteFile();
        remote.uniqueId = UID;
        remote.id = "remote-" + UID;
        f.remote = remote;
        f.local = null; // not yet downloaded -> startDownload proceeds to the claim path
        return f;
    }

    private static TdApi.Message photoMessage() {
        TdApi.Message m = new TdApi.Message();
        m.id = MSG;
        m.chatId = CHAT;
        m.date = (int) (System.currentTimeMillis() / 1000);

        TdApi.MessagePhoto content = new TdApi.MessagePhoto();
        TdApi.Photo photo = new TdApi.Photo();
        TdApi.PhotoSize size = new TdApi.PhotoSize();
        size.type = "y";
        size.width = 100;
        size.height = 100;
        size.photo = fileFor(CURRENT_FILE_ID);
        photo.sizes = new TdApi.PhotoSize[]{size};
        content.photo = photo;
        content.caption = new TdApi.FormattedText("", new TdApi.TextEntity[0]);
        m.content = content;
        return m;
    }

    /** Remove any hermetic verticle registered by a prior test (the registry is process-static). */
    private void clearRegisteredVerticles() {
        for (TelegramVerticle tv : new java.util.ArrayList<>(TelegramVerticles.getAll())) {
            TelegramVerticles.remove(tv);
        }
    }

    private Future<TelegramVerticle> deployHermetic(Vertx vertx) {
        clearRegisteredVerticles();
        return assertReachedPostgres(vertx)
                .compose(v -> vertx.deployVerticle(new DataVerticle()))
                .compose(v -> {
                    // A hermetic verticle bound to the photoSender; register it as the live account.
                    HermeticTelegramVerticle hv = new HermeticTelegramVerticle(
                            Config.TELEGRAM_ROOT + "/hermetic-trigger-" + TG, TG) {
                        @Override
                        public void start(io.vertx.core.Promise<Void> sp) {
                            startHermetic(sp, TG, photoSender());
                        }
                    };
                    TelegramVerticles.add(hv);
                    return vertx.deployVerticle(hv).map(id -> (TelegramVerticle) hv);
                });
    }

    @Test
    @DisplayName("trigger: resolves the CURRENT fileId from stable identity even when the hint is stale")
    void stableIdentityResolvesCurrentFileId(Vertx vertx, VertxTestContext ctx) {
        deployHermetic(vertx)
                .compose(tv -> tv.resolveCurrentFileId(CHAT, MSG))
                .onComplete(ctx.succeeding(fileId -> ctx.verify(() -> {
                    Assertions.assertEquals(CURRENT_FILE_ID, fileId,
                            "the live fileId from the message must be resolved, not a caller-supplied hint");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("trigger: stable-identity claim works after a simulated fileId change (stale hint ignored)")
    void triggerClaimsViaStableIdentity(Vertx vertx, VertxTestContext ctx) {
        deployHermetic(vertx)
                // Pass a STALE fileId hint (999) — the resolver must ignore it and use the live 555.
                .compose(tv -> tv.triggerDownload(CHAT, MSG, 999))
                .compose(trigger -> {
                    ctx.verify(() -> {
                        Assertions.assertTrue(trigger.claimed(),
                                "THIS trigger must report it won the claim");
                        Assertions.assertEquals(TelegramVerticle.ClaimOutcome.CLAIMED, trigger.outcome());
                    });
                    return DataVerticle.fileRepository.getByUniqueId(trigger.record().uniqueId());
                })
                .onComplete(ctx.succeeding(record -> ctx.verify(() -> {
                    Assertions.assertNotNull(record, "the row must exist after the trigger");
                    Assertions.assertTrue(record.isDownloadStatus(FileRecord.DownloadStatus.downloading),
                            "stable-identity trigger must claim (idle->downloading), was: " + record.downloadStatus());
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("trigger: TWO concurrent triggers race the SAME idle row -> exactly one CLAIMED, the other LOST (no false 202)")
    void concurrentTriggersSingleWinnerNoFalseSuccess(Vertx vertx, VertxTestContext ctx) {
        // A REAL concurrent CAS race: two triggers resolve the same message and both attempt the atomic
        // idle->downloading claim. Exactly one wins (CLAIMED); the LOSER — even though the row now reads
        // 'downloading' (set by the WINNER) — MUST report LOST, never claimed. This is the exact bug the
        // ApiResourceHandlers:72 fix addresses: the verdict comes from THIS call's claim result, not the
        // row state. Against the old "re-read row => 202 claimed=true" behavior BOTH would report claimed
        // and this assertion (exactly one winner) would FAIL.
        deployHermetic(vertx)
                .compose(tv -> {
                    // Fire both triggers concurrently at the same idle row (no pre-seed).
                    Future<TelegramVerticle.DownloadTrigger> a = tv.triggerDownload(CHAT, MSG, 999);
                    Future<TelegramVerticle.DownloadTrigger> b = tv.triggerDownload(CHAT, MSG, 999);
                    return Future.all(a, b);
                })
                .compose(cf -> {
                    TelegramVerticle.DownloadTrigger r1 = cf.resultAt(0);
                    TelegramVerticle.DownloadTrigger r2 = cf.resultAt(1);
                    long claimedCount = java.util.stream.Stream.of(r1, r2)
                            .filter(TelegramVerticle.DownloadTrigger::claimed).count();
                    // The loser observes the row as already 'downloading' (winner set it). Depending on
                    // interleaving it either loses the atomic CAS (LOST) or short-circuits on the
                    // already-active guard (ALREADY_ACTIVE) — BOTH are correctly NOT-claimed. The bug
                    // would make the loser report CLAIMED by inferring success from the 'downloading' row.
                    long notClaimedCount = java.util.stream.Stream.of(r1, r2)
                            .filter(t -> !t.claimed()).count();
                    ctx.verify(() -> {
                        Assertions.assertEquals(1, claimedCount,
                                "EXACTLY ONE concurrent trigger may report CLAIMED (got " + claimedCount
                                + "); a false 202 on the loser would make this 2. Outcomes: "
                                + r1.outcome() + ", " + r2.outcome());
                        Assertions.assertEquals(1, notClaimedCount,
                                "the OTHER concurrent trigger must NOT be claimed (200/409, never a false 202)");
                    });
                    return Future.all(statusOf(), activeAttemptCount());
                })
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    Assertions.assertEquals("downloading", cf.resultAt(0), "row is downloading (the winner)");
                    Assertions.assertEquals(1, (int) cf.resultAt(1),
                            "exactly one ACTIVE attempt exists (the loser minted none)");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("trigger: TWO concurrent RE-downloads of the SAME paused row -> exactly one CLAIMED, other not-claimed")
    void concurrentPausedRedownloadSingleWinner(Vertx vertx, VertxTestContext ctx) {
        concurrentRedownloadSingleWinner(vertx, ctx, FileRecord.DownloadStatus.paused);
    }

    @Test
    @DisplayName("trigger: TWO concurrent RE-downloads of the SAME error row -> exactly one CLAIMED, other not-claimed")
    void concurrentErrorRedownloadSingleWinner(Vertx vertx, VertxTestContext ctx) {
        concurrentRedownloadSingleWinner(vertx, ctx, FileRecord.DownloadStatus.error);
    }

    /**
     * A REAL concurrent CAS race on a PAUSED/ERROR row's RE-download: two triggers both attempt the
     * atomic {@code <state>->downloading} claim (which mints the owning attempt). Exactly one wins
     * (CLAIMED); the other MUST report NOT-claimed — never CLAIMED. Against the previous behavior
     * (paused/error fall-through set CLAIMED unconditionally without a CAS gate) BOTH would report
     * CLAIMED and the "exactly one" assertion FAILS. Also asserts exactly one active attempt (the
     * winner retired the row's prior attempt and minted one; the loser minted none).
     */
    private void concurrentRedownloadSingleWinner(Vertx vertx, VertxTestContext ctx,
                                                  FileRecord.DownloadStatus fromState) {
        deployHermetic(vertx)
                // Seed the row in the paused/error state WITH a lingering active attempt (the real
                // shape: the un-owned status chokepoint parks a paused row without retiring its attempt).
                .compose(tv -> insertRow(fromState.name())
                        .compose(v -> insertActiveAttempt("prior-attempt"))
                        .map(tv))
                .compose(tv -> {
                    Future<TelegramVerticle.DownloadTrigger> a = tv.triggerDownload(CHAT, MSG, CURRENT_FILE_ID);
                    Future<TelegramVerticle.DownloadTrigger> b = tv.triggerDownload(CHAT, MSG, CURRENT_FILE_ID);
                    return Future.all(a, b);
                })
                .compose(cf -> {
                    TelegramVerticle.DownloadTrigger r1 = cf.resultAt(0);
                    TelegramVerticle.DownloadTrigger r2 = cf.resultAt(1);
                    long claimedCount = java.util.stream.Stream.of(r1, r2)
                            .filter(TelegramVerticle.DownloadTrigger::claimed).count();
                    long notClaimedCount = java.util.stream.Stream.of(r1, r2)
                            .filter(t -> !t.claimed()).count();
                    ctx.verify(() -> {
                        Assertions.assertEquals(1, claimedCount,
                                "EXACTLY ONE concurrent " + fromState + " re-download may report CLAIMED (got "
                                + claimedCount + "). Outcomes: " + r1.outcome() + ", " + r2.outcome());
                        Assertions.assertEquals(1, notClaimedCount,
                                "the OTHER concurrent " + fromState + " re-download must NOT be claimed");
                    });
                    return Future.all(statusOf(), activeAttemptCount());
                })
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    Assertions.assertEquals("downloading", cf.resultAt(0), "row is downloading (the winner)");
                    Assertions.assertEquals(1, (int) cf.resultAt(1),
                            "exactly one ACTIVE attempt (winner retired the prior one and minted one; loser none)");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("HTTP: TWO concurrent POST /api/downloads at the same idle row -> exactly one 202 claimed=true, other 200 claimed=false")
    void concurrentHttpTriggersSingleWinner(Vertx vertx, VertxTestContext ctx) {
        // Drives the ACTUAL HTTP handler (ApiResourceHandlers.triggerDownload) end to end so its
        // outcome->response mapping is covered: exactly one request gets 202/claimed=true; the other
        // gets 200/claimed=false (never a false 202). Against the false-success bug both would be 202.
        deployHarness(vertx)
                .compose(port -> {
                    WebClient wc = client(vertx);
                    // Both requests resolve to the SAME fresh idle row (the fake sender maps this
                    // chat+message to uid-photo); the atomic CAS is the single-winner gate.
                    JsonObject body = new JsonObject()
                            .put("telegramId", TG).put("chatId", CHAT).put("messageId", MSG);
                    Future<HttpResponse<io.vertx.core.buffer.Buffer>> a =
                            wc.post(port, "127.0.0.1", "/api/downloads").sendJsonObject(body);
                    Future<HttpResponse<io.vertx.core.buffer.Buffer>> b =
                            wc.post(port, "127.0.0.1", "/api/downloads").sendJsonObject(body);
                    return Future.all(a, b).onComplete(ar -> wc.close());
                })
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    HttpResponse<io.vertx.core.buffer.Buffer> r1 = cf.resultAt(0);
                    HttpResponse<io.vertx.core.buffer.Buffer> r2 = cf.resultAt(1);
                    int claimed202 = 0;
                    int notClaimed200 = 0;
                    for (HttpResponse<io.vertx.core.buffer.Buffer> r : java.util.List.of(r1, r2)) {
                        JsonObject b = r.bodyAsJsonObject();
                        boolean claimedFlag = Boolean.TRUE.equals(b.getBoolean("claimed"));
                        if (r.statusCode() == 202 && claimedFlag) {
                            claimed202++;
                        } else if (r.statusCode() == 200 && !claimedFlag) {
                            notClaimed200++;
                        }
                    }
                    Assertions.assertEquals(1, claimed202,
                            "EXACTLY ONE HTTP trigger may return 202 claimed=true (got " + claimed202
                            + "). Statuses: " + r1.statusCode() + "/" + r2.statusCode()
                            + " bodies: " + r1.bodyAsString() + " | " + r2.bodyAsString());
                    Assertions.assertEquals(1, notClaimed200,
                            "the OTHER HTTP trigger must return 200 claimed=false (never a false 202)");
                    ctx.completeNow();
                })));
    }

    // ---- HTTP harness (real Router + WebClient over the hermetic verticle) ---------------------

    private WebClient client(Vertx vertx) {
        return WebClient.create(vertx, new WebClientOptions().setDefaultHost("127.0.0.1"));
    }

    /** Deploy the hermetic verticle AND an HTTP server built from HttpVerticle.initRouter(). */
    private Future<Integer> deployHarness(Vertx vertx) {
        return deployHermetic(vertx).compose(tv -> {
            Promise<Integer> started = Promise.promise();
            vertx.deployVerticle(new AbstractVerticle() {
                @Override
                public void start(Promise<Void> sp) {
                    HttpVerticle http = new HttpVerticle();
                    http.init(vertx, context);
                    HttpServer server = vertx.createHttpServer();
                    server.requestHandler(http.initRouter())
                            .listen(0)
                            .onSuccess(s -> {
                                started.complete(s.actualPort());
                                sp.complete();
                            })
                            .onFailure(err -> {
                                started.fail(err);
                                sp.fail(err);
                            });
                }
            });
            return started.future();
        });
    }

    private Future<Void> insertRow(String status) {
        Map<String, Object> p = new HashMap<>();
        p.put("uniqueId", UID);
        p.put("status", status);
        return SqlTemplate.forUpdate(DataVerticle.pool, """
                        INSERT INTO file_record
                            (id, unique_id, telegram_id, chat_id, message_id, date, has_sensitive_content, size,
                             downloaded_size, type, download_status, transfer_status, start_date, scan_state, download_priority)
                        VALUES
                            (555, #{uniqueId}, 7, 100, 200, 1000, false, 1024, 0,
                             'photo', #{status}, 'idle', 1, 'idle', 0)
                        """)
                .execute(p).mapEmpty();
    }

    private Future<Void> insertActiveAttempt(String attemptId) {
        return SqlTemplate.forUpdate(DataVerticle.pool, """
                        INSERT INTO download_attempt
                            (attempt_id, unique_id, lease_owner, lease_expires_at, status, created_at, updated_at)
                        VALUES (#{a}, #{u}, 'prior', NULL, 'active', 1, 1)
                        """)
                .execute(Map.of("a", attemptId, "u", UID)).mapEmpty();
    }

    private Future<Integer> activeAttemptCount() {
        return SqlTemplate.forQuery(DataVerticle.pool,
                        "SELECT COUNT(*) AS c FROM download_attempt WHERE unique_id = #{u} AND status = 'active'")
                .execute(Map.of("u", UID))
                .map(rs -> rs.iterator().next().getInteger("c"));
    }

    private Future<String> statusOf() {
        return SqlTemplate.forQuery(DataVerticle.pool,
                        "SELECT download_status FROM file_record WHERE unique_id = #{u}")
                .execute(Map.of("u", UID))
                .map(rs -> rs.size() > 0 ? rs.iterator().next().getString("download_status") : null);
    }
}
