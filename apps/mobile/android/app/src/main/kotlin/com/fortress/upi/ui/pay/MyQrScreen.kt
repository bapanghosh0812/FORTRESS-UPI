package com.fortress.upi.ui.pay

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fortress.upi.R
import com.fortress.upi.core.designsystem.FortressBrushes
import com.fortress.upi.core.designsystem.FortressColors
import com.fortress.upi.core.designsystem.FortressShape
import com.fortress.upi.core.designsystem.FortressSpace
import com.fortress.upi.core.designsystem.GhostButton
import com.fortress.upi.core.designsystem.GlassCard
import com.fortress.upi.data.UserProfile
import com.fortress.upi.ui.components.Avatar
import com.fortress.upi.ui.components.Glyph
import com.fortress.upi.ui.components.RoundIconButton
import com.fortress.upi.ui.dashboard.copyToClipboard
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Standard UPI collect URI that every UPI app can scan. */
fun upiQrUri(vpa: String, name: String): String = "upi://pay?pa=${Uri.encode(vpa)}&pn=${Uri.encode(name)}&cu=INR"

/** A QR code drawn module by module. Level H leaves room for the logo in the centre. */
@Composable
fun QrCode(content: String, modifier: Modifier = Modifier, color: Color = Color(0xFF0C1633)) {
    val matrix = remember(content) {
        QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H, EncodeHintType.MARGIN to 0),
        )
    }
    Canvas(modifier.semantics { contentDescription = "UPI QR code" }) {
        val n = matrix.width
        val cell = size.minDimension / n
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (matrix[x, y]) drawRect(color, Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
            }
        }
    }
}

@Composable
fun MyQrScreen(profile: UserProfile?, onBack: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = FortressSpace.gutter, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Glyph.BACK, "Back", onBack)
            Text("My QR", style = MaterialTheme.typography.headlineSmall, color = FortressColors.Ink, modifier = Modifier.padding(start = 12.dp))
        }
        if (profile == null) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(
                    "Your QR code appears here once a bank account is linked to your UPI ID.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = FortressColors.InkMuted,
                )
            }
            return@Column
        }
        val uri = upiQrUri(profile.vpa, profile.name)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(FortressShape.card))
                .background(FortressBrushes.hero)
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Avatar(profile.name, size = 60.dp)
            Text(profile.name, style = MaterialTheme.typography.headlineSmall, color = FortressColors.OnHero)
            Text(profile.vpa, style = MaterialTheme.typography.bodyMedium, color = FortressColors.OnHero.copy(alpha = 0.82f))
            Box(
                Modifier.padding(top = 6.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                QrCode(uri, Modifier.size(228.dp))
                Image(
                    painterResource(R.drawable.fortress_mark),
                    contentDescription = null,
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(5.dp),
                )
            }
            Text(
                "Scan with any UPI app to pay",
                style = MaterialTheme.typography.bodyMedium,
                color = FortressColors.OnHero.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Copy UPI ID", { copyToClipboard(context, "UPI ID", profile.vpa) }, Modifier.weight(1f))
            GhostButton(
                "Share",
                {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Pay ${profile.name} on any UPI app: ${profile.vpa}\n$uri")
                    context.startActivity(Intent.createChooser(send, "Share UPI ID"))
                },
                Modifier.weight(1f),
            )
        }
    }
}
