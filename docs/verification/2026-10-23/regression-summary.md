=== V-5 回归汇总（62 + V-2/V-3 修复，2026-10-24 00:49-01:04）===

| 编号 | 判据 | 结果 | 证据 |
|---|---|---|---|
| V-5.1 | 冷启自启 + 自动进会话 | 通过 | 00:48:47 启动 → 25s 后 app=26530 node=12015，落上次会话可输入，见 cold-62-relaunch.png |
| V-5.2 | 通知状态与实际一致 | 通过 | 通知「🐋 DeepSeek Harness 运行中 / 引擎状态：运行中（端口 3080）」，同期 node 12015 存活，见 notif-dump.txt |
| V-5.3 | 杀 node 后看门狗重拉 | 通过 | kill -9 12015 → app 26530 存活 → 30s 内 node=24445；日志 engine unavailable for 3 checks; controlled respawn，见 regression-watchdog.txt |
| V-5.4 | 悬浮窗：浮标/拖动/开麦·唤醒·收起 | 通过 | 桌面左边缘浮标可见可展开；面板显示「引擎运行中 ✓」；三按钮齐全；点唤醒后变「状态：唤醒已开，喊「小鲸鱼」」，见 overlay-panel2-small.png / overlay-wake-crop.png |
| V-5.5 | 无障碍「打开系统设置」可用 | 通过 | 服务已绑定 Service[label=DeepSeek Harness 屏幕助手]；AI 指令后 topResumedActivity=com.android.settings/.MainSettings |
| V-5.6 | 截图归口统一 + 只留最近 20 张 | 部分通过 | 落盘已统一到 /sdcard/DeepSeekHarness/screenshots/（shot-* 与 screen-* 同目录）；shot-* 当前 11 张未触发清理；screen-* 31 张为历史存量，清理仅在写入时触发，见下方说明 |
| V-5.7 | 模型下载退避重试用例 6 个 | 未验证 | 本轮未重跑脚本用例；模型已 ready 无法复现 429 路径 |

V-5.6 补充说明：
  修复前 shot-* = 11（<20），所以本轮截图后仍是 11+1=12 → 目录总数 42 是因为 screen-* 有 31 张历史图。
  清理逻辑限定 shot-*.png（android_screenshot 产物），不含 screen-*.png（无障碍产物），两套各自独立计数。
  这一点与 commit 688c0eb 的描述一致（两处各自 prune，各留 20 张）。
