# Codex Go 界面方向

## 0.8.0：M3 Expressive + Bento

采用原生 Jetpack Compose 的 `MaterialExpressiveTheme` 与 `MotionScheme.expressive()`，统一颜色、形状、字体和交互反馈。浅色使用石色底、青绿色主色、浅蓝 / 桃色 / 淡紫辅助色；深色使用对应的低亮度色面。正文与功能说明保持足够对比度。

- 空对话用 Bento 快捷卡片，大小错落、局部非对称圆角。写代码、操作手机、整理文件只填入可继续编辑的草稿；选择模型打开真实配置入口。
- 收到消息后，主要空间交给对话。输入框固定在底部，阅读区域按会话保存滚动位置，首个原生会话 ID 分配不会重建输入框。
- 顶部独立显示“模型 · 当前模型”和思考档位，侧栏与空状态也有入口。模型来自本机引擎目录，提供名称 / ID 搜索、刷新、引擎默认和模型支持的思考档位。未启动时说明加载条件，并提供启动按钮。
- 模型选择即时保存，下次发送时传给引擎；执行中可以查看，调整入口锁定。浏览其他会话时先显式进入目标，再调整模型和发送。
- 项目使用自适应卡片网格，360dp 常见手机宽度可呈两列，较窄屏幕回退单列。当前项目、项目数量和选中状态来自真实数据。
- 打开侧栏时收起对话输入键盘；输入历史搜索时折叠功能导航，为结果保留空间。导航区限制高度并可滚动，避免短屏或大字号挤掉历史列表。
- 设置、手机能力、分享和工作区使用相同的圆角、图标色块和间距。无障碍与无线调试继续互补并开；宽屏下两个能力面板并排。
- 快捷卡片有按压形状 / 缩放反馈，系统减少动画设置仍由 Compose 处理。模型面板采用单个模型列表和固定完成按钮，支持深色、横屏草稿与大字号。
- 应用、侧栏和悬浮气泡采用 Codex 彩色图标，通知采用官方单色图形。“快捷对话”页管理悬浮窗、通知回复和待处理输入。悬浮对话固定输入区，对话内容独立滚动；运行中输入明确显示排队，不覆盖主界面草稿。

当前工具链固定 SDK 36 / AGP 8.9.2。Material 3 固定为 `1.5.0-alpha12`，使用该版本公开的实验性 Expressive API；官方 `1.5.0-beta02` AAR 要求 SDK 37 / AGP 9.1，此轮不扩大工具链迁移范围。版本元数据与主题 API 已从 Google Maven 原件核对，见 [依赖记录](assets/frontend-dependencies.json)。

参考：[Material 3 Expressive](https://m3.material.io/)、[Motion physics](https://m3.material.io/styles/motion/overview/how-it-works)、[官方 Compose Material 3 发布记录](https://developer.android.com/jetpack/androidx/releases/compose-material3)。

## 交互经验来源

DSH-Go 提供对话 / 历史 / 执行过程的参考，ChatGPT Android 提供移动端对话节奏；OpenMinis 补充工作区、分享、项目、任务和扩展的经验。当前界面独立实现，参考图不作为产品素材。

- [DSH-Go：执行中的对话](https://github.com/ccch1mneyyy/dsh-go/blob/main/docs/assets/f-steer-zh-light.webp)
- [DSH-Go：会话内容](https://github.com/ccch1mneyyy/dsh-go/blob/main/docs/assets/f-remote-zh-light.webp)
- [ChatGPT Android 官方商店](https://play.google.com/store/apps/details?id=com.openai.chatgpt)
- [OpenMinis 展示图](https://github.com/OpenMinis/OpenMinis/blob/1a71df0f381cefde80bf912b723de6f9831bc177/assets/screenshots.png)

后续功能顺序仍见 [缺口盘点](roadmap.md)；这轮优先处理用户指定的前端与模型选择。

## 0.9.0：Shell 连接与悬浮窗口

Android Shell 使用两列 Bento 选项，展示无线、有线、Shizuku 和 root 的独立连接状态。选中方式以青绿填充与勾选图标标识，操作说明和按钮在下方统一区域显示。有线启动命令放入可滚动对话框并提供复制，避免长命令挤满主页面。测试连接只运行固定的 id，确认执行身份。

悬浮对话使用独立的消息卡片、浅蓝会话条、主色发送按钮和紧凑模型信息；输入固定在底部，内容独立滚动，44dp 控制按钮可清晰区分收起和关闭。气泡拖动后靠近屏幕边缘。深色与浅色遵循主界面相同色板。

配对小窗直接显示六位码输入与配对按钮；受保护的系统设置可能强制隐藏普通悬浮窗，此时使用通知输入。与聊天气泡分别管理开关和生命周期。通知配对使用 Shizuku 风格的单回复动作，避免重复发现同一个端口时重建输入通知。

## 0.14.0：快捷控制与连接即用

聊天页增加闪电入口与输入框下的状态胶囊，单击打开快捷面板。面板使用主色启动卡、浅色连接状态卡、淡紫悬浮窗卡和明确的自动准备选项；缺少哪个连接就显示对应入口。核心信息不依赖长列表里的开关。360dp / 130% 字号下允许正文折行，面板内容滚动；配置变化保留面板，状态栏按深浅色提供对应图标。

能力页面移除“本次操作许可”两项重复开关，优先显示实际可用状态。已授予的系统权限与成功建立的连接直接成为 AI 可用能力；需要时仍可暂停本次能力，暂停并不撤销系统授权。普通重开 App 不需要逐一恢复开关。悬浮窗首次系统授权后自动显示，用户主动关闭与权限暂时缺失分别处理。

实际模拟器截图：[浅色快捷控制](assets/quick-controls-0.14-light.png)、[深色 / 360dp / 大字号](assets/quick-controls-0.14-dark-large.png)。使用本机响应夹具，不展示真实账号。

## 0.15.0：操作中的蓝色提示

仅在实际手机观察或操作调用期间显示四周柔和蓝光与顶部居中的小矩形提示，英文固定为 “Codex is using your phone”。原生 Canvas 使用约 1.5dp 蓝色细线与向内 24dp 的透明渐变；提示条 38dp 起、6dp 圆角、12sp Android 系统无衬线常规字重、左右 18dp 留白，深蓝渐变与浅蓝描边。字号随系统缩放，宽度受安全区域限制；状态栏、挖孔、刘海与导航区域由 WindowInsets / WindowMetrics 处理。

参考的是 [Windows 截图](https://i.gzn.jp/img/2026/06/01/codex-windows-computer-use/04.png) 的轻量提示形式，并只读参考相邻 Niri 项目的生命周期。无前置圆点、取消按钮、Esc 提示或退出动画；保留原有聊天与任务控制。中心透明，不接收触摸与焦点。

实际模拟器截图：[竖屏](assets/phone-indicator-0.15-portrait.png)、[360dp / 大字号 / 刘海](assets/phone-indicator-0.15-small.png)、[横屏](assets/phone-indicator-0.15-landscape.png)。均为测试自有页面，无真实账号内容。

## 0.16.0：少文字，更明确的层级

首页只保留「想做什么？」和四个动作，移除口号与卡片描述。模型入口常驻，项目名称只在需要区别项目时显示；启动是明确的主按钮。薄荷绿用于引擎 / 代码，浅蓝用于手机，珊瑚用于账户 / 文件，紫色用于模型 / 快捷对话，黄色标识辅助动作。提高标题与次要状态的字号差，保留非对称圆角与按压形变。

快捷控制移除重复的授权、启动与后台说明，固定标题 / 关闭按钮，只有主体滚动。设置去掉导读段落，修复、配置恢复、历史图片、预读与版本细节按需展开；错误发生时自动展开相关恢复区域。保留有实际影响的确认与当前错误。手机连接在普通字号下并排，大字号时恢复单列；首页大字号使用宽动作行，避免四字标题被挤出孤字。

实际模拟器截图：[首页](assets/ui-0.16-home.png)、[快捷控制](assets/ui-0.16-controls.png)、[设置](assets/ui-0.16-settings.png)、[手机连接](assets/ui-0.16-device.png)、[深色 / 大字号](assets/ui-0.16-dark-large.png)。

## 0.17.0：中英界面与不同观察提示

设置顶部增加淡紫色语言入口，默认跟随系统；可选 English / 简体中文。正常字号横排当前选择，大字号改为两行，保留图标与进入箭头。所有界面文字进入 Android 资源，用户与模型内容保留原文。

保留原有蓝色边缘与操作生命周期，顶部改为深色扁平状态胶囊：石墨黑底、浅色文字、单色 Codex 图标、完全圆角，无渐变、描边或外发光。最小高度 32dp，随字号和内容调整；仍位于状态栏 / 挖孔安全区域下方，横屏同样避让。依据实际调用显示 viewing your screen / reading your screen / using your phone；仅控制操作在重叠时优先，结束后恢复仍在进行的观察类别。

外形与信息密度参考 [Apple 灵动岛 / 实时活动设计](https://developer.apple.com/design/human-interface-guidelines/live-activities)。这是 Android 上的被动状态提示，保持触摸穿透与立即清理。也对照了官方 [Windows 演示（约 45 秒）](https://www.youtube.com/watch?v=MPIAB-8VmCo&t=45) 与 [Mac 演示](https://www.youtube.com/watch?v=D_FCYsshMI4)，最终按本项目的手机屏幕使用胶囊形式。

实际模拟器截图：[英文首页](assets/ui-0.17-home-en.png)、[中文设置](assets/ui-0.17-settings-zh.png)、[语言选择](assets/ui-0.17-language.png)、[英文深色 / 大字号](assets/ui-0.17-settings-en-large.png)、[观察提示](assets/phone-indicator-0.17-viewing.png)。
