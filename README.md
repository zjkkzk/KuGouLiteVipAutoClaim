# 酷狗概念版VIP自动领取 Xposed 模块

每次启动酷狗概念版时自动检查并领取每日听歌会员，**阻止领取弹窗出现，全程无感领取**，**可选自动升级至概念版VIP**。

## 功能

- **主动领取每日听歌会员** — 酷狗概念版启动时自动调用官方接口领取，不依赖弹窗
- **阻止领取弹窗** — PopupBlocker 在弹窗显示前拦截，弹窗根本不出现
- **自动升级概念版VIP**（可选）— 领取后自动升级至概念版VIP，享受更高音质
- **凭证自动捕获** — Hook 酷狗 OkHttp 请求，自动提取登录凭证
- **每日去重** — 同一天不会重复领取
- **手动触发** — 在模块APP中点击按钮可手动触发领取
- **状态通知** — 领取结果通过系统通知提醒

## 工作原理

```
酷狗概念版启动
    │
    ▼
MainHook.handleLoadPackage()
    │
    ├── PopupBlocker.install()       ← 安装弹窗阻止器
    │       拦截 Dialog.show / WindowManager.addView / startActivity
    │       含"领取/签到"关键词的弹窗直接阻止，不显示
    │
    ├── AutoClicker.install()        ← 安装自动点击器(fallback)
    │       如果弹窗突破阻止仍出现，自动点击领取按钮并关闭弹窗
    │
    ├── NetCapture.install()         ← Hook OkHttp Request.Builder.build()
    │       捕获 kugou.com 请求中的 token/userid/dfid/mid
    │
    └── Hook Application.onCreate()
            │
            ▼
      ClaimWorker.start()
            │
            ├── 检查重置标记（模块APP手动触发时设置）
            ├── 1. 读取缓存凭证 (PrefStore)
            ├── 2. 备用：遍历酷狗 SP 查找凭证
            ├── 3. 等待 OkHttp 捕获凭证
            │
            ▼  凭证就绪
      attemptClaim()
            │
            ├── 检查模块开关 (双通道：ContentProvider + 直接读文件)
            ├── 检查今日是否已成功领取（北京时间）
            │
            ▼
      KugouApi.claimVip()             ← POST /youth/v1/recharge/receive_vip_listen_song
            │
            ├── status=1 → 领取成功
            ├── error_code=131001 → 今日已领取
            ├── error_code=20028 → 账号风控
            │
            ▼  (code=0 或 code=1 时执行)
      KugouApi.upgradeVip()           ← POST /youth/v1/listen_song/upgrade_vip_reward
            │
            ▼
      NotifyUtil → 发送通知
      Settings.putStatus → 回写状态到模块APP
```

### 弹窗处理策略

模块采用**阻止优先 + 点击兜底**的双重策略：

| 层级 | 模块 | 作用 |
|------|------|------|
| 第一层 | PopupBlocker | 在弹窗显示前拦截，`setResult(null)` 阻止显示 |
| 第二层 | PopupBlocker | 漏网弹窗强制 `dismiss` + `cancel` + `setVisibility(GONE)` |
| 第三层 | AutoClicker | 如果弹窗仍出现，自动点击领取按钮，点击后自动关闭弹窗 |

PopupBlocker 拦截的弹窗类型：

| 拦截点 | 覆盖场景 |
|--------|---------|
| `Dialog.setContentView` | 标记含"领取/签到"关键词的 Dialog |
| `Dialog.show()` before | 阻止已标记弹窗显示 |
| `Dialog.show()` after | 漏网弹窗强制关闭 |
| `WindowManagerImpl.addView` | 底层拦截浮层添加到窗口 |
| `Activity.startActivity` | 阻止活动页 Activity 启动 |
| `PopupWindow.showAtLocation` | 阻止 PopupWindow 弹窗 |

## 关键接口

| 接口 | 方法 | 路径 |
|------|------|------|
| 领取听歌VIP | POST | `https://gateway.kugou.com/youth/v1/recharge/receive_vip_listen_song` |
| 升级概念版VIP | POST | `https://gateway.kugou.com/youth/v1/listen_song/upgrade_vip_reward` |

### 签名算法

```
signature = MD5(salt + sorted_kv_string + body + salt)
```

- `salt` = `LnT6xpN3khm36zse0QzvmgTZ3waWdRSA`（概念版专用）
- `sorted_kv_string` = 参数按 key 字母序排列，拼接为 `key1=value1key2=value2...`
- `body` = 请求体（本接口为空）

### 公共参数

| 参数 | 值 |
|------|-----|
| appid | 3116 |
| clientver | 11440 |
| clienttime | 当前时间戳（秒） |
| mid | 设备标识 |
| dfid | 用户设备ID |
| uuid | - |
| token | 登录令牌 |
| userid | 用户ID |

## 构建方法

### 环境要求

- JDK 17
- Android SDK（compileSdk 34，build-tools 34.0.0 或 36.0.0）
- Gradle 8.7+
- AGP 8.6.0

### 使用 Android Studio 构建

1. 用 Android Studio 打开 `KuGouLiteVipAutoClaim` 目录
2. 等待 Gradle 同步完成
3. `Build → Build Bundle(s)/APK(s) → Build APK(s)`
4. APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`

### 使用命令行构建

```bash
# 使用本地 Gradle 8.7 构建
gradle clean :app:assembleDebug

# 或使用 Gradle Wrapper（需要先生成）
./gradlew clean assembleDebug
```

### 构建配置说明

项目针对本地环境做了以下适配（见 `gradle.properties` 和 `build.gradle`）：

| 配置 | 作用 |
|------|------|
| `android.aapt2FromMavenOverride` | 用 build-tools 36 的 aapt2 替换 AGP 自带的，绕过 AGP 8.6.0 在 Windows 上的 stableIds.txt bug |
| `resolutionStrategy` (kotlin) | 用本地缓存的 kotlin 1.9.22 替代 AGP 要求的 1.9.20 |
| 纯原生 UI | 去掉 AndroidX/Material 依赖，使用原生 `Activity` + `Switch`，APK 仅 37KB |

## 使用方法

1. 安装构建好的 APK
2. 在 LSPosed/EdXposed 管理器中启用本模块
3. **在模块作用域中勾选「酷狗概念版」**（`com.kugou.android.lite`）
4. **关闭 LSPosed 设置中的「Xposed API 调用保护」**（否则功能会失效）
5. 重启酷狗概念版
6. 模块会自动在酷狗启动时领取每日VIP，弹窗不会出现
7. 领取结果会通过通知提示，也可在模块APP中查看状态

### 配置选项

| 选项 | 默认 | 说明 |
|------|------|------|
| 启用自动领取 | 开 | 关闭后不自动领取 |
| 自动升级概念版VIP | 关 | 开启后领取成功自动升级，获得更高音质 |

## 项目结构

```
KuGouLiteVipAutoClaim/
├── settings.gradle
├── build.gradle                    # 根构建文件（含 kotlin 版本替换）
├── gradle.properties               # 含 aapt2 override 配置
├── gradle/wrapper/
│   └── gradle-wrapper.properties
└── app/
    ├── build.gradle                # 模块构建文件（纯原生，无 AndroidX 依赖）
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/
        │   └── xposed_init         # Xposed 入口声明
        ├── res/
        │   ├── layout/activity_main.xml
        │   └── values/
        │       ├── strings.xml
        │       └── arrays.xml      # Xposed 作用域
        └── java/com/moekoe/xposed/kugoulite/
            ├── MainHook.java       # Xposed 入口
            ├── PopupBlocker.java   # 弹窗阻止器（阻止领取弹窗显示）
            ├── AutoClicker.java    # 自动点击器（fallback：点击+关闭弹窗）
            ├── NetCapture.java     # OkHttp 凭证捕获
            ├── ClaimWorker.java    # 领取编排器
            ├── KugouApi.java       # API 封装与签名
            ├── PrefStore.java      # 凭证与状态存储
            ├── Settings.java       # 跨进程配置读写（双通道）
            ├── StateProvider.java  # ContentProvider
            ├── ModuleLog.java      # 统一日志工具
            ├── NotifyUtil.java     # 通知工具
            └── MainActivity.java   # 配置界面
```

## 技术说明

### 跨进程通信

Hook 运行在酷狗概念版进程中（UID = 酷狗），模块APP运行在自身进程中（UID = 模块APP），两者 UID 不同，无法直接访问对方的 SharedPreferences。

解决方案采用**双通道读取策略**：

| 通道 | 方式 | 说明 |
|------|------|------|
| 1 | ContentProvider | 通过 `StateProvider` 的 `call()` 方法跨进程通信 |
| 2 | `createPackageContext` | 直接读取模块APP的 SharedPreferences 文件 |

通道1失败时自动降级到通道2，确保配置读取可靠。

- Hook 通过 `ContentResolver.call()` 读取模块APP中的开关配置
- Hook 通过 `ContentResolver.call()` 回写运行状态到模块APP
- 凭证缓存存储在酷狗的 SharedPreferences 中（Hook 进程可直接访问）

### 日期处理

- **领取日期判断**：使用北京时间（Asia/Shanghai），与酷狗服务器一致
- **API 的 `receive_day` 参数**：北京时间日期（之前用 UTC 导致"日期不能小于今天"错误）
- **今日已领取判断**：只有领取成功（code=0）或服务器返回今日已领取（code=1）才标记，避免失败请求阻止后续重试

### 凭证获取

优先级：
1. **OkHttp Hook 捕获** — Hook `okhttp3.Request$Builder.build()`，从 `kugou.com` 请求的 URL query 中提取 token/userid/dfid/mid
2. **缓存凭证** — 上次捕获的凭证保存在 SharedPreferences 中
3. **酷狗 SP 读取** — 遍历酷狗自身的 SharedPreferences 文件查找 token/userid

### 弹窗阻止

PopupBlocker 通过递归遍历 View 树检查文本内容，匹配以下关键词则阻止弹窗：

`领取` `签到` `立即领取` `去领取` `马上领取` `一键签到` `领取会员` `领取VIP` `听歌会员` `概念版VIP` `免费领取` `点击领取` `每日签到` `每日领取` `每日福利` `会员福利` `免费听歌` `立即开通` `去使用` `免费VIP` `赠送` `福利` `活动` `充值` `续费`

AutoClicker 作为 fallback，对 WebView H5 活动页注入 JS 自动点击，点击后延迟关闭弹窗/Activity。

### 手动触发

模块APP中点击"手动触发领取"时：
1. 通过 ContentProvider 设置 `reset_claim_pending` 重置标记
2. 发送广播给酷狗进程触发领取
3. 酷狗进程启动时检查重置标记，清除领取状态后重新领取

### 日志

模块运行日志写入：`/data/data/com.kugou.android.lite/files/kugou_vip_module.log`

可通过 ADB 拉取：
```bash
adb pull /data/data/com.kugou.android.lite/files/kugou_vip_module.log
```

### 错误码

| error_code | 含义 | 处理 |
|------------|------|------|
| - | status=1 | 领取成功 |
| 131001 | 今日已领取 | 标记今日已完成 |
| 20028 | 账号风控 | 通知用户前往手机端领取 |

## 致谢

- [MakcRe/KuGouMusicApi](https://github.com/MakcRe/KuGouMusicApi) — 酷狗音乐 API 接口
- [iAJue/MoeKoeMusic](https://github.com/iAJue/MoeKoeMusic) — 参考实现

## 免责声明

本模块仅供学习交流使用，请遵守酷狗音乐相关服务条款。使用本模块产生的一切后果由使用者自行承担。
