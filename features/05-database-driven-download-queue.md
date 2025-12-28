# Feature 05: Database-Driven Download Queue

**Status**: ✅ Active  
**Complexity**: ⭐⭐⭐ High  
**First Implemented**: v0.2.5  
**Current Implementation**: Commit c0f468c

---

## Overview

**Major architectural refactor** that moves the download queue from in-memory storage to database persistence. Enables:
- Queue survives application restarts
- Priority-based download ordering
- Better concurrency control
- Scalability to thousands of files

## User Story

**As a user**, I want my download queue to persist across restarts, **so that** I don't lose progress when the application crashes or needs to restart.

**As a developer**, I want a scalable queue system, **so that** large channels with 10,000+ files don't overwhelm memory.

---

## Feature Behavior

### Before (In-Memory Queue)
```
Application starts
    ↓
Scan history → Store messages in Map<TelegramId, LinkedList<Message>>
    ↓
Process queue from memory
    ↓
Application restarts → Queue is LOST ❌
```

### After (Database Queue)
```
Application starts
    ↓
Scan history → Insert into database (file_record table)
    ↓
Mark as 'queued' with queued_at timestamp
    ↓
Query database for files to download (ordered by priority, queued_at)
    ↓
Application restarts → Queue PERSISTS ✅
```

---

## Technical Approach

### Core Concepts

1. **Scan State**: Track whether a chat has been fully scanned
2. **Queue State**: Files marked as 'queued' with timestamp
3. **Priority**: Files can have download_priority (higher = first)
4. **Ordering**: Query supports oldest-first or newest-first
5. **Persistence**: Everything in database, not memory

### State Machine

```
File discovered → scan_state = 'idle', download_status = 'idle'
    ↓
Queue for download → download_status = 'queued', queued_at = NOW
    ↓
Start download → download_status = 'downloading'
    ↓
Complete → download_status = 'completed'
```

---

## Implementation Sketch

### Database Changes

**1. Migration: Add queue columns**
```sql
-- Migration file: 0025_add_download_queue_columns.sql

ALTER TABLE file_record 
  ADD COLUMN IF NOT EXISTS scan_state VARCHAR(20) DEFAULT 'idle',
  ADD COLUMN IF NOT EXISTS download_priority INT DEFAULT 0,
  ADD COLUMN IF NOT EXISTS queued_at BIGINT;

-- Indexes for efficient queries
CREATE INDEX idx_file_record_queue 
  ON file_record(chat_id, download_status, queued_at) 
  WHERE download_status IN ('idle', 'queued');

CREATE INDEX idx_file_record_priority 
  ON file_record(chat_id, download_priority DESC, queued_at ASC) 
  WHERE download_status IN ('idle', 'queued');

CREATE INDEX idx_file_record_ready_download 
  ON file_record(telegram_id, chat_id, download_status) 
  WHERE download_status IN ('idle', 'queued');
```

**2. Add to FileRecord model**
```java
// In FileRecord.java
public record FileRecord(
    // ... existing fields ...
    String scanState,        // 'idle', 'scanning', 'complete'
    Integer downloadPriority, // Default 0, higher = more important
    Long queuedAt            // Timestamp when queued (milliseconds)
) {
    // ... methods ...
}
```

### Backend Services

**1. Create DownloadQueueService**
```java
// New file: DownloadQueueService.java

public class DownloadQueueService {
    
    /**
     * Queue files for download by marking them as 'queued' in database.
     */
    public static Future<Integer> queueFilesForDownload(
        long telegramId,
        long chatId,
        int limit,
        Integer cutoffDateSeconds,
        Boolean downloadOldestFirst
    ) {
        // Query for idle files
        String orderClause = (downloadOldestFirst != null && downloadOldestFirst)
            ? "ORDER BY message_id ASC"   // Oldest first
            : "ORDER BY message_id DESC";  // Newest first
        
        String query = """
            UPDATE file_record
            SET download_status = 'queued', queued_at = ?
            WHERE id IN (
                SELECT id FROM file_record
                WHERE telegram_id = ? AND download_status = 'idle'
                  AND (? IS NULL OR chat_id = ?)
                  AND (? IS NULL OR date >= ?)
                  AND type != 'thumbnail'
                %s
                LIMIT ?
            )
        """.formatted(orderClause);
        
        return executeUpdate(query, System.currentTimeMillis(), telegramId, chatId, cutoffDateSeconds, limit)
            .map(rowsAffected -> rowsAffected);  // Returns count of files queued
    }
    
    /**
     * Get files ready to download from the queue.
     */
    public static Future<List<FileRecord>> getFilesForDownload(long telegramId, int limit) {
        String query = """
            SELECT * FROM file_record
            WHERE telegram_id = ? AND download_status = 'queued'
            ORDER BY download_priority DESC, queued_at ASC
            LIMIT ?
        """;
        
        return executeQuery(query, telegramId, limit);
    }
}
```

**2. Create AutomationState enum**
```java
// New file: AutomationState.java

public enum AutomationState {
    HISTORY_PRELOAD(1),
    HISTORY_DOWNLOAD(2),
    HISTORY_DOWNLOAD_SCAN(3),
    HISTORY_TRANSFER(4);
    
    private final int bitValue;
    
    AutomationState(int bitValue) {
        this.bitValue = bitValue;
    }
    
    // Convert from legacy bitwise state
    public static Set<AutomationState> fromLegacyState(int state) {
        Set<AutomationState> result = EnumSet.noneOf(AutomationState.class);
        for (AutomationState s : values()) {
            if ((state & s.bitValue) != 0) {
                result.add(s);
            }
        }
        return result;
    }
    
    // Convert to legacy bitwise state
    public static int toLegacyState(Set<AutomationState> states) {
        return states.stream()
            .mapToInt(s -> s.bitValue)
            .reduce(0, (a, b) -> a | b);
    }
}
```

**3. Refactor AutoDownloadVerticle**
```java
// In AutoDownloadVerticle.java or equivalent

// Periodic task to process queue
vertx.setPeriodic(0, DOWNLOAD_INTERVAL, id -> {
    autoRecords.getDownloadEnabledItems().forEach(auto -> {
        processDownloadQueue(auto.telegramId);
    });
});

private void processDownloadQueue(long telegramId) {
    // 1. Queue more files if needed (from idle to queued)
    int queueLimit = 50;  // How many to queue
    DownloadQueueService.queueFilesForDownload(telegramId, 0, queueLimit, cutoffDate, downloadOldestFirst)
        .compose(queuedCount -> {
            // 2. Get files to download from queue
            return DownloadQueueService.getFilesForDownload(telegramId, concurrencyLimit);
        })
        .onSuccess(files -> {
            // 3. Start downloading each file
            files.forEach(fileRecord -> {
                startDownload(fileRecord.chatId(), fileRecord.messageId(), fileRecord.id());
            });
        });
}
```

**4. Update history discovery**
```java
// When discovering new files from history scan
private void onHistoryMessagesFound(List<TdApi.Message> messages) {
    // 1. Convert to FileRecord and insert into database
    messages.forEach(message -> {
        FileRecord fileRecord = convertToFileRecord(message);
        fileRepository.createIfNotExist(fileRecord);  // insert with download_status='idle'
    });
    
    // 2. Queue them for download
    DownloadQueueService.queueFilesForDownload(telegramId, chatId, messages.size(), null, null);
}
```

### Frontend Changes

**No frontend changes required** - this is purely backend architecture.

However, if you want visibility:
```tsx
// Optional: Show queue status
const { data: queueStatus } = useSWR(
    `/telegram/${accountId}/queue-status`,
    { refreshInterval: 10000 }
);

// Display: "50 files queued, 3 downloading"
```

---

## Integration Points

### Where to Make Changes

1. **Database Schema**
   - Add migration file for new columns
   - Add indexes for queue queries

2. **FileRecord Model**
   - Add scanState, downloadPriority, queuedAt fields

3. **FileRepository**
   - Add queueFilesForDownload() method
   - Add getFilesForDownload() method (with ORDER BY)

4. **New Service: DownloadQueueService**
   - Central service for queue operations
   - Wraps repository calls with business logic

5. **New Enum: AutomationState**
   - Replace bitwise state flags with enum
   - Provide conversion methods for backward compatibility

6. **AutoDownloadVerticle Refactor**
   - Remove in-memory queue (Map<Long, LinkedList<Message>>)
   - Replace with database queries
   - Periodic task queries database for queued files

7. **History Discovery** (optional separate service)
   - Extract history scanning logic
   - Make it work with queue service

---

## Test Cases

### Test 1: Queue Persistence
```
1. Enable automation on chat with 100 files
2. Start downloading (10 files start)
3. Restart application
4. Expected: Queue persists, remaining 90 files still queued
5. Query: SELECT COUNT(*) FROM file_record WHERE download_status='queued'
6. Expected: 90
```

### Test 2: Priority Ordering
```
1. Mark some files with download_priority = 10
2. Mark others with download_priority = 0
3. Query queue
4. Expected: Priority 10 files come first
5. Within same priority, older queued_at comes first
```

### Test 3: Oldest/Newest Ordering
```
1. Queue files with downloadOldestFirst = true
2. Query: SELECT message_id FROM file_record WHERE download_status='queued' ORDER BY queued_at LIMIT 10
3. Expected: message_id values are ascending (older messages first)
4. Queue files with downloadOldestFirst = false
5. Expected: message_id values are descending (newer messages first)
```

### Test 4: Cutoff Date Integration
```
1. Set historySince = 2025-06-01
2. Queue files
3. Query: SELECT MIN(date) FROM file_record WHERE download_status='queued'
4. Expected: Minimum date is >= 2025-06-01
```

### Test 5: Scan State Tracking
```
1. Start history scan
2. Check: scan_state should be 'scanning' during scan
3. When complete: scan_state should be 'complete'
4. New messages arrive: scan_state stays 'complete' (don't re-scan)
```

---

## Known Issues & Considerations

### Issue 1: Migration Complexity
- Requires database migration (new columns + indexes)
- Existing installations need to run migration
- Test migration on backup database first

### Issue 2: Backward Compatibility
- AutomationState enum vs bitwise int state
- Keep legacy methods with @Deprecated annotation
- Provide conversion utilities

### Issue 3: Performance
- Indexes are critical for queue queries
- Without indexes, queries will be slow on large databases
- Test with 10,000+ files before deploying

### Issue 4: Queue Starvation
- If queueing logic is too conservative, downloads may stall
- Balance between queueing too many (memory) vs too few (starvation)
- Recommended: Queue in batches of 50, process limit of 5-10

### Issue 5: Race Conditions
- Multiple processes accessing queue simultaneously
- Use database transactions or SELECT FOR UPDATE
- Or rely on download_status as mutex (file can't be 'downloading' twice)

---

## Code Examples from v0.3.0 Implementation

### Complete Queue Service
```java
// DownloadQueueService.java (simplified)
public class DownloadQueueService {
    private static final Log log = LogFactory.get();
    
    public static Future<List<FileRecord>> getFilesForDownload(
        long telegramId, 
        int limit, 
        Integer cutoffDateSeconds, 
        Boolean downloadOldestFirst
    ) {
        return DataVerticle.fileRepository
            .getFilesReadyForDownload(telegramId, limit, cutoffDateSeconds, downloadOldestFirst);
    }
    
    public static Future<Integer> queueFilesForDownload(
        long telegramId, 
        long chatId, 
        int limit, 
        Integer cutoffDateSeconds, 
        Boolean downloadOldestFirst
    ) {
        return DataVerticle.fileRepository
            .queueFilesForDownload(telegramId, chatId, limit, cutoffDateSeconds, 
                                   downloadOldestFirst != null ? downloadOldestFirst : true);
    }
    
    public static Future<List<FileRecord>> getFilesForDownload(long telegramId, int limit) {
        return DataVerticle.fileRepository.getFilesForDownload(telegramId, limit);
    }
}
```

### Repository Implementation
```java
// In FileRepositoryImpl.java (conceptual)
@Override
public Future<Integer> queueFilesForDownload(
    long telegramId,
    long chatId,
    int limit,
    Integer cutoffDateSeconds,
    boolean downloadOldestFirst
) {
    String orderClause = downloadOldestFirst 
        ? "ORDER BY message_id ASC" 
        : "ORDER BY message_id DESC";
    
    String chatFilter = chatId > 0 ? "AND chat_id = #{chatId}" : "";
    String dateFilter = cutoffDateSeconds != null ? "AND date >= #{cutoffDateSeconds}" : "";
    
    String query = """
        UPDATE file_record
        SET download_status = 'queued', queued_at = #{now}
        WHERE id IN (
            SELECT id FROM file_record
            WHERE telegram_id = #{telegramId}
              AND download_status = 'idle'
              AND type != 'thumbnail'
              %s %s
            %s
            LIMIT #{limit}
        )
    """.formatted(chatFilter, dateFilter, orderClause);
    
    return SqlTemplate.forUpdate(sqlClient, query)
        .execute(params)
        .map(result -> result.rowCount());
}

@Override
public Future<List<FileRecord>> getFilesForDownload(long telegramId, int limit) {
    String query = """
        SELECT * FROM file_record
        WHERE telegram_id = ? AND download_status = 'queued'
        ORDER BY download_priority DESC, queued_at ASC
        LIMIT ?
    """;
    
    return executeQuery(query, telegramId, limit);
}
```

### AutoDownloadVerticle Refactor
```java
// Remove in-memory queue
// private final Map<Long, LinkedList<MessageWrapper>> waitingDownloadMessages = new ConcurrentHashMap<>();

// Replace with periodic database polling
@Override
public void start(Promise<Void> startPromise) {
    initAutoDownload()
        .compose(v -> this.initEventConsumer())
        .onSuccess(v -> {
            // Periodic history scanning (every 2 minutes)
            vertx.setPeriodic(0, HISTORY_SCAN_INTERVAL, id -> {
                scanHistoryAndQueueFiles();
            });
            
            // Periodic download processing (every 10 seconds)
            vertx.setPeriodic(0, DOWNLOAD_INTERVAL, id -> {
                processDownloadQueue();
            });
        });
}

private void processDownloadQueue() {
    autoRecords.getDownloadEnabledItems().forEach(auto -> {
        long telegramId = auto.telegramId;
        
        // 1. Queue idle files (up to 50)
        Integer cutoff = auto.download.rule.historySince;
        Boolean oldestFirst = auto.download.rule.downloadOldestFirst;
        
        DownloadQueueService.queueFilesForDownload(telegramId, 0, 50, cutoff, oldestFirst)
            .compose(queuedCount -> {
                // 2. Get files to download (up to concurrency limit)
                return DownloadQueueService.getFilesForDownload(telegramId, 5);
            })
            .onSuccess(files -> {
                // 3. Start downloads
                files.forEach(file -> {
                    TelegramVerticle tv = TelegramVerticles.get(telegramId);
                    tv.startDownload(file.chatId(), file.messageId(), file.id());
                });
            });
    });
}
```

---

## Integration Points

### Where to Make Changes

1. **Database Migration**
   - Create migration file (e.g., 0025_add_download_queue_columns.sql)
   - Run during application startup

2. **FileRecord Model**
   - Add 3 new fields: scanState, downloadPriority, queuedAt

3. **FileRepository Interface**
   - Add: queueFilesForDownload(...)
   - Add: getFilesForDownload(...)
   - Add: getFilesReadyForDownload(...) (optional)

4. **FileRepositoryImpl**
   - Implement queue methods with SQL

5. **New Service: DownloadQueueService**
   - Wrapper around repository methods
   - Add business logic (defaults, validation)

6. **New Enum: AutomationState** (optional but recommended)
   - Replace bitwise state with enum
   - Provide backward compatibility

7. **AutoDownloadVerticle**
   - Remove: in-memory queue (Map, LinkedList)
   - Add: periodic queue processing
   - Replace: addWaitingMessages() with queueFilesForDownload()
   - Replace: poll from memory with getFilesForDownload()

8. **History Discovery** (optional refactor)
   - Extract to separate service
   - Make it insert to database directly

---

## Test Cases

### Test 1: Basic Queue Persistence
```bash
# 1. Setup
docker-compose up -d
# Enable automation on a chat
# Start downloading some files

# 2. Crash test
docker-compose restart api
# OR: kill -9 <process>

# 3. Verify
# Query database
docker-compose exec db psql -U user -d telegram_files -c \
  "SELECT COUNT(*) FROM file_record WHERE download_status='queued';"
# Expected: Count > 0 (queued files persisted)

# 4. Wait
# Expected: Downloads resume automatically
```

### Test 2: Priority Ordering
```sql
-- 1. Setup (insert test data)
UPDATE file_record SET download_priority = 10 WHERE message_id = 123;
UPDATE file_record SET download_priority = 5 WHERE message_id = 456;
UPDATE file_record SET download_priority = 0 WHERE message_id = 789;

-- 2. Queue them
-- (trigger queueing)

-- 3. Verify order
SELECT id, message_id, download_priority, queued_at 
FROM file_record 
WHERE download_status = 'queued'
ORDER BY download_priority DESC, queued_at ASC;

-- Expected: 
-- message_id=123 (priority 10) first
-- message_id=456 (priority 5) second  
-- message_id=789 (priority 0) last
```

### Test 3: Oldest/Newest Ordering
```sql
-- Test oldest-first
SELECT message_id FROM file_record
WHERE download_status = 'queued' AND telegram_id = 1
ORDER BY queued_at ASC
LIMIT 10;
-- Expected: message_id values should be ascending (if downloadOldestFirst=true)

-- Test newest-first  
-- Expected: message_id values should be descending (if downloadOldestFirst=false)
```

### Test 4: Cutoff Date Integration
```sql
-- Set historySince = 1704067260 (2025-01-01 00:01:00 UTC)
-- Queue files
SELECT COUNT(*), MIN(date), MAX(date) 
FROM file_record 
WHERE download_status = 'queued' AND telegram_id = 1;
-- Expected: MIN(date) >= 1704067260
```

### Test 5: Concurrency Control
```bash
# 1. Set concurrency limit to 5
# 2. Queue 100 files
# 3. Check downloading count
SELECT COUNT(*) FROM file_record WHERE download_status = 'downloading';
# Expected: <= 5 (respects limit)

# 4. Wait for some to complete
# Expected: More files start automatically (queue is processed)
```

---

## Known Issues & Considerations

### Issue 1: Migration Risk
- **Problem**: Adding columns to large file_record table can be slow
- **Solution**: Run migration during low-traffic period
- **Alternative**: Use online schema change tools (gh-ost, pt-online-schema-change)

### Issue 2: Index Size
- **Problem**: Multiple indexes on file_record increases storage
- **Solution**: Indexes are critical for performance, worth the space
- **Monitor**: Check index size with EXPLAIN ANALYZE

### Issue 3: Queue Starvation
- **Problem**: If no files are queued, downloads stop
- **Solution**: Ensure queueing happens frequently enough
- **Recommended**: Queue batch of 50 every 10 seconds

### Issue 4: Race Conditions
- **Problem**: Multiple AutoDownloadVerticle instances (clustered deployment)
- **Solution**: Use database as single source of truth
- **Consideration**: download_status acts as distributed lock

### Issue 5: Memory vs Database Trade-off
- **Before**: Fast (memory), but volatile
- **After**: Persistent (database), but slower queries
- **Mitigation**: Indexes make queries fast enough (~1-5ms)

### Issue 6: Backward Compatibility
- **Problem**: Old automations have bitwise state (int)
- **Solution**: Keep legacy methods, add new enum-based methods
- **Provide**: Conversion utilities (fromLegacyState, toLegacyState)

---

## Performance Benchmarks

Expected performance on v0.3.0:

| Operation | Before (Memory) | After (Database) |
|-----------|----------------|------------------|
| Queue 100 files | <1ms | ~50ms |
| Get 10 files | <1ms | ~5ms (with indexes) |
| Check queue size | <1ms | ~2ms |
| Survive restart | ❌ Lost | ✅ Persists |
| Handle 10,000 files | ⚠️ Memory issues | ✅ Scales well |

---

## Validation Checklist

When re-implementing, verify:
- [ ] Migration file created and runs successfully
- [ ] file_record table has 3 new columns
- [ ] Indexes created (4 total)
- [ ] FileRecord model updated
- [ ] DownloadQueueService created
- [ ] AutomationState enum created (or state management improved)
- [ ] AutoDownloadVerticle refactored:
  - [ ] In-memory queue removed
  - [ ] Periodic queue processing added
  - [ ] Uses DownloadQueueService
- [ ] History discovery inserts to database
- [ ] Queue persists across restarts (test manually)
- [ ] Ordering works (oldest-first and newest-first)
- [ ] Priority works (if implemented)
- [ ] No memory leaks
- [ ] Concurrent downloads respect limit

---

## Dependencies

- **Requires**: Database with file_record table
- **Requires**: Migration system
- **Compatible with**: All other features (historySince, downloadOldestFirst, etc.)

---

## Estimated Re-implementation Time

**On clean codebase**: 1-2 days
- Migration: 1 hour
- Models: 1 hour
- Repository methods: 2-3 hours
- DownloadQueueService: 2-3 hours
- AutomationState enum: 1 hour
- AutoDownloadVerticle refactor: 3-4 hours (most complex)
- Testing: 4-5 hours (critical to get right)
- Bug fixes: 2-3 hours (expected)

**Total**: 16-20 hours for complete, tested implementation

---

## Simplification Options

If full implementation is too complex, consider:

### Option A: Hybrid Approach
- Queue to database (for persistence)
- Keep in-memory cache (for speed)
- Sync periodically

### Option B: Partial Implementation
- Only persist queue on shutdown
- Load on startup
- Keep memory queue during runtime

### Option C: Minimal Implementation
- Just add queued_at timestamp
- Don't implement full priority system
- Simpler queries

**Recommended**: Full implementation (Option from current code) - complexity is worth the benefits.

---

## Migration from Memory to Database

If upgrading from memory-based queue:

```java
// Step 1: Keep both systems running (transition period)
// - New files go to database
// - Existing in-memory queue processes out
// - Eventually in-memory queue empties

// Step 2: Remove in-memory queue
// - Delete Map<Long, LinkedList<Message>> variable
// - Remove all methods that touch it
// - Replace with database queries

// Step 3: Update all call sites
// Find: addToMemoryQueue(message)
// Replace: queueFilesForDownload(telegramId, chatId, ...)
```

---

## Future Enhancements

Possible improvements to this architecture:

1. **Priority API**: Allow users to set priority via UI
2. **Queue Visualization**: Show queued files in UI
3. **Queue Manipulation**: Reorder, cancel, or reprioritize queued files
4. **Smart Queueing**: Use ML to predict which files user wants first
5. **Distributed Queue**: Support multiple download workers

---

## This is Your Most Complex Feature

This refactor touched ~1,500 lines across 12 files. When re-implementing:
- Start with migration and models
- Build DownloadQueueService first (test independently)
- Then refactor AutoDownloadVerticle incrementally
- Test at each step
- Budget 2-3 days for complete implementation

