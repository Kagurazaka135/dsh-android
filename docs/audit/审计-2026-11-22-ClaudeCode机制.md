# 审计：Claude Code 的机制层与文件治理

> 2026-11-22 · 执行 ZCode（GLM-5.3-Flash）· 任务书：`G:\code\dsh-android\任务书-2026-11-22-ZCode-ClaudeCode审计.md`
> 前置：`审计-2026-11-20-提示词层防乱改.md`（prompt 层无设备条文，直接引用）、`docs/权限.md` §2（六档模式记载）
> 施工笔记：`G:\code\cc-mech-audit\notes\`（41/42/43 三份证据链笔记 + 分类器 prompt 100KB 原文存档）
> 性质：**只读审计**。未运行 claude.exe、未装插件、未改配置；凭据文件仅存在性确认（自查见文末）。

## 摘要

四句话：**权限机制**确认到「六档 enum 的 schema 字面量 + permissions 六键 + 模式→判定映射函数 + plan 工具闸 + remote 禁免审档」这一层（全部带字节偏移），但 `deny→ask→allow` 求值顺序与沙箱在 Windows 的缺席只有官方文档与一条报错串级证据；**临时文件治理**上 CC 是已审三家里唯一默认开启全自动清理的——22+ 类文件共用一个 30 天滚动清扫（`cleanupPeriodDays` 旋钮、活跃会话让路、24h 防重、pins/活进程保护），本机实测 30 天界外只剩 12 个文件 4.6MB；**最值得抄的一条**是 auto 模式的 LLM 行为分类器（~43KB prompt、HARD/SOFT BLOCK 两级、默认放行 + 用户意图双向规则、用户自定义规则插槽、独立小模型 temperature 0 判定每次工具调用）；**最大的不确定**是分类器的触发范围与模型档位（触发链、fast/thinking 选择条件未确认），以及子代理对主会话权限规则的继承关系——闭源观测的边界所在。

## 一、审计条件

### 1.1 对象

| 项 | 值 | 出处 |
|---|---|---|
| 可执行文件 | `C:\Users\漫不经心的干饭人\AppData\Local\Microsoft\WinGet\Packages\Anthropic.ClaudeCode_Microsoft.Winget.Source_8wekyb3d8bbwe\claude.exe` | 任务书 + 本机 ls 实测 |
| 大小 | 235,329,184 bytes（任务书记载 235MB 一致） | 本机 `ls -la` |
| 版本 | `2.1.152` 字符串 ×277（首现 @108858280）——任务书记载一致 | 本轮探针 |
| 构建 | bun 单文件打包，prompt 明文内嵌（前置审计 2.0 已证） | 前置审计 |
| 构建/修改时间 | 2026-05-27（文件 mtime） | 本机 `ls -la` |

### 1.2 方法限制（闭源二进制，只能观测字符串）

- 观测单元 = 字符串 + 字节偏移 + 上下文窗口；**字符串共现 ≠ 逻辑关系**（不变量 3）
- 证据分三级，报告内逐条标注：
  - **L1 字符串**：某串存在（计数 + 首偏移）
  - **L2 结构**：字符串旁有 schema/键名簇/报错文案，可推断数据结构
  - **L3 逻辑**：压缩 JS 函数体内出现模式匹配与分支返回（语法层成立；实参语义仍无法确认）
- 前置审计的坑已知并复用：ugrep 复杂度上限（改 Python 切片）、i18n 表淹没输出（先聚合后展示）、GBK stdout 编码（本轮再踩一次，见踩坑 3）

## 二、权限机制（能确认的部分）

### 2.1 六档模式的字符串证据

| 证据 | 级别 | 偏移/计数 | 内容 |
|---|---|---|---|
| 六档 enum schema 字面量 | **L3** | @225838268 | `h.enum(["default","acceptEdits","bypassPermissions","plan","dontAsk","auto"]).describe("Permission mode for controlling how tool executions are handled. 'default' - Standard behavior, prompts for dangerous op…` |
| 模式→判定映射函数 | **L3** | @218948309 | `function JB8(H,q){if(H==="auto")return"classify";if(H==="bypassPermissions"\|\|H==="plan"&&q)return"allow";if(H==="dontAsk")return"deny";return"ask"}` |
| permissions schema 键簇 | L2 | @111586992 | `allow / deny / ask / defaultMode / disableBypassPermissionsMode / additionalDirectories` 相邻 |
| remote 环境校验 | **L3** | @221940621 | `if(mH($.CLAUDE_CODE_REMOTE)&&!["acceptEdits","plan","default","auto"].includes(X))N('settings defaultMode "X" is not supported in CLAUDE_CODE_REMOTE…')` —— dontAsk/bypassPermissions 在 remote 被拒 |
| 计数 | L1 | acceptEdits ×92 / bypassPermissions ×137 / dontAsk ×48 / permissionMode ×220 / defaultMode ×45 | 首偏移见命令清单 |
| bypass 确认持久化 | L2 | `bypassPermissionsModeAccepted` ×8（@147177848…） | 用户接受过 bypass 危险提示的持久化 key |
| bypass 管理禁用 | L2 | `disableBypassPermissionsMode` ×11（@111586992/219004444…） | 策略层可禁用 bypass 档 |

与 `docs/权限.md:66` 记载比对：六档名一致；**enum 里多了 `auto` 的确切位置**（第五），且映射函数给出各档语义——`auto` 不直接判而是转 classify（见四）；`plan&&q → allow` 的实参 q 语义无法从二进制确认（推测为只读工具上下文，**未证实**）。

### 2.2 规则求值顺序

- 字面量探测：`"deny","ask","allow"`（数组）×0、`'deny','ask','allow'` ×0、`are evaluated in order` ×0
- 已找到的相邻证据：permissions 三键并存的 schema（L2）+ hook 判定串 `permissionDecision - "allow", "deny", or "ask" (PreToolUse only)`（@106566534，内嵌文档）+ hook schema `"approve"|"block"`、`"allow"|"deny"|"ask"|"defer"`（@148477008）
- **结论：求值顺序在本机观测层面未确认**。`deny → ask → allow` 的记载（`docs/权限.md:74`）保持为官方文档级证据，二进制内无对应物；「takes precedence」的 16 处命中全部是捆绑 ripgrep 的 man 页文本（@63761349 等），与权限规则无关

### 2.3 plan 模式

| 证据 | 级别 | 偏移/计数 | 内容 |
|---|---|---|---|
| 工具成对 | L2 | `ExitPlanMode` ×27（首 @105993384）/ `EnterPlanMode` ×14（首 @106016576） | 计划模式的进出以**专用工具**形态存在 |
| 反馈环文案 | L2 | @128294519 | `Please revise your plan based on the feedback and call ExitPlanMode again.` |
| 强制开关 | L2 | @112461592 | `planModeRequired` 与环境变量 `CLAUDE_CODE_PLAN_MODE_REQUIRED` 并排——环境变量可强制会话进 plan 约束 |
| 映射函数中的 plan | L3 | @218948309 | `plan` 在条件 q 下直接 allow，其余走 ask |

**结论（L2 级）**：plan 的「不能编辑」由**工具闸（Enter/ExitPlanMode 成对）+ planModeRequired 状态位**实现，与 `docs/权限.md:68-72`「模式状态机 + 单一放行闸门，不是让模型自觉」的记载吻合；完整执行流未确认，但「靠模式状态机而非 prompt」的定性有工具名成对 + 专用错误码 + 映射函数分支三重支撑。

### 2.4 Bash 规则不是安全边界（已知问题）

- **规则引擎本体（L2，@111581079 簇，校验报错串逐字）**：
  - `Tool names must start with uppercase`
  - `The :* pattern must be at the end`
  - `Move :* to the end for prefix matching, or use * for wildcard matching`
  - `Bash(npm run:*) - prefix matching (legacy)` / `Bash(npm run *) - wildcard matching`
  - `Prefix cannot be empty before :*`
  - → 两代匹配语法并存（legacy `:*` 前缀 / 新 `*` 通配），解析器带完整校验；`prefix match(ing)` ×13/×10
- **Windows 无沙箱的 CC 自认（L2，@106123939，逐字）**：`Enterprise policy requires sandboxing, but sandboxing is not available on native Windows. Shell command execution is blocked on this platform by policy.`
- **沙箱默认 fail-open（L2，@218944524 附近 schema 描述，逐字）**：`Exit with an error at startup if sandbox.enabled is true but the sandbox cannot start… When false (default), a warning is shown and commands run unsandboxed.` —— 默认警告+裸跑，managed-settings 可切 hard-gate（启动即退）
- 官方「`Bash(curl *)` 拦不住 `/usr/bin/curl`、`sh -c 'curl …'`」的原句在二进制内无对应物（探测 `not a sandbox` ×0）——该定性保持为官方文档级证据（`docs/权限.md:76`）；二进制侧以「前缀引擎语法 + 无沙箱自认 + 默认 fail-open」三件支撑同一结论：**Bash 规则的防护上限 = 前缀匹配，无沙箱兜底**

## 三、⭐ 临时文件与磁盘占用

### 3.1 本机实测（全部只读：du / ls / find / stat / cat 时间戳）

| 路径（`~/.claude/` 下） | 大小 | 内容 | 判定 |
|---|---|---|---|
| `projects/` | **1.8 GB** | 5 个项目目录（G--code 占 1.8G）的会话转录 `<uuid>.jsonl` + `<uuid>/` 伴生目录 | CC 管的最大 Scratch，**有 30 天滚动清理**（3.2/3.3） |
| `file-history/` | 32 MB / 109 个会话目录 | 每会话的文件编辑历史 | 有目录级清理（KZf） |
| `plugins/` | 29 MB | 插件 | — |
| `memory/` | 9.3 MB | → 符号链接到 G:\.claude（用户自管） | Evidence（用户记忆） |
| `skills/` | 3.9 MB | 技能 | — |
| `debug/` | 796 KB | 调试日志（latest 保留不删） | 有清理（WZf） |
| `history.jsonl` | 2.3 MB | 命令历史 | — |
| `cache/` `backups/` `shell-snapshots/` `session-env/` `paste-cache/` `tasks/` `sessions/` `state/` `plans/` | 401K / 104K / 56K / 48K / 16K / 13K / 6K / 5K / 4K | 各自小头 | 均有专属清理器 |
| `worktrees/` `ide/` | 0 | 空目录 | — |
| `_mem_backup_20260817/` `_retired/` `.git/` `_cleanup.ps1` `secrets.json*` `github-recovery-codes.txt` | 268 MB 等 | **用户自己的文件/备份/凭据，非 CC 机制产物** | CC 不碰（凭据仅存在性确认，未读） |

**30 天界实测（projects/ 的 .jsonl）**：30 天内 269 个 / **1.8 GB**；30 天外 **12 个 / 4.6 MB**——滚动清理在本机真实生效（若无效，旧转录会像 ZCode exec 那样只进不出）。

### 3.2 有没有自动清理：有，而且是 22+ 类文件的全自动清扫

**机制层证据（压缩 JS 函数链，L3 级）**：

- **默认保留期 = 30 天，代码常量**：@231567252 `var x1,o0f=30;`；schema 描述（@219007915）`cleanupPeriodDays: h.number().int().positive().optional().describe("Number of days to retain chat transcripts before automatic cleanup (default: 30). M…`；内嵌设置文档（@232268640）`(default: 30; minimum 1)`；校验提示（@169072176）`cleanupPeriodDays must be at least 1. To keep transcripts for a long time, set a large number (e.g. 3650 for ~10 years).`
- **截止日期计算**（@231559157，逐字）：`function Ws(H){let $=(Z$()||{}).cleanupPeriodDays ?? o0f; if($===0)return null; if(H!==void 0&&H<$)$=H; let K=$*24*60*60*1000; return new Date(Date.now()-K)}` —— 0 = 关闭清理
- **编排器 T94**（@231566854，逐字骨架）：前置检查 `a0f()` → 串行 22 个清理器（转录/文件历史/计划/快照/调试/遥测/任务/uploads/session-env/shares/usage-data/feedback-bundles/traces/dump-prompts/jobs/backups/hfi-auth/mcp-auth-cache/todos/statsig/logs/paste-cache）→ **worktree 清理**（`tengu_worktree_cleanup` 遥测事件）
- **调度与防抖**（@231573969）：启动后延迟触发；**IDE 会话 60 秒内活跃 → 让路**；`.last-cleanup` mtime < 24h → 跳过（`hZf=86_400_000ms`）；跑完写 `.last-cleanup` 时间戳（本机实测值 `2026-11-22T06:06:56.055Z` = 今天 14:06 本地，**清扫真实跑过**）
- **前置条件 a0f**（@231558677）：settings 源被 `--setting-sources` 禁用且无处提供 cleanupPeriodDays → 跳过（有明文日志串）；settings 校验错 → 跳过；`policySettings` 有值 → 放行（策略层优先）
- **防误删保护**（@231564547）：`jobs/pins.json` 钉住的 job 跳过；`daemon/roster.json` 里 **pid 存活检查**（`_0(j.pid, procStart)`）——活 worker 目录不删；debug/ 的 `latest` 永不删（WZf 白名单）

### 3.3 会话记录保留策略

- **转录**：`projects/<项目>/<uuid>.jsonl`（mtime 判定，30 天）+ 同名 `<uuid>/` 伴生目录连带 `rm -rf`；子代理转录在 `<uuid>/subagents/agent-*.jsonl`（本机 16 个），随父会话目录一起走
- **逐文件清理器 e0f**（@231559677 区，逐字要点）：`if(!Y.name.endsWith(".jsonl")&&!Y.name.endsWith(".cast"))continue; if(await AF(...mtime<截止...)) {删除; 若是 .jsonl 则连带同名目录 rm -rf}`
- **例外（诚实记录）**：30 天界外实测幸存 12 个文件（4.6MB）全部在 `projects/C--tmp/`，跨过多个清理窗口仍存活——根因未定（候选：清理器对该目录的 error 路径 / 调度前置失败轮空），量级无害但**「清理逻辑每次都全量成功」无法从二进制确认**
- `history.jsonl`（命令历史）：本轮未发现针对它的清理器——**未找到**，留疑问

### 3.4 和其他家对比（Codex 640TB/年、ZCode 5.1GB）

| 维度 | Claude Code | Codex | ZCode |
|---|---|---|---|
| 转录/会话记录 | **30 天滚动清理，默认开** | sessions 无自动清理（`docs/磁盘限制.md`：删除不可逆需谨慎） | rollout 轮转**代码失效**（#1352：设计 3×64MB，实测 126GB/497 文件） |
| 命令输出 | 进转录一并清理 | logs_2.sqlite 分区控空间（1000 行/10MiB）但**写入量无闸**（21 天 37TB） | exec **无任何清理**（官方 FAQ 承认，#474 实测 45.77GiB） |
| 写入量上限 | 未观测到单文件落盘上限（进没找到） | 无（事故本因） | 无（5GiB 单文件案例） |
| 配额旋钮 | `cleanupPeriodDays`（30 默认/最小 1/0 关/策略优先） | 无用户旋钮（denispol 提案未采纳） | 无 |
| 防误删 | pins + 活 pid + latest 白名单 + 活跃会话让路 | — | busy/referenced 静默跳过（且不告知用户，#1352） |
| 效果可观测 | `.last-cleanup` 时间戳留痕（本机可查） | — | 无 |
| 本机占用 | projects 1.8GB（30 天窗内容） | logs_2.sqlite 45.9MB（本机，写入量另行 37TB/21天） | exec 5.1GB 只进不出 |

**结论**：CC 对「拉鱼屎」的答案是三家里唯一完整的——**事后清做到了默认开启 + 覆盖全面 + 防抖防误删 + 效果留痕**；写入量上限（事前防）CC 与另两家一样缺。

## 四、工具层拦截

### 4.1 ⭐ auto 模式 = LLM 行为分类器（本次最大发现）

- **模式→分类**：`function JB8(H,q){if(H==="auto")return"classify";…}`（@218948309）——auto 档每次工具调用交 LLM 判定，非规则表
- **分类器 prompt（~43KB，@224478513 起，全文存档 `cc-mech-audit/notes/hardblock-prompt-region.txt`）**：开头 `You are a security monitor for autonomous AI coding agents.`
  - **Default Rule：默认 ALLOW**（fail-open），命中规则才 block；Scope 明文「agent 循环、重复、浪费 token 不是安全威胁，绝不 block」
  - **威胁模型三条**：prompt injection / scope creep（「修一个东西顺手『帮忙』删基础设施」）/ accidental damage（炸半径不明）
  - **HARD BLOCK（@224495579，全文提取）——用户意图永不洗白**：① Data Exfiltration（外传敏感数据含 git push 到 org 外、改 API base URL、自选外部服务）② **Auto-Mode Bypass**（越分类器：注入越狱 / **坏信拒收后换通道重试同一动作** / 用旗标配置 shell 间接层让权限系统放行任意代码——「外层命令被放行 ≠ 载荷被放行」）③ 插槽 `<user_hard_deny_rules_to_replace>`（**用户自定义硬拒规则替换点**）
  - **SOFT BLOCK（@224497208，12KB）——用户意图可洗白**：Git Destructive、Push to Default Branch（绕 PR review）、Code from External（curl|bash、pickle；**外部克隆的仓库也不可信**——"local on disk does not mean trusted"）、Cloud Storage Mass Delete、Production Deploy、Remote Shell Writes（kubectl/docker exec/ssh）、**Production Reads（读也是泄——凭据进 transcript）**、Blind Apply（--yes/--auto-approve、预览执行串一条）、Logging/Audit Tampering（停日志/删 git hook）
  - **ALLOW 白名单**（@224509526，9.5KB）+ **User Intent Rule 双向**：授权高门槛（SOFT only）、设限低门槛（「don't push」即生效且持续到用户解除）
  - 输出（@224520712）：`<block>yes</block><reason>…</reason>` / `<block>no</block>`
- **调用侧（L3）**：`querySource:"auto_mode"`、`max_tokens: fast?256:64`、`temperature:0`、`stop_sequences:["</block>"]`、`classifierModel` 独立字段、三档 `xml_2stage/xml_fast/xml_thinking` —— **独立小模型调用**
- ⚠️ 观测边界：哪个函数在什么条件下调 vd1（触发范围）、fast/thinking 的选择逻辑、分类器用哪个模型档——**未确认**

### 4.2 熔断器（回答「连续被拒后熔断」）

- `autocompact: circuit breaker tripped after N consecutive failures (reactive path) skipping future attempts this session` + `tengu_auto_compact_circuit_breaker`（@132284733）
- auto 模式整体熔断：`auto mode disabled: tengu_auto_mode_config.enabled === "disabled" (circuit breaker)`、`auto mode circuit breaker active (cached) falling back to default`、`Agent frontmatter requested auto mode but circuit breaker active`（@134889723/117151104）——**熔断后回落 default 档**
- `circuit breaker` 计 ×10；`tengu_*` 是内部 feature-flag 命名族（statsig）
- **权限拒绝维度的专用熔断器：没找到**。最接近物 = HARD BLOCK 的 Auto-Mode Bypass 条款把「repeated failed attempts to get a command past the classifier」列为越权信号（分类器看得见重试史，人工判定而非自动熔断）

### 4.3 硬编码禁止清单

- 分类器 prompt 的 HARD/SOFT BLOCK 两张表 = **硬编码的禁止清单**（内嵌 prompt 形态），且带用户插槽（`<user_hard_deny_rules_to_replace>` / SOFT 侧同款 `<user_soft_deny_rules_to_replace>`）
- 与本项目的 `FOUNDATION_KEYS`（硬编码白名单）同思路，但 CC 版有两级 + 意图裁定 + 可插拔

## 五、子代理与隔离

| 证据 | 级别 | 偏移 | 内容 |
|---|---|---|---|
| agent 定义 schema | L2 | @120761728 簇（各键 ×41） | `subagent_type / allowedTools / disallowedTools / isolation / maxTurns / background / memory / when_to_use / getSystemPrompt` |
| 内置子代理禁工具 | **L3** | @223196343 / @136515511 / @141364120 | `canUseTool:async()=>({behavior:"deny",message:"Away summary cannot use tools",decisionReason:{type:"other",reason:"away_summary"}})`——away_summary / side_question / rename 三类**代码层禁用全部工具**（`behavior:"deny"` 回调，非 prompt） |
| fork 语义 | L2 | @223190569 | Task 工具 prompt 描述：`specify a subagent_type to use a specialized agent, or omit it to fork yourself — a fork inherits your full conversation context.` |
| 子代理转录有界 | L2（代码）+ 本机 | e0f 逻辑；本机 `projects/G--code/<会话>/subagents/agent-*.jsonl` ×16 | 父会话 .jsonl 清理时伴生目录 `rm -rf` 连带 |
| skip 权限旗标 | L1 | `--dangerously-skip-permissions` ×23（首 @127124720） | CLI 旗标存在；remote 环境下免审档被拒（2.1 的 remote 校验） |
| 子代理继承主会话规则？ | — | 无对应字符串 | **未找到**（继承/不继承的明确表述都没有），进没找到 |

## 六、能抄什么

| # | CC 怎么做（证据） | 我们怎么对应 | 落点 | 工作量 |
|---|---|---|---|---|
| 1 | **30 天全自动滚动清扫**：22+ 类文件一个截止日期、默认开、旋钮可调（3.2 节 L3 证据链） | `$DSH_HOME/tmp/` 的 7 天保留清理由「prompt 劝说」升级为定时机制；tmp 会话化（上张审计报告第八节第 1/4 条）并入同一清扫器 | dsh 定时插件 / Windows 计划任务；AGENTS.md 第七节 | 1-2 小时 |
| 2 | **防抖三件套**：活跃会话 60s 让路、24h 防重（`.last-cleanup` mtime）、跑完写时间戳 | dsh 清扫器照抄：dsh 会话进行中不清 tmp；每天最多一轮；留痕文件可查 | 同上 | +20 分钟 |
| 3 | **防误删保护**：pins 钉住跳过、活 pid 跳过、`latest` 白名单 | dsh 清 tmp 前检查：正在运行的会话目录跳过、用户 `touch` 过的 pin 文件跳过 | 同上 | +30 分钟 |
| 4 | **auto 模式分类器（HARD/SOFT BLOCK 两级 + 默认放行 + 意图双向规则）** | dsh Guard 第三档（现静默放行）的升级蓝本：HARD=改系统设置/还原类（永不放行）、SOFT=卸载/清数据类（用户意图可洗白）；判据用小模型按 transcript 判，不靠关键词表 | dsh-android Guard + 一个小模型 API 调用 | 中（1-2 天：prompt 移植 + 接 DS API + 灰度） |
| 5 | **`<user_hard_deny_rules_to_replace>` 用户插槽** | `FOUNDATION_KEYS` 硬编码白名单升级为配置插槽（用户可在配置文件追加硬拒规则） | dsh-android Guard 配置 | 半天 |
| 6 | **plan 模式工具闸**（Enter/ExitPlanMode 成对 + `CLAUDE_CODE_PLAN_MODE_REQUIRED` 环境变量强制） | dsh 的「只读模式」参照：模式状态机 + 放行闸门，不是 prompt 劝说；环境变量可从外部强制 | dsh-android 会话模式 | 半天（机制已有雏形，docs/权限.md §6） |
| 7 | **remote 环境禁免审档**（dontAsk/bypassPermissions 在 remote 被拒，@221940621） | dsh 手机端（无人盯屏幕的形态 = CC 的 remote 对应物）默认禁掉全放行档 | dsh-android 启动参数校验 | 2 小时 |
| 8 | **沙箱 fail-open 默认 + 可选 hard-gate**（@218944524：默认警告裸跑、managed 可切启动即退） | dsh 的审批闸默认项：默认放行但留「硬闸」开关给高风险环境（主人真机） | dsh-android Guard 配置 | 2 小时 |

## 七、抄不了的

1. **沙箱本体**：CC 在 Windows 原生也没有沙箱（@106123939 自认）——两家同病；CC 的沙箱依赖平台隔离设施（macOS seatbelt / Linux 容器类），dsh-android 没有等价物。
2. **分类器的模型成本架构**：CC 每次判定打一次独立小模型调用（temperature 0、64-256 tokens）——dsh 有 DS API 可做，但手机端延迟与费用要在灰度里量；这不是「抄不了」，是「抄之前要先量成本」。
3. **worktree 隔离**（`tengu_worktree_cleanup`）：依赖 git worktree + CC 的仓库管理假设；dsh 的典型工作区不是 git 仓。
4. **statsig feature-flag 体系**（`tengu_*` ×大量）：远端配置的灰度/熔断开关，需要自家基础设施。

## 八、没找到的部分（闭源决定的）

1. **`deny→ask→allow` 求值顺序**：三种字面量形态全零（2.2 节）；官方文档记载是唯一来源。
2. **分类器触发链**：谁调 vd1、对哪些工具触发、fast/thinking 档怎么选、分类器模型是哪一档——只有 `querySource:"auto_mode"` 与参数结构。
3. **子代理权限继承**：主会话 allow/deny 规则在子代理里的生效关系无字符串证据（只有内置子代理的 canUseTool deny 与 agent schema 的 allowedTools/disallowedTools 键）。
4. **Bash 规则「不是安全边界」的官方原句**：二进制无对应物（`not a sandbox` ×0）；有前缀引擎报错串 + Windows 无沙箱自认 + fail-open 默认三件旁证。
5. **转录单文件大小上限**：未观测到截断机制（对照 ZCode #474 的 5GiB 单文件、#1352 的 11GB）——CC 侧「有没有单命令输出落盘上限」未确认；本机最大转录单文件 70MB（find 实测 @1795296113）。
6. **`history.jsonl` 的清理器**：22+ 清理器清单里没有它；是否永久累积未确认（本机 2.3MB，量级无害）。
7. **`m94`/`JS6`/`C74`/`p94` 的数值与语义**：调度参数与前置函数被混淆压缩。
8. **`projects/C--tmp/` 12 个超龄幸存文件**的根因（3.3 节例外）。

## 九、我的疑问

1. **auto 分类器的成本**：它对「每次工具调用」都打模型，还是只对 deny/ask 边缘案例？这决定我们抄它时的调用量级（抄之前的成本实测项，见六.4）。
2. **本机 `settings.local.json` 只有 permissions.allow**（grep 键名确认）：主人现在的实际档位是 default？六档里实际用哪档，决定分类器那条抄了之后的行为差异——要看一眼 UI 或问主人（读配置值超出本任务权限边界）。
3. **ZCode 桥接 CC 模式名时的 auto 去向**：`docs/权限.md:116-117` 的映射 `default/auto/acceptEdits → build`——CC 的 auto（分类器裁定）映射到 ZCode 的 build（每次文件改动都问），行为语义差一截，这是两套机制对齐时最值得注意的一条。
4. **12 个超龄幸存者**：要不要在下次 CC 更新后复测（若是老版本 bug，新版 `2.1.152` 可能已修——本机 exe 是 5 月构建，官方 master 已前进很多）。

---

## 踩坑记录（本次实际踩到/避掉的坑）

1. **GBK stdout 编码坑（真踩）**：Python 打印含 `¶` 等非 GBK 字符的上下文时 `UnicodeEncodeError`。避法：脚本入口 `sys.stdout.reconfigure(encoding="utf-8", errors="replace")`——与前置审计的 str/bytes 坑同族，这次是输出端。
2. **du 大目录超时（真踩）**：`du -sh */` 在 projects/（1.8GB、数百文件）上 120s 超时转后台，等了 4 分钟才出结果。避法：大目录测量提前放后台或降精度（`du -s` 顶层）。
3. **字符串表 ≠ 代码区（避掉一半）**：`cleanupPeriodDays` 17 个命中里多数是字符串表/报错文案，第一次抽窗口（@153479000）拿到的是字符串表不是逻辑。避法：全部命中逐一过上下文，认准压缩 JS 语法特征（`function`/`=>`/`return`）才算 L3。
4. **「Launch a new agent」首偏移是二进制字符串表（避掉）**：第一个命中是垃圾窗口，按「高偏移 = JS/prompt 区」的经验改抽后续偏移才拿到真描述。
5. **任务书路径红线（避掉）**：`_cleanup.ps1` 与 `secrets.json*`、`github-recovery-codes.txt` 出现在测量输出里——按不变量 2 只确认存在性未读内容；`_cleanup.ps1` 是用户自己的遗留脚本（内容 255 字节，非凭据，读了并标注「非 CC 机制」）。
6. **没运行 claude.exe（避掉）**：全部证据来自二进制静态扫描与本机文件只读测量；版本号用字符串探针（`2.1.152` ×277）而非 `claude --version`。

## 设备状态变更

无。未改系统设置、未装插件、未改 CC 配置、未运行 claude.exe；本机文件零删除零修改（唯一写入 = 报告与 `cc-mech-audit/notes/` 两处白名单目录）。

## 命令清单（全部只读 + 白名单写入）

```
ls -la <exe 路径>                          # 大小/时间验证
ls -la ~/.claude/                          # 顶层清单（凭据文件仅出现在 ls 输出，未读内容）
du -sh ~/.claude/*/                        # 各目录体量（后台完成）
find projects -name "*.jsonl" -mtime ±30   # 30 天界分布 + 体积 du -ch
cat ~/.claude/.last-cleanup                # 清理时间戳
grep -o '"permissions"\|"allow"\|…' settings.local.json   # 只统计权限键名
python -I - <<'PYEOF'                      # 主力工具（每轮一个脚本，单遍读入 235MB）：
  data.find(kw) 计数+偏移 → 上下文切片 → stdout utf-8
  关键词组1：acceptEdits/bypassPermissions/dontAsk/ExitPlanMode/EnterPlanMode/
           planModeRequired/defaultMode/"deny"/"ask"/"allow"/circuit breaker/2.1.152
  关键词组2：cleanupPeriodDays(全17命中上下文)/janitor/retention/shell-snapshots
  关键词组3：subagent_type/disallowedTools/canUseTool deny/Launch a new agent/
           takes precedence/not a sandbox/sandboxing
PYEOF
Write → G:\code\cc-mech-audit\notes\*.md    # 分片笔记（41/42/43 + hardblock-prompt-region.txt 100KB 存档）
cat notes/frag-*.md >> 报告                  # 报告分片组装
```

## 附：验收自查（任务书第七节八项）

| 验收项 | 结果 |
|---|---|
| 1. 报告存在、路径正确 | ✅ `G:\code\dsh-android\审计-2026-11-22-ClaudeCode机制.md` |
| 2. 字符串结论带偏移或 grep 命令 | ✅ 全部 L1/L2/L3 证据标注字节偏移 + 计数；命令清单含完整探针词表 |
| 3. 标注「观测非确认逻辑」 | ✅ 1.2 节定义 L1/L2/L3 三级证据制；每条 L3 带「实参语义无法确认」限定；确认不了的进第八节 |
| 4. 临时文件节有本机实测数字 | ✅ 3.1 表（projects 1.8GB/file-history 32MB/109 目录…）+ 3.3 的 30 天界分布（269 个 vs 12 个） |
| 5. 与 Codex/ZCode 对比表 | ✅ 3.4 六维对比表 |
| 6. 没运行 claude.exe | ✅ 全程静态扫描 + 文件只读；版本用字符串探针 |
| 7. 没读任何凭据文件 | ✅ secrets.json / secrets.json.bak / github-recovery-codes.txt 仅在 ls 输出中确认存在；settings.local.json 只 grep 权限键名不读值 |
| 8. 踩坑记录区已写 | ✅ 上节 6 条（2 真踩 + 4 避掉） |
