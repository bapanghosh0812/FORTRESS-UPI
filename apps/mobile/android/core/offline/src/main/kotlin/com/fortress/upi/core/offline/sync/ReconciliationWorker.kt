package com.fortress.upi.core.offline.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fortress.upi.core.offline.OfflineEngineProvider
import java.util.concurrent.TimeUnit

/**
 * Uploads every pending bundle (sent, received, redeemed) once a validated
 * network is available. Settlement is idempotent server-side, so both payer
 * and payee uploading the same bundle is expected and safe.
 */
class ReconciliationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val engine = (applicationContext as? OfflineEngineProvider)?.offlineEngine() ?: return Result.failure()
        val db = engine.database()
        var retry = false
        for (item in db.outbox().pending(BATCH)) {
            when (val r = engine.settlementApi.submit(item.bundle)) {
                SubmitResult.Settled, SubmitResult.Duplicate -> db.outbox().markSynced(item.id)
                SubmitResult.Conflict -> db.outbox().markRejected(item.id, "CONFLICT")
                is SubmitResult.Rejected -> db.outbox().markRejected(item.id, r.code)
                is SubmitResult.RetryLater -> {
                    db.outbox().markAttempt(item.id, r.reason)
                    retry = true
                }
            }
        }
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        private const val BATCH = 50
        private const val UNIQUE_NAME = "fortress-offline-reconcile"

        /** Safe to call often (KEEP): on connectivity regained, app start, after each offline payment. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ReconciliationWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
