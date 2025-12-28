# Feature 03: Chat Download Progress Badge

**Status**: ✅ Active  
**Complexity**: Medium  
**First Implemented**: v0.2.5  
**Current Implementation**: Commit e9cf3c1

---

## Overview

Displays a real-time progress badge in the header showing download statistics for the currently viewed chat. Shows format like "5/20" (5 completed out of 20 total files) with detailed breakdown on hover.

## User Story

**As a user**, I want to see at a glance how many files are downloading/completed in the current chat, **so that** I can track progress without opening the automation dialog or checking logs.

**Example**: "I'm viewing a channel - I want to see '47/150' meaning 47 files completed out of 150 total."

---

## Feature Behavior

### Visual Design
- **Badge location**: Header, between download speed and connection status
- **Format**: "X/Y" where X = completed, Y = total
- **Color**: Default badge style
- **Visibility**: Only shows when total files > 0
- **Tooltip**: Hover to see detailed breakdown

### Tooltip Content
```
Download Progress
Total: 150
Idle: 80
Downloading: 3
Completed: 47
Error: 2
Paused: 18
Progress: 31%
```

### Refresh Strategy
- **Interval**: 30 seconds (optimized to reduce database load)
- **Deduplication**: 10 seconds (prevents duplicate requests)
- **Focus**: Doesn't revalidate when window regains focus
- **Only fetches**: When accountId and chatId are both available

---

## Technical Approach

### Architecture

```
Frontend Component (chat-download-badge.tsx)
    ↓ (SWR hook)
HTTP GET /telegram/:telegramId/chat/:chatId/statistics
    ↓ (backend endpoint)
TelegramVerticle.getChatDownloadStatistics()
    ↓
FileRepository.getChatDownloadStatistics()
    ↓ (SQL query)
Database (file_record table)
    ↓ (counts by status)
Return: { total, idle, downloading, paused, completed, error }
```

### Key Decisions

1. **30-second refresh**: Balance between real-time updates and database load
2. **SWR caching**: Prevents duplicate requests, improves performance
3. **Conditional rendering**: Don't show badge when total = 0 (avoid clutter)
4. **Respect historySince**: Statistics should only count files after cutoff date

---

## Implementation Sketch

### Backend Changes

**1. Add statistics endpoint**
```java
// In HttpVerticle.java or API router
router.get("/telegram/:telegramId/chat/:chatId/statistics")
    .handler(this::handleChatStatistics);

private void handleChatStatistics(RoutingContext ctx) {
    String telegramId = ctx.pathParam("telegramId");
    String chatId = ctx.pathParam("chatId");
    
    TelegramVerticle telegramVerticle = getTelegramVerticle(telegramId);
    telegramVerticle.getChatDownloadStatistics(Long.parseLong(chatId))
        .onSuccess(ctx::json)
        .onFailure(ctx::fail);
}
```

**2. Implement statistics method**
```java
// In TelegramVerticle.java
public Future<JsonObject> getChatDownloadStatistics(long chatId) {
    // Get historySince from automation config if it exists
    Integer historySince = null;
    Automation automation = getAutomation(this.telegramId, chatId);
    if (automation != null && automation.download != null && automation.download.rule != null) {
        historySince = automation.download.rule.historySince;
    }
    
    // Delegate to repository
    return fileRepository.getChatDownloadStatistics(this.telegramId, chatId, historySince);
}
```

**3. Implement repository method**
```java
// In FileRepositoryImpl.java or equivalent
public Future<JsonObject> getChatDownloadStatistics(
    long telegramId, 
    long chatId, 
    Integer historySince
) {
    // Build conditional query based on historySince
    String query = historySince != null 
        ? """
            SELECT COUNT(*) AS total,
                   COUNT(CASE WHEN download_status = 'downloading' THEN 1 END) AS downloading,
                   COUNT(CASE WHEN download_status = 'paused' THEN 1 END) AS paused,
                   COUNT(CASE WHEN download_status = 'completed' THEN 1 END) AS completed,
                   COUNT(CASE WHEN download_status = 'error' THEN 1 END) AS error,
                   COUNT(CASE WHEN download_status = 'idle' THEN 1 END) AS idle
            FROM file_record
            WHERE telegram_id = ? AND chat_id = ? AND type != 'thumbnail'
              AND date >= ?
          """
        : """
            SELECT COUNT(*) AS total,
                   COUNT(CASE WHEN download_status = 'downloading' THEN 1 END) AS downloading,
                   -- ... same fields ...
            FROM file_record
            WHERE telegram_id = ? AND chat_id = ? AND type != 'thumbnail'
          """;
    
    // Execute and return as JsonObject
    return executeQuery(query, params)
        .map(row -> JsonObject.of(
            "total", row.getInteger("total"),
            "downloading", row.getInteger("downloading"),
            "paused", row.getInteger("paused"),
            "completed", row.getInteger("completed"),
            "error", row.getInteger("error"),
            "idle", row.getInteger("idle")
        ));
}
```

### Frontend Changes

**1. Create badge component**
```tsx
// New file: web/src/components/chat-download-badge.tsx

import useSWR from "swr";
import { Badge } from "@/components/ui/badge";
import { TooltipWrapper } from "./ui/tooltip";
import { useTelegramChat } from "@/hooks/use-telegram-chat";
import { useTelegramAccount } from "@/hooks/use-telegram-account";

interface ChatDownloadStats {
    total: number;
    downloading: number;
    paused: number;
    completed: number;
    error: number;
    idle: number;
}

export function ChatDownloadBadge() {
    const { accountId } = useTelegramAccount();
    const { chatId } = useTelegramChat();

    const { data: stats } = useSWR<ChatDownloadStats>(
        accountId && chatId
            ? `/telegram/${accountId}/chat/${chatId}/statistics`
            : null,
        {
            refreshInterval: 30000,      // 30 seconds (optimized)
            dedupingInterval: 10000,     // 10 seconds
            revalidateOnFocus: false,    // Don't refresh on focus
        }
    );

    // Don't show if no files
    if (!stats || stats.total === 0) {
        return null;
    }

    const pendingCount = stats.idle + stats.downloading;
    const progress = Math.round((stats.completed / stats.total) * 100);

    return (
        <TooltipWrapper
            content={
                <div className="space-y-1 text-xs">
                    <div className="font-semibold">Download Progress</div>
                    <div>Total: {stats.total}</div>
                    <div>Idle: {stats.idle}</div>
                    <div>Downloading: {stats.downloading}</div>
                    <div>Completed: {stats.completed}</div>
                    <div>Error: {stats.error}</div>
                    <div>Paused: {stats.paused}</div>
                    <div>Progress: {progress}%</div>
                </div>
            }
        >
            <Badge variant="secondary">
                {stats.completed}/{stats.total}
            </Badge>
        </TooltipWrapper>
    );
}
```

**2. Add to header component**
```tsx
// In header.tsx (or main header component)
import { ChatDownloadBadge } from "@/components/chat-download-badge";

// In render, near download speed indicator:
<div className="flex items-center gap-2">
    {accountDownloadSpeed !== 0 && (
        <div>Download Speed: {accountDownloadSpeed}</div>
    )}
    
    <ChatDownloadBadge />  {/* Add here */}
    
    {connectionStatus && (
        <Badge>{connectionStatus}</Badge>
    )}
</div>
```

**3. Add to mobile header** (same pattern)
```tsx
// In mobile-header.tsx
import { ChatDownloadBadge } from "@/components/chat-download-badge";

// Add to mobile header layout
<ChatDownloadBadge />
```

---

## Integration Points

### Where to Make Changes

1. **Backend API Endpoint**
   - Add route: GET /telegram/:telegramId/chat/:chatId/statistics
   - Handler calls TelegramVerticle method

2. **Statistics Method**
   - In TelegramVerticle or equivalent
   - Fetches automation config to get historySince
   - Delegates to repository

3. **Repository Method**
   - SQL query with conditional date filtering
   - COUNT with CASE for each status
   - Returns JsonObject with counts

4. **Frontend Component**
   - New file: chat-download-badge.tsx
   - Uses SWR for data fetching
   - Tooltip for detailed view

5. **Header Integration**
   - Import badge component
   - Render in header (desktop and mobile)

---

## Test Cases

### Test 1: Badge Appears
```
1. Navigate to a chat with files
2. Expected: Badge appears in header
3. Expected: Shows format "X/Y"
4. Navigate to empty chat
5. Expected: Badge disappears
```

### Test 2: Statistics Accuracy
```
1. Start downloading 10 files
2. Wait 30+ seconds for badge refresh
3. Expected: Badge shows "0/10" initially
4. After some complete: Badge shows "3/10"
5. After all complete: Badge shows "10/10"
```

### Test 3: Tooltip Details
```
1. Hover over badge
2. Expected: Tooltip appears with breakdown:
   - Total count
   - Status counts (idle, downloading, paused, completed, error)
   - Progress percentage
3. Move mouse away
4. Expected: Tooltip disappears
```

### Test 4: Refresh Optimization
```
1. Monitor network requests (browser DevTools)
2. Expected: Statistics endpoint called every 30 seconds
3. Expected: NOT called every 5 seconds
4. Switch tabs and back
5. Expected: NOT called on focus (revalidateOnFocus: false)
```

### Test 5: History Filter Integration
```
1. Set historySince = 2025-06-01
2. Expected: Badge shows count of files after June 1 only
3. Remove historySince
4. Expected: Badge shows count of all files
```

---

## Known Issues & Considerations

### Issue 1: Performance
- Querying database every 30 seconds can add load on large instances
- Original implementation had 5-second refresh (too frequent)
- 30 seconds is a good balance
- For very large databases, consider increasing to 60 seconds

### Issue 2: Real-time Accuracy
- Badge lags up to 30 seconds behind actual state
- This is intentional (performance optimization)
- For more real-time updates, reduce refreshInterval
- Alternative: Use WebSocket events for instant updates (more complex)

### Issue 3: Mobile Layout
- Badge should be responsive on mobile
- May need to adjust spacing/sizing for small screens
- Test on various screen sizes

### Issue 4: Zero State
- Badge should hide when total = 0
- Prevents showing "0/0" which is confusing
- Check both empty chats and chats with no automation

---

## Optimization Notes

### From v0.2.5 Implementation (Commit d8bcff7)

**Original refresh**: 5 seconds
```tsx
refreshInterval: 5000  // Too frequent, causes DB load
```

**Optimized refresh**: 30 seconds
```tsx
refreshInterval: 30000,      // Reduced from 5s to reduce DB load
dedupingInterval: 10000,     // Additional deduplication
revalidateOnFocus: false,    // Don't refetch on tab focus
```

**Impact**:
- 83% reduction in database queries
- Still feels real-time enough for users
- Significant load reduction on busy instances

---

## Validation Checklist

When re-implementing, verify:
- [ ] Component file created (chat-download-badge.tsx)
- [ ] SWR hook configured with correct endpoint
- [ ] refreshInterval set to 30000 (30 seconds)
- [ ] Badge shows correct format "X/Y"
- [ ] Tooltip has all 6 status counts + progress %
- [ ] Badge hides when stats.total === 0
- [ ] Imported in header.tsx
- [ ] Imported in mobile-header.tsx (if exists)
- [ ] Backend endpoint returns all required fields
- [ ] Statistics respect historySince filter
- [ ] No TypeScript errors
- [ ] Responsive on mobile

---

## Dependencies

- **Requires**: Statistics backend endpoint
- **Optional**: historySince feature (for filtered counts)
- **UI Library**: Badge and Tooltip components

---

## Estimated Re-implementation Time

**On clean codebase**: 2-3 hours
- Backend endpoint: 30 minutes (if statistics method exists)
- Backend repository method: 45 minutes (SQL query)
- Frontend component: 45 minutes (badge + tooltip)
- Integration: 30 minutes (add to headers)
- Testing: 30 minutes (verify accuracy)

---

## Alternative Implementations

### Option A: WebSocket-based (Real-time)
Instead of polling every 30s, push updates via WebSocket when status changes.

**Pros**: Instant updates
**Cons**: More complex, requires WebSocket infrastructure

### Option B: EventSource (SSE)
Use Server-Sent Events for one-way real-time updates.

**Pros**: Simpler than WebSocket, real-time
**Cons**: Requires SSE support, more backend complexity

### Option C: Polling (Current Implementation)
SWR with 30-second refresh.

**Pros**: Simple, works everywhere, good balance
**Cons**: 30-second lag

**Recommended**: Stick with Option C (polling) unless real-time is critical.

