# R8 开启后（release: isMinifyEnabled=true + isShrinkResources=true）的保留规则。
# 原则：只保「反射 / 序列化按名字找代码」的部分，其余全交给 R8 删。

# ---------- kotlinx-serialization ----------
# 官方推荐规则。** 通配版本把依赖库（Koog、ktor、MCP SDK）里的 @Serializable
# 类也罩住——它们同样靠生成的 serializer 类按命名约定反射查找。
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**

-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class **$$serializer {
    *** INSTANCE;
}

# ---------- 网络栈 ----------
# OkHttp 4.12 自带 consumer rules，这里只补它探测可选依赖时的缺类告警。
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**
# ktor 探测多平台引擎（native / darwin / winhttp…）时引用的类在 Android 上不存在。
-dontwarn io.ktor.**
