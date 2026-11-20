# 第三方组件

当前项目未复制旧 Codex Mobile 或 DSH-Go 业务代码。原创部分采用 [MIT License](../LICENSE)；第三方代码、程序、依赖和素材保留各自原许可，不随本项目重新授权。完整文本、原始声明和版本来源见 [licenses](../licenses/README.md)。

| 组件 | 用途 / 上游 |
| --- | --- |
| OpenAI Codex 0.171.0 | 官方 ARM64 package，Apache-2.0；https://github.com/openai/codex |
| Termux PRoot | Android Linux 兼容，GPL-2.0；https://github.com/termux/proot |
| libtalloc | PRoot 依赖，LGPL-3.0-or-later；https://talloc.samba.org/ |
| libandroid-shmem | Android 共享内存适配，BSD-3-Clause；https://github.com/termux/libandroid-shmem |
| Alpine 3.22.1 minirootfs | 各组件分别授权，包括 BusyBox GPL-2.0；https://alpinelinux.org/ |
| Alpine v3.22 工具包及依赖 | Python 3 / pip、Node.js / npm、Git 等；精确版本、组件 license、origin 和 SHA256 见 `tools/toolchain-<ABI>.lock.json`。二进制来自 https://dl-cdn.alpinelinux.org/alpine/ ，对应打包源见 https://gitlab.alpinelinux.org/alpine/aports/-/tree/3.22-stable |
| LibADB Android 3.1.1 | TLS ADB / 配对，Apache-2.0 或 GPL-3.0 双重许可；https://github.com/MuntashirAkon/libadb-android |
| Shizuku API / Provider 13.1.5 | UserService 授权与 Binder 生命周期，MIT；https://github.com/RikkaApps/Shizuku-API |
| Conscrypt 2.5.3 | Android TLS，Apache-2.0 及其附带的原始声明；https://github.com/google/conscrypt |
| Bouncy Castle | ADB 身份证书生成；https://www.bouncycastle.org/licence.html |
| Jetpack Compose / AndroidX | 原生界面，Apache-2.0；https://developer.android.com/jetpack/compose |
| Kotlin / Coroutines | 业务代码与异步任务，Apache-2.0；https://kotlinlang.org/ |
| Codex 图标 | 从本机已安装的官方桌面 App 提取彩色 PNG 与单色 SVG 路径，用于应用识别；归 OpenAI 所有。具体资源名与哈希见 [来源记录](assets/codex-icon-source.json)，产品参考 [Codex app](https://openai.com/index/introducing-the-codex-app/)。与上面的 Apache-2.0 代码许可分别记录。 |

运行时输入、精确版本与哈希见 `tools/runtime*.lock.json` 和 `tools/toolchain-*.lock.json`，打包后的公共 CA bundle 哈希另记在生成的 manifest 中。PRoot 与 libtalloc 的版本化 SONAME 做等长替换，以适配 Android native library 文件名。

公开 Release 随 APK 提供第三方源码包和校验文件：包含运行时依赖的原始源码、精确 Alpine / Termux 构建配方与补丁、Codex voice 原始构建脚本，以及许可证和声明。原始来源、版本与哈希见 [组件清单](../licenses/components.json)，下载入口见 [许可材料说明](../licenses/README.md)。Codex 自带但当前未在界面启用的 voice 依赖也保留原始许可证与源码。
