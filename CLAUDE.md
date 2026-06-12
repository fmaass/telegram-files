# telegram-files -- Conventions for Claude Code

Fork of jarvis2f/telegram-files (0.3.0). Telegram file download manager:
Java 23 / Vert.x 5 backend + Next.js React SPA, single Docker container.

## Architecture

- Backend: Java 23, Vert.x 5, Gradle shadowJar, Apache Commons JEXL3, Hutool, TDLib JNI
- Frontend: Next.js, React, TypeScript, Tailwind, Radix UI (static export)
- Database: SQLite by default (optional PostgreSQL/MySQL via env vars)
- Runtime: Alpine + nginx + custom JRE (jlink), single Docker container
- nginx serves static frontend and reverse-proxies /api/ to Java backend on port 8080

## Fork Maintenance

- Upstream: jarvis2f/telegram-files, remote name `upstream`
- Forked at: v0.3.0 (commit 154fd6ae, Dec 26 2025)
- Fork version: 0.4.0-fms (set in api/build.gradle, web/package.json, VERSION, Start.java)
- Check upstream: `git fetch upstream && git log --oneline HEAD..upstream/main`
- upstream/dev has commits that may overlap with features we implemented independently. Handle duplicates if upstream merges dev into main.

## Build & Run

    cd api && ./gradlew shadowJar          # Build API fat JAR
    cd api && ./gradlew test               # Run backend tests (JUnit 5)
    cd web && npm ci && npm run build       # Build frontend (static export)
    docker build -t telegram-files:tag .    # Full Docker image

Local dev: API on port 8080, frontend dev server on port 3000.

## Key Paths

| What | Path |
|------|------|
| API source | api/src/main/java/telegram/files/ |
| API tests | api/src/test/java/telegram/files/ |
| Frontend | web/src/ |
| Build output | api/build/libs/telegram-files.jar |
| Docker compose (dev/reference) | docker-compose.yaml (repo root) |
| Docker compose (PRODUCTION) | ~/projects/music-processor/telegram-postproc/docker-compose.yml |
| Smoke test | misc/smoke-test.sh (fresh-install boot + health + log scan) |
| Container port mapping (prod) | 8979 -> 8585 (host -> container nginx -> 8080 API) |

## Database

PRODUCTION uses PostgreSQL 17 on Saturn (192.168.1.50:5455, db `telegram_files_mac`,
user `telegram_user`; reached from containers via host gateway). Credentials live in
`~/projects/music-processor/telegram-postproc/.env.local`. The `telegram-files-cleanup`
sidecar container has psql preconfigured for this DB. SQLite at APP_ROOT is the
default for fresh installs and tests only. External services (tgfiles_postproc,
telegram-health-monitor, telegram-monitor-dashboard) read AND write `file_record`
directly -- schema changes must keep that interface stable. After any
schema restructure (PK change, column rename/drop), grep ALL external SQL
consumers (sidecar scripts in telegram-postproc compose, health monitor
queries) and update their WHERE/JOIN clauses. Verify with a manual run of
each consumer's query against the migrated schema.

Migrations are Java-based: `MIGRATIONS` maps in the repository record classes
(e.g. FileRecord), run by `Definition.migrate()`.

## Testing

JUnit 5 + Mockito for backend; vitest (`cd web && npm test`) for frontend.
Backend tests require TDLIB_PATH pointing to the native TDLib library:

    cd api && JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
      TDLIB_PATH=$PWD/../tdlib/macos_silicon ./gradlew test

Gradle 8.10 must RUN on JDK <= 23 (its Groovy/ASM cannot parse newer classfiles);
compilation uses a Java 23 toolchain auto-provisioned via the foojay resolver
(settings.gradle). Run the daemon on Homebrew openjdk@21.

## Homelab Hosts

- **Merkur** -- This machine (Mac), 192.168.1.51 -- **NEVER SSH to it, run commands directly**
- **Saturn** -- Main Docker host (Synology NAS), 192.168.1.50, `ssh saturn.local`

The service container runs on Merkur; its PostgreSQL database runs on Saturn (:5455).

## Deployment Workflow

The PRODUCTION stack is managed from the music-processor project, NOT this repo's
compose file. It includes sidecars (autoheal, cleanup, logger, postproc, health
monitor, dashboard) that depend on this service and its DB schema.

    # Build image (tag must match the compose file's image reference)
    docker build -t telegram-files:0.4.0-fms .

    # Fresh-install smoke test first
    misc/smoke-test.sh telegram-files:0.4.0-fms

    # Deploy (from the production stack directory)
    cd ~/projects/music-processor/telegram-postproc && docker compose up -d telegram_files

    # Verify
    docker ps --filter name=telegram-files
    curl -f http://localhost:8979/api/health

## Git Workflow

- NEVER commit directly to `main`. Create `feature/<name>` or `fix/<name>` branch first.
- Squash merge to main: `git checkout main && git merge --squash <branch> && git commit`
- Delete branch after merge: `git branch -d <branch> && git push origin --delete <branch>`
- NEVER add `Co-authored-by` trailers or use `--trailer` flags. Subject + body only.
- Global hook at `~/.config/git/hooks/commit-msg` strips trailers as safety net.

## Known Anti-Patterns

- **JEXL wildcard permissions**: never use `cn.hutool.core.*` or similar broad wildcards in JexlPermissions.compose(). Always use explicit class-level allowlists. The wildcard exposed RuntimeUtil for arbitrary command execution (upstream issue #130).
- **SQL string interpolation**: never interpolate user-controlled values into SQL via String.formatted() or concatenation. Use #{param} parameterized queries or validate against whitelists. The sort/order/tags parameters were vulnerable.
- **No HTTP auth**: the API has zero authentication middleware. Security relies entirely on network-level access control (LAN only, no Traefik route). Do not assume any route is authenticated.
- **JEXL constructor calls**: filter expressions must not contain `new()` constructor invocations. JexlFeatures blocks these at the engine level, and input validation rejects them before JEXL evaluation as defense-in-depth.
- **Telegram ID ranges**: chat IDs for groups/supergroups/channels are large negative numbers (e.g. -1001359914106). Never validate chatId as positive-only. User IDs are positive; chat IDs can be either.
- **Map.of() null keys**: `Map.of()` / `Map.copyOf()` throw NPE on `.get(null)` / `.containsKey(null)`. Always null-guard the key before calling these methods, or use `HashMap` when null keys are expected.

## Session Review

After completing an implementation session (all tasks done, committed, deployed/verified), automatically produce a **Session Review** before ending. Also produce one when prompted with "session review".

Use this exact format:

    # Session Review -- YYYY-MM-DD

    ## Delivered
    - **Branch**: `feature/xxx` -> merged to `main` / still open
    - Feature/fix A: one-line description
    - Feature/fix B: one-line description
    - **Duration**: ~Xh (wall clock estimate)

    ## Issues

    ### 1. Short title
    - **Symptom**: What was observed (1-2 sentences)
    - **Root cause**: Why (1 sentence)
    - **Fix**: What was done (1 sentence)
    - **Rule**: Prevention rule for future sessions
    - **Repeat?**: No / Yes -- see Session YYYY-MM-DD #N

    ## Observations
    - Non-urgent patterns, things that worked well, things to watch

    ## Action Items
    | # | Action | Priority | Effort | From |
    |---|--------|----------|--------|------|
    | 1 | Description | High/Med/Low | Small/Med/Large | #N |

Rules for writing the review:
- **Delivered first.** Always record what shipped, not just what broke.
- **No debugging narrative.** Each issue gets the 5-field structure above. No "then we tried X, then noticed Y" -- distill to root cause.
- **Flag repeats.** If an issue matches a previous session's lesson, mark it. Repeated issues should escalate in priority.
- **Action items link back** to the issue number that spawned them via the `From` column.
- **Keep it tight.** Each issue should be 5-8 lines max. The whole review should fit on one screen.

## Working Style

- Never suggest stopping, pausing, or deferring work. Just do it.
- **Boy Scout Rule**: when touching files, clean up adjacent files in the same directory -- stale references, dead docs, inconsistencies. Stay proportional, don't over-fix.
