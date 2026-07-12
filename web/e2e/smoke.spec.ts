import { expect, test } from "@playwright/test";

/**
 * Smoke test for the built static-export app shell. This is the Phase-0 canary that proves the
 * Playwright harness works end-to-end: it loads the real exported index.html (served from web/out)
 * and asserts the app shell renders. Later phases add feature-level specs alongside this file.
 */
test("app shell loads and renders", async ({ page }) => {
  const response = await page.goto("/");
  expect(response, "navigation should return a response").not.toBeNull();
  expect(response!.ok(), "index.html should serve with a 2xx status").toBeTruthy();

  // The document title is set by the app layout metadata.
  await expect(page).toHaveTitle(/Telegram Files/i);

  // The React root exists and the document language is set (proves the shell HTML rendered).
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
  await expect(page.locator("body")).toBeVisible();
});
