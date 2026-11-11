# OpenMinis 参考复盘

2026-10-31。查阅 [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis)，固定在查询时的最新提交 [1a71df0](https://github.com/OpenMinis/OpenMinis/commit/1a71df0f381cefde80bf912b723de6f9831bc177)（2026-10-26）。通过网页实际查看了[官方展示图](https://github.com/OpenMinis/OpenMinis/blob/1a71df0f381cefde80bf912b723de6f9831bc177/assets/screenshots.png)，该图标注为 iOS；Android 行为另查源码，没有安装实测其 APK。

它为 Codex Go 补充的是手机工作环境的参考：材料怎样进来、文件属于哪里、切换页面后任务如何继续，以及技能和记忆如何管理。本轮记录交互与架构经验，没有移植代码或新增运行时依赖。

配置恢复、系统分享、项目归属和后台跨会话浏览现已实现。后续工作见 [当前优先级](roadmap.md)。

## 对照后的建设顺序

保留 ARM64 真机、真实账户与模型任务的 P0 验收，以及已有的账户配置恢复优先级。新增参考调整其后的顺序：

| 顺序 | 参考证据 | Codex Go 的缺口与建议 |
| --- | --- | --- |
| P1：系统分享输入 | [ShareReceiverActivity](https://github.com/OpenMinis/OpenMinis/blob/1a71df0f381cefde80bf912b723de6f9831bc177/src/android/app/src/main/java/com/openminis/app/share/ShareReceiverActivity.kt) 接收 SEND / SEND_MULTIPLE / VIEW；[ShareCoordinator](https://github.com/OpenMinis/OpenMinis/blob/1a71df0f381cefde80bf912b723de6f9831bc177/src/android/app/src/main/java/com/openminis/app/share/ShareCoordinator.kt) 指定材料的目标会话 | 已实现系统分享收件箱、独立暂存和显式目标确认；继续验证更多来源应用、复制中进程死亡与厂商窗口行为 |
| P1：项目与任务归属 | [SessionMounts](https://github.com/OpenMinis/OpenMinis/blob/1a71df0f381cefde80bf912b723de6f9831bc177/src/android/app/src/main/java/com/openminis/app/sandbox/SessionMounts.kt) 区分会话目录与共享资源；[SessionConcurrencyManager](https://github.com/OpenMinis/OpenMinis/blob/1a71df0f381cefde80bf912b723de6f9831bc177/src/android/app/src/main/java/com/openminis/app/service/SessionConcurrencyManager.kt) 独立管理运行会话 | 已实现固定项目目录、草稿 / 材料归属，以及运行 A 时浏览 B；一次执行一个任务。后续并行执行需要独立的执行资源与审批归属 |
| P1/P2：长内容与产物阅读 | [LargeContentGuard](https://github.com/OpenMinis/OpenMinis/blob/1a71df0f381cefde80bf912b723de6f9831bc177/src/android/app/src/main/java/com/openminis/app/ui/chat/LargeContentGuard.kt) 为长消息提供折叠、展开与导出，流式内容采用不同策略 | 优先分块渲染长回复、日志折叠与导出、表格和代码操作，再完善大差异查看。阈值以我们自己的设备测试为准 |
| P2：技能与偏好管理 | [SkillRepository](https://github.com/OpenMinis/OpenMinis/blob/1a71df0f381cefde80bf912b723de6f9831bc177/src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt) 有全局启用和会话覆盖；[MemoryRepository](https://github.com/OpenMinis/OpenMinis/blob/1a71df0f381cefde80bf912b723de6f9831bc177/src/android/app/src/main/java/com/openminis/app/data/repository/MemoryRepository.kt) 分开管理全局偏好与历史记录 | 增加查看、编辑、启停、来源与作用范围。实现须服从内置 Codex 的能力；先支持用户明确保存的项目约定与偏好。记忆作为背景，新要求优先，不能把旧任务自动当成当前任务 |
| 后续：系统能力与浏览器 | Android 有 [OffloadPermissionManager](https://github.com/OpenMinis/OpenMinis/blob/1a71df0f381cefde80bf912b723de6f9831bc177/src/android/app/src/main/java/com/openminis/app/offload/OffloadPermissionManager.kt) 和独立的 [BrowserUseManager](https://github.com/OpenMinis/OpenMinis/blob/1a71df0f381cefde80bf912b723de6f9831bc177/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt) | 日历、剪贴板等可考虑按能力开放的原生工具。内置浏览器需要独立处理标签页、登录和下载归属，放在材料与任务结构之后 |

项目目录的逻辑分区不能直接宣称为文件访问的安全隔离。无障碍与无线调试继续互补并开；参考权限组织方式，不改变已经确定的两条能力路径。

## 值得继承的排错经验

- 分享材料先保存，再交接到主界面。[ShareHandoffPolicy](https://github.com/OpenMinis/OpenMinis/blob/1a71df0f381cefde80bf912b723de6f9831bc177/src/android/app/src/main/java/com/openminis/app/share/ShareHandoffPolicy.kt) 记录了厂商窗口逻辑导致启动失败的案例；交接失败后应允许重新打开取回材料。
- 会话目录不要通过切换全局挂载表来表达。`SessionMounts` 的注释记录了后打开的会话覆盖全局路径的问题；切换浏览页面不应改变运行中任务的文件语义。
- 新草稿转为正式会话时，相关资源的归属也要迁移。`SkillRepository.renameSessionOverrides` 对此有明确处理；我们的草稿、附件、任务及未来技能选择应遵循同一原则。
- 状态需要可靠的事件来源。[SessionBadgeStore](https://github.com/OpenMinis/OpenMinis/blob/1a71df0f381cefde80bf912b723de6f9831bc177/src/android/app/src/main/java/com/openminis/app/service/SessionBadgeStore.kt) 区分状态与时间，并注明部分状态只是预留。我们应以 Codex 事件和已确认的进程状态为准，不能仅凭后台停留时长就断言任务中断。

历史问题描述来自作者源码注释，不代表本次已在其当前版本复现。

## 运行时边界需要新增验证

这是对旧结论的补充：它的 [Android 配置](https://github.com/OpenMinis/OpenMinis/blob/1a71df0f381cefde80bf912b723de6f9831bc177/src/android/app/build.gradle.kts) 同样是 **targetSdk 35**。[PRoot 构建脚本](https://github.com/OpenMinis/OpenMinis/blob/1a71df0f381cefde80bf912b723de6f9831bc177/deps/build_proot.sh) 将 PRoot / loader 放进 APK 的 native 库目录，并说明由 loader 加载客体 ELF；还固定了特定 Termux loader 的哈希，记录了替换版本后的跨设备差异。

因此，targetSdk 35 不应被当作“只能运行固定工具集”的最终结论。Codex Go 的固定 ELF 映射只是目前已验证的路径。后续增加独立验证：同一 SDK 下，使用已锁定的 PRoot / loader，在实际 APK 进程中执行新增客体 ELF，核对 stdout、退出码和进程清理；通过后再逐步验证原生 Python / Node 扩展与不同机型。源码对照不能替代实测，也不直接证明通用安装方案已经适用于我们。

现有固定工具集的承诺保持以验证结果为准；参考项目的兼容参数与并发上限也应经过我们的验证再决定。
