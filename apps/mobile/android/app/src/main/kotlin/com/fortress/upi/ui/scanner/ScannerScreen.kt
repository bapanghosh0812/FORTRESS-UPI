package com.fortress.upi.ui.scanner

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fortress.upi.core.designsystem.FortressColors
import com.fortress.upi.core.designsystem.FortressSpace
import com.fortress.upi.core.designsystem.GoldButton
import com.fortress.upi.core.designsystem.rememberHaptics
import com.fortress.upi.ui.components.Glyph
import com.fortress.upi.ui.components.RoundIconButton
import com.fortress.upi.upi.UpiPaymentIntent
import com.fortress.upi.upi.UpiUri
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Smart Scanner.
 *  * On-device ML Kit QR model (no frames leave the phone), KEEP_ONLY_LATEST
 *    backpressure so analysis never queues stale frames.
 *  * AI assist: ML Kit zoom suggestions auto-zoom on small/distant codes, and
 *    AF/AE metering is pointed at the detected code the moment it appears.
 *  * Gold brackets spring-"magnetize" onto the code, haptic lock, hand-off to
 *    review within ~150 ms. Non-UPI codes are refused with a hint.
 */
@Composable
fun ScannerScreen(onResult: (UpiPaymentIntent) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    var torch by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("Align any UPI QR inside the frame") }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraScanner(torch = torch, onHint = { hint = it }, onResult = onResult)
        } else {
            Column(Modifier.align(Alignment.Center).padding(FortressSpace.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Camera access is needed to scan UPI codes. Frames are processed on this device only.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = FortressColors.Ink,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.padding(12.dp))
                GoldButton("Allow camera", onClick = { launcher.launch(Manifest.permission.CAMERA) })
            }
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(FortressSpace.gutter)) {
            RoundIconButton(Glyph.BACK, "Close scanner", onClose)
            Spacer(Modifier.weight(1f))
            RoundIconButton(Glyph.TORCH, if (torch) "Torch off" else "Torch on", { torch = !torch })
        }
        Text(
            hint,
            style = MaterialTheme.typography.bodyLarge,
            color = FortressColors.Ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 48.dp, start = 32.dp, end = 32.dp),
        )
    }
}

@Composable
private fun CameraScanner(torch: Boolean, onHint: (String) -> Unit, onResult: (UpiPaymentIntent) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val bracket = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var locked by remember { mutableStateOf(false) }
    var lastFocusAt by remember { mutableStateOf(0L) }

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        }
    }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            this.controller = controller
        }
    }

    LaunchedEffect(torch) { controller.enableTorch(torch) }
    LaunchedEffect(viewSize) {
        if (!locked && viewSize != IntSize.Zero) {
            val side = viewSize.width * 0.66f
            val left = (viewSize.width - side) / 2f
            val top = (viewSize.height - side) / 2.4f
            bracket.snapTo(Rect(left, top, left + side, top + side))
        }
    }

    DisposableEffect(lifecycleOwner) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .setZoomSuggestionOptions(
                    ZoomSuggestionOptions.Builder { ratio ->
                        controller.setZoomRatio(ratio)
                        true
                    }.setMaxSupportedZoomRatio(MAX_AUTO_ZOOM).build(),
                )
                .build(),
        )
        controller.setImageAnalysisAnalyzer(
            analysisExecutor,
            MlKitAnalyzer(listOf(scanner), ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED, ContextCompat.getMainExecutor(context)) { result ->
                if (locked) return@MlKitAnalyzer
                val code = result.getValue(scanner)?.firstOrNull { it.rawValue != null && it.boundingBox != null } ?: return@MlKitAnalyzer
                val box = checkNotNull(code.boundingBox)

                val now = System.nanoTime()
                if (now - lastFocusAt > FOCUS_INTERVAL_NS) {
                    lastFocusAt = now
                    val point = previewView.meteringPointFactory.createPoint(box.exactCenterX(), box.exactCenterY())
                    controller.cameraControl?.startFocusAndMetering(
                        FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                            .setAutoCancelDuration(2, TimeUnit.SECONDS)
                            .build(),
                    )
                }

                val intent = UpiUri.parse(checkNotNull(code.rawValue))
                if (intent == null) {
                    onHint("That's not a UPI payment code")
                    return@MlKitAnalyzer
                }
                locked = true
                haptics.confirm()
                scope.launch {
                    bracket.animateTo(box.toComposeRect(), spring(dampingRatio = 0.82f, stiffness = 1_400f))
                    delay(LOCK_HOLD_MS)
                    onResult(intent)
                }
            },
        )
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            scanner.close()
            analysisExecutor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize().onSizeChanged { viewSize = it }) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        ScanOverlay(bracket.value, locked)
    }
}

/** Dimmed surround with a clear window and gold corner brackets (drawn in one offscreen layer). */
@Composable
private fun ScanOverlay(window: Rect, locked: Boolean) {
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        if (window == Rect.Zero) return@Canvas
        val radius = 26.dp.toPx()
        drawRect(Color.Black.copy(alpha = 0.55f))
        drawRoundRect(Color.Transparent, window.topLeft, window.size, CornerRadius(radius), blendMode = BlendMode.Clear)

        // Over the camera feed, so fixed colours rather than theme tokens.
        val color = if (locked) Color(0xFF35D49A) else FortressColors.HeroGold
        val len = window.width * 0.18f
        val w = 4.dp.toPx()
        val corners = listOf(
            window.topLeft to Offset(1f, 1f),
            window.topRight to Offset(-1f, 1f),
            window.bottomLeft to Offset(1f, -1f),
            window.bottomRight to Offset(-1f, -1f),
        )
        for ((c, d) in corners) {
            drawLine(color, c, c + Offset(len * d.x, 0f), w, StrokeCap.Round)
            drawLine(color, c, c + Offset(0f, len * d.y), w, StrokeCap.Round)
        }
    }
}

private const val MAX_AUTO_ZOOM = 4f
private const val FOCUS_INTERVAL_NS = 600_000_000L
private const val LOCK_HOLD_MS = 140L
