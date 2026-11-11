# 新项目结构

```text
MainActivity / Compose
  └─ EngineService / StateFlow
       ├─ JsonRpcClient → stdio → PRoot → Codex 0.165.0
       ├─ Timeline → 以 item ID 归并实时事件
       ├─ Draft / PendingMessage → 按会话保存草稿与待发送队列
       ├─ WorkspaceFiles → 导入 / 本地图片输入 / 产物链接 / FileProvider
       ├─ Project / ProjectRepository → 固定 cwd、项目新草稿与原生历史过滤
       ├─ ShareReceiverActivity / ShareInbox → 临时 URI 复制、持久收件箱与明确草稿目标
       ├─ ConfigurationRecovery → 原配置备份、成功启动快照与原子替换
       ├─ RuntimeEnvironment → 可替换的 guest + 独立账户 / 工作区
       ├─ ConnectProxy → Android DNS / VPN socket → TLS 直通
       ├─ HistoryImages / ImageArchive → 分会话原图、压缩预览与校验
       ├─ HistoryGateway → 可选模型 HTTP 请求变换 → Android HTTPS
       └─ 手机工具
            ├─ PhoneAccessService → 无障碍截图 / 手势 / 文字
            └─ WirelessDebug → 本机 mDNS / 通知配对 → TLS ADB → shell-v2
```

只保留一种 Codex 运行位置：手机本机。会话权威来自本机 Codex；App 不另存一套可能分叉的消息历史。独立图片存档是原生会话的辅助材料，不是另一套对话记录。

启动时先调用原生 `config/read` 检查配置层，再完成账户、模型和历史恢复后进入就绪状态。`initialize` 可以在配置错误后回退至默认值，不能独自作为配置可用的证据。`configWarning` 在设置页展示，原始细节不写入诊断。恢复期间禁用新建 / 发送，避免新建请求被加载状态忽略、随后把消息接到旧会话。

运行环境修复通过同一安装互斥锁串行执行，强制从 APK 解包并替换 `environment`，保留同级的 `codex`、`workspace` 和 `projects`。先在临时目录完成解包，替换失败恢复旧目录；若目录切换时进程死亡，下一次安装先恢复备份。修复会重启引擎并撤销本次手机 / Shell 许可，不自动续发队列。它不会清除或修正账户配置，也不会保留用户自行安装到客体系统目录的工具。

账户级 `codex/config.toml` 恢复独立于系统修复。原配置先以原始字节保存到私有 `config-recovery`，提交前一份备份指针后才原子替换配置；最多保留五份操作备份和一份最近成功启动快照。快照只在配置检查、账户 / 模型 / 历史启动完成且文件未改变时更新，不等于模型服务或凭据已可用。恢复可选择默认、上次启动或前一份配置；拒绝符号链接、非普通文件、超过 1 MB 或无法备份的配置。恢复通过安装互斥锁串行执行，保留认证、历史、草稿和工作区，撤销手机操作许可；成功后清除模型覆盖，进入新对话，队列不续发。恢复标志持久保存，恢复后意外退出再启动也不会自动接回旧任务。项目 `.codex/config.toml` 不会被改写。

账户退出使用 `account/logout`，任务结束后才允许操作。系统诊断为独立的白名单 JSON：版本、设备、空间、运行阶段、能力连接和受控事件；不收集原始日志、错误消息、认证文件、配置、用户消息或工作文件。未捕获的 App 异常只记录异常类型，随后交还 Android 的原异常处理器。

会话名称、归档与恢复均以原生 app-server 为准。标题 / 摘要搜索遍历当前视图全部分页，支持取消，并通过客户端引用与请求代数避免旧结果覆盖新查询；历史分页错误有单独的重试状态。归档保留按会话保存的草稿和队列，恢复时队列等待显式继续。消息正文全文搜索、分叉及生成时浏览其他对话尚未实现。

引擎默认不自动启动；操作许可默认关闭。服务拥有本次许可，进程重启后不会恢复。未知的服务端请求返回错误，命令 / 文件操作审批按原请求 ID 回复。待审批数据留在服务状态中，不依赖某个 Dialog 的生命周期。

PRoot 提供兼容环境，不是内核沙箱。Codex 在 Android 应用 UID 权限范围内运行，`untrusted` 审批策略用于命令治理。无障碍与无线调试并行连接。Computer Use 优先使用无障碍，缺少无障碍时使用 ADB；动作沿用产生 frame ID 的实现，不能把旧坐标直接交给另一种实现。Android Shell 可选择无线调试、有线 ADB 桥接、Shizuku UserService 或 su，拥有独立许可，并逐条展示命令确认。停止任务会取消正在运行的工具。

模型请求、账户管理、会话、流式事件和动态工具使用 [官方 app-server 协议](https://learn.chatgpt.com/docs/app-server)。`tools/check_protocol.py` 检查真实安装版本的能力。

## 运行时与验证

历史压缩关闭时，模型请求仍使用原有的 ConnectProxy / TLS 链路。开启时，登录和模型目录读取保持原链路；选定线程的普通 Responses 模型请求通过带随机路径凭据的 loopback HistoryGateway，再由 Android 正常建立 HTTPS。没有 TLS 中间人证书，也不保存或导出 HTTP 认证头。运行时覆盖 provider 的 base_url / WebSocket / 请求压缩选项，普通响应以 HTTP 流式转发；账户 config.toml 不改写。Bedrock / GCP / AWS 签名配置保持原连接，不宣称支持该变换。

ARM64 与 x86_64 的 native libraries、rootfs 和 manifest 分别生成在 `.gradle/runtime-bundles/`。APK 的 BuildConfig 和资产 manifest 都记录目标 ABI，安装器校验一致性。账户与工作区独立于可替换的 guest 系统。

JSON-RPC 的出站消息使用有界 IO 队列。单条入站 JSONL 上限为 `128 * 1024 * 1024` 个字符；截图的 Base64 主要为 ASCII。旧版 16 Mi 字符上限会拒绝 `thread/read` 返回的多截图完整历史。此次保留完整返回内容，没有压缩实时观察或改写原生历史。历史列表读取失败保留侧栏的错误与重试入口，不关闭已连接的引擎；恢复期间若连接已关闭，则停止后续启动步骤，不把断开原因覆盖为“请先启动本机环境”。引擎日志流在停止时可以正常关闭。对话列表的滚动位置与跟随状态由外层界面保存，跨页面保持用户的阅读位置，并提供回到最新回复的入口。

## 任务恢复与模型能力

错误优先按原生 `codexErrorInfo` / HTTP 状态分类，缺失结构化信息时才匹配有限的消息模式。`willRetry` 保留引擎正在重试的状态；终止失败暂停队列，不因中断后的续发标记而越过失败。RPC 调用超时与外部协程取消分别处理，避免超时后停留在生成或准备状态。

默认网络回调跟踪当前网络身份：收到旧网络的丢失事件不能覆盖已切换的新网络；离线回调直接更新状态，避免立即重新读取 `activeNetwork` 时观察到旧值。离线阻止新发送并保留草稿；联网事件不自动提交。此状态只表示设备有默认网络，不保证模型服务可访问。

任务输入在本机私有偏好中按会话记录，成功或明确中断后清理；失败或进程意外退出后可恢复为待检查任务。用户手动重试时保留不同的新草稿。由于任务可能已经执行部分操作，恢复入口提示先检查已有结果，不自动重复执行。这里只保存待检查的输入，跨进程恢复不声称已经精确重建原任务最终状态。

模型目录保留支持的思考档位、说明和默认值。显式模型的 `turn/start.effort` 只使用该目录支持的值；引擎默认选项省略覆盖。恢复会话时以 `thread/resume` 返回的模型 / 深度更新界面，避免沿用另一个会话的设置。目录没有该模型或没有思考能力时不虚构档位。

文件审批只把关联 `fileChange` 的完整内容视为可审阅差异。展示动作、路径与文本；原生明确的新增空文件可按空文件查看。缺失差异或超过 64,000 字符的展示上限时显示原因并禁用批准，服务响应前再次校验。超限需拆分，尚未实现大差异分页查看。

## 材料与产物

系统文件选择器提供临时 URI 读取权限，导入器把材料复制到当前项目的 `imports/<UUID>/`（默认 `/workspace/imports`，其他项目 `/projects/<UUID>/imports`）。每条消息最多 8 个附件，单个不超过 64 MB；复制过程中也检查实际字节数，失败时清理该批目录。移除附件只取消发送，不删除已经导入的工作区文件。

系统 `ACTION_SEND` / `SEND_MULTIPLE` 使用独立接收 Activity。接收器在临时授权仍有效时异步复制 `content://` URI；合并并去重 `EXTRA_STREAM` 与 ClipData，拒绝文件 URI，沿用 8 文件 / 单文件 64 MB 限制，文字上限 128K 字符。复制失败删除本批目录。未确认材料位于 `share-staging`，不挂入原生工作区；确认归属后以批次目录移入工作区。目录已移动而偏好尚未提交时退出，收件箱仍在，重开可继续确认。接收 ViewModel 跨旋转保留工作，SavedStateHandle 保留批次 ID；批次和接收记录在私有偏好中提交，重建后不重复接收已提交批次。进程在复制完成前死亡时需从来源重新分享。

收件箱最多 20 批，未确认的分享可跨进程保留。用户明确选择当前或新对话；目标已有草稿合并，附件合并超过 8 个时两侧都保留。移出收件箱与写入目标草稿在同一次同步偏好事务中提交，避免跨进程重复追加。当前任务忙碌时禁止新建目标，但可加入该会话未发送草稿，不调用 `turn/steer`、排队或发送。移除收件箱条目会清理暂存材料；已经确认进入工作区的文件保留。

PNG / JPEG / WebP / GIF 通过 `localImage` 交给 Codex，其余文件以工作区链接供工具读取。历史图片、文件差异和回复中的 `sandbox:/workspace/...` 链接可呈现文件卡片。路径解析检查 canonical 路径，拒绝越界和指向工作区外的符号链接。FileProvider 仅暴露工作区，打开与分享只授予所选 URI 的读取权限；导出使用系统创建文档流程。

## 执行与手机控制

0.13.2 将动作与图像证据的新鲜度分开：`phone_tap` / `phone_swipe` / `phone_type` / `phone_key` 不再调用 `FrameGuard.requireCurrent`，也不要求先取得有效观察。旧会话仍可传 frameId，新会话中它是可选兼容字段。动作路由直接选择可用的无障碍 / ADB 后端，`observedBy` 只用于同一帧原图读取。`ScreenCoordinates` 根据当前默认显示器的完整像素范围（含系统栏、考虑旋转）检查点击与滑动两端，不读取过期截图的尺寸；参考 [Android Display.getRealSize](https://developer.android.com/reference/android/view/Display#getRealSize(android.graphics.Point))。页面刷新、动画、光标事件和图像缓存到期不再撤销动作执行资格。

执行中插话使用带 `expectedTurnId` 的 `turn/steer`。排队消息由 App 保存，正常完成后顺序发送；停止或失败会暂停队列，进程重启后也需要用户显式继续。中断并发送会等待原轮次结束，再启动队首消息。草稿只有被接受后才清空，插话失败保留原草稿。

手机任务提供独立的暂停状态与人工接管原因。暂停使现有 frame ID 失效，并在下一次工具执行前等待显式继续，不关闭无线调试连接或 Android Shell 许可。恢复后要求重新观察。熄屏 / 锁屏会暂停手机动作；闲置 3 分钟也会暂停。`phone_handoff` 等待用户确认，不把用户在其他应用中输入的秘密当作工具结果收集。

无障碍任务在 App 外使用 accessibility overlay 显示当前动作及暂停 / 继续、停止按钮；截图和手势执行期间隐藏悬浮条，避免截图污染和点击拦截。ADB-only 情况提供常驻通知控制。完成、失败和待确认分别使用任务通知；可完整展示的普通短命令可从通知允许本次或拒绝，长命令、网络 / 额外许可、文件差异与提问需要回到 App 查看。通知动作携带服务实例 nonce 与引擎代数，防止旧请求在重启后被误批准。

应用名规则只是一层有限防护：阻止对自身、常见权限 / 安装 / 系统设置、已识别的银行支付应用执行直接动作；密码节点与带验证码提示的无障碍输入也交还用户。这不等于能够可靠识别所有敏感页面，纯 ADB 路径尤其缺少节点语义。

## 工具链

`tools/toolchain-<ABI>.lock.json` 固定 Alpine v3.22 的 Python 3、pip、Node.js、npm、Git 及依赖版本和 SHA256。打包时把所有预置 ELF 映射到 APK 的 native libraries，把脚本执行位单独记录。安装版本指纹包含输入和打包脚本，升级环境不覆盖账户、工作区、草稿与队列。

targetSdk 保持 35。此方案提供固定预置工具集；运行时新下载的 ELF 和原生扩展不自动获得执行能力，不能宣称支持任意 apk / pip / npm 原生依赖安装。

## 项目归属（0.6.0）

默认项目沿用 `files/workspace` → `/workspace`；新项目为 `files/projects/<UUID>` → `/projects/<UUID>`。父目录在引擎启动时固定挂载，切换项目不改挂载、不重启引擎。项目名不参与路径拼接，改名不影响 cwd。新目录与旧工作区是并列关系，避免继承旧项目的 Git 根或 AGENTS / 配置层；这不构成内核安全隔离。

`ProjectRepository` 在私有偏好保存名称与 UUID；`thread/start.cwd`、`thread/list.cwd` 与恢复返回的 `thread.cwd` 共同决定会话归属。恢复未知目录不会强行映射至默认项目。项目切换取消旧列表请求并检查请求代数与项目 ID，防止旧结果覆盖新列表。

默认新草稿继续使用 `new`；新项目新草稿使用 `new@<UUID>`，正式会话仍使用原生 thread ID。草稿、队列、失败输入共用该归属方式。导入捕获项目目录与 `DraftOwner`；若首个原生会话在导入期间返回，归属只迁移一次，不能被另一项目或后续会话接管。导入中禁止项目 / 会话切换。

分享暂存使用独立 `/workspace/imports/...` 虚拟路径；确认时先保存目标项目和草稿槽位，再将批次目录移到目标项目，转换为真实项目路径，并在同次偏好事务中保存草稿、恢复目标并移除收件箱条目与路由。若移动后退出，原批次仍可通过保存的目标解析；回到原目标继续，不允许另一项目接收已绑定批次。丢弃只删除未发布暂存，已经进入项目的文件保留。

`WorkspaceFiles` 根据固定目录布局选择 `/workspace` 或 `/projects/<UUID>`，并在每个项目根下校验 canonical 路径。FileProvider 只开放 workspace 与 projects，不开放账户或分享暂存。运行任务与浏览状态的分离见下节。

## 后台跨会话浏览（0.7.0）

`EngineService.state` 始终保留原生执行目标：Timeline、RPC 事件、审批、工具和自动队列只更新它。`ReadingConversation` 保存当前浏览目标的历史快照和独立草稿；`screen` 将两者投影给 Compose，并保留全局 busy，防止浏览时绕过账户 / 环境维护守卫。界面使用 browsing 区分当前对话的发送 / 停止 / 重试等操作。

浏览 B 使用 `thread/read(includeTurns=true)`，不调用 `thread/resume`，也不改变持久执行目标 `project/thread`。浏览选择单独写入 `reading`。草稿、文件和分享入口以浏览目标为准；执行时附件校验使用 ownerWorkspace。首次 thread/start 返回前不允许切换浏览，队列 / 草稿迁移先于新 threadId 发布。导入记录是否跟随首次执行，避免同项目的新草稿导入错误迁移。

任务结束保留 TaskRecord 与结果，B 继续停留。进入 B 必须显式操作，恢复原生会话后只切换目标，不自动发送。激活过程锁定导航与导入。Compose 使用 SaveableStateHolder 只包住阅读区域，按项目 / 原生会话槽保存列表与跟随回复状态；输入区保持稳定，首次分配原生 ID 不重建输入框或丢失焦点。

每次执行产生新的任务 token，与结果一起持久化。通知返回 / 停止 / 暂停 / 继续、内联审批、悬浮控制均绑定 token；内联审批另校验服务 epoch、进程 generation 和当前 requestId。返回通知从冷启动恢复引擎后再定位执行会话，过期 token 只提示失效。Activity 消费导航请求后保存消费状态，旋转不会重新打开旧通知目标。

## Expressive 界面与模型入口（0.8.0）

主题使用 Material 3 的 MaterialExpressiveTheme / MotionScheme.expressive，颜色、形状、文字样式与 Bento 组件独立于 EngineService。项目网格、快捷草稿、能力面板与任务栏只读取现有状态和调用既有操作，不改变项目路径、执行目标或双后端语义。阅读区域继续单独保存状态，输入框保持稳定。

模型面板使用引擎返回的 Model 目录，搜索在本地过滤名称、ID 和描述；selectModel / selectEffort 仍由服务校验可编辑状态并保存，提交时填写实际原生 RPC 参数。界面提供引擎未启动说明、默认模型、刷新和支持的思考档位。运行中和跨会话浏览时不允许修改原任务配置。面板关闭不自动提交消息。

## 悬浮对话与通知回复（0.8.0）

ChatOverlay 由 EngineService 管理，使用显式授权的 TYPE_APPLICATION_OVERLAY，气泡不取焦点、展开后允许键盘输入。直接订阅执行 StateFlow，覆盖绕过 publish 的流式 Timeline 更新；新回复跟随到底部，手动阅读上方内容时保留位置。显示依赖引擎就绪、用户开关、悬浮窗权限、屏幕交互和解锁状态；App 可见时关闭。手机工具观察与动作期间隐藏并清除输入焦点，避免污染截图或遮挡目标；服务销毁时移除窗口。

通知 RemoteInput 使用指向未导出服务的显式可变 PendingIntent，其他控制保持不可变。回复绑定不透明随机 token 与 QuickTarget（服务实例、引擎代数、项目、会话及会话修订），处理后轮换 token；旧目标不会跟随新对话。仅首次发送所分配的原生会话 ID 可在同一修订中跟随。Android 12+ 的回复动作要求解锁，服务在所有支持版本上再次检查锁屏状态。

常驻引擎通知合并密集状态更新，最多每秒刷新一次，刷新时读取最新状态；结束状态也会补发，避免 Android 按包限流后永久停留在旧回复。停止 / 销毁服务会取消待刷新的任务。

通知输入方式参考 [Android 官方 Direct reply 说明](https://developer.android.com/develop/ui/compose/notifications/create-notification)。

快捷输入先同步写入 QuickMessages，再发送或加入原执行目标的队列，调用 send(clearDraft=false)，不修改执行或浏览目标的已有草稿。输入不调用插话，也不隐式切换会话。目标不匹配、断网、维护中或队列已满时保留待处理消息，不自动重放；用户显式加入当前草稿时，将草稿合并与消息移除在同次偏好事务中提交。主进程中断后的不确定输入留待用户检查。

## Android Shell 与配对入口（0.9.0）

AndroidShell 维护四种连接的独立可用状态，只向用户选中的实现派发命令，不自动回退或升级权限。切换执行方式、连接丢失时关闭 Shell 许可，取消正在执行的工具和待审批请求；审批记录绑定原方式，提交前再次核对。无障碍和无线调试的 Computer Use 不受 Shell 选择影响。

有线 ADB 的 app_process 在 shell UID 下启动，仅回连到应用的 127.0.0.1 随机端口，以每次 256 位随机令牌验证。等待窗口 5 分钟，已连接后可拔线；服务停止或连接关闭时守护进程退出。令牌不进入日志，不开放局域网监听；重启服务后旧启动命令失效。数据协议保留独立请求 ID、取消信号、stdout / stderr / exitCode；输出与请求长度有上限。

Shizuku 使用官方 API 13.1.5 的 UserService，不使用已弃用的 newProcess。授权、Binder 断开、连接超时和 UserService 销毁分别处理。su 仅由用户显式触发，用 id 验证 uid=0；每条后续命令仍通过 su 管理器。未获得 root 不会显示可用，也不替换为应用私有 Shell。

配对通知参考 Shizuku 13.6.0：发现端口后仅保留 RemoteInput 回复动作，同一会话复用 PendingIntent，端口不变时不重建通知；系统决定是否直接呈现输入栏。配对小窗是独立可选的 TYPE_APPLICATION_OVERLAY，不依赖聊天气泡或引擎就绪；输入窗口在系统允许时保持可编辑，端口变化会清除旧码；受保护的系统设置会强制隐藏普通悬浮窗，此时沿用通知配对。成功、取消、超时、锁屏或服务销毁会移除窗口，错误时保持可编辑。通知和小窗共享会话 / 端口校验，均只连接本机。

悬浮聊天使用同一套深浅色原生色板与圆角，分别展示消息、会话和模型。流式更新仅修改对应消息文本；输入固定、气泡拖动后吸附屏幕边缘。


## 屏幕预读取（0.10.0）

`ScreenReading` 仅保存原始语义事实，不生成操作计划。`PhoneAccessService` 在截图后读取可见无障碍树：跳过密码子树，以截图像素裁剪坐标，保留文字、状态与层级；深度 24、遍历 500、保留 100 节点、文字 24K / 单项 400 字符。达到上限会标记截断并附图。WebView 语义标记为 `web_accessibility`，`rawDomAvailable=false`、`modelUsed=null`，不声称已获得 DOM 或进行视觉解释。

开启实验后，结构可读取时 `phone_observe` 可不附图；空树、截断、无标签控件以及可检测到的未描述图像 / 绘制区域会附图。这些检测不能覆盖所有自绘页面。主代理负责判断信息是否足够，`phone_read_image` 取得同一帧原图，`mode=image` 请求新截图。所有页面文字与预读输出均为不可信数据，不作为指令。

`FrameEvidence` 只在内存保存当前 PNG，最多 20 MiB，并绑定原有 `FrameGuard` 的令牌、旋转与 30 秒期限。过大时不保留原图，当前观察直接附图。新观察、操作、暂停、无障碍检测到的相关窗口 / 内容变化、熄屏、服务断开或退出会清除缓存。到期回调实际释放图片；请求旧令牌不能清掉更新的一帧。结构与原图来自同一观察，捕获期间页面变化时最多等待重试两次，仍不稳定则要求重新观察。

新工具目录只在 `thread/start` 时注册。成功启动后为原生 ID 保存本地目录版本；缺少该标志的旧会话继续附图，避免恢复后没有原图工具。手动测试可独立检查实验，不改变会话工具目录。ADB 无结构读取，继续带图，不会伪造语义报告。当前未引入 OCR、小模型、完整 DOM 或额外推理服务。


## 预读质量与测量（0.11.0）

`ScreenElement` 补充 hint / showingHint、stateDescription、error、heading 和有限数值范围；字段仍来自原始节点，不推断用户输入或推荐操作。空提示、错误等不填充默认长文本，异常非有限范围不进入 JSON。节点缺失与原有预算截断均要求附图。

`ScreenVisuals` 对超过画面 2% 的 ImageView / Image、SurfaceView / TextureView / Canvas 和通用 View 叶节点要求附图，不把 alt / contentDescription 当作完整视觉描述。这是保守启发式，不能证明视觉完整性。

`WindowInventory` 读取至多 32 个可交互窗口，仅忽略本应用的无障碍控制条。普通小型系统栏从覆盖判断中排除；其他可见窗口、当前窗口面积不足画面 75%、库存缺失 / 超限均要求附图。报告明确 `activeWindowOnly=true`。监听 `TYPE_WINDOWS_CHANGED` 并比较窗口 ID / 类型 / 位置，在布局改变时使原图失效；还保留原有内容事件、旋转和时限保护。[Android 官方 getWindows 文档](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getWindows()) 说明该接口仅报告可交互窗口，不能据此声称已经枚举全部视觉内容。

截图 / 节点读取仍在主线程调用系统 API；PNG 编码移到 `Dispatchers.Default`。编码后重新检查 revision 与 rotation，避免后台处理期间页面已变却登记为新帧。`metrics` 的 captureMs / structureMs / encodeMs / attemptMs 描述最后一次尝试，totalMs / attempts 包含本次观察的重试与等待；structureBytes 是结构 JSON 的 UTF-8 大小，originalPngBytes 是原始 PNG 大小。边界从进入观察到编码完成，不含模型网络 / 推理和工具封装时间。

`ReadingPreview` 只解析本应用生成的单行结构报告，图片是否存在以真实 contentItems 为准。界面先展示可读摘要，再展开原始报告；ADB 或失败返回保留原始说明。没有额外模型调用，不把字节大小换算为虚构 token 用量。


## Codex 账号中的模型辅助预读（0.12.0）

`Model.inputModalities` 从原生目录读取，预读选择仅接收已列出的图像模型。辅助开关默认关闭；`phonePreReadModel` 与主模型偏好独立。`gpt-6-luna` 是候选 ID，不通过固定名称假定账号权限，也不静默切换到其他模型。

`CodexPreReader` 使用同一运行环境 / 账号和 ConnectProxy 启动短时的独立 app-server，工作目录固定为独立的 guest `/tmp/codex-go-preread`。不把临时通知、消息和审批送到主客户端的处理器。CLI 与 `thread/start.config` 双层关闭操作功能；逐个显式禁用有效配置里的 MCP 名称，而非仅传入空表。0.165.0 的会话加载会重新解析配置，空表或仅进程覆盖不能代替会话约束。创建会话前检查生效配置，并使用 `ephemeral=true`、空动态工具 / 环境 / 能力列表、只读权限和不允许 provider 替换模型的选项。12 秒限时包括客户端初始化与模型响应；最低思考档由所选目录支持值确定。输出 schema 只允许 texts / visuals / uncertainties 的有界字符串数组。

只给辅助模型截图与原始节点，不提供主用户任务、主对话历史、项目 cwd 或 AGENTS 文档。画面中的提示或指令本身仍是不可信数据；事实数组也不能证明模型没有看错。原始语义节点保持不变并优先于未经验证的模型描述。辅助模型没有动作坐标或计划字段；raw 响应中的工具调用会使这次预读失败。

`PreReadPolicy.shouldAssist` 让完整语义直接通过；常规观察只在可补读的视觉缺失时调用，必须附图的截断 / 窗口覆盖场景不额外调用。显式测试不跳过，用于检查实际连接。`PreReadPolicy.attach` 保留原始缺失标记。只有模型成功、没有不确定项、且无节点不完整 / 窗口覆盖问题时才延迟向主模型附图；这不是把任务决策交给辅助模型。主模型可用原有 `phone_read_image` 复核同帧，或用 `phone_observe(mode=image)` 跳过辅助重新截图。原图仍受 30 秒、旋转、窗口变化和操作许可保护；辅助处理完成后再次验证。失败回退受控原因和原图，不向主模型转发未经解析的模型输出。

临时进程 stderr 只排空，不进入 App 日志。排空任务归外层资源作用域，避免限时请求在返回前等待管道 EOF，而 EOF 又在清理中才触发。结束时先终止进程、再关闭客户端管道，取消与超时也执行清理。临时会话不保存到原生历史，不改变主任务 / 草稿 / 队列或主模型设置。

这条接入复用当前 Codex 模型服务，需要网络及账号额度；它不包含手机本地权重或端侧推理引擎。原生模型入口与临时会话协议参考 [官方模型目录](https://learn.chatgpt.com/docs/models) 和 [app-server 文档](https://learn.chatgpt.com/docs/app-server)，具体工具约束还需对固定运行时验证。


## 历史图片的请求变换与原图存档（0.13.0）

`ImageArchive` 在 App 私有 `history-images/<threadId>/` 保存源图片、JPEG 预览与校验元数据。源字节不重编码；预览最长边 768、质量 65，透明区域合成到白色。SHA256 编号在该会话内稳定。缓存不静默淘汰原图，容量不足时不改变已有原生记录。若只有模型处理过的副本，工具明确报告 `originalAvailable=false`。

`HistoryImages` 可从旧 rollout 的 DynamicToolCall 完成记录建立 call ID 到原图的索引，也在新附件 / 手机工具结果处保存完整源图。`projectHistory` 只替换 App 返回值中的图片字段，保留文字、参数、diff 与审批信息；原生 rollout / SQLite 不重写。模型输入由 `HistoryGateway` 在发送之前变换，不能把界面投影变化当作模型输入已经压缩。

每轮输入带内部随机标记，以区分真正的用户任务与原生 role=user 的环境 / 缩放提示。最新图组保持原样，之前图组使用压缩预览；内部元数据不显示为额外用户消息，也不破坏首个原生 ID 的归并。仅可信的内置历史读取调用可把结果替换为完整原图，普通工具文字和伪造的历史标记不能替换实时图片。

内置 MCP Python 助手通过线程专属 loopback 能力读取存档，不直接操作手机；旧会话恢复时也能加载。启用请求变换前检查原生 MCP 连接状态；工具不可用则保持完整原生图片。读取原图会在 Main 上先清除实时同帧图像缓存，然后才返回历史像素。`phone_read_image` 的短时同帧缓存与此存档是不同能力；清除缓存不阻止之后的点击等动作。

0.13.1 区分内置与自定义 provider：内置 `openai` 仅通过受支持的线程级 `openai_base_url` 改变地址，不能写入 `model_providers.openai`；保留原有认证与 provider 身份，不重命名服务或复制凭据。内置 WebSocket 能力不能覆盖，loopback 对升级请求直接返回 426，由原生运行时回退 HTTP，不把握手转发到上游。自定义服务继续使用 provider 嵌套表深度合并，保留认证 / 头信息；不复制 config/read 可能脱敏的凭据字段。固定 0.165.0 的 API 覆盖对点号键按字面拆分，与 CLI TOML 引号语法不同；包含点号的 provider 名也使用嵌套表。其他保留 ID `ollama` / `lmstudio` 暂时维持原连接，不生成非法配置。HTTPS 上游不跟随重定向，认证头不写日志或存档，loopback 通道有随机路径凭据和连接 / 请求大小边界。

该功能压缩模型请求中的旧图，仍需接收原生完整历史和请求；不保证原生进程的峰值内存下降，也不把图片字节数等同于 token 或额度。真实 ChatGPT 认证、workspace routing、更多图片格式 / 文件 ID 与长时间压力仍需实测。

## 自动准备与能力可用性（0.14.0）

`syncCapabilities` 根据引擎是否就绪、无障碍 / ADB 是否已连、当前 Shell 是否已连，以及本次临时暂停状态生成实际能力。不再把系统授权和 App 内 AI 授权拆为两次操作；连接丢失不会把临时暂停标记错误地写为永久关闭。暂停 / 停止和命令审批仍由原有任务边界执行。

`engineUsed` 记录成功启动，`autoPrepare` 可关闭，`shellBackend` 保存用户选择。前台进入且曾使用过才自动启动；失败状态不循环重启，启动不自动发送草稿 / 队列。无线保存最后连接端口并在失效时重新发现；Shizuku 仅自动绑定已有系统授权，root 仅在曾连接成功时尝试恢复。首次 grant 不通过代码自行授予。

悬浮窗选择与 `Settings.canDrawOverlays` 分别保存 / 检查，短暂缺权不删除用户选择。`overlayUnavailable` 仅关闭当前实例以避免失败重试循环，前台恢复后重新检查。QuickControls 使用可保存状态，支持字号 / 方向变化。

## 操作生命周期提示（0.15.0）

`PhoneActivityIndicator.during` 在 Main 上为实际调用领取唯一租约，首帧绘制后开始工作，在 `finally` 的 NonCancellable 清理中同步移除最后一个窗口，无最短驻留或淡出。`ActivityLeases` 支持重叠与重复退出，暂停 / 断开清空租约后，旧 finally 不能撤掉新操作的提示。只覆盖 observe / tap / swipe / type / key / open_app；缓存 read_image、handoff 和任意 Shell 命令不点亮。

无障碍 `execute` 使用自身服务 token 的 TYPE_ACCESSIBILITY_OVERLAY，interrupt / unbind / destroy 直接清理。无线 ADB 的 `execute` 复用已授予的悬浮窗权限，使用 TYPE_APPLICATION_OVERLAY；无线连接失效与 close 清理。引擎中断、任务结束、能力暂停和熄屏沿用原有的帧失效入口同步关闭两者。辅助模型预读在实际观察返回后执行，因此不持有提示租约。

两个窗口均 FLAG_NOT_TOUCHABLE / FLAG_NOT_FOCUSABLE，不创建输入区或新授权开关。无线窗口 alpha=0.75，低于 Android 12+ 默认不可信覆盖层触摸遮挡上限，且沿用手机调用期间隐藏聊天悬浮窗的规则。未授予悬浮窗权限的 ADB-only 模式仍可正常执行操作，继续使用原有通知；提示缺权不阻止动作。系统进程 / 服务 token 销毁会回收窗口。观察结果保持屏幕像素与当前坐标系；截图可能包含蓝色提示，不对实时截图裁剪、缩放或抹除。

平台依据：[Android 不可信覆盖层触摸规则](https://developer.android.com/about/versions/12/behavior-changes-all#untrusted-touch-events)、[WindowInsets](https://developer.android.com/reference/android/view/WindowInsets)、[WindowManager.LayoutParams](https://developer.android.com/reference/android/view/WindowManager.LayoutParams)。OEM 对覆盖层、受保护界面及后台显示的额外限制仍需真机验证。

## 应用语言与提示类型（0.17.0）

`values/strings.xml` 是英文默认资源，`values-zh` 提供简体中文。Compose、通知和原生服务窗口统一从 `AppText` 读取 Android 资源；旧状态中的应用文案通过显式 metadata 适配，绝不用于用户输入 / 自定义名称 / 模型正文。纯状态与协议层保持独立于 Android 资源，避免文案决定控制逻辑；无线发现使用 `adbSearching` 状态。快捷消息的来源标记仍是稳定内部值，显示时再本地化。

`AppLanguage` 在 Android 13+ 使用 LocaleManager，并在 manifest 注册 en / zh-Hans；Android 11 / 12 保存选择并包装 Activity / Application 资源上下文。UI 切换后刷新通知频道、通知和服务窗口；运行中的引擎、会话和草稿不重建。保留全部语言资源，不拆语言 APK。

`PhoneActivity` 区分 Viewing / Reading / Using；租约重叠时 Using 优先，其余显示最近一次观察类型。结构预读通过内部标签选择 Reading，不向模型增加工具；明确请求原图仍显示 Viewing。提示英文按产品要求固定，其他界面文案随应用语言变化。顶部使用纯色深色胶囊和单色 Codex 图标，无描边 / 阴影；背景与文字是原生 Canvas，状态变化不增加操作等待或退出动画。

平台依据：[Android 应用语言](https://developer.android.com/guide/topics/resources/app-languages)。
