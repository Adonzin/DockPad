# --- DockPad's background service: started by name (app_process / Shizuku), so keep it whole
-keep class com.adonzin.dockpad.service.** { *; }
-keep class com.adonzin.dockpad.core.** { *; }
-keep class com.adonzin.dockpad.IBridge { *; }
-keep class com.adonzin.dockpad.IBridge$* { *; }
-keep class com.adonzin.dockpad.adb.** { *; }

# --- Built-in wireless debugging (libadb-android and helpers)
-keep class io.github.muntashirakon.adb.** { *; }
-keep class io.github.muntashirakon.crypto.** { *; }
# certificate code looks up extension classes by name
-keep class android.sun.** { *; }
-dontwarn android.sun.**
# only the low-level crypto classes are used directly; the rest can go
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn javax.naming.**

# --- Hidden API access
-keep class org.lsposed.hiddenapibypass.** { *; }

# --- Shizuku
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
