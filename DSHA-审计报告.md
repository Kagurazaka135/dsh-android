# DSHA 审计报告 —— 引擎常驻机制与开屏流程，及对 woaiys3 的移植方案

- **审计对象**：`github.com/DSH-APP/DSHA`（Apache-2.0，安卓端 dsh 启动器）
- **审计基线**：本地浅克隆 `G:\code\dsh-android\.cache\DSHA`，commit `810fb03`（"chore: 同步 build173 源码并刷新 Star History"，2026-10-15）
- **施工目录**：`G:\code\dsh-android\`（本报告落盘位置，未改动任何代码）
- **举证约定**：凡结论均给 `文件:行`。DSHA 文件路径省略前缀 `app/src/main/java/com/deepseekharness/app/`；woaiys3 文件路径省略前缀 `woaiys3-app/android-app/`。
- **审计方法**：逐文件读码 + 全仓 grep（watchdog / BOOT_COMPLETED / sentinel / keepalive / enterWeb 等关键词），关键链路双边核对（声明处 + 调用处 + 门禁处）。

---

## 0. 五问速答

任务书提的 5 个问题，直答：

| # | 问题 | 一句话答案 |
|---|------|-----------|
| 1 | 引擎怎么常驻？ | **HarnessService 前台服务**（15 秒看门狗 + START_STICKY + WakeLock/WifiLock + 容器内第二层 bash 看门狗 + 停止哨兵文件双保险）。**不是**任务书标⭐的 RecoveryService——它只是用户手动启动的"应急 DSH" |
| 2 | 开屏流程？ | LAUNCHER = MainActivity（底部导航壳）→ 默认停在「启动」页（LaunchFragment）→ 引擎就绪后**点一次**「进入工作台」→ WebPreviewActivity（WebView 输入框）。**DSHA 冷启动也不是零点击**；只有通知/外部入口带 `open_web` 时才会免点击自动进入 |
| 3 | 看门狗"Web 掉了自动拉起"怎么做？ | HarnessService 内守护线程（`startKeepAlive`，15s 一轮）：分层健康探测（连接拒绝 ≠ 鉴权过期 ≠ 页面坏）→ **只有"连接被拒"连续 3 次失败且距上次重启 >120s** 才提交受控重启；另有容器内 `/root/dsh-watchdog.sh` 作为第二层 |
| 4 | BootReceiver 收到 BOOT_COMPLETED 干了啥？ | **只服务 ADB 设备桥**：没开无线调试直接 return；有权限就自动开无线调试并调度 ADB 保活闹钟。**不开机拉起 Web 引擎**——README 转述的"重启手机自动恢复"在 Web 这一层没有兑现 |
| 5 | 和 woaiys3 差距？woaiys3 为什么要手动点？ | 三个根因：① 冷启动分支**写死停在控制台**（`MainActivity.java:452-459`，v1.12 刻意"不抢界面"）② 看门狗**寄生在 MainActivity**（只有进过主界面才启动，停在控制台时无守护，进程被杀守护即死）③ EngineService 只是**通知壳**（无探测、无拉起、无 WakeLock；无开机自启）。详见 §3 |

---

## 1. DSHA 的引擎常驻机制（代码证据）

### 1.1 先纠正一个假说：主角不是 RecoveryService

任务书把 `recovery/RecoveryService.java` 标为⭐常驻核心。实际读码：它是**用户手动启动的"应急 DSH"前台服务**，注释原文明说：

> `recovery/RecoveryService.java:11-12`："用户启动的应急服务有自己的前台通知，不接入正式 Web 的看门狗。"

它甚至带自停逻辑（`RecoveryService.java:74-108`：不忙、未就绪、无活水、不在修复 → `stopSelf`）。

**真正的常驻主角是 `HarnessService.java`**（511 行）。类注释即自述：

> `HarnessService.java:19-28`："前台保活服务：让 dsh Web UI 在后台稳定常驻。startForeground 常驻通知；START_STICKY 被杀后由系统重启；看门狗：本轮端口、官方鉴权和 HTTP 就绪分层检查；仅连续连接拒绝可提交受控重启；WakeLock/WifiLock 息屏保活。"

（定位方法：全仓 grep "watchdog\|看门狗" + 追 `START_STICKY` 命中的服务。）

### 1.2 常驻的实体：HarnessService 前台服务

- **声明**：`AndroidManifest.xml:174-181` —— `foregroundServiceType="specialUse|connectedDevice"`，附 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property（Android 14+ 的 FGS 类型校验要求；文案："User-started local AI development server and terminal runtime with an ongoing notification and explicit stop action."）。
- **启动入口**：LaunchFragment 的 `doStart()`（`ui/LaunchFragment.java:239-252`）在 `controller.startWeb()` 之后 `startForegroundService(ACTION_START)`。
- **onStartCommand**（`HarnessService.java:131-169`）：
  - 无条件先 `showForegroundNotification()`（满足 Android 8+ 的 5 秒 startForeground 契约）；
  - `ACTION_STOP` 意图 → `stopWebAndSelf()`；
  - **晚到的 START 意图在交付时重查"用户已停止"状态**（防 START_STICKY 重建后违背用户意图拉起）；
  - 返回 `START_STICKY` —— 被系统杀后由系统重建。
- **通知**（`HarnessService.java:480-510`）：正文点击进 MainActivity 带 `open_launch`；含独立「停止」action（PendingIntent 打 Service）。`showForegroundNotification` 在 SDK ≥34 用 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`，LAN 模式追加 `CONNECTED_DEVICE`（`:451-462`）。

### 1.3 看门狗：15 秒一轮的分层健康探测

**常量**（`HarnessService.java:45-47`）：

```java
KEEPALIVE_INTERVAL_MS = 15000L   // 轮询间隔
RESTART_COOLDOWN_MS   = 120000L  // 重启冷却
KEEPALIVE_MAX_FAIL    = 3        // 连续失败阈值
```

**主循环**（`HarnessService.java:310-398`，线程名 `dsha-keepalive`），每轮：

1. `bridge.ensureStarted()` —— 顺手保证 DeviceBridgeService 活着；
2. `refreshLocks()` —— 重估 WakeLock/WifiLock；
3. `ensureLanListening()`；
4. **`c.canAutoRestart()` 门控**（见 1.4 的门禁链）；
5. `WebHealthProbe.probe(authUrl, port, current, 3000)` —— 分层探测；
6. 结果处理：
   - `STALE`（代次过期）→ 重置 fail 计数，不计账；
   - healthy → `reportWebHealth(true)`，重置 fail；
   - 不健康但不是"可重启候选" → `reportWebHealth(false)`，**不计 fail**；
   - 可重启候选 → fail +1；`fail >= 3` 且距上次重启超 120s 冷却 → `c.restartWebAutomatically(generation, msg -> {})`。

**分层探测的语义**（`util/WebHealthProbe.java:26-35`）：

```java
healthy()         = (state == HTTP_READY)   // 只有 HTTP 就绪才算健康
restartCandidate() = (state == UNAVAILABLE) // 只有 TCP 连接被拒（ConnectException refused）才授权重启
```

探测流程：TCP connect → `DshAuthSession.exchange` 官方鉴权。`AUTH_EXPIRED` / `INVALID_RESPONSE` / `LISTENING` 都**不**授权重启，源码注释原文："A broken page/stream/auth response never authorizes a Node restart."

设计要点：**"进程没在监听"才值得重启，"引擎活着但页面坏/鉴权过期"不值当动它**——避免看门狗变捣乱鬼。

**自动重启的执行处**（`core/WebLifecycleController.java:207-226`）：校验 generation + `canAutoRestart()` + 写失败记录 "服务连续三次健康检查未响应" 后走受控重启。

### 1.4 停不下来的两层防线：userStopped 标志 + 停止哨兵文件

自动拉起有一整条门禁链，任何一层说不，就不拉：

- **懒启动门禁**（`util/WebLifecycle.java:9-20`，`beginStart`）：
  ```java
  if (starting || stopping || (automatic && (userStopped || stopSentinelPresent))) return -1;
  ```
  `:41-43`：`canAutoStart = !starting && !stopping && !userStopped && !stopSentinelPresent`。
- **看门狗侧门禁**（`WebLifecycleController.java:953-958`）：
  ```java
  canAutoRestart() = !config.isStartupRecoveryRequested() && !state.recovery.blocked() && lifecycle.canAutoStart(hasStopSentinel())
  ```
- **停止哨兵文件**（`WebLifecycleController.java:933-938`）：`/root/.dsha-stopped`（相对容器 rootfs，见 `util/WebProcSel.java:31` `STOP_SENTINEL`）。
- **为什么 App 层标志不够**：`WebProcSel.java:27` 注释原文——"App 侧的判据管不到容器里的拉起者 —— 看门狗和它写的重启脚本是独立的 bash 进程"。也就是说容器内还有**第二层看门狗**：`WebProcSel.java:112` 定义 `/root/dsh-watchdog.sh`，`:58-59` 定义 `PID_WATCHDOG = /root/.dsha-watchdog.pid`。
- **哨兵在启动命令里也拦一刀**（`util/WebLaunchCommand.java:40-42`）：起 dsh 的命令串内置 `[ ! -e /root/.dsha-stopped ] || exit 0`——即使有人绕过错层标志直接跑启动命令，哨兵文件在场就 exit。
- **清除哨兵**：`core/StartupEnvironmentActions.java:70` 的 `unlockStartup()`（删哨兵，失败抛"无法清除停止标记"）；写哨兵在 `WebLifecycleController.java:868` 附近。

**结论**：用户按「停止」→ Java 层 `userStopped`（内存）+ 容器内哨兵文件（持久）双保险；START_STICKY 重建、看门狗轮询、容器内脚本三条自动路径全部一票否决。**woaiys3 的 `engineStoppedByUser`（`MainActivity.java:241`）只有内存标志，缺持久层——这是要补的。**

### 1.5 息屏保活：WakeLock / WifiLock

- `acquireLocks(needWifi)`（`HarnessService.java:191-217`）：`PARTIAL_WAKE_LOCK "DSHA:web"` + `WIFI_MODE_FULL_HIGH_PERF "DSHA:wifi"`。
- `refreshLocks()`（`:219-265`）：由 `PowerPolicy.keepCpu(eco, active, starting, interactive, lan, work, idle)` 统一决策，屏幕亮/灭、省电模式、是否有活计都在账上；通知文案随之切换（"持续运行 · 后台保活已开启" / "省电模式 · 已空闲，允许系统休眠"）。
- 对应权限：`AndroidManifest.xml:24` `WAKE_LOCK`、`:28` `CHANGE_WIFI_STATE`、`:15` `ACCESS_WIFI_STATE`。

### 1.6 开机自启的真相：BootReceiver 只管 ADB，不拉 Web

`BootReceiver.java`（74 行）全文职责：

- `:20-33` 接收 `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`（后者调度自动备份）；
- `:34` **第一道闸**：`if (!DeviceBridgeService.isAdbEnabled(context)) return;` —— 用户没开 ADB 就直接收工；
- `:38-54` 若有 `WRITE_SECURE_SETTINGS`（Shizuku 授权）→ `Settings.Global.putInt(..., "adb_wifi_enabled", 1)` 自动开无线调试（thedjchi/Shizuku 机制）；
- `:67-70` `DeviceBridgeService.apply(context)` + `AdbKeepAliveReceiver.schedule(context)`。

配套的 `AdbKeepAliveReceiver.java`：`:22` `INTERVAL_MS = 5 * 60_000L`；`:29-30` `AlarmManager.setAndAllowWhileIdle(RTC_WAKEUP, ...)` 5 分钟一次性闹钟链，每次收到后 `DeviceBridgeService.kickNow` + 重排（`:54-61`）。

**结论**：README 转述的"重启手机自动恢复"在**代码层只覆盖 ADB 设备桥**。Web 引擎没有开机拉起路径——开机后引擎要等用户打开 App（启动页点「启动」，或从配置页/插件页触发代拉）才会恢复。**这一条不在 DSHA 抄，反而 woaiys3 若要做开机恢复得自己加（见 §4 改法 D）。**

### 1.7 顺带的"引擎代拉"路径（DSHA 的多入口设计）

引擎不止启动页一个拉起点，多处按需代拉：

- `core/DshModelRepository.java:54-67` `connect()`：Web 未起时自动 `controller.startWeb(null)` + `startForegroundService`（**模型配置页会代拉引擎**）；
- `core/PluginRepository.java:853`：插件操作后 `if (resumeWeb && !controller.startWeb(message -> {})) ...`（插件页会复拉）；
- 通知点击路径：`DeviceBridgeService.java:249-262` 与 `HttpShellService.java:1014-1027` 的通知 Intent 都带 `open_web`。

---

## 2. DSHA 的开屏流程（从点图标到进输入框）

### 2.1 主线（冷启动）

```
点桌面图标
  └─ MainActivity（Manifest :66-74，LAUNCHER，windowSoftInputMode=adjustResize）
       ├─ 启动门禁（ui/MainActivity.java:91-108）
       │    ├─ 未完成欢迎引导 → WelcomeActivity
       │    └─ 环境未就绪 → ExtractActivity（解压/修复）
       └─ 主壳：底部导航「启动 / 插件 / 设置 / 终端」
            └─ 默认选中 nav_launch（ui/MainActivity.java:216-219）→ LaunchFragment
                 ├─ 按钮（ui/LaunchFragment.java:143-150）：
                 │    webReady || entryUrl 就绪 → enterWeb()   ← 一次点击进 WebUI
                 │    否则                    → doStart()      ← 先启动引擎
                 ├─ doStart（:193-253）：controller.startWeb(startStatus)
                 │    + startForegroundService(HarnessService ACTION_START)（:239-252）
                 ├─ 每秒刷新循环（:62-77）：
                 │    pendingAutoEnter && 入口 URL 就绪 → 自动 enterWeb()
                 └─ enterWeb（:304-374）：工作线程换 Web 入口 cookie → 校验 generation/url
                      → openWebEntry() → WebPreviewActivity.intent(url, cookie)   ← 输入框在这
```

关键子流程指针：

- **安装引导链**（首次安装）：`OnboardingPrepareActivity`（4/7）→ `ExtractActivity:315-322`（first_setup → ModelSetupActivity）→ `ModelSetupActivity:151-152` → `OnboardingReadyActivity`（7/7）→ MainActivity。
- **按钮文案/状态**（`ui/LaunchFragment.java:462-577`）：就绪显示"DSH 已就绪，可进入"、按钮"▶ 进入工作台"；未就绪显示启动中计时："本阶段 N 秒 · 总计 N 秒"。
- **可测试缝隙**（`LaunchFragment.java:377-391`）：`webEntryUrl() / webEntryGeneration() / exchangeWebEntryCookie() / openWebEntry()` 留了测试注入点。

### 2.2 快捷路径（免点击自动进入，仅特定入口）

通知/外部入口 Intent 带 `open_web` extra 时走自动进入：

- `ui/MainActivity.java:379-397` `consumeOpenWeb()`：把 `open_web` 转成 `LaunchFragment.ARG_OPEN_WEB`；
- `LaunchFragment` 收到 → `pendingAutoEnter = true`；
- 之后每秒循环（`:62-77`）等鉴权入口 URL 就绪 → `enterWeb()`，无需点击。

**时序大坑（源码注释明确警告）**：`MainActivity.java:379-397` —— 由于 `setSelectedItemId` 会**同步**触发 Fragment 监听器，`pendingAutoEnter` 标记必须在 `setSelectedItemId` 之前置位，否则有竞态。

### 2.3 诚实结论：DSHA 也不是"开屏零点击直达"

- 冷启动点图标：最少**一次点击**（引擎已常驻就绪 → 点"进入工作台"；未就绪 → 先点"启动"）。
- 免点击只在"通知/外部入口带 `open_web` **且引擎已就绪**"时成立。
- **DSHA 相比 woaiys3 的体验优势不来自"开屏自动进入"，而来自"引擎不用等"**：常驻 + 看门狗让引擎在用户开 App 时通常已经就绪，所以那一次点击是"随时可进"，而不是 woaiys3 的"点启动 → 等 30 秒 → 再点打开主界面"两段式。

**因此 woaiys3 的目标（点图标直达输入框）要比 DSHA 现状再进一步**：DSHA 提供了两个现成积木——`pendingAutoEnter`（就绪即自动进入）和 `engine-late-bloom`（woaiys3 自己的备胎守望，见 §3.1）——拼起来即可。

---

## 3. woaiys3 现状与"两步手动"根因

### 3.1 现状盘点（文件:行）

| 环节 | 位置 | 现状 |
|------|------|------|
| 冷启动入口 | `MainActivity.java:452-459` | `setup_done` → `showEngineScreen()`；`savedInstanceState == null` → `showConsole()`（**:456**），否则 `startEngine()`（:458）。v1.12 注释："冷启动先停在控制台" |
| 控制台层 | `:589` showEngineScreen / `:4438-4440` showConsole | 自绘控制台 UI（卡片/精简版）；`:7638` refreshConsole，`:7662-7667` 状态文案"引擎运行中/未启动"、"打开主界面/启动引擎" |
| 控制台点击 | `:7751-7780` conEngineClick → conEngineClickAfterProbe | 在跑 → `enterMainUi()`；否则 → `starting=true; startEngine()` |
| 引擎启动 | `:2145-2153` startEngine | `startKeepAliveService(); startVoiceService();` + 端口持久化 + 悬浮窗 + 后台线程（解压/文件准备/spawnNode） |
| 保活服务 | `EngineService.java`（84 行）全文 | **纯通知壳**：onCreate `startForeground(:31)`、onStartCommand 刷通知 + `START_STICKY(:35-40)`。无探测、无拉起、无锁 |
| 拉起引擎 | `:3914` spawnNode / `:7941-7985` launchEngine | launchEngine：healthOk → 写缓存 +（非控制台时）loadHome；否则防重复 spawn 检查（进程句柄/端口）+ `waitForServer` |
| 等待就绪 | `:4110-4149` waitForServer | 90 秒轮询 healthOk；v1.13.12 超时**不再** loadHome（防死端口白屏），回控制台 |
| 超时守望 | `:4155-4179` conEngineTimedOut | **engine-late-bloom**：回控制台 + 后台守望最多 10 分钟，healthOk 且 consoleVisible 时**自动 enterMainUi()** |
| 看门狗 | `:4184-4213` startWatchdog | 5 秒轮询：`!serverUp && !nodeAlive` 且距上次 respawn ≥20s → spawnNode + loadUrl；`engineStoppedByUser` 跳过 |
| 看门狗启动点 | `:4216-4232` loadHome | `if (consoleVisible) return; // v1.12：控制台还开着时不抢界面` → 然后 startWatchdog + loadUrl |
| 进主界面 | `:7899-7910` enterMainUi | consoleVisible=false、收控制台层、捞 token、loadHome |
| 就绪判定 | `:4362-4373` conEngineRunning | 只认真实端口探测（v1.13 修正："进程活着"不算就绪） |
| 健康探测 | `:4088` healthOk → isDshEngine(port) | HTTP 探测目标端口 |
| Manifest | `AndroidManifest.xml:8` | `minSdkVersion 24, targetSdkVersion 28`；`:69-73` EngineService（`foregroundServiceType="dataSync"`）。**无 RECEIVE_BOOT_COMPLETED、无 WAKE_LOCK、无 BootReceiver** |

### 3.2 根因：为什么必须手动点两下

**R1 —— 冷启动写死停在控制台（决策性的一行）**

`MainActivity.java:452-459`：

```java
if (prefs.getBoolean("setup_done", false)) {
    showEngineScreen();
    // v1.12：冷启动先停在控制台；切屏回来/任务恢复（savedInstanceState != null）直接进主界面。
    if (savedInstanceState == null) {
        showConsole();      // ← 冷启动停在这里，引擎不启动
    } else {
        startEngine();
    }
}
```

这是 v1.12 的**刻意设计**（"不抢界面"）。后果：冷启动后用户看到控制台"未启动"→ 点「启动引擎」→ 等 30~90 秒 → 再点「打开主界面」。两个手动点都源自这里。

**R2 —— 看门狗寄生在 MainActivity，且有启动条件**

`startWatchdog`（:4184-4213）**只被 `loadHome()`（:4216-4232）调用**，而 `loadHome` 开头的 `if (consoleVisible) return;` 意味着：

- 停在控制台 = **没有任何守护**；
- 进了主界面后看门狗才活；**App 进程被系统杀掉 → 看门狗一起死**。`EngineService` 的 `START_STICKY` 只重建服务本身（一个刷通知的壳），不会重建 MainActivity 里的守护逻辑，也不会重新 spawn 引擎。

**R3 —— EngineService 能力空洞 + 无系统级保活**

- 无健康探测、无 spawn 能力（`spawnNode` 在 MainActivity 里，12000 行大文件内部方法，:3914）；
- 无 WakeLock/WifiLock（Manifest 连 `WAKE_LOCK` 权限都没有）；
- 无开机自启（无 `RECEIVE_BOOT_COMPLETED`、无 BootReceiver）。

**附带问题**：`engineStoppedByUser`（:241）是**内存变量**，进程重建即丢——一旦把看门狗迁进 Service，必须先把它持久化，否则 START_STICKY 重建后可能违背用户意图自动拉起。

### 3.3 与 DSHA 逐项差距

| 能力 | DSHA | woaiys3 现状 | 差距性质 |
|------|------|--------------|----------|
| 常驻载体 | HarnessService（specialUse，:174-181 Manifest） | EngineService（dataSync 纯通知壳） | 有载体，缺内容 |
| 看门狗位置 | 服务内守护线程（:310-398） | MainActivity 内（:4184-4213），且进主界面才启动 | **位置错**，且带条件启动 |
| 探测语义 | 分层（HTTP_READY / AUTH_EXPIRED / UNAVAILABLE），只重启"连接被拒" | 二值 healthOk()（:4088） | 简化，夠用但要防误重启 |
| 失败阈值 | 3 次失败 + 120s 冷却 | 无（5s 轮询 + 20s respawn 间隔） | 需要补门槛 |
| 用户停止语义 | userStopped + 哨兵文件双保险 | engineStoppedByUser 内存标志 | 缺持久化 |
| 息屏保活 | WakeLock + WifiLock + PowerPolicy（:191-265） | 无 | 全缺 |
| 开机自启 | 有（但只管 ADB 桥，BootReceiver:34） | 无 | DSHA 也没覆盖 Web |
| 就绪后自动进入 | pendingAutoEnter（LaunchFragment:62-77）+ 通知 open_web | engine-late-bloom 只在超时分支出现（:4155-4179） | 已有模式，未用于常规路径 |
| 启动崩溃防御 | 受控重启 + 冷却 | 防重复 spawn（句柄/端口双检，:7941-7985） | woaiys3 这点做得不比 DSHA 差 |

---

## 4. 落地改法（具体到改哪个文件、加什么）

目标：**点图标 → 引擎自动就绪 → 自动进输入框，一个手动点都不要**；同时保留"用户主动停止后不自动拉起"的语义和失败回退控制台的能力。

### 改法 A：开屏直达（自动启动 + 就绪自动进入）

**文件：`woaiys3-app/android-app/src/com/deepseek/harness/MainActivity.java`**

A1. 冷启动分支改造（`:452-459`）：

```java
if (prefs.getBoolean("setup_done", false)) {
    showEngineScreen();
    showConsole();                     // 保留控制台做视觉过渡（用户可见进度），但不"停"在这
    if (!engineStoppedByUser) {        // 用户停过就不自动
        engineAutoEnter = true;        // 新增成员标志：本轮冷启动目标是直达主界面
        startEngine();                 // v1.13 已有的后台线程流程，内部会防重复 spawn
    }
}
```

要点：不再区分 `savedInstanceState`；`engineAutoEnter` 仿 DSHA `pendingAutoEnter`（`ui/LaunchFragment.java:62-77`），**必须在调用任何会同步触发 UI 回调的方法之前置位**（DSHA 在 `ui/MainActivity.java:379-397` 对此有明确时序警告）。

A2. 就绪点挂自动进入钩子，两处覆盖：

- **立即就绪路径**：`launchEngine` 的 `healthOk()` 分支（`:7941-7948`）。现状已是"非控制台时 loadHome"，补上控制台情形：
  ```java
  if (!consoleVisible) loadHome();
  else if (engineAutoEnter) enterMainUi();   // 新增
  ```
- **等待就绪路径**：`waitForServer` 的就绪出口（`:4110-4149` 的 `if (healthOk()) { loadHome(); return; }`）。同样补 `engineAutoEnter && consoleVisible → enterMainUi()`。

A3. 超时分支：已有 `conEngineTimedOut` 的 **engine-late-bloom**（`:4155-4179`，后台守望最多 10 分钟、就绪且 consoleVisible 时自动 `enterMainUi()`）——只需把它的触发条件从"超时才启用"推广为"常规兜底"，或者不加也行：A2 的两处钩子已覆盖绝大多数路径，晚到的引擎仍由 late-bloom 接住。

A4. 消费标志：`enterMainUi()`（`:7899-7910`）内把 `engineAutoEnter = false`（防止用户之后手动回控制台时又被弹走）；用户按下"停止引擎"时也清零。

**体验效果**：冷启动 → 控制台显示"正在启动…计时"→ 自动进入主界面（WebView 输入框）。全程零点击。

### 改法 B：看门狗迁入 EngineService（守护脱离界面，本方案核心）

**文件：`EngineService.java`（主体）+ `MainActivity.java`（瘦身）**

B1. **抽出引擎控制核心**（推荐路线，参照 DSHA 的 `DshaApp.harnessController()` 应用级单例，`DshaApp.java:62-64`）：新建 `EngineCore.java`（放 `src/com/deepseek/harness/`），承载三件事：

- `static int enginePort`（现由 MainActivity 持久化读写，:2145-2153 附近）；
- `static boolean healthOk(Context)`（现 `:4088`，纯 socket/HTTP 探测——注意不要依赖 Activity）；
- `static void spawnNode(Context ctx, File payload)`（现 `:3914`，需把 `dshrootDir / enginePort / getCacheDir()` 等字段参数化，全部改成从 `Context` 推导）；
- `static boolean engineStoppedByUser`，**读写 SharedPreferences 持久化**。

MainActivity 改为调用 EngineCore；`launchEngine`（`:7941`）保留 UI 编排（进度文案、计时、失败回退），但 spawn/探测走 EngineCore。工作量集中在大文件方法外移，行为不变——先搬不改。

> 路线 2（次选，施工量小但不彻底）：EngineService 起守护线程，发现引擎挂掉 → 发显式 Intent 拉起 MainActivity 处理。缺点：MainActivity 被回收时无人接单，App 冷后台丢守护——**不满足"常驻"目标**，只能算过渡。

B2. **EngineService 内建守护循环**（仿 `HarnessService.java:310-398`，可减配）：

```java
// onCreate / onStartCommand 首启时
Thread t = new Thread(() -> {
    int fail = 0;
    while (!stopRequested) {
        sleep(15_000);                                   // KEEPALIVE_INTERVAL_MS 等价物
        if (EngineCore.isUserStopped(this)) continue;    // 双保险：内存 + prefs
        boolean ok = EngineCore.healthOk(this);
        if (ok) { fail = 0; continue; }
        if (!EngineCore.portListening(enginePort)) {     // 只有"端口没在听"才计 fail
            fail++;
        }
        if (fail >= 3 && SystemClock.uptimeMillis() - lastRestart > 120_000) {
            lastRestart = SystemClock.uptimeMillis();
            fail = 0;
            EngineCore.spawnNode(getApplicationContext(), payload);   // 受控重启
            // 若 MainActivity 存活且在控制台/主界面 → 发本地广播让它刷 UI（可选）
        }
    }
}, "engine-watchdog").start();
```

对齐 DSHA 的三条纪律：
1. **分层**：只在"端口无监听"（≈ DSHA 的 `UNAVAILABLE`）时计数；"进程活着但没监听"等待不重启（woaiys3 的 `portListening` 检查已有，:7941-7985 里那段"第二道防线"逻辑直接复用）；
2. **阈值 + 冷却**：连续 3 次失败 + 120s 冷却（照抄 `HarnessService.java:45-47` 常量）；
3. **用户停止一票否决**：`EngineCore.isUserStopped()` 每次循环重查。

B3. **onStartCommand 重查停止状态**（对齐 `HarnessService.java:131-169` "晚到的 START 意图在交付时重查用户停止状态"）：START_STICKY 重建时若 prefs 里 `userStopped=true` → 立即 `stopSelf()`。

B4. **删掉 MainActivity 的 startWatchdog**（`:4184-4213` 及 `:4219` 调用点），避免两套守护（5s 和 15s）打架。界面文案刷新继续由 `refreshConsole()` 轮询负责，跟守护解耦。

B5. 停止语义打通：`stopKeepAliveService()`（`:2427-2432`）与 `engineStoppedByUser=true` 写入 prefs 绑定（用户点停止时才写；`startEngine` 时清零）。

### 改法 C：WakeLock / WifiLock + Manifest 权限

**文件：`EngineService.java`（加锁）+ `AndroidManifest.xml`（加权限）**

- EngineService.onCreate 里 acquire `PARTIAL_WAKE_LOCK`（tag 如 `"woaiys3:engine"`），onDestroy release；息屏期间不需要全时持有的话，可仿 DSHA `refreshLocks`（`HarnessService.java:219-265`）按屏幕亮灭/省电模式决策——最小版：屏灭时 acquire，屏亮时 release（注册 `ACTION_SCREEN_OFF/ACTION_SCREEN_ON` 广播即可，不需要 PowerPolicy 全家桶）。
- WifiLock（`WIFI_MODE_FULL_HIGH_PERF`）同理，用于 Wi-Fi 下 Web UI 长连接不被切断。
- `AndroidManifest.xml` 加：
  ```xml
  <uses-permission android:name="android.permission.WAKE_LOCK" />
  <!-- ACCESS_WIFI_STATE / CHANGE_WIFI_STATE 若无则补 -->
  ```
- **targetSdk 28 的差异说明**：woaiys3 targetSdk 28，`dataSync` 前台类型不受 Android 14+ 新 FGS 校验约束（DSHA 要写 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 是因为它 targetSdk 37，`build.gradle:23-24`）——**woaiys3 这边不需要动 FGS 类型声明**，这是底座老 SDK 的"红利"。

### 改法 D（可选）：开机自启

只有想要"重启手机 → 通知栏引擎自动恢复"才做（DSHA 自身都没覆盖 Web，属超出对标的增强）：

- `AndroidManifest.xml` 加 `RECEIVE_BOOT_COMPLETED` 权限 + BootReceiver（新文件或复用现有广播组件）：
  ```xml
  <receiver android:name=".BootReceiver" android:exported="false">
      <intent-filter><action android:name="android.intent.action.BOOT_COMPLETED" /></intent-filter>
  </receiver>
  ```
- 接收器逻辑：读 prefs（`setup_done == true && userStopped == false`）→ `startForegroundService(new Intent(ctx, EngineService.class))` + `EngineCore.spawnNode(...)`。**别拉 MainActivity**（Android 10+ 后台启动 Activity 会被拦；Service 不受此限制）。
- 注意各厂商 ROM 的"自启动管理"白名单（纯代码层面绕不开，提示用户即可）。

### 施工注意清单

1. **文件编码**：woaiys3 的 `AndroidManifest.xml` 中文注释在我们的终端按 UTF-8 读是乱码（疑似 GBK 编码存量文件）。改 MainActivity.java / Manifest 前先确认原文件编码（file 命令 / 编辑器右下角），**保持原编码写回**，否则中文字符串全部花掉。
2. **MainActivity.java 是 10847 行大文件**：改法 A 全在既有方法内加钩子（小 diff）；改法 B 的方法外移建议单独一个 commit，先"搬"后"改"，便于回归。
3. **防重复 spawn 不能丢**：现有 `launchEngine` 的双检查（进程句柄 :7941-7985 + `portListening`）是 v1.13 用真机日志（EADDRINUSE 72 次 vs 成功 38 次）换来的，EngineCore 搬迁时原样保留。
4. **回归测试路径**：冷启动直达；控制台停留时按 Home 再回来；`adb shell am kill` 后确认 START_STICKY 重建且不违背 userStopped；用户点「停止引擎」后等 5 分钟确认没有任何自动拉起；90 秒超时场景回控制台（`:4149-4179` 的既有逻辑）。
5. 引擎日志在 `files/dsh-web.log`（`:4149` 附近注释明示），联调排障先看它。

---

## 5. 审计边界（没读到底的部分，诚实交代）

- `core/WebLifecycleController.java`（1116 行）只精读了生命周期门禁、停止哨兵、自动重启三块（:207-226 / :860-958 附近），其余方法（修复/维护任务互相阻塞细节）未逐行走查——与本次三问无关。
- 容器内生成的 `/root/dsh-watchdog.sh` 脚本内容（`WebProcSel.java:112` 引用处定义）未逐行核对，只确认了"存在第二层 bash 看门狗 + 写 PID 文件 + 认哨兵"的结构性证据。
- DSHA 的 UI 细节（WelcomeActivity 分步、WebPreviewActivity 内部实现）只做大纲级确认；与"图标→输入框"主线无关。
- 以上未读部分不影响 §0-§4 的结论方向；若后续施工需要更细的对照，可定点补读。

---

*报告完。审计基线 commit `810fb03`（2026-10-15 克隆）。全部关键结论均已给 `文件:行` 指针，施工前可逐条复核。*
