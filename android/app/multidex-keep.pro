# 多 dex 主 dex 保留规则（minSdk 19 / Android 4.4）。
#
# 背景：minSdk 低于 21 时 Dalvik 在 MultiDex.install 之前只能看到 classes.dex，
# 启动链路一旦落到第二个 dex，4.4 上会直接 NoClassDefFoundError。
# 这里把启动链路与被反射调用的类钉在主 dex 里。
#
# 播放器与数据源由 Media3 / OkHttp 直接引用，不反射，不在此列。

-keep class com.hgtv.star.MainActivity { *; }
-keep class com.hgtv.star.** extends android.app.Activity { *; }
-keep class com.hgtv.star.** extends android.app.Application { *; }

# 反射 / 序列化用到的类。
-keep class com.hgtv.star.data.** { *; }
-keep class com.hgtv.star.core.** { *; }

# MultiDex 自身。
-keep class androidx.multidex.** { *; }
-keep class androidx.multidex.MultiDexApplication { *; }

# Kotlin 反射与元数据。
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, RuntimeVisible*Annotations

# 友盟 / 第三方 SDK 的入口。
-keep class com.umeng.** { *; }
-dontwarn com.umeng.**
