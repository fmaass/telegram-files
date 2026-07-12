# Acceptance Gate — Browser Harness

This directory documents the **manual / real-browser acceptance gate** for telegram-files. It is
the human-driven complement to the automated CI gates:

| Gate | Where | What it proves |
|------|-------|----------------|
| Backend unit + PG integration | CI (`ci.yml` jobs `backend`, `backend-pg-integration`) | Java logic + `isPostgres()` parity against a real PostgreSQL 17 container |
| Playwright smoke | CI (`ci.yml` job `frontend`) | The built static-export app shell renders in a headless Chromium |
| **Browser-harness acceptance** | **Manual / operator, this doc** | A real browser drives the **running deployed service** end-to-end |

The browser-harness gate is intentionally **NOT wired into CI**: it needs a real Chrome with remote
debugging against the live stack, which CI cannot provide. It is invoked by an operator (or an agent
with local Chrome access) as the final live-request acceptance step after a deploy.

## Why a separate real-browser gate

Per the house deployment rules, "deployed" is not done until a live request against the running
service proves the new code is serving. `curl -f /api/health` proves only the static shell / API
liveness — it does not prove the SPA boots, talks to `/api/`, and renders real data. The
browser-harness gate closes that gap for changes touching HTTP handlers, routing, or the SPA.

## Environment

- Production stack (see project `CLAUDE.md`): host `http://localhost:8979` → container nginx →
  Java API on `:8080`. nginx serves the static frontend and reverse-proxies `/api/`.
- Local Chrome must have **remote debugging enabled** (the harness prompts for this on first run and
  opens `chrome://inspect/#remote-debugging`; tick "Allow remote debugging for this browser
  instance"). See the `browser-harness` skill.

## How the gate is invoked

The harness is driven via a heredoc; helpers are pre-imported and the daemon auto-starts:

```bash
# 1. Confirm the harness can reach a real browser tab.
browser-harness --doctor

# 2. Drive the running app and assert the shell + a real API-backed view render.
browser-harness <<'PY'
ensure_real_tab()
new_tab("http://localhost:8979/")
print(page_info())                       # title should be "Telegram Files"
assert "Telegram Files" in page_info()["title"]

# Navigate a real route that exercises the API (accounts list is API-backed).
goto_url("http://localhost:8979/accounts")
print(page_info())
PY
```

Notes:
- First navigation in a session is `new_tab(url)`, not `goto_url(url)`.
- Do NOT use this gate behind an SSO/MFA wall the agent cannot pass. telegram-files has no HTTP auth
  (LAN-only), so the SPA is directly reachable — but if a route is fronted by an auth proxy, verify
  via `docker exec` + authoritative DB read-back instead (house rule).

## What the later Phase-6 probes will assert

Phase 6 will formalize the following named acceptance probes (run against the **running deployed
container**, not a dev server):

1. **Shell boot** — `GET /` returns 200 and the SPA renders `<title>Telegram Files</title>` with a
   non-empty `<body>` (no white-screen / JS crash).
2. **API reachability from the SPA** — a real navigation to `/accounts` (or `/files`) triggers an
   `/api/*` XHR that returns 2xx and the view renders the fetched data, proving nginx → API
   proxying and the frontend↔backend contract on the deployed build.
3. **Health parity** — `GET /api/health` returns healthy AND a representative real `/api/*` route
   (e.g. `/api/telegrams`) returns 2xx (a `/health`-only check can mask an `/api` outage).
4. **Post-deploy digest** — the running container image digest matches the just-built tag (verified
   separately by the deploy workflow, referenced here so the acceptance record is complete).

These probes are the authoritative "deployed is serving the new code" evidence; a green CI run and a
digest match are necessary but not sufficient on their own.
