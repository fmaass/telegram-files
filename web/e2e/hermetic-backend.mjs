// Per-spec hermetic backend lifecycle for the Playwright DOWNLOAD/WS e2e.
//
// Each spec boots its OWN backend (own throwaway SQLite APP_ROOT) in beforeAll and tears it down in
// afterAll. Because the download config runs with workers:1 / fullyParallel:false, the specs run
// STRICTLY SEQUENTIALLY, so each backend has sole ownership of port 8080 and its own database while it
// runs — no two specs ever share a SQLite file or the process-static TelegramVerticles registry.
//
// WHY: with a single shared backend, download.spec's async Phase-3 transfer + the background
// reconciliation/avg-speed timers write the SHARED SQLite WAL database CONCURRENTLY with ws-progress's
// seed INSERT. SQLite in WAL mode with a multi-connection pool then returns SQLITE_BUSY_SNAPSHOT (a
// snapshot conflict that busy_timeout does NOT wait out) — flaking BOTH specs intermittently. A fresh
// per-spec backend gives each spec an isolated database, eliminating the cross-spec write contention at
// its source (not by weakening assertions or serial-only retries). Production uses PostgreSQL, so this
// is purely a test-harness isolation concern.
import { spawn } from "node:child_process";
import { mkdtempSync, existsSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { connect as netConnect } from "node:net";

const repoRoot = fileURLToPath(new URL("../../", import.meta.url));
const jar = join(repoRoot, "api", "build", "libs", "telegram-files.jar");
const tdlib = process.env.TDLIB_PATH || join(repoRoot, "tdlib", "macos_silicon");
const backendPort = Number(process.env.BACKEND_PORT || 8080);

function resolveJava23() {
  if (process.env.JAVA23_BIN && existsSync(process.env.JAVA23_BIN)) return process.env.JAVA23_BIN;
  if (process.env.JAVA_HOME_23) {
    const c = join(process.env.JAVA_HOME_23, "bin", "java");
    if (existsSync(c)) return c;
  }
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
  return "java";
}

// A raw TCP probe: the port is FREE only when a connect is actively refused. `fetch /api/health` is
// insufficient — a backend mid-shutdown may still hold the listen socket while failing health, and a
// half-closed socket can linger; a refused TCP connect is the authoritative "nobody is bound" signal.
/** @param {number} port @returns {Promise<boolean>} */
function portFree(port) {
  return new Promise((resolve) => {
    const sock = netConnect({ port, host: "127.0.0.1" });
    sock.setTimeout(1000);
    sock.once("connect", () => { sock.destroy(); resolve(false); }); // someone is listening
    sock.once("timeout", () => { sock.destroy(); resolve(false); });
    sock.once("error", () => resolve(true)); // ECONNREFUSED -> free
  });
}

/** @param {number} port @param {number} timeoutMs @returns {Promise<boolean>} */
async function waitPortFree(port, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await portFree(port)) return true;
    await new Promise((r) => setTimeout(r, 200));
  }
  return false;
}

/**
 * Boot a fresh hermetic backend and wait for /api/health UP. Returns a handle with stop(). Throws
 * (with captured logs) on boot failure so a flaky boot fails loudly, never silently.
 */
export async function startHermeticBackend() {
  if (!existsSync(jar)) {
    throw new Error(`Backend jar not found at ${jar}. Build it first: (cd api && ./gradlew shadowJar)`);
  }
  // A prior spec's backend must be FULLY gone before we bind 8080 (sequential specs; guard the seam).
  // Fail loudly if the port never frees — booting anyway would BindException and flake.
  if (!(await waitPortFree(backendPort, 20_000))) {
    throw new Error(`Port ${backendPort} is still bound after 20s — a prior backend did not release it. `
      + `A fresh hermetic backend cannot bind. (If a non-test process owns 8080, free it first.)`);
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

  /** @type {string[]} */
  const logChunks = [];
  const dbgLog = process.env.TF_E2E_DEBUG_LOG;
  let dbgFd = null;
  if (dbgLog) {
    const fs = await import("node:fs");
    dbgFd = fs.createWriteStream(dbgLog, { flags: "a" });
  }
  child.stdout.on("data", (d) => { logChunks.push(d.toString()); if (dbgFd) dbgFd.write(d); });
  child.stderr.on("data", (d) => { logChunks.push(d.toString()); if (dbgFd) dbgFd.write(d); });

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

  let stopped = false;
  return {
    pid: child.pid,
    appRoot,
    async stop() {
      if (stopped) return;
      stopped = true;
      const exited = new Promise((r) => child.once("exit", r));
      // Graceful first (drains the pool cleanly), then ESCALATE to SIGKILL if it lingers, so port 8080
      // is guaranteed released before the next spec boots (the graceful Vert.x drain can outrun a short
      // wait, and a still-listening backend BindExceptions the next boot — the root cause of the flake).
      try { child.kill("SIGTERM"); } catch { /* already gone */ }
      const gracefulExit = await Promise.race([
        exited.then(() => true),
        new Promise((r) => setTimeout(() => r(false), 6_000)),
      ]);
      if (!gracefulExit) {
        try { child.kill("SIGKILL"); } catch { /* already gone */ }
        await Promise.race([exited, new Promise((r) => setTimeout(r, 4_000))]);
      }
      // Do not return until the port is actually free (authoritative: a refused TCP connect).
      await waitPortFree(backendPort, 10_000);
      if (appRoot.includes("tf-e2e-")) {
        try { rmSync(appRoot, { recursive: true, force: true }); } catch { /* ignore */ }
      }
    },
  };
}
