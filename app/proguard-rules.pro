# Proguard rules for APK Scope

# Keep data models used in Room and serialization
-keepclassmembers class * {
    @androidx.room.Entity *;
    @androidx.room.Dao *;
}

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep DeviceAdminReceiver and VpnService
-keep public class com.nadeem.apkscope.spike.SandboxAdminReceiver { *; }
-keep public class com.nadeem.apkscope.sandbox.SandboxVpnService { *; }
-keep public class com.nadeem.apkscope.spike.GateVpnService { *; }
-keep public class com.nadeem.apkscope.sandbox.SandboxWorkerService { *; }
