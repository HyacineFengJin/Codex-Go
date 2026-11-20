# 构建与验证

## 输出约定

```sh
bash tools/build_apk.sh             # ARM64，锁定的旧依赖下架时参照 README 指定基础 APK
bash tools/build_apk.sh x86_64      # .gradle/validation/codex-go-x86_64-debug.apk
```

需要 JDK 21、Android SDK 36、Python 3、`ar` 和网络。可用 `CODEX_GO_JAVA_HOME` 指定 JDK。脚本准备 SHA256 锁定的运行时，执行 JVM 测试与 lint，并写入相邻的 APK 元数据 JSON。手机包固定覆盖更新；如果 APK 改变，旧验证汇总会移除，需重新检查。

运行时缓存位于 `.gradle/runtime-bundles/<abi>/`，两个 ABI 独立生成。`-PuiSmoke` 只生成无引擎的界面检查包，不可交付为完整应用。运行时与工具链锁文件是构建输入，必须保留。

旧下载地址下架时可使用 `CODEX_GO_RUNTIME_APK` 和 `CODEX_GO_RUNTIME_APK_SHA256` 复用公开 APK 中完全相同的依赖，示例见 [README](../README.md#构建)。复用先验证整个 APK 哈希、ABI 和输入清单，再仅提取运行时载荷；运行时不通过验证时保留原有生成目录。

## 退出、重建与重启回归

本轮记录见 [0.21.0 恢复自检](lifecycle-selfcheck.md)。新检查脚本：

```sh
python3 tools/check_runtime_reuse.py
python3 tools/check_editor_recovery.py --serial emulator-5570
python3 tools/check_storage_recovery.py --serial emulator-5570
python3 tools/check_lifecycle_recovery.py --serial emulator-5570
# 从已安装的完整 0.21.0 x86_64 包原地升级，并验证 GPT-6.2 Sol / Astra。
python3 tools/check_lifecycle_recovery.py --serial emulator-5570 --upgrade-apk .gradle/validation/codex-go-x86_64-debug.apk
# 安装 v0.17.0 源码的界面测试包后再运行；保留数据升级到当前完整 x86_64 包。
python3 tools/check_upgrade_recovery.py --serial emulator-5570
```

生命周期检查使用完整原生 Codex 与本机 Responses 事件，区别普通 Home / Back 返回、主进程 SIGKILL、应用强制停止和模拟器设备重启；检查草稿 / 项目 / 历史 / 已保存文件恢复、队列暂停且无旧任务重放，以及重启后的新任务。它不会访问用户手机。

编辑器缓冲使用 Activity ViewModel 跨旋转保留，大文本不写入 saved-instance Bundle。硬杀进程或重启不会保存尚未点击“保存”的文件编辑；聊天草稿则持续写入存储。退出编辑器会提示放弃未保存内容。

## 隔离验证

`python3 tools/check_protocol.py` 使用独立临时 CODEX_HOME 检查桌面 app-server，不读取用户账户或启动模型推理。

端到端脚本只接受 `emulator-*`，不可用于真实手机。先准备独占的 Android 15 / x86_64 模拟器，启动到 `emulator-5560`；无线调试检查需要镜像开启 ADB 认证（`ro.adb.secure=1`）。测试使用 localhost Responses fixture，刻意构造假凭据，不调用外部模型推理，结束时恢复配置与系统开关。

```sh
adb -s emulator-5560 install -r .gradle/validation/codex-go-x86_64-debug.apk
bash tools/build_ui_driver.sh emulator-5560
# 历史原生流程夹具使用中文；国际化检查自行切换并恢复语言。
adb -s emulator-5560 shell cmd locale set-app-locales dev.codex.go --locales zh-Hans

python3 tools/check_localization_resources.py
python3 tools/check_localization.py
python3 tools/check_expressive_ui.py
python3 tools/check_floating_ui.py
python3 tools/check_plugins.py
python3 tools/check_quick_chat.py
python3 tools/check_quick_start.py
python3 tools/check_maintenance.py
python3 tools/check_project_flow.py
python3 tools/check_recovery_flow.py
python3 tools/check_repair_failure.py
python3 tools/check_file_flow.py
python3 tools/check_wireless_debugging.py
python3 tools/check_android_engine.py --with-android-shell --device-code
python3 tools/check_phone_flow.py
python3 tools/check_phone_indicator.py --backend accessibility
python3 tools/check_phone_indicator.py --backend adb
python3 tools/check_phone_actions.py
python3 tools/check_phone_actions.py --adb
python3 tools/check_pre_read.py
python3 tools/check_model_pre_read.py
python3 tools/check_history_images.py
python3 tools/check_history_images.py --builtin-openai
python3 tools/check_share_flow.py
python3 tools/check_configuration.py
python3 tools/check_background_flow.py

python3 tools/check_shell_access.py
python3 tools/check_wireless_debugging.py --overlay
python3 tools/check_android_engine.py --with-android-shell --shell-backend wired

python3 tools/verify_release.py 0.19.0
```

无线配对检查应先于 Android Shell 检查。`check_shell_access.py` 需要测试设备安装并启动官方 Shizuku 13.6.0，验证有线桥接令牌、真实 Shizuku UserService、切换许可、服务死亡与无 root 情况；测试会停止 Shizuku，继续验证时需重新启动。root 成功授权和 su 管理器兼容仍需真实已 root 设备。

默认汇总要求完整回归。只复查本轮改动时，可显式传入 `--groups phone-actions phone-actions-adb pre-read phone-flow`；报告会标记 `scope: targeted`，不把它称作完整回归。无线配对的安全模拟器要实际检查 `adb shell getprop ro.adb.secure` 为 1；启动器的 `-prop ro.adb.secure=1` 会被部分版本忽略，不可只凭命令参数判断。`phone-actions-adb` 则只接受 ro.adb.secure=0 的隔离模拟器，测试本机 5555 明文调试后端，不绕过真实设备授权。可选 `--device-code` 只检查设备码申请与取消，不替用户完成登录或保存验证码。

| 检查 | 验证范围 |
| --- | --- |
| expressive-ui | 浅色 / 深色、模型未启动说明、草稿动作、横屏、360dp 项目网格、130% 字号、侧栏收起键盘、预读摘要 / 耗时与原始报告 |
| quick-chat | 独立应用悬浮窗、拖动 / 熄屏隐藏、任务结束前的流式回复、首个原生 ID、排队、打开 App、真实系统 RemoteInput、合并刷新后的最终通知回复、断网保留、重启与显式合并 |
| maintenance / repair-failure | 全分页搜索、键盘搜索布局、归档恢复、保留数据修复、真实启动失败、诊断隐私、假凭据退出 |
| project-flow | 原生 cwd / Git、同名文件隔离、草稿 / 分享 / 历史归属、导出分享与中断恢复 |
| recovery-flow | 搜索真实目录模型、模型 / 思考参数进入请求、运行中锁定、401 / 断流、离线草稿、真实文件审批及大差异守卫 |
| file-flow | 真实文件 / 图片选择器、工具链执行、预览 / 导出 / 分享、插话、排队 / 撤回与中断后发送 |
| wireless-debugging / android-engine | 通知配对与 mDNS、TLS ADB、原生 Codex 与工作区命令、Android Shell、关闭 Shell 后 Computer Use 仍可用 |
| shell-access / pairing-overlay | 有线桥接与错误令牌拒绝、官方 Shizuku 身份与 Binder 生命周期、root 不可用守卫、配对小窗生命周期、受保护设置隐藏时的通知回退与 TLS 配对 |
| history-images | 原生旧截图导入、真实模型请求中的 JPEG 压缩、MCP 原图逐字节一致、实时截图 / 坐标保留、历史读取后的帧失效、重启 / 会话隔离、读取工具不可用时保留原图、关闭后恢复原生链路；使用 localhost 夹具 |
| history-images-openai | 相同完整链路，切换为原生内置 OpenAI + 假 API key；检查受支持的地址覆盖、HTTP 回退和认证头传递，避免只有自定义服务通过 |
| quick-start | 聊天页直接启动、系统授权直接给 AI、自动冷启动与 ADB 重连、Shell 方式持久化、悬浮窗选择 / 权限恢复、关闭自动准备、深色 / 360dp / 130% 字号与设置回退入口 |
| phone-actions / phone-actions-adb | 持续刷新测试页面、无观察点击、旧编号连续点击、焦点变化后输入、导航 / 滑动、超过 30 秒仍可点击；检查实际按钮计数、输入内容、超界拒绝与同帧缓存失效；两个动作后端分别验证 |
| model-pre-read | 独立模型与会话、辅助请求没有工具 / 额外任务历史、辅助预览、同帧原图、完整语义跳过模型 / 显式截图绕过、非法输出 / 工具调用 / 服务错误 / 超时回退、旧结果丢弃、不确定项附图与撤销许可取消；使用 localhost 模型夹具，实际 Luna 和账号仍待测 |
| pre-read | WebView 无障碍语义先行、禁用状态与原始文字、同帧原图传入 Codex、重复读取一致、页面变化后拒绝旧图、显式新截图、带标签图形附图、占位 / 错误 / 标题 / 状态 / 数值、输入法窗口覆盖、布局改变后的旧图失效与本地耗时；不验证真实模型判断或 token 节省 |
| phone-flow | 观察、接管、悬浮控制、暂停恢复、锁屏门控、完成通知与短命令审批 |
| share-flow | 独立 ContentProvider / 系统 Sharesheet、URI 授权、文字 / 多文件、重复名、超限、冷启动、进程重启、忙碌任务与实际图片输入 |
| configuration | 损坏配置、逐字节备份、恢复 / 撤回、账户 / 历史 / 草稿 / 工作区保留 |
| background-flow | A 执行时浏览 B、B 的材料 / 旋转 / 草稿、A 的审批 / 队列、失败、重启、通知返回和过期动作 |

UI driver 的 instrumentation 指向自身，并使用 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`。普通 UiAutomator dump 会抑制其他无障碍服务，不能据此判断能力断开。输入通过 ACTION_SET_TEXT 替换完整字段，能力开关通过语义标签定位，滚动后确认可见区域。

此前 0.12.1 仅交付 ARM64 包，通过 91 项 JVM 测试与 lint，并在 Android 16 真机上手动验证原生引擎启动和原有多截图历史恢复。真机检查没有调用模型推理，核对原始历史前缀、草稿 / 队列 / 重试、账户配置与凭据保留；未重跑 x86_64 APK 或模拟器组。该定向报告与下面完整模拟器汇总的范围不同。

0.13.0 的定向组合覆盖历史图片、原生引擎、结构预读和辅助模型预读。0.13.1 定向复查自定义服务和内置 OpenAI 的完整历史图片链路，两个架构执行 108 项 JVM 测试与 lint。内置服务夹具使用假 API key，分别检查原生认证头保留与 HTTP 回退；不把 localhost 成功称作真实 ChatGPT 登录成功。测试只操作自建模拟器。真实账号和 workspace routing 按 [手动步骤](release.md#手动验证) 验证。

## 报告与清理

原始截图、请求和各组 result.json 位于 `.gradle/validation/reports/`，仅完整成功后才写结果。验证汇总核对两个 APK 哈希、实际安装包、报告新鲜度、版本、JVM 结果与 lint，并将各组结果嵌入 `artifacts/verification.json`。

```sh
python3 tools/clean_project.py
```

清理移除构建、下载运行时、临时验证包、原始报告、Kotlin / Python 缓存；保留源码、锁文件、当前手机 APK / 元数据与验证汇总。测试模拟器可按需要重新创建。真实手机使用 `adb -d install -r artifacts/codex-go.apk` 安装，安装与首屏启动不代表完整引擎、账户和推理已验收。

0.13.2 定向复查无障碍 / ADB 动态页面动作、结构预读的同帧图片、暂停 / 继续和锁屏门控；该版两个架构均执行 110 项单元测试与 lint；0.15.0 增加提示租约测试，0.15.0 为 113 项，0.17.0 为 115 项。

0.14.0 新增 `quick-start`，用 `d.start(auto_prepare=None)` 验证产品默认自动准备，确认系统授权后无需第二个 AI 许可开关，持久保存 Shell 方式，并实测重启 / 悬浮窗权限恢复。旧的显式启动场景使用 `AndroidDriver.start()` 的手动准备配置，避免自动启动改变各自的起点；`capability()` 只读取实际可用状态或执行显式临时暂停 / 恢复，不给系统授权。

本轮定向汇总使用 `--groups quick-start quick-chat phone-actions phone-actions-adb phone-flow model-pre-read`，不把它称为完整回归。

## 控屏提示

`IndicatorDriver` 是仅安装到独占模拟器的 instrumentation，目标为当前 APK，不在产品中增加测试 Activity、广播、特权或控制入口。通过反射使用生产窗口与协程，验证重叠 / 重复退出、异常、取消、旧 finally、真实无障碍观察与手势、服务 interruption / unbind、以及无 finally 的进程死亡。主机在窗口显示时向测试页面的提示条下面点按和输入，校验点击计数、焦点与文字；拍摄四边、360dp / 160% 字号 / 刘海和真实横屏的像素截图。`--backend adb` 检查普通悬浮窗的透明度、触摸和清理；实际 ADB 手机调用另由 native `phone-actions-adb` 覆盖。需要 Python Pillow。

无障碍检查刻意关闭 SYSTEM_ALERT_WINDOW，以验证不增加二次授权；ADB 检查刻意关闭无障碍。测试只改独占模拟器，结束恢复设置。执行中间 PNG、instrumentation 与显示信息在 `.gradle/validation/reports/phone-indicator-*`，最终紧凑结果由 verify_release 收集。

## 0.16.0 的界面检查

此轮的 x86_64 检查使用 `-PuiSmoke`，手机交付仍为包含完整运行时的 ARM64 包。仅为界面包生成 `uiSmoke: true` 的元数据，使用 `verify_release.py 0.16.0 --groups expressive-ui` 汇总；收集器拒绝把界面包用于原生引擎检查。记录的 scope 为 ui，不代表真实模型或本轮原生引擎端到端流程已验证。ARM64 的运行时从已校验的既有完整包恢复，应用由本轮源码重编；输入包 SHA256 记录在元数据中。

新版 UI Driver 同时读取展开状态，按实际状态打开高级选项，避免为了测试恢复旧版说明文字。界面检查验证草稿 / 项目真实动作、模型入口、快捷对话开关、设置折叠、固定关闭按钮与深浅色 / 横屏 / 360dp / 160% 字号。

## 0.17.0 的国际化与分类提示检查

`check_localization_resources.py` 检查中英资源完整性、位置参数与未迁移的界面文案。`check_localization.py` 通过真实语言入口验证中英 / 跟随系统、LocaleManager 同步、冷启动、草稿与用户项目名保留、深色 / 360dp / 160% 字号；模拟器 instrumentation 额外检查资源格式化、第三语言回退、旧状态迁移与通知 / 频道刷新。该驱动只绑定服务，不启动 Codex。

`IndicatorDriver` 同时检查三种提示类别和重叠优先级；原有真实无障碍截图、手势取消、服务断开、穿透与进程退出检查保留。UI_SMOKE 可用于这四组界面 / Android 覆盖层检查，不可用于原生模型流程：

```sh
python3 tools/verify_release.py 0.17.0 --groups localization expressive-ui phone-indicator-accessibility phone-indicator-adb
```

当前手机包的运行时来自校验过的 0.16.0 APK，177 个运行时条目逐字节比对，来源哈希见交付元数据。Android 11 / 12 兼容分支只完成编译，本轮运行环境为 Android 15。

## 0.18.0 悬浮窗定向检查

`check_floating_ui.py` 在独占模拟器中绑定真实 EngineService，用测试状态展示生产悬浮窗，不启动 Codex 进程。检查拖动吸边、空白输入、独立持久草稿、嵌套观察隐藏、忙碌排队提示、对话切换后保留输入、配置 / 语言变更、深浅色、模糊关闭回退、360dp / 160% 字号与横屏键盘。键盘检查使用实际 IME 区域边界，防止控件虽存在于节点树却被键盘遮住。两类顶部提示覆盖层仍单独检查触摸穿透与生命周期。

```sh
python3 tools/verify_release.py 0.18.0 --groups floating-ui phone-indicator-accessibility phone-indicator-adb
```

这一轮为 UI_SMOKE 定向回归，不代表实际账号、模型推理或 OEM 兼容性已验证。真机 ARM64 包包含原有运行时；模拟器 UI 包仅用于隔离检查。

## 0.19.0 插件检查

先构建 `:text-tools-plugin:assembleDebug`，在独占模拟器安装 Text Tools 示例 APK，再运行 `build_ui_driver.sh` 和 `check_plugins.py`。测试驱动另带故障服务，仅存在于测试 APK。检查实际 Binder / UID、连接测试、Unicode 输出、错误输入、坏 JSON、超量回复、超时、服务进程死亡、关闭取消、旧授权拒绝、启用持久化、主程序 `item/tool/call` 分发、旧 turn 拒绝及不依赖手机授权。管理页检查中英、深浅色、大字号、冷启动与宿主停用期间卸载重装后的重新启用要求。

该组使用内存 JSON-RPC 管道检查主程序分发，不启动原生引擎、不调用真实模型。它支持 UI_SMOKE：`python3 tools/verify_release.py 0.19.0 --groups plugin-system`。真实模型自主选择插件、第三方厂商 APK 与 OEM 长期兼容性仍需后续验证。

## 0.20.0 Antigravity

```sh
python3 tools/check_antigravity_protocol.py
python3 tools/prepare_antigravity.py --abi x86_64
python3 tools/prepare_antigravity_probe.py
JAVA_HOME=/path/to/jdk21 ./gradlew -PuiSmoke -Pantigravity :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# 将 UI 包安装到独占模拟器，并准备 build_ui_driver.sh 后：
python3 tools/check_antigravity_ui.py
python3 tools/verify_release.py 0.20.0 --groups antigravity
```

模拟器专用最小 Alpine / PRoot 探针验证应用 UID 下执行官方 CLI 的生产启动方式；不能用 ADB shell 的执行权限代替。直接从应用进程启动 Linux CLI 会受到 Android seccomp 限制（SIGSYS）；生产启动与探针均通过 PRoot。最小探针不包含完整开发环境、不调用模型、不登录真实账号，也不进入 ARM64 分发包。真实 Google 终端授权链接已在隔离桌面环境生成并取消，未完成账号授权。

手机手动验证：选择 Antigravity → 启动 → 使用 Google 登录 → 打开链接并回填授权码 / 最终页面地址 → 选择实际可用模型 → 新建对话发送一个只读任务，再检查取消、审批与模型输出。无需清除 Codex 数据。Google CLI 的不同客户端可能拥有不同模型目录，以实际返回为准。

## 0.20.1 限制清理

ARM64 完整构建执行 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`：128 项 JVM 测试通过，lint 无错误；`python3 tools/check_antigravity_protocol.py` 的 8 项 ACP 夹具与本地化资源检查通过。179 个基础运行时与可选 Google CLI 载荷条目，与已校验的 0.20.0 本地包逐项 SHA256 一致。没有沿用旧版模拟器结果作为本版实测；精确范围见 `artifacts/verification.json`。

定向回归覆盖 40 个附件合并、65 MiB 分享文件完整移入、25 条待发送队列、长消息持久化、长流式回复首尾、完整长 diff 审核、40 张历史图原图关联、超过旧期限的任务等待 / 取消、长 Shell 命令、大插件目录、超过 256 KiB 的 UTF-8 源码，以及 ACP 官方历史游标分页和循环游标错误。

手机手动检查：安装 0.20.1 后发送超过 8 个文件；任务运行时排队超过 10 条；让手机任务停留思考超过 3 分钟后继续操作。USB 后端需用设置中重新生成的命令启动 V2 桥，再验证显式长期限 / `timeoutMs=0` 与停止按钮。保留边界见 [限制审计](limits.md)。本轮不操作用户手机，不完成真实账号授权或模型调用。

## 0.20.2 边界自检

140 项 JVM 测试、13 项 ACP 本地夹具、本地化资源检查和 lint 通过。新增真实 socket 阻塞取消、UTF-8 USB V2 管道 / 串行执行 / 排队取消、续期保活和原文分页往返等回归。Android 15 x86_64 192 MiB 堆上先复现长回复布局 OOM，再验证修复后 1,440,031 字符回复与 500 条队列可用，全文搜索能到末尾，输入法不遮翻页操作，系统 SAF 导出的文本逐字节等于输入。实际 APK daemon 在 Shell UID 2000 下验证超过 64 KiB 的中文命令帧、连续命令和取消后重试。见 [复现与范围](limit-selfcheck.md)；真实账号、模型、ARM64 / OEM 和 Shizuku / root 长任务未据此宣称通过。
