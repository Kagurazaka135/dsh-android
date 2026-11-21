# Prompt · 用 sysapps 数据库查系统应用权限（不刷机不解包）

> 直接复制下面整段给 ZCode

---

## 你能跳过刷机包了

`sysapps.bayton.org` 是一个**公开的系统应用数据库**，数据来自用户设备贡献。**不需要下载 ROM、不需要解包、不需要 apktool**，直接看到：

- 每个系统包声明的**完整权限清单**（含保护级别）
- 签名证书分析（判断是不是 OEM 预装）
- 版本、targetSdk、APK 大小
- 跨设备对比

**已实测可用**（2026-11-21）：
`https://sysapps.bayton.org/packages/com.coloros.assistantscreen` → 返回 OPPO 小布助手的 49 个权限

## 怎么查

```bash
# 单个包
curl -sL "https://sysapps.bayton.org/packages/<包名>"

# 看某 OEM 的收录情况
curl -sL "https://sysapps.bayton.org/oems"
```

需要带代理：`-x http://127.0.0.1:10809`

## 要查的包名（逐个试，未收录就跳过）

**OPPO / ColorOS**
```
com.coloros.assistantscreen
com.oplus.aiunit
com.oplus.aiassistant
com.coloros.assistant
```

**vivo / OriginOS**
```
com.vivo.ai.engine
com.vivo.assistant
com.vivo.voiceassist
com.vivo.abe
```

**小米 / HyperOS**
```
com.miui.voiceassist
com.xiaomi.aiasst.service
com.xiaomi.aiasst.vision
com.miui.assistant
```

**三星**（收录最多，53 台设备）
```
com.samsung.android.bixby.agent
com.samsung.android.bixby.wakeup
com.samsung.android.app.settings.bixby
```

## 每个包要提取的信息

1. **权限总数 + 分级**（运行时 / 签名级 / 安装时 / 厂商）
2. ⭐ **签名级或系统级的那几条是什么**——这是关键
3. 有没有 `WRITE_SECURE_SETTINGS`、`INJECT_EVENTS`、`CAPTURE_AUDIO_HOTWORD`
4. 签名证书是不是 OEM 自己的
5. targetSdk、APK 大小

## 已经查到的例子

`com.coloros.assistantscreen`（OPPO 小布）：

```
49 个权限：4 运行时 + 2 签名/系统级 + 8 安装时 + 35 厂商/未编目
签名证书一致（OPPO 自家 = OEM 预装）
APK 52.9 MB，target SDK 36
```

⚠️ 但要注意：**`assistantscreen` 可能只是界面层**，真正执行的组件可能在别的包里。查完要判断「这个包是不是干活的那些」。

## 局限（先知道，别白费功夫）

| OEM | 收录设备数 | 最后同步 |
|---|---|---|
| Samsung | 53 | 2026-11-20 ✅ |
| Google | 20 | 2026-11-20 ✅ |
| Xiaomi | 8 | **2026-03-20（过期）** |
| OPPO | **2** | 2026-02-02 |
| vivo | **1** | 2026-07-21 |
| realme | 3 | 2025-11-25 |

**国产厂商收录很少。** 查不到就是查不到，别硬挖。

## 查不到怎么办

按这个顺序降级，**别一上来就找 18GB 的 ROM**：

1. **sysapps 查到了** → 直接用，标注来源
2. **查不到** → 试 `apkmirror.com` 的单包（已验可访问）
3. **还没有** → 才考虑 ROM 路线：
   - **realme**：`realmeupdater.com`（自述官方原包）+ `unpackandroidrom` 工具解 ozip
   - **小米**：`xiaomirom.com` 免登录
   - **三星**：SamFW / APKMirror 的 Bixby Wakeup 单包
   - **vivo**：官网 `vivo.com/upgrade/index`（已验 200）
   - **OPPO**：⚠️ **新机固件不公开**，只有 PC 助手 OTA 和加密 `.ozip`。走不通就如实写

## 报告里要写清楚

每个结论标来源：

- `sysapps`（第三方数据库，含用户贡献数据，**不是官方**）
- `APKMirror`（第三方镜像）
- ROM 提取（一手，但来源和合规性另说）

**`sysapps` 的数据是「某台设备上的观测」，不是「官方声明」**——可能不全、可能过期。要标注这一点。

## 一句话

**先查数据库，再想刷机。** 能 30 秒看完全部权限清单的事，不值得花 3 小时下 18GB。
