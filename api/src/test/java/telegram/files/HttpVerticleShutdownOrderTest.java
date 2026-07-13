package telegram.files;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 (D-shutdown): {@link HttpVerticle#stop} drains its owned child verticles in a controlled
 * order — schedulers (AutoDownload, Preload) first, then the transfer drain — BEFORE Start undeploys
 * DataVerticle (the pool). This test verifies the child-undeploy ORDER without a live TDLib account
 * (no TelegramVerticles registered, so the telegram-close leg is a no-op between schedulers and
 * transfer).
 */
class HttpVerticleShutdownOrderTest {

    static Vertx vertx;

    @BeforeAll
    static void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void tearDown() {
        vertx.close();
    }

    /** A verticle that records its name into the shared order list when it is undeployed (stopped). */
    private static class RecordingVerticle extends AbstractVerticle {
        private final String name;
        private final List<String> order;

        RecordingVerticle(String name, List<String> order) {
            this.name = name;
            this.order = order;
        }

        @Override
        public void stop(Promise<Void> stopPromise) {
            order.add(name);
            stopPromise.complete();
        }
    }

    private static String deploy(AbstractVerticle v) {
        CompletableFuture<String> cf = new CompletableFuture<>();
        vertx.deployVerticle(v).onComplete(ar -> {
            if (ar.succeeded()) cf.complete(ar.result());
            else cf.completeExceptionally(ar.cause());
        });
        try {
            return cf.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void set(HttpVerticle v, String field, String value) throws Exception {
        Field f = HttpVerticle.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(v, value);
    }

    @Test
    @DisplayName("stop() undeploys schedulers before the transfer drain, in order")
    void childUndeployOrder() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();

        String autoId = deploy(new RecordingVerticle("autoDownload", order));
        String preloadId = deploy(new RecordingVerticle("preload", order));
        String transferId = deploy(new RecordingVerticle("transfer", order));

        // The HttpVerticle under test needs a vertx binding to call vertx.undeploy(...). Deploy it,
        // then inject the child deployment IDs it owns.
        HttpVerticle http = new HttpVerticle();
        // Deploy with a start override that does nothing but bind vertx/context. We deploy the real
        // HttpVerticle's start would boot the whole stack, so bind vertx via reflection instead.
        Field vertxField = AbstractVerticle.class.getDeclaredField("vertx");
        vertxField.setAccessible(true);
        vertxField.set(http, vertx);

        set(http, "autoDownloadDeploymentId", autoId);
        set(http, "preloadDeploymentId", preloadId);
        set(http, "transferDeploymentId", transferId);

        CompletableFuture<Void> stopped = new CompletableFuture<>();
        Promise<Void> stopPromise = Promise.promise();
        stopPromise.future().onComplete(ar -> {
            if (ar.succeeded()) stopped.complete(null);
            else stopped.completeExceptionally(ar.cause());
        });

        http.stop(stopPromise);
        stopped.get(10, TimeUnit.SECONDS);

        // Order contract: schedulers (autoDownload, preload) drain first; the transfer drain is LAST
        // (after the — here empty — telegram-close leg). No child may undeploy after transfer.
        assertEquals(List.of("autoDownload", "preload", "transfer"), order,
                "children must be drained schedulers-first, transfer-last");
    }
}
