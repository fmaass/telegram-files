# Feature-Based Upgrade Guide

## Quick Reference: Upgrading to New Upstream Versions

This guide shows how to use the feature specifications in this directory to cleanly upgrade to new upstream versions.

---

## Two Approaches

### Approach A: Rebase (Incremental Upgrades)
**Use when**: Upstream changes are minor (< 50 commits)

```bash
# 1. Backup
git branch backup-before-upgrade main

# 2. Rebase
git rebase upstream/main

# 3. Resolve conflicts using feature docs as reference
# 4. Test each feature
# 5. Push
git push --force-with-lease origin main
```

**Pros**: Keeps commit history, faster  
**Cons**: Conflicts can be complex

---

### Approach B: Clean Slate (Major Upgrades) ⭐ RECOMMENDED
**Use when**: Upstream has major changes (50+ commits, breaking changes, or architecture changes)

```bash
# Step 1: Start fresh from upstream
git checkout -b main-v0.X.0 upstream/main

# Step 2: Implement features one-by-one
# Read: features/01-history-cutoff-date.md
# Implement according to spec
git add -A
git commit -m "feat: add history cutoff date"

# Read: features/02-enhanced-chat-sorting.md
# Implement according to spec
git add -A
git commit -m "feat: add enhanced chat sorting"

# Repeat for all features...

# Step 3: Test thoroughly
# Run test suite
# Manual testing of each feature

# Step 4: Replace main
git branch -D main
git branch -m main-v0.X.0 main
git push --force-with-lease origin main
```

**Pros**: Clean implementation on new architecture  
**Cons**: More time-consuming

---

## Feature Implementation Order

**Recommended order** (least dependencies first):

1. ✅ **Feature 01: History Cutoff Date** (2-3 hours)
   - No dependencies
   - Simple to implement
   - Test: Date filtering works

2. ✅ **Feature 02: Enhanced Chat Sorting** (2-3 hours)
   - No dependencies
   - Requires statistics method
   - Test: Automated chats appear first

3. ✅ **Feature 04: Download Oldest First** (1-2 hours)
   - Depends on: History scanning exists
   - Test: Files download in correct order

4. ✅ **Feature 03: Chat Download Progress Badge** (2-3 hours)
   - Depends on: Statistics endpoint (from Feature 02)
   - Test: Badge shows and updates

5. ⚠️ **Feature 05: Database Queue** (1-2 days)
   - Most complex
   - Major refactor
   - Test: Queue persists across restarts

**Total time estimate**: 2-3 days for all features

---

## Quick Reference During Implementation

### For Each Feature:

1. **Read the spec** (e.g., `features/01-history-cutoff-date.md`)
2. **Check "Implementation Sketch"** section
3. **Follow "Integration Points"** checklist
4. **Use "Code Examples"** as reference (adapt to current code)
5. **Run "Test Cases"** to verify
6. **Check "Validation Checklist"**

### Don't Copy-Paste Blindly!

The code examples are **sketches**, not production code. Adapt them to:
- Current variable names
- Current class structure
- Current coding style
- Current error handling patterns

---

## Conflict Resolution Strategy

If you hit conflicts during rebase:

1. **Open relevant feature doc**
   - Example: Conflict in `AutoDownloadVerticle.java` → Open `04-download-oldest-first.md`

2. **Understand intent**
   - Read "Overview" and "Feature Behavior"
   - Understand what the code is trying to achieve

3. **Resolve conflict**
   - Keep upstream structure
   - Re-implement your feature on top
   - Use "Implementation Sketch" as guide

4. **Test immediately**
   - Run test case from feature doc
   - Verify feature still works

---

## Testing Strategy

After implementing all features:

### Smoke Tests (Quick)
```bash
# 1. Application starts without errors
docker-compose up -d

# 2. View chat with files
# Check: Badge appears

# 3. Enable automation with history cutoff
# Check: Only recent files queue

# 4. Toggle download oldest first
# Check: Order changes

# 5. Restart application
# Check: Queue persists
```

### Full Test Suite (Thorough)
```bash
# Run each "Test Case" section from each feature doc
# Document results
# Fix any failures
```

---

## Verification Checklist

After upgrade, verify **ALL** features:

### Feature 01: History Cutoff Date
- [ ] Backend field exists (Integer historySince)
- [ ] Frontend date picker works
- [ ] Files after cutoff date are downloaded
- [ ] Files before cutoff date are skipped
- [ ] Statistics respect cutoff

### Feature 02: Enhanced Chat Sorting
- [ ] Automated chats appear at top
- [ ] Statistics show breakdown by status
- [ ] Activated chat is sticky first

### Feature 03: Chat Download Progress Badge
- [ ] Badge renders in header
- [ ] Shows "X/Y" format
- [ ] Tooltip shows details
- [ ] Updates every 30 seconds
- [ ] Hides when no files

### Feature 04: Download Oldest First
- [ ] Toggle in automation form
- [ ] Backend uses correct fromMessageId and offset
- [ ] Files download in selected order
- [ ] Works with downloadOldestFirst=true and false

### Feature 05: Database Queue
- [ ] Migration applied (3 columns, 4 indexes)
- [ ] DownloadQueueService exists and works
- [ ] AutomationState enum exists
- [ ] Queue persists across restarts
- [ ] Ordering works (priority, oldest/newest)
- [ ] No memory queue remaining

---

## Example: Upgrading from 0.3.0 to 0.4.0

```bash
# Assumption: Upstream releases 0.4.0 with 50 new commits

# Step 1: Clean slate
git checkout -b main-v0.4.0 upstream/main
# You now have vanilla 0.4.0, zero customizations

# Step 2: Read feature list
cat features/README.md
# 5 features to implement

# Step 3: Implement features (use docs as guide)
# Feature 01 (2 hours)
# Feature 02 (2 hours)
# Feature 04 (1 hour)
# Feature 03 (2 hours)
# Feature 05 (12 hours)
# Total: ~2 days

# Step 4: Test everything
# Run all test cases from feature docs
# Verify each feature works

# Step 5: Replace main
git branch -D main
git branch -m main-v0.4.0 main

# Step 6: Push
git push --force-with-lease origin main

# Step 7: Update feature docs if needed
# If implementation changed significantly, update the docs
```

---

## When to Update Feature Docs

Update feature documentation when:

1. **Architecture changes** - Feature now works differently
2. **API changes** - Integration points have changed
3. **Better implementation found** - Update "Code Examples"
4. **Known issues discovered** - Add to "Known Issues" section
5. **Breaking changes** - Update "Dependencies" section

**Don't update** for minor syntax changes or variable renames.

---

## Maintenance Strategy

### Monthly Review
- Check upstream for new releases
- Decide: Rebase or clean slate?
- Budget time based on upstream changes

### After Each Upgrade
- Review each feature doc
- Update if implementation changed significantly
- Add any new gotchas to "Known Issues"

### Feature Tracking
- Keep features/README.md up to date
- Mark deprecated features
- Add new custom features as they're developed

---

## Tips for Success

1. **Implement one feature at a time** - Don't do all 5 at once
2. **Test after each feature** - Catch bugs early
3. **Commit after each feature** - Clean history
4. **Use feature docs as checklist** - Don't skip validation
5. **Budget more time than estimated** - Complex features always take longer
6. **Keep notes** - Document any deviations from spec

---

## FAQ

### Q: Can I skip a feature?
**A**: Yes! Features are independent. If you don't need Feature 03 (badge), skip it.

### Q: Can I change implementation approach?
**A**: Absolutely! The specs describe behavior, not implementation. Use your judgment.

### Q: What if upstream implements similar feature?
**A**: Compare approaches. If upstream's is better, use theirs. If yours is better, keep yours.

### Q: Should I contribute features to upstream?
**A**: Consider it! But architectural features (like database queue) may be too opinionated. Bug fixes and small improvements are easier to contribute.

### Q: How do I add a new custom feature?
**A**: Create a new feature doc (e.g., `06-my-new-feature.md`) following the template. Update features/README.md.

---

## Success Metrics

After upgrade, you should have:
- ✅ Latest upstream version
- ✅ All custom features working
- ✅ No regressions
- ✅ Clean commit history
- ✅ All tests passing
- ✅ Feature docs updated (if needed)

**Time budget**: 2-3 days for major upgrade (0.3 → 0.4)  
**Time budget**: 4-6 hours for minor upgrade (0.3.0 → 0.3.5)

---

## Getting Help

If stuck during upgrade:
1. Read the feature doc again (especially "Known Issues")
2. Check git blame on the file to see original implementation
3. Look at commit history: `git log -p --all -- <filename>`
4. Reach out to AI assistant with specific feature doc as context

**Remember**: Feature docs are guides, not gospel. Adapt to your situation!

