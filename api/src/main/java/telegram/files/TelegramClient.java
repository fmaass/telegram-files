package telegram.files;

import cn.hutool.core.util.TypeUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.io.IOError;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public class TelegramClient {
    private static final Log log = LogFactory.get();

    /**
     * The "send a query, get one result" primitive. In production this delegates to the native
     * {@link Client#send}; in unit tests a fake implementation is injected so the Java-side lifecycle
     * (execute tracking, timeout, rejectAllOutstanding, marshaling) can be exercised WITHOUT
     * constructing a real native TDLib {@link Client} — constructing+abandoning a real native client
     * races its native receive thread and aborts the JVM (SIGABRT) at teardown.
     */
    @FunctionalInterface
    interface Sender {
        // Raw TdApi.Function to match the native Client#send signature exactly (so client::send is a
        // valid method reference).
        @SuppressWarnings("rawtypes")
        void send(TdApi.Function query, Client.ResultHandler resultHandler);
    }

    private Client client;

    // The send primitive (native in prod, fake in tests). Volatile: set under the initialize lock,
    // read from execute() on arbitrary threads.
    private volatile Sender sender;

    private boolean initialized = false;

    // The Vert.x context that OWNS this client (the TelegramVerticle's context). ALL TDLib
    // ingress — update callbacks AND per-request result callbacks — is marshaled onto it so
    // handlers/continuations run on the verticle's own thread instead of TDLib's native receive
    // thread (D4/D5 root fix). Null only in bare unit tests that never call initialize().
    private volatile Context context;

    // Outstanding request promises, keyed by a monotonic request id. Tracked so they can be
    // REJECTED when the client closes (D5 cancellation) — a per-request result callback that
    // never fires after close would otherwise hang the Java future forever.
    private final Map<Long, Promise<? extends TdApi.Object>> outstanding = new ConcurrentHashMap<>();

    private final AtomicLong requestSeq = new AtomicLong();

    // Set once the client is closed (Close result received, or AuthorizationStateClosed). New
    // sends fail fast and all outstanding promises are rejected exactly once.
    private volatile boolean closed = false;

    static {
        Client.setLogMessageHandler(0, new LogMessageHandler());

        try {
            Client.execute(new TdApi.SetLogVerbosityLevel(Config.TELEGRAM_LOG_LEVEL));
            Client.execute(new TdApi.SetLogStream(new TdApi.LogStreamFile(Path.of(Config.LOG_PATH, "tdlib.log").toString(),
                    1 << 27, false)));
        } catch (Client.ExecutionException error) {
            throw new IOError(new IOException("Write access to the current directory is required"));
        }
    }

    public void initialize(Client.ResultHandler updateHandler,
                           Client.ExceptionHandler updateExceptionHandler,
                           Client.ExceptionHandler defaultExceptionHandler) {
        initialize(updateHandler, updateExceptionHandler, defaultExceptionHandler, Vertx.currentContext());
    }

    /**
     * Initialize the TDLib client, binding it to {@code owningContext}. When non-null, the update
     * {@link Client.ResultHandler} is wrapped so every update is re-dispatched onto that context
     * via {@link Context#runOnContext} — TDLib delivers updates on its native receive thread, and
     * this is the single seam that serializes ALL update ingress onto the verticle's own thread.
     * Per-request result callbacks are marshaled through the same context in {@link #execute}.
     */
    public void initialize(Client.ResultHandler updateHandler,
                           Client.ExceptionHandler updateExceptionHandler,
                           Client.ExceptionHandler defaultExceptionHandler,
                           Context owningContext) {
        synchronized (this) {
            if (!initialized) {
                this.context = owningContext;
                Client.ResultHandler marshaledUpdateHandler = marshalUpdateHandler(updateHandler);
                client = Client.create(marshaledUpdateHandler, updateExceptionHandler, defaultExceptionHandler);
                this.sender = client::send;
                initialized = true;
            }
        }
    }

    /**
     * Test-only initialization: bind an owning context and a FAKE send primitive without creating a
     * real native TDLib client. Lets the Java-side lifecycle be tested without racing a native
     * receive thread (which aborts the JVM at teardown).
     */
    void initializeForTest(Context owningContext, Sender fakeSender) {
        synchronized (this) {
            if (!initialized) {
                this.context = owningContext;
                this.sender = fakeSender;
                initialized = true;
            }
        }
    }

    // Package-private for tests.
    Client.ResultHandler marshalUpdateHandler(Client.ResultHandler delegate) {
        if (delegate == null) {
            return object -> { };
        }
        Context ctx = this.context;
        if (ctx == null) {
            // No owning context (bare test client): run inline, preserving legacy behavior.
            return delegate;
        }
        return object -> ctx.runOnContext(_ -> delegate.onResult(object));
    }

    // Package-private for tests: bind an owning context without creating a native client, so the
    // marshaling seam can be exercised in isolation.
    void setContextForTest(Context ctx) {
        this.context = ctx;
    }

    @SuppressWarnings("unchecked")
    public <R extends TdApi.Object> Future<R> execute(TdApi.Function<R> method) {
        return execute(method, false);
    }

    @SuppressWarnings("unchecked")
    public <R extends TdApi.Object> Future<R> execute(TdApi.Function<R> method, boolean ignoreException) {
        log.trace("Execute method: %s".formatted(TypeUtil.getTypeArgument(method.getClass())));
        if (!initialized) {
            throw new IllegalStateException("Client is not initialized");
        }
        if (closed) {
            return Future.failedFuture(new IllegalStateException("Client is closed"));
        }

        Promise<R> promise = Promise.promise();
        long id = requestSeq.incrementAndGet();
        outstanding.put(id, promise);

        Context ctx = this.context;
        sender.send(method, object -> {
            // Marshal the raw TDLib result callback onto the owning context so the promise
            // completes — and every continuation attached to it runs — on the verticle's own
            // thread, not TDLib's native receive thread (D4/D5 root fix). completeFromResult is
            // idempotent (keyed by id) so a close-reject and a late callback cannot double-complete.
            if (ctx == null) {
                completeFromResult(id, promise, object, ignoreException);
            } else {
                ctx.runOnContext(_ -> completeFromResult(id, promise, object, ignoreException));
            }
        });

        if (closed) {
            // Raced with a close between the guard and the send: reject now so we never hang if the
            // native callback was dropped by the closing client.
            rejectOutstanding(id, new IllegalStateException("Client is closed"));
        }
        return promise.future();
    }

    private <R extends TdApi.Object> void completeFromResult(long id, Promise<R> promise,
                                                             TdApi.Object object, boolean ignoreException) {
        if (outstanding.remove(id) == null) {
            // Already rejected by close (or completed) — do not double-complete.
            return;
        }
        if (object.getConstructor() == TdApi.Error.CONSTRUCTOR) {
            if (ignoreException) {
                promise.complete(null);
                return;
            }
            promise.fail(new TelegramRunException((TdApi.Error) object));
        } else {
            @SuppressWarnings("unchecked")
            R result = (R) object;
            promise.complete(result);
        }
    }

    private void rejectOutstanding(long id, Throwable cause) {
        Promise<? extends TdApi.Object> p = outstanding.remove(id);
        if (p != null) {
            p.fail(cause);
        }
    }

    /**
     * Reject EVERY outstanding request promise (D5 cancellation on close). Called when the TDLib
     * client has closed so no per-request callback will ever fire again — without this the Java
     * futures for in-flight requests would hang forever. Idempotent.
     */
    public void rejectAllOutstanding(Throwable cause) {
        closed = true;
        for (Long id : outstanding.keySet().toArray(new Long[0])) {
            Promise<? extends TdApi.Object> p = outstanding.remove(id);
            if (p != null) {
                p.fail(cause);
            }
        }
    }

    // Package-private for tests: number of request promises still awaiting a TDLib callback.
    int outstandingCount() {
        return outstanding.size();
    }

    // Package-private for tests: register a promise into the outstanding map exactly as execute()
    // does, so the close/cancellation path can be exercised deterministically without racing a live
    // TDLib callback. Returns the id so a test can also complete it via the normal path if desired.
    long trackForTest(Promise<? extends TdApi.Object> promise) {
        long id = requestSeq.incrementAndGet();
        outstanding.put(id, promise);
        return id;
    }

    // Package-private for tests: whether the given request id is still tracked as outstanding.
    boolean isTrackedForTest(long id) {
        return outstanding.containsKey(id);
    }

    boolean isClosed() {
        return closed;
    }

    public <R extends TdApi.Object> Future<R> execute(TdApi.Function<R> method, long timeoutMs, Vertx vertx) {
        return withTimeout(execute(method), timeoutMs, vertx);
    }

    /**
     * Wrap {@code source} with a timeout: the returned future fails with {@link TimeoutException}
     * after {@code timeoutMs} if {@code source} has not completed, otherwise mirrors it. Extracted
     * (package-private) so the timeout behavior is unit-testable against an arbitrary never-completing
     * future without a live TDLib client. This is TIMEOUT-ONLY (it fails the Java future; it does NOT
     * cancel the TDLib op — cancellation-on-close is {@link #rejectAllOutstanding}).
     */
    static <R> Future<R> withTimeout(Future<R> source, long timeoutMs, Vertx vertx) {
        Promise<R> promise = Promise.promise();

        long timerId = vertx.setTimer(timeoutMs, _ -> {
            if (!promise.future().isComplete()) {
                promise.fail(new TimeoutException("Operation timed out after " + timeoutMs + " ms"));
            }
        });

        source.onComplete(ar -> {
            vertx.cancelTimer(timerId);
            if (promise.future().isComplete()) {
                return;
            }
            if (ar.succeeded()) {
                promise.complete(ar.result());
            } else {
                promise.fail(ar.cause());
            }
        });

        return promise.future();
    }

    public Client getNativeClient() {
        return client;
    }

    private static class LogMessageHandler implements Client.LogMessageHandler {
        @Override
        public void onLogMessage(int verbosityLevel, String message) {
            log.debug("TDLib: %s".formatted(message));
        }
    }
}
