package com.veil.launcher

import android.os.SystemClock

/** Estado em memória compartilhado entre o serviço e a tela de bloqueio. */
internal object LockSession {
    private val unlocked = HashSet<String>()
    private val leftAt = HashMap<String, Long>()

    @Volatile var active: Boolean = false
    @Volatile var currentTarget: String? = null
    @Volatile var launchedAt: Long = 0L
    /** Verdadeiro quando o aparelho acabou de ser desbloqueado e o VEIL ainda não foi confirmado. */
    @Volatile var deviceLocked: Boolean = false

    @Synchronized fun markUnlocked(pkg: String) {
        unlocked.add(pkg)
        leftAt.remove(pkg)
    }

    @Synchronized fun markLeft(pkg: String) {
        if (pkg in unlocked) leftAt[pkg] = SystemClock.elapsedRealtime()
    }

    @Synchronized fun isUnlocked(pkg: String, graceMs: Long): Boolean {
        if (pkg !in unlocked) return false
        val left = leftAt[pkg] ?: return true
        if (SystemClock.elapsedRealtime() - left <= graceMs) {
            leftAt.remove(pkg)
            return true
        }
        unlocked.remove(pkg)
        leftAt.remove(pkg)
        return false
    }

    @Synchronized fun clearAll() {
        unlocked.clear()
        leftAt.clear()
    }
}
