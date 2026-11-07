package com.deepseek.harness.guard;

/**
 * 权限判定结果：三种，没有第四种。
 *
 * - allow：直接执行
 * - deny：结构性不做（或 fail-closed），把理由回给模型
 * - ask：把待执行内容原样摆给人看，人在真实屏幕上确认后才执行一次
 *
 * 注意：ask 的 detail 必须是「人要做判断所需的最小原文」——通讯类输入时就是即将输入的
 * 整段文字。只写"要输入文字"等于一个空框，人没有可判断的东西。
 */
public final class Decision {

    public enum Kind { ALLOW, DENY, ASK }

    public final Kind kind;
    /** 给模型看的原因（deny 时回给工具调用方）。 */
    public final String reason;
    /** 给人看的正文（ask 弹窗内容，含待输入原文）。 */
    public final String detail;

    private Decision(Kind kind, String reason, String detail) {
        this.kind = kind;
        this.reason = reason == null ? "" : reason;
        this.detail = detail == null ? "" : detail;
    }

    public static Decision allow() {
        return new Decision(Kind.ALLOW, "", "");
    }

    public static Decision deny(String reason) {
        return new Decision(Kind.DENY, reason, "");
    }

    public static Decision ask(String reason, String detail) {
        return new Decision(Kind.ASK, reason, detail);
    }

    public boolean isAllow() {
        return kind == Kind.ALLOW;
    }
}
