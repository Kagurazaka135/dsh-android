/**
 * 自检脚本 —— 拿到手机上让 dsh 自己跑，判断插件在**这台机器**上到底能不能用。
 *
 * 用法（在插件目录里）：
 *   node test/selftest.mjs
 *
 * 会打印 [PASS] / [FAIL] / [SKIP] 三种行；有 FAIL 时退出码为 1。
 * SKIP 表示"这台机器上条件不具备"（例如没联网），不是失败。
 *
 * 三层检查：
 *   单元 —— 不碰网络、不碰 App，纯函数（拦截规则 / HTML 抽取 / 解压 / 版本号解析）
 *   契约 —— 插件导出与工具注册是否符合 dsh 插件规范
 *   实况 —— 真的调一次 web_probe / web_read，看这台机器当前能走哪条通道
 */
import { gzipSync, brotliCompressSync } from "node:zlib";

let pass = 0, fail = 0, skip = 0;
const log = (tag, name, detail) => {
  console.log(`${tag} ${name}${detail ? "  — " + detail : ""}`);
  if (tag === "[PASS]") pass++; else if (tag === "[FAIL]") fail++; else skip++;
};
const ok = (cond, name, detail) => log(cond ? "[PASS]" : "[FAIL]", name, cond ? "" : detail);

console.log("=== dsh-tool-webread selftest ===");
console.log("node " + process.version + " / " + process.platform + "-" + process.arch);
console.log("");

// ── 0. 加载插件（需要 @deepseek-ai/dsh-tools 能解析到） ─────────────────────
let mod;
try {
  mod = await import("../lib/index.js");
} catch (e) {
  console.error("[FAIL] 无法加载插件：" + (e && e.message));
  console.error("");
  console.error("多半是 @deepseek-ai/dsh-tools 解析不到。请在**已安装的位置**里运行：");
  console.error("  cd <dshroot>/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-tool-webread");
  console.error("  node test/selftest.mjs");
  process.exit(2);
}

// ── 1. 契约 ────────────────────────────────────────────────────────────────
console.log("-- 插件契约 --");
ok(mod.name === "tool-webread", "name 导出正确", "name=" + mod.name);
ok(Array.isArray(mod.inject) && mod.inject.includes("tools"), "inject 含 tools", JSON.stringify(mod.inject));
ok(typeof mod.apply === "function", "apply 是函数");

const tools = [];
mod.apply({ tools: { register: (d) => { tools.push(d); return d; } } });
const names = tools.map((t) => t.name).sort();
ok(JSON.stringify(names) === JSON.stringify(["web_probe", "web_read"]), "注册了 2 个工具", names.join(","));
ok(tools.every((t) => t.output && t.output.schema && t.output.schema.additionalProperties === false),
  "输出 schema 均声明 additionalProperties:false");
console.log("");

// ── 2. 单元：拦截规则 ──────────────────────────────────────────────────────
console.log("-- 单元：地址闸门 --");
const { urlPolicy } = mod.__internals;
const blockedCases = [
  ["http://127.0.0.1:3080/api", "回环 IPv4"],
  ["http://localhost:3081/browser", "localhost"],
  ["http://[::1]:3080/", "回环 IPv6"],
  ["http://10.0.0.5/", "10/8 内网"],
  ["http://192.168.1.10/", "192.168 内网"],
  ["http://172.16.3.4/", "172.16-31 内网"],
  ["http://169.254.1.1/", "链路本地"],
  ["file:///etc/passwd", "非 http(s)"],
  ["not-a-url", "非法 URL"]
];
for (const [u, label] of blockedCases) {
  ok(urlPolicy(u) !== null, "拦截 " + label, "应该被拦但放行了：" + u);
}
ok(urlPolicy("https://example.com/") === null, "放行公网 https");
ok(urlPolicy("http://example.com/a?b=c") === null, "放行公网 http");
console.log("");

// ── 3. 单元：HTML 抽取 ─────────────────────────────────────────────────────
console.log("-- 单元：正文抽取 --");
const { htmlToText } = mod.__internals;
const r1 = htmlToText(`<html><head><title>标题 A</title><style>body{color:red}</style></head>
<body><script>var x=1;</script><h1>大标题</h1><p>正文 &amp; 实体 &lt;x&gt;</p><!-- 注释 --></body></html>`);
ok(r1.title === "标题 A", "取到 title", r1.title);
ok(r1.text.includes("大标题") && r1.text.includes("正文"), "保留正文", r1.text.slice(0, 80));
ok(!r1.text.includes("var x=1") && !r1.text.includes("color:red") && !r1.text.includes("注释"),
  "剔除 script/style/注释", r1.text.slice(0, 80));
ok(r1.text.includes("&") && r1.text.includes("<x>"), "解码 HTML 实体", r1.text.slice(0, 80));
console.log("");

// ── 4. 单元：解压 ─────────────────────────────────────────────────────────
console.log("-- 单元：响应体解压 --");
const { decompress } = mod.__internals;
const payload = "<p>hello 压缩世界</p>".repeat(20);
const gz = decompress(gzipSync(Buffer.from(payload)), "gzip").toString("utf8");
const br = decompress(brotliCompressSync(Buffer.from(payload)), "br").toString("utf8");
const plain = decompress(Buffer.from("raw"), "").toString("utf8");
ok(gz === payload, "gzip 解开");
ok(br === payload, "brotli 解开");
ok(plain === "raw", "无编码时原样返回");
console.log("");

// ── 5. 单元：版本号解析 ────────────────────────────────────────────────────
console.log("-- 单元：内核版本解析 --");
const { parseChromeMajor } = mod.__internals;
ok(parseChromeMajor("Mozilla/5.0 ... Chrome/131.0.0.0 Mobile Safari/537.36") === 131, "解析 Chrome/131");
ok(parseChromeMajor("Mozilla/5.0 (Linux; Android 13)") === 0, "无 Chrome 标记时返回 0");
console.log("");

// ── 6. 实况：探测 ─────────────────────────────────────────────────────────
console.log("-- 实况：当前环境 --");
const get = (n) => tools.find((t) => t.name === n);
let probe;
try {
  probe = await get("web_probe").execute({});
  ok(typeof probe.engine === "string" && typeof probe.nativeUsable === "boolean", "web_probe 返回结构完整",
    JSON.stringify(probe).slice(0, 120));
} catch (e) {
  ok(false, "web_probe 未抛异常", String(e && e.message));
  probe = null;
}
if (probe) {
  console.log("       引擎=" + probe.engine +
    " native=" + probe.nativeUsable +
    " http=" + probe.httpUsable +
    " 内核=" + ((probe.kernel && probe.kernel.versionName) || "未知") +
    (probe.degraded ? " [degraded]" : ""));
  if (probe.nativeUsable) log("[PASS]", "原生 WebView 通道可用", "内核 " + (probe.kernel.versionName || "?"));
  else log("[SKIP]", "原生 WebView 通道不可用", probe.hint || probe.error || "");
}
console.log("");

// ── 7. 实况：拦截在工具层也生效 ────────────────────────────────────────────
console.log("-- 实况：工具层闸门 --");
const t = await get("web_read").execute({ url: "http://127.0.0.1:3080/api/session" });
ok(t.ok === false && t.reason === "blocked-url", "web_read 拒绝回环地址", JSON.stringify(t).slice(0, 120));
console.log("");

// ── 8. 实况：真的读一个页面 ────────────────────────────────────────────────
console.log("-- 实况：读取 example.com --");
let read;
try {
  read = await get("web_read").execute({ url: "https://example.com/", maxChars: 2000 });
} catch (e) {
  read = { ok: false, reason: "throw", error: String(e && e.message) };
}
if (read && read.ok) {
  const looksRight = /example/i.test(read.title || "") || /domain|example/i.test(read.text || "");
  ok(looksRight, "取到 example.com 的正文", "title=" + read.title + " chars=" + read.chars);
  console.log("       引擎=" + read.engine + " title=" + JSON.stringify(read.title) + " chars=" + read.chars);
  ok(typeof read.text === "string" && read.text.length > 20, "正文非空", "chars=" + read.chars);
} else {
  const noNet = /net-error|timeout|EAI_AGAIN|ENOTFOUND|ECONN|http-403|http-5/.test(String(read && (read.reason + " " + read.error)));
  log(noNet ? "[SKIP]" : "[FAIL]", "读取 example.com",
    noNet ? "这台机器当前连不上外网（" + (read.error || read.reason) + "）" : JSON.stringify(read).slice(0, 200));
}
console.log("");

// ── 汇总 ──────────────────────────────────────────────────────────────────
console.log("=== 汇总：PASS " + pass + " / FAIL " + fail + " / SKIP " + skip + " ===");
if (fail > 0) {
  console.log("有失败项 —— 把上面的输出整段发出来。");
  process.exit(1);
}
console.log(fail === 0 && pass > 0 ? "全部通过。" : "");
