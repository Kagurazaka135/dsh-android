# v64 真机验收（2026-10-24 15:00 左右）

设备：Xiaomi 2206122SC / Android 15 / `fa15252f`
装机：63 / 1.21.7 → 覆盖安装 **64 / 1.21.8**

---

## ✅ 通过

### B-1 语音进程隔离（**核心验收，通过**）

安装后冷启，两个独立进程：

```
pid=4344  proc=com.deepseek.harness:voice
pid=12317 proc=com.deepseek.harness
```

（63 版只有 1 个进程 `12900 com.deepseek.harness`，对比明确。）

**故意造崩溃**：`am crash <voice-pid>`（adb 无 root、`kill` 被 SELinux 挡，改用 `am crash`）

```
前: voice=4344  main=12317
后: voice=11391 main=12317
```

- 语音进程 4344 **崩溃并自动重启**为 11391
- 主进程 12317 **一动没动**
- 崩溃栈在 `logcat -b crash` 里（`AndroidRuntime ... ActivityThread.main`）

**端口复验（崩溃后）**：
- 引擎 3080 → `401`（活着，401 是没带 token 的正常响应）
- 语音 3280 → `200`

⇒ **判定：`android:process=":voice"` 生效，原生/运行时崩溃不再连累主进程与引擎。这一条正是 B-1 存在的理由，已达成。**

### B-4 EADDRINUSE 日志噪音（通过）

连续 3 次 `am force-stop` + 冷启：

- `logcat | grep -c EADDRINUSE` → **0**
- 每次都能起，进程始终双进程

⇒ **判定：bind 前的 `portListening` 检查有效。**

### 包内容（拆 APK 验证，不依赖手机）

| 项 | 结果 |
|---|---|
| Manifest `.VoiceService` 后跟 `:voice` | ✅ |
| `payload.zip` 内 `dsh-memory` 条目 | 72 个（平铺 / 嵌套各 36） |
| `dshhome/AGENTS.md` | 11,745 字节，含 `cn.bing.com` 与「不许跳过」 |
| 7 个既有插件 | 一个没少 |
| APK | 172,334,749 字节，`versionCode=64`，签名摘要与 63 一致 |

---

## ❌ 不通过

### I-1 dsh-memory **加载失败（每次启动都失败）**

`/sdcard/DeepSeekHarness/dsh-web.log`，**每一次**冷启都出现同样三行：

```
dsh: warning: 1 entry did not activate
dsh-memory (dsh-memory): failed to import
```

- 连跑 3 次冷启，失败 3 次，**不是偶发**
- 引擎其余部分正常（web 服务照常起、端口照常开）
- 日志只有 `failed to import` 这一句，**没有堆栈**，`$DSH_HOME/logs/plugins.log` 在 App 私有目录，无 root 读不到

**PC 侧复现线索**（不能直接当结论）：

```
cd dshroot/lib && node -e "import('dsh-memory')"
  → FAIL: ERR_MODULE_NOT_FOUND
    Cannot find package '@deepseek-ai/dsh-tools'
    imported from .../node_modules/dsh-memory/lib/tools.js

cd dshroot/lib/node_modules/@deepseek-ai/dsh && node -e "import('dsh-memory')"
  → OK, name=dsh-memory
```

即：**平铺那份**（`dshroot/lib/node_modules/dsh-memory`）解析不到 `@deepseek-ai/dsh-tools`（该包只存在于嵌套位置 `dsh/node_modules/@deepseek-ai/dsh-tools`）；**嵌套那份**可以正常 import。

**但这条不能直接定死根因**，因为：
`dsh-tool-accessibility` 同样是「平铺 + 嵌套双份」，却工作正常 —— 说明加载器大概率解析的是嵌套那份。若如此，dsh-memory 的失败可能是另一回事（例如设备侧 payload 抽取不完整、权限、或插件自身 peer 依赖检查）。

**下一步（二选一）**：
1. 删掉平铺那份 `dshroot/lib/node_modules/dsh-memory`，重编装包 → 让 dsh-memory 与其它插件的布局完全一致，再看 `failed to import` 是否消失。**这是最可能命中的一次实验。**
2. 想办法拿到真实堆栈（需要 App 私有目录读权限 → root/Shizuku，或让 dsh 把插件加载错误打到 `/sdcard` 的 dsh-web.log）。

---

## ⚠️ 未验证

以下项本轮**没做**，别当通过：

- **B-2 唤醒开关持久**：需要先在悬浮窗手动开一次唤醒生成 `voice-wake.state`，本轮没做成（UI 输入被讯飞 IME 联想干扰、屏幕锁屏反复熄灭）
- **B-3 录音后恢复唤醒**：同上，需要真实录音流程
- **I-3 搜索降级行为**：需要让模型实际搜一次失败的东西，观察是否换浏览器 —— 本轮没做
- F1 冷启落点 / F2 通知一致性 / F3 看门狗：63 已验过，64 本轮没重跑
- `pm clear` 全新装、真人喊唤醒词、小米助理列表（后者已另验：看不到）

---

## 设备侧改动（已还原）

| 项 | 处置 |
|---|---|
| 锁屏 | 曾 `locksettings set-disabled true` 用于自动化，**已恢复 false** |
| 端口转发 `tcp:18080` | 已移除 |
| 推送的截图 | 落在 `/sdcard/wk.png`，未删（无害） |
| 安装 | 64 覆盖安装，**数据保留**（版本号变了，模型/凭证仍在，引擎正常起） |
