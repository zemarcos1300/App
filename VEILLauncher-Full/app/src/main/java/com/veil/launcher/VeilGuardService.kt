package com.veil.launcher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat

/**
 * Serviço em segundo plano. Usa apenas o evento "mudança de janela" para saber
 * qual app veio para a frente. Não lê o conteúdo da tela (canRetrieveWindowContent=false).
 */
class VeilGuardService : AccessibilityService() {
    private val store by lazy { SecureStore.get(applicationContext) }
    private var lastPkg: String? = null
    private var resumePkg: String? = null
    private var imePackages: Set<String> = emptySet()
    private var receiverRegistered = false

    private val ignored = setOf("com.android.systemui", "android")

    // Nunca bloqueados pela camada "ao desbloquear", para evitar ficar sem acesso
    // às configurações do Android (desativar o serviço) ou a chamadas de emergência.
    private val deviceLockExempt = setOf(
        "com.android.settings", "com.android.emergency", "com.android.dialer",
        "com.google.android.dialer", "com.samsung.android.dialer", "com.android.server.telecom"
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    resumePkg = lastPkg ?: resumePkg
                    lastPkg = null
                    LockSession.clearAll()
                }
                Intent.ACTION_USER_PRESENT -> onUserPresent()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        refreshIme()
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in ignored || pkg in imePackages) return
        if (!store.configured()) return

        val previous = lastPkg
        if (pkg != previous) {
            lastPkg = pkg
            if (previous != null) LockSession.markLeft(previous)
        }

        if (LockSession.deviceLocked) {
            if (pkg !in deviceLockExempt) launchLock(null)
            return
        }

        if (pkg in store.protectedApps() &&
            !LockSession.isUnlocked(pkg, store.graceSeconds() * 1000L)
        ) {
            launchLock(pkg)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (receiverRegistered) {
            try { unregisterReceiver(receiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private fun onUserPresent() {
        val pending = resumePkg
        resumePkg = null
        if (!store.configured()) return
        if (store.lockOnUnlock()) {
            LockSession.deviceLocked = true
            launchLock(null)
        } else if (pending != null && pending in store.protectedApps()) {
            launchLock(pending)
        }
    }

    private fun refreshIme() {
        imePackages = try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .enabledInputMethodList.map { it.packageName }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun launchLock(target: String?) {
        val now = SystemClock.elapsedRealtime()
        val same = LockSession.currentTarget == target
        if (same && (LockSession.active || now - LockSession.launchedAt < 1200L)) return
        LockSession.currentTarget = target
        LockSession.launchedAt = now
        val intent = Intent(this, LockActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
            putExtra(LockActivity.EXTRA_TARGET, target)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
        }
    }
}
