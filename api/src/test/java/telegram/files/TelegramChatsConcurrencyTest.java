package telegram.files;

import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 (D4) thread-safety of {@link TelegramChats}.
 * <p>
 * TDLib update callbacks mutate the ordered chat lists while HTTP request handlers read them via
 * {@link TelegramChats#getChatList}. Before the single-lock unification, getChatList streamed the
 * NavigableSet unsynchronized and could throw {@link java.util.ConcurrentModificationException}
 * against a concurrent setChatPositions. These tests hammer both sides concurrently and assert no
 * CME and internally-consistent snapshots.
 */
class TelegramChatsConcurrencyTest {

    private static TdApi.Chat chat(long id, String title, long order) {
        TdApi.Chat c = new TdApi.Chat();
        c.id = id;
        c.title = title;
        c.type = new TdApi.ChatTypePrivate(id);
        TdApi.ChatPosition pos = new TdApi.ChatPosition();
        pos.list = new TdApi.ChatListMain();
        pos.order = order;
        c.positions = new TdApi.ChatPosition[]{pos};
        return c;
    }

    private static TdApi.UpdateChatPosition positionUpdate(long chatId, long order) {
        TdApi.ChatPosition pos = new TdApi.ChatPosition();
        pos.list = new TdApi.ChatListMain();
        pos.order = order;
        return new TdApi.UpdateChatPosition(chatId, pos);
    }

    @Test
    @DisplayName("concurrent getChatList reads while setChatPositions mutates: no CME, consistent snapshots")
    void concurrentReadWhileMutate() throws InterruptedException {
        TelegramChats chats = new TelegramChats(null);

        // Seed a population of chats.
        int n = 200;
        for (int i = 0; i < n; i++) {
            chats.onChatUpdated(new TdApi.UpdateNewChat(chat(1000 + i, "chat-" + i, n - i)));
        }

        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean stop = new AtomicBoolean(false);
        // The reader signals its own completion; the writer runs until stopped and then signals.
        CountDownLatch readerDone = new CountDownLatch(1);
        CountDownLatch writerDone = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger readIterations = new java.util.concurrent.atomic.AtomicInteger(0);

        // Writer: continuously reshuffle positions (the exact setChatPositions path that mutated the
        // NavigableSet the reader streams).
        Thread writer = new Thread(() -> {
            try {
                long tick = 0;
                while (!stop.get()) {
                    for (int i = 0; i < n; i++) {
                        chats.onChatUpdated(positionUpdate(1000 + i, (tick + i) % (n + 1)));
                    }
                    tick++;
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                writerDone.countDown();
            }
        });

        // Reader: continuously list chats (streams the ordered set + reads chat fields).
        Thread reader = new Thread(() -> {
            try {
                for (int r = 0; r < 5000 && failure.get() == null; r++) {
                    List<TdApi.Chat> list = chats.getChatList(null, null, 50, false, null);
                    // Consistency: every returned chat is non-null and carries a title/id (a torn
                    // read would surface as a null field or a NPE).
                    for (TdApi.Chat c : list) {
                        assertNotNull(c, "returned chat must not be null");
                        assertTrue(c.id >= 1000, "returned chat must have a valid id");
                        assertNotNull(c.title, "returned chat must have a title");
                    }
                    assertTrue(list.size() <= 50, "limit must be honored");
                    readIterations.incrementAndGet();
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                readerDone.countDown();
            }
        });

        writer.start();
        reader.start();

        // The reader MUST finish its run within the timeout (real assertion, no `|| true`).
        assertTrue(readerDone.await(30, TimeUnit.SECONDS),
                "reader did not complete its concurrent run within 30s");
        stop.set(true);
        assertTrue(writerDone.await(30, TimeUnit.SECONDS),
                "writer did not stop within 30s");

        // The concurrency invariant: no CME / torn read surfaced on either thread, AND the reader
        // actually performed its work concurrently with the writer (not a no-op fast-exit).
        if (failure.get() != null) {
            fail("Concurrent access threw: " + failure.get(), failure.get());
        }
        assertTrue(readIterations.get() >= 5000,
                "reader must have completed all 5000 concurrent list operations (got " + readIterations.get() + ")");
    }

    @Test
    @DisplayName("getChatList returns a snapshot: mutating the live chat after the call does not tear the returned object")
    void getChatListReturnsSnapshot() {
        TelegramChats chats = new TelegramChats(null);
        chats.onChatUpdated(new TdApi.UpdateNewChat(chat(42L, "original-title", 10)));

        List<TdApi.Chat> before = chats.getChatList(null, null, 10, false, null);
        assertEquals(1, before.size());
        TdApi.Chat snapshot = before.getFirst();
        assertEquals("original-title", snapshot.title);

        // Mutate the LIVE chat via an update.
        chats.onChatUpdated(new TdApi.UpdateChatTitle(42L, "changed-title"));

        // The previously-returned snapshot must be UNAFFECTED (it was copied under the lock).
        assertEquals("original-title", snapshot.title,
                "a snapshot returned by getChatList must not be mutated by a later chat update");
        // A fresh read reflects the change.
        assertEquals("changed-title", chats.getChatList(null, null, 10, false, null).getFirst().title);
    }
}
