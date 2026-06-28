# Xposed 入口类不被混淆
-keep class com.moekoe.xposed.kugoulite.MainHook { *; }

# ContentProvider 不被混淆
-keep class com.moekoe.xposed.kugoulite.StateProvider { *; }

# 保留 Xposed 接口
-keep class de.robv.android.xposed.** { *; }
-keepclassmembers class * {
    public <init>(android.content.Context);
}
