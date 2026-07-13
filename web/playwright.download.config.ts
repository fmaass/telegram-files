import { defineConfig, devices } from "@playwright/test";

/**
 * Playwright DOWNLOAD e2e harness (Phase-5): the REAL built frontend against the REAL Java backend
 * with the hermetic gateway enabled.
 *
 * global-setup boots the backend jar (APP_ENV=dev, HERMETIC_GATEWAY=1) and waits for /api/health;
 * the webServer serves the static export AND reverse-proxies /api/* to that backend, so the browser
 * drives the new /api surface from a single origin. global-teardown stops the backend.
 *
 * The backend jar must exist (build it first: `cd api && ./gradlew shadowJar`).
 */
const PORT = 4322;
const BASE_URL = `http://127.0.0.1:${PORT}`;

export default defineConfig({
  testDir: "./e2e",
  testMatch: ["**/download.spec.ts"],
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  globalSetup: "./e2e/download-global-setup.mjs",
  globalTeardown: "./e2e/download-global-teardown.mjs",
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
