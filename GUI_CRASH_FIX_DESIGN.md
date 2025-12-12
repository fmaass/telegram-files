# Telegram-Files GUI Crash Fix - Design Document

## Problem Analysis

### Current Behavior
1. **Backend**: When a file is marked as `completed` in the database but doesn't exist on disk (because it was moved by post-processing), `TelegramVerticle.loadPreview()` returns a failed future with message "File not found or not downloaded"
2. **Route Handler**: `HttpVerticle.handleFilePreview()` calls `ctx.fail()` which defaults to status code 500
3. **Failure Handler**: Logs the error as SEVERE and returns 500 with JSON error message
4. **Frontend**: Next.js Image component receives 500 error, may not trigger `onError` handler properly, causing unhandled exception → GUI crash

### Root Cause
- **Files Being Moved is NORMAL**: Files are ALWAYS moved out of inbox by post-processing - this is expected behavior, not an error
- **Unnecessary Requests**: Frontend tries to load album art/previews for files that have been moved
- **Wrong HTTP Status Code**: Missing files should return 404 (Not Found), not 500 (Internal Server Error)
- **Log Spam**: 404s for moved files shouldn't be logged prominently (they're normal)
- **No User Control**: Users can't disable album art loading for moved files

### Impact
- GUI crashes when browsing files that have been processed/moved
- 3,248+ files in database marked as completed but missing from disk
- Logs spammed with "File not found" errors for normal condition
- Poor user experience - can't browse file history safely

## Solution Design

### Key Principles
1. **Files being moved is NORMAL** - not an error condition
2. **Prevent unnecessary requests** - don't request files that have been moved
3. **Silent 404s** - log at DEBUG level, not ERROR/SEVERE
4. **User control** - add setting to disable album art for moved files (default: disabled)
5. **User education** - inform users that album art only works if files don't get moved

### Phase 1: Frontend Prevention (Priority: HIGHEST)

#### 1.1 Add Setting for Album Art for Moved Files

**File**: `web/src/lib/types.ts`

**Add to SettingKeys**:
```typescript
export const SettingKeys = [
  "uniqueOnly",
  "imageLoadSize",
  "alwaysHide",
  "showSensitiveContent",
  "autoDownloadLimit",
  "autoDownloadTimeLimited",
  "proxys",
  "avgSpeedInterval",
  "tags",
  "showAlbumArtForMovedFiles", // NEW
] as const;
```

**File**: `web/src/components/settings-form.tsx`

**Add setting UI**:
```typescript
<div
  className="flex w-full cursor-pointer flex-col space-y-4 rounded-md border p-4 shadow"
  onClick={(event) => handleSwitchChange("showAlbumArtForMovedFiles", event)}
>
  <div className="flex items-center justify-between">
    <Label>Show Album Art for Moved Files</Label>
    <Switch
      id="show-album-art-moved"
      checked={settings?.showAlbumArtForMovedFiles === "true"}
      onCheckedChange={() => handleSwitchChange("showAlbumArtForMovedFiles")}
    />
  </div>
  <p className="text-xs text-muted-foreground">
    Enable to show album art/previews for files that have been moved by post-processing.
    <br />
    <strong>Note:</strong> Files are automatically moved out of the inbox after download.
    Album art will only work if files haven't been moved yet.
    <br />
    <strong>Default:</strong> Disabled (recommended to prevent errors)
  </p>
</div>
```

#### 1.2 Prevent Requests for Moved Files in Frontend

**File**: `web/src/components/file-image.tsx`

**Current Code** (line ~148-158):
```typescript
// 已下载的文件
if (
  file.localPath &&
  (file.type === "photo" || file.mimeType?.startsWith("image/"))
) {
  if (file.extra?.width && file.extra?.height) {
    return renderImage(file.extra.width, file.extra.height, file.uniqueId);
  } else {
    return renderImage(600, 600, file.uniqueId);
  }
}
```

**Proposed Fix**:
```typescript
import { useSettings } from "@/hooks/use-settings";

export default function FilePreview({ ... }) {
  const { settings } = useSettings();
  const showAlbumArtForMovedFiles = settings?.showAlbumArtForMovedFiles === "true";
  
  // Check if file has been moved (downloadStatus = "processed" or file doesn't exist)
  const isFileMoved = file.downloadStatus === "processed" || 
                      (file.downloadStatus === "completed" && !file.localPath);
  
  // 已下载的文件
  if (
    file.localPath &&
    (file.type === "photo" || file.mimeType?.startsWith("image/")) &&
    // Don't try to load if file is moved and setting is disabled
    (!isFileMoved || showAlbumArtForMovedFiles)
  ) {
    if (file.extra?.width && file.extra?.height) {
      return renderImage(file.extra.width, file.extra.height, file.uniqueId);
    } else {
      return renderImage(600, 600, file.uniqueId);
    }
  }
  
  // If file is moved and album art is disabled, show fallback
  if (isFileMoved && !showAlbumArtForMovedFiles) {
    // Show thumbnail if available, otherwise show icon
    if (file.thumbnail) {
      return renderImage(isFullPreview ? 600 : 32, isFullPreview ? 600 : 32, "");
    }
    return renderFileIcon();
  }
  
  // ... rest of code
}
```

### Phase 2: Backend Silent 404 (Priority: HIGH)

#### 2.1 Return 404 Silently (No Error Logging)

**File**: `api/src/main/java/telegram/files/HttpVerticle.java`

**Current Code** (line ~595-604):
```java
telegramVerticle.loadPreview(uniqueId)
    .onSuccess(tuple -> {
        String mimeType = tuple.v2;
        if (StrUtil.isBlank(mimeType)) {
            mimeType = FileUtil.getMimeType(tuple.v1);
        }
        fileRouteHandler.handle(ctx, tuple.v1, mimeType);
    })
    .onFailure(ctx::fail);  // ❌ Defaults to 500, logs as ERROR
```

**Proposed Fix**:
```java
telegramVerticle.loadPreview(uniqueId)
    .onSuccess(tuple -> {
        String mimeType = tuple.v2;
        if (StrUtil.isBlank(mimeType)) {
            mimeType = FileUtil.getMimeType(tuple.v1);
        }
        fileRouteHandler.handle(ctx, tuple.v1, mimeType);
    })
    .onFailure(throwable -> {
        // Check if it's a "file not found" error (normal condition - file was moved)
        String message = throwable.getMessage();
        if (message != null && message.contains("File not found")) {
            // Return 404 silently (log at DEBUG level only)
            if (log.isDebugEnabled()) {
                log.debug("File not found (likely moved): %s".formatted(uniqueId));
            }
            ctx.response()
                .setStatusCode(404)
                .putHeader("Content-Type", "application/json")
                .end(JsonObject.of(
                    "error", "File not found",
                    "uniqueId", uniqueId,
                    "reason", "File has been moved by post-processing"
                ).encode());
        } else {
            // Other errors are real server errors (500)
            ctx.fail(throwable);
        }
    });
```

#### 2.2 Keep TelegramVerticle Simple (No Changes Needed)

**File**: `api/src/main/java/telegram/files/TelegramVerticle.java`

**Keep Current Code** (line ~292-302):
```java
public Future<Tuple2<String, String>> loadPreview(String uniqueId) {
    return DataVerticle.fileRepository
            .getByUniqueId(uniqueId)
            .compose(fileRecord -> {
                if (fileRecord == null || !fileRecord.isDownloadStatus(FileRecord.DownloadStatus.completed)
                    || !FileUtil.exist(fileRecord.localPath())) {
                    return Future.failedFuture("File not found or not downloaded");
                }
                return Future.succeededFuture(Tuple.tuple(fileRecord.localPath(), fileRecord.mimeType()));
            });
}
```

**No changes needed** - the route handler will catch and handle appropriately.

### Phase 3: Improve Image Error Handling (Optional but Recommended)

#### 3.1 Better Error Handling in Image Component

**File**: `web/src/components/file-image.tsx`

**Current Code** (line ~97-108):
```typescript
const handleError = () => {
  setError(true);
};
```

**Proposed Fix**:
```typescript
const handleError = (error?: Error | React.SyntheticEvent<HTMLImageElement, Event>) => {
  // Silently handle errors for moved files (expected condition)
  if (isFileMoved) {
    setError(true);
    return;
  }
  // Log other errors for debugging
  console.warn('Failed to load file preview:', file.uniqueId, error);
  setError(true);
};
```

### Phase 4: API Response Enhancement (Optional)

#### 4.1 Add File Status to API Response

**File**: `api/src/main/java/telegram/files/HttpVerticle.java` (handleFiles method)

**Proposed Enhancement**:
```java
// When returning file list, optionally include file existence status
// This allows frontend to filter without making individual requests
// Add query parameter: ?includeFileStatus=true
// Response includes: { "fileExists": true/false }
```

**Note**: This is optional - the frontend prevention (Phase 1) should be sufficient.

## Implementation Plan

### Step 1: Frontend Prevention (Priority: HIGHEST)
1. ✅ Add `showAlbumArtForMovedFiles` to `SettingKeys` in `types.ts`
2. ✅ Add setting UI in `settings-form.tsx` with helpful description
3. ✅ Update `file-image.tsx` to check setting and file status before requesting
4. ✅ Prevent requests for moved files when setting is disabled (default)
5. ✅ Test that no requests are made for moved files

### Step 2: Backend Silent 404 (Priority: HIGH)
1. ✅ Update `HttpVerticle.handleFilePreview()` to return 404 silently
2. ✅ Log at DEBUG level only (not ERROR/SEVERE)
3. ✅ Test that 404s don't spam logs

### Step 3: Testing (Priority: HIGH)
1. ✅ Test with files that exist (should work normally)
2. ✅ Test with files marked completed but missing (should not request if setting disabled)
3. ✅ Test with setting enabled (should request but handle 404 gracefully)
4. ✅ Test with setting disabled (should not request at all)
5. ✅ Verify GUI no longer crashes
6. ✅ Verify logs are not spammed with errors

### Step 4: Documentation (Priority: MEDIUM)
1. ✅ Document the setting and its purpose
2. ✅ Add helpful tooltip/description in UI
3. ✅ Update README if needed

## Testing Strategy

### Test Cases

1. **File exists and is completed**
   - Expected: File preview loads successfully
   - Status code: 200

2. **File marked completed but missing from disk**
   - Expected: 404 response, GUI shows fallback icon
   - Status code: 404
   - No crash

3. **File not in database**
   - Expected: 404 response
   - Status code: 404

4. **File still downloading**
   - Expected: Appropriate error (not 404)
   - Status code: 400 or 503

5. **Server error (e.g., database connection lost)**
   - Expected: 500 response
   - Status code: 500

### Manual Testing Steps

```bash
# 1. Find a file that's marked completed but missing
docker exec telegram-files-cleanup psql -h postgres-proxy -p 5454 -U telegram_user -d telegram_files_mac -c "
SELECT unique_id, file_name, download_status, local_path 
FROM file_record 
WHERE download_status = 'completed' 
LIMIT 5;
"

# 2. Check if file exists
ls -la /path/to/file

# 3. Test API endpoint
curl -v http://localhost:8979/api/{telegramId}/file/{uniqueId}

# 4. Verify response is 404 (not 500)
# 5. Open GUI and verify it doesn't crash
```

## Risk Assessment

### Low Risk
- Changing error status code from 500 to 404 for missing files
- Adding error handling in route handler
- Frontend already has error handling, just needs to work properly

### Medium Risk
- Custom exception classes (need to ensure they're properly serialized)
- Need to verify all call sites handle the new exception type

### Mitigation
- Keep backward compatibility where possible
- Add comprehensive tests
- Gradual rollout (test in dev first)

## Success Criteria

1. ✅ GUI no longer crashes when browsing files
2. ✅ No unnecessary requests for moved files (when setting disabled)
3. ✅ Missing files return 404 silently (not 500, not logged as ERROR)
4. ✅ Setting defaults to disabled (recommended)
5. ✅ Users understand why album art doesn't work for moved files
6. ✅ No regression in existing functionality
7. ✅ Performance is not impacted
8. ✅ Logs are not spammed with "file not found" errors

## Future Enhancements

1. **File Status Endpoint**: Add `/api/files/{uniqueId}/status` to check file status without trying to load it
2. **Bulk Status Check**: Add endpoint to check multiple files at once
3. **Auto-cleanup**: Periodically mark missing completed files as processed
4. **File Path Validation**: Validate file paths when marking as completed

## Related Files

- `api/src/main/java/telegram/files/TelegramVerticle.java` - Main file loading logic
- `api/src/main/java/telegram/files/HttpVerticle.java` - HTTP route handlers
- `api/src/main/java/telegram/files/FileRouteHandler.java` - File serving logic
- `web/src/components/file-image.tsx` - Frontend image component
- `web/src/lib/api.ts` - API client

## Notes

- The fix is backward compatible - existing working files continue to work
- The fix only changes error handling for missing files
- No database schema changes required
- No breaking API changes (just better error codes)

