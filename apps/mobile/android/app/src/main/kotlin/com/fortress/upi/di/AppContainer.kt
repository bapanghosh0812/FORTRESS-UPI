package com.fortress.upi.di

import android.app.Application
import com.fortress.upi.BuildConfig
import com.fortress.upi.core.crypto.Clock
import com.fortress.upi.core.offline.OfflineEngine
import com.fortress.upi.core.offline.protocol.IssuerTrustStore
import com.fortress.upi.core.offline.protocol.ReceiverIdentity
import com.fortress.upi.core.offline.sync.HttpSettlementApi
import com.fortress.upi.core.offline.sync.ReconciliationWorker
import com.fortress.upi.core.security.binding.Binding
import com.fortress.upi.core.security.binding.BindingStatus
import com.fortress.upi.core.security.binding.BindingStore
import com.fortress.upi.core.security.binding.DeviceBindingManager
import com.fortress.upi.core.security.binding.DeviceIdentity
import com.fortress.upi.core.security.binding.RequestSigner
import com.fortress.upi.core.security.binding.SimBindingMonitor
import com.fortress.upi.core.security.integrity.IntegrityReport
import com.fortress.upi.core.security.integrity.PlayIntegrityClient
import com.fortress.upi.core.security.integrity.RuntimeIntegrityScanner
import com.fortress.upi.core.security.keystore.SealedStore
import com.fortress.upi.core.security.network.SecureHttp
import com.fortress.upi.data.ApiClient
import com.fortress.upi.data.CommonLibrary
import com.fortress.upi.data.DirectoryRepository
import com.fortress.upi.data.HttpBindingApi
import com.fortress.upi.data.OfflineIssuerRepository
import com.fortress.upi.data.PaymentLimits
import com.fortress.upi.data.PaymentsRepository
import com.fortress.upi.data.SandboxCommonLibrary
import com.fortress.upi.data.UnlinkedCommonLibrary
import com.fortress.upi.data.UserProfile
import com.fortress.upi.platform.AppLock
import com.fortress.upi.platform.AppPreferences
import com.fortress.upi.platform.ConnectivityObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Hand-rolled dependency graph (explicit, reflection-free, R8-friendly).
 * Swap for Hilt when feature modules multiply; constructor shapes stay the same.
 */
class AppContainer(private val app: Application) {
    val clock: Clock = Clock.SYSTEM
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Device trust ─────────────────────────────────────────────────────
    val identity = DeviceIdentity(app)
    private val bindingStore = BindingStore(SealedStore(app, keyAlias = "fortress.binding.kek.v1", unlockedDeviceRequired = false))

    @Volatile
    private var binding: Binding? = null

    private val scanner = RuntimeIntegrityScanner(app, BuildConfig.EXPECTED_SIGNER_SHA256, releaseBuild = !BuildConfig.DEBUG)
    private val playIntegrity = PlayIntegrityClient(app, BuildConfig.PLAY_CLOUD_PROJECT_NUMBER)
    private val integrityState = MutableStateFlow<IntegrityReport?>(null)
    val integrity: StateFlow<IntegrityReport?> = integrityState.asStateFlow()

    // ── Network ──────────────────────────────────────────────────────────
    private val requestSigner = RequestSigner(sign = identity::signRequest, deviceId = { binding?.deviceId }, clock = clock)
    val http = SecureHttp.client(requestSigner)
    private val apiBase = BuildConfig.API_BASE_URL.toHttpUrl()
    val api = ApiClient(http, apiBase)
    val online: StateFlow<Boolean> = ConnectivityObserver(app).online.stateIn(scope, SharingStarted.Eagerly, true)

    // ── Features ─────────────────────────────────────────────────────────
    private val commonLibrary: CommonLibrary = if (BuildConfig.DEBUG) SandboxCommonLibrary() else UnlinkedCommonLibrary()
    val payments = PaymentsRepository(api, commonLibrary)
    val issuer = OfflineIssuerRepository(api)
    val trust: IssuerTrustStore = IssuerTrustStore.parse(BuildConfig.ISSUER_KEYS, clock)
    val offlineEngine = OfflineEngine(
        context = app,
        trust = trust,
        settlementApi = HttpSettlementApi(http, apiBase),
        clock = clock,
        receiverIdentity = { binding?.let(::receiverIdentityOf) },
    )
    val bindingManager = DeviceBindingManager(
        identity = identity,
        sim = SimBindingMonitor(app),
        integrity = playIntegrity,
        scanner = scanner,
        api = HttpBindingApi(api),
        store = bindingStore,
        clock = clock,
    )
    val prefs = AppPreferences(app)
    val appLock = AppLock(clock, prefs.autoLock.value.millis)
    val directory = DirectoryRepository(api)

    private val limitsState = MutableStateFlow(PaymentLimits.FALLBACK)
    /** Server-published limits (NPCI/bank/RBI rules); built-in defaults until the first fetch. */
    val limits: StateFlow<PaymentLimits> = limitsState.asStateFlow()

    private val profileState = MutableStateFlow<UserProfile?>(null)
    val profile: StateFlow<UserProfile?> = profileState.asStateFlow()

    /** null while loading; true once this device holds a valid binding (or debug demo mode). */
    private val boundState = MutableStateFlow<Boolean?>(null)
    val bound: StateFlow<Boolean?> = boundState.asStateFlow()

    /** Debug-only exploration without a backend: demo data, no money movement. */
    @Volatile
    var demoMode: Boolean = false
        private set

    val isBound: Boolean get() = binding != null

    fun onAppStart() {
        scope.launch(Dispatchers.IO) {
            refreshBindingNow()
            integrityState.value = scanner.scan()
            if (BuildConfig.PLAY_CLOUD_PROJECT_NUMBER > 0) runCatching { playIntegrity.warmUp() }
            ReconciliationWorker.enqueue(app)
            refreshPolicyNow()
        }
        // Every offline → online transition flushes pending offline bundles and refreshes policy.
        scope.launch {
            online.drop(1).filter { it }.collect {
                ReconciliationWorker.enqueue(app)
                refreshPolicyNow()
            }
        }
        scope.launch { prefs.autoLock.collect { appLock.timeoutMs = it.millis } }
    }

    private suspend fun refreshPolicyNow() {
        if (!isBound) return
        runCatching { directory.limits(clock.nowMillis()) }.onSuccess { limitsState.value = it }
        runCatching { directory.profile() }.onSuccess { profileState.value = it }
    }

    /** Sign out: destroys this device's keys and binding. UPI needs SIM re-verification afterwards. */
    fun signOut() {
        scope.launch(Dispatchers.IO) {
            runCatching { bindingManager.revoke() }
            binding = null
            demoMode = false
            profileState.value = null
            boundState.value = false
        }
    }

    fun refreshBinding() {
        scope.launch(Dispatchers.IO) { refreshBindingNow() }
    }

    private fun refreshBindingNow() {
        val status = runCatching { bindingManager.status() }.getOrDefault(BindingStatus.UNBOUND)
        binding = if (status == BindingStatus.BOUND) runCatching { bindingStore.load() }.getOrNull() else null
        boundState.value = binding != null || demoMode
    }

    fun enterDemoMode() {
        check(BuildConfig.DEBUG) { "demo mode is debug-only" }
        demoMode = true
        boundState.value = true
    }

    fun enqueueSync() = ReconciliationWorker.enqueue(app)

    private fun receiverIdentityOf(b: Binding): ReceiverIdentity = object : ReceiverIdentity {
        override val signedReceiverCert: ByteArray = b.receiverCertificate
        override val receiverId: ByteArray = b.receiverId
        override fun sign(domain: String, body: ByteArray): ByteArray = identity.signWithIdentity(domain, body)
    }
}
