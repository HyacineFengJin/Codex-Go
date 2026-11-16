# Antigravity 适配（0.20.0，实验）

在「设置 → 智能体」选择 Antigravity，启动后使用 Google 账号登录。这里使用官方 `agy acp`，不把 Antigravity 伪装成 OpenAI 兼容接口，也不读取桌面机器已有的登录资料。

## 使用

1. 安装包含可选 Antigravity 运行时的本地构建。
2. 在设置选择 Antigravity，再启动。
3. 点击「使用 Google 登录」，打开链接，完成 Google 登录后，将授权码或最后页面的地址粘贴回应用并提交。
4. 登录完成后，模型与思考档位从官方 CLI 返回的目录读取。选择后新建对话并发送任务。

登录使用官方终端登录流程 `agy acp --login`，适合手机没有桌面浏览器进程的环境。授权码只经进程输入传递，不写入偏好或日志。Google 自己的登录资料保存在独立的应用私有目录 `files/antigravity`，运行时绑定为该引擎的整个 `/root`；Codex 账号目录不绑定给 Antigravity。终端登录结束后重新连接 ACP，使用该目录里的官方登录状态。[Google 登录说明](https://antigravity.google/docs/install?tab=cli)

Codex 的原有账号、模型偏好、历史、草稿和队列继续使用原存储；Antigravity 使用独立的会话偏好与历史。项目文件仍由你选择的项目共享。切换引擎需要先结束当前任务；旧通知目标、临时工具调用和任务状态不会直接转入新引擎。悬浮窗未发送输入也分别保存。

## 接入内容

官方 CLI 固定为 1.5.9，使用 ACP v1 / stdio。适配层映射会话创建 / 加载 / 列表、文字与图片输入、流式文字、工具状态、取消和审批。模型与思考档位来自 `configOptions`，不硬编码模型 ID、不冒充 Zed 或其他官方编辑器来改变模型可见范围。[官方 ACP 文档](https://antigravity.google/docs/cli/acp/)

手机无障碍、Android Shell 与已启用 APK 插件通过私有 loopback MCP 桥接。工具表来自本应用，调用仍检查会话、活动 turn、系统连接和当前许可。临时端点使用随机令牌；账号和授权码不传给插件。审批仅对应本次操作，不把「允许一次」提升为「总是允许」。CLI 本身的命令 / 文件权限规则继续有效。

Antigravity 的归档标记由本客户端保存；并非 Google 云端归档。它的历史图片上下文由官方运行时管理；本客户端的 Codex 历史压缩和 Luna 辅助预读在该引擎下不可用。结构预读取仍可用。运行中补充输入支持排队或中断后发送，本版不开放未经验证的 ACP steering。

## 构建与许可

Google CLI 是可选的上游二进制，不属于本项目 MIT 授权范围，适用 [Google Antigravity 条款](https://antigravity.google/terms)。适配代码属于本项目原创 MIT 部分。默认构建仍仅包含 Codex；可选运行时由 `tools/antigravity.lock.json` 记录官方 URL、版本和 SHA512，准备脚本只下载并核验该固定包。本地构建使用：

```sh
python3 tools/prepare_antigravity.py --abi arm64-v8a
# 先按 docs/testing.md 准备原有基础运行时。
JAVA_HOME=/path/to/jdk21 ./gradlew -PruntimeAbi=arm64-v8a -Pantigravity :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# 或 CODEX_GO_ANTIGRAVITY=1 bash tools/build_apk.sh
```

可选文件作为只读 APK 原生载荷，通过已有 PRoot 路径启动。Android 的直接运行限制不应通过复制到可写目录后直接 exec 来绕过。公开分发构建默认不包含可选 Google CLI；本轮本地验证包不等于已发布新版 Release。

## 验证范围

- 确定性 ACP 夹具：模型 / 思考选择、流式文字、取消、审批、MCP 调用、工作区图片边界、历史持久化及 Google 终端登录 / 取消。
- Android 15 x86_64 独占模拟器：应用 UID 下通过 PRoot 实际启动官方 1.5.9，完成握手并返回预期的 `Authentication required`；不是通过 shell 调试身份冒充应用进程。
- 管理界面：引擎切换、草稿隔离、冷启动持久化、中英、深浅色 / 大字号。

真实 Google 账号授权完成、真实模型任务、账户实际可用模型、ARM64 / OEM 和长时间网络恢复仍需手机验证。本轮没有操作用户手机，也没有调用真实模型。端到端桥接的 Python 工作流已用夹具验证，但未在 ARM64 真机上完成完整登录与任务。

模拟器专用最小 Linux 探针由 `prepare_antigravity_probe.py` 准备，锁文件记录测试所用 Alpine 与 Termux 包；它不进入 ARM64 手机包。
