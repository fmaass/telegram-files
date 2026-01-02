# Phase 1 Remaining Work - Complete Implementation Guide

**Status**: Package Reorganization Complete ✅ | Service Extraction & DI Pending  
**Current Branch**: refactor/phase1-clean  
**Estimated Time**: 12-16 hours  
**Risk Level**: Medium-High  
**Last Updated**: January 2, 2026

---

## Lessons Learned from First Attempt

### ❌ **What Went Wrong**

1. **Quality Gates Skipped**
   - Changed 39 files without incremental compilation checks
   - Accumulated 28+ compilation errors
   - Discovered errors only at the end

2. **Async Refactoring Was Too Aggressive**
   - Removed Future.await() across entire codebase simultaneously
   - Created complex nested callback issues
   - Syntax errors in deeply nested lambdas

3. **No Incremental Testing**
   - Made all changes, then tried to compile
   - No functional testing between tasks
   - Couldn't identify which change broke what

4. **Import Management Chaos**
   - Updated imports inconsistently
   - Sed commands had issues
   - Manual fixes required for 50+ files

### ✅ **What Worked**

1. **Package Structure Design**
   - 6 bounded contexts are perfect
   - Logical grouping is excellent
   - File organization makes sense

2. **Utility Classes**
   - ErrorHandling is well-designed
   - DownloadStatistics is type-safe
   - Both are production-quality

3. **Systematic Approach**
   - When we fixed imports systematically, it worked
   - Clean branch approach succeeded
   - Step-by-step compilation verification worked

---

## Critical Rules for Remaining Work

### **MANDATORY RULES** (Violate at Your Peril)

**Rule 1: Compile After EVERY File Change**
```bash
# After modifying ANY Java file:
docker build -t test-incremental .
# If it fails, FIX IMMEDIATELY before proceeding
```

**Rule 2: No Mass Changes**
- Change ONE file at a time
- Test that ONE file
- Commit that ONE file
- Then move to next file

**Rule 3: Quality Gates Are Not Optional**
- Each gate MUST pass before proceeding
- If gate fails, STOP and fix
- Never skip a gate "to save time"

**Rule 4: Test Functionality, Not Just Compilation**
- After extraction, test the extracted feature
- After DI change, test dependency injection works
- After ANY change, verify application still works

**Rule 5: Keep Escape Hatches**
- Commit after every successful change
- Can rollback to any previous commit
- Don't make 10 changes then try to debug

---

## What's Complete ✅

### **Task 1: Package Reorganization** - DONE

**Completed**:
- ✅ 22 files moved to 6 packages
- ✅ All package declarations updated
- ✅ All imports updated (106 changes across 35 files)
- ✅ Compilation successful (0 errors)
- ✅ Application deployed and running
- ✅ Functional equivalence verified

**Commit**: `530d2d1` - Package reorganization complete

**Package Structure**:
```
telegram.files/
├── core/ (10 files) - Core Telegram functionality
├── download/ (4 files) - Download management
├── transfer/ (2 files) - File transfer
├── automation/ (1 file) - Automation management
├── statistics/ (1 file) - Performance tracking
├── util/ (3 files) - Utilities (ErrorHandling, DownloadStatistics, DateUtils)
└── root/ (8 files) - Shared infrastructure (DataVerticle, HttpVerticle, etc.)
```

---

## What's Left - Detailed Task Breakdown

### **Task 2: Extract FileDownloadService** (6-8 hours)

**Status**: Not started  
**Priority**: P1 - High value  
**Risk**: High (changes TelegramVerticle significantly)  
**Estimated Time**: 6-8 hours

#### **Objective**

Extract 300+ lines of download logic from TelegramVerticle (1,252 LOC) into focused FileDownloadService, reducing TelegramVerticle to ~950 LOC.

#### **Why This Matters**

**Before**:
```
TelegramVerticle: 1,252 lines
- Authorization (150 LOC)
- Downloads (300 LOC) ← Extract this
- Chat management (200 LOC)
- Statistics (150 LOC)
- Proxy management (100 LOC)
- Events (200 LOC)
- Misc (152 LOC)
```

**After**:
```
TelegramVerticle: ~950 lines
- Authorization (150 LOC)
- Chat management (200 LOC)
- Statistics (150 LOC)
- Proxy management (100 LOC)
- Events (200 LOC)
- Misc + delegation (150 LOC)

FileDownloadService: ~300 lines
- startDownload() - Core download logic
- cancelDownload() - Cancel active download
- togglePauseDownload() - Pause/resume
- downloadThumbnail() - Thumbnail handling
- syncFileDownloadStatus() - Status sync
```

#### **Step-by-Step Implementation**

**Step 2.1: Create FileDownloadService Class** (30 minutes)

Create: `api/src/main/java/telegram/files/download/FileDownloadService.java`

```java
package telegram.files.download;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import io.vertx.core.VertxException;
import io.vertx.core.json.JsonObject;
import io.vertx.core.Vertx;
import org.drinkless.tdlib.TdApi;
import org.jooq.lambda.tuple.Tuple;
import telegram.files.ServiceContext;
import telegram.files.TdApiHelp;
import telegram.files.FileRecordRetriever;
import telegram.files.core.EventPayload;
import telegram.files.core.EventEnum;
import telegram.files.core.TelegramClient;
import telegram.files.repository.FileRecord;
import telegram.files.repository.SettingKey;
import telegram.files.util.ErrorHandling;
import telegram.files.util.DateUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/**
 * Service responsible for file download operations.
 * Extracted from TelegramVerticle to follow Single Responsibility Principle.
 */
public class FileDownloadService {
    
    private static final Log log = LogFactory.get();
    
    private final TelegramClient client;
    private final ServiceContext context;
    private final long telegramId;
    private final Vertx vertx;
    private final String rootId;
    
    // Cache for trackDownloadedState setting
    private volatile Boolean trackDownloadedStateCache = null;
    private volatile long trackDownloadedStateCacheTime = 0;
    private static final long CACHE_TTL_MS = 60000;
    
    public FileDownloadService(
        TelegramClient client,
        ServiceContext context,
        long telegramId,
        Vertx vertx,
        String rootId
    ) {
        this.client = client;
        this.context = context;
        this.telegramId = telegramId;
        this.vertx = vertx;
        this.rootId = rootId;
    }
    
    /**
     * Start downloading a file.
     * Copy EXACTLY from TelegramVerticle.startDownload() method.
     */
    public Future<FileRecord> startDownload(Long chatId, Long messageId, Integer fileId) {
        // TODO: Copy implementation from TelegramVerticle.java
        // Lines approximately 304-396 in TelegramVerticle
        return Future.failedFuture("Not implemented yet");
    }
    
    // ... other methods (copy from TelegramVerticle)
}
```

**Quality Gate 2.1**:
```bash
# Compile after creating file
docker build -t test .
# Expected: BUILD SUCCESSFUL (empty methods don't break compilation)
```

**CRITICAL**: Don't copy logic yet, just create the skeleton.

---

**Step 2.2: Copy startDownload() Method** (1 hour)

**CRITICAL INSTRUCTION**: Copy the EXACT implementation, do NOT refactor while copying.

**Source**: `core/TelegramVerticle.java` lines ~304-396

**Process**:
1. Find `public Future<FileRecord> startDownload` in TelegramVerticle
2. Copy from opening brace to closing brace (ENTIRE method)
3. Paste into FileDownloadService
4. Replace `DataVerticle.fileRepository` with `context.fileRepository()`
5. Replace `DataVerticle.settingRepository` with `context.settingRepository()`
6. Replace `this.telegramRecord.id()` with `this.telegramId`
7. Replace `getRootId()` with `this.rootId`
8. Replace `sendEvent(...)` with `vertx.eventBus().publish(...)`

**Quality Gate 2.2**:
```bash
# Compile after copying ONE method
docker build -t test .
# Expected: BUILD SUCCESSFUL

# If FAILS:
# - Check all references are updated (DataVerticle → context)
# - Check all method calls exist
# - Fix BEFORE copying next method
```

**COMMIT**: `git commit -m "refactor: copy startDownload to FileDownloadService"`

---

**Step 2.3: Copy cancelDownload() Method** (30 minutes)

**Source**: `core/TelegramVerticle.java` lines ~422-444

**Process**: Same as Step 2.2
1. Copy EXACT method
2. Update references (DataVerticle → context)
3. Test compilation
4. Commit

**Quality Gate 2.3**:
```bash
docker build -t test .
# Expected: BUILD SUCCESSFUL
```

**COMMIT**: `git commit -m "refactor: copy cancelDownload to FileDownloadService"`

---

**Step 2.4: Copy togglePauseDownload() Method** (30 minutes)

**Source**: `core/TelegramVerticle.java` lines ~446-464

**Process**: Same pattern
**Quality Gate**: Compile + commit

---

**Step 2.5: Copy downloadThumbnail() Method** (30 minutes)

**Source**: `core/TelegramVerticle.java` lines ~398-420

**Process**: Same pattern
**Quality Gate**: Compile + commit

---

**Step 2.6: Copy syncFileDownloadStatus() Method** (1 hour)

**Source**: `core/TelegramVerticle.java` lines ~990-1049

**This is the most complex method** - contains async chains.

**CRITICAL**: Copy EXACTLY, do not try to "improve" it.

**Process**:
1. Copy entire method including all nested callbacks
2. Update DataVerticle → context references
3. Update telegramRecord → telegramId references  
4. Test compilation IMMEDIATELY
5. If fails, revert and try again

**Quality Gate 2.6**:
```bash
docker build -t test .
# Expected: BUILD SUCCESSFUL

# If FAILS with async errors:
# - Revert the method
# - Copy again more carefully
# - Check brace balance
# - DO NOT proceed until this compiles
```

**COMMIT**: `git commit -m "refactor: copy syncFileDownloadStatus to FileDownloadService"`

---

**Step 2.7: Copy Helper Methods** (30 minutes)

**Methods to copy**:
- `isTrackDownloadedStateEnabled()` - If exists
- Any private helper methods used by the 5 main methods

**Process**: Copy each one individually, test after each

---

**Step 2.8: Update TelegramVerticle to Delegate** (1 hour)

**After ALL methods are copied and compiling**, update TelegramVerticle:

```java
public class TelegramVerticle extends AbstractVerticle {
    
    // Add service field
    private FileDownloadService downloadService;
    
    @Override
    public void start(Promise<Void> startPromise) {
        // ... existing initialization ...
        
        // Initialize download service
        this.downloadService = new FileDownloadService(
            this.client,
            ServiceContext.fromDataVerticle(),
            this.telegramRecord.id(),
            vertx,
            getRootId()
        );
        
        // ... rest of start method ...
    }
    
    // Replace method bodies with delegation:
    public Future<FileRecord> startDownload(Long chatId, Long messageId, Integer fileId) {
        return downloadService.startDownload(chatId, messageId, fileId);
    }
    
    public Future<Void> cancelDownload(Integer fileId) {
        return downloadService.cancelDownload(fileId);
    }
    
    public Future<Void> togglePauseDownload(Integer fileId) {
        return downloadService.togglePauseDownload(fileId);
    }
    
    public Future<Boolean> downloadThumbnail(Long chatId, Long messageId, FileRecord thumbnailRecord) {
        return downloadService.downloadThumbnail(chatId, messageId, thumbnailRecord);
    }
    
    // syncFileDownloadStatus is private - no delegation needed, can be removed
}
```

**Quality Gate 2.8**:
```bash
# Compile
docker build -t test .
# Expected: BUILD SUCCESSFUL

# Check delegation
grep "downloadService\." api/src/main/java/telegram/files/core/TelegramVerticle.java | wc -l
# Expected: 4+ (one per delegated method)

# Check TelegramVerticle size
wc -l api/src/main/java/telegram/files/core/TelegramVerticle.java
# Expected: 900-1000 lines (reduced from 1,252)
```

**COMMIT**: `git commit -m "refactor: delegate download operations to FileDownloadService"`

---

**Step 2.9: Remove Old Methods from TelegramVerticle** (15 minutes)

**CRITICAL**: Only do this AFTER delegation works!

1. Remove old `startDownload()` implementation (keep delegation)
2. Remove old `cancelDownload()` implementation (keep delegation)
3. Remove old `togglePauseDownload()` implementation (keep delegation)
4. Remove old `downloadThumbnail()` implementation (keep delegation)
5. Remove old `syncFileDownloadStatus()` (no longer needed in TelegramVerticle)

**Quality Gate 2.9**:
```bash
docker build -t test .
# Expected: BUILD SUCCESSFUL

wc -l api/src/main/java/telegram/files/core/TelegramVerticle.java
# Expected: 900-1000 lines
```

**COMMIT**: `git commit -m "refactor: remove old download methods from TelegramVerticle"`

---

**Step 2.10: Functional Test** (30 minutes)

**MANDATORY TESTING**:

```bash
# Build and deploy
docker build -t telegram-files:test-service-extraction .
docker tag telegram-files:test-service-extraction telegram-files:custom
cd /Users/fabian/projects/music-processor/telegram-postproc
./stackctl restart

# Wait for healthy
sleep 30
docker ps --filter "name=telegram-files" --format "{{.Status}}"
# Expected: "Up X seconds (healthy)"

# Test download functionality
# 1. Open http://localhost:8979
# 2. Navigate to a chat
# 3. Click on a file
# 4. Click "Start Download"
# Expected: Download starts, no errors

# Check logs
docker logs telegram-files | grep -E "ERROR|Exception" | tail -10
# Expected: No unexpected errors

# Test cancel
# 1. Start a download
# 2. Click "Cancel"
# Expected: Download cancels

# Test pause/resume
# 1. Start a download
# 2. Click "Pause"
# Expected: Download pauses
# 3. Click "Resume"
# Expected: Download resumes
```

**If ANY test fails**:
- STOP immediately
- Rollback: `git reset --hard HEAD~5` (back to before extraction)
- Investigate what went wrong
- Fix and try again

**If all tests pass**:
- COMMIT: `git commit -m "test: verified FileDownloadService extraction"`
- Proceed to Task 3

---

### **Task 3: Initialize ServiceContext** (30 minutes)

**Status**: Not started  
**Priority**: P0 - Required for Task 2  
**Risk**: Low  
**Estimated Time**: 30 minutes

#### **Current State**

```java
// DataVerticle.java line 44
public static ServiceContext serviceContext; // Declared but NEVER initialized!
```

This will cause NullPointerException when FileDownloadService tries to use it.

#### **Implementation**

**Step 3.1: Add Initialization** (15 minutes)

**File**: `api/src/main/java/telegram/files/DataVerticle.java`

**Location**: In `start(Promise<Void> stopPromise)` method, AFTER repository initialization

**Find this code** (around line 70):
```java
settingRepository = new SettingRepositoryImpl(pool);
telegramRepository = new TelegramRepositoryImpl(pool);
fileRepository = new FileRepositoryImpl(pool);
statisticRepository = new StatisticRepositoryImpl(pool);
```

**Add IMMEDIATELY after**:
```java
// Initialize ServiceContext for dependency injection
serviceContext = new ServiceContext(
    fileRepository,
    telegramRepository,
    settingRepository,
    statisticRepository
);
log.info("ServiceContext initialized successfully");
```

**Quality Gate 3.1**:
```bash
# Compile
docker build -t test .
# Expected: BUILD SUCCESSFUL

# Verify initialization exists
grep "serviceContext = new ServiceContext" api/src/main/java/telegram/files/DataVerticle.java
# Expected: 1 match

# Verify it's after repository creation
grep -A 3 "statisticRepository = new" api/src/main/java/telegram/files/DataVerticle.java | grep "serviceContext"
# Expected: Match found
```

**COMMIT**: `git commit -m "feat: initialize ServiceContext in DataVerticle.start()"`

---

**Step 3.2: Functional Test** (15 minutes)

```bash
# Deploy
docker build -t test .
docker tag test telegram-files:custom
cd /Users/fabian/projects/music-processor/telegram-postproc
./stackctl restart

# Check logs for initialization message
docker logs telegram-files | grep "ServiceContext initialized"
# Expected: "ServiceContext initialized successfully"

# Check for NullPointerException
docker logs telegram-files | grep "NullPointerException"
# Expected: No matches

# Try using a feature that uses ServiceContext
# (If FileDownloadService is already integrated)
# Start a download
# Expected: Works without NPE
```

**COMMIT**: `git commit -m "test: verified ServiceContext initialization"`

---

### **Task 4: Complete DI Migration in Download Package** (4-6 hours)

**Status**: Not started  
**Priority**: P1 - Important for consistency  
**Risk**: Medium  
**Estimated Time**: 4-6 hours

#### **Current State**

**DI Status**:
- ✅ ServiceContext created
- ✅ ServiceContext initialized (after Task 3)
- ✅ FileDownloadService uses ServiceContext (after Task 2)
- ❌ AutoDownloadVerticle: Still uses static DataVerticle.*
- ❌ DownloadQueueService: Still uses static DataVerticle.*
- ❌ HistoryDiscoveryService: Still uses static DataVerticle.*

#### **Why This Matters**

**Problem**: Inconsistent dependency access
- FileDownloadService: `context.fileRepository()` ✅
- AutoDownloadVerticle: `DataVerticle.fileRepository` ❌

**Result**: Half the code is testable, half isn't.

**Goal**: All services use ServiceContext, none use static access.

---

**Step 4.1: Convert DownloadQueueService** (1 hour)

**Current** (static utility):
```java
public class DownloadQueueService {
    public static Future<Integer> queueFilesForDownload(...) {
        return DataVerticle.fileRepository.queueFilesForDownload(...);
    }
}
```

**Target** (instance service with DI):
```java
public class DownloadQueueService {
    private final ServiceContext context;
    
    public DownloadQueueService(ServiceContext context) {
        this.context = context;
    }
    
    public Future<Integer> queueFilesForDownload(...) {
        return context.fileRepository().queueFilesForDownload(...);
    }
    
    // All methods become instance methods
    // All DataVerticle.* become context.*()
}
```

**Process**:
1. Add ServiceContext field
2. Add constructor
3. Remove `static` from all methods
4. Replace `DataVerticle.fileRepository` → `context.fileRepository()`
5. Replace `DataVerticle.settingRepository` → `context.settingRepository()`

**Quality Gate 4.1**:
```bash
docker build -t test .
# Expected: BUILD SUCCESSFUL

# Verify no static methods
grep "public static" api/src/main/java/telegram/files/download/DownloadQueueService.java
# Expected: No matches (all instance methods now)

# Verify uses context
grep "context\.fileRepository()" api/src/main/java/telegram/files/download/DownloadQueueService.java | wc -l
# Expected: 3+ matches
```

**COMMIT**: `git commit -m "refactor: convert DownloadQueueService to instance service with DI"`

---

**Step 4.2: Update AutoDownloadVerticle to Use Service** (1.5 hours)

**Current**: AutoDownloadVerticle calls `DownloadQueueService.queueFilesForDownload(...)` as static

**Target**: Create instance and use it

**File**: `download/AutoDownloadVerticle.java`

**Add field**:
```java
public class AutoDownloadVerticle extends AbstractVerticle {
    
    // Add these fields
    private ServiceContext context;
    private DownloadQueueService queueService;
    
    @Override
    public void start(Promise<Void> startPromise) {
        // Initialize
        this.context = ServiceContext.fromDataVerticle();
        this.queueService = new DownloadQueueService(context);
        
        // ... rest of start method ...
    }
}
```

**Replace all static calls**:
```bash
# Find all calls
grep -n "DownloadQueueService\." api/src/main/java/telegram/files/download/AutoDownloadVerticle.java

# Replace each:
# OLD: DownloadQueueService.queueFilesForDownload(...)
# NEW: queueService.queueFilesForDownload(...)
```

**CRITICAL**: Do NOT change the logic, only change how the service is accessed.

**Quality Gate 4.2**:
```bash
docker build -t test .
# Expected: BUILD SUCCESSFUL

# Verify no static calls to DownloadQueueService
grep "DownloadQueueService\." api/src/main/java/telegram/files/download/AutoDownloadVerticle.java | wc -l
# Expected: 0

# Verify instance calls
grep "queueService\." api/src/main/java/telegram/files/download/AutoDownloadVerticle.java | wc -l
# Expected: 3+ matches
```

**COMMIT**: `git commit -m "refactor: use DownloadQueueService instance in AutoDownloadVerticle"`

---

**Step 4.3: Convert HistoryDiscoveryService** (1.5 hours)

**Same pattern as DownloadQueueService**:
1. Remove `static` from all methods
2. Add ServiceContext field and constructor
3. Replace DataVerticle.* with context.*()

**Quality Gate 4.3**:
```bash
docker build -t test .
# Expected: BUILD SUCCESSFUL
```

**COMMIT**: `git commit -m "refactor: convert HistoryDiscoveryService to instance service"`

---

**Step 4.4: Update Callers of HistoryDiscoveryService** (1 hour)

**Files that call HistoryDiscoveryService**:
- AutoDownloadVerticle.java

**Update to create instance and use it**:
```java
private HistoryDiscoveryService discoveryService;

@Override
public void start(...) {
    this.discoveryService = new HistoryDiscoveryService(context);
}

// Replace static calls with instance calls
```

**Quality Gate 4.4**:
```bash
docker build -t test .
# Expected: BUILD SUCCESSFUL
```

**COMMIT**: `git commit -m "refactor: use HistoryDiscoveryService instance in AutoDownloadVerticle"`

---

**Step 4.5: Functional Test of Complete DI** (1 hour)

**MANDATORY TESTING**:

```bash
# Deploy
docker build -t telegram-files:di-complete .
docker tag telegram-files:di-complete telegram-files:custom
cd /Users/fabian/projects/music-processor/telegram-postproc
./stackctl restart

# Test ALL download features:

# 1. Manual download
#    - Start download via UI
#    - Expected: Works

# 2. Auto download
#    - Enable automation
#    - Expected: Files queue and download

# 3. History download
#    - Enable "Download History"
#    - Expected: Historical files queue

# 4. Download oldest first
#    - Enable "Download oldest first"
#    - Expected: Files download in correct order

# 5. History cutoff
#    - Set history cutoff date
#    - Expected: Only recent files download

# 6. Queue persistence
#    - Queue some files
#    - Restart: docker-compose restart telegram-files
#    - Expected: Queue persists

# Check logs
docker logs telegram-files | grep -E "ERROR|Exception|NullPointer" | tail -20
# Expected: No errors related to ServiceContext or DI
```

**If ANY test fails**:
- Rollback to before DI migration
- Investigate
- Fix
- Try again

**COMMIT**: `git commit -m "test: verified complete DI migration works"`

---

## Critical Success Criteria

### **Before Considering Task Complete**

**Compilation**:
- [ ] `docker build` succeeds with 0 errors
- [ ] No warnings about missing classes
- [ ] No ClassNotFoundException at runtime

**ServiceContext**:
- [ ] ServiceContext.java exists
- [ ] Initialized in DataVerticle.start()
- [ ] No NullPointerException in logs

**Service Extraction**:
- [ ] FileDownloadService exists (~300 LOC)
- [ ] TelegramVerticle reduced (~950 LOC)
- [ ] All 5 methods delegated

**DI Migration**:
- [ ] DownloadQueueService uses ServiceContext
- [ ] HistoryDiscoveryService uses ServiceContext
- [ ] AutoDownloadVerticle uses service instances
- [ ] Zero static DataVerticle.* calls in download package

**Functional Testing**:
- [ ] Manual download works
- [ ] Auto download works
- [ ] History download works
- [ ] Queue persistence works
- [ ] All features from before refactoring still work

---

## Verification Checklist

### **Static Access Audit**

```bash
# Check download package has no static calls
grep "DataVerticle\." api/src/main/java/telegram/files/download/*.java | wc -l
# Expected: 0

# Check services use context
grep "context\.fileRepository()\|context\.settingRepository()" api/src/main/java/telegram/files/download/*.java | wc -l
# Expected: 30+ (all repository access via context)
```

### **Size Verification**

```bash
# TelegramVerticle should be smaller
wc -l api/src/main/java/telegram/files/core/TelegramVerticle.java
# Expected: 900-1000 lines (was 1,252)

# FileDownloadService should exist
wc -l api/src/main/java/telegram/files/download/FileDownloadService.java
# Expected: 300-400 lines

# Total LOC should be similar (just reorganized)
wc -l api/src/main/java/telegram/files/core/TelegramVerticle.java \
      api/src/main/java/telegram/files/download/FileDownloadService.java
# Expected: Total ~1,250-1,400 lines (similar to original)
```

### **Delegation Verification**

```bash
# TelegramVerticle should delegate
grep "return downloadService\." api/src/main/java/telegram/files/core/TelegramVerticle.java | wc -l
# Expected: 4+ (one per delegated method)

# Old implementations should be removed
grep -A 50 "public Future<FileRecord> startDownload" api/src/main/java/telegram/files/core/TelegramVerticle.java | grep "AddFileToDownloads"
# Expected: No match (logic moved to service)
```

---

## Common Pitfalls & How to Avoid Them

### **Pitfall 1: Copying Code Introduces Syntax Errors**

**Lesson from first attempt**: Async chains are complex, easy to break

**Prevention**:
1. Copy ENTIRE method from opening `{` to closing `}`
2. Don't try to "improve" code while copying
3. Use IDE copy-paste, not manual typing
4. Compile immediately after each method copy
5. If compilation fails, revert and copy again

**Recovery**:
```bash
# If you introduce syntax error:
git diff api/src/main/java/telegram/files/download/FileDownloadService.java
# Review what you copied
# Compare with original in TelegramVerticle
# Fix the specific error
# Or: git checkout -- FileDownloadService.java (revert and try again)
```

---

### **Pitfall 2: Forgetting to Update References**

**Lesson**: DataVerticle.fileRepository must become context.fileRepository()

**Prevention**:
1. After copying method, search for "DataVerticle"
2. Replace ALL occurrences with context.*()
3. Compile to verify

**Checklist after copying**:
```bash
# In the copied method, check:
grep "DataVerticle\." FileDownloadService.java
# Expected: No matches

grep "this\.telegramRecord" FileDownloadService.java
# Expected: No matches (should be this.telegramId)

grep "getRootId()" FileDownloadService.java
# Expected: No matches (should be this.rootId)
```

---

### **Pitfall 3: Breaking Async Chains**

**Lesson from first attempt**: Removing Future.await() broke nested callbacks

**Prevention**:
1. DO NOT remove Future.await() during this refactoring
2. Keep the code EXACTLY as it is
3. Just move it to the new location
4. Async refactoring is a SEPARATE task (not part of Phase 1)

**Rule**: If you see `Future.await()` in original, KEEP IT in the copy.

---

### **Pitfall 4: Not Testing Incrementally**

**Lesson**: Testing only at the end meant 28 errors accumulated

**Prevention**:
1. Test after EVERY method copy
2. Test after EVERY service conversion
3. Test after EVERY caller update

**Testing Template**:
```bash
# After each change:
echo "Testing after: <describe change>"
docker build -t test .
if [ $? -eq 0 ]; then
    echo "✅ Compilation passed"
    git add -A
    git commit -m "Incremental: <describe change>"
else
    echo "❌ Compilation failed"
    echo "FIX THIS BEFORE PROCEEDING"
    exit 1
fi
```

---

### **Pitfall 5: Forgetting to Initialize Services**

**Lesson**: ServiceContext was created but not initialized

**Prevention**:
After creating ANY service:
1. Find where it should be initialized
2. Add initialization code
3. Test that initialization happens
4. Verify no NPE

**Checklist**:
- [ ] Service has constructor with dependencies
- [ ] Service is instantiated in verticle.start()
- [ ] Service instance is stored in field
- [ ] Service is used (not static class)

---

## Quality Gates - Non-Negotiable

### **Gate 1: Compilation (After Every Change)**

```bash
docker build -t test .
```
**PASS**: BUILD SUCCESSFUL  
**FAIL**: Any error → STOP, FIX, RETEST

**DO NOT PROCEED WITH ERRORS**

---

### **Gate 2: No Static Access in Services**

```bash
grep "DataVerticle\." api/src/main/java/telegram/files/download/FileDownloadService.java | wc -l
```
**PASS**: 0  
**FAIL**: > 0 → STOP, REPLACE WITH CONTEXT, RETEST

---

### **Gate 3: Service Initialization**

```bash
# After creating service, verify it's initialized
grep "= new FileDownloadService" api/src/main/java/telegram/files/core/TelegramVerticle.java
```
**PASS**: Found  
**FAIL**: Not found → STOP, ADD INITIALIZATION, RETEST

---

### **Gate 4: Functional Equivalence**

```bash
# After extraction, test the feature
# Start a download via UI
```
**PASS**: Works identically to before  
**FAIL**: Broken or different behavior → STOP, FIX, RETEST

---

### **Gate 5: No Errors in Logs**

```bash
docker logs telegram-files | grep -E "ERROR|Exception" | tail -10
```
**PASS**: Only expected errors (like "already downloaded")  
**FAIL**: New errors, NPE, ClassNotFound → STOP, FIX, RETEST

---

## Rollback Procedures

### **If Task 2 (Service Extraction) Fails**

```bash
# Rollback to after Task 1
git log --oneline | grep "Package reorganization"
# Note the commit hash
git reset --hard <hash>

# Rebuild and redeploy
docker build -t telegram-files:rollback .
docker tag telegram-files:rollback telegram-files:custom
cd /Users/fabian/projects/music-processor/telegram-postproc
./stackctl restart
```

### **If Task 4 (DI Migration) Fails**

```bash
# Rollback to after Task 2
git log --oneline | grep "FileDownloadService"
# Note the last successful commit
git reset --hard <hash>

# Rebuild and redeploy
```

### **If Everything Breaks**

```bash
# Nuclear option: Rollback to main
git checkout main
docker build -t telegram-files:safe .
docker tag telegram-files:safe telegram-files:custom
./stackctl restart

# You still have all the work in refactor/phase1-clean branch
# Can try again later
```

---

## Testing Protocol - Comprehensive

### **After Each Task**

**Quick Test** (2 minutes):
```bash
# 1. Compilation
docker build -t test .

# 2. Deployment
docker tag test telegram-files:custom && ./stackctl restart

# 3. Basic check
curl http://localhost:8979/ | grep "Telegram Files"

# 4. Logs
docker logs telegram-files | tail -50 | grep -E "ERROR|started"
```

**If quick test passes**: Proceed  
**If quick test fails**: Stop and debug

---

### **After ALL Tasks Complete**

**Full Integration Test** (30 minutes):

```
Feature Checklist:
1. [ ] Login works
2. [ ] Browse chats works
3. [ ] View files in chat works
4. [ ] Manual download works
5. [ ] Cancel download works
6. [ ] Pause/resume works
7. [ ] Auto download works
8. [ ] History download works
9. [ ] History cutoff works
10. [ ] Download oldest first works
11. [ ] Queue persistence works
12. [ ] Statistics badge works
13. [ ] Automation settings work
14. [ ] Transfer works

If ALL 14 checks pass: Phase 1 is complete ✅
If ANY check fails: Investigate and fix before deploying to production
```

---

## Timeline with Quality Gates

**Realistic Timeline** (includes testing):

| Task | Coding | Testing | Total | Cumulative |
|------|--------|---------|-------|------------|
| 2.1 Service skeleton | 30min | 5min | 35min | 35min |
| 2.2 Copy startDownload | 1h | 10min | 1h10min | 1h45min |
| 2.3 Copy cancelDownload | 30min | 5min | 35min | 2h20min |
| 2.4 Copy togglePause | 30min | 5min | 35min | 2h55min |
| 2.5 Copy downloadThumbnail | 30min | 5min | 35min | 3h30min |
| 2.6 Copy syncStatus | 1h | 10min | 1h10min | 4h40min |
| 2.7 Copy helpers | 30min | 5min | 35min | 5h15min |
| 2.8 Delegation | 1h | 10min | 1h10min | 6h25min |
| 2.9 Remove old code | 15min | 5min | 20min | 6h45min |
| 2.10 Functional test | 0 | 30min | 30min | 7h15min |
| 3.1 Init ServiceContext | 15min | 5min | 20min | 7h35min |
| 3.2 Test initialization | 0 | 15min | 15min | 7h50min |
| 4.1 Convert DownloadQueue | 1h | 10min | 1h10min | 9h |
| 4.2 Update callers | 1.5h | 15min | 1h45min | 10h45min |
| 4.3 Convert HistoryDiscovery | 1.5h | 10min | 1h40min | 12h25min |
| 4.4 Update callers | 1h | 10min | 1h10min | 13h35min |
| 4.5 Full integration test | 0 | 1h | 1h | 14h35min |

**Total**: ~15 hours (2 days)

**CRITICAL**: The 1/3 of time is TESTING. Do not skip it.

---

## Success Criteria - Final Checklist

Phase 1 remaining work is complete when:

### **Code Quality** ✅
- [ ] Zero compilation errors
- [ ] Zero compilation warnings
- [ ] No TODO comments left
- [ ] Code formatted consistently
- [ ] All imports organized

### **Architecture** ✅
- [ ] FileDownloadService exists (~300 LOC)
- [ ] TelegramVerticle reduced (< 1,000 LOC)
- [ ] ServiceContext initialized
- [ ] All download services use DI
- [ ] Zero static DataVerticle.* in download package

### **Testing** ✅
- [ ] Compilation successful
- [ ] Application starts
- [ ] All 14 features work
- [ ] No regressions
- [ ] Performance stable
- [ ] No memory leaks

### **Documentation** ✅
- [ ] All changes committed
- [ ] Commit messages clear
- [ ] No uncommitted changes
- [ ] Branch pushed to GitHub

---

## LLM Instructions for Remaining Work

### **Prompt Template**

```
I have a telegram-files fork with Phase 1 refactoring partially complete.

**Current State**:
- Branch: refactor/phase1-clean
- Package reorganization: COMPLETE ✅
- Utilities created: COMPLETE ✅
- Service extraction: NOT STARTED
- DI migration: NOT STARTED

**Your Task**:
Complete Task 2 (FileDownloadService extraction) and Task 4 (DI migration) following the detailed plan in:
`refactoring/PHASE1_REMAINING_WORK.md`

**CRITICAL RULES YOU MUST FOLLOW**:

1. **Compile After Every Change**
   - After copying each method: docker build -t test .
   - If compilation fails: STOP, FIX, RETEST
   - NEVER proceed with compilation errors

2. **One Method at a Time**
   - Copy startDownload() → compile → commit
   - Copy cancelDownload() → compile → commit
   - Do NOT copy all methods then try to compile

3. **No Refactoring While Copying**
   - Copy EXACTLY as-is from TelegramVerticle
   - Do NOT "improve" the code
   - Do NOT remove Future.await()
   - Do NOT change logic

4. **Update References Immediately**
   - After copying: DataVerticle → context
   - Test immediately
   - Commit immediately

5. **Test Functionality**
   - After service extraction: test downloads work
   - After DI migration: test all features work
   - Do NOT skip functional testing

**Start with**:
Read `refactoring/PHASE1_REMAINING_WORK.md` Task 2, Step 2.1

**Report after each step**:
"Step 2.X complete, compilation successful, committed as <hash>"

**If blocked**:
Stop immediately, describe the blocker, ask for help
```

---

## What Makes This Different from First Attempt

### **First Attempt** ❌
- Changed 39 files at once
- No incremental compilation
- Aggressive async refactoring
- Quality gates skipped
- Result: 28 compilation errors

### **This Plan** ✅
- Change 1 file at a time
- Compile after EVERY change
- No async changes (keep as-is)
- Quality gates mandatory
- Result: Working code at each step

---

## The ONE Rule to Rule Them All

```
┌─────────────────────────────────────────────────┐
│                                                 │
│   IF IT DOESN'T COMPILE, NOTHING ELSE MATTERS   │
│                                                 │
│   ALWAYS COMPILE BEFORE MOVING ON               │
│                                                 │
└─────────────────────────────────────────────────┘
```

**This is the lesson from the first attempt.**

Compilation errors are cheap to fix when you just made the change.  
Compilation errors are expensive to fix when you have 28 of them.

**Test. Incrementally. Always.**

---

## Estimated Completion

**Conservative Estimate** (with testing):
- Day 1: Task 2 Steps 2.1-2.5 (5 hours)
- Day 2: Task 2 Steps 2.6-2.10 + Task 3 (8 hours)
- Day 3: Task 4 (7 hours)

**Total**: 3 days, 20 hours

**Aggressive Estimate** (if everything goes smoothly):
- Day 1: Task 2 complete (7 hours)
- Day 2: Task 3 + Task 4 (8 hours)

**Total**: 2 days, 15 hours

**Plan for conservative, hope for aggressive.**

---

## This Document Is Your Safety Net

Use it as:
1. **Step-by-step guide** - Follow tasks in exact order
2. **Quality checklist** - Verify gates after each step
3. **Error prevention** - Learn from first attempt's mistakes
4. **Recovery guide** - Rollback procedures if needed
5. **Success criteria** - Know when you're done

**The first refactoring attempt taught us what NOT to do.**  
**This plan incorporates those lessons.**

**Follow it carefully and Phase 1 will succeed.** 🎯

