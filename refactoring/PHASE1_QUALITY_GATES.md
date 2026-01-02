# Phase 1 Quality Gates

**Purpose**: Define measurable criteria for each task completion

---

## Gate 1: Package Reorganization ✅

### **Must Pass Before Proceeding:**

**G1.1 - Compilation Success**
```bash
cd api && ./gradlew clean compileJava
```
- ✅ PASS: `BUILD SUCCESSFUL`
- ❌ FAIL: Any compilation error

**G1.2 - All Files Moved**
```bash
# Core package
find api/src/main/java/telegram/files/core -name "*.java" | wc -l
```
- ✅ PASS: Count = 10
- ❌ FAIL: Count ≠ 10

**G1.3 - No Old Imports**
```bash
grep -r "import telegram.files.TelegramVerticle;" api/src/ | wc -l
```
- ✅ PASS: Count = 0
- ❌ FAIL: Count > 0

**G1.4 - Package Declarations Updated**
```bash
head -1 api/src/main/java/telegram/files/core/TelegramVerticle.java
```
- ✅ PASS: `package telegram.files.core;`
- ❌ FAIL: `package telegram.files;`

**Decision**: All 4 sub-gates must PASS to proceed to Task 2

---

## Gate 2: FileDownloadService Extraction ✅

### **Must Pass Before Proceeding:**

**G2.1 - Service File Exists**
```bash
test -f api/src/main/java/telegram/files/download/FileDownloadService.java && echo "PASS" || echo "FAIL"
```
- ✅ PASS: File exists
- ❌ FAIL: File missing

**G2.2 - Service Size Appropriate**
```bash
wc -l api/src/main/java/telegram/files/download/FileDownloadService.java | awk '{print $1}'
```
- ✅ PASS: 300-400 lines
- ❌ FAIL: < 300 or > 500 lines

**G2.3 - TelegramVerticle Reduced**
```bash
wc -l api/src/main/java/telegram/files/core/TelegramVerticle.java | awk '{print $1}'
```
- ✅ PASS: < 1,000 lines
- ❌ FAIL: ≥ 1,000 lines

**G2.4 - Delegation Present**
```bash
grep "downloadService\." api/src/main/java/telegram/files/core/TelegramVerticle.java | wc -l
```
- ✅ PASS: ≥ 5 occurrences
- ❌ FAIL: < 5 occurrences

**G2.5 - Functional Test**
```bash
# Start app, trigger download via API
curl -X POST http://localhost:8080/1/file/start-download \
  -H "Content-Type: application/json" \
  -d '{"chatId":123,"messageId":456,"fileId":789}'
```
- ✅ PASS: Returns 200 or appropriate error (not 500)
- ❌ FAIL: 500 error or crash

**Decision**: All 5 sub-gates must PASS to proceed to Task 3

---

## Gate 3: ServiceContext DI ✅

### **Must Pass Before Proceeding:**

**G3.1 - ServiceContext Exists**
```bash
test -f api/src/main/java/telegram/files/ServiceContext.java && echo "PASS" || echo "FAIL"
```
- ✅ PASS: File exists
- ❌ FAIL: File missing

**G3.2 - Context Created in DataVerticle**
```bash
grep "serviceContext = new ServiceContext" api/src/main/java/telegram/files/DataVerticle.java
```
- ✅ PASS: Found
- ❌ FAIL: Not found

**G3.3 - Services Use Context (Not Static)**
```bash
# FileDownloadService should NOT use static access
grep "DataVerticle\." api/src/main/java/telegram/files/download/FileDownloadService.java | wc -l
```
- ✅ PASS: Count = 0
- ❌ FAIL: Count > 0

**G3.4 - Context Methods Used**
```bash
grep "context\.fileRepository()" api/src/main/java/telegram/files/download/FileDownloadService.java | wc -l
```
- ✅ PASS: ≥ 3 occurrences
- ❌ FAIL: < 3 occurrences

**G3.5 - Application Starts**
```bash
docker-compose up -d
sleep 30
curl -s http://localhost:8979/ > /dev/null && echo "PASS" || echo "FAIL"
```
- ✅ PASS: HTTP 200
- ❌ FAIL: No response or error

**Decision**: All 5 sub-gates must PASS to proceed to Task 4

---

## Gate 4: ErrorHandling Applied ✅

### **Must Pass Before Proceeding:**

**G4.1 - Minimum Usage Count**
```bash
grep -r "ErrorHandling\." api/src/main/java/telegram/files | wc -l
```
- ✅ PASS: ≥ 20 occurrences
- ❌ FAIL: < 20 occurrences

**G4.2 - Critical Operations Wrapped**
```bash
grep "ErrorHandling.critical" api/src/main/java/telegram/files | wc -l
```
- ✅ PASS: ≥ 10 occurrences
- ❌ FAIL: < 10 occurrences

**G4.3 - Consistent Log Format**
```bash
# Trigger error, check logs
docker logs telegram-files | grep "\[CRITICAL\]\|\[RECOVERABLE\]\|\[OPTIONAL\]" | wc -l
```
- ✅ PASS: > 0 (errors logged with prefix)
- ❌ FAIL: 0 (no consistent format)

**G4.4 - No Bare onFailure in Critical Code**
```bash
# Check critical files don't have bare error handling
grep "\.onFailure(err ->" api/src/main/java/telegram/files/download/FileDownloadService.java | \
  grep -v "ErrorHandling" | wc -l
```
- ✅ PASS: Count = 0 (all use ErrorHandling)
- ❌ FAIL: Count > 0 (some bare handlers remain)

**Decision**: All 4 sub-gates must PASS to proceed to Task 5

---

## Gate 5: Async Patterns Standardized ✅

### **Must Pass Before Proceeding:**

**G5.1 - No Blocking Calls**
```bash
grep -r "Future.await" api/src/main/java/telegram/files | wc -l
```
- ✅ PASS: Count = 0
- ❌ FAIL: Count > 0

**G5.2 - Composition Usage Increased**
```bash
grep -r "\.compose(" api/src/main/java/telegram/files | wc -l
```
- ✅ PASS: ≥ 100 occurrences
- ❌ FAIL: < 100 occurrences

**G5.3 - Performance Test**
```bash
# Measure response time (10 requests)
for i in {1..10}; do
  time curl -s http://localhost:8979/telegram/1/chat/123/statistics > /dev/null 2>&1
done | grep real | awk '{sum+=$2; count++} END {print sum/count}'
```
- ✅ PASS: Average < 0.100s (100ms)
- ❌ FAIL: Average ≥ 0.100s

**G5.4 - Concurrency Test**
```bash
# 10 parallel requests should complete in ~same time as 1
SINGLE=$(time curl -s http://localhost:8979/telegram/1/chat/123/statistics > /dev/null 2>&1)
PARALLEL=$(time bash -c 'for i in {1..10}; do curl -s http://localhost:8979/telegram/1/chat/123/statistics > /dev/null & done; wait' 2>&1)
# Parallel should be < 2x single (proves parallelism)
```
- ✅ PASS: Parallel time < 2× single time
- ❌ FAIL: Parallel time ≥ 2× single time

**Decision**: All 4 sub-gates must PASS to complete Phase 1

---

## Final Phase 1 Gate ✅

### **All Tasks Complete - Ready for Production**

**Compilation Gate**:
```bash
./gradlew clean build
```
- ✅ PASS: BUILD SUCCESSFUL, all tests pass
- ❌ FAIL: Compilation or test failures

**Docker Build Gate**:
```bash
docker build -t telegram-files:phase1-final .
```
- ✅ PASS: Image builds successfully
- ❌ FAIL: Build errors

**Deployment Gate**:
```bash
docker-compose up -d
sleep 60
docker ps --filter "name=telegram-files" --format "{{.Status}}"
```
- ✅ PASS: Status contains "healthy"
- ❌ FAIL: Status is "unhealthy" or "restarting"

**Functional Gate**:
```bash
# Run full test suite (see PHASE1_TESTING_PROTOCOL.md)
./test_phase1.sh
```
- ✅ PASS: All tests pass
- ❌ FAIL: Any test fails

**Regression Gate**:
```
# Manual testing of all features (15 min)
- Login: Works
- Browse chats: Works
- Download file: Works
- Automation: Works
- Statistics: Works
- Settings: Works
```
- ✅ PASS: All features work
- ❌ FAIL: Any feature broken

**Performance Gate**:
```bash
# Response time check
time curl http://localhost:8979/telegram/1/chat/123/statistics
```
- ✅ PASS: < 100ms
- ❌ FAIL: ≥ 100ms or timeout

**Stability Gate**:
```bash
# Run for 1 hour, check memory
docker stats telegram-files --no-stream
# Note initial memory
# ... wait 1 hour ...
docker stats telegram-files --no-stream
# Compare memory
```
- ✅ PASS: Memory increase < 10%
- ❌ FAIL: Memory increase ≥ 10% (potential leak)

---

## Gate Failure Response

### **If Gate Fails:**

1. **STOP** - Don't proceed to next task
2. **Diagnose** - Check logs, run diagnostic commands
3. **Fix** - Address the specific failure
4. **Retest** - Run gate again
5. **Document** - Note what failed and how fixed
6. **Proceed** - Only after gate passes

### **If Multiple Gates Fail:**
- Consider rolling back the task
- Reassess approach
- May need different strategy

### **If Final Gate Fails:**
- **DO NOT DEPLOY TO PRODUCTION**
- Debug in test environment
- Fix all issues
- Rerun full test suite
- Only deploy when all gates pass

---

## Metrics Dashboard

Track these metrics before/after Phase 1:

| Metric | Before | After | Target |
|--------|--------|-------|--------|
| TelegramVerticle LOC | 1,291 | ? | < 1,000 |
| Static calls | 141 | ? | < 50 |
| Future.await calls | 23 | ? | 0 |
| ErrorHandling usage | 2 | ? | > 20 |
| Packages | 1 | ? | 6 |
| Avg response time | ~50ms | ? | < 50ms |
| Test coverage | Low | ? | Medium |

**Success**: All "After" values meet or exceed "Target"

---

## Quality Gate Philosophy

**Why So Strict?**
- Refactoring is high-risk (can break working code)
- Gates catch issues immediately (cheap to fix)
- Prevents accumulation of problems
- Ensures each step is solid before building on it

**Gates are not optional** - they're the difference between successful refactoring and disaster.

**Follow them religiously.**

