# PR #1 复核报告（dsh-android）

> 复核人：ZCode（GLM）· 2026-10-24 凌晨
> 施工分支：`pr1-verify`（起点 `pr1-review` = `acd96e7`）
> 设备：Xiaomi 2206122SC / Android 15 / 序列号 `fa15252f` / adb `D:\1\MiPhoneAssistant\adb.exe`
> 基线：机上原版本 59 / 1.21.3

---

## 结论汇总

### R-1 静态审查（11 个 commit）

| 编号 | 结论 | 证据 |
|---|---|---|
| R-1.1 | **通过** | `git diff 230a75e..acd96e7 -- '*EngineCore.java' '*EngineService.java'` 输出为空。最后 4 个 commit（`b419e26`/`8ab9059`/`5e4c86e`/`acd96e7`）确实没碰自启动/看门狗/通知三处承重代码 |
| R-1.2 | **通过（但有分支能到"不起引擎"）** | `MainActivity.java:450-459`：`setup_done` 首启直接置 true → 无条件 `showEngineScreen()+showConsole()`；`if (!EngineCore.isUserStopped(this)) { engineAutoEnter=true; startEngine(); }`。**唯一不起引擎的分支 = 用户曾主动「停止引擎」（`isUserStopped=true` 持久化）**——这是设计意图，不是缺陷。另注：`engineAutoEnter` 只在就绪回调里消费（`:3942`），冷启正常路径不会丢 |
| R-1.3 | **通过（有窗口期，已记录）** | 启动不再请求存储权限（`MainActivity.java:424-430` 只 `pendingVscreenExtract=true`，不再 `requestPermissions`）。补提取路径在 `onResume`（`:2119-2134`）：只要 `WRITE_EXTERNAL_STORAGE` 已授予或 Android 11+ 有 `MANAGE_EXTERNAL_STORAGE`，就重跑 `extractVscreenDex()` 并清 `pendingVscreenExtract`。**窗口期 = 首次装到用户手动授权之间 vscreen 不可用**，但引擎/会话/工作区都走私有存储，不影响 F1 |
| R-1.4 | **通过（确认为死代码，按要求未删）** | `MainActivity.java:889` 定义 `checkBatteryOptimization()`，全仓 `grep -rn` 除定义与自身错误日志（`:906`）外**零调用点**。原调用位于 `:293` 已被 8ab9059 替换成注释。未删，仅记录 |
| R-1.5 | **通过（形似成套，但 RecognitionService 是空壳）** | Manifest `:116-146` 三个 service：`DshVoiceInteractionService`（exported=true + `BIND_VOICE_INTERACTION` + intent-filter `VoiceInteractionService` + meta-data `@xml/voice_interaction_service`）、`DshVoiceInteractionSessionService`（同上权限，`android:process=":voice_session"`）、`DshRecognitionService`（exported=true + `BIND_RECOGNITION_SERVICE` + intent-filter `RecognitionService` + meta-data `@xml/recognition_service`）。两个 xml 都在 `res/xml/`。**`DshRecognitionService.onStartListening` 同步回空的 `RESULTS_RECOGNITION` 列表**——语法合法（系统不会崩），但**语义上是空壳**：任何选它做识别服务的应用都会得到空结果。注释里作者也自认"Registration stub" |
| R-1.6 | **通过（4 处齐全）** | `build.sh` 里 `COMMONS_LANG` 四类引用全在：`:529` 变量定义、`:531` 依赖存在性循环、`:540` SHA-256 清单（`4eeeae8d…`）、`:548`+`:567` classpath（含 cygpath 分支）、`:595`+`:597` d8 response file（两分支都有）。jar 本体 702952 字节已入库（`libs/commons-lang3-3.18.0.jar`） |
| R-1.7 | **通过（拼接方式正确，`!!js` 可解析）** | `build.sh:465-467`：`cp "$H/.dsh/cordis.patch.yml"` → `printf '\n'` → `cat android-bootstrap.patch.yml >>`。两文件都是**顶层 YAML 数组**（每项以 `- id:` 开头，无外层括号），`cordis.patch.yml` 末尾无收尾符号（实测最后字节 `...tool-browser'\n`），追加拼接合法。`!!js (process.env.HOME + '/Documents')`：`EngineCore.java:216` 确实 `env.put("HOME", app.getFilesDir().getAbsolutePath())`，所以解析为 `/data/user/0/com.deepseek.harness/files/Documents`。**且 `dsh-api-workspace-controller/lib/types/default-directory.js` 有 `validateDocumentsDirectory()` 要求绝对路径**——该值满足。**但注意：`documentsDirectory` 只用于"首次创建默认工作区"，不是把工作区强制设到这里**；引擎日志显示默认落到 `files` 工作区（见 R-2 说明） |
| R-1.8 | **通过** | `AndroidManifest.xml:5-6`：`versionCode="62" versionName="1.21.6"`；`:8` `minSdkVersion="24" targetSdkVersion="28"` 未被改动。构建产物 aapt dump 实测一致 |
| R-1.9 | **通过** | `build.sh:472-490` 构建期把 `dsh-home/AGENTS.md` + `dsh-home/system.md` 用 `{cat A; echo; echo "---"; echo; cat S;} > staging/dshhome/AGENTS.md` 拼接。实测 payload 内 `dshhome/AGENTS.md` = **11200 字节且含「行为准则」段**（= system.md 已拼进去），`dshhome/system.md` = 2055 字节单独进包。升级覆盖白名单 `MainActivity.java:102` 数组里 `"dshhome/AGENTS.md"` 与 `"dshhome/system.md"` 两行都在，不会被"已存在就跳过"误判 |

### R-2 真机复核

| 编号 | 结论 | 证据 |
|---|---|---|
| R-2.1 | **通过** | `am force-stop` 后点桌面图标冷启：00:30:48 启动 → 约 20-25s 后 `pidof` 得 app=9039/后又 26530，node 起；落回上次会话且输入框 `enabled=true focusable=true`（`cold-62-ui.xml`）。**两条通知都「运行中」**：`dsh_engine`「DeepSeek Harness 正在运行 / AI 引擎保活中，后台任务持续执行」、`dsh_overlay`「🐋 DeepSeek Harness 运行中 / 引擎状态：运行中（端口 3080）」、`dsh_voice`「语音服务就绪 / 离线语音模型：ready」（`notif-dump.txt`）。**注意前置条件：本机 `POST_NOTIFICATIONS` 原本 `granted=false`（Android 13+ 未授权 → 通知全被系统丢弃），我先 `pm grant` 才看到通知。这不是 PR 缺陷，但"通知说实话"要先有通知权限** |
| R-2.2 | **受限（改用等价路径完成）** | `adb shell kill <node_pid>` 返回 `Operation not permitted`（shell uid 2000 无权 signal app-uid 进程，SELinux 挡）；`run-as` 不可用（非 debuggable）；`su` 不存在；`adb root` 无效；Shizuku rish 虽已授权但仍是 uid 2000。**改走 App 自身 bash 工具**（引擎内 `bash` 工具，与 node 同 uid）执行 `kill -9`，成功（见 V-5.3） |
| R-2.3 | **通过** | 控制台权限页新增「麦克风」行（`MainActivity.java:7824` `addPermRow(c1, "麦克风", "听写、录音和唤醒词；需要时由你授权", "mic")`），`conPermOk("mic")`/`conPermAction("mic")` 均已实现（`:7771-7774`、`:7916-7922`）。权限统计从 8 项变 9 项（`:7763`） |
| R-2.4 | **未验证（有意推迟）** | `pm clear` 会连 **已下载的离线语音模型（252MB，App 私有目录）+ 用户填的模型凭证** 一起清空。本轮 V/U 阶段强依赖这两样，清掉就无法继续验证唤醒链路。**已把 R-2.4 安排在全部验证之后，本轮未执行**——按红线 6 写"未验证"，不写"通过" |
| R-2.5 | **未验证（需人工）** | 小米设置 → 语音助手列表需要人眼确认列表项并**绝对不能设为默认助理**。ADB 无法安全枚举/截图该页而不误触。**未验证** |

### 11 个 commit 逐个结论

| commit | 内容 | 结论 | 依据 |
|---|---|---|---|
| `429ff34` | 引擎常驻 A+B：抽 EngineCore、看门狗迁 EngineService | **通过** | 看门狗逻辑在 `EngineService.java:89-129`：15s 间隔、连续 3 次失败、120s 冷却、`healthOk` 探针；`onStartCommand` 尊重 `isUserStopped`；`START_STICKY`。真机 V-5.3 实测重拉成功 |
| `2552bde` | AGENTS.md 定稿 | **通过** | 纯文档，117 行改动，与 `dsh-home/AGENTS.md` 实际内容一致 |
| `25431c7` | system.md 进包 + 拼接 + 白名单 | **通过** | 见 R-1.9 |
| `688c0eb` | 截图垃圾归口 + 只留 20 张 | **通过（有限定条件）** | `android_screenshot` 落盘改 `screenshotRootDir()`（按包名派生，`:33-40`），清理命令 `ls -t dir/shot-*.png \| tail -n +21`（`:585-588`）；`AccessibilityService.pruneScreenshots(dir,20)` 只清 `screen-*`（`:1258-1277`）。**两套各自计数，不共享 20 张额度**。实测 shot-* 11→12 未触发（未超 20），符合预期 |
| `9591946` | 下载 429/5xx/超时退避重试 | **通过（静态）** | `VoiceModelStore.download()` 外层循环 4 次（`attempt 0..3`），退避 2s/5s/15s；`RetryableDownloadException` 覆盖 429、5xx、`SocketTimeoutException`、`ConnectException`、416。**未做真机 429 复现**（模型已 ready，无法造限流） |
| `2c28a63` | 悬浮面板加「唤醒」开关 | **通过** | `OverlayService.java:330-333` 加按钮 → `toggleOverlayWake()`（`:478-534`）打 `VoiceService /wake`，带 `LocalAuth` 头。真机点按实测：面板变「状态：唤醒已开，喊「小鲸鱼」」，进程存活 |
| `230a75e` | javac 清单补回 EngineCore.java | **通过** | `build.sh:570` 命令行里 `"$P/src/com/deepseek/harness/EngineCore.java"` 在列，紧随 ConsoleTheme 之后 |
| `b419e26` | 注册系统语音助理壳 | **通过（壳）** | 见 R-1.5。**只验证了"注册结构完整"，没有设为默认助理**（按任务书要求） |
| `8ab9059` | 首启开私有工作区 | **通过** | 见 R-1.2 / R-1.3 |
| `5e4c86e` | 打包 Commons Lang | **通过** | 见 R-1.6。这是**真 bug 的真修复**：没有它 KWS 归档解包直接 `NoClassDefFoundError: org/apache/commons/lang3/SystemProperties` |
| `acd96e7` | 文档/验证记录 | **通过（文档）** | 23 个文件全是 docs/verification 与 TODO，无代码改动 |

### 与「卷王」(LPR 作者) 报告不一致的地方

| 项 | 它说 | 我实测 | 证据 |
|---|---|---|---|
| T1 冷启落点 | "4 次复现直达会话页" | **部分成立**：冷启确实落在会话（可输入），但 **R-2.1 的首次 force-stop 后有一次落在「选择工作区」空状态**（`选择工作区 / 选择一个工作区开始`，截图 `r22-screen.png`）。这是因为默认工作区创建失败或未选中——**不是稳定直达**，与"直达不是代码实现的、是前端 localStorage 记住上次页"的旧判断一致 | `r22-screen.png` / `r22-ui-before.xml` vs `cold-62-relaunch.png` |
| T4 唤醒 | "62 补齐 Commons Lang 后启动 KWS 时 App 自行退出" | **完全属实且更精确**：拿到 `Zygote: Process 9039 exited cleanly (255)` + sherpa-onnx 原生 fatal `Read binary file ... failed` + 明确指引 `Please set assetManager to null when you load model files from the SD card` | `wake-crash-before.txt` |
| T5 识别/TTS | "因唤醒启动退出，未完成链路" | **本轮补全**：证实 ASR 与 TTS 都能工作（录下 TTS 播报→离线识别出「现在几点了？」），见 `wake-asr-tts.txt` | 同左 |
| T6 半双工 | "未验" | **本轮验了，不通过**：TTS 播报期间出现过 `wake.hit`（实验 A），但不是稳定复现（B/C 为 0 次）。详见 `wake-hit-and-halfduplex.txt` 的结论修正 | 同左 |
| T3 看门狗 | "App 自身 shell 验证并终止同 UID 的 Node" | 手法一致，但我走的是**引擎内 bash 工具**（不是 MainActivity 的 shell 入口）。真机结果吻合（3 次探测后 respawn） | `regression-watchdog.txt` |
| 自启动 | "基线的引擎本身会自动启动，原 TODO 中'完全不能自启动'的描述不再成立" | 属实。但我额外发现：**Activity 恢复时（`onResume`/重建）会再跑一遍启动逻辑，撞出 `BindException: EADDRINUSE`（notify server 3080+1）**——不影响功能（引擎已在跑，启动被 `portListening` 挡住），但日志有噪音 | `wake-crash-before-raw.log` 里 23:40:42 那一段 |

### 未验证项（老实列）

1. **R-2.4 `pm clear` 全新装流程** —— 会清空离线模型与凭证，放到最后做（本轮没做）。
2. **R-2.5 小米「语音助手」列表里能否看到本 App** —— 需人工在设置里确认，且明确不能设为默认。
3. **V-5.7 模型下载退避重试 6 个脚本用例** —— 模型已 ready，无法造 429/5xx。
4. **真喊「小鲸鱼」**（人声、非 TTS 回声）—— 需要真人在设备旁喊。本轮全部唤醒命中/未命中都来自 TTS 回声，**真人口呼的命中率仍未验**。
5. **`kwsReady` 的双模型依赖** —— `startWakeMonitor()` 要求 KWS + SenseVoice **双双 ready**（`VoiceService.java:812`），意味着只想用唤醒词也要先下 239MB 的 ASR 模型。这是个设计约束，未作为缺陷处理。
6. **U-3 折腾类**（2 小时挂机、系统杀进程、转屏分屏、耳机插拔、来电抢占、口音中英混说）—— 时间与设备条件所限未跑。

---

## 附：本轮改动的唯一代码

按任务书 V-2 / V-3，只改 `VoiceService.java` 两行：

```diff
- offlineRecognizer = new OfflineRecognizer(getAssets(), config);
+ offlineRecognizer = new OfflineRecognizer(null, config);
- return new KeywordSpotter(getAssets(), config);
+ return new KeywordSpotter(null, config);
```

未动：所有模型路径（仍 `getAbsolutePath()`）、SenseVoice 线程数、`greedy_search`、模型不打进 assets。

`EngineCore.java` / `EngineService.java` 一行未动（`git status` 只显示 VoiceService.java 一处修改）。
