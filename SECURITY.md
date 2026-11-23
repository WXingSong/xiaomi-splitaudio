# 安全说明 / Security Notes

`xiaomi-splitaudio` 基于开源项目 [monarchsofchaos004-gif/splitaudio](https://github.com/monarchsofchaos004-gif/splitaudio)（MIT License）二次定制，专为小米澎湃 OS（HyperOS 3 / Android 16）精简优化。请在使用前了解以下权限边界与安全设计。

## 权限与信任边界

- **Shell 路由守护进程（`route/AppRoute.java`）**：以 Android `shell` 用户（UID 2000）运行，由 Shizuku 或内置无线 ADB 客户端启动，用于获取 `MODIFY_AUDIO_ROUTING` 权限以注册系统级 `AudioPolicy` 和独立读写手机内置扬声器音量。
- **临时文件部署（`/data/local/tmp`）**：应用仅向 `/data/local/tmp` 写入 `approute.dex`、`approute.sh`、`approute.ver`、`approute.music`、`approute.btfilter`、`approute.idle` 与 `approute.token`。包名与蓝牙配置在写入前均经过字符清洗与校验，脚本仅将其作为数据读取，不参与 shell 命令拼接；所有文件的信任边界与设备本身的 `shell` / Shizuku 权限一致。
- **零无障碍权限**：本定制版已彻底移除 `BIND_ACCESSIBILITY_SERVICE`，不读取屏幕内容、不监听全局按键事件。
- **可选悬浮窗权限（`SYSTEM_ALERT_WINDOW`）**：仅用于「音量键伴生侧边手机喇叭音量条」（可随时在主界面关闭），窗口采用 `FLAG_NOT_FOCUSABLE`，仅在分流生效且亮屏解锁状态下响应系统媒体音量变更而滑出，关闭分流时随 `MonitorService` 停止彻底解绑。
- **内置无线 ADB 客户端**：密钥对仅保存在应用私有存储目录（`android:allowBackup="false"`，禁止备份）；仅配对并连接本机 `adbd`，不强求开启「USB 调试（安全设置）」（`WRITE_SECURE_SETTINGS` 仅作旧版已授权设备的兼容保留）。
- **100% 纯离线**：`INTERNET` 权限仅用于访问本机 `127.0.0.1:47821` 控制口与本机无线调试 `adbd`，应用本身不发起任何外部网络请求。

## 本机控制口（`127.0.0.1:47821`）

底层守护进程仅绑定回环地址 `127.0.0.1:47821`（局域网与外网不可达）。任何拥有 `INTERNET` 权限的本机应用都可以尝试连接回环端口，因此每个连接必须同时通过**两道校验**后才会处理指令：

1. **随机 Token 校验**：连接首行必须为 `auth <token>`。Token 为应用首次运行时通过 `SecureRandom` 生成的 32 位十六进制随机串，存放在应用私有存储（不备份），启动时经环境变量 `SPLITAUDIO_TOKEN` 传给守护进程，并由 `approute.sh` 写入 `/data/local/tmp/approute.token`（权限 `0600`、属主 `shell`，普通应用不可读），采用常量时间比对（`MessageDigest.isEqual`）。
2. **对端 UID 校验**：守护进程读取 `/proc/net/tcp6` 与 `/proc/net/tcp` 查找客户端源端口归属 UID，仅放行启动它的本应用 UID（`SPLITAUDIO_UID`）、`shell`（2000）与 `root`（0）。启动时会自检 `/proc/net/tcp*` 可读性；验证失败或来源不明一律回复 `denied` 并关闭连接。

- **受控指令集**（单行严格解析，上限 4096 字节，不调用 shell，串行执行，最大并发 8 连接、单连接超时 1.5 秒）：
  - `state` → 返回 `on` / `off` / `paused`
  - `ver` → 返回运行中引擎的 SHA-256 指纹（用于覆盖安装后自动热升级校验）
  - `pause` / `resume` / `shutdown` → 进入 0 CPU 休眠待命 / 唤醒 / 退出进程
  - `setbt <base64|->` / `setidle <0..1440>` → 热同步蓝牙音箱白名单与断开自动停服分钟数
  - `vol get` / `vol set <0..100>` → 读写手机内置扬声器独立媒体音量
  - 其他任意输入均返回 `err`。
- **已知理论边界**：回环接口上的控制指令为明文传输；若恶意进程在守护进程启动前抢先占用 47821 端口，最多只能返回错误状态文本，无法通过 UID 校验控制真实守护进程，且通道内不涉及任何用户隐私数据。清除应用数据或卸载重装会更换 Token/UID，旧守护进程将拒绝连接，点击「彻底停止底层服务」或通过 `sh /data/local/tmp/approute.sh stop` 即可清理。

## 已知限制

- Android 原生 `AudioPolicy` 限制每条 mix 最多 20 个 UID、单个策略最多 50 条 mix（合计最多路由 1000 个应用 UID），超出部分自动保持在蓝牙输出（用户安装应用优先排列）。
- 分流启动后新安装的应用需在下次重新启用路由规则时生效。
- 实机已验证于 Xiaomi 14 Ultra（HyperOS 3 / Android 16）；不同厂商 ROM 对系统 `AudioPolicy` 的兼容存在差异，若策略被系统拒绝或守护进程退出，音频会自动回落到系统默认蓝牙输出，不会造成无声。
