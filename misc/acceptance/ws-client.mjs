// Minimal dependency-free WebSocket client for the CI dedup probe.
//
// Node's global WHATWG `WebSocket` does NOT allow setting a `Cookie` request header, but the probe
// MUST bind a specific `tf` session cookie the same way a browser does. So this performs the RFC-6455
// Upgrade handshake over a raw socket via `http.request` (which DOES let us set arbitrary headers) and
// then parses server->client text frames off the resulting socket. We only READ frames (server never
// expects client frames here), so the reader handles unmasked server frames; a `close()` sends a
// masked close frame per spec. Zero npm dependencies.
import { request as httpRequest } from "node:http";
import { randomBytes, createHash } from "node:crypto";

const GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

export function connectWs(url, { cookie } = {}) {
  const u = new URL(url);
  const key = randomBytes(16).toString("base64");
  const expectedAccept = createHash("sha1").update(key + GUID).digest("base64");

  return new Promise((resolve, reject) => {
    const headers = {
      Connection: "Upgrade",
      Upgrade: "websocket",
      "Sec-WebSocket-Version": "13",
      "Sec-WebSocket-Key": key,
      Host: u.host,
    };
    if (cookie) headers.Cookie = cookie;

    const req = httpRequest({
      host: u.hostname,
      port: u.port || 80,
      path: u.pathname + u.search,
      method: "GET",
      headers,
    });

    req.on("upgrade", (res, socket) => {
      if (res.headers["sec-websocket-accept"] !== expectedAccept) {
        socket.destroy();
        reject(new Error("bad Sec-WebSocket-Accept — handshake rejected"));
        return;
      }
      resolve(new WsConn(socket));
    });
    req.on("response", (res) => {
      reject(new Error(`WS upgrade failed: HTTP ${res.statusCode}`));
    });
    req.on("error", reject);
    req.end();
  });
}

class WsConn {
  constructor(socket) {
    this.socket = socket;
    this.textHandlers = [];
    this.closeHandlers = [];
    this._buf = Buffer.alloc(0);
    socket.on("data", (chunk) => this._onData(chunk));
    socket.on("close", () => this.closeHandlers.forEach((h) => h()));
    socket.on("error", () => { /* surfaced via close */ });
  }

  onText(fn) { this.textHandlers.push(fn); }
  onClose(fn) { this.closeHandlers.push(fn); }

  _onData(chunk) {
    this._buf = Buffer.concat([this._buf, chunk]);
    // Parse as many complete frames as are buffered.
    for (;;) {
      if (this._buf.length < 2) return;
      const b0 = this._buf[0];
      const b1 = this._buf[1];
      const opcode = b0 & 0x0f;
      const masked = (b1 & 0x80) !== 0;
      let len = b1 & 0x7f;
      let offset = 2;
      if (len === 126) {
        if (this._buf.length < 4) return;
        len = this._buf.readUInt16BE(2);
        offset = 4;
      } else if (len === 127) {
        if (this._buf.length < 10) return;
        len = Number(this._buf.readBigUInt64BE(2));
        offset = 10;
      }
      const maskLen = masked ? 4 : 0;
      if (this._buf.length < offset + maskLen + len) return; // wait for the rest
      let payload = this._buf.subarray(offset + maskLen, offset + maskLen + len);
      if (masked) {
        const mask = this._buf.subarray(offset, offset + 4);
        payload = Buffer.from(payload);
        for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
      }
      this._buf = this._buf.subarray(offset + maskLen + len);

      if (opcode === 0x1) {
        const text = payload.toString("utf8");
        this.textHandlers.forEach((h) => h(text));
      } else if (opcode === 0x9) {
        // ping -> reply pong (masked, empty)
        this._sendFrame(0xa, Buffer.alloc(0));
      } else if (opcode === 0x8) {
        this.closeHandlers.forEach((h) => h());
        this.socket.end();
        return;
      }
      // opcode 0xa (pong) ignored
    }
  }

  _sendFrame(opcode, payload) {
    const mask = randomBytes(4);
    const len = payload.length;
    let header;
    if (len < 126) {
      header = Buffer.from([0x80 | opcode, 0x80 | len]);
    } else if (len < 65536) {
      header = Buffer.alloc(4);
      header[0] = 0x80 | opcode;
      header[1] = 0x80 | 126;
      header.writeUInt16BE(len, 2);
    } else {
      header = Buffer.alloc(10);
      header[0] = 0x80 | opcode;
      header[1] = 0x80 | 127;
      header.writeBigUInt64BE(BigInt(len), 2);
    }
    const masked = Buffer.from(payload);
    for (let i = 0; i < masked.length; i++) masked[i] ^= mask[i % 4];
    try {
      this.socket.write(Buffer.concat([header, mask, masked]));
    } catch { /* socket gone */ }
  }

  // Abrupt transport close (simulates a stale/dropped socket without a clean WS close frame).
  destroy() {
    try { this.socket.destroy(); } catch { /* ignore */ }
  }

  // Clean WS close.
  close() {
    try { this._sendFrame(0x8, Buffer.alloc(0)); this.socket.end(); } catch { /* ignore */ }
  }
}
