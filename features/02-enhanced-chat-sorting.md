# Feature 02: Enhanced Chat Sorting & Statistics

**Status**: ✅ Active  
**Complexity**: Low  
**First Implemented**: v0.2.5  
**Current Implementation**: Commit a556f6f

---

## Overview

Improves chat list organization by sorting automated/subscribed chats to the top. Also adds detailed download statistics per chat showing breakdown by status (idle, downloading, completed, etc.).

## User Story

**As a user**, I want my automated chats to appear at the top of the chat list, **so that** I can easily find and monitor them without scrolling through all chats.

**As a user**, I want to see detailed statistics per chat, **so that** I know exactly how many files are idle, downloading, or completed.

---

## Feature Behavior

### Chat Sorting
- Chats with active automation appear at top of list
- Currently selected chat always appears first (sticky)
- Other chats appear in default order below
- Sorting is transparent to user (no UI control needed)

### Statistics Enhancement
Instead of just "total files", show breakdown:
```json
{
    "total": 150,
    "idle": 80,
    "downloading": 3,
    "paused": 18,
    "completed": 47,
    "error": 2
}
```

---

## Technical Approach

### Sorting Strategy

**Concept**: Pass a set of "priority chat IDs" (automated chats) to the chat list getter.

```
getChatList(activatedChatId, query, limit, archived, priorityChatIds)
    ↓
1. Fetch all chats matching query
2. Sort: priority chats first, then others
3. Ensure activated chat is at position 0
4. Return sorted list
```

### Statistics Strategy

**Concept**: Use SQL CASE statements to count files by status in a single query.

```sql
SELECT 
    COUNT(*) AS total,
    COUNT(CASE WHEN download_status = 'idle' THEN 1 END) AS idle,
    COUNT(CASE WHEN download_status = 'downloading' THEN 1 END) AS downloading,
    -- ... etc for each status
FROM file_record
WHERE telegram_id = ? AND chat_id = ?
```

---

## Implementation Sketch

### Backend Changes

**1. Modify getChatList to accept priority chat IDs**
```java
// In TelegramChats.java or chat management service
public List<TdApi.Chat> getChatList(
    Long activatedChatId,
    String query,
    int limit,
    boolean archived,
    Set<Long> priorityChatIds  // NEW PARAMETER
) {
    Stream<TdApi.Chat> chatStream = getChatStream(archived)
        .filter(chat -> matchesQuery(chat, query));
    
    List<TdApi.Chat> chatList;
    
    if (priorityChatIds != null && !priorityChatIds.isEmpty()) {
        // Sort with priority chats first
        List<TdApi.Chat> allChats = chatStream.collect(Collectors.toList());
        allChats.sort((c1, c2) -> {
            boolean p1 = priorityChatIds.contains(c1.id);
            boolean p2 = priorityChatIds.contains(c2.id);
            if (p1 && !p2) return -1;   // c1 is priority, c2 is not → c1 first
            if (!p1 && p2) return 1;     // c2 is priority, c1 is not → c2 first
            return 0;                    // Both priority or both not → maintain order
        });
        chatList = allChats.stream().limit(limit).collect(Collectors.toList());
    } else {
        // No priority sorting
        chatList = chatStream.limit(limit).collect(Collectors.toList());
    }
    
    // Ensure activated chat is always first
    if (activatedChatId != null) {
        TdApi.Chat activatedChat = getChat(activatedChatId);
        if (activatedChat != null && !chatList.contains(activatedChat)) {
            chatList.addFirst(activatedChat);
        }
    }
    
    return chatList;
}
```

**2. Get priority chat IDs from automations**
```java
// In TelegramVerticle.java or wherever getChats is called
public Future<JsonObject> getChats(String query, int limit, boolean archived) {
    // Get chat IDs that have automation enabled
    Set<Long> priorityChatIds = AutomationsHolder.INSTANCE
        .autoRecords()
        .getDownloadEnabledItems()
        .stream()
        .filter(auto -> auto.telegramId == this.telegramId)
        .map(auto -> auto.chatId)
        .collect(Collectors.toSet());
    
    List<TdApi.Chat> chats = telegramChats.getChatList(
        activatedChatId,
        query,
        limit,
        archived,
        priorityChatIds  // Pass priority IDs
    );
    
    return convertChatsToJson(chats);
}
```

**3. Add detailed statistics method**
```java
// In FileRepositoryImpl.java
public Future<JsonObject> getChatDownloadStatistics(
    long telegramId, 
    long chatId,
    Integer historySince  // Optional: filter by date
) {
    String query = historySince != null 
        ? """
            SELECT COUNT(*) AS total,
                   COUNT(CASE WHEN download_status = 'idle' THEN 1 END) AS idle,
                   COUNT(CASE WHEN download_status = 'downloading' THEN 1 END) AS downloading,
                   COUNT(CASE WHEN download_status = 'paused' THEN 1 END) AS paused,
                   COUNT(CASE WHEN download_status = 'completed' THEN 1 END) AS completed,
                   COUNT(CASE WHEN download_status = 'error' THEN 1 END) AS error
            FROM file_record
            WHERE telegram_id = ? AND chat_id = ? 
              AND type != 'thumbnail'
              AND date >= ?
          """
        : """
            SELECT COUNT(*) AS total,
                   COUNT(CASE WHEN download_status = 'idle' THEN 1 END) AS idle,
                   -- ... same fields ...
            FROM file_record
            WHERE telegram_id = ? AND chat_id = ? AND type != 'thumbnail'
          """;
    
    return executeQuery(query, telegramId, chatId, historySince)
        .map(row -> JsonObject.of(
            "total", row.getInteger("total"),
            "idle", row.getInteger("idle"),
            "downloading", row.getInteger("downloading"),
            "paused", row.getInteger("paused"),
            "completed", row.getInteger("completed"),
            "error", row.getInteger("error")
        ));
}
```

### Frontend Changes

**No UI changes required** - sorting happens automatically in backend.

Optional: Display statistics in chat list
```tsx
// In chat-select.tsx or equivalent (optional)
<div className="flex justify-between">
    <span>{chat.name}</span>
    {chat.stats && (
        <span className="text-xs text-muted-foreground">
            {chat.stats.completed}/{chat.stats.total}
        </span>
    )}
</div>
```

---

## Integration Points

### Where to Make Changes

1. **TelegramChats Class**
   - Modify getChatList() to accept priorityChatIds parameter
   - Add sorting logic for priority chats

2. **Chat Getter Method**
   - In TelegramVerticle or HTTP handler
   - Fetch automated chat IDs from AutomationsHolder
   - Pass to getChatList()

3. **Statistics Repository**
   - Add getChatDownloadStatistics() method
   - Use CASE statements for status breakdown
   - Support historySince filtering (optional)

4. **Statistics API Endpoint**
   - Endpoint: GET /telegram/:telegramId/chat/:chatId/statistics
   - Returns detailed status breakdown

5. **FileRepository Interface**
   - Add method signature for getChatDownloadStatistics()

---

## Test Cases

### Test 1: Priority Sorting
```
1. Enable automation on chats A, B, C
2. Open chat selection dropdown
3. Expected: Chats A, B, C appear at top
4. Expected: Other chats appear below
5. Expected: Order is stable (doesn't change randomly)
```

### Test 2: Activated Chat Sticky
```
1. Navigate to chat X (not automated)
2. Open chat dropdown
3. Expected: Chat X appears first (even though not automated)
4. Navigate to automated chat Y
5. Expected: Chat Y appears first
```

### Test 3: Statistics Accuracy
```
1. Chat has 100 files:
   - 50 idle
   - 10 downloading
   - 35 completed
   - 5 error
2. Call GET /telegram/1/chat/123/statistics
3. Expected: Returns exact counts above
4. Start downloading 5 more files
5. Wait a moment
6. Call statistics again
7. Expected: downloading = 15, idle = 45
```

### Test 4: Statistics with History Filter
```
1. Set historySince = 2025-06-01
2. Chat has 200 files total:
   - 100 files before June 1
   - 100 files after June 1
3. Call statistics endpoint
4. Expected: total = 100 (only files after cutoff)
```

### Test 5: Empty Chat
```
1. View empty chat (no files)
2. Call statistics endpoint
3. Expected: Returns {total: 0, idle: 0, downloading: 0, ...}
4. Expected: No errors
```

---

## Known Issues & Considerations

### Issue 1: Sorting Stability
- Sorting must be stable (same input = same output)
- Don't re-sort on every render
- Cache automated chat IDs where possible

### Issue 2: Statistics Query Performance
- Counting with CASE statements is efficient
- Query should be <10ms even with 10,000+ files
- Indexes on (telegram_id, chat_id, download_status) help

### Issue 3: Priority Chat Changes
- If automation is disabled, chat should drop from top
- Need to recalculate priority chat IDs dynamically
- Don't cache too aggressively

### Issue 4: Large Chat Lists
- If user has 1,000+ chats, sorting all may be slow
- Consider limiting priority sorting to visible chats only
- Most users have <100 chats, not an issue

---

## Code Examples from v0.3.0 Implementation

### Complete Sorting Implementation
```java
// From TelegramChats.java (lines 36-66)
public List<TdApi.Chat> getChatList(
    Long activatedChatId, 
    String query, 
    int limit, 
    boolean archived, 
    Set<Long> priorityChatIds
) {
    Stream<TdApi.Chat> chatStream = (archived ? archivedChatList : mainChatList)
        .stream()
        .map(OrderedChat::chatId)
        .map(chats::get)
        .filter(Objects::nonNull)
        .filter(chat -> StrUtil.isBlank(query) || StrUtil.containsIgnoreCase(chat.title, query));

    List<TdApi.Chat> chatList;
    if (priorityChatIds != null && !priorityChatIds.isEmpty()) {
        List<TdApi.Chat> allChats = chatStream.collect(Collectors.toList());
        allChats.sort((c1, c2) -> {
            boolean p1 = priorityChatIds.contains(c1.id);
            boolean p2 = priorityChatIds.contains(c2.id);
            if (p1 && !p2) return -1;
            if (!p1 && p2) return 1;
            return 0;
        });
        chatList = allChats.stream().limit(limit).collect(Collectors.toList());
    } else {
        chatList = chatStream.limit(limit).collect(Collectors.toList());
    }

    // Activated chat always first
    if (activatedChatId != null) {
        TdApi.Chat activatedChat = chats.get(activatedChatId);
        if (activatedChat != null && !chatList.contains(activatedChat)) {
            chatList.addFirst(activatedChat);
        }
    }

    return chatList;
}
```

### Statistics Query
```java
// From FileRepositoryImpl.java (lines 365-406)
@Override
public Future<JsonObject> getChatDownloadStatistics(
    long telegramId, 
    long chatId, 
    Integer historySince
) {
    String query = historySince != null ? """
        SELECT COUNT(*) AS total,
               COUNT(CASE WHEN download_status = 'downloading' THEN 1 END) AS downloading,
               COUNT(CASE WHEN download_status = 'paused' THEN 1 END) AS paused,
               COUNT(CASE WHEN download_status = 'completed' THEN 1 END) AS completed,
               COUNT(CASE WHEN download_status = 'error' THEN 1 END) AS error,
               COUNT(CASE WHEN download_status = 'idle' THEN 1 END) AS idle
        FROM file_record
        WHERE telegram_id = #{telegramId} AND chat_id = #{chatId} AND type != 'thumbnail'
          AND date >= #{historySince}
        """ : """
        SELECT COUNT(*) AS total,
               COUNT(CASE WHEN download_status = 'downloading' THEN 1 END) AS downloading,
               COUNT(CASE WHEN download_status = 'paused' THEN 1 END) AS paused,
               COUNT(CASE WHEN download_status = 'completed' THEN 1 END) AS completed,
               COUNT(CASE WHEN download_status = 'error' THEN 1 END) AS error,
               COUNT(CASE WHEN download_status = 'idle' THEN 1 END) AS idle
        FROM file_record
        WHERE telegram_id = #{telegramId} AND chat_id = #{chatId} AND type != 'thumbnail'
        """;
    
    return SqlTemplate.forQuery(sqlClient, query)
        .mapTo(row -> {
            JsonObject result = JsonObject.of();
            result.put("total", row.getInteger("total"));
            result.put("downloading", row.getInteger("downloading"));
            result.put("paused", row.getInteger("paused"));
            result.put("completed", row.getInteger("completed"));
            result.put("error", row.getInteger("error"));
            result.put("idle", row.getInteger("idle"));
            return result;
        })
        .execute(params)
        .map(rs -> rs.size() > 0 ? rs.iterator().next() : JsonObject.of());
}
```

---

## Integration Points

### Where to Make Changes

1. **TelegramChats Class**
   - Add priorityChatIds parameter to getChatList()
   - Implement sorting logic

2. **Chat List Endpoint**
   - Fetch automated chat IDs
   - Pass to getChatList()

3. **FileRepository**
   - Add getChatDownloadStatistics() method

4. **API Endpoint**
   - GET /telegram/:telegramId/chat/:chatId/statistics

---

## Test Cases

### Test 1: Automated Chats Sort to Top
```
1. Enable automation on chat ID 100, 200
2. Call GET /telegram/1/chats
3. Check response
4. Expected: Chats 100 and 200 are in first positions
5. Disable automation on chat 100
6. Call again
7. Expected: Only chat 200 is at top now
```

### Test 2: Activated Chat Sticky
```
1. Activate chat 999 (not automated)
2. Get chat list
3. Expected: Chat 999 is at position 0
4. Expected: Automated chats are at positions 1, 2, ...
```

### Test 3: Statistics Breakdown
```sql
-- Setup: Insert test data
INSERT INTO file_record (telegram_id, chat_id, download_status, type, ...) VALUES
(1, 100, 'idle', 'photo', ...),
(1, 100, 'downloading', 'photo', ...),
(1, 100, 'completed', 'video', ...);

-- Call endpoint
GET /telegram/1/chat/100/statistics

-- Expected response:
{
    "total": 3,
    "idle": 1,
    "downloading": 1,
    "paused": 0,
    "completed": 1,
    "error": 0
}
```

---

## Known Issues & Considerations

### Issue 1: Performance
- Sorting happens on every chat list request
- For 100+ chats, this is still fast (<1ms)
- For 1,000+ chats, consider caching priority IDs

### Issue 2: UI Consistency
- Sorting should feel natural, not jarring
- Don't re-sort while user is interacting with dropdown
- Consider sticky positioning for better UX

### Issue 3: Statistics Query
- Counting all statuses in one query is efficient
- Alternative: Separate queries per status (slower)
- CASE statements are well-optimized by DB engines

---

## Validation Checklist

When re-implementing, verify:
- [ ] getChatList() accepts priorityChatIds parameter
- [ ] Sorting logic implemented (priority first)
- [ ] Activated chat always appears first
- [ ] Automated chat IDs fetched from AutomationsHolder
- [ ] getChatDownloadStatistics() method added
- [ ] SQL uses COUNT with CASE for each status
- [ ] Statistics respect historySince filter (if implemented)
- [ ] Excludes thumbnails (type != 'thumbnail')
- [ ] Statistics endpoint returns all 6 status counts
- [ ] Sorting is stable and predictable

---

## Dependencies

- **Requires**: AutomationsHolder or equivalent (to get automated chats)
- **Optional**: historySince feature (for filtered statistics)

---

## Estimated Re-implementation Time

**On clean codebase**: 2-3 hours
- Chat sorting: 1 hour (modify getChatList)
- Statistics method: 1 hour (SQL query)
- Testing: 1 hour (verify sorting and counts)

---

## Alternative Implementations

### Sorting
- **Current**: Sort in backend
- **Alternative**: Sort in frontend (simpler, but less flexible)

### Statistics
- **Current**: Single SQL query with CASE statements
- **Alternative**: Multiple queries (one per status) - simpler but slower

**Recommended**: Stick with current (backend sorting, single SQL query) - more efficient.

