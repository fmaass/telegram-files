package telegram.files;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Promise;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

/**
 * A {@link TelegramVerticle} whose {@code start()} boots HERMETICALLY — with a FAKE
 * {@link TelegramClient.Sender} instead of a real native TDLib client — for the Phase-5 E2E gateway.
 * It NEVER constructs a native TDLib {@link Client} (which would abort the JVM at teardown) and never
 * touches the real Telegram network. Deployed only when
 * {@link telegram.files.http.HermeticGateway#isEnabled()} (non-prod + opt-in), so it is absent from a
 * prod build.
 * <p>
 * The fake sender answers the TDLib queries the download TRIGGER makes with a SYNTHETIC downloadable
 * photo message ({@code GetMessage}/{@code GetChatHistory}/{@code GetFile}/{@code AddFileToDownloads}),
 * so a REAL {@code POST /api/downloads} runs its full stable-identity resolution + Phase-2 atomic claim
 * end to end. The volatile fileId and the stable uniqueId are DETERMINISTIC from chatId+messageId, so
 * the claimed row matches what {@code /files} and the completion address. The bulk enrichment lookup
 * ({@code GetMessages} plural, {@code GetMessageThread}) returns "not found" (resolved with
 * {@code ignoreException=true}), so the DB-backed file record is projected without needing a full
 * message map. No native TDLib client and no real network are ever touched.
 */
public class HermeticTelegramVerticle extends TelegramVerticle {

    private static final Log log = LogFactory.get();

    private final long telegramId;

    public HermeticTelegramVerticle(String rootPath, long telegramId) {
        super(rootPath);
        this.telegramId = telegramId;
    }

    /** Deterministic volatile fileId for a (chat, message). */
    public static int hermeticFileId(long chatId, long messageId) {
        return Math.abs(Long.hashCode(chatId * 31 + messageId)) % 2_000_000 + 1;
    }

    /**
     * Deterministic stable uniqueId for a (chat, message). It is derived from the fileId so that BOTH
     * {@code GetFile(fileId).remote.uniqueId} (the row key startDownload uses) and the message photo's
     * uniqueId agree — the trigger, {@code /files}, and the completion all address the SAME row.
     */
    public static String hermeticUniqueId(long chatId, long messageId) {
        return "hermetic-file-" + hermeticFileId(chatId, messageId);
    }

    @Override
    public void start(Promise<Void> startPromise) {
        log.warn("Deploying HERMETIC telegram verticle (telegramId=%d) — fake sender, no native TDLib. "
                + "This MUST NOT happen in prod.".formatted(telegramId));
        startHermetic(startPromise, telegramId, fakeSender());
    }

    /**
     * The fake TDLib send primitive. The download trigger's single-message lookups
     * ({@code GetMessage}/{@code GetChatHistory}) and {@code GetFile} return a synthetic downloadable
     * photo; {@code AddFileToDownloads} succeeds; bulk enrichment ({@code GetMessages} plural) and
     * {@code GetMessageThread} return not-found (ignored downstream). Runs the handler inline;
     * {@link TelegramClient#execute} marshals the completion onto the owning context.
     */
    @SuppressWarnings("rawtypes")
    static TelegramClient.Sender fakeSender() {
        return (TdApi.Function query, Client.ResultHandler handler) -> {
            TdApi.Object result;
            switch (query.getConstructor()) {
                case TdApi.GetMessage.CONSTRUCTOR -> {
                    TdApi.GetMessage q = (TdApi.GetMessage) query;
                    result = photoMessage(q.chatId, q.messageId);
                }
                case TdApi.GetChatHistory.CONSTRUCTOR -> {
                    TdApi.GetChatHistory q = (TdApi.GetChatHistory) query;
                    // getChatHistory(fromMessageId=messageId+1) — the target is messageId.
                    long messageId = q.fromMessageId - 1;
                    TdApi.Messages messages = new TdApi.Messages();
                    messages.messages = new TdApi.Message[]{photoMessage(q.chatId, messageId)};
                    messages.totalCount = 1;
                    result = messages;
                }
                case TdApi.GetFile.CONSTRUCTOR -> {
                    // Reverse the deterministic fileId is not needed: the trigger passes the fileId it
                    // resolved from GetMessage, whose uniqueId we can rebuild only from chat+message.
                    // GetFile is called with that fileId; return a file carrying the matching uniqueId.
                    // We cannot recover chat/message from the fileId, so map via a thread-local set by
                    // the immediately-preceding GetMessage is overkill — instead the uniqueId is encoded
                    // deterministically in the file below using the fileId as the seed is NOT stable.
                    // Simpler and correct: GetFile returns a file whose uniqueId is derived from the
                    // fileId, and photoMessage uses the SAME derivation — so they always agree.
                    TdApi.GetFile q = (TdApi.GetFile) query;
                    result = fileForId(q.fileId);
                }
                case TdApi.AddFileToDownloads.CONSTRUCTOR -> {
                    TdApi.AddFileToDownloads q = (TdApi.AddFileToDownloads) query;
                    result = fileForId(q.fileId);
                }
                default -> result = new TdApi.Error(404, "hermetic: not supported");
            }
            handler.onResult(result);
        };
    }

    /** A synthetic photo message for (chatId, messageId) with a deterministic downloadable file. */
    static TdApi.Message photoMessage(long chatId, long messageId) {
        TdApi.Message m = new TdApi.Message();
        m.id = messageId;
        m.chatId = chatId;
        m.date = (int) (System.currentTimeMillis() / 1000);

        TdApi.MessagePhoto content = new TdApi.MessagePhoto();
        TdApi.Photo photo = new TdApi.Photo();
        TdApi.PhotoSize size = new TdApi.PhotoSize();
        size.type = "y";
        size.width = 100;
        size.height = 100;
        size.photo = fileForChatMessage(chatId, messageId);
        photo.sizes = new TdApi.PhotoSize[]{size};
        content.photo = photo;
        content.caption = new TdApi.FormattedText("", new TdApi.TextEntity[0]);
        m.content = content;
        return m;
    }

    /** File whose id/uniqueId are deterministic from (chatId, messageId). */
    static TdApi.File fileForChatMessage(long chatId, long messageId) {
        // uniqueId is derived from the fileId (see fileForId), which equals hermeticUniqueId(chat,msg)
        // — so the message photo and GetFile(fileId) always report the SAME uniqueId.
        return fileForId(hermeticFileId(chatId, messageId));
    }

    /** File whose uniqueId is derived from the fileId (used when only the fileId is known). */
    static TdApi.File fileForId(int fileId) {
        TdApi.File f = new TdApi.File();
        f.id = fileId;
        f.size = 4;
        f.expectedSize = 4;
        TdApi.RemoteFile remote = new TdApi.RemoteFile();
        remote.uniqueId = "hermetic-file-" + fileId;
        remote.id = "hermetic-remote-" + fileId;
        f.remote = remote;
        f.local = null; // not downloaded yet -> startDownload proceeds to the claim path
        return f;
    }
}
