// Minimal dependency-free static file server for the Playwright smoke suite.
// Serves the Next.js static export (web/out) so the smoke spec can load the app shell
// without pulling in an extra static-server dependency.
import { createServer } from "node:http";
import { readFile, stat } from "node:fs/promises";
import { extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../out/", import.meta.url));
const port = Number(process.env.PORT || 4321);

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
  // Strip query, decode, and prevent path traversal outside root.
  const clean = decodeURIComponent(urlPath.split("?")[0]);
  let rel = normalize(clean).replace(/^(\.\.[/\\])+/, "");
  let candidate = join(root, rel);
  try {
    const s = await stat(candidate);
    if (s.isDirectory()) candidate = join(candidate, "index.html");
  } catch {
    // Fall back to <path>.html (Next static export names routes foo.html).
    if (!extname(candidate)) candidate = `${candidate}.html`;
  }
  return candidate;
}

const server = createServer(async (req, res) => {
  try {
    const file = await resolveFile(req.url || "/");
    const body = await readFile(file);
    res.writeHead(200, { "Content-Type": MIME[extname(file)] || "application/octet-stream" });
    res.end(body);
  } catch {
    res.writeHead(404, { "Content-Type": "text/plain" });
    res.end("Not found");
  }
});

server.listen(port, () => {
  console.log(`static-server serving ${root} on http://127.0.0.1:${port}`);
});
