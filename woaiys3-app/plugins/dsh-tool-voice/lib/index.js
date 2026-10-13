/**
 * 语音工具插件（v2.0 · B 路线）：给 AI 提供「说话 / 听话 / 录音 / 唤醒词」能力。
 * 与 App 侧 VoiceService（前台服务）通过本地 HTTP（APP_VOICE_PORT，默认 3280）通信。
 *
 * 分工（铁律：插件编排通用、桥平台相关）：
 *   - 本插件（纯 Node，PC/手机通用）：工具注册 + 参数校验 + 结果渲染
 *   - VoiceService（原生）：麦克风 / TTS / 识别引擎 / 唤醒词等平台能力
 *
 * 半双工约定（编排层注意）：TTS 正在播报时服务会拒绝听写/录音（「正在播报」错误）；
 * 需要「说完马上听」的场景请先 voice_speak 后等 speak.done 事件，或直接依赖服务的闸。
 *
 * 注意：DSH 工具输出校验为 additionalProperties:false——execute 返回的每个字段
 * 都必须在 output.schema 中显式声明，否则结果会被判定为非法输出（历史踩坑）。
 * render 必须把关键字段渲染给 AI 看（否则 AI 只知道「成功」不知道内容，历史踩坑）。
 */
import { defineTool } from "@deepseek-ai/dsh-tools";
import { get as httpGet, request as httpRequest } from "node:http";

const name = "tool-voice";
const inject = ["tools"];

const voicePort = () => parseInt(process.env.APP_VOICE_PORT || "3280", 10);

const GUIDE_TEXT =
  "语音服务不可用：请确认 App 正在运行（语音服务随引擎启动）、且本机为 arm64 安卓设备。";

function voiceRequest(path, params, timeoutMs) {
  return new Promise((resolve) => {
    const qs = params
      ? "?" + Object.entries(params).map(([k, v]) =>
          encodeURIComponent(k) + "=" + encodeURIComponent(v)).join("&")
      : "";
    const req = httpGet({
      host: "127.0.0.1",
      port: voicePort(),
      path: path + qs,
      timeout: timeoutMs || 10000,
      // App 侧本地服务鉴权（127.0.0.1 对本机所有应用开放，不是访问控制）
      headers: { "X-DSH-Token": process.env.APP_LOCAL_TOKEN || "" }
    }, (res) => {
      let data = "";
      res.setEncoding("utf8");
      res.on("data", (c) => { data += c; if (data.length > 262144) req.destroy(); });
      res.on("end", () => resolve(data || "{\"ok\":false,\"error\":\"empty response\"}"));
    });
    req.on("error", () => resolve("{\"ok\":false,\"error\":\"App 本地服务不可用\"}"));
    req.on("timeout", () => { req.destroy(); resolve("{\"ok\":false,\"error\":\"App 本地服务超时\"}"); });
    req.end();
  });
}

/** POST JSON。 */
function voicePost(path, body, timeoutMs) {
  return new Promise((resolve) => {
    const payload = JSON.stringify(body || {});
    const req = httpRequest({
      host: "127.0.0.1",
      port: voicePort(),
      path,
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Content-Length": Buffer.byteLength(payload),
        // v1.18.0：本地服务鉴权令牌（App 启动引擎时经 env 注入）
        "X-DSH-Token": process.env.APP_LOCAL_TOKEN || ""
      },
      timeout: timeoutMs || 10000
    }, (res) => {
      let data = "";
      res.setEncoding("utf8");
      res.on("data", (c) => { data += c; if (data.length > 262144) req.destroy(); });
      res.on("end", () => resolve(data || "{\"ok\":false,\"error\":\"empty response\"}"));
    });
    req.on("error", () => resolve("{\"ok\":false,\"error\":\"App 本地服务不可用\"}"));
    req.on("timeout", () => { req.destroy(); resolve("{\"ok\":false,\"error\":\"App 本地服务超时\"}"); });
    req.end(payload);
  });
}

function parseResult(raw) {
  try {
    const v = JSON.parse(raw);
    if (!v.ok && v.error && /服务不可用|超时|empty response/.test(v.error)) {
      return { ok: false, error: GUIDE_TEXT };
    }
    return v;
  } catch (e) {
    return { ok: false, error: "语音服务响应解析失败: " + String(raw).slice(0, 120) };
  }
}

function renderValue(value, args) {
  return (value && typeof value === "object" ? value : {
    ok: false,
    error: "工具未返回结果（render 收到空值；入参 " + JSON.stringify(args === void 0 ? null : args) + "）"
  });
}

function renderSimple(value) {
  value = renderValue(value);
  return [{
    type: "text",
    text: value.ok ? "操作成功。" : "执行失败：" + (value.error || "未知错误")
  }];
}

function apply(ctx) {
  // 状态（始终可查：AI 据此判断 TTS/麦克风/引擎可用性并给用户指引）
  ctx.tools.register(defineTool({
    name: "voice_status",
    description:
      "查询手机语音服务状态：TTS 是否就绪、麦克风权限是否已授予、系统识别/离线识别引擎可用性、" +
      "是否正在录音/播报/听写、唤醒词引擎状态。任何语音操作前先查一次；" +
      "micGranted=false 时请引导用户：系统设置 → 应用 → DeepSeek Harness → 权限 → 麦克风 开启。",
    parameters: {},
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          running: { type: "boolean" },
          apiLevel: { type: "number" },
          micGranted: { type: "boolean" },
          ttsReady: { type: "boolean" },
          speaking: { type: "boolean" },
          lastSpokenText: { type: "string" },
          recording: { type: "boolean" },
          recDurationMs: { type: "number" },
          lastWav: { type: "string" },
          lastWavDurationMs: { type: "number" },
          asrEngine: { type: "string" },
          offlineAsr: { type: "string" },
          wakeEngine: { type: "string" },
          wakeRunning: { type: "boolean" },
          listening: { type: "boolean" }
        }
      },
      render: (_a, v) => {
        v = renderValue(v);
        if (!v.ok) return renderSimple(v);
        const lines = [];
        lines.push("语音服务运行中（API " + (v.apiLevel || 0) + "）");
        lines.push("TTS：" + (v.ttsReady ? "就绪" : "不可用（设备无语音引擎）") + (v.speaking ? " · 正在播报" : ""));
        lines.push("麦克风：" + (v.micGranted ? "已授权" : "未授权（需用户去系统设置开启）"));
        lines.push("识别引擎：系统=" + (v.asrEngine || "none") + " / 离线=" + (v.offlineAsr || "none"));
        lines.push("唤醒词：引擎=" + (v.wakeEngine || "none") + " · " + (v.wakeRunning ? "运行中" : "未运行"));
        if (v.recording) lines.push("正在录音 " + Math.round((v.recDurationMs || 0) / 1000) + "s");
        if (v.listening) lines.push("正在听写…");
        if (v.lastWav) lines.push("最近录音：" + v.lastWav + "（" + Math.round((v.lastWavDurationMs || 0) / 1000) + "s）");
        return [{ type: "text", text: lines.join("\n") }];
      }
    },
    async execute() {
      const v = parseResult(await voiceRequest("/status", undefined, 5000));
      if (!v.ok) return { ok: false, error: v.error || GUIDE_TEXT };
      const out = {
        ok: true,
        running: v.running === true,
        apiLevel: typeof v.apiLevel === "number" ? v.apiLevel : 0,
        micGranted: v.micGranted === true,
        ttsReady: v.ttsReady === true,
        speaking: v.speaking === true,
        lastSpokenText: typeof v.lastSpokenText === "string" ? v.lastSpokenText : "",
        recording: v.recording === true,
        lastWav: typeof v.lastWav === "string" ? v.lastWav : "",
        lastWavDurationMs: typeof v.lastWavDurationMs === "number" ? v.lastWavDurationMs : 0,
        asrEngine: typeof v.asrEngine === "string" ? v.asrEngine : "none",
        offlineAsr: typeof v.offlineAsr === "string" ? v.offlineAsr : "none",
        wakeEngine: typeof v.wakeEngine === "string" ? v.wakeEngine : "none",
        wakeRunning: v.wakeRunning === true,
        listening: v.listening === true
      };
      if (typeof v.recDurationMs === "number") out.recDurationMs = v.recDurationMs;
      return out;
    }
  }));

  // 播报（TTS）
  ctx.tools.register(defineTool({
    name: "voice_speak",
    description:
      "用手机扬声器朗读一段文字（系统 TTS）。用于「语音回答」：把要给用户听的内容念出来。" +
      "长文本会按字数估算播报时长；interrupt=true（默认）打断上一条播报。" +
      "播报期间语音服务会拒绝听写/录音（半双工防自听）。",
    parameters: {
      text: { type: "string", required: true, description: "要朗读的文字" },
      interrupt: { type: "boolean", description: "是否打断当前播报（默认 true）" }
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          speaking: { type: "boolean" },
          estimateMs: { type: "number" }
        }
      },
      render: (args, v) => {
        v = renderValue(v, args);
        if (!v.ok) return renderSimple(v);
        return [{ type: "text", text: "开始播报（约 " + Math.round((v.estimateMs || 0) / 1000) + "s）：" + (args.text || "").slice(0, 60) }];
      }
    },
    async execute(args) {
      const v = parseResult(await voicePost("/tts", {
        text: args.text,
        interrupt: args.interrupt !== false
      }, 8000));
      if (!v.ok) return { ok: false, error: v.error || "播报失败" };
      return {
        ok: true,
        speaking: v.speaking === true,
        estimateMs: typeof v.estimateMs === "number" ? v.estimateMs : 0
      };
    }
  }));

  // 一次性听写（麦克风 + 系统识别）
  ctx.tools.register(defineTool({
    name: "voice_listen",
    description:
      "打开麦克风听用户说一句话，返回识别文本（一次性：说 → 识别 → 返回）。" +
      "用于「喊一声之后接话」「让用户直接口述指令」。需要麦克风权限与系统语音识别服务。" +
      "用户没说话会返回超时错误。注意：播报期间调用会被拒绝（半双工）。",
    parameters: {
      timeoutMs: { type: "number", description: "等待时长上限，默认 20000ms，范围 3000–60000" },
      lang: { type: "string", description: "语言标签，默认 zh-CN" }
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          text: { type: "string" },
          lang: { type: "string" }
        }
      },
      render: (_a, v) => {
        v = renderValue(v);
        if (!v.ok) return renderSimple(v);
        const t = (v.text || "").trim();
        return [{ type: "text", text: t ? "听到：" + t : "听写完成，但没有识别到内容。" }];
      }
    },
    async execute(args) {
      const timeoutMs = Math.min(Math.max(args.timeoutMs || 20000, 3000), 60000);
      const v = parseResult(await voicePost("/listen", {
        timeoutMs,
        lang: args.lang || "zh-CN"
      }, timeoutMs + 8000));
      if (!v.ok) return { ok: false, error: v.error || "听写失败" };
      return {
        ok: true,
        text: typeof v.text === "string" ? v.text : "",
        lang: typeof v.lang === "string" ? v.lang : "zh-CN"
      };
    }
  }));

  // 开始录音
  ctx.tools.register(defineTool({
    name: "voice_record",
    description:
      "开始用麦克风录音（16k 单声道 WAV，存到 App 私有目录）。达到 maxMs 自动停止；" +
      "也可以随时用 voice_record_stop 提前停止。播报期间调用会被拒绝（半双工）。" +
      "录完的 WAV 路径可以用 voice_recognize 转文字（离线引擎就绪后），或交给其他文件工具处理。",
    parameters: {
      maxMs: { type: "number", description: "最长录音时长，默认 30000ms，上限 120000" }
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          recording: { type: "boolean" },
          maxMs: { type: "number" },
          note: { type: "string" }
        }
      },
      render: (_a, v) => {
        v = renderValue(v);
        if (!v.ok) return renderSimple(v);
        return [{ type: "text", text: v.note ? v.note : ("开始录音（上限 " + Math.round((v.maxMs || 0) / 1000) + "s）") }];
      }
    },
    async execute(args) {
      const v = parseResult(await voicePost("/record", { maxMs: args.maxMs || 30000 }, 8000));
      if (!v.ok) return { ok: false, error: v.error || "录音失败" };
      const out = { ok: true, recording: v.recording === true, maxMs: typeof v.maxMs === "number" ? v.maxMs : 0 };
      if (typeof v.note === "string") out.note = v.note;
      return out;
    }
  }));

  // 停止录音
  ctx.tools.register(defineTool({
    name: "voice_record_stop",
    description: "停止当前录音并返回 WAV 文件路径与时长。录音已因超时自动结束时也会返回其产物。",
    parameters: {},
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          wav: { type: "string" },
          durationMs: { type: "number" },
          bytes: { type: "number" },
          note: { type: "string" }
        }
      },
      render: (_a, v) => {
        v = renderValue(v);
        if (!v.ok) return renderSimple(v);
        return [{ type: "text", text: "录音已保存：" + v.wav + "（" + Math.round((v.durationMs || 0) / 1000) + "s）" }];
      }
    },
    async execute() {
      const v = parseResult(await voicePost("/stop", {}, 12000));
      if (!v.ok) return { ok: false, error: v.error || "停止失败" };
      const out = {
        ok: true,
        wav: typeof v.wav === "string" ? v.wav : "",
        durationMs: typeof v.durationMs === "number" ? v.durationMs : 0,
        bytes: typeof v.bytes === "number" ? v.bytes : 0
      };
      if (typeof v.note === "string") out.note = v.note;
      return out;
    }
  }));

  // 离线识别（sherpa-onnx SenseVoice）
  ctx.tools.register(defineTool({
    name: "voice_recognize",
    description:
      "把一段 WAV 录音转成文字（离线识别：sherpa-onnx SenseVoice，中/英/日/韩/粤）。" +
      "模型不打进安装包，首次调用需等模型下载完成（可重复调用）；就绪后返回真实识别文本。",
    parameters: {
      path: { type: "string", description: "WAV 文件路径；缺省用最近一次录音" }
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          text: { type: "string" },
          engine: { type: "string" }
        }
      },
      render: (_a, v) => {
        v = renderValue(v);
        if (!v.ok) return renderSimple(v);
        return [{ type: "text", text: "识别结果：" + (v.text || "（空）") }];
      }
    },
    async execute(args) {
      const v = parseResult(await voicePost("/recognize", { path: args.path || "" }, 30000));
      if (!v.ok) return { ok: false, error: v.error || "识别失败", engine: typeof v.engine === "string" ? v.engine : "none" };
      return {
        ok: true,
        text: typeof v.text === "string" ? v.text : "",
        engine: typeof v.engine === "string" ? v.engine : "none"
      };
    }
  }));

  // 唤醒词开关
  ctx.tools.register(defineTool({
    name: "voice_wake",
    description:
      "控制唤醒词服务（「喊一声」常驻监听，sherpa-onnx zipformer KWS，唤醒词「小鲸鱼」）：action=start 开启 / stop 关闭 / status 查询。" +
      "模型不打进安装包，首次调用需等模型下载完成。",
    parameters: {
      action: { type: "string", required: true, description: "start | stop | status" }
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          running: { type: "boolean" },
          engine: { type: "string" }
        }
      },
      render: (_a, v) => {
        v = renderValue(v);
        if (!v.ok) return renderSimple(v);
        return [{ type: "text", text: "唤醒词服务：" + (v.running ? "运行中" : "未运行") + "（引擎 " + (v.engine || "none") + "）" }];
      }
    },
    async execute(args) {
      const v = parseResult(await voicePost("/wake", { action: args.action || "status" }, 8000));
      if (!v.ok) return {
        ok: false,
        error: v.error || "唤醒词操作失败",
        running: v.running === true,
        engine: typeof v.engine === "string" ? v.engine : "none"
      };
      return {
        ok: true,
        running: v.running === true,
        engine: typeof v.engine === "string" ? v.engine : "none"
      };
    }
  }));

  // 事件长轮询（唤醒命中 / 听写结果 / 播报完成 / 录音完成）
  ctx.tools.register(defineTool({
    name: "voice_events",
    description:
      "长轮询语音事件流：唤醒命中（wake.hit）、听写结果（listen.text/error）、播报完成（speak.done）、" +
      "录音完成（record.done）等。返回 since 之后的新事件与新的游标 seq；下次调用把 seq 传来即可。" +
      "用于「等一句话说完」或「等唤醒」的编排。",
    parameters: {
      since: { type: "number", description: "上次返回的 seq（默认 0 = 看最近事件）" },
      timeoutMs: { type: "number", description: "长轮询等待上限，默认 20000ms，上限 60000" }
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          error: { type: "string" },
          seq: { type: "number" },
          events: {
            type: "array",
            items: {
              type: "object",
              additionalProperties: false,
              properties: {
                seq: { type: "number" },
                ts: { type: "number" },
                type: { type: "string" },
                data: { type: "object", additionalProperties: true }
              }
            }
          }
        }
      },
      render: (_a, v) => {
        v = renderValue(v);
        if (!v.ok) return renderSimple(v);
        const evs = v.events || [];
        if (evs.length === 0) return [{ type: "text", text: "没有新事件（seq=" + v.seq + "）" }];
        const lines = evs.map((e) => {
          const d = e.data && typeof e.data === "object" ? JSON.stringify(e.data).slice(0, 200) : "";
          return "#" + e.seq + " " + e.type + (d ? " " + d : "");
        });
        return [{ type: "text", text: "新事件：\n" + lines.join("\n") }];
      }
    },
    async execute(args) {
      const timeoutMs = Math.min(Math.max(args.timeoutMs || 20000, 0), 60000);
      const v = parseResult(await voiceRequest("/event", {
        since: args.since || 0,
        timeoutMs
      }, timeoutMs + 10000));
      if (!v.ok) return { ok: false, error: v.error || "事件查询失败" };
      const events = Array.isArray(v.events) ? v.events.map((e) => {
        const o = {
          seq: typeof e.seq === "number" ? e.seq : 0,
          ts: typeof e.ts === "number" ? e.ts : 0,
          type: typeof e.type === "string" ? e.type : ""
        };
        if (e.data && typeof e.data === "object") o.data = e.data;
        return o;
      }) : [];
      return { ok: true, seq: typeof v.seq === "number" ? v.seq : 0, events };
    }
  }));
}

export { name, inject, apply };
