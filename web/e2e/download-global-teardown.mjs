// Playwright global teardown for the DOWNLOAD e2e: stop the hermetic backend booted in global-setup.
import { readFileSync, existsSync, rmSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";

export default async function globalTeardown() {
  const stateFile = process.env.__TF_E2E_STATE || join(tmpdir(), "tf-e2e-backend.pid");
  if (!existsSync(stateFile)) return;
  try {
    const { pid, appRoot } = JSON.parse(readFileSync(stateFile, "utf8"));
    if (pid) {
      try { process.kill(pid, "SIGTERM"); } catch { /* already gone */ }
    }
    if (appRoot && appRoot.includes("tf-e2e-")) {
      try { rmSync(appRoot, { recursive: true, force: true }); } catch { /* ignore */ }
    }
    rmSync(stateFile, { force: true });
  } catch { /* ignore */ }
}
