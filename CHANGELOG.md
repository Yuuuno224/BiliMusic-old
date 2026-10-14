# Changelog

## [未发布]

一次以"可读性 + 正确性"为目标的重构。**对外功能保持不变**，不新增用户可见功能。

### 代码结构

- 网络层：把 300 多行的 `BiliApi` 上帝类拆成 `net/api/` 下四个按业务划分的类
  （`SearchApi` / `PlaybackApi` / `LoginApi` / `FavoriteApi`）；
  新增 `net/dto/` 承载纯 JSON 镜像；新增 `Json`、`BiliSession`、`BiliException`
- 领域模型独立成 `model` 包（`Song` / `UserProfile` / `FavoriteFolder` / `AudioStream` / `LoginState`），
  与接口 DTO 解耦，且**零依赖**（原先 `UserProfile.from(NavDto)` 让 model 反向依赖了 net.dto，
  现映射移入 `LoginApi`）
- `BiliText` 从 `util` 移到 `net`（它描述的是接口数据格式），
  顺带解开 `util` ↔ `net.api` 的包级互相依赖
- 新增 `core/AppExecutors`，把全 App 的线程池集中到一处管理
- 从 `PlaybackService` 抽出 `MediaItemFactory`
- 删除死代码（`BiliHttp.getStrict`、`BiliApi.debugHeaders`、`RecommendData.Owner`、
  `QrLoginActivity.this2()`、`MainActivity.newOwningIntent()`、
  `MusicRepository.ensureCid/resolveAudio`、`WbiSigner.TAB_V2`）
- 所有超时/上限/间隔等魔法数字提为命名常量

### 缺陷修复

- **播放地址过期后自动恢复**：新增播放错误监听，重新解析音频地址并回到原播放位置，重试上限 2 次
- **本地数据原子写**：`MusicStore` 改为"写临时文件 + 整体替换"，避免写到一半损坏全部数据
- **Android 13+ 通知权限**：此前从未运行时申请 `POST_NOTIFICATIONS`，
  导致通知栏没有播放控制条
- **登录页重复轮询**：链接过期后会同时跑两个轮询循环，改用世代号 + 串行安排修复
- **播放器回调线程**：`PlayerConnection` 的通知此前可能在子线程执行，现已强制主线程并合并刷新
- **页面销毁后回调**：`SearchFragment` 会在 Fragment 已销毁时操作视图，现统一做存活判断
- **退出登录后界面不同步**：`logout` 改为带完成回调，界面在回调里刷新
- **收藏夹加载**：用户信息缓存丢失时会用 `mid=0` 请求，现先补拉用户信息
- **返回键语义**：在收藏夹内容里按返回键不再直接退出 App
- **图片加载**：支持取消请求、合并同一地址的并发下载、磁盘缓存超限自动清理
- **封面地址**：`cover` 为空时不再拼出非法 URL
- **存储写盘移出主线程**：切歌时的历史落盘改到后台线程
- `MusicStore.isFavorite` 空值安全
- `BiliHttp` 在 4xx/5xx 时不再丢失状态码与响应体，异常信息可直接用于排查
- 播放队列解析改用 `invokeAll`，顺序由标准 API 保证

### 构建与环境

- `build.ps1` 不再硬编码 Gradle 发行版的哈希目录，改用项目自带的 `gradlew`，
  并自动查找 JDK（此前换机器/升级 Gradle 就会失效）
- `build.ps1` 保存为带 BOM 的 UTF-8（Windows PowerShell 5.1 会把无 BOM 的 UTF-8 按 GBK 解码，
  中文注释乱码后会撑破语法结构）；已实测 `build.cmd` / `build.ps1` 从 clean 状态构建成功
- 新增 `build.cmd`，绕过 PowerShell 执行策略，双击即可构建
- `proguard-rules.pro` 更新 keep 规则以匹配新的 `net.dto` 包
- 修复 `tools/api-probe/_04_WbiVerify.java` 引用不存在的包名导致无法编译的问题，
  并扩充为可对照已知值验证 WBI 签名的程序

### 文档

- 重写 `README.md`，并把原先单篇的 `docs/ARCHITECTURE.md` 拆成 7 篇面向 Android 初学者的文档
  （快速上手 / 架构总览 / 代码地图 / 核心流程 / B 站接口与签名 / 开发指南 / 踩坑与 FAQ）
- 修正旧文档中的事实错误（二维码过期状态码 `86091` → `86038`；
  WBI 混淆表"64 位与 84 位两套版本"实为一张 64 项的表；README 声称"生成授权二维码"实为授权链接；
  README 给出的 `build.ps1 -VerboseBuild` 参数不存在）

## [v1.0.0] - 2026-09-03

B站音乐播放器 —— 把B站视频当音乐听。纯客户端 Android 应用，无需后端。

### 功能

- 扫码登录B站账号
- 关键词搜索B站视频（WBI 签名）
- 搜索首页：热门搜索词 + 音乐排行榜 + 最近播放
- DASH 音频流式播放，后台播放、通知栏控制
- 三档循环模式（顺序 / 列表循环 / 单曲循环）
- 播放队列管理（BottomSheet 弹窗，点击跳转、删除）
- 收藏与播放历史
- B站收藏夹浏览（登录后）
- 沉浸式播放页 + 迷你播放条快捷操作
- 我的页：快捷入口、GitHub 仓库、关于
- Spotify 风格深色 UI

### 安装

下载对应架构的 APK 直接安装：

- **arm64-v8a**：64 位 ARM 设备（大多数现代手机）
- **armeabi-v7a**：32 位 ARM 设备（旧设备）

Android 8.0+。

## [v0.2.0] - 2026-08-30

基础功能：搜索、播放、收藏、历史、扫码登录。

## [v0.1.0] - 2026-08-29

初始版本。
