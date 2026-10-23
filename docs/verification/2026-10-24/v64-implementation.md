# dsh-android v64 implementation and verification record

## Status

Implementation and local APK build are complete on `v64-plugins-and-stability`. The specified phone was not attached to adb (`adb devices -l` was empty; serial `fa15252f` returned `device not found`), so installation and all device acceptance checks below remain **unverified**. No device logs or behavior results were fabricated.

The task-book file remains untouched and untracked as required by its redline. This report carries the requested preflight and pitfall records.

## 二·五 · 开工前五项

1. 坑：把插件放进引擎实际不会解析的目录。避法：先核对现有插件布局和启动加载日志，再按已验证的布局打包。
2. 坑：`dsh-memory` 与已有插件发生歧义或遗漏初始化。避法：只用仓库现成 tgz，核对清单和 `cordis.patch.yml`，启动后检查 `plugins.log`。
3. 坑：语音服务迁到 `:voice` 后跨进程状态假设失效。避法：先做进程隔离，再逐项追踪唤醒状态与录音恢复；不改主引擎文件。
4. 坑：稳定性修复误伤冷启动、保活或通知。避法：每项独立提交，并按任务书复验冷启动、通知、看门狗。
5. 坑：构建或真机验证不完整却误报通过。避法：全程单构建；证据落盘，无法执行的判据明确标为“未验证”。

## Implemented

- **I-1:** Added the contents of `dsh-memory-0.1.1.tgz` to the Android payload in the flat and nested candidate module locations; registered one `dsh-memory` plugin entry. The seven existing plugin entries remain present. A host Node module-resolution smoke check from the Cordis loader resolved the nested candidate and imported `name === "dsh-memory"`. Actual engine loading and `plugins.log` are unverified.
- **I-2:** Did not add `dsh-web-search-free`; retained `web-search-deepseek`.
- **I-3:** Added browser fallback instructions to `dsh-home/AGENTS.md`. The built payload contains the composed `AGENTS.md` at 11,745 bytes. In-chat search behavior is unverified.
- **B-1:** Declared `VoiceService` in process `:voice`; retained the microphone foreground-service type and existing port/protocol. Intentional native-fatal isolation and voice-chain checks are unverified.
- **B-2:** Persisted the wake switch in `$DSH_HOME/voice-wake.state`; service startup reads it and attempts to restore wake monitoring. Restart behavior is unverified.
- **B-3:** Recording now pauses active wake monitoring and restores it when recording ends while the saved wake switch remains enabled. Explicit `voice_wake stop` clears the resume request. The AI recording round trip is unverified.
- **B-4:** `startNotifyServer()` checks `portListening()` before binding. Repeated cold starts and `EADDRINUSE` logs are unverified.
- **Version:** Updated the manifest to `64 / 1.21.8`.

## Local build evidence

- Command: repository `build.local.sh`, run once from Git Bash with `OTEL_TRACES_SAMPLER=always_off` and `MSYS_NO_PATHCONV=1`.
- Result: `BUILD OK`; package metadata reports `versionCode=64`, `versionName=1.21.8`.
- `javac` produced 434 classes; `classes.dex` is 4,897,908 bytes.
- Kernel manifest gate passed with 25,859 rows; the build reported 25,857 kernel files. The build copied the updated `AGENTS.md` at 11,745 bytes.
- `payload.zip`: 150 MiB. APK: 172,334,749 bytes; SHA-256 `85174f4dc3570e2b8fa4ce83a21eb44aec14db729c6bcb3e55f0a425a0bbec39`.
- APK signer certificate SHA-256 `57418c9e3cd6c205fe3a721c7585bbb8c0dde449451360d7e89e7a10fcf9684e`, matching the available 62/63 build logs. This verifies the local signing identity; installing over 63 and preserving app data remain unverified.
- Full build took about 21 minutes. Manifest generation alone reported 298.2 seconds for the 25,857-file tree. Full stdout is in `build-v64.log`.

## Still unverified on device

- Install or cover-install 64 and confirm app data is retained.
- `plugins.log` loading without errors; `memory_*` tools; cross-session memory readback; scheduled “记忆整理” task.
- Model behavior after failed `web_search`, including browser fallback and no unsupported answer.
- `:voice` process PID separation, deliberate native fatal, main-process PID stability, engine port 3080, and voice recognition/TTS regression.
- Wake-switch restoration after app restart and wake restoration after AI recording.
- Three repeated `am start` cold starts with engine startup and no `EADDRINUSE`.
- F1 cold start, F2 notification/state consistency, and F3 watchdog regression.

## 四·五 · 本轮新踩的坑

### 坑：共享工作区在提交间切换了 Git 分支
- **根因**：分支在两次工具调用之间从目标分支回到了 `master`，同时两边都有并行文档提交。
- **解法**：保留并行提交，把 I-1 提交移到 `v64-plugins-and-stability`，并将 `master` 恢复到我提交前的父提交；没有 push 或合并。
- **下次怎么避**：每次提交前检查当前分支和目标分支日志；发现分支移动时先保留两边提交再继续。

### 坑：`devhome/` 被 Git 忽略，常规 diff 看不到 payload 改动
- **根因**：仓库 `.gitignore` 忽略整个 `devhome/`。
- **解法**：只对获准的 Cordis 配置和两个插件目录使用显式强制暂存，并复核暂存文件名；任务书未加入。
- **下次怎么避**：施工前检查忽略规则，提交前逐项核对 staged paths。

### 坑：Windows 的 `bash.exe` 命令实际指向 WSL
- **根因**：PATH 中的 `bash.exe` 是 WindowsApps 的 WSL 入口，和仓库构建脚本预期的 Git Bash 路径格式不同。
- **解法**：改用已安装的 Git Bash 可执行文件，并在每条命令里设置 `MSYS_NO_PATHCONV=1`。
- **下次怎么避**：构建前先核对 `uname`、`cygpath` 和实际 Bash 路径。

### 坑：payload 文件数让一次本地构建耗时超过 15 分钟
- **根因**：构建会扫描和压缩约 25,000 个内核文件；Windows 文件树的逐文件处理耗时明显。
- **解法**：保持单构建，等待 manifest、目录统计、payload 压缩和 APK 各闸门完成。
- **下次怎么避**：为完整构建预留约 25 分钟，并持续检查同一进程的日志，不并发启动第二个构建。
