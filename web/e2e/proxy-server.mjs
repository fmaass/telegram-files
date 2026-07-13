// Static file server + API/WS reverse proxy for the Playwright DOWNLOAD e2e.
//
// Serves the Next.js static export (web/out) AND reverse-proxies /api/* and /ws to the real Java
// backend (booted hermetically by global-setup on BACKEND_PORT). This lets the REAL built frontend
// run against the REAL backend from a single origin — the frontend fetches relative /api/... which
// this server forwards to the backend, so the browser sees one host with no CORS.
import { createServer, request as httpRequest } from "node:http";
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

async function resolveFile(urlPath) {
  const clean = decodeURIComponent(urlPath.split("?")[0]);
  const rel = normalize(clean).replace(/^(\.\.[/\\])+/, "");
  let candidate = join(root, rel);
  try {
    const s = await stat(candidate);
    if (s.isDirectory()) candidate = join(candidate, "index.html");
  } catch {
    if (!extname(candidate)) candidate = `${candidate}.html`;
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

server.listen(port, () => {
  console.log(`proxy-server serving ${root} on http://127.0.0.1:${port} -> backend ${backendHost}:${backendPort}`);
});
