package com.fortress.upi.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fortress.upi.core.designsystem.FortressColors
import com.fortress.upi.di.AppContainer
import com.fortress.upi.platform.AuthGate
import com.fortress.upi.ui.dashboard.DashboardScreen
import com.fortress.upi.ui.dashboard.DemoProfile
import com.fortress.upi.ui.history.ActivityScreen
import com.fortress.upi.ui.lock.LockScreen
import com.fortress.upi.ui.offline.OfflineScreen
import com.fortress.upi.ui.onboarding.OnboardingScreen
import com.fortress.upi.ui.pay.PaymentReviewScreen
import com.fortress.upi.ui.pay.Receipt
import com.fortress.upi.ui.pay.MyQrScreen
import com.fortress.upi.ui.pay.SuccessScreen
import com.fortress.upi.ui.scanner.ScannerScreen
import com.fortress.upi.ui.settings.SettingsScreen
import com.fortress.upi.ui.shell.FortressTabBar
import com.fortress.upi.ui.shell.Tab
import com.fortress.upi.upi.UpiPaymentIntent

/** Full-screen flows that sit above the tabbed shell. */
sealed interface Overlay {
    data object Scanner : Overlay
    data class Review(val intent: UpiPaymentIntent) : Overlay
    data class Paid(val receipt: Receipt) : Overlay
    data object MyQr : Overlay
}

/**
 * App gate → shell. Order matters: a device must be bound before it can be
 * unlocked, and nothing renders behind the lock screen.
 */
@Composable
fun FortressNavHost(container: AppContainer, authGate: AuthGate, incomingUpi: MutableState<UpiPaymentIntent?>) {
    val bound by container.bound.collectAsStateWithLifecycle()
    val locked by container.appLock.locked.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(FortressColors.Background)) {
        when {
            bound == null -> Unit // binding status loads in < 100 ms; the splash window covers it
            bound == false -> OnboardingScreen(container, onBound = container::refreshBinding)
            locked -> LockScreen(authGate, onUnlocked = container.appLock::unlock)
            else -> Shell(container, authGate, incomingUpi)
        }
    }
}

@Composable
private fun Shell(container: AppContainer, authGate: AuthGate, incomingUpi: MutableState<UpiPaymentIntent?>) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val overlays = remember { mutableStateListOf<Overlay>() }
    val online by container.online.collectAsStateWithLifecycle()
    val profile by container.profile.collectAsStateWithLifecycle()

    fun push(o: Overlay) {
        overlays.add(o)
    }

    fun pop() {
        if (overlays.isNotEmpty()) overlays.removeAt(overlays.lastIndex)
    }

    LaunchedEffect(incomingUpi.value) {
        incomingUpi.value?.let {
            overlays.clear()
            push(Overlay.Review(it))
            incomingUpi.value = null
        }
    }
    BackHandler(enabled = overlays.isNotEmpty() || tab != Tab.HOME) {
        if (overlays.isNotEmpty()) pop() else tab = Tab.HOME
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(tab, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "tab") { t ->
            when (t) {
                Tab.HOME -> DashboardScreen(
                    container = container,
                    online = online,
                    onScan = { push(Overlay.Scanner) },
                    onOffline = { tab = Tab.VAULT },
                    onHistory = { tab = Tab.ACTIVITY },
                    onMyQr = { push(Overlay.MyQr) },
                    onPay = { push(Overlay.Review(it)) },
                )
                Tab.ACTIVITY -> ActivityScreen(container)
                Tab.VAULT -> OfflineScreen(container = container, authGate = authGate, online = online, onBack = { tab = Tab.HOME })
                Tab.PROFILE -> SettingsScreen(container, onMyQr = { push(Overlay.MyQr) }, onOpenVault = { tab = Tab.VAULT })
            }
        }
        FortressTabBar(tab, onSelect = { tab = it }, onScan = { push(Overlay.Scanner) }, modifier = Modifier.align(Alignment.BottomCenter))

        AnimatedContent(
            targetState = overlays.lastOrNull(),
            transitionSpec = {
                val slide = spring(dampingRatio = 0.9f, stiffness = 520f, visibilityThreshold = IntOffset(1, 1))
                (slideInHorizontally(slide) { it / 5 } + fadeIn(tween(220))) togetherWith (slideOutHorizontally(slide) { it / 8 } + fadeOut(tween(160)))
            },
            label = "overlay",
        ) { o ->
            if (o != null) {
                Box(Modifier.fillMaxSize().background(FortressColors.Background)) {
                    when (o) {
                        Overlay.Scanner -> ScannerScreen(
                            onResult = {
                                pop()
                                push(Overlay.Review(it))
                            },
                            onClose = ::pop,
                        )
                        is Overlay.Review -> PaymentReviewScreen(
                            container = container,
                            intent = o.intent,
                            online = online,
                            onUseOffline = {
                                overlays.clear()
                                tab = Tab.VAULT
                            },
                            onPaid = { receipt ->
                                pop()
                                push(Overlay.Paid(receipt))
                            },
                            onBack = ::pop,
                        )
                        is Overlay.Paid -> SuccessScreen(o.receipt, onDone = {
                            overlays.clear()
                            tab = Tab.ACTIVITY
                        })
                        Overlay.MyQr -> MyQrScreen(profile ?: DemoProfile.takeIf { container.demoMode }, onBack = ::pop)
                    }
                }
            }
        }
    }
}
