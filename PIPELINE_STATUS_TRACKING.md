# Pipeline Status Tracking Implementation

## Overview

Implemented a comprehensive status tracking system for files moving through the complete processing pipeline from Telegram download to iTunes import.

## Status Flow

```
idle → downloading → completed → processed → imported
                         ↓
                       error
```

### Status Definitions

1. **idle**: File discovered in Telegram, not downloaded yet
2. **downloading**: File is actively being downloaded by telegram-files
3. **completed**: File downloaded to telegram inbox (ready for post-processing)
4. **processed**: File moved out of inbox by telegram-postproc (in work directory)
5. **imported**: File successfully imported into iTunes/Music library
6. **error**: Download or processing error occurred

## Implementation Details

### 1. telegram-files (Java/Vert.x)

**File**: `api/src/main/java/telegram/files/repository/FileRecord.java`

- Updated `DownloadStatus` enum to include `processed` and `imported` statuses
- Removed dependency on `trackDownloadedState` setting
- Files are marked as `completed` when downloaded to inbox
- External services handle transitions to `processed` and `imported`

**Changes**:
```java
public enum DownloadStatus {
    idle, downloading, paused, completed, processed, imported, error
}
```

### 2. telegram-postproc (Python)

**New Module**: `telegram_status_updater.py`

Provides functions to update telegram-files database status:

- `mark_as_processed(local_path)` - Mark file as processed when moved from inbox
- `mark_as_imported(local_path)` - Mark file as imported after iTunes import
- `update_status_by_filename(filename, status)` - Update by filename when path unknown
- `get_file_status(local_path)` - Query current status
- `get_status_counts()` - Get statistics by status

**CLI Usage**:
```bash
python3 telegram_status_updater.py counts                    # Show status distribution
python3 telegram_status_updater.py get-status /path/to/file  # Check file status
python3 telegram_status_updater.py mark-processed /path      # Mark as processed
python3 telegram_status_updater.py mark-imported /path       # Mark as imported
```

**Integration**: `move_track.py`

- Automatically marks files as `processed` after successfully moving from inbox to work directory
- Non-fatal: If update fails, file processing continues (logged as debug)
- Uses original source path to find record in telegram database

**File**: `move_track.py` (line ~893)
```python
# Update telegram-files status to 'processed' (file moved out of inbox)
if TELEGRAM_STATUS_ENABLED:
    try:
        success = mark_as_processed(str(src))
        if success:
            log.debug("Updated telegram-files status to 'processed' for: %s", src.name)
    except Exception as e:
        log.debug("Failed to update telegram-files status (non-fatal): %s", e)
```

### 3. iTunes Import Tracking (macOS Host)

**New Script**: `music-cleaner/scripts/host/mark_telegram_imported.py`

Marks files as imported after successful iTunes import.

**Usage**:
```bash
# Mark single file
python3 mark_telegram_imported.py /path/to/file.flac

# Mark entire album
python3 mark_telegram_imported.py --album /path/to/album/

# Mark by filename (when path unavailable)
python3 mark_telegram_imported.py --filename "Track Name.flac"
```

**Integration**: Can be called from maintenance_worker.py after successful import tasks

## Database Schema

The existing `file_record` table already supports the new statuses via its `VARCHAR(255)` column:

```sql
download_status VARCHAR(255)  -- Values: idle, downloading, completed, processed, imported, error
```

No schema migration needed - the enum values are stored as strings.

## Testing Results

✅ **Status Transitions Tested**:
- `completed` → `processed` (via SQL update)
- `processed` → `imported` (via SQL update)
- Web UI accessible and showing files with new statuses

✅ **Final Status Distribution** (after testing):
```
completed   : 11,623 files
downloading :     52 files
idle        : 44,340 files
processed   :      0 files (test file moved to imported)
imported    :      1 file (test file)
```

## Benefits

1. **Full Pipeline Visibility**: Track files from Telegram discovery to iTunes import
2. **Automatic Updates**: telegram-postproc automatically marks files as processed
3. **Audit Trail**: Know which files completed the full pipeline
4. **Prevent Re-downloads**: Distinguish between files that need downloading vs already imported
5. **Metrics**: Can report on pipeline efficiency and bottlenecks
6. **Debug Support**: Quickly identify where files are stuck in the pipeline

## Configuration

No configuration required. The system automatically:
- Uses postgres-proxy connection from environment variables
- Falls back gracefully if status updates fail (non-fatal)
- Works with existing database schema

## Monitoring

Check status distribution:
```bash
# From telegram-postproc container
docker exec telegram-postproc python3 /opt/postprocess/telegram_status_updater.py counts

# From database directly
docker exec telegram-files-cleanup psql -U telegram_user -d telegram_files_mac \
  -c "SELECT download_status, COUNT(*) FROM file_record GROUP BY download_status;"
```

## Future Enhancements

Potential improvements:
1. Add timestamp columns for each status transition
2. Create database views for pipeline analytics
3. Add Prometheus metrics for status transitions
4. Build web dashboard showing pipeline flow rates
5. Automate import tracking by hooking into maintenance_worker

## Git Branch

Branch: `fix/code-review-improvements`
Commit: Contains the full pipeline status tracking implementation

## Related Files

**telegram-files**:
- `api/src/main/java/telegram/files/repository/FileRecord.java`
- `api/src/main/java/telegram/files/TelegramVerticle.java`

**telegram-postproc**:
- `telegram_status_updater.py` (new)
- `move_track.py` (updated)

**music-cleaner**:
- `scripts/host/mark_telegram_imported.py` (new)
