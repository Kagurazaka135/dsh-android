# TODO — dsh-android 待解决清单

> 2026-10-15。活的待办都在这儿（搁置的项目级想法在记忆 `deferred_parking_lot`，不混进来）。
> **⬜ 待办 · 🔄 进行中 · ✅ 完成**

---

## ✅ A1 · 三个真机发现的 bug（ZCode 已修，2026-10-15 真机验收通过）

任务书：`ZCode-修三bug.md`。来源：`真机测试报告-2026-10-15.md`。**验收报告：`验收报告-三bug修复-2026-10-15.md`**。

- [x] **Bug1 · 模型下载走国内镜像**（T4 唤醒死因）—— `VoiceModelStore` 的 HF/GitHub 源 → 镜像优先+回落；`hf-mirror.com` / `ghfast.top`；**SHA256 校验不动**。✅ 真机验过：模型真下下来（累计 252 MB，`asrState=ready`）。
- [x] **Bug2 · 开屏落点要稳**（T1）—— ⚠️ 实际实现**不是**"查会话页 URL 让 `webHomeUrl()` 指过去"，而是 `mobile-patch/mobile.js` **注入 JS 预置修正 localStorage**（剥 `dsh.sessions.current.subagentAddress`、把 `dsh.conversation.*.view` 归位 `chat`）。✅ 真机验过：2 次冷启都落会话页+chat，草稿保留。
- [x] **Bug3 · `VoiceService` 服务类型**（T10，一行）—— `dataSync` → `microphone`。✅ 静态（manifest）+ 真机（`listening on 3280` 不崩）均过。

## ⬜ A7 · 验收遗留（2026-10-15，源自 A1 验收）
- [ ] **⚠️ 引擎自启动没了（回归）** —— 冷启 App 落「**未启动**」，得**手动点「启动引擎」**才起（两次冷启均复现）。已完成区记着"引擎常驻 A+B 真机验过自动启动" → **疑为回归，待查**。（可能与工作区未提交的 EngineCore 常驻改造有关，见 A2。）
- [ ] **⚠️ 引擎退出后会断（回归）** —— 引擎退出后**连接断掉 / 保活通知状态自相矛盾**。截图实证（2026-10-15 18:36）：通知栏显示「🐋 DeepSeek Harness **运行中** · 引擎状态：**未运行**」+「AI 引擎保活中，后台任务持续执行」——**保活服务还在，但引擎已退**，状态不一致且不自动拉起。与上一条同源，均属引擎常驻/看门狗范畴。
- [ ] **Bug1 遗留：429 不重试** —— `VoiceModelStore.download()`（`:248`）对 429/5xx 直接抛异常换源 → 一次**瞬时**限流就把 hf-mirror 废掉、换到必然被墙的原源。**实测踩过**（首轮下载因此失败）。修：退避重试（2s/5s/15s）；HF 侧只 1 个镜像，建议补第二个。
- [ ] **Bug2 遗留：`pm clear` 全新安装项未验**（会清 252MB 模型，代价大，留作复验）。


## ⬜ A2 · 提交 + 推送
- [ ] 工作区未提交：`EngineCore.java`(新) + `build.sh`/`EngineService.java`/`MainActivity.java`(改) + 几份报告。
- [ ] GitHub 远端也落后（图标那版之后没推）。

## ⬜ A3 · 唤醒的"开麦入口"
- [ ] 现在**全 app 没 UI 入口开唤醒**、也不随引擎自动开，唯一开关是 AI 调 `voice_wake start`。
- **待拍板**：(A) 引擎启动自动开 / (B) 悬浮窗开关 / (C) 都要。倾向 **B（+可选 A）**。

## ⬜ A4 · AGENTS.md 定稿 + 进包
- [ ] 草案：`dsh-home/AGENTS.md`（照 CLAUDE.md 骨架，脱敏过，人格=一句"自信阳光的蓝色大肥鱼"）。
- [ ] 让 `build.sh` 把它拷进包的 `dshhome/`（现在没接这步）+ 补 system prompt 那半。

## ⬜ A5 · ROADMAP 里那批（详见 `ROADMAP.md`）
- [ ] 免常驻（现在是前台服务常驻）
- [ ] 接入系统语音助手列表（注册 `VoiceInteractionService`）
- [ ] 操作记忆（执行完记手机设置细节，下次直接读，免重复试错）
- [ ] 能走 API 的走 API（UI 自动化降为最后手段）
- [ ] 半屏助手界面
- [ ] 助手界面背景（配色已定：深色=heavy / 浅色=medium）

## ⬜ A6 · 杂
- [ ] Copilot 试手（交接 `Copilot-交接.md` 写了，还没用）
- [ ] 真机测试方案里 T5-T9 的**阻塞**（没法 adb 驱动手机 AI）—— 挑一个解（AOSP 键盘 / WebView debug / Shizuku / 人工）
- [ ] T3 看门狗真实机验（要 root/Shizuku，现在没）

---

## ✅ 已完成（近期）
- [x] 引擎常驻 A+B（Codex 实现 → opencode 核红线 → ZCode 构建收尾 → 真机验"自动启动/常驻"过）
- [x] 桌面图标（自适应 + legacy）
- [x] 语音 v2 的插件描述 bug 修复
- [x] scrcpy 环境搭好（电脑控手机）
