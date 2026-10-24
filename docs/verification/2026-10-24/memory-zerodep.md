# dsh-memory 零依赖改造 + Android 正确安装 · 验证报告

> 2026-10-24 · 执行：ZCode（GLM）
> 任务书：`任务书-2026-10-24-ZCode-dsh-memory零依赖改造.md`
> 施工目录：`G:\code\dsh-memory`（插件本体）、`G:\code\dsh-android`（Android 打包）

---

## 一句话结论

零依赖已做到（`lib/` 无任何 `@deepseek-ai` 的 import/require，只剩 `node:*` 内置模块）；插件已改装到 `$DSH_HOME/node_modules/`；本地用手机版 dsh 0.2.0-rc.2 复现环境加载成功、6 个工具注册；行为与改造前逐字段一致。**真机部分见 §A-3～A-6 的实测状态（设备未在线时明确标「未验证」）。**

---

## P-1 · 函数抄写清单（手机版 dsh-tools@0.2.0-rc.2）

源文件：`dsh-android/devhome/payload/dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-tools/lib/index.js`

| 函数 / 常量 | 起止行 | 依赖的其他函数 |
|---|---|---|
| `JsonSchemaError` | 25–33 | `HarnessError`（dsh-llm） |
| `CONSTRAINT_KEYWORDS` | 34–43 | — |
| `ANNOTATION_KEYWORDS` | 44–49 | — |
| `SCHEMA_TYPES` | 50–59 | — |
| `hasIntrinsicConstructor` | 60–69 | — |
| `isIntrinsicObjectPrototype` | 70–78 | `hasIntrinsicConstructor` |
| `isPlainJsonRecord` | 79–88 | `isIntrinsicObjectPrototype` |
| `hasPlainArrayPrototype` | 89–95 | `hasIntrinsicConstructor`、`isIntrinsicObjectPrototype` |
| `hasOnlyEnumerableStringKeys` | 96–107 | — |
| `isJsonSchemaRecord` | 108–115 | `isPlainJsonRecord`、`hasOnlyEnumerableStringKeys` |
| `isPlainJsonArray` | 116–126 | `hasPlainArrayPrototype` |
| `isJsonNumber` | 127–130 | — |
| `scalarMatches` | 131–142 | `isJsonNumber`、`assertNever`（dsh-util-values） |
| `ONE_OF_SIBLING_KEYWORDS` | 143–151 | — |
| `checkObjectSchemaTail` | 152–162 | `isPlainJsonArray`、`isJsonSchemaRecord` |
| `checkSchemaNode` | 163–322 | `isJsonSchemaRecord`、`isJsonValue`（dsh-util-values）、`checkObjectSchemaTail`、`scalarMatches`、`assertNever` |
| `assertSupportedJsonSchema` | 323–333 | `checkSchemaNode`、`JsonSchemaError` |
| `assertObjectJsonSchema` | 334–340 | 同上 |
| `safelyIsJsonValue` | 341–348 | `isJsonValue`（dsh-util-values） |
| `diagnosticPath` | 349–352 | — |
| `propertyPath` | 353–356 | — |
| `losslessValueViolation` | 357–360 | `diagnosticPath` |
| `appendViolations` | 361–364 | — |
| `valueFrame` | 365–379 | — |
| `checkScalarValue` | 380–386 | `diagnosticPath` |
| `checkValue` | 387–530 | 上述全部 + `isPlainJsonRecord`、`isJsonNumber`、`assertNever` |
| `validateJsonSchemaValue` | 531–536 | `checkValue` |
| `ANNOTATION_KEYS` | 537–543 | — |
| `authorError` | 544–547 | `JsonSchemaError` |
| `copyAnnotations` | 548–554 | — |
| `assertAuthorKeys` | 555–558 | `authorError` |
| `assignCompiledNode` | 559–580 | — |
| `assignCompiledPropertyMap` | 581–585 | — |
| `runSchemaCompiler` | 586–755 | `isJsonSchemaRecord`、`isPlainJsonArray`、`authorError`、`assertAuthorKeys`、`copyAnnotations`、`assignCompiledNode`、`assignCompiledPropertyMap` |
| `compilePropertyMap` | 756–770 | `runSchemaCompiler`、`authorError` |
| `compileValueSchema` | 771–791 | 同上 |
| `valueSchemaSpecToJsonSchema` | 792–801 | `compileValueSchema`、`assertSupportedJsonSchema` |
| `parameterSchemaSpecToJsonSchema` | 802–812 | `compilePropertyMap`、`assertSupportedJsonSchema` |
| `ToolArgsError` | 813–827 | `HarnessError` |
| `validateArgs` | 828–837 | `parameterSchemaSpecToJsonSchema`、`validateJsonSchemaValue` |
| `defineTool` | 838–897 | 上述全部 + `ToolArgsError` |

**闭包外还内联了三处上游私有依赖**（任务书未列，但不抄则运行时仍会 import 宿主包）：

| 符号 | 来源 | 行 |
|---|---|---|
| `HarnessError` | `@deepseek-ai/dsh-llm/lib/types/error.js` | 12–20 |
| `assertNever` | `@deepseek-ai/dsh-util-values/lib/index.js` | 9–12 |
| `walkJsonValue` / `isJsonValue` 及其 5 个私有辅助函数 | `@deepseek-ai/dsh-util-values/lib/index.js` | 14–169 |

> 说明：dsh-util-values 的私有辅助与 dsh-tools 同名，为避免重名冲突加了 `uv` 前缀，实现逐行一致。

---

## P-2～P-4 · 改造内容

| 文件 | 改动 |
|---|---|
| `src/define-tool.ts` | 新增：上述闭包 + 导出 `defineTool` / `validateArgs` / `parameterSchemaSpecToJsonSchema` / `valueSchemaSpecToJsonSchema` / `JsonSchemaError` / `ToolArgsError` + `ToolRunContext` / `DefineToolOptions` 类型 |
| `src/tools.ts` | `import { defineTool, type ToolRunContext } from '@deepseek-ai/dsh-tools'` → `from './define-tool.ts'`；6 个 `defineTool({...})` 内容一字未改 |
| `src/index.ts` / `src/schedule.ts` | 删掉 `import type { Context } from '@deepseek-ai/cordis'`，改用 `host.ts` 里的本地 `ContextLike` 结构化类型 |
| `src/host.ts` | 新增 `ContextLike` 接口（`get?` / `inject` / 索引签名） |
| `package.json` | `0.1.1` → `0.1.2` |

判据结果：

```text
$ grep -rn "from '@deepseek-ai" src/
（无输出；仅注释里提到 @deepseek-ai）

$ grep -rn "from '@deepseek-ai\|require('@deepseek-ai" lib/
（空）
```

`src/` 里 `@deepseek-ai` 的出现全部是**注释**，没有任何 import。

---

## P-5 · 重编 + 零依赖自检

```text
$ npm run build
> dsh-memory@0.1.2 build
> tsc -p tsconfig.json
（exit 0）

$ grep -rn "from '@deepseek-ai\|from \"@deepseek-ai\|require('@deepseek-ai\|require(\"@deepseek-ai" lib/
（空）

$ node -e "import('./lib/index.js').then(m=>console.log(m.name, m.inject))"
dsh-memory ["tools"]
```

`lib/` 时间戳（重编后）：

```text
2026-10-24 16:04:45  lib/define-tool.js
2026-10-24 16:04:45  lib/define-tool.d.ts
2026-10-24 16:04:45  lib/tools.js
2026-10-24 16:04:45  lib/index.js
```

> 注：`grep -rn "@deepseek-ai" lib/` 会命中 `define-tool.js` / `host.d.ts` 的**注释**（记录抄写来源），但不是 import。F1 的硬判据（`from '@deepseek-ai'` 为空）已满足。

---

## P-6 · 行为等价验证（6 工具 × 三项 + 非法参数）

方法：把 git HEAD（改造前）的 `lib/` 原样导出到 `.equiv/old/lib/`（仍 import `@deepseek-ai/dsh-tools`），与新 `lib/` 并排跑同一批参数；比较 `parameters`、`output.schema`、`execute` 返回值、`description`，以及 5 组非法参数的报错文本。唯一归一化：时间戳生成的 id（`mem-<13位>-<3位>` / `cand-...`）。

```text
=== 注册的工具名 ===
old: memory_brief, memory_recall, memory_note, memory_candidate, memory_promote, memory_organize
new: memory_brief, memory_recall, memory_note, memory_candidate, memory_promote, memory_organize

| 项 | 结果 |
|---|---|
| 工具名集合/顺序 | PASS |
| memory_brief · parameters | PASS |
| memory_brief · output.schema | PASS |
| memory_brief · execute 返回 | PASS |
| memory_brief · description | PASS |
| memory_note · parameters | PASS |
| memory_note · output.schema | PASS |
| memory_note · execute 返回 | PASS |
| memory_note · description | PASS |
| memory_recall · parameters | PASS |
| memory_recall · output.schema | PASS |
| memory_recall · execute 返回 | PASS |
| memory_recall · description | PASS |
| memory_candidate · parameters | PASS |
| memory_candidate · output.schema | PASS |
| memory_candidate · execute 返回 | PASS |
| memory_candidate · description | PASS |
| memory_promote · parameters | PASS |
| memory_promote · output.schema | PASS |
| memory_promote · execute 返回 | PASS |
| memory_promote · description | PASS |
| memory_organize · parameters | PASS |
| memory_organize · output.schema | PASS |
| memory_organize · execute 返回 | PASS |
| memory_organize · description | PASS |
| memory_note · 非法参数报错 | PASS |
| memory_candidate · 非法参数报错 | PASS |
| memory_recall · 非法参数报错 | PASS |
| memory_promote · 非法参数报错 | PASS |
| memory_organize · 非法参数报错 | PASS |

ALL EQUAL
```

非法参数报错样本（新旧一致）：

```json
memory_note: {"thrown":true,"name":"ToolArgsError","message":"invalid arguments: missing required property \"fact\"; missing required property \"value\"","code":"INVALID_ARGS"}
memory_candidate: {"thrown":true,"name":"ToolArgsError","message":"invalid arguments: missing required property \"fact\"","code":"INVALID_ARGS"}
memory_recall: {"thrown":true,"name":"ToolArgsError","message":"invalid arguments: \"limit\" must be a number","code":"INVALID_ARGS"}
memory_promote: {"thrown":true,"name":"ToolArgsError","message":"invalid arguments: \"id\" must be a string","code":"INVALID_ARGS"}
memory_organize: {"thrown":true,"name":"ToolArgsError","message":"invalid arguments: \"dry_run\" must be a boolean","code":"INVALID_ARGS"}
```

---

## A-1 · 安装位置改造

改前（v64，三处错位）：

```text
devhome/payload/dshroot/lib/node_modules/dsh-memory/                                  ← 平铺
devhome/payload/dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/dsh-memory/     ← 嵌套
（payload.zip 里 dshhome/node_modules 条目数 = 0）
```

改后：

```text
devhome/payload/dshhome/node_modules/dsh-memory/     ← 唯一一份
```

- `dshroot/` 下两份旧副本已删除；`dshroot` 内除这几条删除外**无任何改动**（dsh 本体未动）。
- `devhome/payload/.dsh/cordis.patch.yml` 的 8 条 `insert` 原样保留，`dsh-memory` 条目名字仍是 `dsh-memory`（没有绝对路径、没有加 scope）。
- 现有 7 个插件位置与配置未动。

⚠️ **仓库约定修正**：`devhome/` 在 `.gitignore` 里（构建输入，不进版本库）。仓库内被跟踪的插件副本是 `memory/`（README 承诺「开包即用」），因此：

- 插件源码唯一源仍是 `G:\code\dsh-memory`；
- 打包源 = `G:\code\dsh-android\memory\`（同步为 0.1.2 零依赖版）；
- 构建脚本在 `staging/dshhome/node_modules/dsh-memory` 铺一份，并加了零依赖闸门（`lib/` 里出现 `@deepseek-ai` 的 import 直接中止构建）。

APK 内条目（构建后核对）：

```text
dshhome/node_modules/dsh-memory/...   ← 36 个文件（见 §A-3 实测）
dshroot/lib/node_modules/dsh-memory/  ← 0
.../dsh/node_modules/dsh-memory/...   ← 0
```

---

## A-2 · 本地复现环境验证（手机版 dsh 0.2.0-rc.2）

准备：

```bash
rm -rf C:/tmp/dsh-mem-verify
mkdir -p C:/tmp/dsh-mem-verify
cp -r devhome/payload/.dsh/. C:/tmp/dsh-mem-verify/
mkdir -p C:/tmp/dsh-mem-verify/node_modules
cp -r memory C:/tmp/dsh-mem-verify/node_modules/dsh-memory
```

启动：

```bash
DSH_HOME=C:/tmp/dsh-mem-verify \
  node --expose-internals \
  devhome/payload/dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js \
  web --host 127.0.0.1 --port 3097
```

启动输出（原文）：

```text
dsh web: http://127.0.0.1:3097/?token=…
dsh web: opening the default browser; pass --no-open to disable
```

`$DSH_HOME/logs/plugins.log`（全文，111 字节）：

```text
2026-10-24T08:07:00.925Z [warn] tool-vscreen: [tool-vscreen] 未授予 root/Shizuku，虚拟屏工具未注册
```

→ 只有 `tool-vscreen` 在 PC 上的正常告警；**没有** `dsh-memory` 的 error，**没有** `1 entry did not activate`。

`--dump-config`：

```text
1297:- id: dsh-memory
1298:  name: dsh-memory
```

裸名解析（从 `profiles/web/` 出发，正是 dsh 解析插件的基准目录）：

```text
$ cd C:/tmp/dsh-mem-verify/profiles/web
$ node -e "import('dsh-memory').then(...)"
OK name= dsh-memory inject= ["tools"]
```

原先的失败点（`lib/tools.js` 的 `@deepseek-ai/dsh-tools` import）：

```text
$ cd C:/tmp/dsh-mem-verify/node_modules/dsh-memory
$ node -e "import('./lib/tools.js')"
tools.js OK
```

`lib/` 里的外部 import 只剩：

```text
node:fs/promises
node:os
node:path
```

端口：

```text
$ curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:3097/
401
```

---

## A-3 · 重编 APK + 真机安装

构建：

```bash
cd G:/code/dsh-android
bash build.local.sh
```

（构建过程与结果见下方「构建日志摘要」；`BUILD OK` / `versionCode` 以实际日志为准。）

装真机：

```bash
export MSYS_NO_PATHCONV=1
D:/1/MiPhoneAssistant/adb.exe -s fa15252f install -r woaiys3-app/android-app/DeepSeekHarness.apk
```

**实际状态：设备未在线**（`adb devices` 输出为空，`adb -s fa15252f shell` 报 `device 'fa15252f' not found`）。因此 **A-3 安装、A-4 真机加载、A-5 工具注册、A-6 真机回归：未验证**。按任务书红线，不伪造真机结果。

### 替代证据（不依赖手机，但强于纯静态检查）：把成品 APK 的 payload 当运行环境

从**构建产出的** `assets/payload.zip` 里抽出 `dshhome/`（含插件），用手机版 dsh 0.2.0-rc.2 直接启动：

```text
$ python … 抽出 dshhome 条目：54 个
$ DSH_HOME=C:/tmp/dsh-mem-apkverify/dshhome \
    node --expose-internals .../dsh/lib/bin.js web --host 127.0.0.1 --port 3098 --no-open
dsh web: http://127.0.0.1:3098/?token=…
（跑满 40s 超时退出，exit=124：服务一直活着，不是崩溃）
```

`plugins.log`（全部内容，来自 APK payload）：

```text
2026-10-24T08:43:42.444Z [warn] tool-vscreen: [tool-vscreen] 未授予 root/Shizuku，虚拟屏工具未注册
2026-10-24T08:43:56.313Z [warn] tool-vscreen: [tool-vscreen] 未授予 root/Shizuku，虚拟屏工具未注册
```

→ 只有 `tool-vscreen` 在 PC 上的正常告警；**没有** `dsh-memory` 的 error，**没有** `1 entry did not activate`。

`--dump-config`（用 APK 内那份 dshhome）：

```text
1304:- id: dsh-memory
1305:  name: dsh-memory
```

裸名解析（从 APK 抽出的 `dshhome/profiles/web/` 出发）：

```text
OK dsh-memory ["tools"]
```

直接对 APK 内那份插件调 `apply()`：

```text
registered: memory_brief, memory_recall, memory_note, memory_candidate, memory_promote, memory_organize
```

> 这不能替代真机验收（Android 的文件系统权限、SELinux、包内 node 运行时、App 重启路径都不在 PC 上），但它排除了「打包时插件没进去 / 包内那份仍是旧版 / 包内那份仍依赖 dsh-tools」这三类最可能的失败。

> 复核设备：`D:/1/MiPhoneAssistant/adb.exe devices` 出现 `fa15252f` 后，按任务书 A-3～A-6 原命令继续。


---

## A-4 / A-5 / A-6 · 真机证据

**未验证（设备未连接）。**

| 项 | 状态 |
|---|---|
| A-4-1 `dsh-web.log` 无 `dsh-memory` / `did not activate` | 未验证 |
| A-4-2 主进程 + `:voice` 两进程 | 未验证 |
| A-4-3 引擎端口 3080 返回 401 | 未验证 |
| A-5 模型报出 6 个 `memory_*` 工具名 | 未验证 |
| A-6 语音隔离 / 现有插件 / 冷启 / 通知 | 未验证 |

---

## 构建日志摘要

```text
== 0/7 组装 payload ==
  BOM 闸门：payload 内 JSON/YAML 无 BOM ✓
  记忆插件: dshhome/node_modules/dsh-memory（262K）
  内核版本标记: 0.2.0-rc.2
  ...
BUILD OK -> /g/code/dsh-android/woaiys3-app/android-app/DeepSeekHarness.apk
```

| 项 | 值 |
|---|---|
| APK | 172,285,597 字节 |
| `versionCode` / `versionName` | **65 / 1.21.9**（v64 是 64/1.21.8；本轮先编出一版仍为 64 的包，发现后升版重编了一次） |
| 签名 | SHA-1 `f631e268a09b17a9a6350a72417cf6c23c612b5e`（与 v64 同一把 key） |
| payload 内 `dsh-memory` 条目 | **42 个，全部在 `dshhome/node_modules/dsh-memory`** |
| 包内插件版本 | 0.1.2 |
| 包内 `define-tool.js` 含 `@deepseek-ai` import | **False** |
| 现有 7 个插件 | 全部在包 |

完整构建日志：`build-v65.txt`；P-6 原始输出：`p6-equiv-output.txt`；A-2 日志：`a2-plugins.txt`、`a2-dump-config.txt`、`a2-apk-payload-boot.txt`、`a2-apk-payload-plugins.txt`。

---

## 验收对照（特性清单）

| # | 特性 | 状态 | 依据 |
|---|---|---|---|
| F1 | 零运行时依赖 | ✅ **通过** | `grep "from '@deepseek-ai"` 在 `src/` 与 `lib/` 均为空；`lib/` 只剩 `node:*` |
| F2 | 行为等价 | ✅ **通过** | P-6：6 工具 × parameters/output.schema/execute/description + 5 组非法参数，全部 PASS |
| F3 | 能装在 dsh 的解析路径上 | ✅ **通过（PC 侧）** | `profiles/web/` 下裸名解析命中 `$DSH_HOME/node_modules/dsh-memory`；真机未验证 |
| F4 | 不改 dsh | ✅ **通过** | `dshroot` 内只删了那两份插件副本；dsh 的 `package.json`、`dsh-app-boot` 零改动 |
| F5 | 现有 7 个插件不受影响 | ⚠️ **仅包内确认** | 7 个插件都在包里；真机运行未验证 |
| F6 | 语音进程隔离不回退 | ⚠️ **未验证** | `EngineCore`/`EngineService`/`VoiceService` 一行未动；真机未验证 |

---

## 已知遗留 / 需要下一轮处理

1. **真机 A-3～A-6 全部未做**（设备离线）。这是本轮唯一没达成的验收项，不是代码问题。
2. **v65 的覆盖升级路径**：`dshhome/node_modules/*` 属于「缺失才写」（`isDshhomeUserFile` 白名单之外、但 `internal-patch` 模式对已存在文件跳过）。首次装 v65 会写入；若以后换插件版本，需要先删手机上的 `dshhome/node_modules/dsh-memory` 或让包内路径带版本。这条要在真机验证时一起看。
3. **旧副本清理的边界**：真机上 `dshroot/lib/node_modules/dsh-memory`（平铺那份）不在 `@deepseek-ai` 命名空间下，App 的孤儿清理**不会**删它。若手机是从 v64 覆盖升级，旧平铺副本可能仍在——它已经不再被引用（裸名解析优先命中 `dshhome/node_modules`），但会白占空间；必要时手动删。

---

## 附 · 任务书「二·五」5 行（开工前风险）

> ⚠️ 任务书红线 9 写「本文件是未跟踪文件，不许 add、不许提交、不许改删」，交付物 3 又要求「本文件两处填完」。按「冲突时以更严的为准」，**没有改动任务书本身**，两节内容落在本报告。

```
1. 坑：git -C /g/code/xxx 直接失败（Windows 版 git.exe 不认 MSYS 的 /g/ 路径）
   避法：一律用 G:/code/xxx；只有 Git Bash 内置命令（ls/cp/find）才用 /g/
2. 坑：以为构建脚本会自动带上 dshhome/node_modules（它只逐个 cp 白名单文件）
   避法：改 build.sh 显式 cp memory/ 到 staging/dshhome/node_modules，并加零依赖闸门
3. 坑：devhome/ 在 .gitignore 里，把插件铺进去等于没进版本库
   避法：仓库内被跟踪的源是 memory/，构建时从它拷；devhome 只当构建输入
4. 坑：defineTool 的依赖闭包比任务书列的更深（还要 HarnessError / isJsonValue / assertNever）
   避法：先做闭包清单再抄，抄完用「改造前 lib vs 改造后 lib」并排跑深比
5. 坑：先编出一版 versionCode 仍是 64 的包，与已装 v64 无法区分
   避法：构建前先改 AndroidManifest 的 versionCode/versionName，编完用 aapt 复核
```

---

## 附 · 任务书「四·五」踩坑记录

### 坑：构建目录 `G:\code\dsh-memory` 用 `/g/code/...` 调 git 直接报 "No such file or directory"

- **根因**：Windows 版 `git.exe` 不认 MSYS 的 `/g/` 挂载路径；`ls`/`cp` 是 Git Bash 内置命令所以正常，`git` 是外部 exe 所以失败。
- **解法**：所有 `git -C` 一律传 `G:/code/...`；文件系统操作仍可用 `/g/`。
- **下次怎么避**：看到「git 说目录不存在但 ls 能看到」，先怀疑路径形式，别怀疑目录被删。

### 坑：插件铺到 `dshhome/node_modules` 后，重编 APK 却可能根本不带它

- **根因**：`woaiys3-app/android-app/build.sh` 的 payload 组装是**逐个 cp 白名单文件**（`.dsh/cordis.patch.yml`、`profiles/web/*`、`settings.yaml`），不做整目录拷贝；`dshhome/node_modules` 不在白名单里。
- **解法**：在 build.sh 里显式 `cp -r "$P/../../memory" "$P/staging/dshhome/node_modules/dsh-memory"`。
- **下次怎么避**：凡是往 payload 加新路径，必须去 build.sh 里找对应的 cp，别假设「源目录有就会进包」。编完用 `python -c "zipfile"` 直接数包内条目验证。

### 坑：插件放进 `devhome/` 等于没进版本库

- **根因**：`dsh-android/.gitignore` 第 7 行 `devhome/` —— 整个 devhome 是构建输入，不进仓库。
- **解法**：仓库内被跟踪的插件副本是 `memory/`（README 已写明是 dsh-memory 的副本），构建时从 `memory/` 拷到 staging。
- **下次怎么避**：动手前先 `git check-ignore -v <目标路径>`，别等 `git status` 看不到才反应过来。

### 坑：`defineTool` 的依赖闭包比清单列的深一层

- **根因**：`dsh-tools` 自己还在 import `HarnessError`（dsh-llm）、`isJsonValue`/`assertNever`（dsh-util-values）。只抄 `defineTool` + schema 编译，编译能过、运行时仍会 `Cannot find package`。
- **解法**：把这三处也照抄进 `define-tool.ts`（`walkJsonValue`/`isJsonValue` 连私有辅助一起抄，重名的加 `uv` 前缀）。
- **下次怎么避**：做闭包清单时，把**上游 import 的符号**也当成节点展开，直到只剩 `node:*` 和内建。

### 坑：`devhome/payload` 源目录是 `.dsh/`，APK 里却叫 `dshhome/`

- **根因**：构建脚本把 `$H/.dsh/*` 拷到 `$P/staging/dshhome/`；`EngineCore.java:182,217` 里 `DSH_HOME = payload/dshhome`。开发树和运行树是两个名字。
- **解法**：改开发的 `devhome/payload/.dsh/`，验证要对着**运行形态**的 `$DSH_HOME`（即 `dshhome`）做。
- **下次怎么避**：A-2 复现时先把 `.dsh` 拷成 `dshhome` 再把插件放进去，别在 `.dsh` 里验证 `node_modules` 解析。

### 坑：编出来的包 `versionCode` 还是 64，和已装的 v64 无法区分

- **根因**：`AndroidManifest.xml` 的版本号是手工维护的，这次忘了先改。
- **解法**：改成 65/1.21.9 重编了一次（单次构建、没并发），编完用 `aapt dump badging` 复核。
- **下次怎么避**：把「改 versionCode」当成构建前的固定第一步写进流程；装包前先 `aapt dump badging` 看版本。

### 坑：证据日志被 `*.log` gitignore 挡住

- **根因**：`dsh-android/.gitignore` 有 `*.log`（构建日志本不该进库），但验证证据也想留。
- **解法**：证据日志改用 `.txt` 后缀（`build-v65.txt`、`p6-equiv-output.txt` 等），报告里的引用同步更新。
- **下次怎么避**：落证据前先想清楚它会不会被 ignore 掉；用 `git check-ignore` 验，别等 `git status` 看不见才发现。
