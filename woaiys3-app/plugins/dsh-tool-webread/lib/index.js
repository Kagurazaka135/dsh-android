/**
 * 无头上网阅读插件（v0.1.0）—— 给 AI 一个"把网页正文取回来"的工具。
 *
 * 思路直接抄 Via 浏览器 6.7.1 的标答（d/h/a/f/a.smali:65）：
 *   · 不打包内核，用系统的 WebView；
 *   · 用之前**先探测**内核是谁、几版（三层降级：公开 API → 反射 → 放弃）；
 *   · 探不到就别硬跑 —— 换下一档引擎，全都不行就**明确报错**，不假装成功；
 *   · 版本号当能力开关，而不是当装饰。
 *
 * 插件里对应的引擎阶梯（从好到差）：
 *   E1 native —— App 的 /browser 桥（系统 WebView，真渲染 JS、真执行 DOM）
 *   E2 http   —— Node 自己抓原始 HTML 再去标签（没有 JS，但基本盘还在）
 *   E3 都没有 —— 回 ok:false + 具体哪个环节断了 + 怎么修
 *
 * 每条结果都会带 `engine` 与 `kernel`，让调用方知道"这次是站在什么上面拿到的"。
 */
import { defineTool } from "@deepseek-ai/dsh-tools";
import { request as httpRequest } from "node:http";
import { request as httpsRequest } from "node:https";
import { gunzipSync, inflateSync, brotliDecompressSync } from "node:zlib";

const name = "tool-webread";
const inject = ["tools"];

/** 老到这个版本以下就标记 degraded（正文可能取不全），但不拦着用。 */
const MIN_CHROME = 60;
const DEFAULT_MAX_CHARS = 8000;
const HARD_MAX_CHARS = 50000;

const port = () => parseInt(process.env.APP_NOTIFY_PORT || "3081", 10);
const token = () => process.env.APP_LOCAL_TOKEN || "";

// ─────────────────────────── E1：App 的 /browser 桥 ───────────────────────────

/** 调 App 的 /browser 路由（本地服务已鉴权）。 */
function browserOp(op, args, timeoutMs) {
  const payload = JSON.stringify({ op, args: args || {}, timeout_ms: timeoutMs || 30000 });
  return new Promise((resolve) => {
    const req = httpRequest({
      host: "127.0.0.1",
      port: port(),
      path: "/browser",
      method: "POST",
      timeout: (timeoutMs || 30000) + 5000,
      headers: {
        "Content-Type": "application/json",
        "Content-Length": Buffer.byteLength(payload),
        "X-DSH-Token": token()
      }
    }, (res) => {
      let d = "";
      res.setEncoding("utf8");
      res.on("data", (c) => { d += c; if (d.length > 4 * 1024 * 1024) req.destroy(); });
      res.on("end", () => {
        try { resolve(JSON.parse(d || "{}")); }
        catch { resolve({ ok: false, reason: "parse-failed", error: "App 响应解析失败: " + String(d).slice(0, 160) }); }
      });
    });
    req.on("error", () => resolve({ ok: false, reason: "app-unreachable", error: "App 本地服务不可用（请先启动 DeepSeek Harness）" }));
    req.on("timeout", () => { req.destroy(); resolve({ ok: false, reason: "timeout", error: "App 本地服务超时" }); });
    req.write(payload);
    req.end();
  });
}

// ─────────────────────────── 内核探测（Via 那一层） ───────────────────────────

/** 从 UA 里抠 Chrome 主版本号。抠不到返回 0（Via 遇到这种会再问包版本，我们这层只能报 unknown）。 */
function parseChromeMajor(ua) {
  const m = /Chrome\/(\d+)/.exec(String(ua || ""));
  return m ? parseInt(m[1], 10) : 0;
}

/**
 * 探测当前可用的引擎。
 * 和 Via 的区别：Via 拿到包信息就知道"内核在不在"；插件够不到 PackageManager，
 * 只能靠 caps 是否应答 + UA 里有没有 Chrome 来推断，所以结论里带 confidence。
 */
async function probe() {
  const caps = await browserOp("caps", {}, 8000);

  // App 根本不答 —— 可能没启动、可能是别的宿主（PC 版 dsh）
  if (!caps || caps.ok !== true) {
    return {
      nativeUsable: false,
      engine: "none",
      reason: caps && caps.reason ? caps.reason : "app-unreachable",
      error: caps && caps.error ? caps.error : "拿不到 App 的浏览器能力自述",
      kernel: { packageName: "", versionName: "", chromeMajor: 0, level: "unknown" },
      httpUsable: true
    };
  }

  const ua = String(caps.webviewUa || "");
  const chrome = parseChromeMajor(ua);
  // UA 里没 Chrome 标记：可能是 ROM 魔改，也可能根本不是 webview 宿主 —— 当成 unknown，不拦
  const level = chrome <= 0 ? "unknown" : chrome >= MIN_CHROME ? "ok" : "old";

  return {
    nativeUsable: true,
    engine: "native",
    reason: "",
    error: "",
    kernel: {
      packageName: "",          // 原生 caps 目前不暴露包名；UA 里的版本才是我们真正用到的信息
      versionName: chrome > 0 ? "Chromium " + chrome : "",
      chromeMajor: chrome,
      level,
      userAgent: ua
    },
    httpUsable: true
  };
}

// ─────────────────────────── E1 读 ───────────────────────────

async function readViaNative(url, timeoutMs) {
  const opened = await browserOp("open", { url, newTab: true }, timeoutMs);
  if (!opened || opened.ok !== true) {
    return { ok: false, reason: opened && opened.reason || "open-failed", error: opened && opened.error || "打开失败" };
  }
  const tabId = opened.tabId || "";
  let read;
  try {
    read = await browserOp("read", {}, 15000);
  } finally {
    // 不管读成没读成，都把这个临时页签关掉 —— 阅读器不该在用户屏幕上留一堆页签
    if (tabId) browserOp("tabs.close", { tabId }, 8000).catch(() => {});
  }
  if (!read || read.ok !== true) {
    return { ok: false, reason: read && read.reason || "read-failed", error: read && read.error || "取文失败" };
  }
  return {
    ok: true,
    url: opened.url || url,
    title: opened.title || "",
    text: String(read.text || ""),
    chars: Number(read.chars || 0),
    truncated: !!read.truncated,
    loadState: opened.loadState || ""
  };
}

// ─────────────────────────── E2：原始 HTTP ───────────────────────────

/**
 * 安全闸门：只放行 http/https，并且**拒绝回环/私网**。
 * 这一条不是我们自己加的洁癖 —— 原生 BrowserHost.policyReason 就是靠它防止 AI 去打
 * App 自己的本地服务（:3080 / :3081 / node 引擎端口）。降级路径绝不能变成绕开它的后门。
 */
function urlPolicy(raw) {
  let u;
  try { u = new URL(String(raw || "")); }
  catch { return "不是合法 URL"; }
  if (u.protocol !== "http:" && u.protocol !== "https:") return "只允许 http/https";
  const host = u.hostname.replace(/^\[|\]$/g, "").toLowerCase();
  if (host === "localhost" || host === "::1" || host.endsWith(".localhost")) return "禁止访问回环地址";
  if (/^\d+\.\d+\.\d+\.\d+$/.test(host)) {
    const p = host.split(".").map(Number);
    if (p[0] === 10) return "禁止访问内网地址";
    if (p[0] === 127) return "禁止访问回环地址";
    if (p[0] === 169 && p[1] === 254) return "禁止访问链路本地地址";
    if (p[0] === 192 && p[1] === 168) return "禁止访问内网地址";
    if (p[0] === 172 && p[1] >= 16 && p[1] <= 31) return "禁止访问内网地址";
    if (p[0] === 0) return "禁止访问 0.0.0.0/8";
  }
  return null;
}

const ENTITIES = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " ", "#39": "'", "#x27": "'" };

function decodeEntities(s) {
  return s.replace(/&(#x?[0-9a-fA-F]+|[a-zA-Z]+);/g, (m, key) => {
    const k = key.toLowerCase();
    if (ENTITIES[k] !== undefined) return ENTITIES[k];
    if (k.startsWith("#x")) { const c = parseInt(k.slice(2), 16); return Number.isFinite(c) ? String.fromCodePoint(c) : m; }
    if (k.startsWith("#")) { const c = parseInt(k.slice(1), 10); return Number.isFinite(c) ? String.fromCodePoint(c) : m; }
    return m;
  });
}

/** 极简 HTML → 正文。不是 Readability，但足够在"内核不可用"这一档给出可用的东西。 */
function htmlToText(html) {
  let s = String(html || "");
  const title = (/<title[^>]*>([\s\S]*?)<\/title>/i.exec(s) || [, ""])[1];
  s = s
    .replace(/<!--[\s\S]*?-->/g, " ")
    .replace(/<(script|style|noscript|svg|iframe|template|canvas|head)[\s\S]*?<\/\1>/gi, " ")
    .replace(/<br\s*\/?>/gi, "\n")
    .replace(/<\/(p|div|li|tr|h[1-6]|section|article|blockquote|pre)>/gi, "\n")
    .replace(/<[^>]+>/g, " ");
  s = decodeEntities(s);
  s = s.replace(/[ \t ]+/g, " ").replace(/\n{3,}/g, "\n\n").replace(/^\s+|\s+$/g, "");
  return { title: decodeEntities(String(title)).replace(/\s+/g, " ").trim(), text: s };
}

/** 按 Content-Encoding 解压；解不开就原样返回（可能是站点乱标编码）。 */
function decompress(buf, encoding) {
  const e = String(encoding || "").toLowerCase();
  try {
    if (e.includes("br")) return brotliDecompressSync(buf);
    if (e.includes("gzip")) return gunzipSync(buf);
    if (e.includes("deflate")) return inflateSync(buf);
  } catch { /* 标注与实际不符 —— 交给下面按原文处理 */ }
  return buf;
}

function decodeBody(buf, contentType) {
  const m = /charset=([\w-]+)/i.exec(String(contentType || ""));
  const cs = m ? m[1].toLowerCase() : "utf-8";
  try {
    // Node 26 带完整 ICU，gbk/gb2312/big5 都能解
    return new TextDecoder(cs).decode(buf);
  } catch {
    return buf.toString("utf8");
  }
}

function httpGet(url, timeoutMs, redirectsLeft = 5) {
  return new Promise((resolve) => {
    const bad = urlPolicy(url);
    if (bad) return resolve({ ok: false, reason: "blocked-url", error: "导航被拒：" + bad });

    let u;
    try { u = new URL(url); } catch { return resolve({ ok: false, reason: "bad-url", error: "URL 解析失败" }); }
    const isHttps = u.protocol === "https:";
    const req = (isHttps ? httpsRequest : httpRequest)({
      protocol: u.protocol,
      hostname: u.hostname,
      port: u.port || (isHttps ? 443 : 80),
      path: u.pathname + u.search,
      method: "GET",
      timeout: timeoutMs,
      headers: {
        // 伪装成移动端 Chrome：很多站对"无 UA 的裸请求"直接回 403/空壳
        "User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        "Accept-Encoding": "gzip, deflate, br"
      }
    }, (res) => {
      const status = res.statusCode || 0;
      const loc = res.headers.location;
      if (status >= 300 && status < 400 && loc && redirectsLeft > 0) {
        res.resume();
        const next = new URL(loc, url).toString();
        return resolve(httpGet(next, timeoutMs, redirectsLeft - 1));
      }
      const chunks = [];
      res.on("data", (c) => { chunks.push(c); if (chunks.reduce((n, b) => n + b.length, 0) > 8 * 1024 * 1024) req.destroy(); });
      res.on("end", () => {
        const raw = Buffer.concat(chunks);
        const buf = decompress(raw, res.headers["content-encoding"]);
        resolve({
          ok: status >= 200 && status < 400,
          status,
          contentType: res.headers["content-type"] || "",
          body: decodeBody(buf, res.headers["content-type"]),
          reason: status >= 200 && status < 400 ? "" : "http-" + status,
          error: status >= 200 && status < 400 ? "" : "服务器返回 HTTP " + status
        });
      });
      res.on("error", (e) => resolve({ ok: false, reason: "read-error", error: String(e && e.message || e) }));
    });
    req.on("error", (e) => resolve({ ok: false, reason: "net-error", error: String(e && e.message || e) }));
    req.on("timeout", () => { req.destroy(); resolve({ ok: false, reason: "timeout", error: "HTTP 请求超时" }); });
    req.end();
  });
}

async function readViaHttp(url, timeoutMs) {
  const r = await httpGet(url, timeoutMs);
  if (!r.ok) return { ok: false, reason: r.reason, error: r.error };
  const { title, text } = htmlToText(r.body);
  return {
    ok: true,
    url,
    title,
    text,
    chars: text.length,
    truncated: false,
    loadState: "http-" + r.status,
    note: "原始 HTML（未执行 JS）；若要 JS 渲染后的内容，走 native 通道"
  };
}

// ─────────────────────────── 工具 ───────────────────────────

function pick(src, keys) {
  const out = {};
  for (const k of keys) if (src && src[k] !== undefined) out[k] = src[k];
  if (out.ok === undefined) out.ok = false;
  return out;
}

function errText(v) {
  const lines = ["❌ " + (v.error || v.reason || "失败")];
  if (v.hint) lines.push("提示：" + v.hint);
  if (Array.isArray(v.attempts)) {
    for (const a of v.attempts) lines.push(`  · ${a.engine}：${a.reason || "失败"}${a.error ? "（" + a.error + "）" : ""}`);
  }
  return lines.join("\n");
}

function clip(text, max) {
  const s = String(text || "");
  return s.length > max ? s.slice(0, max) : s;
}

const KERNEL_SCHEMA = {
  type: "object", additionalProperties: true,
  properties: {
    packageName: { type: "string" }, versionName: { type: "string" },
    chromeMajor: { type: "number" }, level: { type: "string" }, userAgent: { type: "string" }
  }
};

export function apply(ctx) {
  // 1) 探测：Via 的 caps —— 用之前先看看脚下是什么
  ctx.tools.register(defineTool({
    name: "web_probe",
    description:
      "探测当前可用的上网引擎与系统 WebView 内核版本（不抓页面）。" +
      "返回引擎阶梯：native（App 的系统 WebView，能跑 JS）/ http（原始抓取）/ none，以及内核版本与降级建议。",
    parameters: {},
    output: {
      schema: {
        type: "object", additionalProperties: false,
        properties: {
          ok: { type: "boolean" }, engine: { type: "string" }, nativeUsable: { type: "boolean" },
          httpUsable: { type: "boolean" }, kernel: KERNEL_SCHEMA,
          degraded: { type: "boolean" }, hint: { type: "string" },
          reason: { type: "string" }, error: { type: "string" }
        }
      },
      render: (_a, v) => [{
        type: "text",
        text: v.ok
          ? [
              `可用引擎：${v.engine}${v.nativeUsable ? "（系统 WebView，可执行 JS）" : "（仅原始 HTTP，不执行 JS）"}`,
              `内核版本：${v.kernel && v.kernel.versionName ? v.kernel.versionName : "未知"}` +
                (v.kernel && v.kernel.chromeMajor ? `（Chromium ${v.kernel.chromeMajor}）` : ""),
              v.degraded ? "⚠️ 内核偏旧，复杂页面可能取不全 —— 建议在应用商店更新「Android System WebView」" : "",
              v.hint || ""
            ].filter(Boolean).join("\n")
          : errText(v)
      }],
    },
    async execute() {
      const p = await probe();
      const degraded = p.kernel.chromeMajor > 0 && p.kernel.chromeMajor < MIN_CHROME;
      const out = {
        ok: p.nativeUsable || p.httpUsable,
        engine: p.engine === "native" ? "native" : "http",
        nativeUsable: p.nativeUsable,
        httpUsable: p.httpUsable,
        kernel: p.kernel,
        degraded
      };
      if (!p.nativeUsable) out.hint = "原生 WebView 通道不可用（" + (p.error || p.reason) + "）；将自动降级到原始 HTTP 抓取，JS 渲染的内容拿不到";
      else if (degraded) out.hint = "WebView 内核为 Chromium " + p.kernel.chromeMajor + "（建议 ≥" + MIN_CHROME + "）；可用于降级后仍会尝试 native，失败再退 HTTP";
      return out;
    }
  }));

  // 2) 读网页：按 Via 的阶梯一档一档试
  ctx.tools.register(defineTool({
    name: "web_read",
    description:
      "无头读取一个网页的正文（不弹界面、不留页签）。" +
      "优先走系统 WebView（能拿到 JS 渲染后的内容）；WebView 不可用或取不到字时自动降级为原始 HTTP 抓取；" +
      "两条路都断了会明确报错并说明是哪一环断的。" +
      "只允许 http/https，且禁止访问本机/内网地址。",
    parameters: {
      url: { type: "string", required: true, description: "要读的 http(s) 地址" },
      maxChars: { type: "number", description: "正文最多返回多少字符（默认 8000，上限 50000）" },
      prefer: {
        type: "string", enum: ["auto", "native", "http"],
        description: "默认 auto（native 优先、失败降级）；native=只用 WebView；http=只用原始抓取"
      }
    },
    output: {
      schema: {
        type: "object", additionalProperties: false,
        properties: {
          ok: { type: "boolean" }, url: { type: "string" }, title: { type: "string" },
          text: { type: "string" }, chars: { type: "number" }, truncated: { type: "boolean" },
          engine: { type: "string" }, kernel: KERNEL_SCHEMA, loadState: { type: "string" },
          degraded: { type: "boolean" }, note: { type: "string" },
          attempts: { type: "array", items: { type: "object", additionalProperties: true } },
          reason: { type: "string" }, error: { type: "string" }, hint: { type: "string" }
        }
      },
      render: (_a, v) => [{
        type: "text",
        text: v.ok
          ? [
              `📄 ${v.title || "(无标题)"}`,
              v.url,
              `引擎：${v.engine}${v.degraded ? "（降级）" : ""}｜${v.chars} 字${v.truncated ? "（已截断）" : ""}`,
              v.note || "",
              "",
              v.text
            ].filter((x) => x !== "").join("\n")
          : errText(v)
      }],
    },
    async execute(args) {
      const url = String(args.url || "");
      const maxChars = Math.min(Math.max(Number(args.maxChars) || DEFAULT_MAX_CHARS, 200), HARD_MAX_CHARS);
      const prefer = String(args.prefer || "auto");

      const bad = urlPolicy(url);
      if (bad) {
        return { ok: false, reason: "blocked-url", error: "导航被拒：" + bad, hint: "只允许公网 http/https 地址" };
      }

      const p = await probe();
      const degraded = p.kernel.chromeMajor > 0 && p.kernel.chromeMajor < MIN_CHROME;
      const attempts = [];

      const useNative = prefer !== "http" && p.nativeUsable;
      if (useNative) {
        const r = await readViaNative(url, 30000);
        if (r.ok && r.text && r.text.trim().length > 0) {
          const text = clip(r.text, maxChars);
          return {
            ok: true, url: r.url, title: r.title, text,
            chars: text.length, truncated: r.truncated || r.text.length > maxChars,
            engine: "native", kernel: p.kernel, loadState: r.loadState, degraded
          };
        }
        attempts.push({
          engine: "native",
          reason: r.ok ? "empty-text" : r.reason,
          error: r.ok ? "WebView 取到了空正文（可能是纯 Canvas/图片站，或页面还没渲染完）" : r.error
        });
        if (prefer === "native") {
          return {
            ok: false, reason: attempts[0].reason, error: attempts[0].error,
            attempts, engine: "native", kernel: p.kernel, degraded,
            hint: "prefer=native 时不会降级；要自动降级请用 prefer=auto"
          };
        }
      } else if (prefer === "native") {
        return {
          ok: false, reason: p.reason || "native-unavailable",
          error: p.error || "原生 WebView 通道不可用",
          engine: "none", kernel: p.kernel,
          hint: "确认 DeepSeek Harness 已在运行；或改用 prefer=auto 走原始抓取"
        };
      }

      const h = await readViaHttp(url, 30000);
      if (h.ok && h.text && h.text.trim().length > 0) {
        const text = clip(h.text, maxChars);
        return {
          ok: true, url: h.url, title: h.title, text,
          chars: text.length, truncated: h.text.length > maxChars,
          engine: "http", kernel: p.kernel, loadState: h.loadState,
          degraded: true, note: h.note,
          attempts: attempts.length ? attempts : undefined
        };
      }
      attempts.push({ engine: "http", reason: h.reason, error: h.error });

      return {
        ok: false, reason: "all-engines-failed",
        error: "两条引擎都没取到正文",
        attempts, kernel: p.kernel,
        hint: "native 挂了通常是 App 没启动或 WebView 内核异常；http 挂了通常是网络/代理或站点反爬。可先用 web_probe 看当前引擎状态"
      };
    }
  }));
}

// 给 test/selftest.mjs 用的纯函数出口。工具调用路径不经过这里，闸门仍然只在 urlPolicy 里生效。
export const __internals = { urlPolicy, htmlToText, decompress, decodeBody, parseChromeMajor };

export { inject, name };
