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
- Fork version: 0.3.1-fms (set in api/build.gradle, web/package.json, VERSION, Start.java)
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
| Docker compose | docker-compose.yaml (repo root) |
| Data volume | /Volumes/flexosaurus/downloads/telegram (mounted at /app/data) |
| DB migrations | api/src/main/java/telegram/files/repository/migrations/ |
| Container port mapping | 6543 -> 80 (host -> container nginx -> 8080 API) |

## Database

SQLite at APP_ROOT by default. Optional PostgreSQL or MySQL via DB_TYPE, DB_HOST, DB_PORT, DB_USER, DB_PASSWORD, DB_NAME env vars. Migrations are Java-based in the repository/migrations/ package.

## Testing

JUnit 5 + Mockito for backend. No frontend tests. Tests require TDLIB_PATH pointing to native TDLib library.

    cd api && ./gradlew test

Note: Java 25 required for compilation (Java 23 features). JaCoCo may emit warnings but tests still run.

## Homelab Hosts

- **Merkur** -- This machine (Mac), 192.168.1.51 -- **NEVER SSH to it, run commands directly**
- **Saturn** -- Main Docker host (Synology NAS), 192.168.1.50, `ssh saturn.local`

This service runs on Merkur only. It is NOT deployed on Saturn and has no Traefik route.

## Deployment Workflow

Service runs locally on Merkur via Docker Compose.

    # Build image
    docker build -t telegram-files:main-clean .

    # Deploy
    docker compose up -d

    # Verify
    docker ps --filter name=telegram-files
    curl -f http://localhost:6543/api/health

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
