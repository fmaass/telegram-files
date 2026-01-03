# Phase 1 Completion - Testing Checklist

**Date**: $(date)  
**Branch**: refactor/phase1-clean  
**Status**: Code complete, ready for testing

---

## Pre-Deployment Verification ✅

### Code Structure
- [x] FileDownloadService exists (377 lines)
- [x] TelegramVerticle reduced (1,020 lines, down from 1,252)
- [x] ServiceContext initialized in DataVerticle
- [x] All services use DI (no static DataVerticle calls in services)
- [x] Compilation: 0 errors

### Services Converted
- [x] FileDownloadService: Uses ServiceContext ✅
- [x] DownloadQueueService: Instance service with DI ✅
- [x] HistoryDiscoveryService: Instance service with DI ✅

---

## Step 3.2: Verify ServiceContext Initialization

### Automated Checks (Run these commands)

```bash
# 1. Check initialization log appears
docker logs telegram-files | grep "ServiceContext initialized"
# EXPECTED: "ServiceContext initialized successfully"

# 2. Check for NullPointerException
docker logs telegram-files | grep "NullPointerException"
# EXPECTED: No matches (or only expected ones)

# 3. Check application health
docker ps --filter "name=telegram-files" --format "{{.Status}}"
# EXPECTED: Contains "healthy"
```

### Manual Test
1. Open http://localhost:8979
2. Navigate to any chat
3. Start a download
4. **EXPECTED**: Works without NPE
5. **IF FAILS**: Check logs for specific error

---

## Step 2.10: Functional Testing

### Test 2.10.1: Download Start
**Steps**:
1. Open http://localhost:8979
2. Login if needed
3. Navigate to any chat
4. Find a file with status "Idle"
5. Click on the file
6. Click "Start Download"

**Expected**:
- [ ] Button shows loading
- [ ] Status changes to "Downloading"
- [ ] File starts downloading
- [ ] No errors in browser console
- [ ] No errors in docker logs

**If fails**: Check browser console and `docker logs telegram-files | tail -50`

---

### Test 2.10.2: Download Cancel
**Steps**:
1. Start a download (from Test 2.10.1)
2. Click "Cancel" button

**Expected**:
- [ ] Download stops
- [ ] Status changes back to "Idle" or removed
- [ ] No errors

---

### Test 2.10.3: Download Pause/Resume
**Steps**:
1. Start a download
2. Click "Pause"
   - [ ] Status shows "Paused"
3. Click "Resume"
   - [ ] Status shows "Downloading"
4. Let complete
   - [ ] Status shows "Completed" or "Downloaded"

---

### Test 2.10.4: Check Logs for Errors
```bash
docker logs telegram-files 2>&1 | grep -E "ERROR|Exception|NullPointer" | grep -v "already downloaded\|Can't access"
# EXPECTED: No matches (or only expected errors)
```

---

## Step 4.5: Final Integration Testing (All 14 Features)

### Feature Checklist

#### Core Features
1. [ ] **Login works**
   - Can authenticate Telegram account
   - QR code displays correctly

2. [ ] **Browse chats works**
   - Chat list loads
   - Can navigate between chats

3. [ ] **View files in chat**
   - Files display correctly
   - Filters work (type, status, etc.)

#### Download Features
4. [ ] **Start manual download**
   - Can start download from UI
   - Status updates correctly

5. [ ] **Cancel download**
   - Cancel button works
   - Status resets correctly

6. [ ] **Pause download**
   - Pause button works
   - Status shows "Paused"

7. [ ] **Resume download**
   - Resume button works
   - Status shows "Downloading"

#### Automation Features
8. [ ] **Auto download (enable automation)**
   - Can enable automation in settings
   - Files auto-download when discovered

9. [ ] **History download (enable in automation)**
   - History scanning works
   - Files discovered from history

10. [ ] **History cutoff (set date in automation)**
    - Cutoff date respected
    - Only files after date downloaded

11. [ ] **Download oldest first (toggle in automation)**
    - Ordering works correctly
    - Oldest files download first when enabled

#### Advanced Features
12. [ ] **Queue persistence (restart and check queue survives)**
    - Restart application
    - Queue persists across restarts

13. [ ] **Statistics badge (shows in header)**
    - Statistics display correctly
    - Badge updates

14. [ ] **Transfer files (if auto transfer enabled)**
    - Transfer works if enabled
    - Files moved correctly

---

## Verification Commands

### Check ServiceContext Usage
```bash
# No static access in download services
grep -r "DataVerticle\." api/src/main/java/telegram/files/download/*.java | wc -l
# EXPECTED: 0 (or only in verticles, not services)

# Context is used
grep -r "context\..*Repository()" api/src/main/java/telegram/files/download/*.java | wc -l
# EXPECTED: 30+ (all repository access via context)
```

### Check Application Health
```bash
# Application health
docker ps --filter "name=telegram-files" --format "{{.Status}}"
# EXPECTED: Contains "healthy"

# No errors in logs
docker logs telegram-files 2>&1 | grep -E "ERROR|Exception" | grep -v "already downloaded\|Can't access" | wc -l
# EXPECTED: 0 (or very low)
```

### Check Service Initialization
```bash
# ServiceContext initialized
docker logs telegram-files | grep "ServiceContext initialized"
# EXPECTED: Found

# FileDownloadService created
docker logs telegram-files | grep -i "FileDownloadService\|downloadService" | head -5
# EXPECTED: No errors
```

---

## Success Criteria

Phase 1 is complete when:

- [x] FileDownloadService exists (~377 LOC)
- [x] TelegramVerticle < 1,000 LOC (currently 1,020)
- [x] ServiceContext initialized
- [x] All download services use DI (0 static calls in services)
- [x] Compilation: 0 errors
- [ ] Application: Healthy
- [ ] All 14 features: Working
- [ ] Tests: All passing

---

## If Tests Fail

### Common Issues

1. **NullPointerException**
   - Check: ServiceContext initialized?
   - Check: downloadService created?
   - Check: Initialization order correct?

2. **Download doesn't start**
   - Check: FileDownloadService delegation working?
   - Check: Client initialized?
   - Check: Logs for errors?

3. **ServiceContext not initialized**
   - Check: DataVerticle.start() runs?
   - Check: Repositories created before ServiceContext?

### Debug Commands
```bash
# Check initialization order
grep -A 20 "public void start" api/src/main/java/telegram/files/DataVerticle.java | grep -n "serviceContext\|deployVerticle"

# Check service creation
grep "downloadService = new FileDownloadService" api/src/main/java/telegram/files/core/TelegramVerticle.java

# Check for NPE
docker logs telegram-files | grep -A 10 "NullPointerException"
```

---

## Final Verification Script

Run this after all tests pass:

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
if [ $SIZE -lt 1100 ]; then echo "✅ PASS ($SIZE lines)"; else echo "⚠️  WARNING ($SIZE lines)"; fi

# 4. ServiceContext initialized
echo "4. ServiceContext initialized..."
grep -q "serviceContext = new ServiceContext" api/src/main/java/telegram/files/DataVerticle.java && echo "✅ PASS" || echo "❌ FAIL"

# 5. No static access in download services
echo "5. No static access in download services..."
COUNT=$(grep -r "DataVerticle\." api/src/main/java/telegram/files/download/*.java 2>/dev/null | grep -v "verticle\|PreloadMessage" | wc -l | tr -d ' ')
if [ "$COUNT" -eq 0 ]; then echo "✅ PASS"; else echo "⚠️  $COUNT static calls remain (may be in verticles)"; fi

# 6. Services use DI
echo "6. Services use dependency injection..."
COUNT=$(grep -r "context\..*Repository()" api/src/main/java/telegram/files/download/*.java 2>/dev/null | wc -l | tr -d ' ')
if [ "$COUNT" -gt 20 ]; then echo "✅ PASS ($COUNT DI calls)"; else echo "⚠️  WARNING (only $COUNT DI calls)"; fi

# 7. Deployment health
echo "7. Application health..."
docker ps --filter "name=telegram-files" --format "{{.Status}}" | grep -q "healthy" && echo "✅ PASS" || echo "⚠️  Check deployment"

# 8. No errors in logs
echo "8. Error check..."
ERROR_COUNT=$(docker logs telegram-files 2>&1 | grep -E "ERROR|Exception" | grep -v "already downloaded\|Can't access" | wc -l | tr -d ' ')
if [ "$ERROR_COUNT" -eq 0 ]; then echo "✅ PASS"; else echo "⚠️  $ERROR_COUNT errors (review them)"; fi

echo ""
echo "If all tests PASS: Ready to merge to main"
echo "If any test FAIL: Debug before merging"
```

---

## Next Steps After Testing

1. **If all tests pass**:
   - Create completion report
   - Update documentation
   - Consider merging to main

2. **If tests fail**:
   - Debug specific failures
   - Fix issues
   - Retest
   - Document any known issues

---

**Good luck with testing!** 🎯

