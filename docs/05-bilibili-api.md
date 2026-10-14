# 05 · B 站接口与 WBI 签名

> 目标：知道本项目调了哪些 B 站接口、每个接口怎么调、以及为什么有些要签名有些不要。
> 想在项目里**新增一个接口**，看最后一节。

> ⚠️ 本篇记录的是**代码当前的实现**，不是 B 站官方文档。
> B 站接口是公开但不承诺稳定的，随时可能变。变了之后请对照本篇更新。

---

## 1. 通用约定（先看这一节，能省很多困惑）

### 1.1 失败也是 HTTP 200

B 站几乎所有接口都返回 HTTP 200，真正的结果在响应体里：

```json
{ "code": 0, "message": "0", "data": { ... } }
```

`code == 0` 才是成功。**只看 HTTP 状态码会把失败当成功**。

对应到代码：`net/dto/ApiResponse`，以及所有 API 方法里的 `if (!parsed.ok())` 判断。

### 1.2 统一请求头

`BiliHttp` 会给每个请求加上：

| 头 | 值 | 为什么 |
|---|---|---|
| `User-Agent` | 桌面 Chrome 的 UA | 用 Java/OkHttp 默认 UA 会被部分接口拒 |
| `Referer` | `https://www.bilibili.com/` | 防盗链；图片 CDN 和音频 CDN 也校验它 |
| `Cookie` | `CookieStore.cookieHeader()` | 登录凭证 + 风控指纹 |

超时：连接 10 秒、读取 15 秒（`BiliHttp` 里的常量）。

### 1.3 响应头里也有数据

只有一处例外需要读响应头：**登录轮询成功时，登录 Cookie 在 `Set-Cookie` 响应头里**，
不在响应体里。见 `LoginApi.poll()`。

---

## 2. 调通 B 站接口要过三道关

初学者最困惑的是"URL 明明对，为什么报错"。B 站的 web 接口有额外门槛：

```
第 1 关：风控指纹 buvid3
  第一次请求前要先向 /x/frontend/finger/spi 申请，之后放进 Cookie 一起发。
  没有它，一部分接口会直接拒绝。→ BiliSession.ensureFingerprint()

第 2 关：WBI 签名 wts + w_rid
  部分接口要求请求参数额外带上时间戳与签名，服务端会用同样算法验一遍。
  → BiliSession.wbiMixinKey() + WbiSigner.sign()

第 3 关：登录 Cookie
  收藏夹等接口需要登录。→ BiliHttp 自动附带，业务代码不用管
```

三道关的处理全部集中在 `net/BiliSession.java`，API 类只需要选对方法：

| 该用哪个方法 | 什么时候 |
|---|---|
| `BiliSession.get(url, type, label)` | 不需要参数、不需要签名 |
| `BiliSession.getWithParams(base, params, type, label)` | 要参数，但**不需要签名** |
| `BiliSession.getSigned(base, params, type, label)` | 要参数，**且需要签名**（会自动申请指纹） |

---

## 3. 接口清单

代码里一共用到 **12 个接口**。下表是总览，每个的细节在下一节。

| # | 接口 | 路径 | 签名 | 指纹 | 登录 | 代码位置 |
|---|---|---|---|---|---|---|
| 1 | 风控指纹 | `/x/frontend/finger/spi` | 否 | — | 否 | `BiliSession.ensureFingerprint` |
| 2 | WBI 密钥 + 用户信息 | `/x/web-interface/nav` | 否 | 否 | 否 | `BiliSession.wbiMixinKey` / `LoginApi.profile` |
| 3 | 视频搜索 | `/x/web-interface/search/type` | **是** | 是 | 否 | `SearchApi.search` |
| 4 | 热门搜索词 | `/x/web-interface/search/square` | 否 | 是 | 否 | `SearchApi.hotWords` |
| 5 | 音乐区排行榜 | `/x/web-interface/ranking/index` | 否 | 是 | 否 | `SearchApi.musicRanking` |
| 6 | 视频详情（取 cid） | `/x/web-interface/view` | 否 | 否 | 否 | `PlaybackApi.videoMeta` |
| 7 | 播放地址 | `/x/player/playurl` | 否 | 否 | 否 | `PlaybackApi.bestAudio` |
| 8 | 生成授权链接 | `passport…/qrcode/generate` | 否 | 否 | 否 | `LoginApi.generate` |
| 9 | 轮询登录状态 | `passport…/qrcode/poll` | 否 | 否 | 否 | `LoginApi.poll` |
| 10 | 收藏夹列表 | `/x/v3/fav/folder/created/list` | **是** | 是 | **是** | `FavoriteApi.folders` |
| 11 | 收藏夹内容 | `/x/v3/fav/resource/list` | **是** | 是 | **是** | `FavoriteApi.allResources` |
| 12 | 注销登录 | `passport…/cookie/revoke` | 否 | 否 | 是 | `LoginApi.revokeSession` |

---

## 4. 逐个接口详解

### 4.1 风控指纹

```
GET https://api.bilibili.com/x/frontend/finger/spi
```

**响应**

```json
{ "code": 0, "data": { "b_3": "……", "b_4": "……" } }
```

`b_3` → Cookie `buvid3`，`b_4` → Cookie `buvid4`。
本项目只在**还没有 buvid3 时**才请求一次，之后一直复用。

对应 `net/dto/FingerprintDto`（用 `@SerializedName` 把 `b_3` 映射到 `buvid3`）。

### 4.2 nav（一件两用）

```
GET https://api.bilibili.com/x/web-interface/nav
```

这个接口做了两件看起来无关的事，所以项目里被调用了两次、用途不同：

1. **取 WBI 密钥**：`data.wbi_img.img_url` / `data.wbi_img.sub_url`
2. **取用户信息**：`data.uname` / `data.face` / `data.mid` / `data.level_info.current_level`

**未登录时**：外层返回 `code: -101`，而 `data` 依然是合法对象、其中有 `isLogin: false`。
也就是说**同一个"没登录"事实，在响应里出现了两次**（外层 code 和内层 isLogin）。

本项目的处理是：`LoginApi.profile()` 只在 `code == -101` 时直接返回一个空的
`UserProfile`（等于 `isLogin = false`），不去读 `data.isLogin`；
只有 `code == 0` 时才用 `data` 里的字段构造 `UserProfile`。
**关键是别把 -101 当异常抛出去**——它不是错误，只是"当前没登录"。

密钥的处理方式：从 URL 里取文件名（去掉目录和 `.png` 后缀），得到两个 32 字符的 key。

```
https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png
                          └──────────── imgKey ────────────┘
```

### 4.3 视频搜索（**要签名**）

```
GET https://api.bilibili.com/x/web-interface/search/type?<签名后的参数>
```

**参数**

| 参数 | 值 | 说明 |
|---|---|---|
| `keyword` | 用户输入 | 关键词 |
| `page` | `1` | 页码。本项目只取第 1 页 |
| `page_size` | `20` | 单页上限就是 20 |
| `platform` | `pc` | — |
| `search_type` | `video` | 只搜视频 |
| `wts` / `w_rid` | 由 `WbiSigner` 注入 | 见第 5 节 |

**响应**（只列本项目用到的字段）

```json
{ "code": 0, "data": { "seid": "…", "result": [
  { "bvid": "BV1xx", "title": "<em class=\"keyword\">周杰伦</em>晴天",
    "author": "某UP", "duration": "3:45", "pic": "//i0.hdslb.com/…jpg" }
] } }
```

**三个坑**

1. `title` 里带 HTML 高亮标签 → 必须用 `BiliText.cleanTitle()` 清洗
2. `duration` 是 `"3:45"` 文本，**不是秒数** → `BiliText.parseDurationSeconds()`
3. **响应里没有 `cid`** → 播放前必须再调一次"视频详情"

### 4.4 热门搜索词

```
GET https://api.bilibili.com/x/web-interface/search/square?limit=10
```

**响应**

```json
{ "code": 0, "data": { "trending": { "list": [
  { "keyword": "周杰伦", "show_name": "周杰伦" }
] } } }
```

`show_name` 通常比 `keyword` 更像人话，优先用它（见 `HotSearchDto`）。

### 4.5 音乐区排行榜

```
GET https://api.bilibili.com/x/web-interface/ranking/index?rid=3&ps=20
```

`rid=3` 是音乐区，`ps=20` 是条数。

**注意它的 `data` 直接是数组**，和其他接口长得不一样：

```json
{ "code": 0, "data": [ { "bvid": "…", "title": "…", "author": "…",
                         "pic": "…", "duration": 245 } ] }
```

`duration` 在这里是**秒数**（`245`），不是 `"3:45"`。
Gson 会把 JSON 数字读进 `String` 字段，所以值是 `"245"`，
`BiliText.parseDurationSeconds` 靠"有没有冒号"来区分两种格式。

### 4.6 视频详情（取 cid）

```
GET https://api.bilibili.com/x/web-interface/view?bvid=BV1xx411c7mD
```

**响应**（只列用到的）

```json
{ "code": 0, "data": { "bvid": "…", "cid": 123456, "duration": 245,
                       "title": "…", "pic": "…" } }
```

**为什么必须调它**：搜索接口不返回 cid，而 cid 是取播放地址的必要参数。这是整个播放链路上
"多出来的一次请求"，也是搜索来的歌点播比收藏夹点播慢一拍的原因
（收藏夹接口直接带 cid，可以跳过这一步）。

### 4.7 播放地址

```
GET https://api.bilibili.com/x/player/playurl
      ?bvid=BV1xx&cid=123456&fnval=16&fourk=1&platform=pc&qn=0
```

| 参数 | 值 | 说明 |
|---|---|---|
| `fnval` | `16` | 要 DASH 格式（音视频分离）。我们要的就是其中的纯音频流 |
| `fourk` | `1` | 允许 4K（对音频无影响，但官方网页播放器也带） |
| `platform` | `pc` | — |
| `qn` | `0` | 让服务端按当前账号权限给最好的音质；登录后能拿到更高码率 |

**响应结构**（简化）

```json
{ "code": 0, "data": {
  "dash": {
    "audio": [ { "id": 30280, "baseUrl": "https://…", "backupUrl": ["…"],
                 "bandwidth": 192000, "codecs": "mp4a.40.2" } ],
    "flac":  { "audio": { "id": 30251, "baseUrl": "…", "codecs": "fLaC" } }
  },
  "durl": [ { "url": "https://…" } ]
} }
```

**音质选择策略**（`PlaybackApi.pickBest`）

1. `dash.audio` 里 `id` 最大**且带可用地址**的一条 —— `id` 越大码率越高
2. `dash.flac.audio` —— 无损，通常需要大会员
3. `durl[0].url` —— 老式整段流，音质最差，仅兜底

三者都没有 → 抛异常（通常是付费课程或已下架）。

> **关于让不让它签名**：本项目**不签名**，这是经过验证的现状。
> 盲目加上签名一旦服务端校验的参数集合与我们对不上，会直接把播放打死。
> 如果你要改，请先在真机上验证能播。

**地址有时效**：`baseUrl` 是带签名和过期时间的 CDN 链接，通常几小时后 403。
所以 `AudioStream` 绝不能存进本地文件；播放出错时用同一个 bvid 重新解析即可
（见 `PlaybackService.handlePlaybackError`）。

### 4.8 生成授权链接

```
GET https://passport.bilibili.com/x/passport-login/web/qrcode/generate
```

**响应**

```json
{ "code": 0, "data": { "url": "https://passport.bilibili.com/…",
                       "qrcode_key": "…" } }
```

- `url` —— 授权链接。**用户点它会被唤起 B 站 App 的确认页**。
  本项目不自己画二维码图片，直接让用户点链接，少一步设备协作。
- `qrcode_key` —— 轮询凭证，每次轮询都要带上。

### 4.9 轮询登录状态

```
GET https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=<key>
```

**响应**

```json
{ "code": 0, "data": { "url": "…", "refresh_token": "…",
                       "timestamp": 1700000000, "code": 86101 } }
```

**这里有两层 `code`，别搞混：**

| 层 | 含义 |
|---|---|
| 外层 `ApiResponse.code` | 接口调用成功没有 |
| 内层 `data.code` | **用户扫码到哪一步了** |

内层取值（常量在 `model/LoginState.java`，见"为什么放在 model 里"的注释）：

| 常量 | 值 | 含义 | 界面行为 |
|---|---|---|---|
| `LoginState.SUCCESS` | **0** | 登录成功 | 保存 Cookie → 拉用户信息 → 关页面 |
| `LoginState.SCANNED` | **86090** | 已扫码，待用户确认 | 提示"正在完成登录…"，继续轮询 |
| `LoginState.WAITING` | **86101** | 等待扫码 | 提示"等待确认…"，继续轮询 |
| `LoginState.EXPIRED` | **86038** | 链接已过期 | 重新生成一条新链接 |

> **上一版文档把过期码写成了 `86091`，是错的。** 正确的是 `86038`。
> 这里特别标注，因为排查"登录一直不成功"时，错误的状态码会让你查错方向。

**登录成功的判断依据**：内层 `code == 0` 时，B 站会在
**`Set-Cookie` 响应头**里下发登录凭证。`LoginApi.poll` 会立刻解析并写入 `CookieStore`。
响应体里没有任何 token。

### 4.10 收藏夹列表（**要签名 + 要登录**）

```
GET https://api.bilibili.com/x/v3/fav/folder/created/list?<签名后>
```

| 参数 | 值 |
|---|---|
| `up_mid` | 用户 uid（来自 `UserProfile.mid`），**传 0 会被拒** |
| `pn` | `1` |
| `ps` | `20` |

```json
{ "code": 0, "data": { "list": [
  { "id": 123456, "title": "默认收藏夹", "media_count": 42, "cover": "…" }
] } }
```

注意 `id` 是**收藏夹 id**，不是用户 uid；请求夹内内容时用这个 `id`。

### 4.11 收藏夹内容（**要签名 + 要登录**）

```
GET https://api.bilibili.com/x/v3/fav/resource/list?<签名后>
```

| 参数 | 值 | 说明 |
|---|---|---|
| `media_id` | 收藏夹 id | 不是 uid |
| `pn` | 页码 | 从 1 开始 |
| `ps` | `20` | 单页上限 |
| `order` | `mtime` | 按收藏时间倒序 |
| `type` | `2` | **只取视频**，过滤音频/专栏 |
| `platform` | `web` | — |

```json
{ "code": 0, "data": { "medias": [
  { "bvid": "BV1xx", "title": "…", "cover": "…",
    "duration": 245, "cid": 123456, "upper": { "name": "某UP" } }
], "has_more": true } }
```

**两个要点**

1. 这里**直接带 `cid`** → 从收藏夹点播可以跳过"视频详情"那一次请求
2. `has_more` 决定要不要继续翻页。`FavoriteApi.allResources` 最多翻 `MAX_PAGES`（10 页，约 200 条）

### 4.12 注销登录

```
POST https://passport.bilibili.com/x/passport-login/web/cookie/revoke
Content-Type: application/x-www-form-urlencoded

csrf=<bili_jct 的值>
```

`bili_jct` 就是 CSRF token（它本身也是个 Cookie）。
这是**尽力而为**的操作：失败也无所谓，本地 Cookie 照样清掉。

---

## 5. WBI 签名算法

### 5.1 为什么要签名

B 站在 2023 年给一批 web 接口加了校验：请求必须额外带
`wts`（时间戳）和 `w_rid`（签名）。服务端用同样算法算一遍，对不上就返回 `-403`。
这能挡住不模拟浏览器的爬虫。

### 5.2 三步算法

**第 1 步：取密钥**

调 nav 接口，从 `wbi_img.img_url` 和 `sub_url` 里各取文件名，得到两个 32 字符的 key。

**第 2 步：混淆出 mixinKey**

把两个 key 拼成 64 个字符，然后按固定表重排，取**前 32 个**：

```
joined = imgKey + subKey            // 64 个字符
MIXIN_TABLE = [46, 47, 18, 2, 53, 8, 23, 32, 15, 50, …]   // 64 个下标，是 0~63 的排列
mixinKey = joined[MIXIN_TABLE[0..31]]                     // 32 个字符
```

这张表是官方前端 JS 里硬编码的，无法推导，只能照抄。

> **上一版文档声称存在"64 位（V1）"和"84 位（V2）"两张表，并按长度自适应选择。**
> 这是错的：`imgKey + subKey` 恒为 64 个字符，
> 旧代码里那张所谓的 V2 表实际有 64 项且**永远不会被走到**（死代码）。
> 现在只保留一张表。

**第 3 步：算 w_rid**

```
① 把所有业务参数放进 TreeMap          → 保证按 key 的字典序
② 注入 wts = 当前秒级时间戳
③ 每个值去掉 ! ' ( ) * 这 5 个字符
④ 对 key 和 value 做 URL 编码（等价 encodeURIComponent，空格编成 %20）
⑤ 拼成 a=1&b=2&wts=…                  → 得到 payload
⑥ w_rid = MD5(payload + mixinKey)
⑦ 最终 query = payload + "&w_rid=" + w_rid
```

**顺序不能变。** 先排序、再去非法字符、再编码，最后才拼 mixinKey。
错了服务端只会回"签名校验失败"，不会告诉你错在哪。

### 5.3 怎么验证算法是对的

用已知值对照。`tools/api-probe/_04_WbiVerify.java` 就是干这个的：

```powershell
javac -d out -sourcepath app/src/main/java tools/api-probe/_04_WbiVerify.java
java -cp out _04_WbiVerify
```

它用的对照值是社区验证过的：

| 输入 | 值 |
|---|---|
| imgKey | `7cd084941338484aae1ad9425b84077c` |
| subKey | `4932caff0ff746eab6f01bf08b70ac45` |
| 期望 mixinKey | `ea1db124af3c7062474693fa704f4ff8` |

输出 `MATCH = true` 就说明混淆算法正确。**这个探针已被实测通过**，
所以 `WbiSigner.mixinKey` 可以信任。

### 5.4 密钥有效期与自动重试

密钥的缓存时长与官方前端一致：**2 小时**（`BiliSession` 内部的私有常量 `WBI_TTL_MS`）。

服务端可能在我们还在用旧密钥时就轮换了。这时接口返回业务码 `-403`。
`BiliSession.getSigned` 的处理是：

```
第 1 次请求 → 失败且 code == -403
  → invalidateWbiKeys()      丢弃缓存
  → 重新拉 nav 拿新密钥
  → 再请求一次
仍失败 → 抛 BiliException，交给上层提示用户
```

这条逻辑能挡掉绝大多数"昨天还好好的，今天搜索就报错"的情况。

### 5.5 哪些接口要签名

不是所有接口都要。判别方法：**看代码里用的是哪个 `BiliSession` 方法**。

- 用 `getSigned(...)` → 要签名：搜索、收藏夹列表、收藏夹内容
- 用 `getWithParams(...)` → 不签名但要拼参数：播放地址
- 用 `get(...)` → 不签名：nav、视频详情、登录相关；
  热门搜索与排行榜虽然不签名，但会先调 `ensureFingerprint()` 确保拿到 `buvid3`
  （见 §3 表格里这两行的"指纹"列）

> 这三条不是猜的，是实测结果。想改某一个接口的签名策略，先在真机上验证。

---

## 6. 风控与错误码速查

| 现象 | 错误码 | 含义 | 该怎么办 |
|---|---|---|---|
| 搜索/收藏夹报错，提示含 `HTTP 412` | 412 | 风控拦截（短时间请求太频繁） | 等几分钟。不要写并发请求，`bili-io` 是单线程就是为了避免这个 |
| 提示"访问权限不足" | -403 | WBI 签名失效 | 代码会自动刷新密钥重试一次；仍失败则重启 App |
| 提示"账号未登录" | -101 | 未登录 / 凭证过期 | `AuthManager.refreshProfile` 会检测到并清理本地登录态 |
| "风控校验失败" | -352 | 同上 | 同上 |
| 视频不存在 / 已下架 | 各种 | 单曲问题 | 播放时会自动跳过这一首 |
| 无可用音频流 | —— | 付费课程 / 没音轨 | 抛出后该曲目从队列里剔除 |

---

## 7. 怎么新增一个 B 站接口

假设你要加"视频评论列表"。五步：

### 第 1 步：想清楚要不要签名 / 指纹 / 登录

看第 3 节的表格，或找一个同类接口参考。**不确定就先不签名**（多数接口不要）。

### 第 2 步：在 `net/dto/` 新建响应模型

```java
public class CommentDto {
    public List<Item> replies;

    public static class Item {
        public String message;
        @SerializedName("like")     // JSON 键与字段名不一致时用这个
        public long likeCount;
    }
}
```

只映射你真正要用的字段。多余的字段 Gson 会自动忽略，
B 站以后加字段也不会影响你。

### 第 3 步：在 `net/api/` 新建或扩展接口类

```java
public final class CommentApi {

    private static final String URL =
            "https://api.bilibili.com/x/v2/reply/main";
    private static final Type TYPE =
            new TypeToken<ApiResponse<CommentDto>>() { }.getType();

    private CommentApi() {
    }

    /** 取某视频的评论。同步阻塞，必须在子线程调用。 */
    public static List<String> messages(String bvid, int page) throws IOException {
        Map<String, String> params = new TreeMap<>();
        params.put("oid", bvid);
        params.put("pn", String.valueOf(page));
        params.put("type", "1");

        // 三选一：get / getWithParams / getSigned
        CommentDto data = BiliSession.getWithParams(URL, params, TYPE, "获取评论");

        List<String> messages = new ArrayList<>();
        if (data == null || data.replies == null) {
            return messages;
        }
        for (CommentDto.Item item : data.replies) {
            messages.add(item.message);   // 在这里把 dto 翻译成 model
        }
        return messages;
    }
}
```

要点：

- 参数用 `TreeMap`（签名要求字典序，不签名时也无害）
- **返回 `model` 类型，不要返回 `dto`**
- 出错时让 `BiliSession` 抛 `BiliException`，不要自己吞掉

### 第 4 步：在 `MusicRepository` 加一个异步方法

```java
public static void comments(String bvid, Callback<List<String>> callback) {
    run("获取评论", () -> CommentApi.messages(bvid, 1), callback);
}
```

就这一行。线程切换、错误包装都由私有的 `run(...)` 骨架处理了。

### 第 5 步：在界面调用

```java
MusicRepository.comments(song.bvid, new MusicRepository.Callback<List<String>>() {
    @Override
    public void onSuccess(List<String> comments) {
        if (!isViewUsable()) {   // ← 别忘了这个判断
            return;
        }
        // 更新界面
    }

    @Override
    public void onError(String message) {
        if (!isViewUsable()) {
            return;
        }
        // 提示用户
    }
});
```

### 别忘了：

- **不要加并发**：`bili-io` 是单线程，这是为了防风控
- **不要在主线程调 `CommentApi`**：它是同步阻塞的
- **文档也要改**：在本篇第 3 节的表格里加一行

---

## 8. 调试手段

### 8.1 `tools/api-probe/` 探针

`tools/api-probe/` 下有几个**独立**的 Java 小程序，用来在不启动 Android 的情况下
试接口。它们不参与 Gradle 构建，需要单独用 `javac` 编译。

| 文件 | 用途 |
|---|---|
| `_01_BiliApiProbe.java` | 基础接口连通性（搜索 / nav 等） |
| `_02_PlayUrlProbe.java` | 播放地址解析 |
| `_03_AudioProbe.java` | 音频流下载 |
| `_04_WbiVerify.java` | **WBI 签名对照验证**（唯一被实测通过的一个） |
| `_05_FavProbe.java` | 收藏夹接口 |
| `TestLoad.java` | 加载测试入口 |

只有 `_04_WbiVerify.java` 会引用项目源码（`com.yuuuno224.bilimusic.net.WbiSigner`，
纯 Java 无 Android 依赖）。编译它：

```powershell
cd <项目根目录>
javac -encoding UTF-8 -d .probe-out -sourcepath app/src/main/java tools/api-probe/_04_WbiVerify.java
java -Dfile.encoding=UTF-8 -cp .probe-out _04_WbiVerify
```

其余几个探针是**自包含**的独立实验代码（自己拼 URL、自己算签名），
方便在不受项目结构影响的情况下试接口。它们引用的是自己的常量，
改了项目代码不会影响它们，但也**不会跟着项目一起更新**——
如果 B 站接口变了，它们可能会失效。

> 历史遗留：`_04_WbiVerify.java` 原本 `import com.bilimusic.app._01_net.WbiSigner`，
> 那是一个早已废弃的包名（只存在于旧的构建缓存里），导致它根本编译不过。
> 现已改为引用当前真实存在的类。

### 8.2 用 adb 看日志

```powershell
adb logcat -s MusicStore:* AndroidRuntime:E
```

`AndroidRuntime:E` 能抓到崩溃堆栈；
`MusicStore` 是本项目**唯一**打日志的地方（存储读写失败时），
所以想加自己的日志，建议先在这个 tag 列表里补一个新的。
`BiliHttp` / `BiliSession` 等网络层类目前**没有**打日志，
接口报错是通过抛 `BiliException` 冒泡到界面提示的。

### 8.3 直接用浏览器/curl 试接口

有些接口不需要 Cookie 就能试，例如排行榜：

```powershell
curl "https://api.bilibili.com/x/web-interface/ranking/index?rid=3&ps=3"
```

需要签名的接口就不能这样直接试了——签名的参数每次都不一样。

---

继续读：[06-development-guide.md](06-development-guide.md) 开始动手改。
