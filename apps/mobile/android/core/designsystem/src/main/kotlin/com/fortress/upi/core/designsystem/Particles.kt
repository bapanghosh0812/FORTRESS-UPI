package com.fortress.upi.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.withFrameNanos
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Generative success burst: an emerald/gold "fortress bloom" that erupts from
 * the confirmation mark, arcs under gravity and dissolves.
 *
 * 120 Hz discipline:
 *  * all particle state lives in pre-allocated primitive arrays (zero
 *    allocation per frame → no GC pauses mid-animation);
 *  * the frame clock is read only inside the draw lambda, so each frame
 *    re-runs the draw phase alone — no recomposition, no relayout;
 *  * integration uses real frame deltas, so 60/90/120/144 Hz look identical.
 */
@Composable
fun SuccessBurst(
    trigger: Int,
    modifier: Modifier = Modifier,
    palette: List<Color> = listOf(FortressColors.Emerald, FortressColors.GoldAccent, FortressColors.Ink, FortressColors.Gold),
    count: Int = 180,
) {
    val density = LocalDensity.current.density
    val system = remember(count) { ParticleSystem(count) }
    val frame = remember { mutableLongStateOf(0L) }

    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        system.spawn(seed = trigger.toLong(), density = density)
        var last = withFrameNanos { it }
        while (system.alive) {
            withFrameNanos { now ->
                system.step(((now - last) / 1_000_000_000f).coerceAtMost(MAX_STEP_SEC))
                last = now
                frame.longValue = now
            }
        }
        frame.longValue = 0L
    }

    Canvas(modifier) {
        if (frame.longValue != 0L) system.draw(this, palette)
    }
}

private const val MAX_STEP_SEC = 1f / 30f

internal class ParticleSystem(private val n: Int) {
    private val x = FloatArray(n)
    private val y = FloatArray(n)
    private val vx = FloatArray(n)
    private val vy = FloatArray(n)
    private val life = FloatArray(n)
    private val ttl = FloatArray(n)
    private val radius = FloatArray(n)
    private val tint = IntArray(n)
    private var rng = 0L
    private var gravity = 0f

    var alive = false
        private set

    fun spawn(seed: Long, density: Float) {
        rng = seed xor 0x5DEECE66DL
        gravity = 900f * density
        for (i in 0 until n) {
            val angle = next() * 2f * PI.toFloat()
            val speed = (220f + next() * 620f) * density
            x[i] = 0f
            y[i] = 0f
            vx[i] = cos(angle) * speed
            vy[i] = sin(angle) * speed - 260f * density
            ttl[i] = 0.9f + next() * 1.1f
            life[i] = ttl[i]
            radius[i] = (1.2f + next() * 3.4f) * density
            tint[i] = (next() * 4).toInt()
        }
        alive = true
    }

    fun step(dt: Float) {
        var any = false
        val drag = 1f - 1.8f * dt
        for (i in 0 until n) {
            if (life[i] <= 0f) continue
            vx[i] *= drag
            vy[i] = vy[i] * drag + gravity * dt
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
            life[i] -= dt
            if (life[i] > 0f) any = true
        }
        alive = any
    }

    fun draw(scope: DrawScope, palette: List<Color>) = with(scope) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        for (i in 0 until n) {
            if (life[i] <= 0f) continue
            val t = life[i] / ttl[i]
            drawCircle(
                color = palette[tint[i] % palette.size].copy(alpha = t * t),
                radius = radius[i] * (0.4f + 0.6f * t),
                center = Offset(cx + x[i], cy + y[i]),
            )
        }
    }

    /** xorshift64* — fast, allocation-free, deterministic per seed (visual use only; not for security). */
    private fun next(): Float {
        rng = rng xor (rng ushr 12)
        rng = rng xor (rng shl 25)
        rng = rng xor (rng ushr 27)
        val v = (rng * 2685821657736338717L) ushr 40
        return v.toFloat() / (1L shl 24).toFloat()
    }
}
