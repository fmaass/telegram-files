package telegram.files;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 (D4/D5 root fix): ALL TDLib ingress — update callbacks AND per-request result callbacks —
 * is marshaled onto the owning verticle's Vert.x context, not run on TDLib's native receive thread.
 */
class TelegramCallbackContextTest {

    static Vertx vertx;

    @BeforeAll
    static void setUp() {
        // TelegramClient's static initializer opens a TDLib log stream at Config.LOG_PATH; ensure the
        // directory exists before the class is first referenced (parallel-suite isolation).
        new java.io.File(Config.LOG_PATH).mkdirs();
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void tearDown() {
        vertx.close();
    }

    /** Deploy a throwaway verticle and return its context. */
    private static Context deployAndGetContext() {
        AtomicReference<Context> ctxRef = new AtomicReference<>();
        CompletableFuture<Void> deployed = new CompletableFuture<>();
        vertx.deployVerticle(new AbstractVerticle() {
            @Override
            public void start() {
                ctxRef.set(context);
                deployed.complete(null);
            }
        }).onFailure(deployed::completeExceptionally);
        try {
            deployed.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return ctxRef.get();
    }

    @Test
    @DisplayName("update callbacks are marshaled onto the owning verticle context, not the calling thread")
    void updateHandlerRunsOnContext() throws Exception {
        Context ctx = deployAndGetContext();
        TelegramClient client = new TelegramClient();
        client.setContextForTest(ctx);

        AtomicReference<Thread> handlerThread = new AtomicReference<>();
        CompletableFuture<Void> ran = new CompletableFuture<>();
        Client.ResultHandler marshaled = client.marshalUpdateHandler(object -> {
            handlerThread.set(Thread.currentThread());
            // If we are on the verticle context, this must be the context we deployed.
            assertSame(ctx, Vertx.currentContext(),
                    "update handler must run on the owning verticle context");
            ran.complete(null);
        });

        // Invoke from THIS test thread (simulating TDLib's native receive thread). The handler must
        // NOT run inline here — it must be dispatched onto the verticle context.
        Thread callingThread = Thread.currentThread();
        marshaled.onResult(new TdApi.UpdateConnectionState(new TdApi.ConnectionStateReady()));

        ran.get(5, TimeUnit.SECONDS);
        assertNotNull(handlerThread.get());
        assertNotSame(callingThread, handlerThread.get(),
                "update handler must not run on the calling (native) thread");
    }

    @Test
    @DisplayName("request-result callbacks complete on the owning verticle context, not the delivering thread")
    void requestResultRunsOnContext() throws Exception {
        Context ctx = deployAndGetContext();
        TelegramClient client = new TelegramClient();

        // FAKE send that delivers its result from a SEPARATE thread — modeling TDLib's native receive
        // thread — WITHOUT creating a real native client (which would race its receive thread and
        // abort the JVM at teardown). The result-marshaling seam must dispatch the completion onto the
        // owning verticle context.
        AtomicReference<Thread> deliveringThread = new AtomicReference<>();
        client.initializeForTest(ctx, (query, handler) -> {
            Thread t = new Thread(() -> handler.onResult(new TdApi.Chats(0, new long[0])));
            deliveringThread.set(t);
            t.start();
        });

        AtomicReference<Context> completionContext = new AtomicReference<>();
        AtomicReference<Thread> completionThread = new AtomicReference<>();
        CompletableFuture<Void> done = new CompletableFuture<>();

        Future<TdApi.Chats> f = client.execute(new TdApi.GetChats(new TdApi.ChatListMain(), 10));
        f.onComplete(_ -> {
            completionContext.set(Vertx.currentContext());
            completionThread.set(Thread.currentThread());
            done.complete(null);
        });

        done.get(10, TimeUnit.SECONDS);
        assertSame(ctx, completionContext.get(),
                "the request-result continuation must run on the owning verticle context");
        assertNotSame(deliveringThread.get(), completionThread.get(),
                "the continuation must NOT run on the delivering (native) thread — it must be marshaled");
    }

    @Test
    @DisplayName("with no owning context, the update handler runs inline (legacy bare-client behavior)")
    void noContextRunsInline() {
        TelegramClient client = new TelegramClient(); // context stays null
        AtomicReference<Thread> handlerThread = new AtomicReference<>();
        Client.ResultHandler marshaled = client.marshalUpdateHandler(object -> handlerThread.set(Thread.currentThread()));

        marshaled.onResult(new TdApi.UpdateConnectionState(new TdApi.ConnectionStateReady()));
        assertSame(Thread.currentThread(), handlerThread.get(),
                "with no context the handler runs inline on the calling thread");
    }
}
