# xiaomi-splitaudio

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" align="right" alt="xiaomi-splitaudio icon">

**小米澎湃 OS 蓝牙音频分流工具 — 音乐 App 走蓝牙音箱（原生无损），刷视频等其他 App 走手机喇叭。**

> **基于开源项目二次开发声明 / Derived Work Notice**  
> 本项目基于开源项目 **[monarchsofchaos004-gif/splitaudio](https://github.com/monarchsofchaos004-gif/splitaudio)**（MIT License）修改与深度定制，针对 **小米澎湃 OS（HyperOS 3 / Android 16）** 的日常使用场景进行了架构精简与体验增强，完整保留原项目版权声明与 MIT 开源协议。

> **运行环境**：已在 **Xiaomi 14 Ultra（HyperOS 3 / Android 16）** 实机验证；要求 **Android 12（API 31）及以上**，**无需 Root**、**无需无障碍权限**、**100% 纯离线**。

---

## 解决什么痛点？

手机连着客厅/桌面的蓝牙音箱正在用 QQ 音乐放歌，顺手打开小红书、抖音、B 站或微信刷一条短视频时，系统默认会把短视频声音也一起推送到蓝牙音箱，甚至直接把音乐顶掉。

**xiaomi-splitaudio** 借助系统级 `AudioPolicy`（由 Shizuku 或内置无线 ADB 的 shell 权限注册）实现独立分流：
- **指定音乐 App**（如 QQ 音乐、网易云音乐、YouTube Music 等）：**100% 保留在原生蓝牙 A2DP 通道播放**，零音质与音量损耗；
- **其余所有 App**（小红书、抖音、B 站、微信视频号等）：媒体音频自动走**手机自带扬声器**；
- 配合小米澎湃 OS 自带的 **「音助（多应用媒体音调节 + 允许多个应用同时发声）」**，边放无损蓝牙音乐、边用手机喇叭刷视频互不打扰。

---

## 核心特性

### 1. 原生无损音质 & 固定扬声器分流
- **音乐零接管**：常规播放状态下仅对非音乐 App 注册手机扬声器路由规则，不对音乐 App 施加任何 `AudioMix` 拦截或重采样，保持蓝牙音箱原生解码与动态范围。
- **固定扬声器模式**：开启分流且目标蓝牙音箱已连接期间，非音乐 App 始终走手机喇叭，不因音乐暂停或切歌而切回蓝牙。

### 2. 特定蓝牙音箱白名单 & 断开自动停服
- 支持勾选指定 **1 台或多台蓝牙音箱**（按 MAC / 设备名精准匹配）；连接蓝牙耳机、车载蓝牙等非白名单设备时绝不误触发分流。
- 目标音箱断开连接超过设定时间（默认 2 分钟，支持自定义 0～1440 分钟或关闭）后自动撤回规则、退出前台服务并让底层休眠待命。

### 3. 澎湃原生风格「双入口」手机喇叭独立调音（零无障碍）
- **音量键侧边伴生悬浮音量条（可选）**：
  - 分流期间按实体音量键调整蓝牙音箱音量时，在系统音量条旁自动伴生滑出小米澎湃 OS 侧边栏风格的**竖向圆角胶囊手机喇叭音量条**（顶部等宽数字、底部手机喇叭矢量图标、澎湃蓝液位无缝反色）；
  - 采用 `FLAG_NOT_FOCUSABLE` 悬浮窗，弹出与上下拖动全程**不抢焦点、不暂停底层视频**，轻点底部图标一键静音/恢复，松手或 2.6 秒无操作柔和淡出；
  - 支持在主界面切换 **右侧并排 / 左侧边缘 / 关闭** 并实时预览（关闭分流即彻底解绑，零后台残留）。
- **控制中心快捷磁贴 + 长按毛玻璃小窗**：
  - 下拉控制中心仅占 **1 个「音频分流」磁贴**，**短按**一键开启/停止分流；
  - **长按**弹出原生毛玻璃风格浮窗（`FLAG_BLUR_BEHIND` 实时背景虚化、自动适配深浅色模式、不占最近任务），包含实时状态圆点、澎湃蓝胶囊开关、`54dp` 宽大胶囊滑块（0%～100% 独立调手机喇叭音量、点图标静音、小窗内实体音量键按 5% 微调）、**「更多设置」**跳转主界面与**「完成」**按钮。

### 4. 通话与微信语音防串音
- **运营商来电 / 通话**（`MODE_RINGTONE` / `MODE_IN_CALL`）：0.25 秒内自动撤回分流并暂停音乐，通话结束后自动续播。
- **微信语音 / 视频通话**（`MODE_IN_COMMUNICATION`）：动态启用音乐 App 蓝牙锁定保护，并将通话/铃声用途排除在扬声器路由外、阻止通话通道落到白名单音箱，避免串音。

### 5. 免 Root 双后端 + 0 CPU 休眠待命（日常无 Wi-Fi 秒开）
- **Shizuku 优先，内置无线 ADB 兜底**：未装 Shizuku 时通过内置一次性四步引导完成普通无线调试配对即可，**无需开启小米繁琐的「USB 调试（安全设置）」**。
- **重启后只需激活一次**：底层激活完成后即可关回无线调试；日常关闭分流时底层路由进程进入 `Object.wait()` **0% CPU 休眠待命**，后续开关分流、改白名单与调音量全部通过本机控制口 `127.0.0.1:47821` 10ms 完成，**断 Wi-Fi 也能秒开**。
- **自动版本指纹校验与热升级**：覆盖安装新版 APK 后自动比对运行中底层与内置 `approute.dex` / `approute.sh` 的 SHA-256 指纹；有 Shizuku 或 Wi-Fi 时自动安全热替换升级，离线时保留旧引擎继续工作不中断。

### 6. 零无障碍 & 100% 纯离线
- 彻底移除无障碍服务、多应用混音轮询、系统全局多焦点强制修改、在线查更新、诊断上报与付费模块，**无任何数据离开手机**。

---

## 基于原版 [splitaudio](https://github.com/monarchsofchaos004-gif/splitaudio) 的主要改动

| 维度 | 原版 `splitaudio` | 本定制版 `xiaomi-splitaudio` |
|---|---|---|
| 构建与发行形态 | 拆分 `fdroid` / `play` 双 flavor，含 Pro 订阅与联网更新/诊断上报 | 合并为单一纯净离线版本（Shizuku 优先 + 内置无线 ADB 兜底），零外网请求 |
| 蓝牙设备触发条件 | 连接任意蓝牙音频且音乐在播时触发 | 新增**特定蓝牙音箱白名单** + **固定扬声器模式** + **断开定时自动停服** |
| 音乐音质保护 | 常规分流可能叠加策略影响音乐通道 | 常规播放仅路由非音乐 App，音乐 100% 走原生蓝牙 A2DP；VoIP 期间动态锁定音乐防串音 |
| 日常启停与续航 | 依赖后台常驻监听与反复连 ADB | 下拉磁贴按需启停；关闭后底层 0% CPU 休眠待命，日常无 Wi-Fi 10ms 秒开；免「USB 调试（安全设置）」 |
| 喇叭音量调节 | 依赖无障碍服务拦截音量键 + 每应用混音轮询 | **零无障碍**：新增**音量键侧边伴生竖向悬浮条**（不暂停视频）+ **小米控制中心风格长按磁贴小窗** |
| 多焦点打断控制 | 修改系统全局 `multi_audio_focus_enabled` 并轮询焦点栈 | 移除全局焦点篡改（启动自动清理遗留标志），原生协同澎湃 OS「音助」与音乐 App 多播设置 |
| 底层引擎维护 | 需手动停止后重新部署 | 新增 `approute.dex` SHA-256 自动校验与在线安全热升级 |

---

## 快速上手（小米 HyperOS 3 推荐配置）

### 第一步：系统声音助手配置（推荐，解决视频打断音乐）
1. 打开系统 **设置 → 声音与触感 → 音助**：
   - 开启 **「多应用媒体音调节」**；
   - 开启 **「允许多个应用同时发声」**。
2. 在常用音乐 App 内开启允许同时播放（例如 **QQ 音乐 → 设置 → 允许与其他应用同时播放**）。
3. 建议在系统蓝牙设置中关闭目标音箱的 **「通话音频」**，进一步杜绝电话/微信语音串音。

### 第二步：一次性权限配置（免 Root，二选一）
- **方式 A（Shizuku，推荐）**：启动 [Shizuku](https://shizuku.rikka.app/) 后打开本应用，点击 **「授权 Shizuku」** 即可。
- **方式 B（内置无线 ADB，无需安装 Shizuku）**：
  1. 在开发者选项中开启 **「无线调试」**（无需「USB 调试（安全设置）」）；
  2. 打开应用按四步引导完成一次配对并激活底层引擎；
  3. 激活后可关闭无线调试，日常开关分流均走 0 CPU 休眠待命，无需 Wi-Fi（仅在手机重启后需再激活一次）。

### 第三步：白名单、快捷磁贴与侧边浮条
1. 在主界面 **「选择音乐 App」** 中勾选保留在蓝牙音箱播放的音乐软件；
2. 在 **「指定蓝牙音箱」** 中勾选你的蓝牙音箱（避免连接蓝牙耳机时误分流）；
3. 点击 **「添加到控制中心快捷开关」**，将 **「音频分流」** 磁贴放到下拉控制中心（**短按**开关分流，**长按**弹喇叭音量小窗）；
4. 如需按实体音量键时在屏幕侧边伴生弹出手机喇叭音量条，在主界面 **「音量键伴生浮条」** 授予一次悬浮窗权限并选择 **右侧并排 / 左侧边缘**；
5. 建议将本应用省电策略设为 **「无限制」**（关闭磁贴时前台服务会自动退出，不占后台电量）。

---

## 工作原理

```text
xiaomi-splitaudio App
  ├── 首次点火（每次重启仅需 1 次）：Shizuku / 内置无线 ADB (shell 用户)
  │     └── 部署并启动 /data/local/tmp/approute.sh -> app_process AppRoute (route/AppRoute.java)
  │           ├── 常规分流：注册系统 AudioPolicy（仅将非音乐 App 路由到手机内置扬声器，音乐走原生蓝牙 A2DP）
  │           ├── 微信语音/视频：动态附加音乐 App 蓝牙锁定规则 + 清理白名单音箱通话通道
  │           ├── 运营商通话：0.25s 撤回分流并暂停音乐，挂断自动续播
  │           └── 关闭分流：撤回规则并进入 Object.wait() 0% CPU 休眠待命
  └── 日常控制（0 Wi-Fi / 0 ADB）：本机回环 TCP 127.0.0.1:47821
        ├── 双重鉴权：32 位随机 Token（SecureRandom）+ /proc/net/tcp* Peer UID 校验
        └── 指令集：state / ver / pause / resume / shutdown / setbt / setidle / vol get / vol set
```

- Android `shell` 用户（UID 2000）拥有 `MODIFY_AUDIO_ROUTING` 权限，可注册系统级 `AudioPolicy`（每条 mix 最多 20 个 UID，自动分片支持最多 50 条 mix / 1000 个应用）。
- 底层通过 `IAudioService.getDeviceVolume / setDeviceVolume` 直接读写手机内置扬声器（`TYPE_BUILTIN_SPEAKER`）的独立媒体音量，因此调节手机喇叭音量与蓝牙音箱音量互不干扰。
- **故障安全**：路由进程一旦停止或被杀，系统自动回收其 `AudioPolicy`，所有声音立即恢复正常蓝牙输出，绝不会导致手机静音。

---

## 从源码构建

### 环境要求
- **JDK 17**
- **Android SDK**：`platforms;android-36`、`build-tools`（建议 `36.0.0` 或 `37.0.0`）
- **Gradle 8.9**（仓库自带 `gradlew` / `gradlew.bat`）

### 编译步骤

> 每次修改 `route/AppRoute.java` 后，必须先重新编译生成 `app/src/main/assets/approute.dex`，再打包 APK。

**Windows（PowerShell）：**
```powershell
powershell -ExecutionPolicy Bypass -File route/build_dex.ps1
.\gradlew.bat testDebugUnitTest assembleDebug
```

**Linux / macOS：**
```bash
sh route/build_dex.sh
./gradlew testDebugUnitTest assembleDebug
```

**构建未签名 Release APK：**
```bash
./gradlew assembleRelease
```

---

## 隐私与安全

- **零数据收集、零外网请求**：应用不连接任何远程服务器，不包含统计、广告、热更新或上报模块。
- `INTERNET` 权限**仅用于本机通信**：连接 `127.0.0.1:47821` 底层控制口（随机 Token + `/proc/net/tcp*` Peer UID 双校验，其他 App 访问一律返回 `denied`）以及本机无线调试 `adbd`。
- 详细安全模型与已知限制请参见 [SECURITY.md](SECURITY.md)。

---

## 致谢与开源协议

- 本项目基于开源项目 **[monarchsofchaos004-gif/splitaudio](https://github.com/monarchsofchaos004-gif/splitaudio)** 进行二次修改，感谢原作者与所有贡献者提供的核心 `AudioPolicy` 路由思路与基础实现！
- 使用的第三方开源组件：
  - [Shizuku API](https://github.com/RikkaApps/Shizuku-API)（Apache-2.0）
  - [libadb-android](https://github.com/MuntashirAkon/libadb-android)（Apache-2.0）
  - [Conscrypt](https://github.com/google/conscrypt)（Apache-2.0）
  - [spake2-java](https://github.com/MuntashirAkon/spake2-java)（LGPL-3.0，未修改原样引用）
- 本项目沿用 **[MIT License](LICENSE)** 开源发布。
