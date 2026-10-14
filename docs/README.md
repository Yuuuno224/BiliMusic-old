# BiliMusic 文档

这里不是 API 手册，而是一套**带你读懂这个项目**的教程。
所有文档都假设你**刚开始学 Android**，会解释"为什么这么做"，而不只是"这么做了"。

---

## 先看这里：三条阅读路线

### 路线 A：我只想把它跑起来（约 20 分钟）

1. [01-getting-started.md](01-getting-started.md) —— 装 JDK、SDK，编译出 APK，装到手机

跑起来就可以停了。以后想改东西再回来看其它篇。

### 路线 B：我想搞懂这个 App 是怎么运作的（约 2 小时）

按顺序读：

1. [01-getting-started.md](01-getting-started.md) —— 先能跑
2. [02-architecture.md](02-architecture.md) —— 先建立整体地图：有哪几层、谁依赖谁
3. [03-code-map.md](03-code-map.md) —— 再看每一块代码具体在哪、负责什么
4. [04-core-flows.md](04-core-flows.md) —— 最后跟着五条真实流程走一遍，把点连成线

读完这四篇，你应该能在不看代码的情况下，说清楚
"从点击一首歌到听见声音，中间经过了哪些类"。

### 路线 C：我要动手改（按需查阅）

1. [06-development-guide.md](06-development-guide.md) —— 加接口 / 加页面 / 改播放逻辑的步骤
2. [05-bilibili-api.md](05-bilibili-api.md) —— 调 B 站接口时会用到的所有细节
3. [07-faq-and-pitfalls.md](07-faq-and-pitfalls.md) —— 改崩了先来这里找

---

## 各篇内容速查

| 文档 | 一句话说明 | 关键内容 |
|---|---|---|
| [01-getting-started.md](01-getting-started.md) | 从零把它编译出来 | JDK/SDK 准备、`build.ps1` 用法、安装 APK、报错排查表 |
| [02-architecture.md](02-architecture.md) | 整体设计地图 | 分层图、依赖规则、五条数据流、线程模型、存储清单 |
| [03-code-map.md](03-code-map.md) | 代码在哪、干什么 | 每个包/类/资源的职责、关键常量位置 |
| [04-core-flows.md](04-core-flows.md) | 五条核心流程时序 | 搜索、播放、登录、收藏与历史、图片加载 |
| [05-bilibili-api.md](05-bilibili-api.md) | B 站接口与签名 | 12 个接口的参数与响应、WBI 签名算法、风控码 |
| [06-development-guide.md](06-development-guide.md) | 常见改动怎么做 | 加接口、加页面、改播放、改存储、注意事项 |
| [07-faq-and-pitfalls.md](07-faq-and-pitfalls.md) | 踩坑与限制 | 14 个已修缺陷、Android 新手易混点、已知限制 |

---

## 术语表（看文档时遇到不懂的回来查）

| 术语 | 含义 |
|---|---|
| **bvid** | B 站视频编号，形如 `BV1xx411c7mD`。本项目里它就是"一首歌"的主键 |
| **cid** | 视频分 P 的内部编号。取音频地址必须用它，而搜索接口不返回它 |
| **DASH** | 一种把音视频拆成两条独立流的格式。本项目只要其中的音频流 |
| **WBI 签名** | B 站给部分接口加的校验，请求要带 `wts`（时间戳）和 `w_rid`（签名） |
| **buvid3** | B 站的风控指纹 Cookie。不带上它，部分接口会拒绝服务 |
| **SESSDATA / bili_jct** | B 站登录凭证 Cookie。前者是身份，后者是 CSRF token |
| **MediaSession** | Media3 提供的"播放会话"机制，让服务端播放器能被界面/通知栏远程控制 |
| **MediaController** | 界面侧用来遥控 MediaSession 的"遥控器" |
| **ExoPlayer** | Media3 的播放器实现，本项目真正的解码/播放引擎 |
| **主线程 / UI 线程** | 唯一能修改界面的线程。网络和写大文件都不能在这里做 |
| **ANR** | Application Not Responding。主线程被卡住超过约 5 秒，系统弹出的"无响应" |
| **Fragment** | 可以嵌在 Activity 里的"半张页面"，本项目三个主页面都是 Fragment |
| **Adapter** | RecyclerView 的"数据 → 列表项视图"翻译器 |
| **前台服务** | 带通知、不易被系统杀掉的 Service。音乐播放必须用它 |

---

## 关于旧的 `ARCHITECTURE.md`

项目里曾经有一份单篇的 `docs/ARCHITECTURE.md`（600 多行）。它已被本篇索引下的 7 篇文档取代。
旧文档里核实出的**事实错误**（例如把二维码过期状态码写成 `86091`，
而正确值是 `86038`）在 [07-faq-and-pitfalls.md](07-faq-and-pitfalls.md) 里逐条列出。

旧文件本身已经从工作区移除，但**没有丢失**——它被完整保留在 Git 历史里：

```bash
git show 95d38af:docs/ARCHITECTURE.md
```

---

## 这份文档是怎么保证不出错的

上一版文档里有过这些**实打实的错误**，新版全部修正，并在
[07-faq-and-pitfalls.md](07-faq-and-pitfalls.md) 里记录了原文错在哪：

- 把二维码过期状态码写成 `86091`（**实际是 86038**）
- 声称 WBI 混淆表有 64 位和 84 位两套版本（**实际只有一套 64 项的，另一份代码永远走不到**）
- README 写"生成授权二维码"（**实际根本不生成二维码图片**）
- README 写编译命令 `build.ps1 -VerboseBuild`（**脚本不支持这个参数**）

所以本次文档的原则是：**每一句话都要能在代码里找到对应**。
文档里出现的类名、方法名、常量值、文件路径，都直接取自重构后的源码。

---

## 文档与代码的对应关系

如果你改了代码，请顺手改对应文档：

| 你改了什么 | 该更新哪篇 |
|---|---|
| 新增/删除类、移动包 | [03-code-map.md](03-code-map.md)、[02-architecture.md](02-architecture.md) |
| 改线程模型（`AppExecutors`） | [02-architecture.md](02-architecture.md)、[03-code-map.md](03-code-map.md) |
| 改存储格式或路径 | [02-architecture.md](02-architecture.md)、[03-code-map.md](03-code-map.md) |
| 改/加 B 站接口 | [05-bilibili-api.md](05-bilibili-api.md) |
| 改播放流程、加自定义命令 | [04-core-flows.md](04-core-flows.md)、[06-development-guide.md](06-development-guide.md) |
| 修掉一个坑 | [07-faq-and-pitfalls.md](07-faq-and-pitfalls.md) |
