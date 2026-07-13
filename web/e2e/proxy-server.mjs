// Static file server + API/WS reverse proxy for the Playwright DOWNLOAD e2e.
//
// Serves the Next.js static export (web/out) AND reverse-proxies /api/* and /ws to the real Java
// backend (booted hermetically by global-setup on BACKEND_PORT). This lets the REAL built frontend
// run against the REAL backend from a single origin — the frontend fetches relative /api/... which
// this server forwards to the backend, so the browser sees one host with no CORS.
import { createServer, request as httpRequest } from "node:http";
import { connect as netConnect } from "node:net";
import { readFile, stat } from "node:fs/promises";
import { extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../out/", import.meta.url));
const port = Number(process.env.PORT || 4322);
const backendPort = Number(process.env.BACKEND_PORT || 8080);
const backendHost = process.env.BACKEND_HOST || "127.0.0.1";

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".txt": "text/plain; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".ico": "image/x-icon",
  ".woff2": "font/woff2",
};

async function fileExists(p) {
  try {
    const s = await stat(p);
    return s.isFile();
  } catch {
    return false;
  }
}

async function resolveFile(urlPath) {
  const clean = decodeURIComponent(urlPath.split("?")[0]);
  const rel = normalize(clean).replace(/^(\.\.[/\\])+/, "");
  const candidate = join(root, rel);
  if (await fileExists(candidate)) return candidate;
  // Next.js static export writes a route like `/files` as a SIBLING `files.html` file — even when a
  // `files/` directory also exists (for the route's data chunks). Prefer the `.html` sibling over the
  // directory's non-existent index.html so nested routes (`/files`, `/accounts`) resolve, not just `/`.
  if (!extname(candidate)) {
    const asHtml = `${candidate}.html`;
    if (await fileExists(asHtml)) return asHtml;
  }
  try {
    const s = await stat(candidate);
    if (s.isDirectory()) return join(candidate, "index.html");
  } catch {
    if (!extname(candidate)) return `${candidate}.html`;
  }
  return candidate;
}

function proxyToBackend(req, res) {
  // Same-origin from the browser's view (page and /api share this server's origin). Strip the Origin
  // header so the backend's dev CORS allowlist (which only permits the :3000 dev server) does not
  // treat the forwarded request as a disallowed cross-origin call and 403 it.
  const headers = { ...req.headers, host: `${backendHost}:${backendPort}` };
  delete headers.origin;
  delete headers.referer;
  const options = {
    host: backendHost,
    port: backendPort,
    method: req.method,
    path: req.url,
    headers,
  };
  const upstream = httpRequest(options, (up) => {
    res.writeHead(up.statusCode || 502, up.headers);
    up.pipe(res);
  });
  upstream.on("error", (err) => {
    res.writeHead(502, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: `proxy: ${err.message}` }));
  });
  req.pipe(upstream);
}

const server = createServer(async (req, res) => {
  const url = req.url || "/";
  // Reverse-proxy the API surface (and the health/metrics/version infra) to the backend.
  if (url.startsWith("/api/") || url === "/health" || url === "/metrics" || url === "/version") {
    proxyToBackend(req, res);
    return;
  }
  try {
    const file = await resolveFile(url);
    const body = await readFile(file);
    res.writeHead(200, { "Content-Type": MIME[extname(file)] || "application/octet-stream" });
    res.end(body);
  } catch {
    res.writeHead(404, { "Content-Type": "text/plain" });
    res.end("Not found");
  }
});

// WebSocket upgrade proxy: the frontend opens a same-origin ws://<this-host>/ws, which the browser
// upgrades via this server. Forward the raw upgrade handshake + the byte stream to the backend /ws so
// the REAL Vert.x websocket (session cookie carried in the upgrade headers) fans out to this socket.
// Without this, /ws would 404 here and the SPA's websocket never reaches the backend.
server.on("upgrade", (req, clientSocket, head) => {
  const upstream = netConnect(backendPort, backendHost, () => {
    // Re-serialize the upgrade request line + headers to the backend, stripping cross-origin markers so
    // the backend's dev CORS/allowlist does not reject the forwarded handshake (mirrors proxyToBackend).
    const headers = { ...req.headers, host: `${backendHost}:${backendPort}` };
    delete headers.origin;
    delete headers.referer;
    let raw = `${req.method} ${req.url} HTTP/1.1\r\n`;
    for (const [k, v] of Object.entries(headers)) {
      const vals = Array.isArray(v) ? v : [v];
      for (const one of vals) raw += `${k}: ${one}\r\n`;
    }
    raw += "\r\n";
    upstream.write(raw);
    if (head && head.length) upstream.write(head);
    upstream.pipe(clientSocket);
    clientSocket.pipe(upstream);
  });
  const cleanup = () => { try { upstream.destroy(); } catch { /* ignore */ } try { clientSocket.destroy(); } catch { /* ignore */ } };
  upstream.on("error", cleanup);
  clientSocket.on("error", cleanup);
});

server.listen(port, () => {
  console.log(`proxy-server serving ${root} on http://127.0.0.1:${port} -> backend ${backendHost}:${backendPort}`);
});
