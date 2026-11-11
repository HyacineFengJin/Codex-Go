# 构建与验证

## 输出约定

```sh
bash tools/build_apk.sh             # artifacts/codex-go.apk：当前 ARM64 手机包
bash tools/build_apk.sh x86_64      # .gradle/validation/codex-go-x86_64-debug.apk
```

需要 JDK 21、Android SDK 36、Python 3、`ar` 和网络。可用 `CODEX_GO_JAVA_HOME` 指定 JDK。脚本准备 SHA256 锁定的运行时，执行 JVM 测试与 lint，并写入相邻的 APK 元数据 JSON。手机包固定覆盖更新；如果 APK 改变，旧验证汇总会移除，需重新检查。

运行时缓存位于 `.gradle/runtime-bundles/<abi>/`，两个 ABI 独立生成。`-PuiSmoke` 只生成无引擎的界面检查包，不可交付为完整应用。运行时与工具链锁文件是构建输入，必须保留。

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

python3 tools/verify_release.py 0.16.0
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
