# dsh-tool-webread

给 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（dsh）加一个**无头上网阅读**能力：把网页正文取回来，不弹界面、不留页签。

不打包浏览器内核，也不要求装任何额外依赖——用的是手机上本来就有的系统 WebView。

---

## 当前状态

| 环境 | 状态 | 说明 |
|---|---|---|
| **dsh 0.2.x + 安卓**（小米 2206122SC / HyperOS / Android 15 / SDK 35，DSH 0.2.0-rc.2 / Node v26.4.0 / WebView Chromium 156） | **已实测可用** | 自检 30/30 通过；native 通道实读 example.com / baidu.com / qq.com；热加载生效，未重启引擎 |
| dsh 0.1.x（旧安装树） | 未实测 | 本仓 `install.mjs` 面向这一代，见[安装](#安装) |
| PC 版 dsh | 可用但只有降级档 | 没有 App 的 `/browser` 桥，native 不可用，只走原始 HTTP |

实机验证细节见 [验证记录](#实机验证记录)。

---

## 为什么长这样

拆 [Via 浏览器 6.7.1](https://viayoo.com/) 得到的答案不是一个结论，是一套做法：**不带内核，靠"先探、再降、探不到就说"活下来**。

Via 2.6 MB 的 APK 里没有 `lib/`、没有 `.so`、没有 X5/Gecko，全靠系统 `android.webkit.WebView`——也就是说它每装到一台新手机上，脚下的内核都是别人给的、型号和版本都不可控。它的应付办法是：

1. **先探测**内核是谁、几版（公开 API → 反射 → 放弃，三层降级）；
2. **探不到就别硬跑**，弹修复页让用户去装 Android System WebView，而不是白屏或闪退；
3. **版本号当能力开关**，同一个功能按内核版本给两套实现（如原生暗色 vs 注入 CSS）；
4. 每条 WebView 调用按 `SDK_INT` 分层，全部包 `try/catch`，老系统走旧路径。

换句话说：**跨机型的保证不来自"兼容所有内核"，而来自"假设内核随时可能不对，并为每种不对准备好下一步"**。

这个插件把第 1、2、3 条搬到了插件层——不碰原生代码，只调用 App 已有的 `/browser` 桥：

```
E1  native   系统 WebView（真渲染 JS，能拿到动态内容）
E2  http     Node 自己抓原始 HTML 再去标签（不执行 JS）
E3  都没有   明确报错，告诉你断在哪一环
```

每档失败都记录原因，不会静默返回空内容。

---

## 两个工具

| 工具 | 作用 |
|---|---|
| `web_probe` | 探测当前可用引擎与 WebView 内核版本；不抓页面。第一次上网前调一次。 |
| `web_read` | 读取网页正文。`prefer` 可选 `auto`（默认，native 优先、失败降级）/ `native` / `http`。 |

`web_read` 的参数：

| 参数 | 说明 |
|---|---|
| `url` | 必填，只允许 http/https |
| `maxChars` | 正文上限，默认 8000，封顶 50000 |
| `prefer` | `auto` / `native` / `http` |

返回里固定带 `engine`（这一档走的是哪条路）和 `kernel`（当前内核版本），失败时带 `attempts`（每档各断在哪）。

---

## 安装

### dsh 0.2.x（当前主流，profile 机制）

0.2 起插件不再拷进 dsh 安装树，而是作为 **profile 依赖**装在 `profiles/<名字>/` 里。本仓的 `install.mjs` 是 0.1.x 时代的做法，**不适用于 0.2.x**，请按下面手动装（实测走通的路径）：

```bash
# 1. 本地打包
npm pack                      # 产出 deepseek-ai-dsh-tool-webread-0.1.0.tgz

# 2. 装进 web profile
dsh plugin --profile web add /绝对路径/deepseek-ai-dsh-tool-webread-0.1.0.tgz

# 3. 确认 profiles/web/cordis.patch.yml 里有这一条（add 通常会自己写入；没有就手动加）
#    - insert:
#        - id: tool-webread
#          name: "@deepseek-ai/dsh-tool-webread"
```

0.2.x 支持**热加载**：改完 patch 后 `dsh-hmr` 会自动重载，**不必重启引擎**（新增条目可以；移除条目才要重启）。

卸载：

```bash
dsh plugin --profile web remove @deepseek-ai/dsh-tool-webread
# 再删掉 profiles/web/cordis.patch.yml 里的 tool-webread 块，然后重启引擎
```

### dsh 0.1.x（旧安装树，未实测）

```bash
node install.mjs                                   # 自动找 dshroot
node install.mjs --dshroot <dshroot 所在目录>       # 手动指定
```

脚本做三件事，全部幂等：拷插件进安装树、在 dsh 的 `package.json` 登记依赖、在 `$HOME/.dsh/cordis.patch.yml` 的 `insert` 列表注册。装完要重启引擎。⚠️ 这套路径在 0.2.x 上**对不上**，会改到不该改的安装树。

---

## 自检

必须在**已安装的位置**里跑（否则 `@deepseek-ai/dsh-tools` 解析不到）：

```bash
cd <该插件实际安装目录>
node test/selftest.mjs
```

输出形如：

```
=== dsh-tool-webread selftest ===
-- 插件契约 --
[PASS] name 导出正确
...
-- 实况：当前环境 --
[PASS] web_probe 返回结构完整
       引擎=native native=true http=true 内核=Chromium 156
[PASS] 原生 WebView 通道可用  — 内核 Chromium 156
-- 实况：读取 example.com --
[PASS] 取到 example.com 的正文
       引擎=native title="Example Domain" chars=917
=== 汇总：PASS 30 / FAIL 0 / SKIP 0 ===
全部通过。
```

三种行：`[PASS]` 通过、`[FAIL]` 失败（退出码 1）、`[SKIP]` 这台机器上条件不具备（例如没联网 / 没有 native 通道），**不算失败**。

覆盖：插件契约、9 种非法地址拦截、HTML 正文抽取、gzip/brotli 解压、内核版本号解析，以及真的去读一次 example.com。

---

## 实机验证记录

2026-10-31 在小米 2206122SC（HyperOS / Android 15 / SDK 35）上，DSH 0.2.0-rc.2 + Node v26.4.0 实测：

| 用例 | 结果 |
|---|---|
| 自检 | PASS 30 / FAIL 0 / SKIP 0 |
| `web_probe` | native 可用，内核 Chromium 156 |
| example.com | ✅ native，917 字 |
| baidu.com | ✅ native，103 字 |
| qq.com | ⚠️ native 仅 90 字——正文靠 JS 异步加载，读太快只剩骨架 |
| `http://127.0.0.1:3080/...` | ✅ 拦截："禁止访问回环地址" |
| `ftp://...` | ✅ 拦截："只允许 http/https" |
| news.ycombinator.com | ❌ 两条引擎都失败（该机外网受限）：native 空正文 + http 超时，错误分级清楚 |
| 页签残留 | ✅ 读完自动关闭临时页签，无残留 |
| 安装方式 | profile 依赖 + `cordis.patch.yml` 注册，**热加载生效，引擎进程未重启** |

---

## 已知问题

### `prefer=http` 直连返回非法输出

```
tool "web_read" returned invalid output: value is not lossless JSON
```

原因：HTTP 成功分支写了 `attempts: attempts.length ? attempts : undefined`，显式 `undefined` 不是合法 JSON，过不了 dsh 的工具输出校验。

影响面：**只影响显式传 `prefer=http`**。`auto`（native 失败后降级）和 `native` 都正常。修法一行，尚未提交。

### 局限

- **JS 重的站点容易只拿到骨架**。qq.com 实测只有 90 字——`onPageFinished` 一到就读，异步渲染还没跑完。没有等待/重试机制。
- **不执行 JS 的那一档拿不到动态内容**。SPA、需要登录、靠 JS 渲染正文的站点必须走 native；native 挂了就只剩骨架。
- **单次一页**，没有批量抓取、没有站点地图。
- **native 档会真的开一个 WebView 页签**（借用 App 的浏览器宿主），读完立刻关；读的过程中 App 被杀可能残留。
- **内核探测只能看到 UA 里的 Chrome 版本**。原生 `caps` 没暴露内核包名，`kernel.packageName` 恒为空；UA 被 ROM 改成没有 `Chrome/` 标记时只能报 `unknown` 而不是猜。
- 低于 Chromium 60 标 `degraded` 并提示更新 WebView，**但不拦着用**。

---

## 安全边界

只允许 **http/https**，并且**拒绝本机与内网地址**：`127.0.0.0/8`、`10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`、`169.254.0.0/16`、`0.0.0.0/8`、`::1`、`localhost`、`*.localhost`。

理由：原生 `BrowserHost.policyReason` 就靠这条挡住"AI 去打 App 自己的本地服务"（`:3080` / `:3081` / node 引擎端口）。插件降级到原始 HTTP 时如果不管，等于给这条防线开了个后门——所以闸门在插件侧重写了一遍，两档引擎都过同一道。

**审计结论（第三方 2026-10-31 代码审计）**：零普通运行时依赖（无可投毒第三方包）；无 `eval` / `new Function` / `child_process` / 动态 `import()` / 文件写入；`process.env` 只读 `APP_NOTIFY_PORT` 与 `APP_LOCAL_TOKEN`，token 只出现在发往 `127.0.0.1` 的请求头里，不落盘不外发；源码内无写死的公网域名。

⚠️ **但它是第三方代码，运行在 dsh 引擎进程内，拥有引擎的全部权限，没有额外沙箱。** 上述审计只证明"没发现恶意行为"，不等于"保证安全"。

---

## 仓库结构

```
lib/index.js        插件本体（两个工具 + 引擎阶梯 + 地址闸门）
test/selftest.mjs   自检脚本
install.mjs         0.1.x 时代的安装脚本（0.2.x 请用 dsh plugin，见上）
package.json        npm 包描述（peerDependencies: dsh-tools / cordis）
```

---

## 许可

MIT
