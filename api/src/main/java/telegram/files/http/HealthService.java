package telegram.files.http;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.drinkless.tdlib.TdApi;
import telegram.files.Config;
import telegram.files.DataVerticle;
import telegram.files.TelegramVerticle;
import telegram.files.TelegramVerticles;

import java.util.List;

/**
 * Health assessment for {@code GET /api/health} (and the {@code /health} alias).
 * <p>
 * Health is defined by two REAL checks, never an internal 200:
 * <ul>
 *   <li><b>DB round-trip</b> — a live {@code SELECT 1} against the pool. If the DB is unreachable
 *       the service is UNHEALTHY (503).</li>
 *   <li><b>TDLib liveness</b> — for every CONFIGURED account, the Phase-4 volatile lifecycle fields
 *       ({@link TelegramVerticle#authorized}, {@link TelegramVerticle#lastConnectionState}) are read
 *       to report whether the client is alive. A configured account whose client is not authorized is
 *       reported as {@code degraded} but does NOT by itself fail health (auth is a user action, not an
 *       app fault).</li>
 * </ul>
 * <b>Zero configured accounts = HEALTHY</b>: a fresh install with a reachable DB and the app up is
 * healthy — there is simply nothing to be logged into yet. The overall verdict is UNHEALTHY only when
 * the DB round-trip fails.
 */
public final class HealthService {

    private static final Log log = LogFactory.get();

    private HealthService() {
    }

    /** The assessed health, with the HTTP status the handler should return. */
    public record Health(boolean healthy, JsonObject body) {
        public int statusCode() {
            return healthy ? 200 : 503;
        }
    }

    public static Future<Health> assess() {
        return dbRoundTrip()
                .map(dbOk -> {
                    JsonObject body = new JsonObject();
                    body.put("status", dbOk ? "UP" : "DOWN");
                    body.put("version", telegram.files.Start.VERSION);

                    JsonObject checks = new JsonObject();
                    checks.put("db", new JsonObject()
                            .put("status", dbOk ? "UP" : "DOWN"));

                    JsonArray accounts = new JsonArray();
                    List<TelegramVerticle> all = TelegramVerticles.getAll();
                    for (TelegramVerticle tv : all) {
                        accounts.add(new JsonObject()
                                .put("id", String.valueOf(tv.getId()))
                                .put("authorized", tv.authorized)
                                .put("connectionState", connectionStateName(tv.lastConnectionState))
                                .put("status", tv.authorized ? "UP" : "degraded"));
                    }
                    checks.put("telegram", new JsonObject()
                            // Zero configured accounts is explicitly healthy (fresh install).
                            .put("configuredAccounts", all.size())
                            .put("accounts", accounts));

                    body.put("checks", checks);
                    // Overall health is gated ONLY on the DB round-trip. Un-authorized accounts are a
                    // user action, not an app fault, so they never flip the app to unhealthy.
                    return new Health(dbOk, body);
                });
    }

    /** A live {@code SELECT 1} against the pool; false (not a failed future) when the DB is down. */
    private static Future<Boolean> dbRoundTrip() {
        if (DataVerticle.pool == null) {
            return Future.succeededFuture(false);
        }
        return DataVerticle.pool.query("SELECT 1").execute()
                .map(rs -> true)
                .otherwise(err -> {
                    log.warn("Health DB round-trip failed: %s".formatted(err.getMessage()));
                    return false;
                });
    }

    private static String connectionStateName(TdApi.ConnectionState state) {
        if (state == null) {
            return "unknown";
        }
        return switch (state.getConstructor()) {
            case TdApi.ConnectionStateReady.CONSTRUCTOR -> "ready";
            case TdApi.ConnectionStateConnecting.CONSTRUCTOR -> "connecting";
            case TdApi.ConnectionStateConnectingToProxy.CONSTRUCTOR -> "connectingToProxy";
            case TdApi.ConnectionStateUpdating.CONSTRUCTOR -> "updating";
            case TdApi.ConnectionStateWaitingForNetwork.CONSTRUCTOR -> "waitingForNetwork";
            default -> "unknown";
        };
    }
}
