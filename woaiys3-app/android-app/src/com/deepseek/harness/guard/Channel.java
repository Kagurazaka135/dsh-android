package com.deepseek.harness.guard;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 触发通道（不变量 ②：标签由原生侧打，Node 侧无法伪造）。
 *
 * 为什么不能是请求字段：插件在磁盘上、模型与 bash 同 uid 可写；请求里带
 * "channel":"voice" 等于让被管的人自己填「我没被管」。
 *
 * 做法：VoiceService 在自己的进程里维护语音会话（wake.hit 开、wake.timeout 关），
 * 其它原生组件经 127.0.0.1:<voicePort>/voice-state 读取。Node 既不在那个进程里，
 * 也没有能改这个状态的接口——它最多能读到「现在是语音」，而读到并不能改变判定方向。
 *
 * 已知代价：模型可以等会话超时后再动手。所以 TTL 是安全参数：
 * 越小越安全、误伤越大；当前 120s（VoiceService.VOICE_SESSION_TTL_MS）。
 */
final class Channel {

    private static final String HEADER = "X-DSH-Token";
    private static final long PROBE_INTERVAL_MS = 400L;

    private static volatile Context sCtx;
    /** 独立 jar（虚拟屏核心）用：没有 App Context，由启动参数给端口与令牌。 */
    private static volatile int sCorePort;
    private static volatile String sCoreToken;

    private static volatile long sVoiceUntil;
    private static volatile long sLastProbeAt;

    private Channel() {
    }

    static void install(Context context) {
        if (context == null) return;
        sCtx = context.getApplicationContext();
    }

    /** 虚拟屏核心（shell 进程）用：端口 + App 的本地令牌（由启动参数传入）。 */
    static void configure(int voicePort, String token) {
        sCorePort = voicePort;
        sCoreToken = token;
    }

    /** 当前是否处于语音会话。读不到语音服务 = 不是语音会话（不把「服务没起」当成「guard 坏了」）。 */
    static boolean voiceActive() {
        long now = System.currentTimeMillis();
        if (sVoiceUntil > now) return true;
        if (now - sLastProbeAt < PROBE_INTERVAL_MS) return false;
        sLastProbeAt = now;
        try {
            int port = 0;
            String token = null;
            Context ctx = sCtx;
            if (ctx != null) {
                port = appVoicePort(ctx);
                token = com.deepseek.harness.LocalAuth.token(ctx);
            } else if (sCorePort > 0) {
                port = sCorePort;
                token = sCoreToken;
            }
            if (port <= 0 || token == null || token.length() < 16) return false;
            String raw = get(port, token, "/voice-state");
            if (raw == null || raw.indexOf("\"active\":true") < 0) return false;
            long until = longField(raw, "until", 0L);
            if (until > now) {
                sVoiceUntil = until;
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 语音端口与 VoiceService 同一口径：按包名派生引擎端口 + 200。 */
    private static int appVoicePort(Context ctx) {
        int def;
        try {
            String pkg = ctx.getPackageName();
            int engine = pkg.contains("beta") ? 3082 : pkg.contains("compat") ? 3084 : 3080;
            def = engine + 200;
        } catch (Throwable t) {
            def = 3280;
        }
        try {
            return ctx.getSharedPreferences("dsh_prefs", Context.MODE_PRIVATE)
                    .getInt("voice_port", def);
        } catch (Throwable t) {
            return def;
        }
    }

    private static String get(int port, String token, String path) {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            URL u = new URL("http://127.0.0.1:" + port + path);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(400);
            c.setReadTimeout(400);
            c.setRequestMethod("GET");
            c.setRequestProperty(HEADER, token);
            in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 8192) break;
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static long longField(String json, String key, long def) {
        try {
            String k = "\"" + key + "\"";
            int i = json.indexOf(k);
            if (i < 0) return def;
            i = json.indexOf(':', i + k.length());
            if (i < 0) return def;
            int e = i + 1;
            while (e < json.length() && " -0123456789".indexOf(json.charAt(e)) >= 0) e++;
            return Long.parseLong(json.substring(i + 1, e).trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
