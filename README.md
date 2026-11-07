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

> **Releases 里只有带确认的那个版本**（`confirm`）。它会在动你手机敏感的东西之前先问你——
> 微信里替你打字、读剪贴板、改装好的 APK 会弹原生确认框；
> 支付宝读屏、直接拨号、改无障碍授权这类直接拒绝。
>
> **想要不带这些确认的版本？只能自己从源码构建**：见[下面](#想要没有确认框的版本)。
> 没把它放进 Releases 不是忘了，是故意的——理由也写在同一节。

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

### 4. 两个权限 flavor（`confirm` / `plain`）

同一份代码能编出两个包，**差异在构建期定死，装好之后切不了**：

```bash
bash build.sh                      # 默认：confirm（带确认），Releases 只发这个
BUILD_FLAVOR=plain bash build.sh   # 全放行（与加入权限层之前的行为完全一致）
```

| | `confirm`（默认） | `plain` |
|---|---|---|
| 读屏 / 截图 / 点击 / 滑动 / 起应用（普通 App） | 直接放行 | 直接放行 |
| 通讯类 App（微信 / QQ）里输入文字 | **弹窗，原样展示待输入的文字** | 直接放行 |
| 金融类 App（支付宝等）读屏 / 点击 / 输入 | 拒绝 | 直接放行 |
| 改"影响体验"的设置（输入法 / 字体 / 桌面布局） | 弹窗 | 直接放行 |
| 改无障碍授权 / 设备管理 / 卸载 / 直接拨号 / `curl \| bash` | 结构性拒绝 | 行为同旧版 |
| 语音通道（唤醒词触发） | 不执行设备动作，只聊天 | 行为同旧版 |

两个包**包名与签名相同**，`pm install -r` 可以互相覆盖（数据与权限保留）。

**`confirm` 包是 fail-closed 的**：权限规则模块加载或执行失败、读不到前台应用包名、
guard 抛异常，一律按拒绝处理，不会悄悄退回全放行。换句话说，它坏掉的时候是"什么都不做"，
不是"什么都放行"。

---

## 想要没有确认框的版本

**Releases 里没有它，只能自己编**：

```bash
git clone https://github.com/Kagurazaka135/dsh-android.git
cd dsh-android
bash scripts/fetch-devhome.sh          # 挖 ~500MB 运行时（只需一次）
BUILD_FLAVOR=plain bash build.sh       # 约 15–20 分钟
# 产物：woaiys3-app/android-app/DeepSeekHarness.apk
```

编出来的包与 Releases 那个**签名相同、包名相同**，可以直接 `pm install -r` 覆盖安装，
数据和权限都保留。

### 为什么不做成 Releases 里的第二个包

因为**这里需要一个取舍，而取舍的判据不是"用户方便"**。

这个 App 让模型真正操作手机：点屏、读屏、装应用、改系统设置。2026-10-26 出过一次事——
模型自己把方向锁定关了、把输入法从讯飞切成了谷歌，用户没让它做任何一件。
所以给下载量最大的一档加了一层确认。

那为什么不做个"关闭确认"的按钮放在设置里？**因为那个按钮模型也能按。**
开关只要能由模型触发，需要保护的人会被它关掉，不需要的人会被它打开——两头都不对。

所以这个选择被放到了**构建**这一步：愿意花半小时配工具链、能自己读代码的人，
才拿得到不设防的版本。这不是安全边界（源码是公开的，谁都能编），
**是一道麻烦**——而这道麻烦正好筛掉了绝大多数不该拿它的人。

> 顺手说清楚：`plain` 和 `confirm` 在同一个 commit 上，编出来的东西除了权限层没有区别。
> 公开源码里本来就有 `plain` 的全部代码，藏是藏不住的，也没打算藏。

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

### 权限层（`guard/` + `flavor/`）

- **两个 flavor**：`confirm`（默认，带确认）/ `plain`（全放行，只能源码构建）
- **判定在原生侧（dex）**：不在 Node 插件里 —— 插件在磁盘上、模型能改；dex 改不了
- **判据是编进包的常量**（`BuildInfo.FLAVOR`），不是运行时探测：类加载失败不会退化成全放行
- **动作 × 前台 App**：前台包名用系统读到的（无障碍事件），不用模型自述
- **语音通道标签由原生进程生成**：唤醒命中 / 指令识别都发生在 `VoiceService`，Node 无法伪造
- **确认框是原生悬浮窗**：`FLAG_SECURE`（AI 截图拍不到）+ 原样展示将输入 / 将执行的文本
- 设计推导见 [docs/权限.md](docs/权限.md)

---

## 状态与已知问题

**🚧 早期开发中 —— 能装能用，但离"顺手"还远。**

- **语音唤醒在真机上不稳定** —— 装了也常叫不出来，正在调。
- **还不是系统级语音助手** —— 进不了手机的「默认语音助手」列表，唤起只靠应用自己的浮标。
- **很多手机操作还在走 UI 自动化** —— 不如直接调系统 API 可靠，这块要重做。
- **权限层已内建（`confirm` 构建）** —— 语音要麦克风常驻；手机 agent 能读屏、点屏、起应用。
  `confirm` 包对通讯类输入、剪贴板读取、影响体验的设置、装 APK 会弹原生确认框，对金融类读屏、
  直接拨号、改无障碍授权等结构性拒绝。**`plain` 构建仍是全放行**，装之前先确认自己编的是哪个。
- **语音通道有一处已知漏判** —— 「语音只能聊天、不操作手机」这条，理论上模型可以调
  `voice_wake(stop)` 自己把语音会话关掉，从而绕过。已知、未修（见 TODO.md 的 P0）。
  真实损害范围还没实测过，保守看待这条限制。
- **模型调用**：示例走本地凭证代理（`http://127.0.0.1:9999` + 假 Key），**代码里不含任何真 Key**。

完整的问题清单与下一步计划 → **[ROADMAP.md](ROADMAP.md)**

---

## 致谢

- [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) —— dsh 本体（MIT）
- [woaiys3/deepseek-harness-android-app](https://github.com/woaiys3/deepseek-harness-android-app) —— 安卓工程底座（MIT）
- [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) —— 语音引擎（Apache-2.0）
- [yuloong07-star/Luwi](https://github.com/yuloong07-star/Luwi) / [Miuzarte/LittleWhale](https://github.com/Miuzarte/LittleWhale) —— 语音层设计参考（Apache-2.0）

## 许可

MIT（与上游底座一致）。
