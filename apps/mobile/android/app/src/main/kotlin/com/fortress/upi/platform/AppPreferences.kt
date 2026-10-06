package com.fortress.upi.platform

import android.content.Context
import androidx.core.content.edit
import com.fortress.upi.core.designsystem.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AutoLock(val label: String, val millis: Long) {
    IMMEDIATELY("Immediately", 0L),
    ONE_MINUTE("After 1 minute", 60_000L),
    FIVE_MINUTES("After 5 minutes", 300_000L),
}

/**
 * Non-sensitive UI preferences (theme, balance masking, auto-lock). Nothing
 * here grants access to money, so plain SharedPreferences is appropriate;
 * keys, tokens and notes live in the Keystore and the encrypted database.
 */
class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("fortress.prefs", Context.MODE_PRIVATE)

    private val themeState = MutableStateFlow(enumOr(prefs.getString(KEY_THEME, null), ThemeMode.SYSTEM))
    val theme: StateFlow<ThemeMode> = themeState.asStateFlow()

    private val hideState = MutableStateFlow(prefs.getBoolean(KEY_HIDE, true))
    val hideBalances: StateFlow<Boolean> = hideState.asStateFlow()

    private val lockState = MutableStateFlow(enumOr(prefs.getString(KEY_LOCK, null), AutoLock.ONE_MINUTE))
    val autoLock: StateFlow<AutoLock> = lockState.asStateFlow()

    private val warnState = MutableStateFlow(prefs.getBoolean(KEY_WARN, true))
    val payeeWarnings: StateFlow<Boolean> = warnState.asStateFlow()

    fun setTheme(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME, mode.name) }
        themeState.value = mode
    }

    fun setHideBalances(hide: Boolean) {
        prefs.edit { putBoolean(KEY_HIDE, hide) }
        hideState.value = hide
    }

    fun setAutoLock(lock: AutoLock) {
        prefs.edit { putString(KEY_LOCK, lock.name) }
        lockState.value = lock
    }

    fun setPayeeWarnings(on: Boolean) {
        prefs.edit { putBoolean(KEY_WARN, on) }
        warnState.value = on
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_HIDE = "hide_balances"
        const val KEY_LOCK = "auto_lock"
        const val KEY_WARN = "payee_warnings"

        inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
            name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: fallback
    }
}
