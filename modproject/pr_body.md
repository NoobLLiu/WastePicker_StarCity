## 变更概述

为"垃圾桶探测器"客户端模组添加了完整的垃圾桶自动拾取与内容导出功能。

## 主要功能

- **自动打开垃圾桶 GUI**：检测到垃圾桶刷新消息后自动发送 /trash 指令打开容器
- **容器内容导出**：延迟读取容器槽位内容，导出为 JSON（供 AI 读取）和 Markdown 表格（供人类阅读）
- **#pick 自定义指令**：通过 Mixin 注入聊天屏幕，输入 #pick 回车即可触发自动拾取，消息不发送到服务器
- **可点击文件链接**：在聊天栏发送带有可点击链接的文件路径，方便快速访问导出文件

## 技术实现

- 新增 ChatScreenMixin 注入聊天屏幕拦截 #pick 指令
- 使用 ScreenEvents.AFTER_INIT 监听容器 GUI 打开
- 使用 ClientTickEvents.END_CLIENT_TICK 延迟读取槽位数据
- 添加 Access Widener 使 ChatScreen.chatInputSuggestor 字段可访问

## 修改文件

- TrashCanDetectorClient.java：核心逻辑扩展
- ChatScreenMixin.java：新增 Mixin
- fabric.mod.json：添加 Mixin 配置引用
- trashcandetector.accesswidener：添加字段访问权限
- trashcandetector.client.mixins.json：新增 Mixin 配置文件
