import { test, expect } from "@playwright/test";

/**
 * Phase-5 DOWNLOAD e2e against the REAL Java backend + the hermetic gateway.
 *
 * The app shell is served from the built static export; /api/* is reverse-proxied to the hermetic
 * backend (APP_ENV=dev, HERMETIC_GATEWAY=1). The flow is driven THROUGH the frontend origin: the
 * page's own fetch (same code path as web/src/lib/api.ts `request()`) calls the REAL
 * `POST /api/downloads` trigger, and the download's progression flows through the REAL Phase-2 claim,
 * the Phase-2 completion CAS, and the Phase-3 durable transfer in the backend — NOT a frontend-only
 * mutation. The test asserts the transfer actually ran (transfer_status=completed + local_path in the
 * destination), not merely download_status=completed.
 */

const CHAT_ID = 100;
const MSG_ID = 200 + (Date.now() % 100000); // unique message per run -> unique file row
// Destination the hermetic transfer moves the artifact into (local backend + local test share /tmp).
const DEST = "/tmp/tf-e2e-transfer-dest";

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

type FileRow = {
  uniqueId: string;
  downloadStatus: string;
  transferStatus?: string;
  localPath?: string | null;
};

async function findFile(
  page: import("@playwright/test").Page,
  status: string,
  uniqueId: string,
): Promise<FileRow | undefined> {
  const res = await apiFetch(page, `/api/files?downloadStatus=${status}&limit=100`);
  const files = (res.body as { files: FileRow[] }).files ?? [];
  return files.find((f) => f.uniqueId === uniqueId);
}

test("real POST /api/downloads triggers, claims, completes, and the Phase-3 transfer runs", async ({ page }) => {
  await page.goto("/");
  await expect(page).toHaveTitle(/Telegram Files/i);

  // 1) Register a hermetic account (fake sender, no native TDLib) + a transfer automation so Phase-3
  //    picks up the completed download and runs the durable transfer.
  const account = await apiFetch(page, "/api/test/gateway/account", {
    method: "POST",
    body: JSON.stringify({ telegramId: 1 }),
  });
  expect(account.status).toBe(200);

  const automation = await apiFetch(page, "/api/test/gateway/automation", {
    method: "POST",
    body: JSON.stringify({ telegramId: 1, chatId: CHAT_ID, destination: DEST }),
  });
  expect(automation.status).toBe(200);

  // 2) Call the REAL trigger endpoint through the frontend origin. It resolves the volatile fileId
  //    from stable identity (chat+message) and does the Phase-2 atomic claim.
  const trigger = await apiFetch(page, "/api/downloads", {
    method: "POST",
    body: JSON.stringify({ telegramId: 1, chatId: CHAT_ID, messageId: MSG_ID }),
  });
  expect(trigger.status).toBe(202);
  const triggered = trigger.body as { uniqueId: string; claimed: boolean; outcome: string; downloadStatus: string };
  expect(triggered.claimed).toBeTruthy();
  expect(triggered.outcome).toBe("CLAIMED");
  const uniqueId = triggered.uniqueId;

  // 3) Assert the download APPEARS as downloading in the resource listing.
  await expect
    .poll(async () => (await findFile(page, "downloading", uniqueId))?.downloadStatus, {
      timeout: 10_000,
    })
    .toBe("downloading");

  // 4) Inject a hermetic DOWNLOAD-COMPLETE through the REAL Phase-2/3 pipeline (writes a real artifact
  //    the transfer will move).
  const complete = await apiFetch(page, "/api/test/gateway/complete", {
    method: "POST",
    body: JSON.stringify({ uniqueId }),
  });
  expect(complete.status).toBe(200);
  expect((complete.body as { injected: boolean }).injected).toBeTruthy();

  // 5) Assert it reaches completed AND the Phase-3 durable transfer actually ran: transfer_status
  //    becomes completed and local_path points into the transfer DESTINATION (not the inbox).
  await expect
    .poll(
      async () => {
        const row = await findFile(page, "completed", uniqueId);
        if (!row) return null;
        return { ds: row.downloadStatus, ts: row.transferStatus, lp: row.localPath };
      },
      { timeout: 20_000, intervals: [250, 500, 1000] },
    )
    .toEqual({ ds: "completed", ts: "completed", lp: expect.stringContaining(DEST) });
});
