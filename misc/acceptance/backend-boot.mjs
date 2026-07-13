// Boot the REAL Java backend HERMETICALLY (APP_ENV=dev + HERMETIC_GATEWAY=1) for the CI dedup probe.
//
// This is the DEV-MODE fresh boot the WS exactly-once probe runs against — NOT the prod smoke-test
// (misc/smoke-test.sh), under which the injector is hard-off. It builds nothing (expects
// api/build/libs/telegram-files.jar to exist) and launches it against a throwaway SQLite APP_ROOT so
// NO real Telegram network / native TDLib client work happens. Returns { proc, port, appRoot, baseUrl }.
import { spawn } from "node:child_process";
import { mkdtempSync, existsSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, "..", "..");

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

function resolveTdlibDir() {
  for (const c of [
    process.env.TDLIB_PATH,
    join(repoRoot, "tdlib", "macos_silicon"),
    join(repoRoot, "api", "tdlib-linux"),
    join(repoRoot, "tdlib-linux"),
  ]) {
    if (c && existsSync(c)) return c;
  }
  return join(repoRoot, "tdlib", "macos_silicon");
}

export async function bootBackend({ port = Number(process.env.BACKEND_PORT || 8080) } = {}) {
  const jar = join(repoRoot, "api", "build", "libs", "telegram-files.jar");
  if (!existsSync(jar)) {
    throw new Error(`Backend jar not found at ${jar}. Build it first: (cd api && ./gradlew shadowJar)`);
  }
  const java = resolveJava23();
  const tdlib = resolveTdlibDir();
  const appRoot = mkdtempSync(join(tmpdir(), "tf-ws-probe-"));

  const proc = spawn(java, [`-Djava.library.path=${tdlib}`, "-jar", jar], {
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
  proc.stdout.on("data", (d) => logChunks.push(d.toString()));
  proc.stderr.on("data", (d) => logChunks.push(d.toString()));

  const deadline = Date.now() + 60_000;
  let ready = false;
  while (Date.now() < deadline) {
    try {
      const resp = await fetch(`http://127.0.0.1:${port}/api/health`);
      if (resp.ok) {
        const body = await resp.json();
        if (body.status === "UP") { ready = true; break; }
      }
    } catch { /* not up yet */ }
    await new Promise((r) => setTimeout(r, 500));
  }
  if (!ready) {
    proc.kill("SIGKILL");
    throw new Error(`Backend did not become healthy on :${port}\n${logChunks.join("")}`);
  }

  return {
    proc,
    port,
    appRoot,
    baseUrl: `http://127.0.0.1:${port}`,
    log: () => logChunks.join(""),
    stop() {
      try { proc.kill("SIGTERM"); } catch { /* ignore */ }
      if (appRoot.includes("tf-ws-probe-")) {
        try { rmSync(appRoot, { recursive: true, force: true }); } catch { /* ignore */ }
      }
    },
  };
}
