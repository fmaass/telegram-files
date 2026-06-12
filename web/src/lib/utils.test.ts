import { describe, expect, it } from "vitest";
import { parseProxyString, split } from "./utils";

describe("parseProxyString", () => {
  it("parses mtproto proxies", () => {
    const p = parseProxyString("mtproto://proxy.example.com:443?secret=abc123");
    expect(p).toEqual({
      name: "mtproto proxy",
      server: "proxy.example.com",
      port: 443,
      username: "",
      password: "",
      secret: "abc123",
      type: "mtproto",
    });
  });

  it("parses http proxies with credentials", () => {
    const p = parseProxyString("http://user:pass@10.0.0.1:8080");
    expect(p).toMatchObject({
      server: "10.0.0.1",
      port: 8080,
      username: "user",
      password: "pass",
      type: "http",
    });
  });

  it("parses socks5 proxies without credentials", () => {
    const p = parseProxyString("socks5://10.0.0.2:1080");
    expect(p).toMatchObject({
      server: "10.0.0.2",
      port: 1080,
      type: "socks5",
    });
  });

  it("returns null for garbage input", () => {
    expect(parseProxyString("not-a-proxy")).toBeNull();
    expect(parseProxyString("")).toBeNull();
  });
});

describe("split", () => {
  it("splits and trims", () => {
    expect(split(",", "a, b , c")).toEqual(["a", "b", "c"]);
  });

  it("returns empty array for empty input", () => {
    expect(split(",", undefined)).toEqual([]);
    expect(split(",", "")).toEqual([]);
  });
});
