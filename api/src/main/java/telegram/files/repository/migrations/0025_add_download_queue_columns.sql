-- Migration 0.2.5: Add download queue management columns
-- 
-- Adds:
--   - scan_state: Track discovery/scanning state per chat
--   - download_priority: Priority for queue ordering (higher = more important)
--   - queued_at: Timestamp when file was queued for download
--
-- Adds indexes for efficient queue queries

-- Add new columns
ALTER TABLE file_record 
  ADD COLUMN IF NOT EXISTS scan_state VARCHAR(20) DEFAULT 'idle',
  ADD COLUMN IF NOT EXISTS download_priority INT DEFAULT 0,
  ADD COLUMN IF NOT EXISTS queued_at BIGINT;

-- Add 'queued' to download_status (we'll handle this in code, but document it)
-- Note: download_status is VARCHAR, so 'queued' can be used without schema change
-- Valid values: 'idle', 'queued', 'downloading', 'paused', 'completed', 'error'

-- Create indexes for efficient queue queries
CREATE INDEX IF NOT EXISTS idx_file_record_queue 
  ON file_record(chat_id, download_status, queued_at) 
  WHERE download_status IN ('idle', 'queued');

CREATE INDEX IF NOT EXISTS idx_file_record_scan 
  ON file_record(chat_id, scan_state, message_id);

-- Index for priority-based queue ordering (queued_at is BIGINT, so direct comparison works)
CREATE INDEX IF NOT EXISTS idx_file_record_priority 
  ON file_record(chat_id, download_priority DESC, queued_at ASC NULLS LAST) 
  WHERE download_status IN ('idle', 'queued');

-- Index for finding files ready to download (idle + queued)
CREATE INDEX IF NOT EXISTS idx_file_record_ready_download 
  ON file_record(telegram_id, chat_id, download_status) 
  WHERE download_status IN ('idle', 'queued');

COMMENT ON COLUMN file_record.scan_state IS 'Discovery state: idle, scanning, complete';
COMMENT ON COLUMN file_record.download_priority IS 'Download priority (higher = more important, default 0)';
COMMENT ON COLUMN file_record.queued_at IS 'Timestamp (milliseconds since epoch) when file was queued for download';

