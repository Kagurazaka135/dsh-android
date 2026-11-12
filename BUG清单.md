# BUG 清单

> 长期台账。一条 bug 一行，状态变更时更新，不删历史。
> 详细报告在 `报告-*.md`，GitHub issue 见链接列。

| ID | 现象 | 层归属 | 状态 | 记录 |
|---|---|---|---|---|
| B-001 | 点搜索会话后抽屉点不动，整页像卡死 | 本仓库 layout 补丁 | 已修（1.21.10） | [issue #2](https://github.com/Kagurazaka135/dsh-android/issues/2)、`报告-2026-11-12-搜索卡死与粘贴截断.md` §2 |
| B-002 | 长文本粘贴有时只剩最后一段（复现不稳定） | **未定**：输入法重切 composing ／ 本仓库 focusin 的 blur 抢焦点，两者都能造出症状 | 未修，只检测留证 | [issue #3](https://github.com/Kagurazaka135/dsh-android/issues/3)、`报告-2026-11-12-搜索卡死与粘贴截断.md` §3；任务书 `任务书-2026-11-12-Copilot-BugB-粘贴截断.md` |

---

## 设备状态污染（不是代码 bug，是流程事故）

| ID | 现象 | 原因 | 状态 |
|---|---|---|---|
| D-001 | 手机锁屏消失，只剩降亮度 | 自动化脚本 `svc power stayon true` 改写了 `stay_on_while_plugged_in=15`，收工没还原 | 已还原（2026-11-12 12:xx，实测三项均为默认值） |

### D-001 · 锁屏被自动化脚本吃掉

**现象**：手机放着不动，屏幕不熄灭、不出现锁屏，只有 MIUI 把亮度压下去。拔掉充电线才恢复正常。

**根因**：排查脚本每轮开场都执行

```bash
svc power stayon true; input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard
```

后两条是本次操作（不改设置），**第一条是持久设置**——把 `settings global stay_on_while_plugged_in` 写成 `15`（AC | USB | 无线 | 座充全开），重启不恢复。设备当时正插 USB，于是屏幕永不熄灭，锁屏根本没有出现的机会。

会话记录里同一组合命令出现 **43 次**（每轮测试前一次），全部未回滚。

**排查要点**：先看 `dumpsys power | grep mStayOn` 和 `settings get global stay_on_while_plugged_in`，再怀疑锁屏本身。**注意排除项也要记录**：`lockscreen.disabled=0`（正常）、`screen_off_timeout=30000`（正常），只有 `stay_on` 一项异常。

**还原**：

```bash
adb shell svc power stayon false
adb shell settings get global stay_on_while_plugged_in   # 应为 0
adb shell dumpsys power | grep mStayOn                   # 应为 false
```

### 硬规则：动设备状态必须三件套

任何自动化任务（真机测试、脚本排查）**改动设备设置**时：

1. **改前报备** —— 说清改哪个键、改成什么、为什么非改不可
2. **改后声明** —— 报告里单列一节「设备状态变更」，逐条列出键与值
3. **收工还原** —— 交付前恢复原值并**验证恢复成功**（贴命令输出）

**适用范围**（非穷举）：屏幕常亮/超时、亮度、音量、勿扰、开发者选项、USB 调试授权、输入法、默认应用、电池优化白名单、时区与语言、动画缩放、锁屏设置。

**为什么**：手机是主人的日常设备，不是测试台。一个没还原的设置会让主人在完全无关的场合遇到怪异行为，而且因为"什么都没改过"而根本想不到去查设置——这次就查了半个晚上。

---

## B-001 · 点搜索后抽屉点不动

**一句话根因**：抽屉子节点写成条件表达式（`div` ↔ `Sidebar` 类型切换）→ React 卸载重挂整棵侧栏 → `searchRequest` 计数器的 effect 用旧值重放 → `expandSidebar()` → 与用户点击的 `toggleSidebar()` **相消**（一次点击 = 两次 toggle）。

**为什么只有点过搜索才出现**：没点搜索时计数器为 0，effect 直接 return，重挂无副作用。

**修法**：改成恒定结构 + `display: contents` 收起。

**同类坑**：**任何「包一层」的补丁都会改变 React 树形状**。判断一个包裹是否安全，标准不是「看起来一样」，是**元素类型是否稳定**。

---

## B-002 · 长文本粘贴只剩最后一段

**一句话根因**：输入法先把整段塞进 composing 区，再重切成更短的一段（`setComposingText` 是替换语义），最后只提交最后一段。

**已排除**：不是本仓库引入（桌面不复现、禁 mobile.js 不复现、`dsh-client-ui-conversation` 与上游逐字一致）；上游无长度阈值、无粘贴转附件。

**为什么不直接修**：任何「补回被覆盖文本」的做法都会与正常拼音纠错冲突（纠错同样是替换 composing 区）。

**当前做法**：`mobile-patch/mobile.js` 末尾检测段，判定「真后缀 + 长度不到一半 + 最长 ≥200 字」，只留证不改正文。

**同类坑**：**IMPORTANT：`setComposingText` 是替换不是追加**。凡是看到「长文本粘贴只保留结尾」，先怀疑 composing 区被重切，再去查代码里的截断逻辑。

---

## 待记（遇到再补）

- 记录格式：ID / 现象 / 层归属 / 状态 / 记录位置
- 层归属四选一：**本仓库补丁** / **上游 dsh** / **WebView·输入法** / **未定**
- 每条必须带「同类坑」一行——那是给下一次的自己看的
