# ── Fortress release hardening (R8 full mode is the AGP default) ──

# Flatten packages and strip source metadata: raises the cost of static analysis.
-repackageclasses 'f'
-allowaccessmodification
-renamesourcefileattribute ''
-keepattributes !SourceFile,!LineNumberTable

# Remove every android.util.Log call (no log leakage even if one slips past review).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static boolean isLoggable(java.lang.String, int);
}

# Kotlin null-check intrinsics carry parameter names; strip them in release.
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullParameter(java.lang.Object, java.lang.String);
    static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
}

# Retrace mapping files are uploaded to Play Console from CI and kept out of VCS.
# For stronger protection (string/class encryption, native RASP, anti-tamper),
# layer a commercial obfuscator (DexGuard / Appdome) on top of R8 in the release job.
