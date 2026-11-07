package com.deepseek.harness.guard;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 原生确认框（ask 的唯一出口）。
 *
 * 几个必须的细节：
 * - 窗口用 TYPE_APPLICATION_OVERLAY + FLAG_NOT_FOCUSABLE：浮在人正看着的那块屏幕上，
 *   但不抢输入焦点（否则无障碍读屏/输入会读到我们的弹窗，而不是微信的输入框）。
 * - FLAG_SECURE：模型自己的截图通道拍不到这个框。
 * - 同一时刻只允许一个 pending；等待期间 GuardImpl 拒绝其它设备动作（工具层互斥锁）。
 * - 超时 / 建不出窗口 / 拿不到悬浮窗权限 → 一律 false（deny + 解锁）。
 * - 不引用任何 R.* 资源（这份代码也会被编进 vscreen 的独立 jar，那里没有 App 资源）。
 */
public final class Consent {

    private static volatile Context sCtx;
    private static final Object LOCK = new Object();
    private static volatile boolean sPending;

    private Consent() {
    }

    public static void install(Context context) {
        if (context == null) return;
        sCtx = context.getApplicationContext();
    }

    /** 是否有确认框正在等人处理。 */
    public static boolean isPending() {
        return sPending;
    }

    /**
     * 同步询问。true=人点了「允许一次」；false=拒绝/超时/无法显示/已有待确认。
     * 这个方法会阻塞调用线程（本地 HTTP 的处理线程），最长 timeoutMs。
     */
    public static boolean ask(final String title, final String detail, long timeoutMs) {
        final Context ctx = sCtx;
        if (ctx == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx)) return false;
        } catch (Throwable t) {
            return false;
        }
        synchronized (LOCK) {
            if (sPending) return false;
            sPending = true;
        }

        final long budget = Math.max(3000L, Math.min(timeoutMs, 60000L));
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean allowed = new AtomicBoolean(false);
        final AtomicBoolean settled = new AtomicBoolean(false);
        final Handler main = new Handler(Looper.getMainLooper());

        final WindowManager[] wmRef = new WindowManager[1];
        final View[] viewRef = new View[1];

        final Runnable removeNow = new Runnable() {
            @Override public void run() {
                try {
                    if (wmRef[0] != null && viewRef[0] != null) {
                        wmRef[0].removeView(viewRef[0]);
                    }
                } catch (Throwable ignored) {
                }
                wmRef[0] = null;
                viewRef[0] = null;
            }
        };

        final Runnable finish = new Runnable() {
            @Override public void run() {
                removeNow.run();
                if (settled.compareAndSet(false, true)) done.countDown();
            }
        };

        final Runnable timeout = new Runnable() {
            @Override public void run() {
                finish.run();
            }
        };

        main.post(new Runnable() {
            @Override public void run() {
                try {
                    WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                    if (wm == null) {
                        finish.run();
                        return;
                    }
                    FrameLayout root = buildRoot(ctx, title, detail, allowed, finish);
                    int type = Build.VERSION.SDK_INT >= 26
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE;
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT,
                            type,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                    | WindowManager.LayoutParams.FLAG_SECURE
                                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                            PixelFormat.TRANSLUCENT);
                    lp.gravity = Gravity.TOP | Gravity.START;
                    lp.packageName = ctx.getPackageName();
                    wmRef[0] = wm;
                    viewRef[0] = root;
                    wm.addView(root, lp);
                    main.postDelayed(timeout, budget);
                } catch (Throwable t) {
                    finish.run();
                }
            }
        });

        boolean completed = false;
        try {
            completed = done.await(budget + 1500L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!completed) {
            main.post(removeNow);
        }
        synchronized (LOCK) {
            sPending = false;
        }
        // 窗口刚移除，给 WindowManager 一点时间把输入焦点还给下面的 App。
        if (completed && allowed.get()) {
            try { Thread.sleep(150L); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        return completed && allowed.get();
    }

    private static FrameLayout buildRoot(final Context ctx, String title, String detail,
                                         final AtomicBoolean allowed, final Runnable finish) {
        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(0xD9000000);
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(ctx, 20), dp(ctx, 16), dp(ctx, 20), dp(ctx, 14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(28, 28, 34));
        bg.setCornerRadius(dp(ctx, 16));
        bg.setStroke(dp(ctx, 1), Color.rgb(72, 72, 88));
        card.setBackground(bg);

        TextView titleView = new TextView(ctx);
        titleView.setText(title == null || title.isEmpty() ? "AI 请求确认" : title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        titleView.setGravity(Gravity.START);
        card.addView(titleView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(ctx);
        TextView detailView = new TextView(ctx);
        detailView.setText(detail == null ? "" : detail);
        detailView.setTextColor(Color.rgb(226, 226, 234));
        detailView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        detailView.setTextIsSelectable(true);
        detailView.setPadding(0, dp(ctx, 12), 0, dp(ctx, 12));
        scroll.addView(detailView);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(ctx, 4);
        slp.bottomMargin = dp(ctx, 4);
        card.addView(scroll, slp);

        TextView hint = new TextView(ctx);
        hint.setText("点「允许一次」只放行这一次；不操作将自动拒绝，之后可正常使用。");
        hint.setTextColor(Color.rgb(150, 150, 164));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        card.addView(hint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(ctx, 14);
        row.setLayoutParams(rowLp);

        TextView denyBtn = pill(ctx, "拒绝", Color.rgb(58, 58, 70), Color.WHITE);
        denyBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                allowed.set(false);
                finish.run();
            }
        });
        row.addView(denyBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView allowBtn = pill(ctx, "允许一次", Color.rgb(42, 98, 214), Color.WHITE);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(ctx, 10);
        allowBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                allowed.set(true);
                finish.run();
            }
        });
        row.addView(allowBtn, alp);
        card.addView(row);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        clp.leftMargin = dp(ctx, 22);
        clp.rightMargin = dp(ctx, 22);
        root.addView(card, clp);
        return root;
    }

    private static TextView pill(Context ctx, String text, int bgColor, int textColor) {
        TextView v = new TextView(ctx);
        v.setText(text);
        v.setTextColor(textColor);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(ctx, 18), dp(ctx, 10), dp(ctx, 18), dp(ctx, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(ctx, 20));
        v.setBackground(bg);
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    private static int dp(Context ctx, int value) {
        try {
            return Math.round(value * ctx.getResources().getDisplayMetrics().density);
        } catch (Throwable t) {
            return value;
        }
    }
}
