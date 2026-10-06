# SQLCipher loads JNI symbols by class/method name.
-keep class net.zetetic.database.** { *; }
-keep interface net.zetetic.database.** { *; }

# The HCE service and the reconciliation worker are instantiated by the framework.
-keep class com.fortress.upi.core.offline.transport.nfc.FortressHceService { <init>(); }
-keep class com.fortress.upi.core.offline.sync.ReconciliationWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}
