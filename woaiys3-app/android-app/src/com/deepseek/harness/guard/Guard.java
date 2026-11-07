package com.deepseek.harness.guard;

import android.content.Context;

import com.deepseek.harness.BuildInfo;

/**
 * 权限门面：分叉 + fail-closed。
 *
 * 不变量 ①：flavor 判定用**编进 dex 的常量** BuildInfo.FLAVOR，不用 Class.forName。
 * 类加载失败至少有四种原因（没编进去 / 依赖缺 / 混淆 / 静态初始化炸），只有「没编进去」该放行；
 * 用「找不到就放行」等于把「坏了」当成「没有」。这种悄悄退化是本项目最不能接受的失效。
 *
 * 不变量 ③：两个 flavor 走同一条代码路径。guard 目录在 plain 包里也参与编译，
 * 只是 Guard.check 在常量判定后直接 allow —— 不是条件编译，不是两份文件列表。
 *
 * 不变量 ④：规则抛异常一律 deny。没有「异常时放行」的分支。
 */
public final class Guard {

    /** 请求来源。APP=主进程的本地服务；VOICE_SERVICE=语音服务自身的原子能力；VSCREEN=虚拟屏。 */
    public enum Origin { APP, VOICE_SERVICE, VSCREEN }

    /** 一次待判定请求。body/foreground 都必须是原生侧读到的值，不能来自模型自述。 */
    public static final class Request {
        public final String path;
        public final String body;
        /** 系统读到的前台包名；读不到传空串（app 敏感动作会 fail-closed 拒绝）。 */
        public final String foreground;
        public final Origin origin;

        public Request(String path, String body, String foreground, Origin origin) {
            this.path = path == null ? "" : path;
            this.body = body == null ? "" : body;
            this.foreground = foreground == null ? "" : foreground;
            this.origin = origin == null ? Origin.APP : origin;
        }

        /**
         * 前台包名是否「已知」。App 各服务里空串 = 读不到（fail-closed）；
         * 虚拟屏核心的空串 = 这个虚拟屏里没有启动过任何应用（无保护对象）。
         */
        public boolean foregroundKnown() {
            return origin == Origin.VSCREEN || !foreground.isEmpty();
        }
    }

    /** 原生确认框的最长等待；超时 = deny + 解锁（不是永久卡住）。 */
    public static final long ASK_TIMEOUT_MS = 20000L;

    private static volatile Context sCtx;

    private Guard() {
    }

    /** 由主进程的原生组件调用（Activity / 无障碍 / 桥服务 / 语音服务 onCreate）。幂等。 */
    public static void install(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        sCtx = app;
        Channel.install(app);
        Consent.install(app);
    }

    /**
     * 记录 App 自己写进剪贴板的文本（原生侧留痕）。
     * 用途：通讯类 App 里「粘贴」前把即将输入的内容原样摆给人看。
     * 这是原生记录，不是请求字段——模型改不了本次粘贴的实际内容。
     */
    public static void noteClipboardWrite(String text) {
        GuardImpl.noteClipboard(text);
    }

    public static Context context() {
        return sCtx;
    }

    /** 虚拟屏核心（独立 shell 进程）用：给它语音服务的端口与 App 的本地令牌。 */
    public static void configureVoiceProbe(int voicePort, String token) {
        Channel.configure(voicePort, token);
    }

    public static Decision check(Request request) {
        if (request == null) return Decision.deny("权限判定缺少请求信息");
        // plain：行为与今天完全一致（常量判定，不做运行时探测）。
        // 读不到常量（包坏了）→ 不当作 plain，继续走下方 guarded 判定（fail-closed）。
        boolean guarded = true;
        try {
            guarded = BuildInfo.guarded();
        } catch (Throwable t) {
            guarded = true;
        }
        if (!guarded) return Decision.allow();
        try {
            return GuardImpl.check(request);
        } catch (Throwable t) {
            // 承重墙：规则模块抛任何异常（含 NoClassDefFoundError）都拒绝，绝不放行。
            return Decision.deny("权限规则模块异常，已按拒绝处理（fail-closed）");
        }
    }
}
