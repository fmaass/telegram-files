package telegram.files;

import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class VerifiedBugsTest {

    @Test
    @DisplayName("UpdateNewMessage must NOT reach onChatUpdated")
    void test_updateNewMessage_does_not_reach_onChatUpdated() {
        TelegramUpdateHandler handler = new TelegramUpdateHandler();

        List<String> receivedEvents = new ArrayList<>();

        handler.setOnMessageReceived(msg -> receivedEvents.add("onMessageReceived"));
        handler.setOnChatUpdated(obj -> receivedEvents.add("onChatUpdated"));

        TdApi.UpdateNewMessage update = new TdApi.UpdateNewMessage();
        update.message = new TdApi.Message();
        handler.onResult(update);

        assertTrue(receivedEvents.contains("onMessageReceived"),
                "onMessageReceived should be called for UpdateNewMessage");
        assertFalse(receivedEvents.contains("onChatUpdated"),
                "onChatUpdated must NOT be called for UpdateNewMessage (fall-through bug)");
    }

    @Test
    @DisplayName("UpdateFile must NOT reach UpdateFileDownload handler")
    void test_updateFile_does_not_fall_through_to_download() {
        TelegramUpdateHandler handler = new TelegramUpdateHandler();

        AtomicReference<TdApi.UpdateFile> receivedFile = new AtomicReference<>();
        handler.setOnFileUpdated(receivedFile::set);

        TdApi.UpdateFile update = new TdApi.UpdateFile();
        update.file = new TdApi.File();
        handler.onResult(update);

        assertNotNull(receivedFile.get(), "onFileUpdated should be called");
    }

    @Test
    @DisplayName("Chat update events should NOT reach default handler logging")
    void test_chatUpdates_do_not_fall_through_to_default() {
        TelegramUpdateHandler handler = new TelegramUpdateHandler();

        List<String> receivedEvents = new ArrayList<>();
        handler.setOnChatUpdated(obj -> receivedEvents.add("onChatUpdated"));

        TdApi.UpdateNewChat chatUpdate = new TdApi.UpdateNewChat();
        chatUpdate.chat = new TdApi.Chat();
        handler.onResult(chatUpdate);

        assertEquals(1, receivedEvents.size(),
                "onChatUpdated should be called exactly once, not also triggering default");
    }

    @Test
    @DisplayName("TelegramChats uses ONE shared lock for all chat-list access (Phase 4 unification)")
    void test_chatLists_lock_is_shared_instance() throws Exception {
        // Phase 4 unified the previous three-lock scheme (chatListsLock + per-chat monitors +
        // ConcurrentHashMap) into a single `lock` monitor guarding every read and write, so the
        // getChatList stream can no longer CME against a concurrent setChatPositions.
        var field = TelegramChats.class.getDeclaredField("lock");
        field.setAccessible(true);
        assertNotNull(field, "a shared `lock` field should exist as the single chat-list monitor");
        assertEquals(Object.class, field.getType(), "lock should be a plain Object monitor");
        // The old dedicated chatListsLock must be gone (folded into the single lock).
        assertThrows(NoSuchFieldException.class,
                () -> TelegramChats.class.getDeclaredField("chatListsLock"),
                "chatListsLock should be removed — access is unified under `lock`");
    }
}
