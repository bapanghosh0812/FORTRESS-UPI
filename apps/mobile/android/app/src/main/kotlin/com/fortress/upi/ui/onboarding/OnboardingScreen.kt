package com.fortress.upi.ui.onboarding

import android.Manifest
import android.content.ActivityNotFoundException
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fortress.upi.BuildConfig
import com.fortress.upi.R
import com.fortress.upi.core.designsystem.FortressColors
import com.fortress.upi.core.designsystem.FortressSpace
import com.fortress.upi.core.designsystem.GhostButton
import com.fortress.upi.core.designsystem.GlassCard
import com.fortress.upi.core.designsystem.GoldButton
import com.fortress.upi.core.security.binding.DeviceBindingManager
import com.fortress.upi.data.ApiException
import com.fortress.upi.di.AppContainer
import com.fortress.upi.ui.components.SectionLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class BindStep { Idle, Keys, Sms, Verifying, Done, Failed }

/**
 * First run: welcome → runtime permissions → device binding (attested keys +
 * SMS from the SIM) → done. Bank linking happens on the PSP's account-discovery
 * screen after binding (needs a bound device for signed requests).
 */
@Composable
fun OnboardingScreen(container: AppContainer, onBound: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(FortressSpace.gutter)) {
        AnimatedContent(step, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "onboarding") { s ->
            when (s) {
                0 -> Welcome(onNext = { step = 1 }, onDemo = if (BuildConfig.DEBUG) ({ container.enterDemoMode() }) else null)
                1 -> Permissions(onNext = { step = 2 })
                else -> Binding(container.bindingManager, onBound = onBound)
            }
        }
    }
}

@Composable
private fun Welcome(onNext: () -> Unit, onDemo: (() -> Unit)?) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.fortress_mark), contentDescription = "Fortress", modifier = Modifier.size(160.dp))
        }
        Text("Pay anytime. Even offline.", style = MaterialTheme.typography.displayMedium, color = FortressColors.Ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(
            "UPI with hardware-sealed keys, and an offline vault that works when the network doesn't.",
            style = MaterialTheme.typography.bodyLarge, color = FortressColors.InkMuted, textAlign = TextAlign.Center,
        )
        GoldButton("Get started", onNext, Modifier.fillMaxWidth())
        if (onDemo != null) GhostButton("Explore demo (debug build)", onDemo, Modifier.fillMaxWidth())
    }
}

@Composable
private fun Permissions(onNext: () -> Unit) {
    val permissions = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onNext() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionLabel("STEP 1 OF 2")
        Text("A few permissions", style = MaterialTheme.typography.headlineSmall, color = FortressColors.Ink)
        Text("Fortress asks only for what a feature needs. You can change these later.", style = MaterialTheme.typography.bodyLarge, color = FortressColors.InkMuted)
        GlassCard(Modifier.fillMaxWidth()) {
            PermissionRow("Camera", "Scan QR codes · frames stay on this phone")
            PermissionRow("Phone", "Bind this SIM to your account")
            PermissionRow("Nearby devices", "Pay offline over Bluetooth · no location used")
            PermissionRow("Notifications", "Payment alerts and settlement updates")
        }
        Spacer(Modifier.weight(1f))
        GoldButton("Continue", { launcher.launch(permissions) }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun PermissionRow(title: String, subtitle: String) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = FortressColors.Ink)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = FortressColors.InkMuted)
    }
}

@Composable
private fun Binding(manager: DeviceBindingManager, onBound: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(BindStep.Idle) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<DeviceBindingManager.Pending?>(null) }

    fun start() {
        scope.launch {
            error = null
            try {
                state = BindStep.Keys
                val p = manager.start()
                pending = p
                state = BindStep.Sms
                try {
                    context.startActivity(p.smsIntent)
                } catch (e: ActivityNotFoundException) {
                    throw IllegalStateException("No SMS app available to send the binding message")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = BindStep.Failed
                error = bindingMessage(e)
            }
        }
    }

    fun finish() {
        val p = pending ?: return
        scope.launch {
            try {
                state = BindStep.Verifying
                manager.finish(p)
                state = BindStep.Done
                onBound()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = BindStep.Failed
                error = bindingMessage(e)
            }
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionLabel("STEP 2 OF 2")
        Text("Secure this phone", style = MaterialTheme.typography.headlineSmall, color = FortressColors.Ink)
        GlassCard(Modifier.fillMaxWidth()) {
            StepRow("Create keys inside StrongBox", state.ordinal > BindStep.Keys.ordinal, state == BindStep.Keys)
            StepRow("Send one secure SMS from this SIM", state.ordinal > BindStep.Sms.ordinal, state == BindStep.Sms)
            StepRow("Verify device with key attestation", state == BindStep.Done, state == BindStep.Verifying)
        }
        Text(
            "Your phone sends one SMS to prove this SIM is yours. Keys never leave the secure hardware.",
            style = MaterialTheme.typography.bodyMedium, color = FortressColors.InkMuted,
        )
        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = FortressColors.Danger) }
        Spacer(Modifier.weight(1f))
        when (state) {
            BindStep.Idle, BindStep.Failed -> GoldButton(if (state == BindStep.Failed) "Try again" else "Bind this phone", ::start, Modifier.fillMaxWidth())
            BindStep.Sms -> GoldButton("I've sent the SMS", ::finish, Modifier.fillMaxWidth())
            else -> GoldButton("Securing…", {}, Modifier.fillMaxWidth(), enabled = false)
        }
    }
}

@Composable
private fun StepRow(text: String, done: Boolean, active: Boolean) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(12.dp).clip(CircleShape).background(
                when {
                    done -> FortressColors.Emerald
                    active -> FortressColors.GoldAccent
                    else -> FortressColors.Line
                },
            ),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (done || active) FortressColors.Ink else FortressColors.InkDim,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

private fun bindingMessage(e: Exception): String = when (e) {
    is ApiException -> if (e.status == 0) "No connection. Binding needs the internet once." else "Binding was refused (${e.code}). Try again."
    else -> e.message ?: "Binding failed. Try again."
}
