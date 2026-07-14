# ---- Strip all android.util.Log calls from the release build ----
# Requires an -optimize proguard baseline (set in build.gradle.kts). Debug builds keep logs.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static boolean isLoggable(...);
}

# ---- Keep our own code un-obfuscated / un-shrunk ----
# The app relies on manifest-declared components and a few version-guarded classes
# (AppOps API-30 isolation, VpnService). Keeping the package avoids reflection/verify surprises;
# log stripping above still applies.
-keep class com.privacy.camerawatch.** { *; }

# AndroidX/Material are already covered by their consumer rules.
