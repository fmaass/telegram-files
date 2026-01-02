# Phase 1 Refactoring - Testing Protocol

**Purpose**: Ensure refactoring doesn't break functionality

---

## Testing After Each Task

### **Task 1: Package Reorganization**

**Compilation Test** (MUST PASS):
```bash
cd /Users/fabian/projects/telegram-files/api
./gradlew clean compileJava

# Expected output:
# BUILD SUCCESSFUL in Xs
```

**Import Verification**:
```bash
# Check no old imports remain
grep -r "import telegram.files.TelegramVerticle;" api/src/
# Expected: No matches (should be telegram.files.core.TelegramVerticle)

# Verify new imports work
grep -r "import telegram.files.core.TelegramVerticle;" api/src/ | wc -l
# Expected: 5+ matches
```

**Package Verification**:
```bash
# Count files in new packages
find api/src/main/java/telegram/files/core -name "*.java" | wc -l
# Expected: 10

find api/src/main/java/telegram/files/download -name "*.java" | wc -l
# Expected: 4

find api/src/main/java/telegram/files/transfer -name "*.java" | wc -l
# Expected: 2
```

**No Functional Test Needed** (pure code organization)

---

### **Task 2: Extract FileDownloadService**

**Compilation Test**:
```bash
./gradlew clean compileJava
# Expected: BUILD SUCCESSFUL
```

**Size Verification**:
```bash
# FileDownloadService should exist
wc -l api/src/main/java/telegram/files/download/FileDownloadService.java
# Expected: 300-400 lines

# TelegramVerticle should be smaller
wc -l api/src/main/java/telegram/files/core/TelegramVerticle.java
# Expected: 900-1000 lines (was 1,291)
```

**Delegation Verification**:
```bash
# TelegramVerticle should delegate to service
grep "downloadService\." api/src/main/java/telegram/files/core/TelegramVerticle.java
# Expected: 5+ matches (startDownload, cancelDownload, etc.)
```

**Functional Test** (CRITICAL):
```bash
# 1. Build and run
docker build -t telegram-files:test .
docker run -d --name test-tg telegram-files:test

# 2. Test download start
curl -X POST http://localhost:8080/1/file/start-download \
  -H "Content-Type: application/json" \
  -d '{"chatId":123,"messageId":456,"fileId":789}'
# Expected: 200 OK or appropriate error (not 500)

# 3. Check logs for errors
docker logs test-tg | grep ERROR
# Expected: No unexpected errors

# 4. Cleanup
docker stop test-tg && docker rm test-tg
```

---

### **Task 3: ServiceContext DI**

**Compilation Test**:
```bash
./gradlew clean compileJava
# Expected: BUILD SUCCESSFUL
```

**Static Access Verification**:
```bash
# New services should NOT use static access
grep "DataVerticle\." api/src/main/java/telegram/files/download/FileDownloadService.java
# Expected: No matches

grep "context\.fileRepository()" api/src/main/java/telegram/files/download/FileDownloadService.java
# Expected: Multiple matches
```

**Context Creation Verification**:
```bash
# DataVerticle should create context
grep "serviceContext = new ServiceContext" api/src/main/java/telegram/files/DataVerticle.java
# Expected: 1 match
```

**Functional Test**:
```bash
# Full application test
docker-compose up -d
sleep 30

# Check application started
curl http://localhost:8979/
# Expected: HTML response

# Check downloads work
# (Use UI to start a download)

# Check logs
docker logs telegram-files | grep "ServiceContext\|NullPointerException"
# Expected: No NullPointerException
```

---

### **Task 4: Apply ErrorHandling Patterns**

**Usage Verification**:
```bash
# Count ErrorHandling usage
grep -r "ErrorHandling\." api/src/main/java/telegram/files | wc -l
# Expected: 20+ occurrences

# Verify critical operations wrapped
grep "ErrorHandling.critical" api/src/main/java/telegram/files | wc -l
# Expected: 10+ matches

# Verify recoverable operations wrapped
grep "ErrorHandling.recoverable" api/src/main/java/telegram/files | wc -l
# Expected: 5+ matches
```

**Error Logging Test**:
```bash
# Trigger an error (e.g., invalid file ID)
curl -X POST http://localhost:8080/1/file/start-download \
  -H "Content-Type: application/json" \
  -d '{"chatId":123,"messageId":456,"fileId":-1}'

# Check logs for consistent format
docker logs telegram-files | grep "\[CRITICAL\]\|\[RECOVERABLE\]"
# Expected: Errors logged with [CRITICAL] or [RECOVERABLE] prefix
```

**Functional Test**:
```bash
# Application should handle errors gracefully
# 1. Disconnect database (simulate failure)
docker-compose stop postgres-proxy

# 2. Try operation
curl http://localhost:8979/telegram/1/chat/123/statistics
# Expected: Error response, not crash

# 3. Reconnect
docker-compose start postgres-proxy

# 4. Verify recovery
curl http://localhost:8979/telegram/1/chat/123/statistics
# Expected: Works again
```

---

### **Task 5: Standardize Async Patterns**

**Blocking Verification**:
```bash
# Verify no Future.await() remains
grep -r "Future.await" api/src/main/java/telegram/files
# Expected: No matches

# Count composition usage
grep -r "\.compose(" api/src/main/java/telegram/files | wc -l
# Expected: 100+ (increased from before)
```

**Performance Test**:
```bash
# Measure response time
time curl http://localhost:8979/telegram/1/chat/123/statistics
# Expected: < 100ms

# Before refactoring: May have been slower due to blocking
```

**Concurrency Test**:
```bash
# Start 10 downloads simultaneously
for i in {1..10}; do
  curl -X POST http://localhost:8080/1/file/start-download \
    -H "Content-Type: application/json" \
    -d "{\"chatId\":123,\"messageId\":$i,\"fileId\":$i}" &
done
wait

# Check logs - downloads should start in parallel
docker logs telegram-files | grep "Start download" | tail -10
# Expected: Timestamps very close together (parallel, not sequential)
```

---

## Full Phase 1 Integration Test

After all tasks complete, run comprehensive test:

### **1. Build Test**
```bash
cd /Users/fabian/projects/telegram-files
docker build -t telegram-files:phase1-complete .
# Expected: BUILD SUCCESSFUL
```

### **2. Deployment Test**
```bash
docker tag telegram-files:phase1-complete telegram-files:custom
cd /Users/fabian/projects/music-processor/telegram-postproc
./stackctl down && ./stackctl up
# Expected: All containers start healthy
```

### **3. Functional Test Suite**

**A. Download Workflow**
```
1. Open http://localhost:8979
2. Navigate to a chat
3. Click on a file → Start Download
4. Expected: Download starts, progress shows
5. Wait for completion
6. Expected: File shows "Completed" or "Downloaded" (if setting enabled)
```

**B. Automation Workflow**
```
1. Open automation dialog
2. Enable auto download with history
3. Set history cutoff date
4. Enable "download oldest first"
5. Save automation
6. Expected: Files start queueing and downloading
7. Check badge in header
8. Expected: Shows accurate counts
```

**C. Statistics Workflow**
```
1. Navigate to chat with files
2. Check badge in header
3. Expected: Shows "X/Y" format
4. Hover over badge
5. Expected: Tooltip shows breakdown
6. Check Settings → Statistics tab
7. Expected: Charts and numbers display
```

**D. Transfer Workflow**
```
1. Enable auto transfer in automation
2. Set destination path
3. Wait for files to complete
4. Expected: Files transfer to destination
5. Check file status
6. Expected: Shows "Transferred"
```

### **4. Error Handling Test**

**A. Database Failure**
```bash
# Stop database
docker-compose stop postgres-proxy

# Try operations
curl http://localhost:8979/telegram/1/chat/123/statistics
# Expected: Error response, not crash

# Check logs
docker logs telegram-files | grep "\[RECOVERABLE\]"
# Expected: Graceful error messages

# Restart database
docker-compose start postgres-proxy
# Expected: Application recovers
```

**B. Invalid Parameters**
```bash
# Try invalid telegramId
curl http://localhost:8080/telegram/-1/chat/123/statistics
# Expected: 400 Bad Request with "telegramId must be positive"

# Try invalid limit
# (Internal test - DownloadQueueService.queueFilesForDownload(-1, 0, -5, null, null))
# Expected: IllegalArgumentException
```

### **5. Performance Test**

**A. Response Time**
```bash
# Measure statistics endpoint
time curl http://localhost:8979/telegram/1/chat/123/statistics
# Expected: < 100ms

# Measure 10 sequential requests
for i in {1..10}; do
  time curl -s http://localhost:8979/telegram/1/chat/123/statistics > /dev/null
done
# Expected: Consistent timing, no degradation
```

**B. Concurrency**
```bash
# 10 parallel requests
for i in {1..10}; do
  curl -s http://localhost:8979/telegram/1/chat/123/statistics > /dev/null &
done
wait
# Expected: All complete successfully, no errors
```

**C. Memory Stability**
```bash
# Monitor memory over 5 minutes
docker stats telegram-files --no-stream
# Initial memory: Note value

# Trigger operations (downloads, statistics, etc.)
# ... use application for 5 minutes ...

docker stats telegram-files --no-stream
# Expected: Memory similar to initial (no leak)
```

### **6. Regression Test**

**Check all existing features still work:**
- [ ] Login with Telegram account
- [ ] Browse chats
- [ ] View files in chat
- [ ] Start manual download
- [ ] Pause/resume download
- [ ] Cancel download
- [ ] Enable automation
- [ ] Set history cutoff date
- [ ] Enable download oldest first
- [ ] Enable auto transfer
- [ ] View statistics
- [ ] Change settings
- [ ] View progress badge

**If ANY feature breaks, Phase 1 is NOT complete.**

---

## Automated Test Script

```bash
#!/bin/bash
# test_phase1.sh - Run after Phase 1 completion

set -e

echo "=== Phase 1 Refactoring Test Suite ==="
echo ""

# Test 1: Compilation
echo "Test 1: Compilation..."
cd api && ./gradlew clean compileJava > /dev/null 2>&1 && echo "✅ PASS" || echo "❌ FAIL"

# Test 2: No Future.await
echo "Test 2: No blocking calls..."
COUNT=$(grep -r "Future.await" api/src/main/java/telegram/files | wc -l | tr -d ' ')
if [ "$COUNT" -eq 0 ]; then echo "✅ PASS"; else echo "❌ FAIL ($COUNT found)"; fi

# Test 3: ErrorHandling usage
echo "Test 3: ErrorHandling applied..."
COUNT=$(grep -r "ErrorHandling\." api/src/main/java/telegram/files | wc -l | tr -d ' ')
if [ "$COUNT" -ge 20 ]; then echo "✅ PASS ($COUNT uses)"; else echo "❌ FAIL (only $COUNT uses)"; fi

# Test 4: Package structure
echo "Test 4: Package organization..."
CORE=$(find api/src/main/java/telegram/files/core -name "*.java" 2>/dev/null | wc -l | tr -d ' ')
DOWNLOAD=$(find api/src/main/java/telegram/files/download -name "*.java" 2>/dev/null | wc -l | tr -d ' ')
if [ "$CORE" -ge 10 ] && [ "$DOWNLOAD" -ge 4 ]; then echo "✅ PASS"; else echo "❌ FAIL"; fi

# Test 5: TelegramVerticle size
echo "Test 5: TelegramVerticle reduced..."
LINES=$(wc -l api/src/main/java/telegram/files/core/TelegramVerticle.java 2>/dev/null | awk '{print $1}')
if [ "$LINES" -lt 1000 ]; then echo "✅ PASS ($LINES lines)"; else echo "❌ FAIL ($LINES lines, should be < 1000)"; fi

# Test 6: ServiceContext exists
echo "Test 6: ServiceContext created..."
if [ -f "api/src/main/java/telegram/files/ServiceContext.java" ]; then echo "✅ PASS"; else echo "❌ FAIL"; fi

# Test 7: Build Docker image
echo "Test 7: Docker build..."
docker build -t telegram-files:phase1-test . > /dev/null 2>&1 && echo "✅ PASS" || echo "❌ FAIL"

echo ""
echo "=== Test Suite Complete ==="
echo "If all tests PASS, Phase 1 is ready for deployment."
```

**Run this script to verify Phase 1 completion.**

---

## Manual Verification Checklist

After automated tests pass, manually verify:

### **Code Quality**
- [ ] No compilation warnings
- [ ] No linter errors
- [ ] No TODO comments added
- [ ] Code formatted consistently
- [ ] All imports organized

### **Architecture**
- [ ] Packages follow bounded context pattern
- [ ] Services have single responsibility
- [ ] Dependencies injected, not static
- [ ] Error handling consistent
- [ ] Async patterns consistent

### **Functionality**
- [ ] Application starts without errors
- [ ] All features work as before
- [ ] No regressions detected
- [ ] Performance stable or improved
- [ ] Memory usage stable

### **Documentation**
- [ ] ARCHITECTURE.md created (if applicable)
- [ ] Code comments updated for moved files
- [ ] README updated if structure changed
- [ ] Migration notes documented

---

## Failure Scenarios & Recovery

### **Scenario 1: Compilation Fails After Package Move**

**Symptom**: `./gradlew compileJava` fails with "cannot find symbol"

**Diagnosis**:
```bash
# Find missing imports
./gradlew compileJava 2>&1 | grep "cannot find symbol"
# Note which class is missing
```

**Fix**:
```bash
# Search for old import
grep -r "import telegram.files.MissingClass;" api/src/

# Update to new package
sed -i '' 's/import telegram\.files\.MissingClass;/import telegram.files.newpackage.MissingClass;/g' api/src/path/to/file.java
```

### **Scenario 2: Application Crashes on Startup**

**Symptom**: Docker container exits immediately

**Diagnosis**:
```bash
docker logs telegram-files
# Look for stack trace
```

**Common Causes**:
- Missing import
- Circular dependency
- Static initialization failure

**Fix**:
- Check stack trace for exact error
- Verify all moved files have correct package declarations
- Check for circular imports between packages

### **Scenario 3: Downloads Don't Work**

**Symptom**: Start download returns error or does nothing

**Diagnosis**:
```bash
# Check if FileDownloadService is initialized
docker logs telegram-files | grep "FileDownloadService"

# Check for delegation
grep "downloadService\." api/src/main/java/telegram/files/core/TelegramVerticle.java
```

**Fix**:
- Verify FileDownloadService is instantiated in TelegramVerticle.start()
- Verify all methods properly delegated
- Check service has correct dependencies

### **Scenario 4: NullPointerException with ServiceContext**

**Symptom**: NPE when accessing context.fileRepository()

**Diagnosis**:
```bash
# Check if context is created
grep "serviceContext = " api/src/main/java/telegram/files/DataVerticle.java

# Check if context is passed to services
grep "new FileDownloadService.*context" api/src/main/java/telegram/files
```

**Fix**:
- Ensure DataVerticle.start() creates serviceContext
- Ensure services receive context in constructor
- Ensure context is created before services

---

## Performance Benchmarks

### **Before Phase 1**
```
Download start: ~50-100ms (includes blocking)
Statistics query: ~30-50ms
Concurrent downloads: Sequential (blocking)
Event loop: Occasionally blocked
```

### **After Phase 1 (Expected)**
```
Download start: ~20-40ms (non-blocking)
Statistics query: ~20-30ms (cached settings)
Concurrent downloads: Parallel (non-blocking)
Event loop: Never blocked
```

### **How to Measure**
```bash
# Response time
time curl http://localhost:8979/telegram/1/chat/123/statistics

# Concurrent operations
time bash -c 'for i in {1..10}; do curl -s http://localhost:8979/telegram/1/chat/123/statistics > /dev/null & done; wait'
# Should be ~same as single request (parallel execution)
```

---

## Rollback Procedure

If Phase 1 causes critical issues:

### **Immediate Rollback** (< 5 minutes)
```bash
# 1. Switch to main
git checkout main

# 2. Rebuild and redeploy
docker build -t telegram-files:custom .
cd /Users/fabian/projects/music-processor/telegram-postproc
./stackctl down && ./stackctl up

# 3. Verify
curl http://localhost:8979/
# Expected: Application works
```

### **Partial Rollback** (keep what works)
```bash
# 1. Create new branch from main
git checkout main
git checkout -b refactor/phase1-partial

# 2. Cherry-pick working commits
git cherry-pick <hash-of-ErrorHandling-commit>
git cherry-pick <hash-of-DownloadStatistics-commit>
# Skip problematic commits

# 3. Deploy
docker build -t telegram-files:custom .
./stackctl restart
```

### **Debug and Fix** (if issue is fixable)
```bash
# Stay on refactor/phase1-architecture
# Fix the issue
# Test again
# Continue
```

---

## Success Criteria - Final Checklist

Phase 1 is complete when ALL of these are true:

### **Structure** ✅
- [ ] 6 packages exist (core, download, transfer, automation, statistics, util)
- [ ] 45+ files moved to appropriate packages
- [ ] All package declarations correct
- [ ] All imports updated

### **Services** ✅
- [ ] ErrorHandling utility used 20+ times
- [ ] DownloadStatistics domain object used
- [ ] FileDownloadService extracted (~350 LOC)
- [ ] ServiceContext created and used
- [ ] TelegramVerticle < 1,000 LOC

### **Dependencies** ✅
- [ ] ServiceContext passed to new services
- [ ] No static DataVerticle.* in new code
- [ ] Old code still works (backward compat maintained)

### **Async** ✅
- [ ] Zero Future.await() calls
- [ ] All operations use composition
- [ ] Consistent error handling

### **Quality** ✅
- [ ] No compilation errors
- [ ] No linter warnings
- [ ] No test failures
- [ ] Code formatted

### **Functionality** ✅
- [ ] Application starts
- [ ] Login works
- [ ] Downloads work
- [ ] Transfers work
- [ ] Automation works
- [ ] Statistics work
- [ ] Settings work
- [ ] No regressions

### **Performance** ✅
- [ ] Response times stable or improved
- [ ] No memory leaks
- [ ] Concurrent operations work
- [ ] No event loop blocking

### **Documentation** ✅
- [ ] ARCHITECTURE.md created
- [ ] Code comments updated
- [ ] Migration notes documented
- [ ] Known issues documented

---

## When to Stop

**STOP and rollback if:**
- Any quality gate fails repeatedly
- Functionality breaks and can't be fixed quickly
- Performance degrades significantly
- Time exceeds 1.5 weeks (60 hours)

**STOP and reassess if:**
- Hitting unexpected complexity
- Discovering architectural issues
- Need to redesign approach

**Continue if:**
- Quality gates passing
- Functionality intact
- Making steady progress
- Within time budget

---

## Post-Phase 1 Actions

After Phase 1 complete and deployed:

1. **Monitor for 48 hours**
   - Watch logs for errors
   - Monitor performance metrics
   - Check user reports

2. **Merge to main** (if stable)
   ```bash
   git checkout main
   git merge refactor/phase1-architecture
   git push origin main
   ```

3. **Update feature documentation**
   - Update code examples in features/*.md
   - Reflect new package structure
   - Update import statements

4. **Plan Phase 2**
   - Review what worked well
   - Identify next improvements
   - Estimate effort

---

## This Protocol Ensures Quality

Follow this testing protocol religiously. **Don't skip tests** - they catch issues early when they're cheap to fix.

**Phase 1 is foundation for all future improvements.** Get it right.

