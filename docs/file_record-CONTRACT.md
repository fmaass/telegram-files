# `file_record` Contract (v1)

**Status:** Frozen v1. This phase makes **no schema change** — it documents and guards the
existing `file_record` contract so a future migration cannot silently break an external consumer.

The `file_record` table is a **shared contract**. It is the primary interface between
`telegram-files` (Java/Vert.x, this repo) and the `telegram-postproc` pipeline
(`~/projects/music-processor/telegram-postproc/`). Three-plus external services **read AND write**
`file_record` directly over the same PostgreSQL 17 database (`telegram_files_mac` on Saturn:5455).

Authoritative schema: `api/src/main/java/telegram/files/repository/FileRecord.java`
(`SCHEME`, `INDEXES`, `MIGRATIONS`, `DownloadStatus`/`TransferStatus` enums, `canTransitionTo`).
Machine-checkable manifest: `api/src/test/java/telegram/files/FileRecordContract.java`.
Guard tests: `FileRecordContractGuardTest` (schema/PK) + `FileRecordContractBehaviorTest`
(values/nullability/units/ownership), both on the real-Postgres `pgIntegrationTest` harness.

> **Change protocol.** Any change to a column name, type, the primary key, the `download_status`
> enum, or a timestamp unit is a **breaking change to external consumers**. It requires updating
> BOTH `FileRecord.java` and `FileRecordContract.java` in lock-step, updating this document, and
> grepping/patching every external consumer listed below. The contract-guard tests fail CI if the
> live migrated schema drifts from the manifest.

---

## 1. Column manifest (29 columns, PK = `unique_id`)

Types are the PostgreSQL `information_schema.columns.data_type` the SQL type resolves to
(VARCHAR → `character varying`, INT → `integer`, BIGINT → `bigint`, BOOLEAN → `boolean`).

**Owner** legend:
- **tf** — telegram-files owns/writes it (external services treat as read-only).
- **ext** — external telegram-postproc services own the writes.
- **shared** — both systems write it (see §4 write-ownership rules; these are the boundary columns).

| # | Column | PG type | SQL type | Nullable | Owner | Notes |
|---|--------|---------|----------|----------|-------|-------|
| 1 | `id` | integer | INT | yes | tf | Telegram file id; changes over time. Not the PK. |
| 2 | `unique_id` | character varying | VARCHAR(255) | no (PK) | tf | **PRIMARY KEY.** Stable Telegram unique id. External UPDATEs key off this / `file_name`+`size`. |
| 3 | `telegram_id` | bigint | BIGINT | yes | tf | Account id. |
| 4 | `chat_id` | bigint | BIGINT | yes | tf | **Can be large NEGATIVE** for groups/supergroups/channels. |
| 5 | `message_id` | bigint | BIGINT | yes | tf | |
| 6 | `media_album_id` | bigint | BIGINT | yes | tf | |
| 7 | `date` | integer | INT | yes | tf | Telegram upload time — **epoch SECONDS** (see §3). |
| 8 | `has_sensitive_content` | boolean | BOOLEAN | yes | tf | |
| 9 | `size` | bigint | BIGINT | yes | tf | File size in bytes. External UPDATEs match on `file_name`+`size`. |
| 10 | `downloaded_size` | bigint | BIGINT | yes | **shared** | tf writes during download; external reset-stuck logic sets it to `0` (§4). |
| 11 | `type` | character varying | VARCHAR(255) | yes | tf | `thumbnail`\|`photo`\|`video`\|`audio`\|`file`. Nearly all external filters use `type IN ('audio','file')`. |
| 12 | `mime_type` | character varying | VARCHAR(255) | yes | tf | |
| 13 | `file_name` | character varying | VARCHAR(255) | yes | tf | External UPDATEs/joins match on this (full, `LEFT(...,60)`, or stem via `regexp_replace`). |
| 14 | `thumbnail` | character varying | VARCHAR(2056) | yes | tf | |
| 15 | `thumbnail_unique_id` | character varying | VARCHAR(255) | yes | tf | |
| 16 | `caption` | character varying | VARCHAR(4096) | yes | tf | |
| 17 | `extra` | character varying | VARCHAR(4096) | yes | tf | |
| 18 | `local_path` | character varying | VARCHAR(1024) | yes | **shared** | tf sets it on download; external readers guard `IS NULL OR = ''`. |
| 19 | `download_status` | character varying | VARCHAR(255) | yes | **shared** | Core status. tf owns up to `completed`; ext owns `processed`/`imported` and reset→`idle` (§2, §4). |
| 20 | `transfer_status` | character varying | VARCHAR(255) | yes | tf | No external consumer in this audit reads or writes it. |
| 21 | `start_date` | bigint | BIGINT | yes | **shared** | tf sets at download start; external reset-stuck sets NULL (§4). **Epoch MILLISECONDS** (§3). |
| 22 | `completion_date` | bigint | BIGINT | yes | **shared** | tf sets on download complete; external `processed` writers overwrite with processing-time millis (§4). **Epoch MILLISECONDS** (§3). |
| 23 | `tags` | character varying | VARCHAR(2056) | yes | tf | No external consumer in this audit touches it. |
| 24 | `thread_chat_id` | bigint | BIGINT | yes | tf | |
| 25 | `message_thread_id` | bigint | BIGINT | yes | tf | **Read by** `finalize_manifest.sh:209,222`. |
| 26 | `reaction_count` | bigint | BIGINT (DEFAULT 0) | yes | tf | |
| 27 | `scan_state` | character varying | VARCHAR(20) (DEFAULT 'idle') | yes | tf | Discovery lifecycle. No external consumer reads/writes it. |
| 28 | `download_priority` | integer | INT (DEFAULT 0) | yes | tf | |
| 29 | `queued_at` | bigint | BIGINT | yes | tf | Set to `System.currentTimeMillis()` when queued. **Epoch MILLISECONDS** (§3). |

Primary key: **`unique_id`** (single column). Set by migration `0.3.3` (was `id` pre-0.3.3).
`FileRecordContractGuardTest.fileRecordPrimaryKeyMatchesContract` asserts this exactly.

---

## 2. Status-value ownership rules

### `download_status` — canonical enum
`{ idle, downloading, paused, completed, processed, imported, error }`
(`FileRecord.DownloadStatus`). `isTerminal()` ⇒ `completed | processed | imported | error`.

Transition table (`canTransitionTo`), abbreviated:
`idle → downloading|error`; `downloading → paused|completed|error|idle`;
`paused → downloading|idle|error`; `completed → processed|error`;
`processed → imported`; `imported → (dead end)`; `error → idle|downloading`.

**Ownership:**
- **telegram-files** drives the download lifecycle up to and including **`completed`**
  (`idle`→`downloading`→`completed`, plus `paused`/`error`).
- **External services** own the post-download transitions **`completed → processed → imported`**,
  written AFTER the file is physically moved out of the inbox
  (`telegram_status_updater.py`, `file_processor_worker.py`, the backfills).
- **NEVER-DOWNGRADE RULE (load-bearing):** once an external service sets `processed` or `imported`,
  telegram-files MUST NOT overwrite it — not even after a container restart when TDLib's local cache
  is gone and it would otherwise report the file as `idle`. Enforced in
  `TelegramVerticle.updateFile` (worktree `TelegramVerticle.java:1146-1148`, `1142-1145` comment)
  and `:411-415`. The DB status is authoritative over TDLib's view.
  `EXTERNAL_TERMINAL_STATUSES = {processed, imported}` in the manifest pins this set.

### `transfer_status`
`{ idle, transferring, completed, error }` (`FileRecord.TransferStatus`). telegram-files-owned; no
external consumer in this audit references it.

### `scan_state`
`{ idle, scanning, complete }` (no enum class). Its production source of truth is the state-contract
comment on the `FileRecord.scanState` field (`FileRecord.java:41`:
`// Discovery state: 'idle', 'scanning', 'complete'`); at runtime only the `'idle'` literal is
assigned/compared and the schema DEFAULT is `'idle'`. telegram-files-owned.
`FileRecordContractBehaviorTest.scanStateValuesMatchProductionSource` parses that production comment
and asserts the manifest equals it exactly (adding a value in production fails CI until the manifest
and this doc are updated); `scanStateColumnDefaultIsIdle` pins the live column DEFAULT independently.

### Phantom value `'downloaded'` (see Finding A)
Several external READERS filter `download_status IN (... 'downloaded' ...)` but **no writer ever
emits `'downloaded'`, and it is NOT in the canonical enum.** telegram-files tops out at `completed`.
These reader branches are therefore effectively dead against current telegram-files behavior.
Pinned by `FileRecordContractBehaviorTest.downloadedIsPhantomReadOnlyStatus`: if telegram-files ever
starts emitting `'downloaded'`, that test breaks and forces reconciliation.
Read sites: `monitoring_dashboard.py:86`, `unified_dashboard.py:169`, `pipeline_health_checker.py:131`,
`telegram_health_monitor.py:143,156,205`.

---

## 3. Timestamp units (pinned)

| Column | Type | Unit | Evidence |
|--------|------|------|----------|
| `date` | INT | Telegram **epoch SECONDS** | tf reads `date * 1000L` to get millis (`TelegramVerticle.java:1355`); external readers use `date/1000` before comparing to epoch seconds (`telegram_health_monitor.py:189`). |
| `start_date` | BIGINT | **epoch MILLISECONDS** | External reset-stuck compares `start_date/1000 < EXTRACT(epoch …)` (`reset_stuck_downloads.sh:37`); health monitor uses `int((now-2h).timestamp()*1000)` threshold (`telegram_health_monitor.py:173`). |
| `completion_date` | BIGINT | **epoch MILLISECONDS** | tf sets `System.currentTimeMillis()` (`TelegramVerticle.java:1133`); external writers set `int(time.time()*1000)` (`file_processor_worker.py:594`); readers use `completion_date/1000`. |
| `queued_at` | BIGINT | **epoch MILLISECONDS** | tf sets `System.currentTimeMillis()` (`AutoDownloadVerticle.java:95,239`; `FileRepositoryImpl.java:985,1042`). |

The millis columns are BIGINT because epoch-millis overflows INT; `date` fits in INT as seconds.
`FileRecordContractBehaviorTest.timestampUnitsArePinned` guards these widths as the unit contract,
and `FileRecordContractGuardTest` asserts the exact PG types. A future width/type change flips the
unit contract across every external reader and MUST NOT ship silently.

---

## 4. Write-ownership fixtures (which columns each side may write)

telegram-files must never overwrite an external-owned status once set (§2 never-downgrade).
Conversely, external services write three telegram-files-lifecycle columns in specific,
**intentional** flows (the "shared" rows above). These are documented so a future reviewer does not
treat them as contract violations:

1. **External `processed` writers overwrite `completion_date`** with *processing* time (not download
   time): `file_processor_worker.py:600,617`, `verify_dubtechno_completed.py:90`,
   `backfill_processed_files.py:112` — all `completion_date = int(time.time()*1000)`.
2. **External reset-stuck logic reverts `download_status='downloading' → 'idle'` and nulls
   `start_date` / zeros `downloaded_size`:** `telegram_health_monitor.py:222-258`,
   `reset_stuck_downloads.sh:44-53`. This deliberately re-queues genuinely stuck downloads.

telegram-files-exclusive columns external services must NOT write: everything marked **tf** in §1
(all file metadata, `scan_state`, `download_priority`, `queued_at`, `transfer_status`, `tags`,
`thread_chat_id`, `message_thread_id`, `id`, `unique_id`, and `download_status` transitions up to
`completed`).

---

## 5. External-consumer citation index

Files under `~/projects/music-processor/telegram-postproc/`. R = reads `file_record`,
W = writes `file_record`.

| File | R/W | download_status VALUES set | Columns written | Key read columns (file:line) |
|------|-----|-----------------------------|-----------------|------------------------------|
| `telegram_status_updater.py` | R+W | `processed`, `imported` | `download_status` | `download_status` (`:272,296`); UPDATEs `:81,96,159,170,188,199` |
| `file_processor_worker.py` | W | `processed` | `download_status`, `completion_date` | UPDATEs `:597,614` |
| `verify_dubtechno_completed.py` | R+W | `processed` | `download_status`, `completion_date` | `file_name,size,local_path` (`:31`); UPDATE `:87` |
| `backfill_processed_files.py` | R+W | `processed` | `download_status`, `completion_date` | `id,download_status` (`:87`); UPDATE `:110` |
| `backfill_completed_to_imported.py` | R+W | `imported` | `download_status` | reads `:58,74,112`; UPDATE `:94` |
| `backfill_completed_to_imported.sql` | R+W | `imported` | `download_status` | reads `:8,16,31`; UPDATE `:24` |
| `backfill_processed_status.py` | R | `processed` (via updater) | (delegates) | `file_name,download_status,local_path` (`:48`) |
| `telegram_health_monitor.py` | R+W | `idle` (reset) | `download_status`, `start_date`(NULL), `downloaded_size`(0) | reads `:132,153,174,188,201`; UPDATEs `:222,245,253` |
| `reset_stuck_downloads.sh` | R+W | `idle` (reset) | `download_status`, `start_date`(NULL), `downloaded_size`(0) | reads `:31`; UPDATE `:44` |
| `monitoring_dashboard.py` | R | — | — | `download_status,completion_date,chat_id,type,date` (`:86`) |
| `unified_dashboard.py` | R | — | — | `download_status,completion_date,chat_id,type,date` (`:167`) |
| `pipeline_health_checker.py` | R | — | — | `download_status,completion_date,chat_id,type,date` (`:129`) |
| `finalize_manifest.sh` | R | — | — | `chat_id,message_thread_id,completion_date,date` (`:208,221,301,313`) |
| `add_download_index.sql` | DDL | — | (index only) | partial index on `download_status='idle'` (`:5`) |

### Findings surfaced by the audit (STOP-trigger review)

- **Finding A — phantom read status `'downloaded'`:** external READERS filter on it; no writer emits
  it; not in the enum. This is a **read-side assumption**, not an out-of-enum write, so it does not
  change the never-downgrade rule. Documented in §2 and pinned by a behavioral test.
- **Finding B — external writes to shared columns (`completion_date`, `start_date`,
  `downloaded_size`) and reset-to-`idle`:** these are within the canonical enum and are intentional
  pipeline behaviors, but they write columns the naive ownership model would call telegram-files-only.
  Reclassified as **shared** in §1 and documented as write-ownership fixtures in §4. Downstream
  effect on later phases: the never-downgrade rule applies to `processed`/`imported` only — it MUST
  NOT be widened to `completed`/`downloading`, because external reset-stuck legitimately moves
  `downloading → idle`.

No external writer sets `download_status` to a value outside the canonical enum. No STOP-blocking
schema surprise. `message_thread_id` is a live external read-dependency (`finalize_manifest.sh`) and
is included as a nullable contract column.
