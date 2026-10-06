package com.fortress.upi.core.designsystem

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** Stroke a rounded rect inset by half the stroke so it never clips. */
internal fun DrawScope.drawRoundRectStroke(brush: Brush, radius: Float, stroke: Stroke) {
    val inset = stroke.width / 2f
    drawRoundRect(
        brush = brush,
        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
        size = androidx.compose.ui.geometry.Size(size.width - stroke.width, size.height - stroke.width),
        cornerRadius = CornerRadius(radius, radius),
        style = stroke,
    )
}
