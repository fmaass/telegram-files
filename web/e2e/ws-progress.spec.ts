import { test, expect } from "@playwright/test";
import { startHermeticBackend } from "./hermetic-backend.mjs";

// Boot an ISOLATED hermetic backend (own fresh SQLite DB) for THIS spec, so its seed/progress writes
// never contend with another spec's transfer/timer writes on a shared DB (SQLITE_BUSY_SNAPSHOT flake).
let backend: { stop: () => Promise<void> };
test.beforeAll(async () => {
  backend = await startHermeticBackend();
});
test.afterAll(async () => {
  await backend?.stop();
});

/**
 * Phase-6 PROGRESS-RENDER e2e against the REAL Java backend + the hermetic gateway (APP_ENV=dev,
 * HERMETIC_GATEWAY=1). This is the DOM-render half of the BL-02 acceptance: it proves a GENUINE
 * download-progress websocket event (a non-terminal, partial `downloadedSize` UpdateFile injected via
 * the gateway) actually RENDERS in a real file row, and that the row reaches `completed` EXACTLY ONCE.
 *
 * It navigates to `/files` (the offline DB-backed listing that renders per-file ROWS with a progress
 * bar) — NOT the `/` root with fetch-polling. The file is seeded `idle`, so the injected progress is a
 * REAL idle->downloading transition (a real DB write + a real FILE_STATUS event carrying the partial
 * downloadedSize), and the completion is a REAL downloading->completed transition. Frame-count and
 * DOM-render are asserted SEPARATELY (this spec owns DOM-render; ws-dedup-probe.mjs owns frame-count).
 */

const CHAT_ID = 100;
const UID = "ws-progress-" + Date.now();
const FILE_NAME = `progress-render-${Date.now()}.bin`;
const SIZE = 1024;

async function apiFetch(page: import("@playwright/test").Page, path: string, init?: RequestInit) {
  return page.evaluate(
    async ({ path, init }) => {
      const resp = await fetch(path, {
        credentials: "include",
        headers: { "Content-Type": "application/json" },
        ...init,
      });
      const text = await resp.text();
      let body: unknown = null;
      try {
        body = text ? JSON.parse(text) : null;
      } catch {
        body = text;
      }
      return { status: resp.status, body };
    },
    { path, init },
  );
}

test("a real progress event renders in the file row, and the row completes exactly once", async ({ page }) => {
  // 1) Register a hermetic account (fake sender, no native TDLib) so the gateway can drive the REAL
  //    onFileUpdated on an owning verticle. NO transfer automation is configured, so transferStatus
  //    stays `idle` and the download-status badge (Downloading/Completed) is the one on screen.
  await page.goto("/");
  await expect(page).toHaveTitle(/Telegram Files/i);
  const account = await apiFetch(page, "/api/test/gateway/account", {
    method: "POST",
    body: JSON.stringify({ telegramId: 1 }),
  });
  expect(account.status).toBe(200);

  // 2) Seed the file as `idle` (via the DB) so the offline /files list shows a row we can watch, and so
  //    the injected progress is a REAL idle->downloading transition (a real write + real FILE_STATUS).
  const seed = await apiFetch(page, "/api/test/gateway/seed", {
    method: "POST",
    body: JSON.stringify({
      uniqueId: UID,
      telegramId: 1,
      chatId: CHAT_ID,
      fileName: FILE_NAME,
      downloadStatus: "idle",
      // `photo` so the row survives the frontend's default `type=media` (photo|video) offline filter.
      type: "photo",
    }),
  });
  expect(seed.status).toBe(200);

  // 3) Navigate to the offline file listing (renders per-file rows with a progress bar). SCOPE every
  //    assertion to the SEEDED file's row — a FileRow is a `div[data-index]` that contains the (unique)
  //    filename. Asserting EXACT COUNTS on this scoped row (not `.first()` on a global locator) is what
  //    makes "render once" real: a duplicate/stale row or a double-rendered badge FAILS the test.
  await page.goto("/files");
  // `hasText` matches ancestors too, so several nested divs contain the name; the FileRow is the one
  // carrying `data-index`. Exactly ONE such row must exist for this unique file.
  const row = page.locator("div[data-index]", { hasText: FILE_NAME });
  await expect(row).toHaveCount(1, { timeout: 15_000 });

  // Give the browser websocket a moment to OPEN so the injected frames are not missed.
  await page.waitForTimeout(1000);

  // 4) Inject a REAL progress event (non-terminal, partial downloadedSize) through the gateway ->
  //    onFileUpdated. This fires a FILE_STATUS with downloadStatus=downloading + downloadedSize=SIZE/2,
  //    which use-files merges into the rendered row (downloadedSize drives the progress bar at ~50%).
  const progress = await apiFetch(page, "/api/test/gateway/progress", {
    method: "POST",
    body: JSON.stringify({ uniqueId: UID, downloadedSize: Math.floor(SIZE / 2) }),
  });
  expect(progress.status).toBe(200);
  expect((progress.body as { injected: boolean }).injected).toBeTruthy();

  // 5) DOM ASSERTION #1 — the SEEDED ROW renders download progress EXACTLY ONCE: within that row there
  //    is exactly one "Downloading" badge AND exactly one progress bar (Radix role="progressbar", only
  //    rendered while 0 < progress < 100). Exact counts, not `.first()`, so a duplicate render bites.
  await expect(row.getByText("Downloading")).toHaveCount(1, { timeout: 10_000 });
  await expect(row.locator('[role="progressbar"]')).toHaveCount(1, { timeout: 10_000 });
  // The row itself must still be a single row (no duplicate row rendered by the progress event).
  await expect(row).toHaveCount(1);

  // 6) Inject the REAL completion (downloading -> completed).
  const complete = await apiFetch(page, "/api/test/gateway/complete", {
    method: "POST",
    body: JSON.stringify({ uniqueId: UID }),
  });
  expect(complete.status).toBe(200);
  expect((complete.body as { injected: boolean }).injected).toBeTruthy();

  // 7) DOM ASSERTION #2 — the SEEDED ROW reaches COMPLETED EXACTLY ONCE: within that row there is
  //    exactly one "Completed" badge, ZERO "Downloading" badges, and ZERO progress bars (progress pins
  //    to 100, which the row does not render as a bar). This proves the row TRANSITIONED and left NO
  //    duplicate/stale downloading render behind.
  await expect(row.getByText("Completed")).toHaveCount(1, { timeout: 15_000 });
  await expect(row.getByText("Downloading")).toHaveCount(0);
  await expect(row.locator('[role="progressbar"]')).toHaveCount(0);
  // Still exactly one row for this file — completion did not spawn a duplicate row.
  await expect(row).toHaveCount(1);

  // 8) STABILITY — a beat later the row is STILL a single Completed row with no re-emergence of a
  //    downloading badge/bar (no flicker/duplicate re-render bouncing the row back).
  await page.waitForTimeout(1000);
  await expect(row).toHaveCount(1);
  await expect(row.getByText("Completed")).toHaveCount(1);
  await expect(row.getByText("Downloading")).toHaveCount(0);
  await expect(row.locator('[role="progressbar"]')).toHaveCount(0);
});
