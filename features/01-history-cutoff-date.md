# Feature 01: History Cutoff Date

**Status**: ✅ Active  
**Complexity**: Low  
**First Implemented**: v0.2.5  
**Current Implementation**: Commit 3535f5b

---

## Overview

Allows users to specify a cutoff date when downloading historical files. Only files sent **on or after** the specified date will be downloaded, skipping older files.

## User Story

**As a user**, I want to download only recent files from a chat, **so that** I don't waste time and storage downloading years of old content I don't need.

**Example**: "I only want files from January 2025 onwards, skip everything before that."

---

## Feature Behavior

### UI
- Date picker in automation form (Advanced section)
- Label: "History since"
- Shows when "Download History" is enabled
- Time automatically set to 00:01 AM UTC on selected date

### Backend
- Converts date to Unix timestamp (seconds since epoch)
- Finds the first message ID at or after that timestamp (sentinel message)
- During history scanning, stops when reaching messages older than sentinel
- Statistics queries respect the cutoff (only count files after date)

---

## Technical Approach

### Concept
Instead of storing just a date, store the **message ID** that corresponds to that date. This is more efficient for Telegram API queries.

```
User selects date → Convert to timestamp → Find message at that time → Store message ID → Use for filtering
```

### Data Model

**Backend (Java)**:
```java
class DownloadRule {
    Integer historySince;  // UTC seconds since epoch (nullable)
}
```

**Frontend (TypeScript)**:
```typescript
type AutoDownloadRule = {
    historySince?: string | null;  // ISO-8601 timestamp string
}
```

---

## Implementation Sketch

### Backend Changes

**1. Add field to DownloadRule**
```java
// In SettingAutoRecords.java or equivalent
public static class DownloadRule {
    // ... existing fields ...
    
    /** UTC seconds since epoch. If present, only backfill messages on/after this. */
    public Integer historySince;
}
```

**2. Compute sentinel message during automation start**
```java
// In AutoDownloadVerticle or history scanning service
Long sentinelMessageId = null;
Integer sentinelMessageDate = null;

if (automation.download.rule.historySince != null) {
    // Use Telegram's searchChatMessages to find first message >= historySince
    TdApi.SearchChatMessages search = new TdApi.SearchChatMessages();
    search.chatId = chatId;
    search.fromMessageId = 0;  // Start from newest
    search.limit = 1;
    // Search for any message, then check dates iteratively
    
    // Store the message ID as sentinel
    sentinelMessageId = sentinelMessage.id;
    sentinelMessageDate = sentinelMessage.date;
}
```

**3. Filter during history scanning**
```java
// During message processing
for (TdApi.Message message : foundMessages) {
    // Skip if older than sentinel
    if (sentinelMessageId != null && message.id < sentinelMessageId) {
        continue;  // Skip this message
    }
    
    // Process message for download
    queueForDownload(message);
}

// Check if scan is complete
if (reachedSentinel) {
    markScanComplete();
    return;
}
```

**4. Filter statistics queries**
```java
// In statistics endpoint
public Future<JsonObject> getChatDownloadStatistics(long telegramId, long chatId) {
    Integer historySince = getAutomationHistorySince(telegramId, chatId);
    
    String query = historySince != null 
        ? "SELECT COUNT(*) ... WHERE date >= #{historySince}"
        : "SELECT COUNT(*) ... ";
    
    return executeQuery(query, historySince);
}
```

### Frontend Changes

**1. Add to TypeScript types**
```typescript
// In types.ts
export type AutoDownloadRule = {
    // ... existing fields ...
    historySince?: string | null;  // ISO-8601 format
};
```

**2. Add date picker to form**
```tsx
// In automation-form.tsx, inside Advanced section
<div className="mt-3">
    <Label htmlFor="history-since">History since</Label>
    <input
        id="history-since"
        type="date"
        value={formatDateForInput(value.historySince)}
        onChange={(e) => {
            const isoDate = e.target.value 
                ? new Date(e.target.value + "T00:01:00Z").toISOString()
                : null;
            onChange({ ...value, historySince: isoDate });
        }}
        disabled={!value.downloadHistory}
    />
    <p className="text-xs text-muted-foreground">
        Time is automatically set to 00:01 AM on the selected date.
    </p>
</div>
```

**3. Add to default automation config**
```typescript
// In automation-dialog.tsx
const DEFAULT_AUTO = {
    download: {
        rule: {
            // ... existing fields ...
            historySince: null,  // No cutoff by default
        }
    }
};
```

---

## Integration Points

### Where to Make Changes

1. **Data Model**
   - Backend: Automation/download rule configuration class
   - Frontend: Type definitions for automation rules

2. **History Scanning**
   - The service/class that scans chat history for files
   - Add logic to compute sentinel message
   - Add filtering during message iteration

3. **Statistics**
   - The endpoint/method that returns download statistics
   - Add WHERE clause filtering by date

4. **UI Form**
   - Automation configuration form component
   - Add date picker input
   - Place in "Advanced" section near "Download History" toggle

---

## Test Cases

### Test 1: Date Filter Works
```
1. Create automation with historySince = 2025-01-01
2. Start download
3. Check logs for "History cutoff enabled" message
4. Verify: Only files with date >= 2025-01-01 are queued
5. Query DB: SELECT * FROM files WHERE date >= <timestamp>
```

### Test 2: Statistics Respect Filter
```
1. Set historySince = 2025-06-01
2. View chat
3. Check statistics badge
4. Expected: Count only includes files from June 2025+
5. Remove historySince
6. Expected: Count includes all files
```

### Test 3: Null Cutoff (Default Behavior)
```
1. Leave historySince empty (null)
2. Start automation
3. Expected: All historical files are downloaded (no filtering)
```

### Test 4: UI Behavior
```
1. Disable "Download History"
2. Expected: Date picker is disabled
3. Enable "Download History"
4. Expected: Date picker is enabled
5. Select a date
6. Expected: Time is set to 00:01 AM UTC automatically
```

---

## Known Issues & Considerations

### Issue 1: Sentinel Message Lookup
- Finding the exact message ID for a date requires iterating through messages
- Telegram API doesn't have "get message by date" directly
- Solution: Search with filters, check dates, iterate until found

### Issue 2: Timezone Handling
- Always use UTC (00:01 AM) to avoid timezone confusion
- Frontend converts local date to UTC ISO-8601
- Backend works with Unix timestamps (seconds since epoch)

### Issue 3: Performance
- Sentinel lookup happens once per automation start
- Not a performance issue (1-2 API calls)
- Cache the sentinel ID to avoid repeated lookups

### Issue 4: Edge Cases
- If no message exists at exact date, use closest message after
- If date is in future, start from newest message (no filtering)
- If date is before first message, download nothing (all filtered out)

---

## Code Examples from v0.3.0 Implementation

### Backend: Computing Sentinel
```java
// AutoDownloadVerticle.java (conceptual)
if (auto.download.rule.historySince != null && auto.download.rule.historySince > 0) {
    TelegramVerticle telegramVerticle = getTelegramVerticle(auto.telegramId);
    
    // Search for messages around the cutoff time
    TdApi.SearchChatMessages search = new TdApi.SearchChatMessages();
    search.chatId = auto.chatId;
    search.fromMessageId = 0;
    search.limit = 100;
    
    TdApi.FoundChatMessages results = await telegramVerticle.client.execute(search);
    
    // Find first message >= historySince
    for (TdApi.Message msg : results.messages) {
        if (msg.date >= auto.download.rule.historySince) {
            sentinelMessageId = msg.id;
            sentinelMessageDate = msg.date;
            break;
        }
    }
}
```

### Backend: Filtering During Scan
```java
// During history scanning (conceptual)
List<TdApi.Message> messages = foundChatMessages.messages;

// Filter out messages older than sentinel
if (sentinelMessageId != null) {
    long oldestInBatch = messages[messages.length - 1].id;
    
    if (oldestInBatch <= sentinelMessageId) {
        // We've reached the cutoff, mark scan complete
        return completeScan();
    }
    
    messages = messages.stream()
        .filter(msg -> msg.id >= sentinelMessageId)
        .collect(Collectors.toList());
}

// Queue filtered messages
queueForDownload(messages);
```

### Backend: Statistics Query
```java
// In FileRepository or statistics service (conceptual)
public Future<JsonObject> getChatDownloadStatistics(long telegramId, long chatId, Integer historySince) {
    String query = historySince != null 
        ? """
            SELECT 
                COUNT(*) AS total,
                COUNT(CASE WHEN download_status = 'downloading' THEN 1 END) AS downloading,
                COUNT(CASE WHEN download_status = 'completed' THEN 1 END) AS completed
            FROM file_record
            WHERE telegram_id = ? AND chat_id = ? 
              AND date >= ?  -- Filter by historySince
          """
        : """
            SELECT COUNT(*) AS total, ...
            FROM file_record
            WHERE telegram_id = ? AND chat_id = ?
          """;
    
    return executeQuery(query, params);
}
```

### Frontend: Date Picker
```tsx
// In automation-form.tsx (conceptual)
<Label htmlFor="history-since">History since</Label>
<input
    id="history-since"
    type="date"
    value={
        value.historySince 
            ? new Date(value.historySince).toISOString().slice(0,10) 
            : ""
    }
    onChange={(e) => {
        const isoDate = e.target.value 
            ? new Date(e.target.value + "T00:01:00Z").toISOString()
            : null;
        onChange({ ...value, historySince: isoDate });
    }}
    disabled={!value.downloadHistory}
/>
<p className="text-xs text-muted-foreground">
    Time is automatically set to 00:01 AM on the selected date.
</p>
```

---

## Validation Checklist

When re-implementing, verify:
- [ ] Backend field added to DownloadRule (Integer historySince)
- [ ] Frontend field added to AutoDownloadRule (string? | null)
- [ ] UI date picker in automation form
- [ ] Sentinel message computation on automation start
- [ ] Message filtering during history scan
- [ ] Statistics queries include date filter
- [ ] Date picker disabled when "Download History" is off
- [ ] Time is always 00:01 AM UTC
- [ ] Null/undefined handled correctly (no filter applied)
- [ ] Logs show "History cutoff enabled" message

---

## Dependencies

- None (standalone feature)
- Works with any history scanning implementation
- Compatible with any statistics system

---

## Estimated Re-implementation Time

**On clean codebase**: 2-3 hours
- Backend: 1 hour (sentinel computation + filtering)
- Frontend: 30 minutes (date picker)
- Testing: 1 hour (verify filtering works)

