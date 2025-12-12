# Automation State Management Implementation Summary

## Branch
`feature/automation-state-management-fixes`

## Changes Made

### 1. Created AutomationControlState Enum
**File**: `api/src/main/java/telegram/files/repository/AutomationControlState.java`

- New enum with values: STOPPED (0), IDLE (1), ACTIVE (2)
- Validation and auto-correction methods
- Separate from `AutomationState` which tracks completion phases

### 2. Added Control State to Automation Class
**File**: `api/src/main/java/telegram/files/repository/SettingAutoRecords.java`

- Added `controlState` field (int, defaults to -1 for "not set")
- Added helper methods:
  - `getControlState()` - Returns enum, auto-corrects invalid states
  - `setControlState()` - Sets state
  - `isStopped()`, `isActive()` - Convenience methods
  - `validateControlState()` - Validates and auto-corrects

### 3. Added REST API Endpoints
**File**: `api/src/main/java/telegram/files/HttpVerticle.java`

New endpoints:
- `GET /api/automations` - List all automations with states
- `GET /api/automations/:chatId` - Get automation status
- `POST /api/automations/:chatId/start` - Start automation (set to ACTIVE)
- `POST /api/automations/:chatId/stop` - Stop automation (set to STOPPED)
- `POST /api/automations/:chatId/state` - Set state directly (0/1/2)
- `GET /api/automations/:chatId/health` - Get health status with activity tracking

### 4. State Persistence on Startup
**File**: `api/src/main/java/telegram/files/AutomationsHolder.java`

- `init()` method now validates and auto-corrects states on startup
- If `enabled=true` but `state=STOPPED`, auto-transitions to IDLE
- Saves corrected states back to database

### 5. State Synchronization
**File**: `api/src/main/java/telegram/files/AutomationsHolder.java`

- `onAutoRecordsUpdate()` validates and syncs control state with enabled flag
- If `enabled=false`, sets state to STOPPED
- If `enabled=true` and state is STOPPED, sets to IDLE

### 6. Automatic State Transitions
**File**: `api/src/main/java/telegram/files/AutoDownloadVerticle.java`

- Download loop checks control state before processing
- Automatically transitions:
  - IDLE → ACTIVE when pending files are detected
  - ACTIVE → IDLE when no pending files remain
- Only processes automations that are ACTIVE

### 7. Automatic Recovery
**File**: `api/src/main/java/telegram/files/AutoDownloadVerticle.java`

- Periodic validation every 5 minutes
- Auto-recovery: if `enabled=true` but `state=STOPPED`, transitions to IDLE
- Logs all state transitions for debugging

## API Usage Examples

### Start Automation
```bash
curl -X POST http://localhost:8979/api/automations/-1001359914106/start
```

### Stop Automation
```bash
curl -X POST http://localhost:8979/api/automations/-1001359914106/stop
```

### Set State Directly
```bash
curl -X POST http://localhost:8979/api/automations/-1001359914106/state \
  -H "Content-Type: application/json" \
  -d '{"state": 2}'
```

### Get Status
```bash
curl http://localhost:8979/api/automations/-1001359914106
```

### Get Health
```bash
curl http://localhost:8979/api/automations/-1001359914106/health
```

## State Behavior

### State Values
- **0 (STOPPED)**: Automation will not run, even if enabled=true
- **1 (IDLE)**: Automation is enabled but waiting (no pending files)
- **2 (ACTIVE)**: Automation is actively processing files

### State Transitions
- **On Startup**: Invalid states are auto-corrected
- **When Enabled**: If state is STOPPED, auto-transitions to IDLE
- **When Disabled**: State is set to STOPPED
- **During Operation**: 
  - IDLE → ACTIVE when files are pending
  - ACTIVE → IDLE when no files remain

### Validation
- Invalid state values (e.g., 10) are auto-corrected
- State is validated on:
  - Startup
  - Settings update
  - API calls
  - Periodic checks (every 5 minutes)

## Migration Notes

### Database Schema
The `controlState` field is added to the `Automation` JSON in `setting_record` table.

### Backward Compatibility
- Existing automations without `controlState` will default to -1 (not set)
- On first load, `validateControlState()` will set appropriate value based on `enabled` flag
- Legacy `state` field (bitwise completion phases) is preserved

### Migration Path
1. Deploy new code
2. On startup, all automations will be validated and corrected
3. States will be persisted to database automatically

## Testing Checklist

- [ ] Compile Java code
- [ ] Test REST API endpoints
- [ ] Verify state persistence across restarts
- [ ] Test automatic state transitions
- [ ] Test auto-recovery mechanism
- [ ] Verify invalid states are corrected
- [ ] Test state synchronization with enabled flag

## Next Steps

1. Build and test the changes
2. Update Docker image
3. Deploy to production
4. Monitor state transitions in logs
5. Update Python monitoring code to use new API endpoints


