# Acceptance Gate — WebSocket EXACTLY-ONCE delivery (BL-02)

This gate proves the websocket delivers download events **exactly once** per event and that the
**reconnect / stale-close race** does not evict a live session (the BL-02 fix). It has two tiers:

| Tier | Where | Runs against | Automated? |
|------|-------|--------------|------------|
| **CI hermetic probe** | `misc/acceptance/ws-dedup-probe.mjs` + `web/e2e/ws-progress.spec.ts` | a fresh **dev-mode** backend (`APP_ENV=dev`, `HERMETIC_GATEWAY=1`) with the synthetic injector | **YES** — runs in CI (job `ws-dedup-probe`) and the Playwright job |
| **Live release probe** | `misc/acceptance/ws-live-release.py` (this doc) | the **deployed production container** with a **REAL Telegram account** | **NO** — human/operator-gated at release time |

The CI probe is the day-to-day guarantee. The **live probe below is the release-time confirmation**
that the same property holds on the real deployed stack with real Telegram traffic — the injector is
**hard-off under prod** (both the HTTP gateway and `injectUpdateForGateway` refuse when `APP_ENV=prod`),
so the live probe uses a genuine download instead of the synthetic injector.

---

## What the CI probe proves (already automated)

- **Frame exactly-once** — one injected download event delivers exactly one websocket frame for that
  file (counted by `uniqueId`), with a **bite proof** that the counter is real (a second distinct
  completion delivers its own frame; no cross-leak onto the first file's stream).
- **Reconnect / stale-close dedup** — two overlapping same-`tf`-cookie sockets: after socket A's late
  close, socket B keeps its registration and receives the next event with **no duplicate, no eviction**.
  Proven RED-first: the probe FAILS against a backend whose `handleWebSocketClose` compare-and-remove
  is reverted.
- **Progress render** — a real non-terminal progress event RENDERS in a file row (`[role=progressbar]`
  + a "Downloading" badge) and the row reaches **completed exactly once** ("Completed" badge, progress
  bar gone).

Run the CI probe locally:

```bash
# 1. Build the backend jar (the probe boots it in dev-mode with the injector on).
cd api && JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  TDLIB_PATH=$PWD/../tdlib/macos_silicon ./gradlew shadowJar

# 2. Frame exactly-once + reconnect/stale-close (boots a throwaway dev backend, tears it down).
node misc/acceptance/ws-dedup-probe.mjs

# 3. DOM progress-render + complete-exactly-once (Playwright, dev-mode hermetic backend).
cd web && npm ci && npx playwright test --config=playwright.download.config.ts ws-progress.spec.ts
```

---

## Live release probe (human/operator-gated — real account, NOT CI)

The injector is absent from a prod build, so this tier drives a **real download** on the deployed
container and observes the live websocket. It requires a real Chrome with remote debugging (see the
`browser-harness` skill) and a real Telegram account already authorized in the running service.

### Preconditions

- The new image is deployed and serving (verify per project `CLAUDE.md` Deployment Workflow: digest
  match + `curl -f http://localhost:8979/api/health` + `curl -f http://localhost:8979/api/telegrams`).
- At least one Telegram account is authorized and has a chat with a **downloadable file** available.
- Local Chrome has remote debugging enabled (`chrome://inspect/#remote-debugging`).

### Procedure

1. Open the dashboard (`http://localhost:8979/`) in the real browser during an **active download**.
2. Confirm the file row shows a moving progress bar (`[role=progressbar]`) and a byte count that
   INCREASES — progress events are arriving over the websocket.
3. Assert **no duplicate rows / no duplicate progress jumps** for the same file (each `uniqueId` has one
   row; the bar moves monotonically, it does not double-count).
4. **Reconnect the same session**: use the dashboard's reconnect affordance (or toggle network
   off/on) so the websocket reconnects on the **same `tf` cookie**. Confirm the row keeps updating —
   the reconnected socket still receives events (the old socket's close did not evict the session).
5. Let the download COMPLETE. Confirm the row reaches **Completed exactly once** (the badge settles on
   Completed; it does not flicker back to Downloading, and no duplicate completed row appears).

The harness script `misc/acceptance/ws-live-release.py` automates the browser-driving parts of steps
1–5 against the running container; a human confirms the "no duplicate" judgments it prints.

### Acceptance

- One row per downloading file; progress advances monotonically (no duplicate/double-counted frames).
- After a same-session reconnect, the row continues to update (no eviction).
- The file reaches Completed exactly once.

This is the authoritative "the deployed websocket delivers exactly once with real Telegram traffic"
evidence — a green CI hermetic probe is necessary but, per the house deployment rules, a live request
against the running service is the final acceptance for a change touching the websocket fanout.
