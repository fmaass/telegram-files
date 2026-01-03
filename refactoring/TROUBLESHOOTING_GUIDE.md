# Troubleshooting Guide - Phase 1 Remaining Work

**Purpose**: Fix ANY problem that occurs during implementation

---

## Problem: "cannot find symbol" Error

### **Symptom**
```
error: cannot find symbol
  symbol:   class ServiceContext
  location: package telegram.files
```

### **Diagnosis**
```bash
# Check if class exists
find api/src -name "ServiceContext.java"
# EXPECTED: api/src/main/java/telegram/files/ServiceContext.java

# Check if import exists in the file with error
grep "import.*ServiceContext" <file-with-error>
# EXPECTED: import telegram.files.ServiceContext;
# IF NOT FOUND: Missing import
```

### **Solution**
```bash
# Add missing import
sed -i '' '/^import telegram.files.repository/a\
import telegram.files.ServiceContext;
' <file-with-error>

# Recompile
docker build -t test .
```

---

## Problem: "package X does not exist" Error

### **Symptom**
```
error: package DataVerticle does not exist
  DataVerticle.fileRepository
```

### **Diagnosis**

You're using `DataVerticle.fileRepository` but didn't import `telegram.files.DataVerticle`

### **Solution**
```bash
# Add import
sed -i '' '/^import telegram.files.repository/a\
import telegram.files.DataVerticle;
' <file-with-error>

# Recompile
docker build -t test .
```

---

## Problem: "illegal start of expression" Error

### **Symptom**
```
error: illegal start of expression
                        })
                         ^
```

### **Diagnosis**

Unbalanced braces or parentheses - usually from incorrect method extraction

### **Solution**

**Step 1**: Find the problematic method
```bash
# Error shows line number, e.g., line 605
# Check context around that line
sed -n '600,610p' <file-with-error> | cat -n
```

**Step 2**: Check brace balance
```bash
# Count braces in the method
sed -n '<method-start>,<method-end>p' <file> | \
  awk 'BEGIN{open=0;close=0} {
    for(i=1;i<=length($0);i++) {
      c=substr($0,i,1);
      if(c=="{") open++;
      if(c=="}") close++;
    }
  } END {print "Open: "open", Close: "close", Balance: "(open-close)}'

# EXPECTED: Balance = 0
# IF NOT 0: Unbalanced - find missing brace
```

**Step 3**: Fix
```bash
# Option A: Find missing brace and add it
# Use IDE's "Go to matching brace" feature

# Option B: Re-extract the method
# Go back to TelegramVerticle
# Find exact start and end
# Extract again with correct line numbers
```

---

## Problem: NullPointerException at Runtime

### **Symptom**
```
[ERROR] NullPointerException at FileDownloadService.startDownload
```

### **Diagnosis**

**Possible Causes**:
1. ServiceContext is null (not initialized)
2. Service instance is null (not created)
3. Field in ServiceContext is null
4. TelegramClient is null

### **Solution**

**Check 1**: ServiceContext initialized?
```bash
docker logs telegram-files | grep "ServiceContext initialized"
# EXPECTED: Message found
# IF NOT: ServiceContext.start() never ran or initialization missing
```

**Check 2**: Service created?
```bash
# Check TelegramVerticle initialization
grep "downloadService = new FileDownloadService" api/src/main/java/telegram/files/core/TelegramVerticle.java
# EXPECTED: Found
# IF NOT: Service not instantiated
```

**Check 3**: Initialization order?
```bash
# ServiceContext must be created BEFORE verticles start
# Check DataVerticle.start() order:
grep -A 30 "public void start" api/src/main/java/telegram/files/DataVerticle.java | \
  grep -n "serviceContext\|deployVerticle"

# EXPECTED: serviceContext line number < deployVerticle line number
# IF NOT: ServiceContext created after verticles (too late)
```

**Fix**: Move ServiceContext initialization earlier in DataVerticle.start()

---

## Problem: Feature Works Differently After Refactoring

### **Symptom**

Downloads worked before refactoring, now they behave differently (slower, fail, wrong order, etc.)

### **Diagnosis**

Logic was modified while copying

### **Solution**

**Step 1**: Compare logic
```bash
# Extract original method from main branch
git show main:api/src/main/java/telegram/files/TelegramVerticle.java | \
  sed -n '/public Future<FileRecord> startDownload/,/^    }/p' > /tmp/original_logic.java

# Extract from FileDownloadService
sed -n '/public Future<FileRecord> startDownload/,/^    }/p' \
  api/src/main/java/telegram/files/download/FileDownloadService.java > /tmp/new_logic.java

# Compare (ignoring DataVerticle/context changes)
diff -w /tmp/original_logic.java /tmp/new_logic.java | \
  grep -v "DataVerticle\|context\|telegramRecord\|getRootId"

# EXPECTED: Minimal or no differences
# IF MANY DIFFERENCES: Logic was changed - this is wrong
```

**Step 2**: Revert and copy again EXACTLY
```bash
# Revert FileDownloadService to before the problematic method
git log --oneline -- api/src/main/java/telegram/files/download/FileDownloadService.java
# Find commit before the method was added

git checkout <commit-before> -- api/src/main/java/telegram/files/download/FileDownloadService.java

# Copy again from TelegramVerticle
# This time: Copy EXACTLY, change ONLY references
```

---

## Problem: "Method X is already defined"

### **Symptom**
```
error: method startDownload(Long,Long,Integer) is already defined in class FileDownloadService
```

### **Diagnosis**

Method was copied twice

### **Solution**
```bash
# Count how many times method appears
grep -c "public.*startDownload" api/src/main/java/telegram/files/download/FileDownloadService.java
# EXPECTED: 1
# IF 2: Duplicate method

# Remove duplicate
# Open file in editor
# Find the duplicate (likely at bottom of file)
# Delete one copy
# Keep the one with updated references (context.*)

# Recompile
docker build -t test .
```

---

## Problem: Build Takes Forever

### **Symptom**

`docker build` runs for > 5 minutes

### **Diagnosis**

Docker is re-downloading dependencies or rebuilding everything

### **Solution**
```bash
# Check Docker cache
docker builder prune
# Remove: old builder cache

# Or: Check if Dockerfile changed
git diff main -- Dockerfile
# IF CHANGED: Dockerfile modifications can break cache

# Or: Use cached layers
docker build --cache-from telegram-files:phase1-clean -t test .
```

---

## Problem: Can't Tell If Feature Works

### **Symptom**

Not sure if download actually works or just appears to work

### **Solution - Detailed Download Test**
```bash
# 1. Check database before
docker exec postgres-proxy psql -h host.docker.internal -p 5455 -U telegram_user -d telegram_files_mac \
  -c "SELECT COUNT(*) FROM file_record WHERE download_status='downloading';"
# Note count: BEFORE_COUNT

# 2. Start a download via UI

# 3. Check database after (within 5 seconds)
docker exec postgres-proxy psql -h host.docker.internal -p 5455 -U telegram_user -d telegram_files_mac \
  -c "SELECT COUNT(*) FROM file_record WHERE download_status='downloading';"
# Note count: AFTER_COUNT

# 4. Verify
if [ $AFTER_COUNT -gt $BEFORE_COUNT ]; then
    echo "✅ Download started (database updated)"
else
    echo "❌ Download didn't start (database not updated)"
fi

# 5. Check TDLib activity
docker logs telegram-files | grep "AddFileToDownloads" | tail -3
# EXPECTED: Recent log entries
# IF NONE: Downloads not reaching TDLib
```

---

## Problem: Too Many Errors to Debug

### **Symptom**

20+ compilation errors, don't know where to start

### **Diagnosis**

You made too many changes before compiling

### **Solution**

**IMMEDIATE**:
```bash
# 1. Stash changes
git stash

# 2. Go back to last working state
git log --oneline -10
# Find last commit that compiled

# 3. Start over from that point
# This time: ONE change, compile, commit, repeat
```

**PREVENTION**: Never make more than 1 change without compiling

---

## Problem: Don't Know If ServiceContext Is Working

### **Symptom**

Not sure if DI is actually being used

### **Solution - DI Verification Test**
```bash
# 1. Add debug logging temporarily
# In FileDownloadService constructor:
# log.info("FileDownloadService created with telegramId: {}", telegramId);

# 2. Redeploy
docker build -t test . && docker tag test telegram-files:custom && ./stackctl restart

# 3. Check logs
docker logs telegram-files | grep "FileDownloadService created"
# EXPECTED: One entry per telegram account
# IF FOUND: Service is being instantiated ✅
# IF NOT: Service not created ❌

# 4. Check context is not null
# In FileDownloadService.startDownload(), add at start:
# if (context == null) throw new IllegalStateException("ServiceContext is null!");

# 5. Trigger download
# EXPECTED: No IllegalStateException
# IF EXCEPTION: Context is null - not initialized properly
```

---

## Problem: Git Merge Conflicts

### **Symptom**

When trying to merge back to main, conflicts occur

### **Solution**
```bash
# Check what conflicts
git checkout main
git merge refactor/phase1-clean
# If conflicts:

# Show conflicts
git diff --name-only --diff-filter=U

# For each conflicted file:
git checkout --theirs <file>  # Use refactor branch version
# Or:
git checkout --ours <file>    # Use main branch version
# Or: Manually resolve

# After resolving all:
git add -A
git commit -m "merge: resolve conflicts from Phase 1 refactoring"
```

---

## Problem: Memory Leak After Refactoring

### **Symptom**

Memory increases over time, doesn't stabilize

### **Diagnosis**
```bash
# Check memory over time
watch -n 60 'docker stats telegram-files --no-stream'
# Watch for 10-15 minutes
# EXPECTED: Memory stabilizes
# IF INCREASING: Leak

# Check for common causes:
# 1. Service instances not released
# 2. Cache not bounded (e.g., trackDownloadedStateCache)
# 3. Event listeners not removed
```

### **Solution**
```bash
# Check if services are recreated repeatedly
docker logs telegram-files | grep "FileDownloadService created" | wc -l
# EXPECTED: Small number (one per account)
# IF LARGE: Services being recreated - check verticle lifecycle

# Check cache size
# If cache is unbounded, add size limit or TTL
```

---

## Problem: Performance Degraded

### **Symptom**

Application slower after refactoring

### **Diagnosis**
```bash
# Baseline (before refactoring)
# From main branch:
time curl -s http://localhost:8979/telegram/1/chat/123/statistics
# Note: <baseline>

# After refactoring:
time curl -s http://localhost:8979/telegram/1/chat/123/statistics
# Note: <refactored>

# Compare
# IF refactored > baseline * 1.1 (10% slower): Investigate
```

### **Common Causes**

1. **ServiceContext lookup overhead**
   - Solution: Cache ServiceContext reference

2. **Service instantiation in hot path**
   - Solution: Create services once in start(), reuse

3. **Added synchronization**
   - Check if any `synchronized` keywords added
   - Solution: Remove unnecessary synchronization

---

## When to Give Up and Rollback

### **Rollback If**:

1. **Can't fix compilation after 2 hours**
   - Errors are too complex
   - Don't understand the issue
   - Making it worse

2. **Features are broken and can't figure out why**
   - Downloads don't work
   - Can't identify root cause
   - Been debugging for 3+ hours

3. **Performance degraded significantly**
   - > 20% slower
   - Memory leak
   - Can't fix

### **Rollback Procedure**
```bash
# 1. Identify last good commit
git log --oneline -20
# Find: Last commit that worked

# 2. Hard reset
git reset --hard <last-good-commit>

# 3. Force push (careful!)
git push --force origin refactor/phase1-clean

# 4. Redeploy
docker build -t telegram-files:safe .
docker tag telegram-files:safe telegram-files:custom
./stackctl restart

# 5. Verify working
curl http://localhost:8979/

# 6. Document what went wrong
echo "Attempted Step X.Y, encountered Z, could not fix, rolled back" >> refactoring/ATTEMPTS.log
```

---

## Emergency Rollback to Main

**If everything is broken**:
```bash
# 1. Go to stable main
git checkout main

# 2. Build and deploy
docker build -t telegram-files:emergency .
docker tag telegram-files:emergency telegram-files:custom
cd /Users/fabian/projects/music-processor/telegram-postproc
./stackctl down && ./stackctl up

# 3. Verify
curl http://localhost:8979/
# Application should work

# 4. You still have refactor/phase1-clean branch
# Can try again later with more care
```

---

## Getting Help

### **Information to Provide**

If you need to ask for help, provide:

```
1. **What step**: Step X.Y - <description>

2. **What you did**: 
   <exact commands run>

3. **What happened**:
   <exact error message>
   <or behavior observed>

4. **What you expected**:
   <what should have happened>

5. **What you've tried**:
   <troubleshooting steps attempted>

6. **Current state**:
   git status
   docker ps --filter "name=telegram-files"
   
7. **Logs** (last 50 lines):
   docker logs telegram-files | tail -50
```

**With this information, anyone can help you debug.**

---

## Preventive Measures

### **Before Starting Each Day**

```bash
# 1. Verify starting state is clean
git status
# EXPECTED: Clean tree

# 2. Verify application works
curl http://localhost:8979/
# EXPECTED: HTTP 200

# 3. Note current progress
git log --oneline -5
# Know where you are

# 4. Create checkpoint branch
git branch checkpoint-$(date +%Y%m%d)
# Can always return to this
```

### **Before Making Any Change**

```bash
# 1. Read the handbook step
# 2. Understand what you're about to do
# 3. Have rollback plan ready
# 4. Then execute
```

### **After Making Any Change**

```bash
# 1. Compile immediately
docker build -t test .

# 2. If passes: commit
git add -A && git commit -m "description"

# 3. If fails: fix or revert
git checkout -- <file>
```

---

## This Guide Has Your Back

**Use it when**:
- Something goes wrong
- Not sure what to do
- Need to rollback
- Need help

**Every problem has a solution here.** 🛟

