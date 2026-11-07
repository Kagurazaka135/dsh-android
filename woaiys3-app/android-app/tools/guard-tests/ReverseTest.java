package com.deepseek.harness.guard;

import com.deepseek.harness.BuildInfo;
import com.deepseek.harness.LocalAuth;

/**
 * 反例自测（不变量 ④ fail-closed）。
 *
 * 用法：java -cp <throwing-GuardImpl-classes>;<guard-classes>;... ReverseTest
 * 其中 throwing-GuardImpl 是「静态初始化就抛异常」的 GuardImpl，用来模拟规则模块坏掉。
 */
public final class ReverseTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        System.out.println("== flavor=" + BuildInfo.FLAVOR + " guarded=" + BuildInfo.guarded() + " ==");

        if (BuildInfo.guarded()) {
            // 规则模块一加载就炸 → 必须 deny（不是放行）
            Decision d = Guard.check(new Guard.Request("/tap?x=1&y=2", "", "com.android.chrome",
                    Guard.Origin.APP));
            expect("规则模块抛异常 → DENY", d, "DENY");
            // 同一条路径再走一次（类初始化失败会被缓存成 NoClassDefFoundError，仍须 deny）
            Decision d2 = Guard.check(new Guard.Request("/dump", "", "com.android.chrome",
                    Guard.Origin.APP));
            expect("第二次仍 DENY", d2, "DENY");

            // 令牌对但动作被拒 → false
            boolean allowed = Guard.check(new Guard.Request("/tap?x=1&y=2", "", "com.eg.android.AlipayGphone",
                    Guard.Origin.APP)).isAllow();
            expect("支付宝点击 not allow", allowed ? Decision.allow() : Decision.deny("x"), "DENY");
        } else {
            // plain：即便 guard 坏了也不经过它（常量判定在调用之前）
            Decision d = Guard.check(new Guard.Request("/tap?x=1&y=2", "", "com.eg.android.AlipayGphone",
                    Guard.Origin.APP));
            expect("plain 恒 ALLOW", d, "ALLOW");
        }

        // 身份层：token 为空 / 太短 / 不匹配 一律 false（与 flavor 无关）
        String raw = "POST /tap HTTP/1.1\r\nX-DSH-Token: 0123456789abcdef0123456789abcdef\r\n\r\n";
        expect("token 匹配", LocalAuth.okIdentity("0123456789abcdef0123456789abcdef", raw, "/tap")
                ? Decision.allow() : Decision.deny("x"), "ALLOW");
        expect("token 不匹配", LocalAuth.okIdentity("ffffffffffffffffffffffffffffffff", raw, "/tap")
                ? Decision.allow() : Decision.deny("x"), "DENY");
        expect("expected 为空", LocalAuth.okIdentity("", raw, "/tap")
                ? Decision.allow() : Decision.deny("x"), "DENY");
        expect("expected 太短", LocalAuth.okIdentity("abc", raw, "/tap")
                ? Decision.allow() : Decision.deny("x"), "DENY");

        System.out.println("---- pass=" + pass + " fail=" + fail + " ----");
        System.exit(fail == 0 ? 0 : 1);
    }

    private static void expect(String name, Decision d, String kind) {
        if (d.kind.name().equals(kind)) {
            pass++;
            System.out.println("  ok   " + name + " → " + d.kind.name());
        } else {
            fail++;
            System.out.println("  FAIL " + name + " → " + d.kind.name() + "（期望 " + kind + "）");
        }
    }

    private ReverseTest() {
    }
}
