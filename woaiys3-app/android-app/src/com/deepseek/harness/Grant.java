package com.deepseek.harness;

import android.util.Log;

import com.deepseek.harness.guard.Consent;
import com.deepseek.harness.guard.Decision;
import com.deepseek.harness.guard.Guard;

/**
 * 桥许可（Grant）：长期 token 之后的那一道**授权**判定。
 *
 * 为什么 token 不够：token 明文存在 dsh_prefs（bash 同 uid 能读），它只证明"你是本机
 * 这个 App 的引擎"，不证明"这次动作被允许"。所以 confirm 包里每个本地服务都要再过
 * 这一层；plain 包在 LocalAuth 里就返回了，不会走到这里。
 *
 * ask 是**同步**的：处理线程停在原生确认框上，人点「允许一次」就只放行这一次
 * （没有可复用的票据，模型拿不走）；拒绝 / 超时 / 建不出窗口一律 false。
 */
public final class Grant {

    private static final String TAG = "dsh-guard";

    /** 给 LocalAuth.denied() 取拒绝原因（同一线程：handler 里 ok() 之后紧接 denied()）。 */
    private static final ThreadLocal<String> REASON = new ThreadLocal<String>();

    private Grant() {
    }

    /** 1.5 定下的接口（不带 body / 前台包名；app 敏感动作会 fail-closed）。 */
    public static boolean verify(String rawHead, String path) {
        return verify(rawHead, path, "", "", Guard.Origin.APP);
    }

    public static boolean verify(String rawHead, String path, String body,
                                 String foreground, Guard.Origin origin) {
        REASON.remove();
        Decision d;
        try {
            d = Guard.check(new Guard.Request(path, body, foreground, origin));
        } catch (Throwable t) {
            // Guard.check 内部已经 catch；这里再兜一次，保证调用方永远拿到 false 而不是异常。
            d = Decision.deny("权限判定异常（fail-closed）");
        }

        if (d.kind == Decision.Kind.ALLOW) return true;
        if (d.kind == Decision.Kind.DENY) {
            REASON.set(d.reason);
            Log.w(TAG, "deny " + path + " : " + d.reason);
            return false;
        }

        // ASK：只在这里弹一次原生确认框；超时 = deny + 解锁。
        boolean allowed = Consent.ask("AI 请求确认", d.detail, Guard.ASK_TIMEOUT_MS);
        if (!allowed) {
            REASON.set(d.reason + "（未确认或确认超时）");
            Log.w(TAG, "ask-denied " + path);
            return false;
        }
        Log.i(TAG, "ask-allowed-once " + path);
        return true;
    }

    static String lastReason() {
        return REASON.get();
    }

    static void clearReason() {
        REASON.remove();
    }
}
