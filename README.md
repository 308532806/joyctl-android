# JoyCtl Android

JoyCtl Android 是基于 [hexwander/joyctl](https://github.com/hexwander/joyctl) 的安卓版实现。原桌面版通过 PC 侧 ADB 操作已 root 的小米手机；本项目改为在手机本机通过 `su` 读取和写入 Joyose 云控数据库。

## 功能

- 本机 root 检测、设备代号、MIUI/HyperOS 版本和 `persist.sys.sc_allow_conn` 状态读取
- 读取 `/data/data/com.xiaomi.joyose/databases/teg_config.db`
- 编辑 `rules.rule_content` 中的 JSON 策略并写回 SQLite
- 推送修改后的数据库到 Joyose，并在覆盖前生成 `.joyctl.bak` 备份
- 冻结/恢复 Joyose MCC 云控下发：`persist.sys.sc_allow_conn=0/1`
- 复刻 MCC 云控协议，支持按机型代号从小米服务器拉取云端规则
- 一键策略模板：解除 Novatek 帧率锁、放宽 PID 温控、提升 migt 大核基线、清除温度降帧表、关闭监控上报、关闭预下载等
- 支持通过 Android 文件选择器导入/导出 `teg_config.db`

## 使用前提

- 小米/红米/POCO 设备
- 已 root，且 `su` 可被第三方 app 调用
- Joyose 包名为 `com.xiaomi.joyose`
- 修改系统云控可能影响发热、续航和稳定性，请先备份

## 构建

```bash
./gradlew assembleDebug
```

Release APK 由 GitHub Actions 构建并使用仓库 Secrets 中的 keystore 签名。

需要的 Secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

## 许可

MIT。核心协议、数据库结构和策略模板来自原 JoyCtl 项目，已在源码和许可中注明来源。
