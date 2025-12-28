# Feature 04: Download Oldest First

**Status**: ✅ Active  
**Complexity**: Medium  
**First Implemented**: v0.2.5  
**Current Implementation**: Commit 3cebe1e

---

## Overview

Allows users to reverse the download order from newest-to-oldest (default) to oldest-to-newest (chronological). Useful for archival purposes or when file order matters.

## User Story

**As a user**, I want to download files in chronological order (oldest first), **so that** I can build an archive that mirrors the original timeline of the chat.

**Example**: "Download all files from a music channel starting from the first song ever posted."

---

## Feature Behavior

### UI
- Toggle switch: "Download oldest first"
- Located in automation form (Advanced section)
- Placed near "History since" date picker
- Disabled when "Download History" is disabled
- Defaults to OFF (maintains backward compatibility)

### Backend
- When enabled: Scan from oldest message ID (1) forward
- When disabled: Scan from newest message ID (0) backward (default)
- Uses Telegram's `SearchChatMessages` API with negative offset for forward scanning

---

## Technical Approach

### Key Concept: TDLib's SearchChatMessages API

Telegram's API has two parameters that control direction:

```
fromMessageId: Starting point
    0 = start from newest message
    1+ = start from specific message ID

offset: Search direction
    0 or positive = search backward (toward older messages)
    negative = search forward (toward newer messages)
```

### Implementation Strategy

**Default (Newest → Oldest)**:
```
fromMessageId = 0    // Start from newest
offset = 0           // Go backward
Result: Newest, then older, then oldest
```

**Reversed (Oldest → Newest)**:
```
fromMessageId = 1    // Start from oldest
offset = -10 to -50  // Go forward (negative = forward)
Result: Oldest, then newer, then newest
```

---

## Implementation Sketch

### Backend Changes

**1. Add field to DownloadRule**
```java
// In SettingAutoRecords.java or equivalent
public static class DownloadRule {
    // ... existing fields ...
    
    /** If true, download from oldest to newest. If false (default), download from newest to oldest. */
    public boolean downloadOldestFirst;  // Defaults to false
}
```

**2. Modify history scanning logic**
```java
// In AutoDownloadVerticle or history scanning service
TdApi.SearchChatMessages searchChatMessages = new TdApi.SearchChatMessages();
searchChatMessages.chatId = chatId;

// Check the downloadOldestFirst setting
boolean downloadOldestFirst = rule.downloadOldestFirst;

if (downloadOldestFirst) {
    // For oldest-to-newest: start from message ID 1, scan forward
    searchChatMessages.fromMessageId = (nextFromMessageId == 0) ? 1 : nextFromMessageId;
    searchChatMessages.offset = -10;  // Negative = scan forward
} else {
    // For newest-to-oldest (default): start from 0, scan backward
    searchChatMessages.fromMessageId = nextFromMessageId;  // 0 on first call
    searchChatMessages.offset = 0;     // 0 or positive = scan backward
}

searchChatMessages.limit = 100;
TdApi.FoundChatMessages results = await client.execute(searchChatMessages);

// Continue with nextFromMessageId from results
nextFromMessageId = results.nextFromMessageId;
```

**3. Reset logic when switching file types**
```java
// When no more files found of current type, switch to next type
if (foundMessages.length == 0) {
    String nextFileType = getNextFileType(currentFileType);
    if (nextFileType != null) {
        // Reset to appropriate starting position
        nextFromMessageId = downloadOldestFirst ? 1 : 0;
        scanHistory(nextFileType, nextFromMessageId);
    }
}
```

**4. Integration with database queue (if applicable)**
```java
// When queuing files from database
public Future<Integer> queueFilesForDownload(
    long telegramId, 
    long chatId, 
    int limit,
    Boolean downloadOldestFirst
) {
    String orderClause = (downloadOldestFirst != null && downloadOldestFirst)
        ? "ORDER BY message_id ASC"   // Oldest first
        : "ORDER BY message_id DESC";  // Newest first (default)
    
    String query = "SELECT * FROM file_record " +
                   "WHERE telegram_id = ? AND download_status = 'idle' " +
                   orderClause + " LIMIT ?";
    
    return executeQuery(query);
}
```

### Frontend Changes

**1. Add to TypeScript types**
```typescript
// In types.ts
export type AutoDownloadRule = {
    // ... existing fields ...
    downloadOldestFirst?: boolean;  // Optional, defaults to false
};
```

**2. Add toggle to form**
```tsx
// In automation-form.tsx, in Advanced section after "History since"
<div className="mt-3 flex items-center justify-between">
    <Label htmlFor="download-oldest-first">Download oldest first</Label>
    <Switch
        id="download-oldest-first"
        checked={value.downloadOldestFirst ?? false}
        onCheckedChange={(checked) =>
            onChange({ ...value, downloadOldestFirst: checked })
        }
        disabled={!value.downloadHistory}
    />
</div>
<p className="text-xs text-muted-foreground">
    If enabled, files will be downloaded from oldest to newest. 
    Otherwise, files will be downloaded from newest to oldest (default).
</p>
```

**3. Add to default config**
```typescript
// In automation-dialog.tsx
const DEFAULT_AUTO = {
    download: {
        rule: {
            // ... existing fields ...
            downloadOldestFirst: false,  // Default to newest-first
        }
    }
};
```

---

## Integration Points

### Where to Make Changes

1. **Data Model**
   - Backend: DownloadRule configuration class
   - Frontend: AutoDownloadRule type definition

2. **History Scanning Service**
   - The service that calls Telegram's SearchChatMessages API
   - Modify to set fromMessageId and offset based on flag

3. **Queue Management** (if database-driven)
   - Add ORDER BY clause that respects downloadOldestFirst
   - Can be in repository methods or query builders

4. **UI Form**
   - Automation form component
   - Add toggle near "Download History" section

---

## Test Cases

### Test 1: Oldest First Enabled
```
1. Enable automation with downloadOldestFirst = true
2. Start download
3. Check logs: Should see "fromMessageId=1" or "fromMessageId=<low number>"
4. Check logs: Should see "offset=-10" or negative value
5. Verify: First files downloaded have oldest timestamps
6. Query DB: Files should have ascending message_id order in download sequence
```

### Test 2: Newest First (Default)
```
1. Enable automation with downloadOldestFirst = false (or unset)
2. Start download
3. Check logs: Should see "fromMessageId=0"
4. Check logs: Should see "offset=0"
5. Verify: First files downloaded have newest timestamps
```

### Test 3: Toggle While Downloading
```
1. Start download with oldest-first
2. Pause automation
3. Toggle to newest-first
4. Resume automation
5. Expected: Remaining files downloaded in new order
6. Note: Already downloaded files maintain their order
```

### Test 4: UI Behavior
```
1. Disable "Download History"
2. Expected: "Download oldest first" toggle is disabled/grayed out
3. Enable "Download History"
4. Expected: Toggle becomes enabled
```

---

## Known Issues & Considerations

### Issue 1: API Offset Limits
- Telegram API requires: `limit > -offset` when offset is negative
- If offset=-50, limit must be > 50
- Solution: Use offset=-10 with limit=100 (safe ratio)

### Issue 2: Restart Behavior
- If automation is restarted mid-scan, it resumes from `nextFromMessageId`
- The order remains consistent (doesn't switch mid-scan)
- Changing the toggle requires resetting scan state

### Issue 3: Multiple File Types
- When scanning multiple file types (photo, video, audio, file)
- Each type restart should respect the order setting
- Reset fromMessageId to 1 or 0 based on downloadOldestFirst

### Issue 4: Performance
- Forward scanning (oldest-first) may be slightly slower
- Telegram's API is optimized for backward scanning (default)
- Difference is negligible for most use cases

---

## Code Examples from v0.3.0 Implementation

### Complete Backend Example
```java
// From AutoDownloadVerticle.java (lines 338-350)
TdApi.SearchChatMessages searchChatMessages = new TdApi.SearchChatMessages();
searchChatMessages.chatId = chatId;

// Handle reverse order (oldest to newest)
boolean downloadOldestFirst = params.rule != null && params.rule.downloadOldestFirst;
if (downloadOldestFirst) {
    // For oldest-to-newest: start from message ID 1 if beginning, use negative offset to scan forward
    searchChatMessages.fromMessageId = nextFromMessageId == 0 ? 1 : nextFromMessageId;
    searchChatMessages.offset = -10;  // Negative offset scans forward (toward newer messages)
} else {
    // For newest-to-oldest (default): start from 0 (newest), scan backward
    searchChatMessages.fromMessageId = nextFromMessageId;
    searchChatMessages.offset = 0;  // Default behavior
}

searchChatMessages.limit = Math.min(MAX_WAITING_LENGTH, 100);
searchChatMessages.filter = getSearchMessagesFilter(fileType);
```

### Database Queue Integration
```java
// From DownloadQueueService.java (conceptual)
public static Future<Integer> queueFilesForDownload(
    long telegramId, 
    long chatId, 
    int limit,
    Boolean downloadOldestFirst
) {
    // Pass ordering preference to repository
    return fileRepository.queueFilesForDownload(
        telegramId, 
        chatId, 
        limit,
        downloadOldestFirst != null ? downloadOldestFirst : true  // Default to oldest-first for queue
    );
}
```

### Complete Frontend Example
```tsx
// From automation-form.tsx (lines 351-368)
{/* Download oldest first toggle */}
<div className="mt-3 flex items-center justify-between">
    <Label htmlFor="download-oldest-first">Download oldest first</Label>
    <Switch
        id="download-oldest-first"
        checked={value.downloadOldestFirst ?? false}
        onCheckedChange={(checked) =>
            onChange({
                ...value,
                downloadOldestFirst: checked,
            })
        }
        disabled={!value.downloadHistory}
    />
</div>
<p className="mt-1 text-xs text-muted-foreground">
    If enabled, files will be downloaded from oldest to newest. 
    Otherwise, files will be downloaded from newest to oldest (default).
</p>
```

---

## Validation Checklist

When re-implementing, verify:
- [ ] Backend field added (boolean downloadOldestFirst)
- [ ] Frontend field added (downloadOldestFirst?: boolean)
- [ ] UI toggle in automation form (Advanced section)
- [ ] Toggle is disabled when downloadHistory is false
- [ ] Default value is false (newest-first for backward compatibility)
- [ ] SearchChatMessages uses correct fromMessageId and offset
- [ ] File type switching resets fromMessageId correctly
- [ ] Database queries (if any) respect ordering
- [ ] Logs show correct parameters when scanning
- [ ] Files are downloaded in expected order

---

## Dependencies

- **Requires**: History scanning feature (SearchChatMessages API)
- **Optional**: Database queue system (for persistent ordering)
- **Compatible with**: History cutoff date feature

---

## Estimated Re-implementation Time

**On clean codebase**: 1-2 hours
- Backend: 30 minutes (modify search parameters)
- Frontend: 20 minutes (add toggle)
- Database integration: 30 minutes (if queue system exists)
- Testing: 30 minutes (verify order)

---

## Migration Notes

- **v0.2.5 → v0.3.0**: No conflicts, clean integration
- **Future versions**: Watch for changes in SearchChatMessages API
- **Breaking changes**: If TDLib API changes offset behavior

