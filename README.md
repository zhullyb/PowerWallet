# PowerWallet · 电源键钱包

双击小米手机电源键，直接打开 **Google 钱包刷卡页面**。Windows 图形工具内置 ADB 和手机插件，可安装、激活、检查状态，或恢复原快捷键，无需 root。

## 本 fork：小米 15 / HyperOS 4

**一句话：用 adb shell 把系统设置 `double_click_power_key` 改成 `launch_QAW_wallet`，双击电源键就会打开 Google 钱包。** 插件做的事情，就是在需要的时候替你执行这条命令。

### 原生 Android 的机制

- Android 11 引入了 Quick Access Wallet（QAW）：钱包应用实现一个 `QuickAccessWalletService`，系统就能直接调起它。
- 按 AOSP 的实现，系统并不固定打开某个钱包，而是读取**默认 NFC 付款应用**，再去这个应用里找 QAW 服务。Google Pay 的 QAW 服务由 Play 服务提供，打开的是 Google 钱包。（这一点在小米 15 上尚未单独验证。）
- 原生 Android 还支持「双击电源键打开钱包」，对应 Secure 表的 `double_tap_power_button_gesture=1`。

### 小米做了什么

- HyperOS 用自己的设置 `double_click_power_key`（System 表）决定双击电源键做什么，并把它同步到原生设置上。国行设置界面只提供 `mi_pay`（小米钱包）等选项。
- 系统其实认识 `launch_QAW_wallet`（即原生的「打开钱包」），只是国行把它标记为 `disabledByCustFeature`，设置界面里不显示。
- 开机时 HyperOS 会检查这个值，有时会把 `launch_QAW_wallet` 当成「不支持」清成 `null`，或者填回默认的 `mi_pay`。四次重启里出现过清空、保留、填回 `mi_pay` 三种结果。

### 原项目做了什么

原项目当时还不知道 `launch_QAW_wallet`，所以绕了一个大圈：

1. 把 `double_click_power_key` 改成系统不认识的 `google_pay`，让系统的双击动作失效；
2. 通过 adb 启动一个常驻的 shell 进程，监听电源键事件，自己识别双击；
3. 识别到双击后，用 `am start` 打开 Google 钱包。

这个进程在重启后就没了，所以每次重启都要用 adb 重新激活。在 HyperOS 4 上，`google_pay` 同样会在开机时被系统清掉。

### 本 fork 做了什么

- **改用系统原生的 `launch_QAW_wallet`**。不再需要常驻进程，锁屏验证也交给系统处理。
- **切换模式时同步默认 NFC 付款应用**：Google 钱包模式对应 Google Pay（QAW 依赖它），小米钱包模式对应小米钱包。如果你选的是第三方付款应用，插件不会改动。
- **插件自己执行 adb 命令**。普通 App 无权写这个设置（会报错 `You cannot keep your settings in the secure settings.`），所以插件内置了一个 adb 客户端。在通知栏输入一次无线调试配对码以后，切换模式时插件会临时打开无线调试、连接本机、执行 `settings put`，完成后再关闭无线调试。
- **开机后自动写回**。如果你选的是 Google 钱包模式，开机并连上 Wi-Fi 后，插件会检查这个值：是 `null` 或 `mi_pay` 就写回 `launch_QAW_wallet`，是其他功能（比如相机）就不动。小米钱包模式（`mi_pay`）本身就能保持到重启后，插件什么都不用做。

> [!IMPORTANT]
> **切换模式和开机写回都需要 adb shell 权限（有线或无线调试均可），也就是说那一刻必须开着「开发者选项」和「USB 调试 / 无线调试」。** 插件只是替你临时打开无线调试，并没有绕开这个前提。
>
> 如果你日常会关闭开发者选项（例如 HSBC HK 等银行 App 不允许在开启开发者选项的手机上使用）：
>
> - 关闭期间无法切换模式；
> - 小米钱包模式重启后不受影响；
> - Google 钱包模式在重启后可能被系统清掉，在你重新打开开发者选项、连上 Wi-Fi 让插件写回之前，双击不会打开 Google 钱包；
> - 关闭开发者选项后，已授予的 `WRITE_SECURE_SETTINGS` 和无线调试配对会不会被收回，尚未验证。

### 其他前提与说明

- 需要 Wi-Fi（无线调试只能在 Wi-Fi 下工作）。请为插件允许「自启动」，省电策略设为「无限制」，否则开机广播可能被拦截，切到后台时也会被冻结。
- 插件因此**请求了网络权限**（只连接 `127.0.0.1` 上的 adbd），并持有一把被 adbd 信任的密钥，可以在开发者选项中「撤销 USB 调试授权」作废它。下文原项目关于「APK 不请求网络权限」的说明不适用于本 fork。
- 如果不想用插件，也可以手动执行：`adb shell settings put system double_click_power_key launch_QAW_wallet`。但重启后可能需要再执行一次。
- 已在小米 15 国行 `OS4.0.0.22.XOCCNXM` 上验证：双击打开 Google 钱包（解锁 / 锁屏）、模式切换、NFC 联动、开机写回。尚未验证：开机时没有 Wi-Fi 稍后才连上、全新安装后的完整配对流程、真实读卡器付款。
- 原项目的按键助手（`Helper`）保留，作为不支持 `launch_QAW_wallet` 的系统的后备，可以用 `linux/powerwallet.sh` 构建和激活。

Linux 下构建：

```bash
ANDROID_HOME=/path/to/android-sdk linux/powerwallet.sh build   # 需要 JDK 17+、platform android-35、build-tools
adb install -r linux/build/PowerWallet-linux.apk
```

以下为原项目说明。

---

**目前仅适配：小米 13 Ultra / ishtar，国行 HyperOS `OS3.0.308.0.WMACNXM`，Android 16。** 未适配的设备不会被激活。系统更新后需要重新验证兼容性。

[下载 Windows EXE](https://github.com/DustinYe/PowerWallet/releases/latest) · [完整使用说明](docs/usage.md) · [验证记录](docs/verification.md)

![PowerWallet 深色界面（布局测试数据）](docs/PowerWallet-dark.png)

## Windows 版

- 单个 EXE，内置 Android platform-tools 37.0.1 的 ADB、USB 组件和手机插件 1.0.3。
- 自动检测手机、USB 调试授权、机型、系统版本和 Google 钱包。
- 一键安装并激活、停用并恢复、打开手机插件。
- 深色、浅色、跟随系统三种主题，保存用户选择，标题栏和滚动条同步适配。
- Per-Monitor V2 DPI 感知，按显示器工作区限制初始窗口大小，响应 DPI / 显示器变化。
- 小屏幕自动换行并提供滚动访问，不修改电脑分辨率或缩放设置。
- 状态来自运行中的助手，不能仅凭旧日志判定已激活。
- 1.0.1 修复后台刷新期间操作按钮短暂失效的问题，并明确显示恢复或保留用户新设置的结果。

支持 Windows 10 / 11 x64（.NET Framework 4.8，当前 Windows 10 / 11 通常已内置）。使用标准用户权限即可，不需要管理员运行。

## 使用

1. 从 Releases 下载并运行 `PowerWallet-Setup-1.0.1.exe`。它是便携式工具，不会安装 Windows 服务。
2. 在手机开发者选项中开启 **USB 调试**，使用数据线连接电脑并允许调试授权。
3. 在工具中选择手机，点击 **安装并激活**。如果手机要求确认 USB 安装，请在手机上完成。
4. 看到“钱包快捷键已就绪”后，双击实体电源键测试。

**激活完成后可以拔线；手机重启后必须重新连接电脑激活。** 助手若被系统终止，也需要重新激活。仅安装 APK 不会启动按键助手。

支付仍由 Google 钱包处理，并遵循它的验证和设备安全要求。本工具不读取银行卡、NFC 交易数据或 Google 账户，也不执行付款。真实读卡器交易未在本次开发中测试。

## 手机插件

手机桌面上的“电源键钱包”提供激活状态、打开 Google 钱包、停用并恢复入口。

- 1.0.1：修复人脸验证成功但尚未上滑退出锁屏时无法显示钱包的问题。
- 1.0.2：为电脑端增加实时状态和重启后的恢复命令，按键识别与钱包启动逻辑保持 1.0.1 的行为。
- 1.0.3：移除未对助手开放的锁屏接口依赖，修复普通锁屏分支的偶发报错；助手未运行时状态查询正常返回失败码，不再产生异常崩溃记录。

## 原理与权限

在已验证的这版国行系统中，框架 `google_pay` 快捷操作实现为空，而 `mi_pay` 固定打开小米钱包。ADB shell 助手保存原设置，将双击操作设置为该空操作，再监听实体电源键事件，通过 Google 钱包的 `QUICKDRAW` 入口打开卡片页面。

人脸验证已完成、锁屏页仍显示时，助手请求系统收起锁屏后再启动钱包；系统仍负责验证。工具不修改系统文件，不替换小米钱包，不需要 root 或无障碍服务。

Android APK 不请求网络权限。电脑端仅在本机调用内置 ADB，不包含遥测、自动更新或外网请求。ADB 的本机服务和手机调试传输仍由 Android platform-tools 处理。

## 从源码构建

Windows 端无需 NuGet 或额外 UI 框架，使用 .NET Framework 4.8 自带 C# 编译器和 WPF：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\desktop\build.ps1
.\dist\PowerWallet-Setup-1.0.1.exe --self-test .\artifacts
```

自检离线进行，覆盖兼容性拒绝、未授权、缺少钱包、安装失败、恢复、安全日志和 38 种主题 / 分辨率 / 缩放布局组合；不会激活或更改连接的手机。

Android 端需要 JDK 21、Android SDK Platform 36 与 Build Tools 36.0.0：

```powershell
.\android\Build.ps1 -Sdk 'Android SDK 路径' -Jdk 'JDK 路径' -BuildDirectory '构建目录' -ApkPath '.\desktop\assets\PowerWallet-1.0.3.apk'
```

仓库不包含 APK 签名私钥。自行构建的不同签名 APK 不能直接覆盖正式版；如果切换签名，先恢复原快捷键，再卸载旧插件。正式版内置 APK 的哈希见 `desktop/assets/SHA256SUMS.txt`。

## 许可

本项目自有源码采用 [MIT](LICENSE) 许可。内置 ADB 和 USB 组件遵循其原许可，完整声明保存在 [Android platform-tools NOTICE](desktop/assets/NOTICE.txt)。本项目独立开发，与 Xiaomi、Google 无隶属或背书关系。
