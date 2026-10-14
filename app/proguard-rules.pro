# =============================================================================
# R8 / ProGuard 规则
# =============================================================================
# 什么时候会用到这个文件？
#   只有 app/build.gradle 里把 release 的 minifyEnabled 设成 true 时才生效。
#   当前项目是 false（不混淆），因为混淆后一旦规则写漏，问题只在 release 包出现，
#   排查成本很高。规则先写在这里备好，等要开启混淆时再打开开关。
#
# 混淆的两个基本原则：
#   1. 被反射用到的类/字段/方法，名字不能被改 —— 否则反射找不到它们；
#   2. 只有注解和泛型签名会被"擦掉"，需要 keepattributes 保留。
# =============================================================================

# -----------------------------------------------------------------------------
# Gson：通过反射读写字段，字段名就是 JSON 的键名
# -----------------------------------------------------------------------------
# 这是本项目最容易踩坑的地方。举例：
#   class Song { public String bvid; }
#   Gson 依赖字段名 "bvid" 去匹配 JSON 里的 "bvid"。
#   如果 R8 把字段改名成 "a"，解析出来的 Song 就全是 null。
#
# 所以所有 DTO 与本地存储用到的模型都必须整类保留。
-keep class com.yuuuno224.bilimusic.net.dto.** { *; }
-keep class com.yuuuno224.bilimusic.model.** { *; }
-keep class com.yuuuno224.bilimusic.store.MusicStore$StoreData { *; }

# 保留泛型签名（ApiResponse<List<Xxx>> 这种嵌套泛型靠它才能解析）
-keepattributes Signature
# 保留注解（@SerializedName 就是注解，被擦掉就映射不上 snake_case 的键）
-keepattributes *Annotation*
# 保留行号，出错时堆栈里才有可读的行号
-keepattributes SourceFile,LineNumberTable

# -----------------------------------------------------------------------------
# 其它
# -----------------------------------------------------------------------------
# java.* / javax.* 的部分包在 Android 上不存在，让 R8 不要因为找不到而报错
-dontwarn javax.naming.**
-dontwarn java.beans.**
