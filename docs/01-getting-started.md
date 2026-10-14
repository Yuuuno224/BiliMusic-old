# 01 · 快速上手

> 目标：从一台干净的 Windows 电脑出发，把 BiliMusic 编译成 APK 并装到手机上。
> 全程大约 20 分钟，其中大部分时间在等 Gradle 下载依赖。

---

## 1. 三样东西必须先装好

Android 开发环境由三块拼成，缺一个都编译不了。别怕，这里只说最少必要的信息。

### 1.1 什么是 JDK，为什么需要它

**JDK** = Java Development Kit，Java 的开发工具包。
Gradle（构建工具）自己是用 Java 写的，Android 项目里的 `.java` 文件也要靠 JDK 编译成 `.class`。

本项目要求 **JDK 21**。

**最省事的做法**：直接用 Android Studio 自带的 JDK。Android Studio 会带一个叫 JBR
（JetBrains Runtime）的 JDK，就在安装目录的 `jbr` 文件夹里，例如：

```
D:\Android\Android Studio\jbr
```

`build.ps1` 会自动去这些位置找 JDK，找到了就不用你管：

1. 环境变量 `JAVA_HOME`（如果已经设了）
2. `D:\Android\Android Studio\jbr`
3. `%LOCALAPPDATA%\Programs\Android Studio\jbr`
4. `C:\Program Files\Android\Android Studio\jbr`
5. `C:\Program Files\Java\jdk-21`
6. `C:\Program Files\Eclipse Adoptium\jdk-21*`（通配符匹配带版本号的目录）

都找不到时脚本会报错并告诉你怎么手动指定。

<details>
<summary>为什么不用系统里随便一个 JDK？</summary>

Gradle 对 JDK 版本有要求。JDK 太旧（比如 8、11）会直接报
`Unsupported class file major version`；太新有时候插件还没适配。
用 Android Studio 自带的 JBR 最稳，因为 Google 就是拿它测 AGP 的。
</details>

### 1.2 什么是 Android SDK

**SDK** = Software Development Kit，这里指 **Android SDK**，里面装着：

- `platforms/android-XX` —— 某个 Android 版本的 API 定义（本项目要 `android-36.1`）
- `build-tools/` —— 打包 APK 用的工具（`aapt2`、`d8` 等）
- `platform-tools/adb` —— 通过 USB 操作手机的工具

**装法（二选一）**：

- **装了 Android Studio**：打开一次 Android Studio，跟着向导勾选 SDK 组件即可。
- **只用命令行**：装 Android 官方的 `cmdline-tools`，然后执行
  ```powershell
  sdkmanager "platforms;android-36.1" "platform-tools" "build-tools;36.0.0"
  ```

### 1.3 告诉项目 SDK 在哪：`local.properties`

Android 项目不会去猜 SDK 装在哪，而是读项目根目录下的 `local.properties`：

```properties
sdk.dir=D\:\\Android\\Sdk
```

注意两个反斜杠——这是 Java Properties 文件的转义规则，`:` 和 `\` 都要用 `\` 转义。

> **这个文件不会被提交到 Git**（见 `.gitignore`）。
> 因为每个人的 SDK 路径都不一样，提交上去只会互相打架。
> 所以你 clone 下来之后，如果这个文件不存在，自己新建一个即可。

---

## 2. 编译

### 2.1 用项目自带的脚本（推荐）

```powershell
.\build.cmd
```

**为什么不是 `.\build.ps1`**：Windows PowerShell 默认的执行策略（本机是 `RemoteSigned`）
不允许运行未签名的 `.ps1` 脚本，直接跑会看到：

```
无法加载文件 build.ps1。未对文件 build.ps1 进行数字签名。
```

`build.cmd` 是一个两行的批处理包装，内部带了 `-ExecutionPolicy Bypass`，
所以它能正常跑；而且双击就能用，不依赖你当前的 PowerShell 窗口。

如果你想直接调 PowerShell 脚本，显式绕过执行策略：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

脚本做了三件事：**找 JDK → 找 `gradlew` → 执行任务**。支持的用法：

```powershell
.\build.cmd                       # 构建 Debug APK（默认）
.\build.cmd release               # 构建 Release APK（未签名）
.\build.cmd clean                 # 清理构建产物
.\build.cmd wrapper               # 重新生成 Gradle Wrapper
.\build.cmd assembleDebug -GradleArgs "--stacktrace"   # 加额外参数
```

用 PowerShell 直接写则是：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 -Task release
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 -Task assembleDebug -GradleArgs '--stacktrace'
```

**产物位置**：

```
app\build\outputs\apk\debug\app-arm64-v8a-debug.apk      约 9 MB，现代手机装这个
app\build\outputs\apk\debug\app-armeabi-v7a-debug.apk    老旧 32 位设备装这个
```

> 本项目按 CPU 架构分包（`app/build.gradle` 里的 `splits.abi`），
> 所以**没有"万能包"**。这样做的好处是每个 APK 体积更小——
> ExoPlayer 带原生的解码库，把两种架构都塞进一个包会大不少。

> ⚠️ **改 `build.ps1` 时注意**：必须保存为**带 BOM 的 UTF-8**。
> Windows PowerShell 5.1 会把无 BOM 的 UTF-8 文件按 GBK 解码，
> 中文注释变成乱码后可能撑破语法结构，报出莫名其妙的
> `Missing closing '}'`。用 VS Code 时右下角要选 `UTF-8 with BOM`。
> 详见 [07 篇 E2](07-faq-and-pitfalls.md#e2-buildps1-没有-utf-8-bomwindows-powershell-51-会读成乱码)。

### 2.2 用 Android Studio

双击打开项目根目录，等右下角的 Gradle 同步跑完，然后：

- **Build → Make Project** 只编译，不打包
- **Build → Build Bundle(s) / APK(s) → Build APK(s)** 打包 APK

### 2.3 第一次编译为什么这么慢

Gradle 要下载 AGP、AndroidX、Media3 等依赖，几百 MB，国内网络下可能很慢甚至超时。
下载过一次之后会缓存在 `GRADLE_USER_HOME`（默认在用户目录的 `.gradle`），之后编译就快了
（本项目实测增量编译 9 秒左右）。

<details>
<summary>下载太慢怎么办：配置国内镜像</summary>

编辑 `settings.gradle`，在两个 `repositories { }` 块里把镜像放最前面：

```groovy
maven { url 'https://maven.aliyun.com/repository/google' }
maven { url 'https://maven.aliyun.com/repository/public' }
maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
```

Gradle 本体下载慢的话，改 `gradle/wrapper/gradle-wrapper.properties`
里的 `distributionUrl` 为腾讯云镜像：

```properties
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-9.4.1-bin.zip
```
</details>

---

## 3. 装到手机上

### 3.1 命令行安装（需要 adb）

```powershell
# 手机要用 USB 连电脑，并在「开发者选项」里打开「USB 调试」
adb devices                                                   # 确认手机被识别
adb install -r app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
```

`-r` 表示覆盖安装（保留数据）。

### 3.2 直接拷贝安装

把 APK 传到手机（微信文件传输、网盘、数据线都行），用文件管理器点开安装。
手机会提示"未知来源应用"，允许即可。

### 3.3 怎么知道自己手机该装哪个

绝大多数 2017 年以后的手机都是 **arm64-v8a**。
实在不确定就装 `arm64-v8a`，装不上再换 `armeabi-v7a`（装错时通常提示"应用未安装"）。

---

## 4. 报错排查表

改代码之前，先把这张表存下来。90% 的环境问题都在这里。

### 4.1 环境类

| 报错 | 原因 | 解决 |
|---|---|---|
| `无法加载文件 build.ps1。未对文件进行数字签名` | PowerShell 执行策略不允许跑未签名脚本 | 用 `.\build.cmd`，或加 `-ExecutionPolicy Bypass` |
| `Missing closing '}'` / `The string is missing the terminator` | `build.ps1` 被存成了无 BOM 的 UTF-8，PowerShell 5.1 按 GBK 解码后乱码 | 用 VS Code 存成 `UTF-8 with BOM` |
| `ERROR: 找不到可用的 JDK` | 没设 `JAVA_HOME`，也没装 Android Studio | 装 JDK 21，或设 `$env:JAVA_HOME = "D:\Android\Android Studio\jbr"` |
| `Unsupported class file major version` | JDK 版本不对（太旧或太新） | 换成 JDK 21 |
| `SDK location not found` | 缺 `local.properties` | 新建该文件并写 `sdk.dir=...` |
| `Failed to install ... platforms;android-36.1` | SDK 里没装这个 platform | `sdkmanager "platforms;android-36.1"` |
| `Could not resolve com.android.tools.build:gradle:9.2.1` | 网络问题，依赖没下下来 | 配国内镜像（见 2.3 折叠块） |
| `无法删除项 ... .gradle\...` / 文件被占用 | Android Studio 正开着，和命令行抢文件锁 | **先完全关闭 Android Studio**，再从命令行编译 |
| `gradlew.bat 不是内部或外部命令` | 用错了目录 | 确认当前在项目根目录，且用的是 `.\gradlew.bat`（前面有 `.\`） |

### 4.2 安装类

| 报错 | 原因 | 解决 |
|---|---|---|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 手机上已装的版本签名不同 | 先卸载旧版：`adb uninstall com.yuuuno224.bilimusic` |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | APK 架构和手机不匹配 | 换另一个 ABI 的 APK |
| `应用未安装` / `App not installed` | 同上，或系统版本低于 Android 8.0 | 换 APK；确认手机是 Android 8.0+ |

### 4.3 运行时报错

| 现象 | 原因 | 解决 |
|---|---|---|
| 能播放，但**通知栏里没有控制条** | Android 13+ 的通知权限被拒了 | 到「设置 → 应用 → BiliMusic → 通知」手动打开 |
| 搜索报"HTTP 412" | 触发了 B 站风控（短时间请求太频繁） | 等几分钟再试；不要同时开多个客户端 |
| 搜索报"访问权限不足"（-403） | WBI 签名密钥过期 | 正常情况会自动刷新重试；仍失败就重启 App |
| 收藏夹提示"未获取到用户信息" | 登录信息缓存丢了 | 重新登录一次 |
| 部分歌点开就跳过 | 该视频已下架 / 是付费课程 / 无音轨 | 属正常情况，会从队列里跳过 |
| 播放中途卡住或报错 | 音频地址过期（B 站 CDN 链接有时效） | 代码会自动重新解析并重试最多 2 次 |
| 图片不显示 | 网络问题，或该图 CDN 挂了 | 会显示占位图标；可下拉重进页面触发重试 |

---

## 5. 下一步

环境通了、App 也跑起来了，接下来：

- 想理解整体设计 → [02-architecture.md](02-architecture.md)
- 想知道代码都在哪 → [03-code-map.md](03-code-map.md)
- 想直接动手改 → [06-development-guide.md](06-development-guide.md)
