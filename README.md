# JoyCtl Android

JoyCtl Android 是基于 [hexwander/joyctl](https://github.com/hexwander/joyctl) 的安卓版实现。原桌面版通过 PC 侧 ADB 操作已 root 的小米手机；本项目改为在手机本机通过 `su` 读取和写入 Joyose 云控数据库。

## 功能

- 底部导航切换：设备、云端、规则、日志；按钮有按压水波纹，点击不再整页禁用
- 设备页按 PC 版对称布局：拉取/推送、冻结/恢复，刷新状态在底部
- 设备信息用机型/代号/系统/Root/云控徽章展示
- 云端拉取优先使用本机 Joyose 版本号；拉不到再按当前机型探测可用配置
- 规则页模板改成 PC 风格卡片：标题、说明、应用/选择游戏；选择游戏会列出本机应用名称和包名
- 说明文案收到各面板右上角 ⓘ，点击后展开
- 读取 `com.xiaomi.joyose` 的 `teg_config.db` 并写回
- 推送前备份 `.joyctl.bak`，推送后回读校验
- 冻结/恢复 Joyose MCC 云控：`persist.sys.sc_allow_conn=0/1`

## 使用前提

- 小米/红米/POCO 设备
- 已 root，且 `su` 可被第三方 app 调用
- 修改系统云控可能影响发热、续航和稳定性，请先备份

## 构建

Release APK 由 GitHub Actions 在 tag `v*` 时签名发布。
