# Roadmap

> 真机实测（2026-10-13）后整理的下一步。**☐ = 待办**。

## 🔧 待修（真机发现）

- [ ] **引擎不能常驻** —— 截图实证：App 打开是「**未启动**」→ 得手动点「**启动引擎**」→ 变「引擎运行中」→ 还得再点「**打开主界面**」才进得去。**两次手动操作 = 劝退。** 目标：**点 App 开屏直接到输入框**（引擎自动起、自动进会话）。参考 **DSHA 的「前台服务 + 看门狗」（Web 掉了自动拉起，不用手动重启）**。
  - **⚠️ 2026-10-15 验收复现（回归）**：**引擎自启动没了** —— 两次冷启 App 均落「未启动」、得手动点「启动引擎」。而"已完成"区记着"引擎常驻 A+B 真机验过自动启动" → **疑为回归**，待查（可能与工作区未提交的 `EngineCore` 常驻改造有关）。
  - **⚠️ 2026-10-15 又复现（回归）**：**引擎退出后会断** —— 退出后**保活通知与引擎状态自相矛盾**（通知「运行中 · 引擎状态：未运行」，保活服务在但引擎已退），不自动拉起、连接断掉。与上条同源，属看门狗缺失。
- [ ] **开屏直达输入框** —— 别让用户点两次（启动引擎 + 进界面）。理想：秒开 → 输入框就绪。
  - **📋 已派 ZCode 审计 DSHA**（2026-10-15）：任务书 `G:\code\dsh-android\ZCode-DSHA审计.md`（含 DSHA 确切入口：`app/recovery/RecoveryService.java` 等）→ 产出 `DSHA-审计报告.md`（DSHA 怎么常驻/秒进 + 给 woaiys3 的落地改法）。

- [ ] **语音唤醒调不出来** —— **已诊断（ZCode 2026-10-14，不是 bug）**：
  - ① **全 app 没有任何 UI 入口能开唤醒，也不随引擎自动开** —— 唯一开关是 AI 调 `voice_wake start`。所以"启动引擎后直接喊"= 没东西在听，没反应是**必然**。
  - ② **离线模型没下完 → `/wake start` 被拒**（要 KWS 5MB + SenseVoice 239MB 双双 ready；国内直连 HF/GitHub 常不可达）。→ **✅ 已修（T4，`3edabf5` 走镜像）+ 真机验过（2026-10-15）**：模型真下下来（252 MB，`asrState=ready`）。⚠️ 遗留：**429 不重试**，首轮撞限流仍会整体失败（见 `TODO.md` A7）。
  - ✅ **已排除**：token / 阈值 / 音频格式 / 半双工闸（本地沙盘同构复现 **3/3 命中**，`y ú` 正确、`y ǘ` 反而错）。
  - **📋 开麦入口（已记下，后面做）**：三选一 —— (A) 引擎启动后自动开 / (B) 悬浮窗加开关 / (C) 都要。**倾向 B（+可选 A）**：常驻麦克风该让用户知道并可控。
  - 排查口径：logcat TAG = **`dsh-voice`**（不是 `VoiceService`）。
- [ ] **免常驻** —— 现在语音靠前台服务常驻（耗电、易被杀）。要一个"不必一直后台挂着"的形态。

### 📌 开麦 + 保活：别人怎么做的（2026-10-14 扒 Luwi/OpenClaw 实证）

**开麦（麦克风常驻听着）**：① 前台服务 **`foregroundServiceType="microphone"`**（Luwi=`microphone|specialUse`，OpenClaw 声明 `FOREGROUND_SERVICE_MICROPHONE`）② 常驻通知 ③ `START_STICKY` ④ `ServiceCompat.startForeground(..., MICROPHONE or SPECIAL_USE)` ⑤ KWS 守门（~100KB, <1% CPU）+ ASR 唤醒后起。

**保活**：① 电池白名单 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（OpenClaw）② 开机自启 `RECEIVE_BOOT_COMPLETED` + BootReceiver ③ **国内厂商 ROM 适配**（华为/小米/OPPO 各要单独要自启动 + 关省电）④ JobScheduler 定期重启（需 Shizuku/root）。

**⚠️ 2026 新规**：① 前台服务 **≠ CPU 不睡**，保活要 partial wake lock，但 **Google 2026-03 起对 wakelock 上架执法**（屏幕关着平均持 2h+）→ 克制 ② Android 17：后台音频要可见 Activity 或非 SHORT_SERVICE 前台服务。

**🔴 咱们 app 的雷 + 缺口**：
- ✅ **`VoiceService` 声明的是 `foregroundServiceType="dataSync"`，但它开麦克风** → 安卓要求用麦克风的前台服务声明 **`microphone`** 类型（targetSdk 28 暂时豁免，但提 SDK/部分 ROM 会静默掐麦）→ **已改成 `microphone`**（T10，`28e99fc`）+ 真机验过（2026-10-15，`voice server listening on 3280` 不崩）。
- ❌ 完全没做电池白名单、开机自启 → 后台被杀概率本来就高。

**补法清单**：① ✅ VoiceService 类型改 `microphone`（已完成）② 加电池白名单 + 引导页请求 ③ 开机自启（可选）④ 开麦入口（拍板 A/B/C）⑤ 慎用 wakelock。
- [ ] **接入系统语音助手** —— 现在**进不了系统的「语音助手」列表**（默认助理成不了）。需要注册 `VoiceInteractionService`，才能被长按电源键 / 设为默认助理唤起。

## 🚧 计划

- [ ] **⭐ 好好写 AGENTS.md + system prompt（下一版）** —— 根因级改进：模型**不知道"自己有什么手"**（有记忆库不读、有语音工具不主动调），而 `AGENTS.md` + system prompt 是**唯一能告诉它的地方**。
  - **`AGENTS.md`** = 环境 + 能力说明书：① 跑在哪（手机 dsh）② **家底清单**（记忆 `memory_*` / 语音 `voice_*` / 手机操作 `android_*`/`lw_*` …）—— ⚠️ **每条要写"什么时候用"**，光列名字模型还是不会用 ③ 工作目录/约束 ④ 干活规矩（先观察后动手、危险操作先问）。
  - **system prompt / persona** = 身份 + 行为：① 你是谁（人格）② 行为边界（危险操作/不确定/被拒绝）③ **语音场景要短、口语、能被打断**（最易被忽略）。
  - 两者互补：AGENTS.md 是客观「有什么」，system prompt 是主观「我是谁、怎么行为」。

  > **😤 顺便吐槽一句**：
  > DeepSeek 团队居然**不在 system prompt 里告诉模型自己有什么能力** —— dsh 默认 prompt 就一句
  > `You are an AI agent powered by DeepSeek Harness`。结果：模型不知道自己有记忆库、有语音工具、能开麦……
  > 摆着的工具不主动用、存着的记忆不读，**得让它自己读自己的源码，来搞清楚自己能干嘛** 🤣。
  > 这份说明（AGENTS.md + system prompt）不是可有可无的文档 —— **它是模型唯一的「自知之明」来源**。

- [ ] **操作记忆** —— 每次执行完操作，记录这次"是怎么做到的"（哪个开关在哪、界面长什么样）；下次类似指令**直接读记录执行**，不必每次重新试错。
- [ ] **能走 API 的走 API** —— 有些能力直接调 Android API，比 UI 自动化可靠；UI 自动化作最后手段。
- [ ] **半屏助手界面** —— 像系统语音助手那样只占半屏的 UI。
- [ ] **助手界面背景** —— 拟人化形象当背景。**已定配色**：
  - **深色主题** = heavy 档（降饱和 ×0.40 / 虚化 22px / 压暗 ×0.58 / 深底遮罩 0.60）
  - **浅色主题** = medium 档（降饱和 ×0.60 / 虚化 14px / 提亮 ×1.12 / 白遮罩 0.45）
  - 可选增强：裁掉"比耶的手"、顶部/底部渐变遮罩（压 UI 区、中间留氛围）

## ✅ 已完成

- [x] 语音 v2：唤醒词（sherpa-onnx KWS）+ 离线识别（SenseVoice）+ 语音播报（含半双工闸）+ 浮标
- [x] 语音插件 `dsh-tool-voice`（8 个 AI 工具）
- [x] 记忆插件（L3 版本化事实 + L4 摘要 + 夜间整理）
- [x] 桌面图标（自适应 + legacy）
