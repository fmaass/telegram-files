# Phase 1 Remaining Work - Complete Implementation Handbook

**Purpose**: Foolproof guide for completing Tasks 2-4 with ZERO mistakes  
**Audience**: AI Assistant or Human Developer  
**Prerequisite**: refactor/phase1-clean branch deployed and working  
**Estimated Time**: 12-16 hours

---

## ⚠️ CRITICAL: Read This First

### **Why This Handbook Exists**

The first refactoring attempt failed with 28 compilation errors because:
1. Multiple files changed before compiling
2. Quality gates were skipped
3. Code was modified while being copied
4. No incremental testing

**This handbook prevents ALL of those mistakes.**

### **How to Use This Handbook**

**DO**:
- ✅ Follow EVERY step in EXACT order
- ✅ Run EVERY command shown
- ✅ Check EVERY expected output
- ✅ Stop if ANY check fails

**DON'T**:
- ❌ Skip ahead
- ❌ Skip compilation checks
- ❌ Change multiple files before testing
- ❌ Modify code while copying it

**If in doubt**: STOP and ask for clarification

---

## Prerequisites Verification

**Before starting, verify**:

```bash
# 1. On correct branch
cd /Users/fabian/projects/telegram-files
git branch --show-current
# EXPECTED OUTPUT: refactor/phase1-clean
# IF DIFFERENT: git checkout refactor/phase1-clean

# 2. Working tree is clean
git status
# EXPECTED OUTPUT: "nothing to commit, working tree clean"
# IF DIFFERENT: git add -A && git commit -m "wip: save state"

# 3. Code compiles
docker build -t verify-prereq . 2>&1 | grep "BUILD\|error:"
# EXPECTED OUTPUT: Contains "DONE" and NO "error:"
# IF ERRORS: STOP - fix current branch first

# 4. Application is healthy
docker ps --filter "name=telegram-files" --format "{{.Status}}"
# EXPECTED OUTPUT: Contains "healthy"
# IF NOT: STOP - fix deployment first
```

**If ALL 4 checks pass**: ✅ Proceed to Task 2  
**If ANY check fails**: ❌ STOP - Fix before proceeding

---

## Task 2: Extract FileDownloadService

**Estimated Time**: 6-8 hours  
**Sub-steps**: 10  
**Critical**: Follow EXACT order

---

### **Step 2.1: Create FileDownloadService Skeleton** (15 minutes)

**Objective**: Create empty service file that compiles

**Command 1**: Create the file
```bash
cat > api/src/main/java/telegram/files/download/FileDownloadService.java << 'JAVA'
package telegram.files.download;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import io.vertx.core.VertxException;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
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
    
    // Methods will be added in subsequent steps
}
JAVA
```

**Expected**: File created

**Verification 2.1.1**: Check file exists
```bash
test -f api/src/main/java/telegram/files/download/FileDownloadService.java && echo "✅ File created" || echo "❌ File missing"
# EXPECTED: ✅ File created
# IF ❌: Re-run command 1
```

**Verification 2.1.2**: Check compilation
```bash
docker build -t test-step2.1 . 2>&1 | tail -5
# EXPECTED: Contains "DONE" and "unpacking"
# NO "error:" or "BUILD FAILED"
# IF ERRORS: Show them and STOP
```

**Verification 2.1.3**: Count lines
```bash
wc -l api/src/main/java/telegram/files/download/FileDownloadService.java
# EXPECTED: Approximately 60-70 lines
# IF DIFFERENT: Check file content
```

**If ALL verifications pass**: ✅ Commit
```bash
git add api/src/main/java/telegram/files/download/FileDownloadService.java
git commit -m "refactor: create FileDownloadService skeleton"
git log --oneline -1
# Note the commit hash
```

**If ANY verification fails**: ❌ STOP, fix, retest

---

### **Step 2.2: Copy startDownload() Method** (1.5 hours)

**Objective**: Copy exact implementation of startDownload from TelegramVerticle

**CRITICAL**: This is the most important method. Copy EXACTLY.

**Command 2.2.1**: Find the method in original location
```bash
grep -n "public Future<FileRecord> startDownload" api/src/main/java/telegram/files/core/TelegramVerticle.java
# EXPECTED OUTPUT: Line number (e.g., "305:")
# Note this line number: START_LINE=<number>
```

**Command 2.2.2**: Find where method ends
```bash
# The method ends at the closing brace that matches the opening
# Approximately 90-100 lines after START_LINE
# We'll extract lines START_LINE to START_LINE+95
```

**Command 2.2.3**: Extract the method
```bash
# Replace <START_LINE> with actual line number from 2.2.1
START_LINE=<INSERT_LINE_NUMBER_HERE>
END_LINE=$((START_LINE + 95))

sed -n "${START_LINE},${END_LINE}p" api/src/main/java/telegram/files/core/TelegramVerticle.java > /tmp/startDownload_original.java

# View what was extracted
cat /tmp/startDownload_original.java | head -20
cat /tmp/startDownload_original.java | tail -10
```

**Verification 2.2.3**: Check extraction looks correct
```bash
# Should start with:
head -3 /tmp/startDownload_original.java
# EXPECTED: 
#   public Future<FileRecord> startDownload(Long chatId, Long messageId, Integer fileId) {
#       return ...
# IF DIFFERENT: Adjust START_LINE and re-extract

# Should end with closing brace:
tail -3 /tmp/startDownload_original.java | grep "^    }$"
# EXPECTED: Match found
# IF NOT FOUND: Adjust END_LINE and re-extract
```

**Command 2.2.4**: Update references in extracted code
```bash
# Replace DataVerticle. with context.
sed 's/DataVerticle\.fileRepository/context.fileRepository()/g' /tmp/startDownload_original.java | \
sed 's/DataVerticle\.settingRepository/context.settingRepository()/g' | \
sed 's/this\.telegramRecord\.id()/this.telegramId/g' | \
sed 's/getRootId()/this.rootId/g' > /tmp/startDownload_updated.java

# Verify replacements
diff /tmp/startDownload_original.java /tmp/startDownload_updated.java | head -20
# EXPECTED: See DataVerticle → context changes
```

**Command 2.2.5**: Insert into FileDownloadService
```bash
# Find insertion point (after constructor, before closing brace)
LINE_NUM=$(grep -n "// Methods will be added" api/src/main/java/telegram/files/download/FileDownloadService.java | cut -d: -f1)

# Create temp file with method inserted
head -n $((LINE_NUM - 1)) api/src/main/java/telegram/files/download/FileDownloadService.java > /tmp/service_temp.java
cat /tmp/startDownload_updated.java >> /tmp/service_temp.java
tail -n +$((LINE_NUM + 1)) api/src/main/java/telegram/files/download/FileDownloadService.java >> /tmp/service_temp.java

# Replace original
mv /tmp/service_temp.java api/src/main/java/telegram/files/download/FileDownloadService.java
```

**Verification 2.2.5**: Check method was added
```bash
grep -c "public Future<FileRecord> startDownload" api/src/main/java/telegram/files/download/FileDownloadService.java
# EXPECTED: 1
# IF 0: Method not added - repeat Command 2.2.5
# IF 2+: Duplicate - check file
```

**CRITICAL Verification 2.2.6**: Compile immediately
```bash
docker build -t test-step2.2 . 2>&1 > /tmp/build2.2.log
BUILD_STATUS=$?

if [ $BUILD_STATUS -eq 0 ]; then
    echo "✅ COMPILATION PASSED"
else
    echo "❌ COMPILATION FAILED"
    echo "Errors:"
    grep "error:" /tmp/build2.2.log | head -10
    echo ""
    echo "STOP: Fix these errors before proceeding"
    echo "Common issues:"
    echo "- Missing imports"
    echo "- Incorrect reference updates"
    echo "- Method not closed properly"
    exit 1
fi
```

**If compilation passes**: ✅ Commit
```bash
git add api/src/main/java/telegram/files/download/FileDownloadService.java
git commit -m "refactor: copy startDownload() to FileDownloadService"
echo "Step 2.2 complete - Commit: $(git log --oneline -1)"
```

**If compilation fails**: ❌ STOP
1. Read error messages carefully
2. Check if imports are missing
3. Check if references were updated correctly
4. Fix the specific error
5. Retest compilation
6. Do NOT proceed until it compiles

---

### **Step 2.3: Copy cancelDownload() Method** (45 minutes)

**Objective**: Copy exact implementation of cancelDownload

**Process**: IDENTICAL to Step 2.2, but for cancelDownload

**Command 2.3.1**: Find method
```bash
grep -n "public Future<Void> cancelDownload" api/src/main/java/telegram/files/core/TelegramVerticle.java
# Note START_LINE
```

**Command 2.3.2**: Extract method (approximately 25 lines)
```bash
START_LINE=<INSERT_NUMBER>
END_LINE=$((START_LINE + 25))
sed -n "${START_LINE},${END_LINE}p" api/src/main/java/telegram/files/core/TelegramVerticle.java > /tmp/cancelDownload_original.java
```

**Command 2.3.3**: Update references
```bash
sed 's/DataVerticle\.fileRepository/context.fileRepository()/g' /tmp/cancelDownload_original.java > /tmp/cancelDownload_updated.java
```

**Command 2.3.4**: Insert into FileDownloadService
```bash
# Append before final closing brace
sed -i '' '$i\
\
' api/src/main/java/telegram/files/download/FileDownloadService.java

# Insert method (before last line)
LINE_COUNT=$(wc -l < api/src/main/java/telegram/files/download/FileDownloadService.java)
head -n $((LINE_COUNT - 1)) api/src/main/java/telegram/files/download/FileDownloadService.java > /tmp/service.java
cat /tmp/cancelDownload_updated.java >> /tmp/service.java
tail -n 1 api/src/main/java/telegram/files/download/FileDownloadService.java >> /tmp/service.java
mv /tmp/service.java api/src/main/java/telegram/files/download/FileDownloadService.java
```

**MANDATORY Verification 2.3**: Compile
```bash
docker build -t test-step2.3 . 2>&1 | grep "BUILD\|error:"
# EXPECTED: No "error:", contains "DONE"
# IF ERRORS: STOP, fix, retest
```

**Commit**:
```bash
git add -A
git commit -m "refactor: copy cancelDownload() to FileDownloadService"
```

---

### **Step 2.4: Copy togglePauseDownload() Method** (45 minutes)

**Process**: Identical to Steps 2.2 and 2.3

**Key Differences**:
- Method name: `togglePauseDownload`
- Approximate length: 20 lines
- Return type: `Future<Void>`

**Template**:
```bash
# 1. Find method
grep -n "public Future<Void> togglePauseDownload" api/src/main/java/telegram/files/core/TelegramVerticle.java

# 2. Extract (START_LINE to START_LINE+20)
START_LINE=<number>
END_LINE=$((START_LINE + 20))
sed -n "${START_LINE},${END_LINE}p" api/src/main/java/telegram/files/core/TelegramVerticle.java > /tmp/togglePause_original.java

# 3. Update references
sed 's/DataVerticle\.fileRepository/context.fileRepository()/g' /tmp/togglePause_original.java > /tmp/togglePause_updated.java

# 4. Insert into FileDownloadService
# (same pattern as 2.3.4)

# 5. COMPILE
docker build -t test-step2.4 . 2>&1 | grep "error:" | wc -l
# EXPECTED: 0
# IF > 0: STOP and fix

# 6. Commit
git add -A && git commit -m "refactor: copy togglePauseDownload() to FileDownloadService"
```

---

### **Step 2.5: Copy downloadThumbnail() Method** (45 minutes)

**Method signature**: `public Future<Boolean> downloadThumbnail(Long chatId, Long messageId, FileRecord thumbnailRecord)`

**Approximate length**: 30 lines

**Follow same pattern as Steps 2.2-2.4**

**MANDATORY**: Compile and commit after adding

---

### **Step 2.6: Copy syncFileDownloadStatus() Method** (2 hours)

**⚠️ WARNING**: This is the MOST COMPLEX method (~60 lines with nested async chains)

**CRITICAL INSTRUCTIONS**:

1. **Find method start**:
```bash
grep -n "private Future<Void> syncFileDownloadStatus" api/src/main/java/telegram/files/core/TelegramVerticle.java
# Note START_LINE
```

2. **Find method end** (THIS IS CRITICAL):
```bash
START_LINE=<number>
# The method likely ends around START_LINE + 60
# But we need to find the EXACT closing brace

# Look for the method structure
sed -n "${START_LINE},$((START_LINE + 70))p" api/src/main/java/telegram/files/core/TelegramVerticle.java | cat -n

# Find where the method closes (look for closing brace at same indentation as "private")
# Count braces to be sure:
sed -n "${START_LINE},$((START_LINE + 70))p" api/src/main/java/telegram/files/core/TelegramVerticle.java | \
  awk '{
    for(i=1;i<=length($0);i++) {
      c=substr($0,i,1);
      if(c=="{") open++;
      if(c=="}") close++;
    }
    print NR": "{$0} " (open="open", close="close", balance="(open-close)")";
  }'
# Find line where balance returns to 0 - that's the end
```

3. **Extract with EXACT line range**:
```bash
START_LINE=<number>
END_LINE=<number>  # The line where balance=0

sed -n "${START_LINE},${END_LINE}p" api/src/main/java/telegram/files/core/TelegramVerticle.java > /tmp/syncStatus_original.java

# Verify extraction
echo "First 5 lines:"
head -5 /tmp/syncStatus_original.java
echo "Last 5 lines:"
tail -5 /tmp/syncStatus_original.java
echo "Line count:"
wc -l /tmp/syncStatus_original.java
# EXPECTED: 55-65 lines
```

4. **Update references** (CAREFUL):
```bash
# Multiple replacements needed
sed 's/DataVerticle\.fileRepository/context.fileRepository()/g' /tmp/syncStatus_original.java | \
sed 's/DataVerticle\.settingRepository/context.settingRepository()/g' | \
sed 's/this\.telegramRecord\.id()/this.telegramId/g' | \
sed 's/getRootId()/this.rootId/g' | \
sed 's/sendEvent(/this.sendEvent(/g' > /tmp/syncStatus_updated.java

# Check what changed
diff /tmp/syncStatus_original.java /tmp/syncStatus_updated.java | head -30
# EXPECTED: Only reference updates, no logic changes
```

5. **Add sendEvent() helper method**:

Since syncFileDownloadStatus calls `sendEvent()`, we need to add it to the service:

```bash
# Add to FileDownloadService before the closing brace:
cat >> /tmp/helper_methods.java << 'HELPER'

    /**
     * Send event to event bus.
     */
    private void sendEvent(EventPayload payload) {
        vertx.eventBus().publish(EventEnum.TELEGRAM_EVENT.address(),
                JsonObject.of("telegramId", this.telegramId, "payload", JsonObject.mapFrom(payload)));
    }
HELPER
```

6. **Insert both methods**:
```bash
# Insert syncFileDownloadStatus and sendEvent before closing brace
LINE_COUNT=$(wc -l < api/src/main/java/telegram/files/download/FileDownloadService.java)
head -n $((LINE_COUNT - 1)) api/src/main/java/telegram/files/download/FileDownloadService.java > /tmp/service.java
cat /tmp/syncStatus_updated.java >> /tmp/service.java
cat /tmp/helper_methods.java >> /tmp/service.java
echo "}" >> /tmp/service.java
mv /tmp/service.java api/src/main/java/telegram/files/download/FileDownloadService.java
```

7. **CRITICAL Compilation Check**:
```bash
echo "Compiling after adding syncFileDownloadStatus (complex method)..."
docker build -t test-step2.6 . 2>&1 > /tmp/build2.6.log

if [ $? -eq 0 ]; then
    echo "✅ COMPILATION PASSED"
    echo "Method added successfully"
else
    echo "❌ COMPILATION FAILED"
    echo ""
    echo "This is expected to be difficult. Errors:"
    grep "error:" /tmp/build2.6.log | head -10
    echo ""
    echo "Common issues:"
    echo "1. Unbalanced braces (check extraction range)"
    echo "2. Missing 'this.' prefix on method calls"
    echo "3. Reference not updated"
    echo ""
    echo "TO FIX:"
    echo "1. Check /tmp/syncStatus_updated.java for syntax"
    echo "2. Verify all DataVerticle references updated"
    echo "3. Check brace balance"
    echo "4. If stuck: Revert and try again"
    echo ""
    echo "REVERT COMMAND:"
    echo "git checkout -- api/src/main/java/telegram/files/download/FileDownloadService.java"
    exit 1
fi
```

8. **If compilation passes**: Commit immediately
```bash
git add -A
git commit -m "refactor: copy syncFileDownloadStatus() to FileDownloadService (complex method)"
echo "✅ Step 2.6 complete"
```

**Time Checkpoint**: If you've spent > 2 hours on this step, take a break and resume fresh.

---

### **Step 2.7: Copy Remaining Methods** (1 hour)

**Methods to copy** (if they exist and are used by the 5 main methods):

1. `downloadThumbnail()` - Already done in 2.5
2. Any private helper methods called by the copied methods

**Process**:
```bash
# Check what methods are called
grep -E "this\.[a-zA-Z]+\(" /tmp/syncStatus_updated.java /tmp/startDownload_updated.java | \
  grep -v "client\|context\|vertx" | \
  sed 's/.*this\.\([a-zA-Z]*\)(.*/\1/' | sort -u

# For each method found:
# - Check if it's in TelegramVerticle
# - If yes, copy it (same process as above)
# - If no, it's inherited or doesn't need copying
```

**Quality Gate**: After adding each helper method, compile

---

### **Step 2.8: Update TelegramVerticle to Delegate** (1.5 hours)

**Objective**: Make TelegramVerticle use FileDownloadService instead of having its own implementation

**Command 2.8.1**: Add service field to TelegramVerticle
```bash
# Find where fields are declared (around line 60-70)
grep -n "private AvgSpeed avgSpeed" api/src/main/java/telegram/files/core/TelegramVerticle.java
# Note line number

# Add new field after avgSpeed
LINE_NUM=<number>
sed -i '' "${LINE_NUM}a\\
\\
    private FileDownloadService downloadService;
" api/src/main/java/telegram/files/core/TelegramVerticle.java
```

**Verification 2.8.1**: Check field added
```bash
grep "private FileDownloadService downloadService" api/src/main/java/telegram/files/core/TelegramVerticle.java
# EXPECTED: Match found
# IF NOT: Re-run Command 2.8.1
```

**Command 2.8.2**: Add import for FileDownloadService
```bash
# Find import section
grep -n "^import telegram.files" api/src/main/java/telegram/files/core/TelegramVerticle.java | head -1
# Note line number

# Add import
sed -i '' '/^import telegram.files.util.ErrorHandling;$/a\
import telegram.files.download.FileDownloadService;
' api/src/main/java/telegram/files/core/TelegramVerticle.java
```

**Command 2.8.3**: Initialize service in start() method
```bash
# Find where start() method initializes things
grep -n "this.avgSpeed = new AvgSpeed" api/src/main/java/telegram/files/core/TelegramVerticle.java
# Note line number

# Add initialization after avgSpeed
LINE_NUM=<number>
sed -i '' "${LINE_NUM}a\\
        this.downloadService = new FileDownloadService(this.client, ServiceContext.fromDataVerticle(), this.telegramRecord.id(), vertx, getRootId());
" api/src/main/java/telegram/files/core/TelegramVerticle.java
```

**CRITICAL Verification 2.8.3**: Compile
```bash
docker build -t test-step2.8 . 2>&1 | grep "error:" | wc -l
# EXPECTED: 0
# IF > 0: Check errors, fix, retest
```

**Command 2.8.4**: Replace method implementations with delegation

**For startDownload**:
```bash
# Find the method in TelegramVerticle
grep -n "public Future<FileRecord> startDownload" api/src/main/java/telegram/files/core/TelegramVerticle.java
START_LINE=<number>
END_LINE=$((START_LINE + 95))  # Original method length

# Replace method body with delegation
cat > /tmp/startDownload_delegation.java << 'DELEGATION'
    public Future<FileRecord> startDownload(Long chatId, Long messageId, Integer fileId) {
        return downloadService.startDownload(chatId, messageId, fileId);
    }
DELEGATION

# Replace in file (this is complex, use editor or manual replacement)
```

**SAFER APPROACH**: Manually edit in IDE
1. Open `TelegramVerticle.java` in IDE
2. Find `public Future<FileRecord> startDownload`
3. Delete method body (everything between `{` and `}`)
4. Replace with: `return downloadService.startDownload(chatId, messageId, fileId);`
5. Save
6. Compile immediately

**Repeat for**:
- cancelDownload
- togglePauseDownload  
- downloadThumbnail

**Keep**:
- syncFileDownloadStatus (it's private, called internally by FileDownloadService)

**MANDATORY Verification 2.8.4**: Compile after EACH method replacement
```bash
docker build -t test-delegation . 2>&1 | grep "error:" | wc -l
# EXPECTED: 0
# IF > 0: STOP, fix, retest
```

**Verification 2.8.5**: Check delegation count
```bash
grep "return downloadService\." api/src/main/java/telegram/files/core/TelegramVerticle.java | wc -l
# EXPECTED: 4 (one per delegated public method)
# IF DIFFERENT: Check what's missing
```

**Commit**:
```bash
git add -A
git commit -m "refactor: delegate download methods to FileDownloadService"
```

---

### **Step 2.9: Remove Old Implementations** (30 minutes)

**⚠️ CRITICAL**: Only do this AFTER delegation works!

**Verification Before Starting**:
```bash
# Verify delegation is in place
grep "return downloadService\." api/src/main/java/telegram/files/core/TelegramVerticle.java | wc -l
# EXPECTED: 4
# IF < 4: DON'T proceed - delegation not complete
```

**Process**:

The old method bodies should already be replaced with delegation in Step 2.8.4.

**What to remove**:
- Old `syncFileDownloadStatus()` method (it's now in FileDownloadService, not needed in TelegramVerticle)

**How to find it**:
```bash
grep -n "private Future<Void> syncFileDownloadStatus" api/src/main/java/telegram/files/core/TelegramVerticle.java
# If found: This method should be removed (it's private, only used internally)
# If not found: It may have already been removed or made public
```

**Removal**:
```bash
# If syncFileDownloadStatus exists in TelegramVerticle and is private:
# Delete it (it's now in FileDownloadService)

# Find line range
START_LINE=<number>
END_LINE=$((START_LINE + 60))

# Delete lines
sed -i '' "${START_LINE},${END_LINE}d" api/src/main/java/telegram/files/core/TelegramVerticle.java
```

**MANDATORY Verification 2.9**: 
```bash
# 1. Compile
docker build -t test-step2.9 . 2>&1 | grep "error:" | wc -l
# EXPECTED: 0

# 2. Check TelegramVerticle size
wc -l api/src/main/java/telegram/files/core/TelegramVerticle.java
# EXPECTED: 900-1000 lines (reduced from 1,252)
# IF > 1000: More extraction needed
# IF < 900: Might have deleted too much

# 3. Check FileDownloadService size
wc -l api/src/main/java/telegram/files/download/FileDownloadService.java
# EXPECTED: 300-400 lines
```

**Commit**:
```bash
git add -A
git commit -m "refactor: remove old download methods from TelegramVerticle"
```

---

### **Step 2.10: Functional Testing** (1 hour)

**⚠️ MANDATORY**: Do NOT skip this step

**Test 2.10.1: Compilation and Deployment**
```bash
echo "=== Step 2.10: Functional Testing ==="

# Build
docker build -t telegram-files:service-extracted . 2>&1 | tail -3
# EXPECTED: "DONE", "unpacking"
# IF BUILD FAILED: STOP - debug before deploying

# Deploy
docker tag telegram-files:service-extracted telegram-files:custom
cd /Users/fabian/projects/music-processor/telegram-postproc
./stackctl restart

# Wait for health
echo "Waiting 30 seconds for container health..."
sleep 30

# Check status
docker ps --filter "name=telegram-files" --format "{{.Status}}"
# EXPECTED: Contains "healthy"
# IF "restarting" or "unhealthy": STOP - check logs
```

**Test 2.10.2: Download Start**
```
MANUAL TEST (use browser):
1. Open http://localhost:8979
2. Login if needed
3. Navigate to any chat
4. Find a file with status "Idle"
5. Click on the file
6. Click "Start Download"

EXPECTED:
- Button shows loading
- Status changes to "Downloading"
- File starts downloading

IF FAILS:
- Check browser console for errors
- Check docker logs: docker logs telegram-files | tail -50
- Check for NullPointerException
- Likely: downloadService not initialized properly
```

**Test 2.10.3: Download Cancel**
```
MANUAL TEST:
1. Start a download (from 2.10.2)
2. Click "Cancel" button

EXPECTED:
- Download stops
- Status changes back to "Idle" or removed

IF FAILS: Check logs for errors
```

**Test 2.10.4: Download Pause/Resume**
```
MANUAL TEST:
1. Start a download
2. Click "Pause"
   EXPECTED: Status "Paused"
3. Click "Resume"
   EXPECTED: Status "Downloading"
4. Let complete
   EXPECTED: Status "Completed" or "Downloaded"

IF ANY FAILS: Delegation is broken, check implementation
```

**Test 2.10.5: Check Logs for Errors**
```bash
docker logs telegram-files 2>&1 | grep -E "ERROR|Exception|NullPointer" | grep -v "already downloaded\|Can't access"
# EXPECTED: No matches (or only expected errors)
# IF ERRORS FOUND: Investigate each one
```

**Test 2.10.6: Verify No Regressions**
```bash
# Check that FileDownloadService is being used
docker logs telegram-files | grep "FileDownloadService"
# EXPECTED: No errors related to FileDownloadService

# Check downloads are happening
docker logs telegram-files | grep "Start download file" | tail -5
# EXPECTED: If automation is enabled, should see download activity
```

**Final Verification 2.10**:
```bash
echo "Functional Test Results:"
echo "- Download start: [PASS/FAIL]"
echo "- Download cancel: [PASS/FAIL]"
echo "- Download pause/resume: [PASS/FAIL]"
echo "- No errors in logs: [PASS/FAIL]"
echo ""
echo "If ALL PASS: Task 2 complete ✅"
echo "If ANY FAIL: Debug and fix before proceeding ❌"
```

**Commit test results**:
```bash
git commit --allow-empty -m "test: verified FileDownloadService extraction works"
```

---

## Task 3: Initialize ServiceContext

**Estimated Time**: 30 minutes  
**Risk**: Low  
**Critical**: Must be done before Task 4

---

### **Step 3.1: Add Initialization to DataVerticle** (15 minutes)

**Objective**: Initialize ServiceContext so it's not null

**Command 3.1.1**: Find where repositories are initialized
```bash
grep -n "statisticRepository = new StatisticRepositoryImpl" api/src/main/java/telegram/files/DataVerticle.java
# Note line number: REPO_LINE
```

**Command 3.1.2**: Add ServiceContext initialization immediately after
```bash
REPO_LINE=<number>
INSERTION_LINE=$((REPO_LINE + 1))

# Add initialization
sed -i '' "${INSERTION_LINE}i\\
        \\
        // Initialize ServiceContext for dependency injection\\
        serviceContext = new ServiceContext(\\
            fileRepository,\\
            telegramRepository,\\
            settingRepository,\\
            statisticRepository\\
        );\\
        log.info(\"ServiceContext initialized successfully\");
" api/src/main/java/telegram/files/DataVerticle.java
```

**Verification 3.1.1**: Check code was added
```bash
grep -A 7 "statisticRepository = new" api/src/main/java/telegram/files/DataVerticle.java | grep "serviceContext = new"
# EXPECTED: Match found
# IF NOT: Re-run Command 3.1.2
```

**Verification 3.1.2**: Compile
```bash
docker build -t test-step3.1 . 2>&1 | grep "error:" | wc -l
# EXPECTED: 0
# IF > 0: Check errors, fix, retest
```

**Commit**:
```bash
git add api/src/main/java/telegram/files/DataVerticle.java
git commit -m "feat: initialize ServiceContext in DataVerticle.start()"
```

---

### **Step 3.2: Verify Initialization Works** (15 minutes)

**Deploy and test**:
```bash
# Build
docker build -t telegram-files:context-init .

# Deploy
docker tag telegram-files:context-init telegram-files:custom
cd /Users/fabian/projects/music-processor/telegram-postproc
./stackctl restart

# Wait
sleep 30
```

**Verification 3.2.1**: Check initialization log
```bash
docker logs telegram-files | grep "ServiceContext initialized"
# EXPECTED: "ServiceContext initialized successfully"
# IF NOT FOUND: Initialization didn't run - check code
```

**Verification 3.2.2**: Check for NullPointerException
```bash
docker logs telegram-files | grep "NullPointerException"
# EXPECTED: No matches
# IF FOUND: ServiceContext is still null somewhere
```

**Verification 3.2.3**: Test a feature
```
MANUAL TEST:
1. Open http://localhost:8979
2. Navigate to chat
3. Start a download

EXPECTED: Works without NPE
IF FAILS: Check logs for specific error
```

**Commit**:
```bash
git commit --allow-empty -m "test: verified ServiceContext initialization works"
```

---

## Task 4: Complete DI Migration

**Estimated Time**: 4-6 hours  
**Risk**: Medium  
**Prerequisite**: Task 3 complete (ServiceContext initialized)

---

### **Step 4.1: Convert DownloadQueueService to Instance Service** (1.5 hours)

**Objective**: Change DownloadQueueService from static utility class to instance service with DI

**Current State**:
```java
public class DownloadQueueService {
    public static Future<Integer> queueFilesForDownload(...) {
        return DataVerticle.fileRepository.queueFilesForDownload(...);
    }
}
```

**Target State**:
```java
public class DownloadQueueService {
    private final ServiceContext context;
    
    public DownloadQueueService(ServiceContext context) {
        this.context = context;
    }
    
    public Future<Integer> queueFilesForDownload(...) {
        return context.fileRepository().queueFilesForDownload(...);
    }
}
```

**Implementation**:

**Command 4.1.1**: Add ServiceContext field
```bash
# Find class declaration
grep -n "public class DownloadQueueService" api/src/main/java/telegram/files/download/DownloadQueueService.java
LINE_NUM=<number>

# Add field and constructor after class declaration
sed -i '' "$((LINE_NUM + 2))i\\
\\
    private final ServiceContext context;\\
    \\
    public DownloadQueueService(ServiceContext context) {\\
        this.context = context;\\
    }
" api/src/main/java/telegram/files/download/DownloadQueueService.java
```

**Command 4.1.2**: Remove `static` keyword from all methods
```bash
sed -i '' 's/public static Future/public Future/g' api/src/main/java/telegram/files/download/DownloadQueueService.java
```

**Command 4.1.3**: Update DataVerticle references
```bash
sed -i '' 's/DataVerticle\.fileRepository/context.fileRepository()/g' api/src/main/java/telegram/files/download/DownloadQueueService.java
```

**Command 4.1.4**: Add import for ServiceContext
```bash
sed -i '' '/^import telegram.files.repository.FileRecord;$/a\
import telegram.files.ServiceContext;
' api/src/main/java/telegram/files/download/DownloadQueueService.java
```

**MANDATORY Verification 4.1**: Compile
```bash
docker build -t test-step4.1 . 2>&1 | grep "error:" | wc -l
# EXPECTED: 0
# IF > 0: Common issues:
#   - Import missing
#   - Static reference remaining
#   - Constructor syntax error
```

**Additional Verification**: Check no static methods remain
```bash
grep "public static" api/src/main/java/telegram/files/download/DownloadQueueService.java
# EXPECTED: No matches
# IF MATCHES FOUND: Some methods still static - remove 'static' keyword
```

**Commit**:
```bash
git add api/src/main/java/telegram/files/download/DownloadQueueService.java
git commit -m "refactor: convert DownloadQueueService to instance service with DI"
```

---

### **Step 4.2: Update AutoDownloadVerticle to Use Service Instance** (1.5 hours)

**Objective**: Change AutoDownloadVerticle from calling static DownloadQueueService methods to using an instance

**Command 4.2.1**: Add service field
```bash
# Find class fields section (around line 40-50)
grep -n "private final SettingAutoRecords autoRecords" api/src/main/java/telegram/files/download/AutoDownloadVerticle.java
LINE_NUM=<number>

# Add field
sed -i '' "${LINE_NUM}a\\
\\
    private ServiceContext context;\\
    private DownloadQueueService queueService;
" api/src/main/java/telegram/files/download/AutoDownloadVerticle.java
```

**Command 4.2.2**: Initialize in start() method
```bash
# Find start() method
grep -n "public void start(Promise<Void> startPromise)" api/src/main/java/telegram/files/download/AutoDownloadVerticle.java
START_LINE=<number>

# Add initialization at beginning of start() method (after opening brace)
sed -i '' "$((START_LINE + 1))i\\
        this.context = ServiceContext.fromDataVerticle();\\
        this.queueService = new DownloadQueueService(context);\\
" api/src/main/java/telegram/files/download/AutoDownloadVerticle.java
```

**Command 4.2.3**: Replace static calls with instance calls
```bash
# Find all static calls
grep -n "DownloadQueueService\." api/src/main/java/telegram/files/download/AutoDownloadVerticle.java

# For each occurrence, replace:
sed -i '' 's/DownloadQueueService\.queueFilesForDownload/queueService.queueFilesForDownload/g' api/src/main/java/telegram/files/download/AutoDownloadVerticle.java

sed -i '' 's/DownloadQueueService\.getFilesForDownload/queueService.getFilesForDownload/g' api/src/main/java/telegram/files/download/AutoDownloadVerticle.java

sed -i '' 's/DownloadQueueService\.getDownloadingCount/queueService.getDownloadingCount/g' api/src/main/java/telegram/files/download/AutoDownloadVerticle.java
```

**Verification 4.2.1**: Check all static calls replaced
```bash
grep "DownloadQueueService\." api/src/main/java/telegram/files/download/AutoDownloadVerticle.java | wc -l
# EXPECTED: 0
# IF > 0: Some static calls remain - replace them
```

**Verification 4.2.2**: Check instance calls exist
```bash
grep "queueService\." api/src/main/java/telegram/files/download/AutoDownloadVerticle.java | wc -l
# EXPECTED: 3+ (one for each method call)
# IF 0: Instance calls not added - check sed commands
```

**CRITICAL Verification 4.2.3**: Compile
```bash
docker build -t test-step4.2 . 2>&1 | grep "error:" | wc -l
# EXPECTED: 0
# IF > 0: Common issues:
#   - ServiceContext import missing
#   - DownloadQueueService import missing (it's in same package, should be fine)
#   - Field initialization in wrong place
```

**Commit**:
```bash
git add api/src/main/java/telegram/files/download/AutoDownloadVerticle.java
git commit -m "refactor: use DownloadQueueService instance in AutoDownloadVerticle"
```

---

### **Step 4.3: Convert HistoryDiscoveryService** (1.5 hours)

**Objective**: Same pattern as DownloadQueueService

**Process** (same as Step 4.1):
1. Add ServiceContext field
2. Add constructor
3. Remove `static` from all methods
4. Replace DataVerticle.* with context.*()
5. Compile after EACH change
6. Commit

**Checklist**:
```bash
# After modifications:

# Check 1: No static methods
grep "public static" api/src/main/java/telegram/files/download/HistoryDiscoveryService.java | wc -l
# EXPECTED: 0

# Check 2: Has ServiceContext field
grep "private final ServiceContext context" api/src/main/java/telegram/files/download/HistoryDiscoveryService.java
# EXPECTED: Match found

# Check 3: Uses context, not DataVerticle
grep "DataVerticle\." api/src/main/java/telegram/files/download/HistoryDiscoveryService.java | wc -l
# EXPECTED: 0

# Check 4: Compiles
docker build -t test-step4.3 . 2>&1 | grep "error:" | wc -l
# EXPECTED: 0
```

**Commit**:
```bash
git add api/src/main/java/telegram/files/download/HistoryDiscoveryService.java
git commit -m "refactor: convert HistoryDiscoveryService to instance service with DI"
```

---

### **Step 4.4: Update Callers of HistoryDiscoveryService** (1 hour)

**Files that call HistoryDiscoveryService**:
- `AutoDownloadVerticle.java`
- Possibly `PreloadMessageVerticle.java`

**For AutoDownloadVerticle**:

**Command 4.4.1**: Add service field
```bash
# Find where queueService is declared
grep -n "private DownloadQueueService queueService" api/src/main/java/telegram/files/download/AutoDownloadVerticle.java
LINE_NUM=<number>

# Add field
sed -i '' "${LINE_NUM}a\\
    private HistoryDiscoveryService discoveryService;
" api/src/main/java/telegram/files/download/AutoDownloadVerticle.java
```

**Command 4.4.2**: Initialize in start()
```bash
# Find where queueService is initialized
grep -n "this.queueService = new DownloadQueueService" api/src/main/java/telegram/files/download/AutoDownloadVerticle.java
LINE_NUM=<number>

# Add initialization
sed -i '' "${LINE_NUM}a\\
        this.discoveryService = new HistoryDiscoveryService(context);
" api/src/main/java/telegram/files/download/AutoDownloadVerticle.java
```

**Command 4.4.3**: Replace static calls
```bash
# Find all static calls
grep -n "HistoryDiscoveryService\." api/src/main/java/telegram/files/download/AutoDownloadVerticle.java

# Replace with instance calls
sed -i '' 's/HistoryDiscoveryService\.discoverHistory/discoveryService.discoverHistory/g' api/src/main/java/telegram/files/download/AutoDownloadVerticle.java
```

**Verification 4.4**: Compile
```bash
docker build -t test-step4.4 . 2>&1 | grep "error:" | wc -l
# EXPECTED: 0
```

**Commit**:
```bash
git add api/src/main/java/telegram/files/download/AutoDownloadVerticle.java
git commit -m "refactor: use HistoryDiscoveryService instance in AutoDownloadVerticle"
```

---

### **Step 4.5: Final Integration Testing** (1 hour)

**Test ALL 14 features**:

```bash
#!/bin/bash
# final_integration_test.sh

echo "=== Final Integration Test ==="
echo "Testing all 14 features..."
echo ""

# Deploy latest
docker build -t telegram-files:di-complete . 2>&1 | tail -3
docker tag telegram-files:di-complete telegram-files:custom
cd /Users/fabian/projects/music-processor/telegram-postproc
./stackctl restart
sleep 30

echo "Application started. Perform manual tests:"
echo ""
echo "Feature Checklist:"
echo "1.  [ ] Login works"
echo "2.  [ ] Browse chats works"
echo "3.  [ ] View files in chat"
echo "4.  [ ] Start manual download"
echo "5.  [ ] Cancel download"
echo "6.  [ ] Pause download"
echo "7.  [ ] Resume download"
echo "8.  [ ] Auto download (enable automation)"
echo "9.  [ ] History download (enable in automation)"
echo "10. [ ] History cutoff (set date in automation)"
echo "11. [ ] Download oldest first (toggle in automation)"
echo "12. [ ] Queue persistence (restart and check queue survives)"
echo "13. [ ] Statistics badge (shows in header)"
echo "14. [ ] Transfer files (if auto transfer enabled)"
echo ""
echo "Test each feature. Mark [x] if it works."
echo ""
echo "ALL 14 must pass before Phase 1 is complete."
```

**Test Results**:
```
Feature Test Results:
1. Login: [PASS/FAIL]
2. Browse chats: [PASS/FAIL]
3. View files: [PASS/FAIL]
4. Manual download: [PASS/FAIL]
5. Cancel: [PASS/FAIL]
6. Pause: [PASS/FAIL]
7. Resume: [PASS/FAIL]
8. Auto download: [PASS/FAIL]
9. History download: [PASS/FAIL]
10. History cutoff: [PASS/FAIL]
11. Download oldest first: [PASS/FAIL]
12. Queue persistence: [PASS/FAIL]
13. Statistics badge: [PASS/FAIL]
14. Transfer: [PASS/FAIL]

If ALL PASS: ✅ Phase 1 complete
If ANY FAIL: ❌ Debug and fix
```

**Commit**:
```bash
git commit --allow-empty -m "test: verified all 14 features work after complete DI migration"
```

---

## Quality Gates - Mandatory Checkpoints

### **After EVERY step, run this**:

```bash
#!/bin/bash
# quality_gate.sh <step-name>

STEP=$1
echo "=== Quality Gate: $STEP ==="

# Gate 1: Compilation (NON-NEGOTIABLE)
echo "[1/4] Compilation..."
docker build -t test-$STEP . > /tmp/gate.log 2>&1
if [ $? -eq 0 ]; then
    echo "✅ PASS"
else
    echo "❌ FAIL - Compilation errors:"
    grep "error:" /tmp/gate.log | head -5
    echo ""
    echo "STOP: Fix compilation before proceeding"
    exit 1
fi

# Gate 2: Git state
echo "[2/4] Git state..."
if git diff --quiet; then
    echo "❌ FAIL - No changes committed"
    echo "Run: git add -A && git commit -m 'description'"
    exit 1
else
    echo "⚠️  Uncommitted changes - commit after gate passes"
fi

# Gate 3: No static access in download package (after Task 4 starts)
echo "[3/4] Static access check..."
STATIC_COUNT=$(grep -r "DataVerticle\." api/src/main/java/telegram/files/download/ 2>/dev/null | wc -l | tr -d ' ')
echo "   Static calls: $STATIC_COUNT"
if [ "$STATIC_COUNT" -gt 30 ]; then
    echo "⚠️  High count (target is 0 after Task 4)"
fi

# Gate 4: Application health
echo "[4/4] Deployment health..."
docker ps --filter "name=telegram-files" --format "{{.Status}}" | grep -q "healthy"
if [ $? -eq 0 ]; then
    echo "✅ PASS - Application healthy"
else
    echo "⚠️  WARNING - Application not healthy (may need restart)"
fi

echo ""
echo "=== Gate Result: PASS (proceed to next step) ==="
```

**Usage**:
```bash
# After Step 2.2:
./quality_gate.sh "2.2-copy-startDownload"

# After Step 4.1:
./quality_gate.sh "4.1-convert-DownloadQueueService"
```

---

## Error Recovery Procedures

### **If Compilation Fails**

```bash
# 1. Get error details
docker build -t test . 2>&1 | grep -A 3 "error:" > /tmp/errors.txt
cat /tmp/errors.txt

# 2. Identify error type
grep "cannot find symbol" /tmp/errors.txt
# If matches: Missing import

grep "illegal start of expression" /tmp/errors.txt
# If matches: Syntax error (brace/parenthesis)

grep "package .* does not exist" /tmp/errors.txt
# If matches: Wrong package name

# 3. Fix based on error type
# (See specific fixes below)

# 4. After fixing, RETEST
docker build -t test . 2>&1 | grep "error:" | wc -l
# EXPECTED: 0

# 5. Only proceed when compilation passes
```

### **If Feature Doesn't Work After Extraction**

```bash
# 1. Compare with original
git diff main refactor/phase1-clean -- api/src/main/java/telegram/files/download/FileDownloadService.java | less

# 2. Check for logic changes
# EXPECTED: Only reference updates (DataVerticle → context)
# IF logic changed: You modified code while copying - REVERT

# 3. Revert and copy again EXACTLY
git checkout HEAD~1 -- api/src/main/java/telegram/files/download/FileDownloadService.java
# Copy again, more carefully

# 4. Verify references
grep "DataVerticle\." api/src/main/java/telegram/files/download/FileDownloadService.java
# EXPECTED: 0 matches
# IF > 0: You missed updating references
```

### **If ServiceContext Causes NullPointerException**

```bash
# 1. Check initialization
grep "serviceContext = new ServiceContext" api/src/main/java/telegram/files/DataVerticle.java
# EXPECTED: Match found
# IF NOT: Initialization missing

# 2. Check initialization order
grep -A 5 "statisticRepository = new" api/src/main/java/telegram/files/DataVerticle.java
# EXPECTED: ServiceContext initialization should be within 5 lines
# IF NOT: Initialization in wrong place (might be before repositories exist)

# 3. Check it's called before verticles start
grep -A 20 "serviceContext = new" api/src/main/java/telegram/files/DataVerticle.java | grep "deployVerticle"
# EXPECTED: deployVerticle comes AFTER serviceContext initialization
# IF BEFORE: Move initialization earlier
```

---

## Verification Checklist - Before Considering Complete

### **Code Structure** ✅

```bash
# 1. FileDownloadService exists
test -f api/src/main/java/telegram/files/download/FileDownloadService.java && echo "✅" || echo "❌"

# 2. Has 5+ methods
grep -c "public Future" api/src/main/java/telegram/files/download/FileDownloadService.java
# EXPECTED: 5+ (startDownload, cancel, togglePause, downloadThumbnail, syncStatus)

# 3. TelegramVerticle reduced
wc -l api/src/main/java/telegram/files/core/TelegramVerticle.java
# EXPECTED: 900-1000 lines
# IF > 1000: Extraction incomplete

# 4. ServiceContext initialized
grep "serviceContext = new ServiceContext" api/src/main/java/telegram/files/DataVerticle.java
# EXPECTED: Found

# 5. Services converted to instances
grep "public static" api/src/main/java/telegram/files/download/DownloadQueueService.java | wc -l
# EXPECTED: 0 (no static methods)
```

### **Dependency Injection** ✅

```bash
# 1. No static access in download services
grep -r "DataVerticle\." api/src/main/java/telegram/files/download/*.java | wc -l
# EXPECTED: 0 (all use ServiceContext)

# 2. Context is used
grep -r "context\.fileRepository()\|context\.settingRepository()" api/src/main/java/telegram/files/download/*.java | wc -l
# EXPECTED: 30+ (all repository access via context)

# 3. Services are instantiated
grep "= new DownloadQueueService(context)" api/src/main/java/telegram/files/download/*.java
# EXPECTED: Found in AutoDownloadVerticle

grep "= new FileDownloadService(" api/src/main/java/telegram/files/core/TelegramVerticle.java
# EXPECTED: Found
```

### **Compilation** ✅

```bash
docker build -t final-check . 2>&1 | grep "BUILD\|error:"
# EXPECTED: Contains "DONE", "unpacking", NO "error:"
```

### **Functionality** ✅

```
Manual Test Results (from Step 4.5):
- All 14 features: PASS

Logs:
docker logs telegram-files | grep -E "ERROR|Exception" | grep -v "already downloaded"
# EXPECTED: No unexpected errors
```

---

## Timeline with Checkpoints

| Hour | Task | Checkpoint |
|------|------|------------|
| 0:00 | Prerequisites verified | ✅ Branch clean, compiles |
| 0:15 | Step 2.1 complete | ✅ Skeleton compiles |
| 1:45 | Step 2.2 complete | ✅ startDownload copied, compiles |
| 2:30 | Step 2.3 complete | ✅ cancelDownload copied, compiles |
| 3:15 | Step 2.4 complete | ✅ togglePause copied, compiles |
| 4:00 | Step 2.5 complete | ✅ downloadThumbnail copied, compiles |
| 6:00 | Step 2.6 complete | ✅ syncStatus copied, compiles |
| 6:30 | Step 2.7 complete | ✅ Helpers copied, compiles |
| 8:00 | Step 2.8 complete | ✅ Delegation added, compiles |
| 8:30 | Step 2.9 complete | ✅ Old code removed, compiles |
| 9:30 | Step 2.10 complete | ✅ Features tested, working |
| 10:00 | Step 3.1 complete | ✅ ServiceContext initialized |
| 10:15 | Step 3.2 complete | ✅ No NPE, working |
| 11:45 | Step 4.1 complete | ✅ DownloadQueue converted |
| 13:15 | Step 4.2 complete | ✅ AutoDownload updated |
| 14:45 | Step 4.3 complete | ✅ HistoryDiscovery converted |
| 15:45 | Step 4.4 complete | ✅ Callers updated |
| 16:45 | Step 4.5 complete | ✅ All features tested |

**If you're behind**: You're being thorough (good)  
**If you're ahead**: Did you skip testing? (bad)

---

## The ONE Rule

```
┌──────────────────────────────────────────────────────────┐
│                                                          │
│           COMPILE AFTER EVERY SINGLE CHANGE              │
│                                                          │
│  Not after every step.                                   │
│  Not after every method.                                 │
│  After EVERY change to EVERY file.                       │
│                                                          │
│  If it doesn't compile: STOP and FIX                     │
│  Never proceed with errors                               │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

---

## Success Indicators

**Green Lights** (proceed):
- ✅ docker build succeeds
- ✅ No error: messages in output
- ✅ Feature tested and works
- ✅ Commit created
- ✅ Git tree clean

**Red Flags** (stop):
- ❌ Compilation errors
- ❌ NullPointerException
- ❌ Feature doesn't work
- ❌ Different behavior than before
- ❌ Uncommitted changes accumulating

---

## When You're Done

### **Final Verification Script**

```bash
#!/bin/bash
echo "=== Phase 1 Final Verification ==="

# 1. Compilation
echo "1. Compilation..."
docker build -t telegram-files:phase1-final . > /dev/null 2>&1 && echo "✅ PASS" || echo "❌ FAIL"

# 2. File structure
echo "2. FileDownloadService exists..."
test -f api/src/main/java/telegram/files/download/FileDownloadService.java && echo "✅ PASS" || echo "❌ FAIL"

# 3. TelegramVerticle size
echo "3. TelegramVerticle size..."
SIZE=$(wc -l < api/src/main/java/telegram/files/core/TelegramVerticle.java)
if [ $SIZE -lt 1000 ]; then echo "✅ PASS ($SIZE lines)"; else echo "❌ FAIL ($SIZE lines, should be < 1000)"; fi

# 4. ServiceContext initialized
echo "4. ServiceContext initialized..."
grep -q "serviceContext = new ServiceContext" api/src/main/java/telegram/files/DataVerticle.java && echo "✅ PASS" || echo "❌ FAIL"

# 5. No static access in download
echo "5. No static access in download package..."
COUNT=$(grep -r "DataVerticle\." api/src/main/java/telegram/files/download/*.java 2>/dev/null | wc -l | tr -d ' ')
if [ "$COUNT" -eq 0 ]; then echo "✅ PASS"; else echo "❌ FAIL ($COUNT static calls remain)"; fi

# 6. Services use DI
echo "6. Services use dependency injection..."
COUNT=$(grep -r "context\..*Repository()" api/src/main/java/telegram/files/download/*.java 2>/dev/null | wc -l | tr -d ' ')
if [ "$COUNT" -gt 20 ]; then echo "✅ PASS ($COUNT DI calls)"; else echo "⚠️  WARNING (only $COUNT DI calls)"; fi

# 7. Deployment health
echo "7. Application health..."
docker ps --filter "name=telegram-files" --format "{{.Status}}" | grep -q "healthy" && echo "✅ PASS" || echo "❌ FAIL"

# 8. No errors in logs
echo "8. Error check..."
ERROR_COUNT=$(docker logs telegram-files 2>&1 | grep -E "ERROR|Exception" | grep -v "already downloaded\|Can't access" | wc -l | tr -d ' ')
if [ "$ERROR_COUNT" -eq 0 ]; then echo "✅ PASS"; else echo "⚠️  $ERROR_COUNT errors (review them)"; fi

echo ""
echo "If all tests PASS: Ready to merge to main"
echo "If any test FAIL: Debug before merging"
```

**Save as**: `/tmp/final_verification.sh`  
**Run when**: All tasks complete  
**Expected**: All ✅ PASS

---

## Documentation Update After Completion

**Update these files**:

1. `refactoring/EXECUTIVE_SUMMARY.md`
   - Change "40% complete" → "100% complete"
   - Update metrics
   - Change status to COMPLETE

2. Create `refactoring/PHASE1_COMPLETION_REPORT.md`
   - What was done
   - How long it took
   - Issues encountered
   - Final metrics
   - Lessons learned

3. Update `README.md` (if exists)
   - Note new package structure
   - Explain service organization

---

## This Handbook Guarantees Success

**How**:
- ✅ Every step is detailed
- ✅ Every command is provided
- ✅ Every verification is specified
- ✅ Every error scenario has recovery
- ✅ Quality gates are mandatory
- ✅ Testing is comprehensive

**Follow this handbook exactly** and you CANNOT fail.

**The first attempt didn't have this level of detail. This one does.** 🎯

