package com.fortress.upi.core.security.hygiene

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.view.WindowManager
import com.fortress.upi.core.crypto.wipe

/** Window-level defenses applied to every Fortress activity. */
object SecureWindow {
    fun apply(activity: Activity) {
        val window = activity.window
        // No screenshots, screen recording or insecure-display mirroring.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // Hide non-system overlays while we are visible (requires HIDE_OVERLAY_WINDOWS).
        window.setHideOverlayWindows(true)
        // Drop touches when another window obscures ours (tapjacking).
        window.decorView.filterTouchesWhenObscured = true
        // Blank thumbnail in Recents.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity.setRecentsScreenshotEnabled(false)
    }
}

/**
 * Owned secret buffer that is zeroed on close. Use for PIN-adjacent material,
 * unwrapped keys and decrypted payloads; never convert secrets to String.
 */
class SecretBytes private constructor(private val bytes: ByteArray) : AutoCloseable {
    @Volatile
    private var closed = false

    fun <T> use(block: (ByteArray) -> T): T {
        check(!closed) { "secret already destroyed" }
        return block(bytes)
    }

    override fun close() {
        closed = true
        bytes.wipe()
    }

    companion object {
        /** Takes ownership of [bytes]; the caller must not keep a reference. */
        fun adopt(bytes: ByteArray): SecretBytes = SecretBytes(bytes)
    }
}

/** Clipboard writes for user-requested copies of non-secret identifiers (e.g. a UPI ref ID). */
object SafeClipboard {
    fun copy(context: Context, label: String, text: CharSequence, sensitive: Boolean) {
        val clip = ClipData.newPlainText(label, text)
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: suppress clipboard previews and keyboard suggestions.
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    }

    fun clear(context: Context) {
        context.getSystemService(ClipboardManager::class.java)?.clearPrimaryClip()
    }
}
