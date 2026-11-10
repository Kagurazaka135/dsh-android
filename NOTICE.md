# 许可范围与第三方组件 / License Scope & Third-Party Notices

本仓库是**混合许可**的：不同目录适用不同许可证，**请分别遵守**。
根目录 [LICENSE](LICENSE)（MIT）只覆盖本项目作者原创的部分，不覆盖下文列出的第三方内容。

---

## 1. MIT 覆盖范围

本仓库中由本项目作者原创的内容：

- 文档与说明：`README.md`、`ROADMAP.md`、`TODO.md`、`docs/`、`memory/`
- 构建与工具脚本：`build.sh`、`scripts/`、`sync-dsh-memory.sh`
- 提示词与配置：`dsh-home/`
- 本作者对内置安卓工程所作的修改与新增，例如
  `woaiys3-app/android-app/src/com/deepseek/harness/guard/`、
  `VoiceService.java`、`VoiceModelStore.java` 等语音与权限相关代码

## 2. 不适用 MIT 的部分

| 范围 | 许可证 | 说明 |
|---|---|---|
| `woaiys3-app/` | **MIT** | 上游安卓底座，见 [woaiys3-app/LICENSE](woaiys3-app/LICENSE) |
| `woaiys3-app/android-app/src/com/deepseek/harness/vscreen/` | **LGPL-3.0** | 虚拟屏功能移植自 [Operit](https://github.com/AAswordman/Operit)，全文见 `woaiys3-app/licenses/lgpl-3.0.txt` |
| `woaiys3-app/android-app/libs/` 下的第三方库、`assets/rish_shizuku.dex` | **Apache-2.0** 等 | Shizuku、sherpa-onnx、Apache Commons、Kotlin 等，逐项来源与 SHA-256 见 [libs/README.md](woaiys3-app/android-app/libs/README.md) |
| `devhome/`、`_apk/` 等随包运行时与构建产物 | 各自原许可 | 不入库或按上游许可分发 |
| 离线语音模型（SenseVoice / KWS） | 上游模型仓库 | **不随包分发**，App 首次使用时下载并按 SHA-256 校验 |

完整的逐组件清单（含每条 LGPL-3.0 义务的履行情况）见
[woaiys3-app/THIRD_PARTY_NOTICES.md](woaiys3-app/THIRD_PARTY_NOTICES.md)。

## 3. 引用 LGPL-3.0 部分的注意事项

虚拟屏模块（`vscreen/`）按 **LGPL-3.0** 分发：

- 修改后的该模块文件及其衍生作品必须继续以 LGPL-3.0 分发，并保留原始版权与许可声明
- 不得对该模块附加禁止修改或禁止为调试而反向工程的限制
- 本仓库已随附 GPL-3.0 与 LGPL-3.0 全文：`woaiys3-app/licenses/`

若你要复用本仓库，**根 LICENSE 的 MIT 条款不能用于 `vscreen/` 下的文件**。

## 4. 与本项目无关的声明

本项目为社区项目，与 DeepSeek 官方无隶属或背书关系。
