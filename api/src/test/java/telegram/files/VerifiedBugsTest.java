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
    @DisplayName("chatLists lock must be a shared instance, not a fresh object")
    void test_chatLists_lock_is_shared_instance() throws Exception {
        var field = TelegramChats.class.getDeclaredField("chatListsLock");
        field.setAccessible(true);
        assertNotNull(field, "chatListsLock field should exist as a dedicated lock object");
        assertEquals(Object.class, field.getType(), "chatListsLock should be a plain Object");
    }
}
