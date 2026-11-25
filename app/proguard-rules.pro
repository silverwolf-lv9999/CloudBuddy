# CloudBuddy ProGuard/R8 规则
#
# release 已开 isMinifyEnabled + isShrinkResources，这里只保留「反射/序列化/协议」
# 这三类 R8 看不到的引用。业务代码本身不需要 keep。

# ---------- kotlinx-serialization ----------
# 序列化器通过伴生对象的反射/合成方法访问，R8 不知道谁在读。
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations
-keepclassmembers class **$$serializer {
    *** descriptor;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,allowobfuscation @kotlinx.serialization.Serializable class *
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------- OkHttp / Okio ----------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepclassmembers class * extends java.lang.Enum {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------- Koog（本机 agent 框架，反射创建 prompt executor / tool registry）----------
-dontwarn ai.koog.**
-keep class ai.koog.** { *; }
-keepclassmembers class ai.koog.** {
    @kotlinx.serialization.Serializable <fields>;
}
# Koog 用 kotlinx.serialization 的多态注册，序列化器类名参与运行时解析
-keep class **$$serializer { *; }
-keepclassmembers class **$$serializer {
    <fields>;
}

# ---------- Ktor client（Koog 的 HTTP 引擎，SPI 服务加载）----------
-dontwarn io.ktor.**
-keep class io.ktor.client.engine.okhttp.** { *; }
-keepclassmembers class io.ktor.** {
    *** Companion;
}
-keepnames class io.ktor.client.HttpClientEngineContainer

# ---------- org.json ----------
-keep class org.json.** { *; }

# 保留行号，崩溃栈才有意义（不影响体积）
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
