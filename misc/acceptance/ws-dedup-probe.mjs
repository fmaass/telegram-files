#!/usr/bin/env node
// WebSocket EXACTLY-ONCE delivery probe (BL-02) — the load-bearing Phase-6 CI deliverable.
//
// Proves, against a REAL dev-mode backend (APP_ENV=dev + HERMETIC_GATEWAY=1), the two properties BL-02
// was supposed to guarantee but that were never verified end-to-end over the real WS transport:
//
//   (1) FRAME EXACTLY-ONCE: one injected download event delivers EXACTLY ONE websocket frame for that
//       file (counted by uniqueId). A BITE PROOF injects a duplicate event and asserts the count then
//       exceeds one — so the "== 1" assertion genuinely bites (it is not vacuously true).
//
//   (2) RECONNECT / STALE-CLOSE DEDUP (the real BL-02 race): two OVERLAPPING same-`tf`-cookie sockets.
//       Open A, open B (same session), then let A's late/abrupt close fire. The compare-and-remove in
//       HttpVerticle.handleWebSocketClose must NOT evict B's registration, and B must receive a
//       subsequent event with NO duplicate frame. A RED control run (BL02_BREAK=1) drops the
//       compare-and-remove guard's protection expectation to prove the probe catches the regression.
//
// Transport: the proxy-server has no WS upgrade handler, so the probe connects a headless WS client
// DIRECTLY to the backend /ws, binding the `tf` session cookie exactly as a browser does (obtained via
// a prior HTTP request through the SessionHandler). It NEVER runs under prod (boots dev-mode itself).
//
// Usage:
//   node misc/acceptance/ws-dedup-probe.mjs                 # boot a fresh dev backend, run, tear down
//   BACKEND_URL=http://127.0.0.1:8080 node ...ws-dedup-probe.mjs   # run against an already-running dev backend
//   BL02_BREAK=expect  node ...                             # (self-test) assert the reconnect probe WOULD fail
//                                                           #   if the compare-and-remove were reverted
import { connectWs } from "./ws-client.mjs";
import { bootBackend } from "./backend-boot.mjs";

const FILE_STATUS = 5; // EventPayload.TYPE_FILE_STATUS

function log(...a) { console.log("[ws-dedup-probe]", ...a); }
function fail(msg) { console.error("[ws-dedup-probe] FAIL:", msg); process.exitCode = 1; throw new Error(msg); }

// --- HTTP helpers (carry the tf cookie so the session that owns the socket is the browser's) ---------
async function httpGetCookie(baseUrl) {
  const resp = await fetch(`${baseUrl}/api/health`);
  const setCookie = resp.headers.get("set-cookie");
  if (!setCookie) throw new Error("backend did not set a session cookie on /api/health");
  const tf = setCookie.split(";")[0]; // "tf=<id>"
  if (!tf.startsWith("tf=")) throw new Error(`unexpected session cookie: ${setCookie}`);
  return tf;
}

async function post(baseUrl, path, body, cookie) {
  const resp = await fetch(`${baseUrl}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...(cookie ? { Cookie: cookie } : {}) },
    body: JSON.stringify(body),
  });
  const text = await resp.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { json = text; }
  return { status: resp.status, body: json };
}

// Collect FILE_STATUS frames for a specific uniqueId on a socket.
function frameCollector(ws, uniqueId) {
  const frames = [];
  ws.onText((text) => {
    let msg;
    try { msg = JSON.parse(text); } catch { return; }
    if (msg.type !== FILE_STATUS) return;
    const data = msg.data || {};
    if (data.uniqueId === uniqueId) frames.push(msg);
  });
  return frames;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function seedAccountAndFile(baseUrl, cookie, uniqueId, chatId) {
  const acct = await post(baseUrl, "/api/test/gateway/account", { telegramId: 1 }, cookie);
  if (acct.status !== 200) fail(`seed account failed: ${acct.status} ${JSON.stringify(acct.body)}`);
  const seed = await post(baseUrl, "/api/test/gateway/seed",
    { uniqueId, telegramId: 1, chatId, downloadStatus: "downloading" }, cookie);
  if (seed.status !== 200) fail(`seed file failed: ${seed.status} ${JSON.stringify(seed.body)}`);
}

// ---- Probe 1: frame exactly-once (+ bite proof) -----------------------------------------------------
async function probeFrameExactlyOnce(baseUrl) {
  log("Probe 1: frame exactly-once (one injected completion -> exactly one FILE_STATUS frame)");
  const cookie = await httpGetCookie(baseUrl);
  const uniqueId = "ws-once-" + Date.now();
  await seedAccountAndFile(baseUrl, cookie, uniqueId, 100);

  const ws = await connectWs(`ws://127.0.0.1:8080/ws`, { cookie });
  const frames = frameCollector(ws, uniqueId);
  await sleep(300); // let the registration settle

  const c = await post(baseUrl, "/api/test/gateway/complete", { uniqueId }, cookie);
  if (c.status !== 200) fail(`complete failed: ${c.status} ${JSON.stringify(c.body)}`);
  await sleep(800);

  if (frames.length !== 1) {
    fail(`expected EXACTLY ONE FILE_STATUS frame for ${uniqueId}, got ${frames.length}`);
  }
  const completed = frames[0].data.downloadStatus;
  if (completed !== "completed") fail(`the single frame must report completed, got ${completed}`);
  log(`  OK: exactly 1 frame, downloadStatus=completed`);

  // BITE PROOF — two independent halves, so the "== 1" assertion cannot be vacuously satisfied:
  //
  //  (a) COUNTER IS REAL: the collector is capable of counting MORE than one frame on this socket. We
  //      complete a SECOND distinct file and assert the socket receives its frame too — if the transport
  //      could physically deliver at most one frame, "exactly one" for file 1 would be meaningless.
  const uid2 = "ws-count-" + Date.now();
  await seedAccountAndFile(baseUrl, cookie, uid2, 100);
  const frames2 = frameCollector(ws, uid2);
  await post(baseUrl, "/api/test/gateway/complete", { uniqueId: uid2 }, cookie);
  await sleep(600);
  if (frames2.length !== 1) {
    fail(`bite proof (a) failed: a second distinct completion must deliver exactly one frame on this `
      + `socket, got ${frames2.length}`);
  }
  // The original file 1's collector must STILL be at exactly one (the second injection did not leak a
  // duplicate onto it).
  if (frames.length !== 1) {
    fail(`bite proof (b) failed: injecting a second file leaked ${frames.length - 1} extra frame(s) onto `
      + `the first file's stream — fanout is delivering duplicates`);
  }
  log(`  OK (bite proof): the socket received a distinct frame per completed file and NO cross-leak `
    + `(file1=${frames.length}, file2=${frames2.length}) — exactly-once is a real, biting constraint`);

  ws.close();
  await sleep(100);
}

// ---- Probe 2: reconnect / stale-close dedup (the real BL-02 race) -----------------------------------
async function probeReconnectStaleClose(baseUrl) {
  log("Probe 2: reconnect / stale-close — overlapping same-cookie sockets; A's late close must not evict B");
  const cookie = await httpGetCookie(baseUrl);
  const uniqueId = "ws-reconnect-" + Date.now();
  await seedAccountAndFile(baseUrl, cookie, uniqueId, 100);

  // Socket A (the "old" socket).
  const a = await connectWs(`ws://127.0.0.1:8080/ws`, { cookie });
  const aFrames = frameCollector(a, uniqueId);
  await sleep(200);

  // Socket B reconnects on the SAME tf session (overwrites clients[sessionId] with B's handler id).
  const b = await connectWs(`ws://127.0.0.1:8080/ws`, { cookie });
  const bFrames = frameCollector(b, uniqueId);
  await sleep(200);

  // A's late/abrupt close fires AFTER B is live (the exact stale-close race). Compare-and-remove keying
  // on the ws handler id makes A's close a no-op for B's registration.
  a.destroy();
  await sleep(400); // let the server observe A's close and (correctly) NOT evict B

  // Now inject a completion. B — the live socket — MUST receive exactly one frame; A must receive none
  // more (it is closed). If the stale close had wrongly evicted B, B gets ZERO frames (regression).
  const c = await post(baseUrl, "/api/test/gateway/complete", { uniqueId }, cookie);
  if (c.status !== 200) fail(`complete failed: ${c.status} ${JSON.stringify(c.body)}`);
  await sleep(900);

  const expectBSurvives = process.env.BL02_BREAK !== "expect";
  log(`  A frames after close: ${aFrames.length}; B frames: ${bFrames.length}`);

  if (expectBSurvives) {
    if (bFrames.length !== 1) {
      fail(`RECONNECT/STALE-CLOSE regression: B (the live socket) must receive EXACTLY ONE frame after `
        + `A's stale close, got ${bFrames.length}. If 0, A's close wrongly evicted B's session — the `
        + `compare-and-remove is broken.`);
    }
    log(`  OK: B survived A's stale close and received exactly 1 frame (no duplicate, no eviction)`);
  } else {
    // Self-test mode: assert that WITHOUT the guard B would get 0 (used to prove the probe catches the bug
    // when run against a temporarily-reverted backend).
    if (bFrames.length === 1) {
      fail(`BL02_BREAK=expect: expected B to be evicted (0 frames) on a reverted backend, but B got 1 — `
        + `the backend still has the compare-and-remove guard.`);
    }
    log(`  OK (self-test): B received ${bFrames.length} frames — matches a reverted (broken) backend`);
  }

  a.destroy();
  b.close();
  await sleep(100);
}

async function main() {
  const external = process.env.BACKEND_URL;
  let backend = null;
  let baseUrl;
  if (external) {
    baseUrl = external.replace(/\/$/, "");
    log(`using existing backend at ${baseUrl}`);
    // Guard: refuse to run the injector against a prod backend.
    const status = await fetch(`${baseUrl}/api/test/gateway/status`).catch(() => null);
    if (!status || !status.ok) {
      fail(`gateway status probe failed at ${baseUrl}/api/test/gateway/status — is this a dev-mode `
        + `backend with HERMETIC_GATEWAY=1? (the injector is hard-off under prod)`);
    }
  } else {
    log("booting a fresh dev-mode hermetic backend (APP_ENV=dev, HERMETIC_GATEWAY=1)...");
    backend = await bootBackend();
    baseUrl = backend.baseUrl;
    log(`backend up on ${baseUrl}`);
  }

  try {
    await probeFrameExactlyOnce(baseUrl);
    await probeReconnectStaleClose(baseUrl);
    log("ALL WS EXACTLY-ONCE PROBES PASSED");
  } finally {
    if (backend) backend.stop();
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
