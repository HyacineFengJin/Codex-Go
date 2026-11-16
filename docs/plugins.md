# Plugin API 1

Codex Go 接受独立安装的 Android APK 插件。在「更多 → 插件」或侧栏中管理。安装走 Android 自己的安装器；启用后，工具注册到新对话。关闭立即生效。原有聊天、手机与 Shell 权限不受影响。

插件运行在自己的 Android UID / 进程中。主程序不加载插件 Dex、原生库或网页脚本，也不默认传递聊天记录、账号、工作区路径、屏幕或授权令牌。**模型传给工具的显式参数仍会交给插件**，只启用你信任的插件。插件自身需要的 Android 权限由它自己的 App 申请。

## 可运行示例

[Text Tools 源码](../examples/text-tools) 是无依赖的 Java 示例，仅统计调用参数里的 Unicode 码点、以空白分隔的词和行，不申请网络或设备权限。

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew :text-tools-plugin:assembleDebug
# 输出：examples/text-tools/build/outputs/apk/debug/text-tools-plugin-debug.apk
adb -s YOUR_DEVICE install examples/text-tools/build/outputs/apk/debug/text-tools-plugin-debug.apk
```

在 Codex Go 启用 Text Tools，点击「测试连接」，然后新建对话，例如「用 Text Tools 统计 Hi 喵 的字符和词数」。运行应用需现有 Codex 账号 / 服务；测试连接和 Binder 夹具不调用模型。

## 声明服务

主程序按 `dev.codex.go.action.PLUGIN` 查询服务；不用维护插件白名单。服务必须 exported、enabled、没有绑定权限，且声明以下 metadata，指向 UTF-8 raw JSON：

```xml
<service android:name=".ToolsService" android:exported="true">
  <intent-filter><action android:name="dev.codex.go.action.PLUGIN" /></intent-filter>
  <meta-data android:name="dev.codex.go.PLUGIN_MANIFEST" android:resource="@raw/plugin" />
</service>
```

```json
{
  "apiVersion": 1,
  "capabilities": ["tools"],
  "name": "Text Tools",
  "description": "Local text utilities",
  "timeoutMs": 10000,
  "tools": [{
    "name": "text_stats",
    "description": "Count supplied text",
    "inputSchema": {
      "type": "object",
      "properties": {"text": {"type": "string"}},
      "required": ["text"],
      "additionalProperties": false
    }
  }]
}
```

不按工具数量、描述长度或 schema 大小设置配额。工具本地名匹配 `[a-z][a-z0-9_]{0,31}`，同一插件中唯一；加宿主命名空间后仍需符合模型工具名协议。JSON Schema 根类型必须为 object。主程序不实现完整 JSON Schema 校验器，**插件必须验证每次输入的类型、范围与权限**。未知 API 或必需 capability 会显示为不兼容，不能启用。

名称自动映射为 `plugin_<组件名 SHA256 的前 20 位>_<本地名>`，不与内建工具共用名称。版本、能力声明、描述与 schema 是插件开发者提供的资料，不是系统指令。

## Messenger JSON 协议

使用 Android `Service.onBind()` 返回 `Messenger.binder`，不依赖宿主 SDK。宿主每次调用显式绑定目标组件。`Message.what = 1` 是请求，`replyTo` 是回复地址，`Bundle["json"]` 是以下字符串：

```json
{"apiVersion":1,"id":"UUID","method":"tools/call","params":{"name":"text_stats","arguments":{"text":"Hi 喵"}}}
```

插件以 `Message.what = 2` 回复，同样放在 `Bundle["json"]`：

```json
{"apiVersion":1,"id":"同一 UUID","result":{"content":[{"type":"text","text":"{\"characters\":4}"}],"isError":false}}
```

工具执行失败可以 `isError: true` 返回文字；协议 / 输入错误也可以回复 `{"apiVersion":1,"id":"UUID","error":{"code":"INVALID_REQUEST","message":"原因"}}`。API 1 仅支持 text 内容，不接受图片 URI、文件路径或 HTML 执行。

`method: "ping"` 的 params 是 `{}`，应返回 `result: {"apiVersion":1}`。它用于「测试连接」，不会自动执行某个业务工具。

取消消息为 `Message.what = 3`，JSON 为 `{"apiVersion":1,"id":"同一 UUID"}`，不需要回复。长任务应按 ID 管理可取消的后台工作；服务主线程只接收 / 派发消息，不执行长工作。取消是尽力通知，关闭插件或超时不能撤销已经发生的副作用；宿主不自动重试。

API 1 用 Messenger / Bundle 内联传 JSON，单请求保留 64 KiB、单回复保留 128 KiB（UTF-8）的传输预算，为 UTF-16 Bundle 和 Android Binder 共享事务缓冲留空间。这不是文件大小或工具数量配额；大块数据还需要文件 / URI 通道，当前 v1 尚未实现。回复 text 条数不设配额。

`timeoutMs` 省略或 0 表示不设期限；正数由插件指定，不设 30 秒上限。最多同时执行 4 次请求，其余等待，排队不计入执行期限。用户停止任务或关闭插件仍会取消调用。调用结束、失败、超时或取消后解绑服务；服务死亡只返回工具错误，不结束聊天。

清单读取按当前剩余堆的 1/8 预算逐块检查，防止资源伪报长度或增长时无边界读入；这不是工具数量配额。「测试连接」的 ping 另有 5 秒健康检查期限，不影响正常工具任务的执行期限。

宿主校验回复的 `sendingUid`、协议版本与请求 ID。插件也应通过 `Message.sendingUid` 核对调用者，不信任 JSON 内自报的包名。示例展示如何匹配已安装 `dev.codex.go` 的 UID；插件若需要更严格的发布者认证，可以另行验证宿主签名。不要在 Handler 回调里用 `Binder.getCallingUid()` 代替消息的原始 UID。[Android Messenger / 绑定服务](https://developer.android.com/develop/background-work/services/bound-services)

## 启用与对话生命周期

启用状态绑定服务组件、安装身份、APK 当前签名与原始清单的 SHA256。修改工具、描述、schema、能力或签名需要重新启用；卸载后重装也需要重新启用。保持这些内容不变的同签名普通更新可以沿用启用状态。管理页显示无效清单，不让一个坏插件阻止其他插件被发现。

新对话保存当时的工具目录与启用指纹。旧对话恢复使用原目录；之后新启用的工具需要新建对话。旧目录不意味着继续授权：每次调用仍检查当前启用状态、清单指纹、当前对话与活动 turn；旧 turn、卸载、关闭或变更的工具会被拒绝。禁用也取消在途请求，响应不会作为成功结果继续返回。

插件不依赖手机无障碍、无线 ADB 或 root 的开关。需要网络、文件、第三方登录等额外能力时，由插件自行提供设置入口并处理授权，不借用宿主私有凭据。

## 引擎与后续扩展

描述、进程通信和返回格式位于 `plugins/PluginContract`、`PluginBroker`、`PluginManager`；当前 Codex 返回值映射在独立的 `CodexPluginAdapter`。API 1 不绑定模型名，也不要求插件依赖 Codex 类。0.20.0 另增可选 Antigravity ACP 适配（见 [说明](antigravity.md)）；插件仍使用相同工具协议。Claude Code 引擎尚未实现。

第一版的 capability 为 `tools`。后续若增加资源、界面入口、事件订阅或能力后端，将定义独立 capability、权限边界和版本协商，不让旧插件自动获得新增权限。暂不提供市场、远程下载、页面注入或插件替换内建手机 / Shell 后端。

Codex 的动态工具接口目前仍属实验功能，宿主锁定的运行时为 0.165.0。工具在 `thread/start` 注册，由 `item/tool/call` 路由；升级引擎时需要重跑协议检查。[OpenAI Docs：App Server](https://developers.openai.com/codex/app-server/)

原创接口、宿主实现与示例继承仓库 MIT License；第三方插件保留各自许可。
