# Custom Features Documentation

This directory contains **standalone feature specifications** for all custom features added to this fork.

## Purpose

These documents allow you to:
1. **Upgrade cleanly** - Reset to any upstream version without carrying old code
2. **Re-implement easily** - Apply features to new codebase from scratch
3. **Track features** - Know what custom functionality exists
4. **Maintain independently** - Each feature is self-contained

## How to Use During Upgrades

### Standard Upgrade Process (with features)
```bash
# 1. Backup current main
git branch backup-before-upgrade main

# 2. Rebase onto new upstream
git rebase upstream/main

# 3. Resolve conflicts one-by-one
```

### Clean Slate Upgrade Process (recommended for major versions)
```bash
# 1. Reset to upstream version (forget all changes)
git checkout -b main-v0.4.0 upstream/main

# 2. Apply each feature one-by-one from specs
# Read features/01-history-cutoff-date.md
# Implement according to spec
# Test
# Commit

# 3. Repeat for each feature

# 4. Replace main
git branch -D main
git branch -m main-v0.4.0 main
```

## Feature List

| ID | Feature | Status | Complexity | Dependencies |
|----|---------|--------|------------|--------------|
| 01 | History Cutoff Date | ✅ Active | Low | None |
| 02 | Enhanced Chat Sorting | ✅ Active | Low | Statistics |
| 03 | Chat Download Progress Badge | ✅ Active | Medium | Statistics endpoint |
| 04 | Download Oldest First | ✅ Active | Medium | History scanning |
| 05 | Database-Driven Download Queue | ✅ Active | High | Database schema |

## Document Structure

Each feature document contains:
- **Overview**: What the feature does
- **User Story**: Why it was added
- **Technical Approach**: High-level implementation strategy
- **Code Sketches**: Pseudo-code and key concepts
- **Integration Points**: Where changes are needed
- **Test Cases**: How to verify it works
- **Known Issues**: Gotchas and considerations

## Notes

- Code sketches are **not exact implementations** - they're conceptual guides
- Actual implementation will vary based on current codebase structure
- Focus on **intent and behavior**, not specific syntax
- Each feature can be implemented independently

