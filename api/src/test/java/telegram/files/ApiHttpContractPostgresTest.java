package telegram.files;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.templates.SqlTemplate;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP-level contract tests for the Phase-5 resource surface over REAL Postgres and a REAL Vert.x
 * HTTP server built from {@link HttpVerticle#initRouter()}. Covers:
 * <ul>
 *   <li>{@code /api/health} — healthy with ZERO configured accounts (DB reachable + app up);</li>
 *   <li>{@code /api/metrics} — explicit metric names + {@code text/plain; version=0.0.4} content type;</li>
 *   <li>alias parity — {@code /health} vs {@code /api/health}, {@code /version} vs {@code /api/version}
 *       produce identical results;</li>
 *   <li>{@code POST /api/downloads} — 400 on missing fields, 404 on an unknown account.</li>
 * </ul>
 * The server is a bare {@code initRouter()} (no child verticles / TDLib), so these tests exercise the
 * real routing, aliasing, health and metrics wiring without a live Telegram client.
 */
class ApiHttpContractPostgresTest extends PostgresIntegrationTest {

    /** A verticle that stands up ONLY the router + an HTTP server on an ephemeral port. */
    static final class RouterHarness extends AbstractVerticle {
        volatile int port;

        @Override
        public void start(Promise<Void> startPromise) {
            HttpVerticle http = new HttpVerticle();
            // Give the HttpVerticle this verticle's Vert.x + an empty config so initRouter() works.
            http.init(vertx, context);
            HttpServer server = vertx.createHttpServer();
            server.requestHandler(http.initRouter())
                    .listen(0)
                    .onSuccess(s -> {
                        port = s.actualPort();
                        startPromise.complete();
                    })
                    .onFailure(startPromise::fail);
        }
    }

    private Future<Integer> deployHarness(Vertx vertx) {
        return assertReachedPostgres(vertx)
                .compose(v -> vertx.deployVerticle(new DataVerticle()))
                .compose(v -> {
                    RouterHarness harness = new RouterHarness();
                    return vertx.deployVerticle(harness).map(id -> harness.port);
                });
    }

    private WebClient client(Vertx vertx) {
        return WebClient.create(vertx, new WebClientOptions().setDefaultHost("127.0.0.1"));
    }

    @Test
    @DisplayName("HTTP: /api/health is HEALTHY (200) with zero configured accounts and DB reachable")
    void healthZeroAccountsHealthy(Vertx vertx, VertxTestContext ctx) {
        deployHarness(vertx).compose(port -> {
            WebClient wc = client(vertx);
            return wc.get(port, "127.0.0.1", "/api/health").send()
                    .onComplete(ar -> wc.close());
        }).onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
            Assertions.assertEquals(200, resp.statusCode(), "zero-account fresh install must be healthy");
            JsonObject body = resp.bodyAsJsonObject();
            Assertions.assertEquals("UP", body.getString("status"));
            Assertions.assertEquals(0, body.getJsonObject("checks").getJsonObject("telegram")
                    .getInteger("configuredAccounts"), "no accounts configured");
            ctx.completeNow();
        })));
    }

    @Test
    @DisplayName("HTTP: /api/metrics exposes explicit metric names with the Prometheus content type")
    void metricsScrape(Vertx vertx, VertxTestContext ctx) {
        String uid = "uid-metric";
        deployHarness(vertx).compose(port -> insertRow(uid, "completed").map(port))
                .compose(port -> {
                    WebClient wc = client(vertx);
                    return wc.get(port, "127.0.0.1", "/api/metrics").send()
                            .onComplete(ar -> wc.close());
                })
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    Assertions.assertEquals(200, resp.statusCode());
                    String ct = resp.getHeader("Content-Type");
                    Assertions.assertTrue(ct != null && ct.contains("version=0.0.4"),
                            "content type must be the Prometheus text format, was: " + ct);
                    String text = resp.bodyAsString();
                    Assertions.assertTrue(text.contains("telegram_files_up 1"), "up gauge present");
                    Assertions.assertTrue(text.contains("telegram_files_download_files{status=\"completed\"}"),
                            "per-status gauge present with bounded status label");
                    Assertions.assertTrue(text.contains("telegram_files_accounts_total 0"),
                            "accounts_total gauge present");
                    // Cardinality guard: the only label key present is `status` (no per-file labels).
                    Assertions.assertFalse(text.contains("uniqueId="), "must NOT emit a per-file label");
                    Assertions.assertFalse(text.contains("message_id="), "must NOT emit a per-message label");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("HTTP: alias parity — /health == /api/health and /version == /api/version")
    void aliasParity(Vertx vertx, VertxTestContext ctx) {
        deployHarness(vertx).compose(port -> {
            WebClient wc = client(vertx);
            return Future.all(
                    wc.get(port, "127.0.0.1", "/health").send(),
                    wc.get(port, "127.0.0.1", "/api/health").send(),
                    wc.get(port, "127.0.0.1", "/version").send(),
                    wc.get(port, "127.0.0.1", "/api/version").send()
            ).onComplete(ar -> wc.close());
        }).onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
            io.vertx.ext.web.client.HttpResponse<io.vertx.core.buffer.Buffer> legacyHealth = cf.resultAt(0);
            io.vertx.ext.web.client.HttpResponse<io.vertx.core.buffer.Buffer> apiHealth = cf.resultAt(1);
            io.vertx.ext.web.client.HttpResponse<io.vertx.core.buffer.Buffer> legacyVersion = cf.resultAt(2);
            io.vertx.ext.web.client.HttpResponse<io.vertx.core.buffer.Buffer> apiVersion = cf.resultAt(3);

            Assertions.assertEquals(apiHealth.statusCode(), legacyHealth.statusCode(),
                    "/health and /api/health must return the same status");
            Assertions.assertEquals(apiHealth.bodyAsJsonObject().getString("status"),
                    legacyHealth.bodyAsJsonObject().getString("status"),
                    "/health and /api/health must report the same health status");

            Assertions.assertEquals(apiVersion.statusCode(), legacyVersion.statusCode());
            Assertions.assertEquals(apiVersion.bodyAsJsonObject(), legacyVersion.bodyAsJsonObject(),
                    "/version and /api/version must be identical");
            ctx.completeNow();
        })));
    }

    @Test
    @DisplayName("HTTP: POST /api/downloads -> 400 on missing fields, 404 on an unknown account")
    void downloadTriggerValidation(Vertx vertx, VertxTestContext ctx) {
        AtomicInteger portRef = new AtomicInteger();
        deployHarness(vertx).compose(port -> {
            portRef.set(port);
            WebClient wc = client(vertx);
            // Missing chatId/messageId -> 400.
            return wc.post(port, "127.0.0.1", "/api/downloads")
                    .sendJsonObject(new JsonObject().put("telegramId", "123"))
                    .compose(bad -> {
                        Assertions.assertEquals(400, bad.statusCode(), "missing fields -> 400");
                        // Full body but unknown account -> 404.
                        return wc.post(port, "127.0.0.1", "/api/downloads")
                                .sendJsonObject(new JsonObject()
                                        .put("telegramId", "999999")
                                        .put("chatId", 100L)
                                        .put("messageId", 200L));
                    })
                    .onComplete(ar -> wc.close());
        }).onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
            Assertions.assertEquals(404, resp.statusCode(), "unknown account -> 404");
            ctx.completeNow();
        })));
    }

    private Future<Void> insertRow(String uniqueId, String status) {
        Map<String, Object> p = new HashMap<>();
        p.put("uniqueId", uniqueId);
        p.put("status", status);
        return SqlTemplate.forUpdate(DataVerticle.pool, """
                        INSERT INTO file_record
                            (id, unique_id, telegram_id, chat_id, message_id, date, has_sensitive_content, size,
                             downloaded_size, type, download_status, transfer_status, scan_state, download_priority)
                        VALUES
                            (1, #{uniqueId}, 10, 100, 200, 1000, false, 1000, 0,
                             'file', #{status}, 'idle', 'idle', 0)
                        """)
                .execute(p).mapEmpty();
    }
}
