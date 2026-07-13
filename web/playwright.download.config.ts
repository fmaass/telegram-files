import { defineConfig, devices } from "@playwright/test";

/**
 * Playwright DOWNLOAD + WS-PROGRESS e2e harness (Phase-5/6): the REAL built frontend against the REAL
 * Java backend with the hermetic gateway enabled.
 *
 * Backend lifecycle is PER-SPEC: each spec boots its OWN hermetic backend jar (APP_ENV=dev,
 * HERMETIC_GATEWAY=1) with a fresh SQLite DB in beforeAll and tears it down in afterAll
 * (e2e/hermetic-backend.mjs). Because the specs run strictly sequentially (workers:1,
 * fullyParallel:false), no two ever share a database or the process-static verticle registry — this
 * eliminates cross-spec SQLite write contention. global-setup only validates the jar exists; there is
 * NO globalTeardown (per-spec afterAll owns teardown via the child handle, so no stale-PID hazard).
 * The webServer serves the static export AND reverse-proxies /api/* AND the /ws websocket to the
 * backend on :8080, so the browser drives one origin.
 *
 * Specs:
 *  - download.spec.ts    (Phase-5): trigger -> claim -> complete -> Phase-3 durable transfer
 *                                   (asserts transfer_status=completed + local_path in the destination).
 *  - ws-progress.spec.ts (Phase-6): a real download-progress websocket event RENDERS in a file row and
 *                                   the row reaches completed exactly once (DOM-render half of BL-02).
 *
 * The backend jar must exist (build it first: `cd api && ./gradlew shadowJar`).
 */
const PORT = 4322;
const BASE_URL = `http://127.0.0.1:${PORT}`;

export default defineConfig({
  testDir: "./e2e",
  testMatch: ["**/download.spec.ts", "**/ws-progress.spec.ts"],
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  // Backends are booted/torn down PER-SPEC (e2e/hermetic-backend.mjs beforeAll/afterAll), which owns
  // ownership-safe teardown (it holds the child handle — no PID-file lookup). No globalTeardown: a
  // fixed-PID-file teardown could SIGTERM an unrelated process after PID reuse from a stale file.
  globalSetup: "./e2e/download-global-setup.mjs",
  use: {
    baseURL: BASE_URL,
    trace: "on-first-retry",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  webServer: {
    // Build the static export if missing, then serve it with /api proxied to the backend.
    command:
      "sh -c 'test -f out/index.html || SKIP_ENV_VALIDATION=1 NEXT_PUBLIC_API_URL=/api NEXT_PUBLIC_WS_URL=/ws npm run build; PORT=" +
      PORT +
      " BACKEND_PORT=8080 node e2e/proxy-server.mjs'",
    url: BASE_URL,
    reuseExistingServer: !process.env.CI,
    timeout: 180_000,
  },
});
