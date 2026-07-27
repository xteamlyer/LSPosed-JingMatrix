# The zygisk hooker reaches the manager entirely by reflection: it loads
# "<managerPackage>.Constants" out of the injected dex and invokes the static
# setBinder(IBinder) on it. Neither the class nor the method has a call site inside
# this APK, so R8 would otherwise remove or rename both and the binder handshake
# would fail silently at runtime.
-keep class org.matrix.vector.manager.Constants {
    public static boolean setBinder(android.os.IBinder);
}

# ParasiticManagerHooker redirects the resolved activity to this class by name.
-keep class org.matrix.vector.manager.ui.MainActivity { <init>(); }

# AIDL stubs and the parcelables crossing the daemon boundary.
-keep class org.lsposed.lspd.** { *; }
-keep class rikka.parcelablelist.** { *; }

# kotlinx.serialization keeps generated serializers reachable from the companion.
-keepclassmembers class **$$serializer { *** descriptor; }
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Gson models are constructed reflectively from field names.
-keepclassmembers class org.matrix.vector.manager.data.model.** { <fields>; }

# OkHttp / Okio ship analysis-only references to optional platform classes.
-dontwarn okhttp3.internal.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
