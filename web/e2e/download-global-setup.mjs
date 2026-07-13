// Playwright global setup for the DOWNLOAD e2e: boot the REAL Java backend hermetically.
//
// Builds nothing (expects api/build/libs/telegram-files.jar to exist — the e2e task builds it first)
// and launches it with APP_ENV=dev + HERMETIC_GATEWAY=1 against a throwaway SQLite APP_ROOT, so the
// hermetic gateway routes are mounted and NO real Telegram network / native client work happens.
// Writes the child PID to a file so global-teardown can stop it.
import { spawn } from "node:child_process";
import { mkdtempSync, writeFileSync, existsSync, readdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = fileURLToPath(new URL("../../", import.meta.url));
const jar = join(repoRoot, "api", "build", "libs", "telegram-files.jar");
const tdlib = join(repoRoot, "tdlib", "macos_silicon");
const backendPort = Number(process.env.BACKEND_PORT || 8080);

function resolveJava23() {
  if (process.env.JAVA23_BIN && existsSync(process.env.JAVA23_BIN)) return process.env.JAVA23_BIN;
  if (process.env.JAVA_HOME_23) {
    const c = join(process.env.JAVA_HOME_23, "bin", "java");
    if (existsSync(c)) return c;
  }
  // Gradle-provisioned Adoptium 23 (foojay resolver) — used by the backend build toolchain.
  const jdksRoot = join(process.env.HOME || "", ".gradle", "jdks");
  if (existsSync(jdksRoot)) {
    for (const d of readdirSync(jdksRoot)) {
      if (!d.includes("23")) continue;
      const base = join(jdksRoot, d);
      try {
        for (const inner of readdirSync(base)) {
          const c = join(base, inner, "Contents", "Home", "bin", "java");
          if (existsSync(c)) return c;
          const c2 = join(base, inner, "bin", "java");
          if (existsSync(c2)) return c2;
        }
      } catch { /* ignore */ }
    }
  }
  return "java"; // last resort (must be >= 23 on PATH)
}

export default async function globalSetup() {
  if (!existsSync(jar)) {
    throw new Error(`Backend jar not found at ${jar}. Build it first: (cd api && ./gradlew shadowJar)`);
  }
  const java = resolveJava23();
  const appRoot = mkdtempSync(join(tmpdir(), "tf-e2e-"));

  const child = spawn(java, [`-Djava.library.path=${tdlib}`, "-jar", jar], {
    env: {
      ...process.env,
      APP_ENV: "dev",
      HERMETIC_GATEWAY: "1",
      APP_ROOT: appRoot,
      DB_TYPE: "sqlite",
      TELEGRAM_API_ID: process.env.TELEGRAM_API_ID || "12345",
      TELEGRAM_API_HASH: process.env.TELEGRAM_API_HASH || "deadbeefdeadbeefdeadbeefdeadbeef",
    },
    stdio: ["ignore", "pipe", "pipe"],
    detached: false,
  });

  const logChunks = [];
  child.stdout.on("data", (d) => logChunks.push(d.toString()));
  child.stderr.on("data", (d) => logChunks.push(d.toString()));

  // Wait for /api/health to answer UP (real DB round-trip). Fail loudly on timeout.
  const deadline = Date.now() + 60_000;
  let ready = false;
  while (Date.now() < deadline) {
    try {
      const resp = await fetch(`http://127.0.0.1:${backendPort}/api/health`);
      if (resp.ok) {
        const body = await resp.json();
        if (body.status === "UP") { ready = true; break; }
      }
    } catch { /* not up yet */ }
    await new Promise((r) => setTimeout(r, 500));
  }
  if (!ready) {
    child.kill("SIGKILL");
    throw new Error(`Backend did not become healthy on :${backendPort}\n${logChunks.join("")}`);
  }

  const stateFile = join(tmpdir(), "tf-e2e-backend.pid");
  writeFileSync(stateFile, JSON.stringify({ pid: child.pid, appRoot }));
  // Detach the reference so the process keeps running for the test run.
  child.unref();
  process.env.__TF_E2E_STATE = stateFile;
  console.log(`hermetic backend up (pid ${child.pid}) on :${backendPort}, APP_ROOT=${appRoot}`);
}
