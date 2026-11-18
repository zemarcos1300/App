package com.veil.launcher

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Tela de bloqueio exibida sobre um app protegido ou logo após desbloquear o aparelho. */
class LockActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TARGET = "veil_target"
    }

    private val target = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = android.graphics.Color.rgb(8, 11, 18)
        window.navigationBarColor = android.graphics.Color.rgb(8, 11, 18)

        val store = SecureStore.get(this)
        if (!store.configured()) {
            finish()
            return
        }

        LockSession.active = true
        target.value = intent.getStringExtra(EXTRA_TARGET)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goHome()
            }
        })

        setContent {
            VeilTheme {
                val pkg = target.value
                var error by remember(pkg) { mutableStateOf("") }
                val label = remember(pkg) { labelFor(pkg) }
                AuthScreen(
                    title = if (pkg == null) "VEIL bloqueado" else "Acesso protegido",
                    subtitle = label,
                    error = error,
                    onCancel = { goHome() },
                    onSubmit = { pin, gesture, symbols ->
                        if (store.verify(pin, gesture, symbols)) {
                            unlockAndFinish(pkg)
                        } else {
                            val left = store.remainingLockMs()
                            error = if (left > 0) {
                                "Muitas tentativas. Aguarde ${(left + 999) / 1000} segundos."
                            } else {
                                "Combinação incorreta."
                            }
                        }
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        target.value = intent.getStringExtra(EXTRA_TARGET)
    }

    override fun onStop() {
        super.onStop()
        // Se o usuário sair da tela de bloqueio (Home, recentes), ela se encerra;
        // o serviço a exibirá de novo quando necessário.
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        LockSession.active = false
        super.onDestroy()
    }

    private fun unlockAndFinish(pkg: String?) {
        if (pkg == null) LockSession.deviceLocked = false else LockSession.markUnlocked(pkg)
        finish()
    }

    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(home)
        finish()
    }

    @Suppress("DEPRECATION")
    private fun labelFor(pkg: String?): String {
        if (pkg == null) return "Confirme sua identidade para continuar."
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
    }
}
