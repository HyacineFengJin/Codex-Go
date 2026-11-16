# 解除限制后的自检（0.20.2）

2026-11-14。仅操作自建 Android 15 x86_64 模拟器和主机测试，不安装或操作用户手机。

| 问题 | 修复与证据 |
| --- | --- |
| 取消 64K 截断后，长回复直接生成大量 Compose 文本节点 | 实际复现：1,440,031 字符回复与 500 条队列在 192 MiB 堆上触发 `OutOfMemoryError`。改为明确的行内预览、全文分页 / 搜索 / UTF-8 导出；队列、附件和大工具列表按可见项布局。原数据不裁剪。 |
| USB 命令还在清理时，下一命令触发旧单命令门闩 | 执行由串行工作队列管理，读取线程仍能响应取消和 ping；排队取消不再触发连接断开。覆盖快速连续命令、取消后立即重试、取消尚未运行的命令。 |
| 无期限任务遇到 socket 写入阻塞时，协程取消不等于关闭连接 | 取消关闭对应 socket 来解除底层阻塞；取消控制帧只做有限时间的尽力发送，不能在 NonCancellable 清理中永远等待。 |
| 安静运行的长任务超过一次性 30 分钟保活 | 只在任务 / 登录期间续期有限 OS 租约，任务结束、服务关闭及协程提前取消时释放；快速停止再启动不会被旧回调释放新租约。虚拟时钟覆盖一个多小时。 |
| ACP 引擎不确认用户停止，取消总期限后可能永久 busy | 只在用户点停止后等确认；5 秒未确认则关闭失联引擎、暂停队列并要求检查副作用。进程退出有 SIGTERM → SIGKILL 清理，丢弃晚到输出。正常任务没有这条执行截止。 |
| 插件清单取消固定大小上限、文件在检查后增长 | 按剩余堆的 1/8 预算逐块读取并严格解码 UTF-8，不再依赖一次 `length()` 检查。普通大文件和大型插件目录仍支持；无法容纳时明确报错。 |

140 项 JVM 测试与 lint 通过。13 项 ACP 测试使用本地夹具，包含忽略取消、取消发送阻塞 / 断管、存储失败、陈旧停止请求，以及拒绝 SIGTERM 的真实子进程，不使用 Google 账号或模型。

模拟器回归使用 `tools/check_limit_regression.py` 和仅限模拟器的 `LimitUiDriver`，检查大回复 + 500 条队列、全文首尾与搜索、输入法弹出时翻页，以及通过系统文件选择器导出全文后按字节比较。`tools/check_wired_v2.py` 实际启动 APK 内的 USB daemon，验证 Android Shell UID 2000、超过 64 KiB 的 UTF-8 命令帧、连续命令、取消后立即重试和 ping。它不冒充真机或完整 Shizuku / root 回归。

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew -PuiSmoke :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# 安装到自建模拟器并安装工具 driver 后：
bash tools/build_ui_driver.sh emulator-5570
python3 tools/check_limit_regression.py --serial emulator-5570
python3 tools/check_wired_v2.py --serial emulator-5570
python3 tools/check_antigravity_protocol.py
```

精确 APK 哈希、导出校验值及前后结果保存在 [验证记录](../artifacts/verification.json)。ARM64 包的 179 个基础 / Google 运行时条目与已校验的 0.20.1 包逐项一致。真实账号、真实模型任务、ARM64 / OEM、Shizuku / root 长任务和长时间真实网络恢复仍待手动验证；不宣称不存在任何边界 Bug。
