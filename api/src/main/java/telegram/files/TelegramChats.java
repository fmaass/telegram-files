package telegram.files;

import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import org.drinkless.tdlib.TdApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Ordered chat index backing the chat-list endpoints.
 * <p>
 * Thread-safety (D4): TDLib update callbacks (now marshaled onto the owning verticle's context)
 * mutate this structure while HTTP request handlers on other contexts read it via
 * {@link #getChatList}. ALL access — every read AND every write, including the ordered-set streams
 * and the mutation of {@link TdApi.Chat} value objects — is serialized under ONE monitor
 * ({@code lock}). This replaces the previous three-lock scheme (a {@code ConcurrentHashMap}, a
 * {@code chatListsLock}, and per-{@code Chat} {@code synchronized} blocks) whose ordered-set stream
 * in {@code getChatList} ran unsynchronized and could throw {@link java.util.ConcurrentModificationException}
 * against a concurrent {@code setChatPositions}. {@code getChatList} builds its whole result INSIDE
 * the lock and SNAPSHOTS the {@link TdApi.Chat} value objects (a shallow field copy) so a concurrent
 * update cannot tear a returned chat's fields after the method returns.
 */
public class TelegramChats {
    private static final Log log = LogFactory.get();

    private final TelegramClient client;

    // Single monitor guarding chats, mainChatList, archivedChatList and the two haveFull* flags.
    private final Object lock = new Object();

    private final Map<Long, TdApi.Chat> chats = new HashMap<>();

    private final NavigableSet<OrderedChat> mainChatList = new TreeSet<>();

    private final NavigableSet<OrderedChat> archivedChatList = new TreeSet<>();

    private boolean haveFullMainChatList = false;

    private boolean haveFullArchivedChatList = false;

    public TelegramChats(TelegramClient client) {
        this.client = client;
    }

    public List<TdApi.Chat> getChatList(Long activatedChatId, String query, int limit, boolean archived, Set<Long> priorityChatIds) {
        synchronized (lock) {
            NavigableSet<OrderedChat> source = archived ? archivedChatList : mainChatList;
            List<TdApi.Chat> allChats = new ArrayList<>();
            for (OrderedChat orderedChat : source) {
                TdApi.Chat chat = chats.get(orderedChat.chatId());
                if (chat == null) {
                    continue;
                }
                if (!StrUtil.isBlank(query) && !StrUtil.containsIgnoreCase(chat.title, query)) {
                    continue;
                }
                allChats.add(chat);
            }

            if (priorityChatIds != null && !priorityChatIds.isEmpty()) {
                allChats.sort((c1, c2) -> {
                    boolean p1 = priorityChatIds.contains(c1.id);
                    boolean p2 = priorityChatIds.contains(c2.id);
                    if (p1 && !p2) return -1;
                    if (!p1 && p2) return 1;
                    return 0;
                });
            }

            List<TdApi.Chat> chatList = new ArrayList<>();
            for (TdApi.Chat chat : allChats) {
                if (chatList.size() >= limit) {
                    break;
                }
                chatList.add(snapshot(chat));
            }

            if (activatedChatId != null) {
                TdApi.Chat activatedChat = chats.get(activatedChatId);
                if (activatedChat != null && chatList.stream().noneMatch(c -> c.id == activatedChatId)) {
                    chatList.addFirst(snapshot(activatedChat));
                }
            }

            return chatList;
        }
    }

    /**
     * Shallow snapshot of a {@link TdApi.Chat} taken UNDER the lock, so a concurrent update mutating
     * the live chat's fields after this method returns cannot tear the returned object. Copies the
     * reference fields the read path consumes (id, title, photo, positions, type, unread state,
     * lastMessage); the positions array is copied so a later {@code setChatPositions} replacing it
     * does not alias the snapshot.
     */
    private static TdApi.Chat snapshot(TdApi.Chat chat) {
        TdApi.Chat copy = new TdApi.Chat();
        copy.id = chat.id;
        copy.type = chat.type;
        copy.title = chat.title;
        copy.photo = chat.photo;
        copy.positions = chat.positions == null ? null : chat.positions.clone();
        copy.lastMessage = chat.lastMessage;
        copy.unreadCount = chat.unreadCount;
        copy.lastReadInboxMessageId = chat.lastReadInboxMessageId;
        copy.lastReadOutboxMessageId = chat.lastReadOutboxMessageId;
        copy.messageSenderId = chat.messageSenderId;
        copy.permissions = chat.permissions;
        copy.unreadMentionCount = chat.unreadMentionCount;
        copy.unreadReactionCount = chat.unreadReactionCount;
        return copy;
    }

    public TdApi.Chat getChat(long chatId) {
        synchronized (lock) {
            TdApi.Chat chat = chats.get(chatId);
            return chat == null ? null : snapshot(chat);
        }
    }

    public void loadMainChatList() {
        boolean shouldLoad;
        synchronized (lock) {
            shouldLoad = !haveFullMainChatList;
        }
        if (!shouldLoad) {
            return;
        }
        // send LoadChats request if there are some unknown chats and have not enough known chats
        client.execute(new TdApi.LoadChats(new TdApi.ChatListMain(), 100))
                .onSuccess(_ -> {
                    // chats had already been received through updates, let's retry request
                    loadMainChatList();
                })
                .onFailure(error -> {
                    if (error instanceof TelegramRunException tre && tre.getError().code == 404) {
                        synchronized (lock) {
                            haveFullMainChatList = true;
                            log.debug("Main chat list is loaded, size: %d".formatted(mainChatList.size()));
                        }
                    }
                });
    }

    public void loadArchivedChatList() {
        boolean shouldLoad;
        synchronized (lock) {
            shouldLoad = !haveFullArchivedChatList;
        }
        if (!shouldLoad) {
            return;
        }
        // send LoadChats request if there are some unknown chats and have not enough known chats
        client.execute(new TdApi.LoadChats(new TdApi.ChatListArchive(), 100))
                .onSuccess(_ -> {
                    // chats had already been received through updates, let's retry request
                    loadArchivedChatList();
                })
                .onFailure(error -> {
                    if (error instanceof TelegramRunException tre && tre.getError().code == 404) {
                        synchronized (lock) {
                            haveFullArchivedChatList = true;
                            log.debug("Archived chat list is loaded, size: %d".formatted(archivedChatList.size()));
                        }
                    }
                });
    }

    public void onChatUpdated(TdApi.Object object) {
        synchronized (lock) {
            switch (object.getConstructor()) {
                case TdApi.UpdateNewChat.CONSTRUCTOR: {
                    TdApi.UpdateNewChat updateNewChat = (TdApi.UpdateNewChat) object;
                    TdApi.Chat chat = updateNewChat.chat;
                    chats.put(chat.id, chat);
                    setChatPositions(chat, chat.positions);
                    break;
                }
                case TdApi.UpdateChatTitle.CONSTRUCTOR: {
                    TdApi.UpdateChatTitle updateChat = (TdApi.UpdateChatTitle) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    if (chat != null) {
                        chat.title = updateChat.title;
                    }
                    break;
                }
                case TdApi.UpdateChatPhoto.CONSTRUCTOR: {
                    TdApi.UpdateChatPhoto updateChat = (TdApi.UpdateChatPhoto) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    if (chat != null) {
                        chat.photo = updateChat.photo;
                    }
                    break;
                }
                case TdApi.UpdateChatReadInbox.CONSTRUCTOR: {
                    TdApi.UpdateChatReadInbox updateChat = (TdApi.UpdateChatReadInbox) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    if (chat != null) {
                        chat.lastReadInboxMessageId = updateChat.lastReadInboxMessageId;
                        chat.unreadCount = updateChat.unreadCount;
                    }
                    break;
                }
                case TdApi.UpdateChatLastMessage.CONSTRUCTOR: {
                    TdApi.UpdateChatLastMessage updateChat = (TdApi.UpdateChatLastMessage) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    if (chat != null) {
                        chat.lastMessage = updateChat.lastMessage;
                        setChatPositions(chat, updateChat.positions);
                    }
                    break;
                }
                case TdApi.UpdateChatPosition.CONSTRUCTOR: {
                    TdApi.UpdateChatPosition updateChat = (TdApi.UpdateChatPosition) object;
                    if (updateChat.position.list.getConstructor() != TdApi.ChatListMain.CONSTRUCTOR
                        && updateChat.position.list.getConstructor() != TdApi.ChatListArchive.CONSTRUCTOR) {
                        break;
                    }

                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    if (chat == null) {
                        break;
                    }
                    int i;
                    for (i = 0; i < chat.positions.length; i++) {
                        if (chat.positions[i].list.getConstructor() == updateChat.position.list.getConstructor()) {
                            break;
                        }
                    }
                    TdApi.ChatPosition[] new_positions = new TdApi.ChatPosition[chat.positions.length + (updateChat.position.order == 0 ? 0 : 1) - (i < chat.positions.length ? 1 : 0)];
                    int pos = 0;
                    if (updateChat.position.order != 0) {
                        new_positions[pos++] = updateChat.position;
                    }
                    for (int j = 0; j < chat.positions.length; j++) {
                        if (j != i) {
                            new_positions[pos++] = chat.positions[j];
                        }
                    }
                    assert pos == new_positions.length;

                    setChatPositions(chat, new_positions);
                    break;
                }
            }
        }
    }

    // Must be called while holding {@code lock}.
    private void setChatPositions(TdApi.Chat chat, TdApi.ChatPosition[] positions) {
        for (TdApi.ChatPosition position : chat.positions) {
            if (position.list.getConstructor() == TdApi.ChatListMain.CONSTRUCTOR) {
                if (!mainChatList.remove(new OrderedChat(chat.id, position))) {
                    log.warn("Chat %d was not found in mainChatList".formatted(chat.id));
                }
            }
            if (position.list.getConstructor() == TdApi.ChatListArchive.CONSTRUCTOR) {
                if (!archivedChatList.remove(new OrderedChat(chat.id, position))) {
                    log.warn("Chat %d was not found in archivedChatList".formatted(chat.id));
                }
            }
        }

        chat.positions = positions;

        for (TdApi.ChatPosition position : chat.positions) {
            if (position.list.getConstructor() == TdApi.ChatListMain.CONSTRUCTOR) {
                if (!mainChatList.add(new OrderedChat(chat.id, position))) {
                    log.warn("Chat %d was already in mainChatList".formatted(chat.id));
                }
            }
            if (position.list.getConstructor() == TdApi.ChatListArchive.CONSTRUCTOR) {
                if (!archivedChatList.add(new OrderedChat(chat.id, position))) {
                    log.warn("Chat %d was already in archivedChatList".formatted(chat.id));
                }
            }
        }
    }

    private record OrderedChat(long chatId, TdApi.ChatPosition position) implements Comparable<OrderedChat> {

        @Override
        public int compareTo(OrderedChat o) {
            if (this.position.order != o.position.order) {
                return o.position.order < this.position.order ? -1 : 1;
            }
            if (this.chatId != o.chatId) {
                return o.chatId < this.chatId ? -1 : 1;
            }
            return 0;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof OrderedChat(long id, TdApi.ChatPosition position1)) {
                return this.chatId == id && this.position.order == position1.order;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(chatId) * 31 + Long.hashCode(position.order);
        }
    }
}
