# dsh-android

> 把 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（dsh）装进安卓手机 —— **能喊一声的 AI 助手 + 真正会操作手机的 agent + 跨会话记忆**。

在手机上跑完整的 dsh 内核，原生侧补上语音与设备控制，**万物皆插件**（PC / 手机同一份包）。

---

## 能做什么

| | |
|---|---|
| 🎙️ **语音助手** | 喊一声唤醒词唤起、**离线**语音识别（SenseVoice）、语音播报、常驻浮标 |
| 📱 **手机 agent** | 让 AI 真正操作手机：点屏 / 读屏 / 起应用 / 截图 / 特权 shell（Shizuku 免 root） |
| 🧠 **分层记忆** | 跨会话的 L3 版本化事实 + L4 启动摘要 + 夜间自动整理 |
| 🔌 **插件生态** | 完全复用 dsh 插件规范，官方生态白送；手机能力也做成标准 dsh 插件 |

---

## 快速开始

**只想用** → 到 [Releases](../../releases) 下 `DeepSeekHarness-*.apk`，安装即用：

1. 装 APK（Android 8+ / arm64）
2. 首次启动按引导给权限（**麦克风 / 无障碍 / 悬浮窗**等）
3. 首次启动要解压内置运行时，**等几分钟、别息屏**
4. 填 API Key（或指向你自己的本地代理），进会话即可说话

**想自己构建** → 见 [从源码构建](#从源码构建)。

---

## 架构

```
┌─────────────────────────────────────────────────┐
│ dsh 插件层（Node，PC/手机通用）                   │
│   语音插件 · 记忆插件 · 官方生态插件              │
├─────────────────────────────────────────────────┤
│ 桥（本地 HTTP + token，一行 JSON）               │
├─────────────────────────────────────────────────┤
│ 原生层（Android / Java）                          │
│   VoiceService（麦克风/TTS/唤醒）· 无障碍 · 虚拟屏 │
├─────────────────────────────────────────────────┤
│ dsh 本体（薄内核，内置 arm64 node 运行时）        │
└─────────────────────────────────────────────────┘
```

**隔离点 = 桥那条线**：插件层（编排）PC / 手机通用；桥往下的原生能力平台相关。

---

## 目录

| 路径 | 是什么 |
|---|---|
| `woaiys3-app/` | 安卓工程本体（fork 自 [woaiys3/deepseek-harness-android-app](https://github.com/woaiys3/deepseek-harness-android-app)） |
| `woaiys3-app/android-app/src/` | **本项目的原生改动**（`VoiceService.java` / `VoiceModelStore.java` 等） |
| `woaiys3-app/plugins/dsh-tool-voice/` | **语音插件**（8 个 AI 工具） |
| `memory/` | **记忆插件**（独立成库：[dsh-memory](https://github.com/Kagurazaka135/dsh-memory) 的一份副本） |
| `docs/` | 设计 / 磁盘限制 / 语音模块说明 |
| `scripts/` | 构建辅助（挖 devhome 等） |
| `build.sh` | 一键构建入口 |

---

## 从源码构建

### 1. 挖 devhome（构建所需的 arm64 node 运行时 + dsh 内核）

本仓库只收源码，**不含**那两坨二进制（~500MB）——它们打在上游 release APK 里：

```bash
bash scripts/fetch-devhome.sh
```

### 2. 生成签名密钥

```bash
keytool -genkeypair -keystore woaiys3-app/android-app/release.jks \
  -alias dsh -keyalg RSA -keysize 2048 -validity 10950
```

### 3. 构建

```bash
KEYSTORE_PASS=<你设的口令> bash build.sh
# 产物：woaiys3-app/android-app/DeepSeekHarness.apk
```

> 工具链（aapt / d8 / zipalign / apksigner / javac / android.jar）路径在
> `devhome/payload/build/env.sh`，按需改。

---

## 组件

### 语音模块（`dsh-tool-voice`）

- **唤醒**：sherpa-onnx zipformer KWS，唤醒词**「小鲸鱼」**，常驻监听
- **识别**：sherpa-onnx **SenseVoice**（中/英/日/韩/粤），**离线**，模型首启下载
- **播报**：系统 TTS，含「半双工闸」（播报时关麦、丢弃残余音频，防自问自答）
- **桥**：本地 HTTP（`enginePort+200`）+ token 鉴权

### 记忆插件（`memory/`）

- **L3 版本化事实**（只追加不覆盖） + **L4 启动摘要**（O(1) 读）
- **夜间整理**：走 dsh 原生 schedule，插件幂等自动注册
- 零运行时依赖；模型走本地代理（代码零真 Key）
- 独立仓库：[dsh-memory](https://github.com/Kagurazaka135/dsh-memory)

---

## 已知边界

- **还在开发中**：核心的唤醒 / 离线识别 / 浮标已实现并构建出 APK，但**真机端到端尚未完整验收**（首启模型下载、真实识别、唤醒效果待真机确认）。
- **权限是"全放权"设计**：语音要麦克风常驻；手机 agent 能读屏、点屏、起应用。**建议装在备用机上**，别装主力机。
- **模型调用**：本项目示例走**本地凭证代理**（`http://127.0.0.1:9999` + 假 Key），**代码里不含任何真 Key**。

---

## 致谢

- [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) —— dsh 本体（MIT）
- [woaiys3/deepseek-harness-android-app](https://github.com/woaiys3/deepseek-harness-android-app) —— 安卓工程底座（MIT）
- [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) —— 语音引擎（Apache-2.0）
- [yuloong07-star/Luwi](https://github.com/yuloong07-star/Luwi) / [Miuzarte/LittleWhale](https://github.com/Miuzarte/LittleWhale) —— 语音层设计参考（Apache-2.0）

## 许可

MIT（与上游底座一致）。
