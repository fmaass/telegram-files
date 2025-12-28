# LLM Upgrade Instruction

**Purpose**: Copy-paste this entire document to an AI assistant (like Claude, GPT, etc.) to upgrade telegram-files to a new upstream version and re-implement all custom features.

**Last Updated**: v0.3.0 (December 28, 2025)

---

## 🤖 INSTRUCTION FOR AI ASSISTANT - START HERE

---

# Project Context

You are working with a fork of `jarvis2f/telegram-files` - a self-hosted Telegram file downloader.

**Repository Details:**
- **Original Upstream**: https://github.com/jarvis2f/telegram-files
- **This Fork**: https://github.com/fmaass/telegram-files
- **Current Version**: 0.3.0
- **Custom Features**: 5 major enhancements

**Remote Configuration:**
```bash
upstream = https://github.com/jarvis2f/telegram-files.git  (original)
origin = git@github.com:fmaass/telegram-files.git  (fork, uses SSH)
```

---

# Task: Upgrade to New Upstream Version

## Objective

Upgrade this fork to the latest upstream version (e.g., 0.4.0) while preserving and re-implementing all 5 custom features.

## Strategy

Use **Clean Slate Approach**:
1. Start from clean upstream version
2. Re-implement each feature from specifications in `features/` directory
3. Test each feature after implementation
4. Replace main branch

**Why Clean Slate:**
- Avoids complex merge conflicts
- Takes advantage of upstream's new architecture
- Ensures features are implemented "the new way"
- Cleaner git history

---

## Step-by-Step Instructions

### Phase 1: Preparation (10 minutes)

```bash
# 1. Fetch latest from upstream
cd /Users/fabian/projects/telegram-files
git fetch upstream

# 2. Check what version we're upgrading to
git log upstream/main --oneline -1
# Note the version tag (e.g., "🔖 0.4.0")

# 3. Check how many new commits
git log upstream/main --not main --oneline | wc -l
# If > 50 commits, clean slate is definitely the right choice

# 4. Backup current main
git branch backup-v0.3.0 main

# 5. Create new branch from upstream
git checkout -b main-v0.4.0 upstream/main
# ← You now have vanilla upstream 0.X.0, zero customizations
```

---

### Phase 2: Implement Features (2-3 days)

**IMPORTANT**: Read each feature doc from `features/` directory before implementing.

#### **Feature 01: History Cutoff Date** (2-3 hours)

```bash
# 1. Read the specification
cat features/01-history-cutoff-date.md

# 2. Implement according to spec
# - Add Integer historySince to DownloadRule (backend)
# - Add historySince?: string | null to AutoDownloadRule (frontend)
# - Add sentinel message computation logic
# - Add filtering during history scan
# - Add date picker to automation form
# - Update statistics queries to respect cutoff

# 3. Follow "Integration Points" section in the doc
# 4. Use "Implementation Sketch" as guide (adapt to current codebase)
# 5. Use "Code Examples from v0.3.0" as reference
# 6. Run "Test Cases" to verify

# 7. Commit
git add -A
git commit -m "feat: add history cutoff date feature

Allows filtering downloads by date. Only files on/after the
specified date are downloaded.

Implementation based on features/01-history-cutoff-date.md spec."
```

#### **Feature 02: Enhanced Chat Sorting** (2-3 hours)

```bash
# 1. Read spec
cat features/02-enhanced-chat-sorting.md

# 2. Implement
# - Modify getChatList() to accept priorityChatIds parameter
# - Add priority sorting logic (automated chats first)
# - Add getChatDownloadStatistics() method
# - Add SQL query with CASE statements for status breakdown

# 3. Test
# - Verify automated chats appear at top
# - Verify statistics show breakdown

# 4. Commit
git add -A
git commit -m "feat: add enhanced chat sorting and statistics

Automated chats appear at top of chat list. Statistics show
detailed breakdown by status.

Implementation based on features/02-enhanced-chat-sorting.md spec."
```

#### **Feature 04: Download Oldest First** (1-2 hours)

```bash
# 1. Read spec  
cat features/04-download-oldest-first.md

# 2. Implement
# - Add boolean downloadOldestFirst to DownloadRule
# - Add to AutoDownloadRule type (frontend)
# - Modify SearchChatMessages to use:
#   * if (downloadOldestFirst): fromMessageId=1, offset=-10
#   * else: fromMessageId=0, offset=0
# - Add UI toggle in automation form

# 3. Test
# - Enable toggle, verify files download oldest-to-newest
# - Disable toggle, verify files download newest-to-oldest

# 4. Commit
git add -A
git commit -m "feat: add download oldest first option

Allows reversing download order from newest-to-oldest (default)
to oldest-to-newest (chronological).

Implementation based on features/04-download-oldest-first.md spec."
```

#### **Feature 03: Chat Download Progress Badge** (2-3 hours)

```bash
# 1. Read spec
cat features/03-chat-download-progress-badge.md

# 2. Implement
# - Create web/src/components/chat-download-badge.tsx
# - Use SWR with 30s refresh interval
# - Display "X/Y" format
# - Add tooltip with detailed breakdown
# - Import and render in header.tsx
# - Import and render in mobile-header.tsx

# 3. Test
# - Navigate to chat with files
# - Verify badge appears
# - Verify tooltip shows details
# - Verify updates every 30 seconds

# 4. Commit
git add -A
git commit -m "feat: add chat download progress badge

Visual progress indicator in header showing download statistics.
Refreshes every 30 seconds to reduce database load.

Implementation based on features/03-chat-download-progress-badge.md spec."
```

#### **Feature 05: Database-Driven Download Queue** (1-2 days)

```bash
# 1. Read spec (this is the complex one!)
cat features/05-database-driven-download-queue.md

# 2. Implement (in order)
# Day 1:
# - Create migration: 0025_add_download_queue_columns.sql
#   * Add columns: scan_state, download_priority, queued_at
#   * Add 4 indexes
# - Update FileRecord model with new fields
# - Add queue methods to FileRepository interface
# - Implement queue methods in FileRepositoryImpl

# Day 2:
# - Create DownloadQueueService.java
# - Create AutomationState.java enum
# - Create HistoryDiscoveryService.java (optional, can extract later)
# - Create DateUtils.java

# Day 3:
# - Refactor AutoDownloadVerticle:
#   * Remove in-memory queue (Map<Long, LinkedList<Message>>)
#   * Add periodic queue processing
#   * Replace addWaitingMessages() with DownloadQueueService calls
# - Test persistence across restarts

# 3. Critical test
# - Start app, queue files, docker-compose restart, verify queue persists

# 4. Commit
git add -A
git commit -m "feat: add database-driven download queue architecture

Major refactor: Move download queue from memory to database.
Enables persistence across restarts and better scalability.

Files:
- DownloadQueueService.java (queue management)
- HistoryDiscoveryService.java (history scanning)
- AutomationState.java (enum-based state)
- DateUtils.java (utilities)
- Migration 0025 (schema changes)

Implementation based on features/05-database-driven-download-queue.md spec."
```

---

### Phase 3: Testing (4-5 hours)

```bash
# Run comprehensive tests for each feature

# 1. History Cutoff Date
# - Set date to 2025-06-01
# - Verify only files after that date are downloaded
# - Check database: SELECT MIN(date) FROM file_record WHERE chat_id = X
# - Expected: MIN(date) >= cutoff timestamp

# 2. Chat Sorting
# - Enable automation on 3 chats
# - Open chat dropdown
# - Verify automated chats at top

# 3. Progress Badge
# - Navigate to chat with files
# - Verify badge shows "X/Y"
# - Hover for tooltip
# - Verify updates every 30 seconds

# 4. Download Oldest First
# - Enable toggle
# - Check logs: "fromMessageId=1, offset=-10"
# - Verify files download oldest-to-newest

# 5. Database Queue
# - Start downloading
# - Restart app: docker-compose restart api
# - Verify queue persists
# - Query: SELECT COUNT(*) FROM file_record WHERE download_status='queued'
# - Expected: Queue survived restart
```

---

### Phase 4: Finalization (30 minutes)

```bash
# 1. Check version
cat VERSION
# Expected: 0.4.0 (or whatever upstream version)

# 2. Verify all features present
git log --oneline main --not upstream/main
# Expected: 5-6 commits (5 features + maybe docs update)

# 3. Run verification script
cd /Users/fabian/projects/telegram-files
echo "historySince: $(grep -r 'historySince' api/ web/ 2>/dev/null | wc -l)"
echo "downloadOldestFirst: $(grep -r 'downloadOldestFirst' api/ web/ 2>/dev/null | wc -l)"
echo "ChatDownloadBadge: $(grep -r 'ChatDownloadBadge' web/ 2>/dev/null | wc -l)"
echo "DownloadQueueService: $(grep -r 'DownloadQueueService' api/ 2>/dev/null | wc -l)"
# Expected: All numbers > 0

# 4. Replace main branch
git checkout main
git branch -D main
git checkout main-v0.4.0
git branch -m main

# 5. Push
git push --force-with-lease origin main
git push origin --tags

# 6. Clean up
git branch -D backup-v0.3.0  # Optional: keep backup for a while
```

---

## Critical Notes for AI Assistant

### **Do's** ✅

1. ✅ **Read the feature specs first** - Don't guess implementation
2. ✅ **Adapt code sketches** - Don't copy-paste blindly
3. ✅ **Use validation checklists** - Ensure completeness
4. ✅ **Run test cases** - Prove features work
5. ✅ **Keep implementation simple** - Don't over-engineer
6. ✅ **Commit after each feature** - Clean history
7. ✅ **Test after each feature** - Catch bugs early

### **Don'ts** ❌

1. ❌ **Don't copy v0.3.0 code exactly** - Adapt to new structure
2. ❌ **Don't skip test cases** - You must verify each feature
3. ❌ **Don't merge or rebase** - Use clean slate approach
4. ❌ **Don't implement features in wrong order** - Follow dependency order
5. ❌ **Don't skip Feature 05** - It's complex but critical
6. ❌ **Don't forget to update VERSION file** - Should match upstream
7. ❌ **Don't push until all features tested** - Verify first

---

## Feature Implementation Order

**MUST follow this order** (to respect dependencies):

1. **Feature 01** (No dependencies) → 2-3 hours
2. **Feature 02** (No dependencies) → 2-3 hours  
3. **Feature 04** (Needs history scanning) → 1-2 hours
4. **Feature 03** (Needs Feature 02 statistics) → 2-3 hours
5. **Feature 05** (Most complex, needs database) → 1-2 days

**Total Estimated Time**: 2-3 days

---

## Expected Conflicts & Solutions

### **If Upstream Changed History Scanning:**
- Read `features/04-download-oldest-first.md`
- Key concept: "Use negative offset for forward scan"
- Adapt to new scanning API in 0.4.0

### **If Upstream Changed Database Schema:**
- Read `features/05-database-driven-download-queue.md`
- Key concept: "Add scan_state, download_priority, queued_at columns"
- Adapt migration to new schema structure

### **If Upstream Changed Automation Config:**
- Read `features/01-history-cutoff-date.md` and `features/04-download-oldest-first.md`
- Key concept: "Add fields to DownloadRule config"
- Find equivalent config structure in 0.4.0

---

## Verification Requirements

Before marking task complete, verify:

### **Backend Verification**
```bash
# Check all fields present
grep "Integer historySince" api/src/main/java/telegram/files/repository/SettingAutoRecords.java
grep "boolean downloadOldestFirst" api/src/main/java/telegram/files/repository/SettingAutoRecords.java

# Check new files exist
test -f api/src/main/java/telegram/files/DownloadQueueService.java
test -f api/src/main/java/telegram/files/HistoryDiscoveryService.java
test -f api/src/main/java/telegram/files/repository/AutomationState.java
test -f api/src/main/java/telegram/files/repository/migrations/0025_add_download_queue_columns.sql

# Check integration
grep "DownloadQueueService" api/src/main/java/telegram/files/AutoDownloadVerticle.java | wc -l
# Expected: 5+ occurrences
```

### **Frontend Verification**
```bash
# Check types
grep "historySince" web/src/lib/types.ts
grep "downloadOldestFirst" web/src/lib/types.ts

# Check components
test -f web/src/components/chat-download-badge.tsx
grep "ChatDownloadBadge" web/src/components/header.tsx

# Check UI elements
grep "history-since" web/src/components/automation-form.tsx
grep "download-oldest-first" web/src/components/automation-form.tsx
```

### **Functional Verification**
```bash
# 1. No linter errors
# Run linter on modified files

# 2. No TypeScript errors
cd web && npx tsc --noEmit --skipLibCheck

# 3. Version correct
cat VERSION
# Expected: 0.X.0 (whatever upstream version)

# 4. All features present
git log --oneline main --not upstream/main
# Expected: 5 commits (5 features)
```

---

## Common Pitfalls to Avoid

### ⚠️ **Pitfall 1: Copying v0.3.0 Code Exactly**
- **Wrong**: Copy-paste from feature docs
- **Right**: Understand concept, adapt to 0.X.0 structure

### ⚠️ **Pitfall 2: Skipping Test Cases**
- **Wrong**: Implement feature, assume it works, move on
- **Right**: Run test cases from spec, verify works, then move on

### ⚠️ **Pitfall 3: Wrong Implementation Order**
- **Wrong**: Implement Feature 03 before Feature 02
- **Right**: Follow order: 01 → 02 → 04 → 03 → 05

### ⚠️ **Pitfall 4: Not Adapting to New Architecture**
- **Wrong**: Force old patterns onto new code
- **Right**: Use new 0.X.0 patterns, implement feature concept

### ⚠️ **Pitfall 5: Incomplete Implementation**
- **Wrong**: Add field but forget UI, or add UI but forget backend
- **Right**: Use "Validation Checklist" in each feature doc

---

## Expected Outcomes

### **After Completion:**

**Commits:**
```bash
git log --oneline -10
# Expected output:
# <hash> feat: add database-driven download queue
# <hash> feat: add chat download progress badge
# <hash> feat: add download oldest first option
# <hash> feat: add enhanced chat sorting and statistics
# <hash> feat: add history cutoff date feature
# <hash> 🔖 0.X.0  ← Upstream version tag
```

**File Statistics:**
```bash
git diff --stat upstream/main...main | tail -1
# Expected: ~20-30 files changed, +2000-3000 lines
```

**Features Present:**
```bash
# All these should return > 0
echo "historySince: $(grep -r 'historySince' api/ web/ | wc -l)"
echo "downloadOldestFirst: $(grep -r 'downloadOldestFirst' api/ web/ | wc -l)"
echo "ChatDownloadBadge: $(grep -r 'ChatDownloadBadge' web/ | wc -l)"
echo "DownloadQueueService: $(grep -r 'DownloadQueueService' api/ | wc -l)"
```

---

## Task Checklist

When you (AI assistant) complete this task, verify:

### **Preparation**
- [ ] Fetched latest upstream
- [ ] Created backup branch
- [ ] Created clean branch from upstream
- [ ] Confirmed on correct version

### **Implementation**
- [ ] Feature 01 implemented and tested
- [ ] Feature 02 implemented and tested
- [ ] Feature 04 implemented and tested
- [ ] Feature 03 implemented and tested
- [ ] Feature 05 implemented and tested

### **Verification**
- [ ] No linter errors
- [ ] No TypeScript errors
- [ ] All backend fields present
- [ ] All frontend components present
- [ ] All UI elements present
- [ ] All test cases pass
- [ ] Version file correct

### **Finalization**
- [ ] Main branch replaced
- [ ] Pushed to origin
- [ ] Tags pushed
- [ ] Backup branch kept (optional)

### **Documentation**
- [ ] Feature docs reviewed
- [ ] No significant implementation differences
- [ ] Or: Feature docs updated if implementation changed

---

## Communication Guidelines

### **What to Report**

**After each feature:**
```
✅ Feature 01 (History Cutoff Date) implemented
- Added historySince field to backend and frontend
- Added sentinel message computation
- Added date picker UI
- Test passed: Files after 2025-06-01 only
```

**After completion:**
```
🎉 Upgrade Complete!

Upgraded from: 0.3.0
Upgraded to: 0.4.0
Features re-implemented: 5/5
New upstream commits: 80
Custom commits: 5
Time taken: 2.5 days
Status: ✅ All tests passing

Next steps:
- Deploy to staging
- Test in production-like environment
- Monitor for any edge cases
```

---

## Troubleshooting Guide

### **Issue: Feature spec code doesn't work in 0.X.0**

**Solution:**
1. Understand the CONCEPT from the spec, not the exact syntax
2. Find equivalent functionality in 0.X.0
3. Adapt the pattern to match 0.X.0's style
4. Example:
   - Spec says: `searchChatMessages.fromMessageId = 1`
   - 0.4.0 has: `scanRequest.setStartPoint(MessageId.oldest())`
   - Both achieve same concept: "Start from oldest message"

### **Issue: Test case fails**

**Solution:**
1. Re-read the "Feature Behavior" section
2. Check "Known Issues" for gotchas
3. Verify all items in "Validation Checklist"
4. Debug with logging
5. If fundamentally broken, ask user for guidance

### **Issue: Can't find where to integrate**

**Solution:**
1. Look at "Integration Points" section in feature doc
2. Use git blame on 0.X.0 files to understand structure
3. Search for similar patterns in 0.X.0 codebase
4. Use codebase_search tool to find relevant code

### **Issue: Feature 05 seems impossible**

**Solution:**
1. This is the most complex feature (1-2 days)
2. Break it down into sub-tasks:
   - Migration first (1 hour)
   - Models next (1 hour)
   - Repository methods (3 hours)
   - Services (4 hours)
   - Refactor AutoDownloadVerticle (6 hours)
   - Testing (4 hours)
3. Don't rush it - this is critical infrastructure

---

## Time Budget Expectations

### **Realistic Timeline:**

- **Day 1**: Features 01, 02, 04 (6-8 hours)
- **Day 2**: Feature 03 + Start Feature 05 (8 hours)
- **Day 3**: Complete Feature 05 + Testing (8 hours)

### **Fast Track** (if 0.X.0 changes are minimal):
- Features 01-04: 6 hours
- Feature 05: 10 hours
- Testing: 2 hours
- **Total**: ~18 hours (2 days)

### **Slow Track** (if 0.X.0 has major changes):
- Features 01-04: 10 hours
- Feature 05: 20 hours (complete rewrite)
- Testing: 6 hours
- **Total**: ~36 hours (4-5 days)

**Budget conservatively. Complex refactors always take longer than estimated.**

---

## Success Criteria

Mark task as complete ONLY when:

1. ✅ All 5 features implemented
2. ✅ All test cases from feature docs pass
3. ✅ No linter/TypeScript errors
4. ✅ Application starts without errors
5. ✅ Main branch updated and pushed
6. ✅ User can verify features in UI
7. ✅ Database queue persists across restart (Feature 05 critical test)

---

## Output Format for User

Provide a summary like this:

```markdown
# Upgrade to v0.X.0 Complete! 🎉

## Summary
- **From**: v0.3.0
- **To**: v0.X.0
- **Upstream commits integrated**: 80
- **Custom features re-implemented**: 5/5
- **Files changed**: 28
- **Lines added**: +2,847
- **Lines removed**: -1,235

## Features Verified ✅
1. ✅ History Cutoff Date - Working
2. ✅ Enhanced Chat Sorting - Working
3. ✅ Chat Progress Badge - Working
4. ✅ Download Oldest First - Working
5. ✅ Database-Driven Queue - Working (persists across restarts)

## Changes from v0.3.0 Implementation
- Feature 01: No changes (same implementation)
- Feature 02: Adapted to new chat API in 0.X.0
- Feature 03: No changes
- Feature 04: Updated to use new scanning service
- Feature 05: Refactored to work with new verticle structure

## Test Results
All test cases from feature specifications: PASSED ✅

## Ready for Production
The fork is now on v0.X.0 with all custom features intact.
```

---

## Additional Context Files

The following files in `features/` directory are your complete specifications:

- `README.md` - System overview
- `UPGRADE_GUIDE.md` - General upgrade instructions
- `EXAMPLE_UPGRADE_0.4.0.md` - Concrete walkthrough example
- `01-history-cutoff-date.md` - Feature 01 complete spec
- `02-enhanced-chat-sorting.md` - Feature 02 complete spec
- `03-chat-download-progress-badge.md` - Feature 03 complete spec
- `04-download-oldest-first.md` - Feature 04 complete spec
- `05-database-driven-download-queue.md` - Feature 05 complete spec

**Read these files for complete implementation guidance.**

---

## Important Reminders

1. **SSH for Git**: Origin uses SSH (git@github.com), not HTTPS
2. **Force Push Required**: After clean slate, you'll need `--force-with-lease`
3. **Backup Exists**: backup-v0.3.0 branch has old implementation if needed
4. **Test Database**: Feature 05 requires database restart test
5. **Time Budget**: Don't rush, budget 2-3 days

---

## End of Instructions

**When you're done, report:**
1. Success summary (version, features, test results)
2. Any implementation differences from specs
3. Any issues encountered
4. Total time taken

Good luck! 🚀

