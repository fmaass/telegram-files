import { defineConfig, devices } from "@playwright/test";

/**
 * Playwright smoke harness for the Next.js static export.
 *
 * The app ships as a static export (next.config.js `output: "export"` -> web/out). The webServer
 * below builds that export if needed and serves it via a tiny dependency-free static server, so the
 * smoke spec loads the real built app shell — not the dev server. Later phases add richer specs to
 * e2e/.
 */
const PORT = 4321;
const BASE_URL = `http://127.0.0.1:${PORT}`;

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
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
    // Build the static export if it is missing, then serve web/out.
    command:
      "sh -c 'test -f out/index.html || SKIP_ENV_VALIDATION=1 NEXT_PUBLIC_API_URL=/api NEXT_PUBLIC_WS_URL=/ws npm run build; PORT=" +
      PORT +
      " node e2e/static-server.mjs'",
    url: BASE_URL,
    reuseExistingServer: !process.env.CI,
    timeout: 180_000,
  },
});
