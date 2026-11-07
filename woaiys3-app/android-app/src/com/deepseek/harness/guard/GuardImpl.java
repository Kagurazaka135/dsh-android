package com.deepseek.harness.guard;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则入口（第 2 段）。
 *
 * 判据全部写死在这张表里，模型不能自己判：
 *   动作 × 前台 App（系统读到的包名，不是模型自述）
 *   通道（语音会话由原生 VoiceService 维护）
 *
 * 分级依据：docs/权限.md §6.11 / §6.12 / §6.10.1。
 *
 * 已知洞（诚实标注，不假装解决）：
 * - shizuku_shell 是万能 shell，正则只能拦典型形态（见 §6.10.2「报警器不是门」）。
 *   走 rish 直连时连这道正则都不过。
 * - 通讯类 App 的网页版在浏览器里，前台包名是浏览器（§6.17 ②）。
 * - App 内通话按钮点坐标绕得过元素匹配（§6.17 ①）。
 */
final class GuardImpl {

    private static final Set<String> COMM = new HashSet<String>();
    private static final Set<String> FINANCE = new HashSet<String>();
    private static final Set<String> DIALER = new HashSet<String>();
    private static final Set<String> EXPERIENCE_KEYS = new HashSet<String>();
    private static final Set<String> FOUNDATION_KEYS = new HashSet<String>();

    static {
        // 通讯类：输入文字要弹窗（展示原文），点击 / 读屏仍然放行。
        COMM.add("com.tencent.mm");        // 微信
        COMM.add("com.tencent.mobileqq");  // QQ
        COMM.add("com.tencent.tim");       // TIM（QQ 办公版，语义相同）

        // 金融类：读屏 / 点击 / 输入默认拒绝。
        FINANCE.add("com.eg.android.AlipayGphone"); // 支付宝
        FINANCE.add("com.unionpay");                // 云闪付
        FINANCE.add("com.icbc");                    // 工行
        FINANCE.add("com.ccb.longjiLife");          // 建行
        FINANCE.add("com.android.bankabc");         // 农行
        FINANCE.add("com.chinamworld.bocmbci");     // 中行
        FINANCE.add("cmb.pb");                      // 招行

        // 系统拨号盘：不允许代点（§6.10「系统拨号盘干净堵死」）。
        DIALER.add("com.android.dialer");
        DIALER.add("com.google.android.dialer");
        DIALER.add("com.android.contacts");         // 多数国产 ROM 的拨号器与联系人同包
        DIALER.add("com.samsung.android.dialer");
        DIALER.add("com.oneplus.dialer");

        // 影响体验（可还原）：弹窗。
        EXPERIENCE_KEYS.add("default_input_method");
        EXPERIENCE_KEYS.add("selected_input_method_subtype");
        EXPERIENCE_KEYS.add("font_scale");
        EXPERIENCE_KEYS.add("font_weight_adjustment");
        EXPERIENCE_KEYS.add("display_size_forced");
        EXPERIENCE_KEYS.add("display_density_forced");

        // 改地基：结构性不做（无障碍 / 设备管理 / USB 调试 / 开发者选项 / 未知来源）。
        FOUNDATION_KEYS.add("enabled_accessibility_services");
        FOUNDATION_KEYS.add("accessibility_enabled");
        FOUNDATION_KEYS.add("accessibility_display_magnification_enabled");
        FOUNDATION_KEYS.add("adb_enabled");
        FOUNDATION_KEYS.add("development_settings_enabled");
        FOUNDATION_KEYS.add("enabled_notification_listeners");
        FOUNDATION_KEYS.add("install_non_market_apps");
        // 注意：动画缩放（animator/transition/window_animation_scale）属"可还原、无对外影响"，
        // 故意放行 —— 它们常被调试脚本临时改小，弹窗会变成纯噪音。
    }

    private static final Pattern PIPE_SHELL = Pattern.compile("\\|\\s*(sh|bash)\\b");
    private static final Pattern INPUT_CALL = Pattern.compile(
            "\\binput\\s+keyevent\\s+(5|6|79|keycode_call|keycode_endcall|keycode_heatsethook)\\b");
    private static final Pattern PM_SPECIAL = Pattern.compile(
            "\\bpm\\s+(grant|revoke)\\b.*(bind_accessibility_service|bind_device_admin|package_usage_stats"
                    + "|bind_notification_listener_service|write_secure_settings|manage_external_storage)");
    private static final Pattern DPM_OWNER = Pattern.compile("\\bdpm\\s+set-device-owner\\b");
    private static final Pattern APPOPS = Pattern.compile("\\bappops\\s+set\\b");
    private static final Pattern SETTINGS_PUT = Pattern.compile(
            "\\bsettings\\s+put\\s+(global|system|secure)\\s+([a-z0-9_.]+)");
    private static final Pattern INSTALL = Pattern.compile(
            "\\b(pm|cmd\\s+package)\\s+install\\b|\\binstall-(create|write|commit)\\b");
    private static final Pattern INPUT_TEXT = Pattern.compile(
            "(?:^|[;&|])\\s*input\\s+text\\s+(.+)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PASTE = Pattern.compile(
            "\\binput\\s+keyevent\\s+(279|keycode_paste)\\b");
    private static final Pattern INPUT_GESTURE = Pattern.compile(
            "\\binput\\s+(tap|swipe|keyevent|motionevent)\\b");
    private static final Pattern FORCE_STOP = Pattern.compile("\\bam\\s+(force-stop|kill)\\b");

    private GuardImpl() {
    }

    /** App 自己写进剪贴板的最近一段文本（原生侧留痕，用于粘贴前的原文展示）。 */
    private static volatile String sLastClipboard = "";

    static void noteClipboard(String text) {
        sLastClipboard = text == null ? "" : text;
    }

    /** 即将粘贴的内容：优先读系统剪贴板（最准），读不到再用 App 自己写的留痕。 */
    private static String pasteText() {
        try {
            android.content.Context ctx = Guard.context();
            if (ctx != null) {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip()) {
                    CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(ctx);
                    if (cs != null && cs.length() > 0) return cs.toString();
                }
            }
        } catch (Throwable ignored) {
        }
        return sLastClipboard;
    }

    static Decision check(Guard.Request r) {
        Act a = classify(r);
        if (a == null) return Decision.allow();

        if (!a.deny.isEmpty()) return Decision.deny(a.deny);
        // 语音服务自身的原子能力（TTS / 听写 / 录音 / 事件流）不参与动作分级：
        // 它们不是"对手机动手"，语音通道的限制作用在设备动作上。
        if (r.origin == Guard.Origin.VOICE_SERVICE) return Decision.allow();

        boolean voice = Channel.voiceActive();
        if (voice && a.blockedInVoice) {
            return Decision.deny("当前是语音通道：只陪你聊天，不替你操作手机。"
                    + "要执行这个动作，请在文字对话里再说一次。");
        }
        if (Consent.isPending()) {
            return Decision.deny("正在等你手机上确认上一个操作；确认框消失前不执行新的操作（工具层互斥）。");
        }

        boolean allowAsk = r.origin == Guard.Origin.APP;
        if (!a.forceAsk.isEmpty()) {
            return askOrDeny(allowAsk, a.forceAsk, a.askReason);
        }
        return appRules(r, a, allowAsk);
    }

    // ==================== 动作 × 前台 App ====================

    private static Decision appRules(Guard.Request r, Act a, boolean allowAsk) {
        if (!a.appSensitive) return Decision.allow();

        if (!r.foregroundKnown()) {
            // 不变量 ④：读不到前台包名就 deny，不按"普通 App"兜底。
            return Decision.deny("读不到当前前台应用包名，按拒绝处理（fail-closed）。"
                    + "请先让无障碍服务连上屏幕，再重试。");
        }

        String pkg = r.foreground.trim();
        if (pkg.isEmpty()) {
            // 只可能是虚拟屏：这个虚拟屏里没有启动过任何应用，没有可保护的对象。
            return Decision.allow();
        }
        if (DIALER.contains(pkg)) {
            return Decision.deny("前台是系统拨号盘：不允许代点拨打相关操作。"
                    + "要把号码填进拨号盘可以，最后那一下请人自己按。");
        }
        if (FINANCE.contains(pkg)) {
            return Decision.deny("前台是金融类应用（" + pkg + "）：读屏 / 点击 / 输入默认拒绝。"
                    + "（设计上需要单次授权，本版未开放。）");
        }
        if (COMM.contains(pkg) && "input.text".equals(a.name)) {
            return askOrDeny(allowAsk, a.detail, "在通讯类应用里输入文字需要确认");
        }
        return Decision.allow();
    }

    private static Decision askOrDeny(boolean allowAsk, String detail, String reason) {
        if (!allowAsk) {
            return Decision.deny(reason + "；当前通道无法弹出确认框（虚拟屏 / 后台），已拒绝。");
        }
        return Decision.ask(reason, detail);
    }

    // ==================== 路径 → 动作 ====================

    private static Act classify(Guard.Request r) {
        String base = stripQuery(r.path);
        String body = r.body;

        if (base.equals("/dump") || base.equals("/screenshot")
                || base.equals("/vscreen/see") || base.equals("/vscreen/preview")) {
            return Act.screen("读取屏幕内容");
        }
        if (base.equals("/tap") || base.equals("/touch") || base.equals("/touch-release")
                || base.equals("/gesture") || base.equals("/vscreen/tap")) {
            return Act.gesture("点击 / 触摸");
        }
        if (base.equals("/swipe") || base.equals("/scroll") || base.equals("/hold")
                || base.equals("/vscreen/swipe")) {
            return Act.gesture("滑动 / 滚动");
        }
        if (base.equals("/back") || base.equals("/home") || base.equals("/vscreen/key")) {
            return Act.gesture("按键");
        }
        if (base.equals("/input")) {
            String text = queryParam(r.path, "text");
            if (text.isEmpty()) text = jsonField(body, "text");
            String mode = queryParam(r.path, "mode");
            if (mode.isEmpty()) mode = jsonField(body, "mode");
            // mode=paste：实际输入的是剪贴板内容，不是 text 参数 —— 展示剪贴板原文。
            if ("paste".equals(mode)) {
                String clip = pasteText();
                return Act.input(clip.isEmpty() ? "（从剪贴板粘贴；剪贴板内容此刻读不到）" : clip);
            }
            return Act.input(text);
        }
        if (base.equals("/setting")) {
            String key = jsonField(body, "key");
            if (key.isEmpty()) key = queryParam(r.path, "key");
            String value = jsonField(body, "value");
            if (value.isEmpty()) value = queryParam(r.path, "value");
            if (key.isEmpty()) return Act.plain("setting.read");
            return Act.setting(key, value);
        }
        if (base.equals("/clipboard")) {
            String action = jsonField(body, "action");
            if (action.isEmpty()) action = queryParam(r.path, "action");
            if ("write".equals(action)) return Act.plain("clipboard.write");
            return Act.clipboardRead();
        }
        if (base.equals("/shell")) {
            return classifyShell(jsonField(body, "command"));
        }
        if (base.equals("/browser")) {
            String op = jsonField(body, "op").toLowerCase(Locale.US);
            if (op.equals("click") || op.equals("type") || op.equals("press")
                    || op.equals("nav") || op.equals("open")) {
                return Act.simple("browser.write", true);
            }
            return Act.plain("browser.read");
        }
        if (base.equals("/vscreen/launch")) {
            return Act.simple("vscreen.launch", true);
        }
        // /status /notify /overlay /usage /voice/* /vscreen/create|status|close|ping：不是设备动作
        return null;
    }

    /** 特权 shell 的命令分级（报警器：只覆盖典型形态，漏是已知的）。 */
    private static Act classifyShell(String command) {
        String cmd = command == null ? "" : command.trim();
        if (cmd.isEmpty()) return Act.plain("shell.empty");
        String lower = cmd.toLowerCase(Locale.US);

        if (PIPE_SHELL.matcher(lower).find()) {
            return Act.deny("把下载内容直接交给 shell 执行（curl | bash 一类）属于结构性禁止。");
        }
        if (lower.contains("intent.action.call")) {
            return Act.deny("直接拨打电话（ACTION_CALL）属于结构性禁止；只能打开拨号盘由人按拨打。");
        }
        if (INPUT_CALL.matcher(lower).find()) {
            return Act.deny("按拨打键属于结构性禁止。");
        }
        if (lower.contains("pm uninstall") || lower.contains("pm clear")) {
            return Act.deny("卸载应用 / 清除应用数据不可逆，属于结构性禁止。");
        }
        if (PM_SPECIAL.matcher(lower).find() || DPM_OWNER.matcher(lower).find()
                || APPOPS.matcher(lower).find()) {
            return Act.deny("修改无障碍 / 设备管理 / 特殊访问权限属于结构性禁止。");
        }

        Matcher sm = SETTINGS_PUT.matcher(lower);
        if (sm.find()) {
            return Act.setting(sm.group(2), "");
        }
        if (INSTALL.matcher(lower).find()) {
            return Act.install("安装 APK（特权 shell）：" + cmd);
        }

        Matcher tm = INPUT_TEXT.matcher(cmd);
        if (tm.find()) {
            return Act.input(cleanInputText(tm.group(1)));
        }
        if (PASTE.matcher(lower).find()) {
            String t = pasteText();
            return Act.input(t.isEmpty()
                    ? "（从剪贴板粘贴；剪贴板内容此刻读不到）"
                    : t);
        }
        if (INPUT_GESTURE.matcher(lower).find()) {
            return Act.gesture("特权 shell 模拟输入：" + cmd);
        }
        if (FORCE_STOP.matcher(lower).find()) {
            return Act.forceAsk("即将强制停止应用（会丢失未保存状态）：\n\n" + cmd,
                    "强制停止应用需要确认");
        }
        // 其余（screencap / dumpsys / getprop / pm list / ls / cat ...）：允许。
        return Act.simple("shell.exec", true);
    }

    private static String cleanInputText(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.length() >= 2) {
            char a = s.charAt(0);
            char b = s.charAt(s.length() - 1);
            if ((a == '\'' && b == '\'') || (a == '"' && b == '"')) {
                s = s.substring(1, s.length() - 1);
            }
        }
        s = s.replace("\\'", "'").replace("\\\"", "\"");
        // Android `input text` 用 %s 表示空格。
        return s.replace("%s", " ");
    }

    // ==================== 小工具 ====================

    private static String stripQuery(String path) {
        if (path == null) return "";
        int q = path.indexOf('?');
        return q >= 0 ? path.substring(0, q) : path;
    }

    private static String queryParam(String path, String key) {
        if (path == null) return "";
        int q = path.indexOf('?');
        if (q < 0) return "";
        String[] pairs = path.substring(q + 1).split("&");
        for (int i = 0; i < pairs.length; i++) {
            int eq = pairs[i].indexOf('=');
            if (eq > 0 && pairs[i].substring(0, eq).equals(key)) {
                try {
                    return java.net.URLDecoder.decode(pairs[i].substring(eq + 1), "UTF-8");
                } catch (Throwable t) {
                    return pairs[i].substring(eq + 1);
                }
            }
        }
        return "";
    }

    /** 极简 JSON 字符串取值（只支持本项目用到的 字符串/数字/布尔 标量）。 */
    private static String jsonField(String json, String key) {
        if (json == null || json.isEmpty()) return "";
        String k = "\"" + key + "\"";
        int i = json.indexOf(k);
        if (i < 0) return "";
        int colon = json.indexOf(':', i + k.length());
        if (colon < 0) return "";
        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        if (p >= json.length()) return "";
        if (json.charAt(p) == '"') {
            StringBuilder sb = new StringBuilder();
            p++;
            while (p < json.length()) {
                char c = json.charAt(p++);
                if (c == '\\' && p < json.length()) {
                    char e = json.charAt(p++);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'u':
                            if (p + 4 <= json.length()) {
                                try {
                                    sb.append((char) Integer.parseInt(json.substring(p, p + 4), 16));
                                } catch (Throwable ignored) {
                                }
                                p += 4;
                            }
                            break;
                        default: sb.append(e);
                    }
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        int e = p;
        while (e < json.length() && ",}".indexOf(json.charAt(e)) < 0) e++;
        return json.substring(p, e).trim();
    }

    /** 一次规范化后的动作。 */
    private static final class Act {
        final String name;
        final boolean appSensitive;
        final boolean blockedInVoice;
        final String detail;
        final String askReason;
        final String forceAsk;
        final String deny;

        Act(String name, boolean appSensitive, boolean blockedInVoice, String detail,
            String askReason, String forceAsk, String deny) {
            this.name = name;
            this.appSensitive = appSensitive;
            this.blockedInVoice = blockedInVoice;
            this.detail = detail == null ? "" : detail;
            this.askReason = askReason == null ? "" : askReason;
            this.forceAsk = forceAsk == null ? "" : forceAsk;
            this.deny = deny == null ? "" : deny;
        }

        static Act screen(String detail) {
            return new Act("screen.read", true, true, detail, "", "", "");
        }

        static Act gesture(String detail) {
            return new Act("gesture", true, true, detail, "", "", "");
        }

        static Act input(String text) {
            String t = text == null ? "" : text;
            String detail = "即将输入以下文字：\n\n" + (t.isEmpty() ? "(空字符串，语义是清空输入框)" : t);
            return new Act("input.text", true, true, detail, "", "", "");
        }

        static Act setting(String key, String value) {
            String k = key == null ? "" : key;
            String v = value == null ? "" : value;
            if (FOUNDATION_KEYS.contains(k)) {
                return new Act("setting.put", false, true,
                        "", "", "",
                        "修改系统地基设置（" + k + "）属于结构性禁止。");
            }
            if (EXPERIENCE_KEYS.contains(k)) {
                String detail = "即将修改影响体验的系统设置：\n\n" + k
                        + (v.isEmpty() ? "" : " = " + v)
                        + "\n\n这类改动可还原，但会改变你此刻的输入法 / 字体 / 桌面布局。";
                return new Act("setting.put", false, true, detail,
                        "修改影响体验的系统设置需要确认", detail, "");
            }
            return new Act("setting.put", false, true, "", "", "", "");
        }

        static Act install(String detail) {
            return new Act("package.install", false, true, detail,
                    "安装 APK 需要确认", detail, "");
        }

        static Act clipboardRead() {
            String detail = "即将读取剪贴板内容。剪贴板里常有刚复制的密码、短信验证码、地址。";
            return new Act("clipboard.read", false, true, detail,
                    "读取剪贴板需要确认", detail, "");
        }

        static Act simple(String name, boolean blockedInVoice) {
            return new Act(name, false, blockedInVoice, "", "", "", "");
        }

        static Act plain(String name) {
            return new Act(name, false, false, "", "", "", "");
        }

        static Act forceAsk(String detail, String reason) {
            return new Act("ask", false, true, detail, reason, detail, "");
        }

        static Act deny(String reason) {
            return new Act("deny", false, true, "", "", "", reason);
        }
    }
}
