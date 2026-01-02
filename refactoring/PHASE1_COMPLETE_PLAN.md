# Phase 1 Architectural Refactoring - Complete Implementation Plan

**Status**: Mini Phase 1 Complete ✅ | Full Phase 1 Pending  
**Estimated Time**: 1 week (40 hours)  
**Risk Level**: Medium  
**Branch**: `refactor/phase1-architecture`

---

## Executive Summary

Phase 1 establishes architectural foundation for maintainability:
1. ✅ **Mini Phase 1 Complete** - ErrorHandling utility + DownloadStatistics domain object (2 hours)
2. ⏳ **Remaining Work** - Package reorganization + Dependency Injection + Error handling application (38 hours)

**Goal**: Transform from monolithic structure with static dependencies to organized, testable, maintainable architecture.

---

## What's Already Done (Mini Phase 1) ✅

### 1. ErrorHandling Utility Class
**File**: `api/src/main/java/telegram/files/ErrorHandling.java`
- `critical()` - Fail-fast for essential operations
- `recoverable()` - Graceful degradation with defaults
- `optional()` - Debug logging for non-critical ops
- `silent()` - Swallow errors (use sparingly)

### 2. DownloadStatistics Domain Object
**File**: `api/src/main/java/telegram/files/DownloadStatistics.java`
- Type-safe statistics representation
- Business methods: `pending()`, `progressPercent()`, `isComplete()`
- Null-safe `fromRow()` constructor
- Conversion: `toJson()`, `fromJson()`

### 3. Applied to Critical Paths
- `isTrackDownloadedStateEnabled()` - Uses ErrorHandling.recoverable()
- `startDownload()` - Uses ErrorHandling.critical()
- `getChatDownloadStatistics()` - Uses DownloadStatistics

**Commits**:
- `2a249e2` - Mini Phase 1 implementation
- `e01316d` - Import fix

---

## Remaining Work: Full Phase 1

### Task 1: Package Reorganization (6 hours)

#### **Objective**
Move 45+ classes from flat `telegram.files` package into bounded contexts.

#### **New Package Structure**
```
telegram.files/
├── core/                    (10 files)
│   ├── TelegramVerticle.java
│   ├── TelegramClient.java
│   ├── TelegramChats.java
│   ├── TelegramVerticles.java
│   ├── TelegramUpdateHandler.java
│   ├── Start.java
│   ├── Config.java
│   ├── EventEnum.java
│   ├── EventPayload.java
│   └── TelegramRunException.java
│
├── download/                (4 files)
│   ├── AutoDownloadVerticle.java
│   ├── DownloadQueueService.java
│   ├── HistoryDiscoveryService.java
│   └── PreloadMessageVerticle.java
│
├── transfer/                (2 files)
│   ├── TransferVerticle.java
│   └── Transfer.java
│
├── automation/              (2 files)
│   ├── AutomationsHolder.java
│   └── (AutomationState is in repository)
│
├── statistics/              (1 file)
│   └── AvgSpeed.java
│
├── util/                    (new, 3 files)
│   ├── ErrorHandling.java
│   ├── DownloadStatistics.java
│   └── DateUtils.java
│
└── repository/              (unchanged, 19 files)
    └── ... (keep as-is)
```

#### **File Move Commands**
```bash
cd /Users/fabian/projects/telegram-files/api/src/main/java/telegram/files

# Create util package
mkdir -p util

# Move to core
git mv TelegramVerticle.java core/
git mv TelegramClient.java core/
git mv TelegramChats.java core/
git mv TelegramVerticles.java core/
git mv TelegramUpdateHandler.java core/
git mv Start.java core/
git mv Config.java core/
git mv EventEnum.java core/
git mv EventPayload.java core/
git mv TelegramRunException.java core/

# Move to download
git mv AutoDownloadVerticle.java download/
git mv DownloadQueueService.java download/
git mv HistoryDiscoveryService.java download/
git mv PreloadMessageVerticle.java download/

# Move to transfer
git mv TransferVerticle.java transfer/
git mv Transfer.java transfer/

# Move to automation
git mv AutomationsHolder.java automation/

# Move to statistics
git mv AvgSpeed.java statistics/

# Move to util
git mv ErrorHandling.java util/
git mv DownloadStatistics.java util/
git mv DateUtils.java util/

# Keep in root (shared utilities)
# - DataVerticle.java
# - HttpVerticle.java
# - TdApiHelp.java
# - TelegramConverter.java
# - FileRecordRetriever.java
# - FileRouteHandler.java
# - MessageFilter.java
# - MessyUtils.java
# - Maintain.java
```

#### **Update Package Declarations**
After moving files, update package declaration in each moved file:

```java
// Example: core/TelegramVerticle.java
// OLD:
package telegram.files;

// NEW:
package telegram.files.core;
```

#### **Update Imports Across Codebase**
Use find-and-replace:

```bash
# Update imports in all Java files
find api/src -name "*.java" -exec sed -i '' 's/import telegram\.files\.TelegramVerticle;/import telegram.files.core.TelegramVerticle;/g' {} \;
find api/src -name "*.java" -exec sed -i '' 's/import telegram\.files\.AutoDownloadVerticle;/import telegram.files.download.AutoDownloadVerticle;/g' {} \;
# ... repeat for all moved classes
```

#### **Quality Gates**
- [ ] All files moved to correct packages
- [ ] All package declarations updated
- [ ] All imports updated (no compilation errors)
- [ ] `./gradlew compileJava` succeeds
- [ ] No broken references

#### **Testing**
```bash
# 1. Compilation test
cd api && ./gradlew clean compileJava
# Expected: BUILD SUCCESSFUL

# 2. Import verification
grep -r "import telegram.files.TelegramVerticle" api/src/
# Expected: No matches (should be telegram.files.core.TelegramVerticle)

# 3. Package verification
find api/src/main/java/telegram/files/core -name "*.java" | wc -l
# Expected: 10 files
```

---

### Task 2: Extract FileDownloadService (8 hours)

#### **Objective**
Extract 300 LOC of download logic from TelegramVerticle (1,291 lines) into focused FileDownloadService.

#### **Methods to Extract**
From `TelegramVerticle.java`:
- `startDownload(Long chatId, Long messageId, Integer fileId)` - Lines 304-396
- `downloadThumbnail(...)` - Lines 398-420
- `cancelDownload(Integer fileId)` - Lines 422-444
- `togglePauseDownload(Integer fileId)` - Lines 446-464
- `syncFileDownloadStatus(...)` - Lines 990-1049

#### **New Service Class**
```java
package telegram.files.download;

import telegram.files.core.TelegramClient;
import telegram.files.repository.FileRecord;
import telegram.files.repository.FileRepository;
import telegram.files.util.ErrorHandling;
import io.vertx.core.Future;
import org.drinkless.tdlib.TdApi;

/**
 * Service responsible for file download operations.
 * Extracted from TelegramVerticle to follow Single Responsibility Principle.
 */
public class FileDownloadService {
    
    private final TelegramClient client;
    private final FileRepository fileRepository;
    private final long telegramId;
    
    public FileDownloadService(TelegramClient client, FileRepository fileRepository, long telegramId) {
        this.client = client;
        this.fileRepository = fileRepository;
        this.telegramId = telegramId;
    }
    
    /**
     * Start downloading a file.
     * 
     * @param chatId Chat ID containing the message
     * @param messageId Message ID containing the file
     * @param fileId Telegram file ID
     * @return Future resolving to FileRecord when download starts
     */
    public Future<FileRecord> startDownload(Long chatId, Long messageId, Integer fileId) {
        return ErrorHandling.critical(
            Future.all(
                client.execute(new TdApi.GetFile(fileId)),
                client.execute(new TdApi.GetMessage(chatId, messageId)),
                client.execute(new TdApi.GetMessageThread(chatId, messageId), true)
            ),
            String.format("Start download for file %d in chat %d", fileId, chatId)
        ).compose(results -> {
            // ... (copy existing logic from TelegramVerticle)
        });
    }
    
    /**
     * Cancel an active download.
     */
    public Future<Void> cancelDownload(Integer fileId) {
        // ... (copy from TelegramVerticle)
    }
    
    /**
     * Toggle pause/resume for a download.
     */
    public Future<Void> togglePauseDownload(Integer fileId) {
        // ... (copy from TelegramVerticle)
    }
    
    /**
     * Download thumbnail for a file.
     */
    public Future<Boolean> downloadThumbnail(Long chatId, Long messageId, FileRecord thumbnailRecord) {
        // ... (copy from TelegramVerticle)
    }
    
    /**
     * Sync file download status when file completes.
     */
    public Future<Void> syncFileDownloadStatus(TdApi.File file, TdApi.Message message, TdApi.MessageThreadInfo messageThreadInfo) {
        // ... (copy from TelegramVerticle)
    }
}
```

#### **Update TelegramVerticle**
```java
public class TelegramVerticle extends AbstractVerticle {
    
    // Add service field
    private FileDownloadService downloadService;
    
    @Override
    public void start(Promise<Void> startPromise) {
        // Initialize service
        this.downloadService = new FileDownloadService(
            this.client, 
            DataVerticle.fileRepository, 
            this.telegramRecord.id()
        );
        
        // ... rest of start()
    }
    
    // Delegate to service
    public Future<FileRecord> startDownload(Long chatId, Long messageId, Integer fileId) {
        return downloadService.startDownload(chatId, messageId, fileId);
    }
    
    // Remove old implementation (300 lines deleted)
}
```

#### **Quality Gates**
- [ ] FileDownloadService created with 5 methods
- [ ] TelegramVerticle reduced by ~300 LOC
- [ ] All download operations delegated to service
- [ ] No compilation errors
- [ ] Existing tests still pass

#### **Testing**
```bash
# 1. Verify extraction
wc -l api/src/main/java/telegram/files/download/FileDownloadService.java
# Expected: ~350 lines

wc -l api/src/main/java/telegram/files/core/TelegramVerticle.java
# Expected: ~990 lines (was 1,291)

# 2. Functional test
# Start application, download a file
# Expected: Works identically to before

# 3. Verify delegation
grep "downloadService\." api/src/main/java/telegram/files/core/TelegramVerticle.java | wc -l
# Expected: 5+ (one for each delegated method)
```

---

### Task 3: Create ServiceContext for Transitional DI (4 hours)

#### **Objective**
Replace static `DataVerticle.*` accessors with injected dependencies, enabling testability.

#### **Create ServiceContext Class**
```java
package telegram.files;

import telegram.files.repository.*;

/**
 * Transitional dependency injection container.
 * Holds references to shared services, passed via constructor instead of static access.
 * 
 * Migration path:
 * 1. Pass ServiceContext to new code
 * 2. Gradually refactor static calls to use context
 * 3. Eventually remove DataVerticle static fields
 */
public class ServiceContext {
    
    private final FileRepository fileRepository;
    private final TelegramRepository telegramRepository;
    private final SettingRepository settingRepository;
    private final StatisticRepository statisticRepository;
    
    public ServiceContext(
        FileRepository fileRepository,
        TelegramRepository telegramRepository,
        SettingRepository settingRepository,
        StatisticRepository statisticRepository
    ) {
        this.fileRepository = fileRepository;
        this.telegramRepository = telegramRepository;
        this.settingRepository = settingRepository;
        this.statisticRepository = statisticRepository;
    }
    
    // Getters
    public FileRepository fileRepository() { return fileRepository; }
    public TelegramRepository telegramRepository() { return telegramRepository; }
    public SettingRepository settingRepository() { return settingRepository; }
    public StatisticRepository statisticRepository() { return statisticRepository; }
    
    /**
     * Create from DataVerticle static fields (transitional).
     */
    public static ServiceContext fromDataVerticle() {
        return new ServiceContext(
            DataVerticle.fileRepository,
            DataVerticle.telegramRepository,
            DataVerticle.settingRepository,
            DataVerticle.statisticRepository
        );
    }
}
```

#### **Update DataVerticle to Create Context**
```java
public class DataVerticle extends AbstractVerticle {
    
    // Keep static fields for backward compatibility (temporary)
    public static Pool pool;
    public static FileRepository fileRepository;
    // ...
    
    // Add context instance
    public static ServiceContext serviceContext;
    
    public void start(Promise<Void> stopPromise) {
        pool = buildSqlClient();
        settingRepository = new SettingRepositoryImpl(pool);
        telegramRepository = new TelegramRepositoryImpl(pool);
        fileRepository = new FileRepositoryImpl(pool);
        statisticRepository = new StatisticRepositoryImpl(pool);
        
        // Create context
        serviceContext = new ServiceContext(
            fileRepository,
            telegramRepository,
            settingRepository,
            statisticRepository
        );
        
        // ... rest of start()
    }
}
```

#### **Refactor FileDownloadService to Use DI**
```java
public class FileDownloadService {
    
    private final TelegramClient client;
    private final ServiceContext context;  // Instead of static DataVerticle.*
    private final long telegramId;
    
    public FileDownloadService(TelegramClient client, ServiceContext context, long telegramId) {
        this.client = client;
        this.context = context;
        this.telegramId = telegramId;
    }
    
    public Future<FileRecord> startDownload(...) {
        // OLD: DataVerticle.fileRepository.getByUniqueId(...)
        // NEW: context.fileRepository().getByUniqueId(...)
    }
}
```

#### **Migration Strategy**
1. Create ServiceContext class
2. Add to DataVerticle.start()
3. Update FileDownloadService to accept ServiceContext
4. Update DownloadQueueService to accept ServiceContext
5. Gradually migrate other services
6. **Don't remove static fields yet** (backward compatibility)

#### **Quality Gates**
- [ ] ServiceContext class created
- [ ] DataVerticle creates context instance
- [ ] FileDownloadService uses context (no static calls)
- [ ] DownloadQueueService uses context (no static calls)
- [ ] Application starts without errors
- [ ] Downloads still work

#### **Testing**
```bash
# 1. Verify no static calls in new services
grep "DataVerticle\." api/src/main/java/telegram/files/download/FileDownloadService.java
# Expected: No matches

# 2. Verify context is used
grep "context\.fileRepository()" api/src/main/java/telegram/files/download/FileDownloadService.java
# Expected: Multiple matches

# 3. Functional test
# Start app, trigger download, verify works
```

---

### Task 4: Apply ErrorHandling Patterns (4 hours)

#### **Objective**
Replace inconsistent error handling with unified patterns across 15-20 critical code paths.

#### **Critical Paths to Update**

**1. AutoDownloadVerticle.java**
```java
// Line ~280: History scanning
// OLD:
TdApi.FoundChatMessages foundChatMessages = Future.await(telegramVerticle.client.execute(searchChatMessages)
    .onFailure(r -> log.error("Search chat messages failed! TelegramId: %d ChatId: %d".formatted(telegramId, chatId), r))
);

// NEW:
Future<TdApi.FoundChatMessages> foundChatMessagesFuture = ErrorHandling.critical(
    telegramVerticle.client.execute(searchChatMessages),
    String.format("Search chat messages for telegramId %d, chatId %d", telegramId, chatId)
);
// Then use .compose() instead of Future.await()
```

**2. TransferVerticle.java**
```java
// Transfer operations should use ErrorHandling.critical()
return ErrorHandling.critical(
    fileRepository.updateTransferStatus(...),
    "Update transfer status for file " + fileRecord.uniqueId()
);
```

**3. HttpVerticle.java**
```java
// Settings updates should use ErrorHandling.recoverable()
return ErrorHandling.recoverable(
    settingRepository.createOrUpdate(...),
    JsonObject.of(),
    "Update settings"
);
```

**4. PreloadMessageVerticle.java**
```java
// Message preloading is optional
return ErrorHandling.optional(
    client.execute(searchChatMessages),
    "Preload messages for chat " + chatId
);
```

#### **Pattern Selection Guide**
- **critical()**: Database writes, essential API calls, user-initiated actions
- **recoverable()**: Settings fetch, optional features, degradable functionality
- **optional()**: Thumbnails, statistics, background tasks
- **silent()**: Truly ignorable operations (use sparingly)

#### **Quality Gates**
- [ ] 15-20 critical paths wrapped with ErrorHandling
- [ ] Consistent error logging format
- [ ] No bare `.onFailure()` in critical code
- [ ] Error messages include context
- [ ] Compilation succeeds

#### **Testing**
```bash
# 1. Count ErrorHandling usage
grep -r "ErrorHandling\." api/src/main/java/telegram/files | wc -l
# Expected: 20+ occurrences

# 2. Verify patterns
grep "ErrorHandling.critical" api/src/main/java/telegram/files | wc -l
# Expected: 10+ (critical operations)

grep "ErrorHandling.recoverable" api/src/main/java/telegram/files | wc -l
# Expected: 5+ (degradable operations)

# 3. Error simulation test
# Disconnect database, trigger operation
# Expected: Consistent error messages with [CRITICAL] or [RECOVERABLE] prefix
```

---

### Task 5: Standardize Async Patterns (6 hours)

#### **Objective**
Remove remaining `Future.await()` calls (23 total), convert to reactive composition.

#### **Current Future.await() Locations**
```bash
# Find all blocking calls
grep -rn "Future.await" api/src/main/java/telegram/files
```

**Priority Order** (highest impact first):
1. AutoDownloadVerticle.java (7 uses) - In hot path
2. TelegramVerticle.java (1 use remaining)
3. PreloadMessageVerticle.java (3 uses)
4. TransferVerticle.java (5 uses)
5. Others (7 uses)

#### **Conversion Pattern**
```java
// BEFORE (blocking):
private void processFile() {
    TdApi.Message message = Future.await(client.execute(new TdApi.GetMessage(...)));
    if (message == null) return;
    
    FileRecord record = Future.await(repository.getByUniqueId(message.id));
    // ... more processing
}

// AFTER (reactive):
private Future<Void> processFile() {
    return client.execute(new TdApi.GetMessage(...))
        .compose(message -> {
            if (message == null) {
                return Future.failedFuture("Message not found");
            }
            return repository.getByUniqueId(message.id);
        })
        .compose(record -> {
            // ... more processing
            return Future.succeededFuture();
        });
}
```

#### **Quality Gates**
- [ ] Zero `Future.await()` calls in codebase
- [ ] All async operations use `.compose()` or `.map()`
- [ ] Error handling consistent (via ErrorHandling utility)
- [ ] No deadlocks or blocking
- [ ] Performance improved (non-blocking)

#### **Testing**
```bash
# 1. Verify no blocking
grep -r "Future.await" api/src/main/java/telegram/files
# Expected: No matches

# 2. Performance test
# Measure response time for download start
# Before: ~50-100ms (includes blocking)
# After: ~10-20ms (fully async)

# 3. Concurrency test
# Start 10 downloads simultaneously
# Before: Sequential (blocking)
# After: Parallel (non-blocking)
```

---

## LLM Implementation Instructions

### **Copy-Paste Prompt for AI Assistant**

```
I have a telegram-files fork that needs Phase 1 architectural refactoring.

**Context:**
- Repository: /Users/fabian/projects/telegram-files
- Branch: refactor/phase1-architecture
- Mini Phase 1 is complete (ErrorHandling + DownloadStatistics)
- Need to complete remaining Phase 1 tasks

**Your Task:**
Implement the remaining Phase 1 refactoring following the plan in:
`refactoring/PHASE1_COMPLETE_PLAN.md`

**Tasks (in order):**
1. Package Reorganization (6 hours)
   - Move 45+ files to new packages (core, download, transfer, automation, statistics, util)
   - Update all package declarations
   - Update all imports across codebase
   - Test compilation

2. Extract FileDownloadService (8 hours)
   - Extract 300 LOC from TelegramVerticle
   - Create FileDownloadService class
   - Update TelegramVerticle to delegate
   - Test downloads still work

3. Create ServiceContext for DI (4 hours)
   - Create ServiceContext class
   - Update DataVerticle to create context
   - Refactor services to use context instead of static calls
   - Test application starts

4. Apply ErrorHandling Patterns (4 hours)
   - Wrap 15-20 critical paths with ErrorHandling utility
   - Use critical(), recoverable(), optional() appropriately
   - Test error scenarios

5. Standardize Async Patterns (6 hours)
   - Remove all 23 Future.await() calls
   - Convert to reactive composition
   - Test performance and concurrency

**Requirements:**
- Follow quality gates for each task
- Run tests after each task
- Commit after each task completes
- Report progress and blockers

**Start with Task 1: Package Reorganization**
```

---

## Quality Gates & Success Criteria

### **After Each Task**

**Compilation Gate:**
```bash
cd api && ./gradlew clean compileJava
# Must succeed before proceeding
```

**Linter Gate:**
```bash
# No new warnings
./gradlew check
```

**Git State:**
```bash
# Clean commits, no merge conflicts
git status
# Expected: nothing to commit, working tree clean
```

### **After Full Phase 1**

**Structural Verification:**
```bash
# 1. Package structure
find api/src/main/java/telegram/files -type d
# Expected: core/, download/, transfer/, automation/, statistics/, util/

# 2. File counts
find api/src/main/java/telegram/files/core -name "*.java" | wc -l
# Expected: 10

find api/src/main/java/telegram/files/download -name "*.java" | wc -l
# Expected: 5 (4 moved + FileDownloadService)

# 3. No static access in services
grep "DataVerticle\." api/src/main/java/telegram/files/download/*.java
# Expected: No matches (all use ServiceContext)

# 4. No Future.await
grep -r "Future.await" api/src/main/java/telegram/files
# Expected: No matches

# 5. ErrorHandling usage
grep -r "ErrorHandling\." api/src/main/java/telegram/files | wc -l
# Expected: 25+ occurrences
```

**Functional Verification:**
```bash
# 1. Application starts
docker-compose up -d
docker logs telegram-files | grep "Started"
# Expected: "Http server started on port 8080"

# 2. Download works
curl -X POST http://localhost:8080/1/file/start-download \
  -H "Content-Type: application/json" \
  -d '{"chatId":123,"messageId":456,"fileId":789}'
# Expected: 200 OK

# 3. Statistics work
curl http://localhost:8080/telegram/1/chat/123/statistics
# Expected: JSON with total, downloading, completed, etc.

# 4. No errors in logs
docker logs telegram-files | grep ERROR | wc -l
# Expected: 0 (or only expected errors)
```

**Performance Verification:**
```bash
# 1. Response time improved
time curl http://localhost:8080/telegram/1/chat/123/statistics
# Expected: < 100ms (was potentially higher with blocking)

# 2. Concurrent operations
# Start 10 downloads simultaneously
# Expected: All start in parallel (not sequential)

# 3. Memory stable
docker stats telegram-files --no-stream
# Expected: Memory usage similar to before (no leaks)
```

---

## Rollback Plan

If Phase 1 causes issues:

```bash
# Option A: Revert specific task
git revert <commit-hash>

# Option B: Reset to main
git checkout main
git branch -D refactor/phase1-architecture

# Option C: Cherry-pick working parts
git checkout main
git checkout -b refactor/phase1-partial
git cherry-pick <hash-of-ErrorHandling-commit>
git cherry-pick <hash-of-DownloadStatistics-commit>
# Skip problematic commits
```

---

## Risk Mitigation

### **High-Risk Areas**
1. **Package moves** - Can break imports if not thorough
2. **Service extraction** - Can break if dependencies missed
3. **Future.await removal** - Can introduce subtle async bugs

### **Mitigation Strategies**
1. **Incremental commits** - One task per commit
2. **Test after each task** - Catch issues early
3. **Keep backup branch** - Easy rollback
4. **Gradual migration** - ServiceContext alongside static (transitional)

---

## Timeline Estimate

| Task | Time | Cumulative |
|------|------|------------|
| ✅ Mini Phase 1 | 2h | 2h |
| Package Reorg | 6h | 8h |
| Extract FileDownloadService | 8h | 16h |
| ServiceContext DI | 4h | 20h |
| Apply ErrorHandling | 4h | 24h |
| Standardize Async | 6h | 30h |
| Testing & Fixes | 6h | 36h |
| Documentation | 4h | 40h |

**Total**: 40 hours (1 week)

---

## Success Metrics

### **Code Quality**
- ✅ TelegramVerticle < 1,000 LOC (from 1,291)
- ✅ No static service access in new code
- ✅ Zero Future.await() calls
- ✅ Consistent error handling (ErrorHandling.*)
- ✅ Type-safe domain objects

### **Maintainability**
- ✅ Clear package boundaries
- ✅ Single Responsibility per class
- ✅ Testable services (DI)
- ✅ Self-documenting error handling

### **Performance**
- ✅ Non-blocking operations
- ✅ Parallel execution possible
- ✅ No event loop blocking

### **Reliability**
- ✅ Graceful error degradation
- ✅ Consistent error logging
- ✅ No silent failures

---

## Post-Phase 1 State

### **Before Phase 1**
```
Structure: Flat package, 45+ files
TelegramVerticle: 1,291 LOC (god object)
Dependencies: Static access (untestable)
Async: Mixed patterns, 23 Future.await()
Errors: Inconsistent handling
Grade: B+
```

### **After Phase 1**
```
Structure: 6 packages, organized by domain
TelegramVerticle: ~900 LOC (focused)
Dependencies: ServiceContext (testable)
Async: Consistent composition, 0 Future.await()
Errors: Unified ErrorHandling utility
Grade: A-
```

---

## Next Steps After Phase 1

### **Phase 2: Decomposition** (2 weeks)
- Split remaining god objects
- Add transaction support
- Create more domain services

### **Phase 3: Strategic** (1 month)
- Rich domain model
- TDLib adapter layer
- Full test coverage

**Phase 1 is the foundation** - makes Phase 2 & 3 possible.

---

## Files to Create/Modify

### **New Files** (5)
- ✅ `ErrorHandling.java` (Mini Phase 1)
- ✅ `DownloadStatistics.java` (Mini Phase 1)
- ⏳ `ServiceContext.java` (Task 3)
- ⏳ `download/FileDownloadService.java` (Task 2)
- ⏳ `util/` package (Task 1)

### **Modified Files** (~50)
- ⏳ All moved files (package declarations)
- ⏳ All files importing moved classes (imports)
- ⏳ TelegramVerticle.java (delegate to FileDownloadService)
- ⏳ DataVerticle.java (create ServiceContext)
- ⏳ AutoDownloadVerticle.java (use ErrorHandling, remove Future.await)
- ⏳ DownloadQueueService.java (use ServiceContext)
- ⏳ 10-15 other files (apply ErrorHandling patterns)

---

## Common Pitfalls & Solutions

### **Pitfall 1: Import Hell**
**Problem**: After moving files, 100+ import statements need updating

**Solution**:
```bash
# Use sed for bulk updates
find api/src -name "*.java" -exec sed -i '' \
  's/import telegram\.files\.TelegramVerticle;/import telegram.files.core.TelegramVerticle;/g' {} \;

# Or use IDE refactoring (IntelliJ: Refactor → Move)
```

### **Pitfall 2: Circular Dependencies**
**Problem**: After extraction, ServiceA needs ServiceB which needs ServiceA

**Solution**:
- Use interfaces to break cycles
- Pass dependencies via constructor
- Avoid bidirectional dependencies

### **Pitfall 3: Async Conversion Bugs**
**Problem**: Converting Future.await() to composition introduces subtle bugs

**Solution**:
- Test each conversion individually
- Use `.compose()` for chaining
- Use `.map()` for transformations
- Always handle errors with `.recover()` or `.onFailure()`

### **Pitfall 4: Breaking Changes**
**Problem**: Refactoring breaks existing functionality

**Solution**:
- Keep static fields during transition (backward compat)
- Add new code alongside old
- Gradually migrate
- Remove old code last

---

## Verification Checklist

Before marking Phase 1 complete:

### **Code Structure**
- [ ] 6 packages created (core, download, transfer, automation, statistics, util)
- [ ] 45+ files moved to appropriate packages
- [ ] All package declarations updated
- [ ] All imports updated
- [ ] No compilation errors

### **Services**
- [ ] ErrorHandling utility exists and used 20+ times
- [ ] DownloadStatistics domain object exists
- [ ] FileDownloadService extracted (~350 LOC)
- [ ] ServiceContext created
- [ ] TelegramVerticle reduced to ~900 LOC

### **Dependencies**
- [ ] ServiceContext passed to new services
- [ ] No static DataVerticle.* calls in new code
- [ ] Old code still works (backward compat)

### **Async**
- [ ] Zero Future.await() calls
- [ ] All operations use composition
- [ ] Consistent error handling

### **Testing**
- [ ] Application starts
- [ ] Downloads work
- [ ] Transfers work
- [ ] Statistics work
- [ ] No errors in logs
- [ ] Performance stable or improved

---

## Documentation Updates Needed

After Phase 1:

1. **Update features/05-database-driven-download-queue.md**
   - Reflect new package structure
   - Update code examples with new imports

2. **Create ARCHITECTURE.md**
   - Document package structure
   - Explain ServiceContext pattern
   - Show dependency flow

3. **Update CONTRIBUTING.md** (if exists)
   - Error handling guidelines
   - Async patterns to use
   - Package organization rules

---

## Estimated Completion

**If starting now:**
- Day 1: Package reorganization (6h) + FileDownloadService (2h)
- Day 2: FileDownloadService complete (6h) + ServiceContext (2h)
- Day 3: ServiceContext complete (2h) + ErrorHandling application (4h) + Async patterns (2h)
- Day 4: Async patterns complete (4h) + Testing (4h)
- Day 5: Bug fixes (4h) + Documentation (4h)

**Total**: 5 days (40 hours)

---

## This Document Is Your Roadmap

Use this as:
1. **Implementation guide** - Follow tasks in order
2. **Quality checklist** - Verify each gate before proceeding
3. **Testing protocol** - Run tests after each task
4. **LLM instruction** - Copy relevant sections to AI assistant

**Phase 1 transforms the codebase from "working" to "maintainable".**

