package com.deepseek.harness;

/** 构建期模板：build.sh 会把它拷成 out/gen/com/deepseek/harness/BuildInfo.java。 */
public final class BuildInfo {
    public static final String FLAVOR = "plain";

    public static boolean guarded() {
        return "confirm".equals(FLAVOR);
    }
}
