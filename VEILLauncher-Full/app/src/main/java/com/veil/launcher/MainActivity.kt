package com.veil.launcher

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import kotlin.math.abs

private val Night = Color(0xFF080B12)
private val Panel = Color(0xFF151C29)
private val Panel2 = Color(0xFF202B3D)
private val Silver = Color(0xFFB9C8E2)
private val White = Color(0xFFF3F6FC)
private val Muted = Color(0xFF8996AB)
private val Danger = Color(0xFFFF9292)

data class VeilAppInfo(val label: String, val packageName: String, val icon: Drawable)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(8, 11, 18)
        window.navigationBarColor = android.graphics.Color.rgb(8, 11, 18)
        setContent { VeilTheme { VeilRoot(this) } }
    }
}

@Composable
private fun VeilTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Silver, onPrimary = Night, background = Night,
            surface = Panel, onSurface = White, secondary = Muted,
            error = Danger
        ),
        content = content
    )
}

private class SecureStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("veil_vault", Context.MODE_PRIVATE)
    private val alias = "veil_aes_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun readData(): JSONObject {
        val encoded = prefs.getString("vault_blob", null) ?: return JSONObject()
        return try {
            val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
            val ivSize = bytes[0].toInt()
            val iv = bytes.copyOfRange(1, 1 + ivSize)
            val encrypted = bytes.copyOfRange(1 + ivSize, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun writeData(data: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val packed = byteArrayOf(iv.size.toByte()) + iv + encrypted
        prefs.edit().putString(
            "vault_blob",
            android.util.Base64.encodeToString(packed, android.util.Base64.NO_WRAP)
        ).apply()
    }

    @Synchronized fun configured(): Boolean = readData().has("hash")

    @Synchronized fun setCredentials(pin: String, gesture: String, symbols: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive("$pin|${gesture}|$symbols", salt)
        val data = readData()
        data.put("salt", android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP))
        data.put("hash", android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP))
        data.put("failures", 0)
        data.put("lock_until", 0L)
        writeData(data)
    }

    @Synchronized fun verify(pin: String, gesture: String, symbols: String): Boolean {
        val data = readData()
        val until = data.optLong("lock_until", 0L)
        if (System.currentTimeMillis() < until) return false
        val saltText = data.optString("salt", "")
        val hashText = data.optString("hash", "")
        if (saltText.isBlank() || hashText.isBlank()) return false
        val salt = android.util.Base64.decode(saltText, android.util.Base64.NO_WRAP)
        val expected = android.util.Base64.decode(hashText, android.util.Base64.NO_WRAP)
        val actual = derive("$pin|${gesture}|$symbols", salt)
        val valid = MessageDigest.isEqual(expected, actual)
        if (valid) {
            data.put("failures", 0)
            data.put("lock_until", 0L)
        } else {
            val failures = data.optInt("failures", 0) + 1
            data.put("failures", failures)
            if (failures >= 5) {
                data.put("lock_until", System.currentTimeMillis() + 30_000L)
                data.put("failures", 0)
            }
        }
        writeData(data)
        return valid
    }

    @Synchronized fun remainingLockMs(): Long =
        (readData().optLong("lock_until", 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    @Synchronized fun protectedApps(): Set<String> {
        val array = readData().optJSONArray("protected") ?: JSONArray()
        return (0 until array.length()).map { array.optString(it) }.toSet()
    }

    @Synchronized fun setProtected(packageName: String, enabled: Boolean) {
        val set = protectedApps().toMutableSet()
        if (enabled) set.add(packageName) else set.remove(packageName)
        val data = readData()
        data.put("protected", JSONArray(set.toList()))
        writeData(data)
    }

    @Synchronized fun clearCredentials() {
        val data = readData()
        data.remove("hash"); data.remove("salt")
        data.put("failures", 0); data.put("lock_until", 0L)
        writeData(data)
    }

    private fun derive(value: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(value.toCharArray(), salt, 210_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
    }
}

private enum class Page { HOME, SETTINGS, SETUP, AUTH, CHANGE_AUTH }
private enum class AuthPurpose { OPEN_APP, CHANGE_CREDENTIALS }

@Composable
private fun VeilRoot(context: Context) {
    val store = remember { SecureStore(context) }
    val apps = remember { loadLaunchableApps(context) }
    var page by remember { mutableStateOf(if (store.configured()) Page.HOME else Page.SETUP) }
    var protected by remember { mutableStateOf(store.protectedApps()) }
    var selected by remember { mutableStateOf<VeilAppInfo?>(null) }
    var purpose by remember { mutableStateOf(AuthPurpose.OPEN_APP) }
    var authError by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf("") }

    fun openApp(app: VeilAppInfo) {
        if (app.packageName in protected) {
            selected = app
            purpose = AuthPurpose.OPEN_APP
            authError = ""
            page = Page.AUTH
        } else {
            context.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                context.startActivity(it)
            }
        }
    }

    when (page) {
        Page.SETUP -> CredentialScreen(
            title = "Configure seu VEIL",
            subtitle = "Crie seu PIN, desenhe um gesto e escolha seus símbolos.",
            onCancel = null,
            onSave = { pin, gesture, symbols ->
                store.setCredentials(pin, gesture, symbols)
                page = Page.HOME
            }
        )
        Page.CHANGE_AUTH -> AuthScreen(
            title = "Confirme sua identidade",
            subtitle = "Digite suas credenciais atuais para alterar a combinação.",
            error = authError,
            onCancel = { page = Page.SETTINGS; authError = "" },
            onSubmit = { pin, gesture, symbols ->
                if (store.verify(pin, gesture, symbols)) {
                    page = Page.SETUP
                    authError = ""
                } else authError = "Combinação incorreta ou bloqueio temporário."
            }
        )
        Page.AUTH -> AuthScreen(
            title = "Acesso protegido",
            subtitle = selected?.label ?: "Aplicativo protegido",
            error = authError,
            onCancel = { page = Page.HOME; selected = null; authError = "" },
            onSubmit = { pin, gesture, symbols ->
                if (store.verify(pin, gesture, symbols)) {
                    val app = selected
                    page = Page.HOME
                    selected = null
                    authError = ""
                    app?.let { target ->
                        context.packageManager.getLaunchIntentForPackage(target.packageName)?.let { launch ->
                            context.startActivity(launch)
                        }
                    }
                } else {
                    val left = store.remainingLockMs()
                    authError = if (left > 0) "Muitas tentativas. Aguarde ${((left + 999) / 1000)} segundos." else "Combinação incorreta."
                }
            }
        )
        Page.SETTINGS -> SettingsPage(
            apps = apps,
            protected = protected,
            onToggle = { app, enabled ->
                store.setProtected(app.packageName, enabled)
                protected = store.protectedApps()
            },
            onChangeCredentials = { page = Page.CHANGE_AUTH },
            onBack = { page = Page.HOME },
            onReset = {
                store.clearCredentials()
                page = Page.SETUP
            }
        )
        Page.HOME -> HomePage(
            apps = apps,
            protected = protected,
            onSettings = { page = Page.SETTINGS },
            onOpen = ::openApp
        )
    }
}

@Suppress("DEPRECATION")
private fun loadLaunchableApps(context: Context): List<VeilAppInfo> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        .mapNotNull { resolve ->
            val info = resolve.activityInfo ?: return@mapNotNull null
            if (info.packageName == context.packageName) return@mapNotNull null
            VeilAppInfo(
                resolve.loadLabel(context.packageManager).toString(),
                info.packageName,
                resolve.loadIcon(context.packageManager)
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

@Composable
private fun VeilHeader(title: String, onBack: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Silver) }
        }
        Text(title, color = White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HomePage(
    apps: List<VeilAppInfo>,
    protected: Set<String>,
    onSettings: () -> Unit,
    onOpen: (VeilAppInfo) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, apps) { apps.filter { it.label.contains(query, true) } }
    val time = remember { java.time.format.DateTimeFormatter.ofPattern("HH:mm") }
    var now by remember { mutableStateOf(java.time.LocalTime.now().format(time)) }
    LaunchedEffect(Unit) {
        while (true) {
            now = java.time.LocalTime.now().format(time)
            kotlinx.coroutines.delay(15_000)
        }
    }
    Column(
        Modifier.fillMaxSize().background(Night).statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("V E I L", color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("SEU ESPAÇO, DO SEU JEITO", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp)
            }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Configurações", tint = Silver) }
        }
        Spacer(Modifier.height(28.dp))
        Text(now, color = White, fontSize = 58.sp, fontWeight = FontWeight.ExtraLight)
        Text(java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", java.util.Locale("pt", "BR"))).replaceFirstChar { it.uppercase() }, color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(30.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("APLICATIVOS", color = Silver, fontSize = 10.sp, letterSpacing = 3.sp, modifier = Modifier.weight(1f))
            Text("${filtered.size}", color = Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Buscar aplicativos", color = Muted) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, null, tint = Muted) } },
            singleLine = true, shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Silver, unfocusedBorderColor = Panel2,
                focusedTextColor = White, unfocusedTextColor = White,
                cursorColor = Silver
            )
        )
        Spacer(Modifier.height(12.dp))
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nenhum aplicativo encontrado.", color = Muted)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 18.dp)
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(Panel).clickable { onOpen(app) }
                            .padding(horizontal = 13.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val bitmap = remember(app.packageName) { app.icon.toBitmap(96, 96).asImageBitmap() }
                        androidx.compose.foundation.Image(bitmap, contentDescription = app.label, modifier = Modifier.size(38.dp))
                        Spacer(Modifier.width(13.dp))
                        Text(app.label, color = White, modifier = Modifier.weight(1f), fontSize = 14.sp)
                        if (app.packageName in protected) Icon(Icons.Default.Lock, "Protegido", tint = Silver, modifier = Modifier.size(17.dp))
                        else Icon(Icons.Default.ChevronRight, null, tint = Muted)
                    }
                }
            }
        }
        Text("VEIL LAUNCHER  •  TOQUE EM UM APP PARA ABRIR", color = Color(0xFF596579), fontSize = 8.sp, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp))
    }
}

@Composable
private fun SettingsPage(
    apps: List<VeilAppInfo>,
    protected: Set<String>,
    onToggle: (VeilAppInfo, Boolean) -> Unit,
    onChangeCredentials: () -> Unit,
    onBack: () -> Unit,
    onReset: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = apps.filter { it.label.contains(query, true) }
    Column(Modifier.fillMaxSize().background(Night).statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
        VeilHeader("Configurações", onBack)
        Spacer(Modifier.height(14.dp))
        Text("SEGURANÇA", color = Silver, fontSize = 10.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        SettingsAction("Alterar credenciais", "Atualize gesto, símbolos e PIN", Icons.Default.Key, onChangeCredentials)
        SettingsAction("Redefinir credenciais", "Apaga a combinação atual e solicita uma nova", Icons.Default.Refresh, onReset)
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = Panel2)
        Spacer(Modifier.height(16.dp))
        Text("APLICATIVOS PROTEGIDOS", color = Silver, fontSize = 10.sp, letterSpacing = 2.sp)
        Text("A proteção é solicitada ao abrir o app por dentro do VEIL.", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Filtrar aplicativos", color = Muted) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
            singleLine = true, shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Silver, unfocusedBorderColor = Panel2,
                focusedTextColor = White, unfocusedTextColor = White
            )
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            items(filtered, key = { it.packageName }) { app ->
                Row(
                    Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(13.dp)).padding(start = 10.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(app.label, color = White, modifier = Modifier.weight(1f), fontSize = 13.sp)
                    Switch(
                        checked = app.packageName in protected,
                        onCheckedChange = { onToggle(app, it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Night, checkedTrackColor = Silver)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsAction(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(14.dp))
            .background(Panel).clickable(onClick = onClick).padding(13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Silver)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = White, fontSize = 14.sp)
            Text(subtitle, color = Muted, fontSize = 10.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = Muted)
    }
}

@Composable
private fun CredentialScreen(
    title: String,
    subtitle: String,
    onCancel: (() -> Unit)?,
    onSave: (String, String, String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var gesture by remember { mutableStateOf("") }
    var symbols by remember { mutableStateOf(listOf<Int>()) }
    var error by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().background(Night).statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("V E I L", color = Silver, fontSize = 13.sp, letterSpacing = 5.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(15.dp))
        Text(title, color = White, fontSize = 27.sp, fontWeight = FontWeight.Light)
        Text(subtitle, color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))
        Text("1 · DESENHE SEU GESTO", color = Silver, fontSize = 10.sp, letterSpacing = 2.sp)
        GesturePad(gesture, onGesture = { gesture = it })
        Text("2 · ESCOLHA 3 A 6 SÍMBOLOS", color = Silver, fontSize = 10.sp, letterSpacing = 2.sp)
        SymbolGrid(symbols) { value ->
            symbols = if (value in symbols) symbols - value else if (symbols.size < 6) symbols + value else symbols
        }
        Spacer(Modifier.height(12.dp))
        Text("3 · CRIE UM PIN DE 6 A 12 DÍGITOS", color = Silver, fontSize = 10.sp, letterSpacing = 2.sp)
        PinField("PIN", pin) { pin = it }
        PinField("Confirmar PIN", confirm) { confirm = it }
        if (error.isNotBlank()) Text(error, color = Danger, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                error = when {
                    gesture.length < 2 -> "Desenhe um gesto com pelo menos 2 direções."
                    symbols.size < 3 -> "Escolha pelo menos 3 símbolos."
                    pin.length < 6 -> "O PIN precisa ter pelo menos 6 dígitos."
                    pin != confirm -> "Os PINs não coincidem."
                    else -> ""
                }
                if (error.isEmpty()) onSave(pin, gesture, symbols.joinToString(","))
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Silver, contentColor = Night)
        ) { Text("Salvar combinação", fontWeight = FontWeight.SemiBold) }
        if (onCancel != null) TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Cancelar", color = Muted)
        }
    }
}

@Composable
private fun AuthScreen(
    title: String,
    subtitle: String,
    error: String,
    onCancel: () -> Unit,
    onSubmit: (String, String, String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var gesture by remember { mutableStateOf("") }
    var symbols by remember { mutableStateOf(listOf<Int>()) }
    Column(
        Modifier.fillMaxSize().background(Night).statusBarsPadding().navigationBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Lock, null, tint = Silver, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(15.dp))
        Text(title, color = White, fontSize = 27.sp, fontWeight = FontWeight.Light)
        Text(subtitle, color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))
        Text("DESENHE O GESTO", color = Silver, fontSize = 10.sp, letterSpacing = 2.sp)
        GesturePad(gesture, onGesture = { gesture = it })
        Text("SELECIONE OS SÍMBOLOS NA ORDEM", color = Silver, fontSize = 10.sp, letterSpacing = 2.sp)
        SymbolGrid(symbols) { value ->
            symbols = if (value in symbols) symbols - value else if (symbols.size < 6) symbols + value else symbols
        }
        Spacer(Modifier.height(12.dp))
        PinField("PIN", pin) { pin = it }
        if (error.isNotBlank()) Text(error, color = Danger, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onSubmit(pin, gesture, symbols.joinToString(",")) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Silver, contentColor = Night)
        ) { Text("Desbloquear") }
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Cancelar", color = Muted)
        }
    }
}

@Composable
private fun GesturePad(value: String, onGesture: (String) -> Unit) {
    var points by remember { mutableStateOf(listOf<Offset>()) }
    var directions by remember { mutableStateOf("") }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(112.dp).padding(vertical = 7.dp)
                .clip(RoundedCornerShape(16.dp)).background(Panel)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { start ->
                            points = listOf(start)
                            directions = ""
                        },
                        onDrag = { change, _ ->
                            points = points + change.position
                            change.consume()
                        },
                        onDragEnd = {
                            val sampled = points
                            if (sampled.size > 1) {
                                val first = sampled.first()
                                val last = sampled.last()
                                val dx = last.x - first.x
                                val dy = last.y - first.y
                                val direction = if (abs(dx) > abs(dy)) {
                                    if (dx > 0) "R" else "L"
                                } else {
                                    if (dy > 0) "D" else "U"
                                }
                                directions += direction
                                if (directions.length > 8) directions = directions.takeLast(8)
                                onGesture(directions)
                            }
                            points = emptyList()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                if (points.size > 1) {
                    val path = Path().apply {
                        moveTo(points.first().x, points.first().y)
                        points.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(path, Silver, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round))
                }
                if (points.isEmpty()) {
                    drawCircle(Silver.copy(alpha = 0.22f), radius = 4.dp.toPx(), center = Offset(size.width / 2, size.height / 2))
                }
            }
            if (points.isEmpty()) Text("Deslize o dedo aqui", color = Muted, fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Gesto registrado: ", color = Muted, fontSize = 10.sp)
            Text(if (value.isEmpty()) "—" else value.chunked(1).joinToString(" · "), color = Silver, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { directions = ""; points = emptyList(); onGesture("") }) {
                Text("Limpar", color = Muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun SymbolGrid(selected: List<Int>, onSelect: (Int) -> Unit) {
    val symbols = listOf("○", "◇", "△", "□", "✦", "⬡", "⌁", "☆", "◈")
    Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
        for (row in 0..2) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                for (col in 0..2) {
                    val index = row * 3 + col + 1
                    val active = index in selected
                    Box(
                        Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (active) Panel2 else Panel)
                            .clickable { onSelect(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(symbols[index - 1], color = if (active) Silver else White, fontSize = 19.sp)
                    }
                }
            }
        }
    }
    if (selected.isNotEmpty()) {
        Text("Ordem: ${selected.joinToString(" → ")}", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun PinField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(it.filter(Char::isDigit).take(12)) },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        shape = RoundedCornerShape(13.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Silver, unfocusedBorderColor = Panel2,
            focusedTextColor = White, unfocusedTextColor = White,
            focusedLabelColor = Silver, unfocusedLabelColor = Muted,
            cursorColor = Silver
        )
    )
}
