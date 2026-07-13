// Playwright global setup for the DOWNLOAD/WS e2e.
//
// The backend is booted PER-SPEC (e2e/hermetic-backend.mjs beforeAll/afterAll) so each spec runs
// against its OWN fresh SQLite database — eliminating cross-spec SQLITE_BUSY_SNAPSHOT contention on a
// shared DB (see hermetic-backend.mjs for the rationale). This global setup only validates the backend
// jar exists so the run fails early and clearly if it was not built.
import { existsSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = fileURLToPath(new URL("../../", import.meta.url));
const jar = join(repoRoot, "api", "build", "libs", "telegram-files.jar");

export default async function globalSetup() {
  if (!existsSync(jar)) {
    throw new Error(`Backend jar not found at ${jar}. Build it first: (cd api && ./gradlew shadowJar)`);
  }
  console.log("download/ws e2e: backend is booted per-spec (isolated SQLite DB per spec).");
}
