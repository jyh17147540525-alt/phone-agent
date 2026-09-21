# ══════════════════════════════════════════════════════════════
#  R8 / ProGuard 规则 —— PocketAgent
# ══════════════════════════════════════════════════════════════
#
# 总体原则：**尽量少写 keep 规则**。
#
# 每加一条 `-keep`，就等于从混淆器手里抢走一块它本可以帮你保护的东西。
# 而本项目的目标机型是社区用户的手机，逆向门槛越低，"改一改就说是自己的"
# 和"注入一段就变成木马"这两件事就越容易发生。
#
# 所以下面只保留两类规则：① 反射调用必须保留的 ② 序列化契约必须保留的。
# 任何"加上就好了"式的宽容规则都不加 —— 那类规则会一路累积成一张渔网。

# ── 保留行号，否则线上崩溃栈全是 a.b.c()，没法排查 ─────────────
# 只保留行号不保留源码文件名，崩溃报告里不会泄露源码结构
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── kotlinx.serialization ──────────────────────────────────────
# 生成的 serializer 通过反射查找伴生对象，被混淆掉就会在运行时
# 抛 SerializationException，而且**只在运行时**，编译期毫无征兆。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.pocketagent.** {
    *** Companion;
}
-keepclasseswithmembers class com.pocketagent.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.pocketagent.**$$serializer { *; }

# ── OkHttp / Okio ──────────────────────────────────────────────
# 它们自带 consumer rules，通常不需要额外配置。
# 但 OkHttp 在部分平台上会反射探测可选依赖（BouncyCastle、Conscrypt 等），
# 探测失败只是走降级路径，不需要 keep —— 这里的 -dontwarn 是为了
# 让构建日志干净，避免真正的警告被淹掉。
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── 插件契约的公开字段名 ───────────────────────────────────────
# ⚠️ 插件清单的 JSON 字段名是**对第三方作者的公开契约**。
#    作者照着文档写 `"apiVersion"`，本体就不能因为混淆把它改成 `"a"`。
#    序列化器本身由 @Serializable 保证，这里额外兜一层是因为
#    PluginManifest 是跨版本兼容的负债 —— 改错了要等作者来报 bug 才发现。
-keepclassmembers class com.pocketagent.plugin.api.** {
    <fields>;
}
