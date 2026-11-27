package fr.soaresden.diallo

import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.telecom.DisconnectCause
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Ce qu'il faut pour que l'écran s'allume et montre l'appel quand le téléphone est verrouillé :
 * - Android 14+ : les notifications plein écran ;
 * - Android 15+ : « Allumer l'écran » (accès spécial des applis) ;
 * - HyperOS / MIUI : « Afficher sur l'écran de verrouillage » et « Fenêtres pop-up en arrière-plan »,
 *   refusées par défaut aux applis qui ne sont pas de Xiaomi.
 */
internal object CallScreenAccess {
    class Item(val label: String, val ok: Boolean, val open: (Context) -> Unit)

    val xiaomi: Boolean by lazy {
        Build.MANUFACTURER.equals("Xiaomi", true) || prop("ro.miui.ui.version.name").isNotEmpty() || prop("ro.mi.os.version.name").isNotEmpty()
    }

    fun items(ctx: Context): List<Item> {
        val out = ArrayList<Item>()
        if (Build.VERSION.SDK_INT >= 34) {
            val ok = try { ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent() } catch (_: Throwable) { true }
            out += Item(tr("Notifications plein écran"), ok) { c ->
                go(c, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg(c)))
            }
        }
        turnScreenOn(ctx)?.let { ok ->
            out += Item(tr("Allumer l'écran"), ok) { c -> go(c, Intent("android.settings.TURN_SCREEN_ON_SETTINGS", pkg(c))) }
        }
        if (xiaomi) {
            miuiOp(ctx, 10020)?.let { ok -> out += Item(tr("Afficher sur l'écran de verrouillage"), ok) { c -> openMiui(c) } }
            miuiOp(ctx, 10021)?.let { ok -> out += Item(tr("Fenêtres pop-up en arrière-plan"), ok) { c -> openMiui(c) } }
        }
        return out
    }

    fun missing(ctx: Context) = items(ctx).count { !it.ok }

    /** null : rien à vérifier sur ce téléphone. */
    private fun turnScreenOn(ctx: Context): Boolean? {
        if (Build.VERSION.SDK_INT < 35) return null
        return try {
            val ops = ctx.getSystemService(AppOpsManager::class.java)
            when (ops.unsafeCheckOpNoThrow("android:turn_screen_on", Process.myUid(), ctx.packageName)) {
                AppOpsManager.MODE_ALLOWED -> true
                AppOpsManager.MODE_DEFAULT -> ctx.checkSelfPermission("android.permission.TURN_SCREEN_ON") == PackageManager.PERMISSION_GRANTED
                else -> false
            }
        } catch (_: Throwable) { null }
    }

    /** Autorisations propres à MIUI / HyperOS (10020 : écran de verrouillage, 10021 : démarrage en arrière-plan). */
    private fun miuiOp(ctx: Context, op: Int): Boolean? = try {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val m = AppOpsManager::class.java.getMethod("checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
        (m.invoke(ops, op, Process.myUid(), ctx.packageName) as Int) == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) { null }

    private fun openMiui(c: Context) {
        val i = Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", c.packageName)
        val tries = listOf(
            Intent(i).setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"),
            Intent(i).setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity"),
            i,
        )
        for (t in tries) if (start(c, t)) return
        details(c)
    }

    private fun pkg(c: Context) = Uri.parse("package:" + c.packageName)
    private fun go(c: Context, i: Intent) { if (!start(c, i)) details(c) }
    private fun details(c: Context) { start(c, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg(c))) }
    private fun start(c: Context, i: Intent): Boolean =
        try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Throwable) { false }

    private fun prop(k: String): String = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, k) as? String ?: ""
    } catch (_: Throwable) { "" }
}

/** Liste des autorisations, chacune touchable ; revérifiée au retour des réglages. */
@Composable
internal fun CallScreenAccessList(onChange: (Int) -> Unit = {}) {
    val ctx = LocalContext.current
    var ver by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) ver++ }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val items = remember(ver) { CallScreenAccess.items(ctx) }
    LaunchedEffect(items) { onChange(items.count { !it.ok }) }
    items.forEach { it ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp))
                .clickable(enabled = !it.ok) { it.open(ctx) }.padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (it.ok) "✅" else "❌", fontSize = 15.sp); Spacer(Modifier.width(10.dp))
            Text(it.label, fontSize = 13.5.sp, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
            if (!it.ok) Text(tr("Autoriser"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
    if (CallScreenAccess.xiaomi && items.any { !it.ok }) Text(
        tr("Sur Xiaomi, ces réglages sont dans « Autres autorisations »."), fontSize = 12.sp, color = muted(.65f), modifier = Modifier.padding(top = 4.dp)
    )
}

/** Au lancement, si Di-Allô est le téléphone par défaut et qu'une autorisation manque : on le dit une fois par jour au plus. */
@Composable
internal fun CallScreenAccessDialog() {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("call_screen", Context.MODE_PRIVATE) }
    var show by rememberSaveable {
        mutableStateOf(
            !sp.getBoolean("never", false) &&
                System.currentTimeMillis() - sp.getLong("asked", 0) > 20 * 3600_000L &&
                (ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_DIALER) == true) &&
                CallScreenAccess.missing(ctx) > 0
        )
    }
    if (!show) return
    LaunchedEffect(Unit) { sp.edit().putLong("asked", System.currentTimeMillis()).apply() }
    AlertDialog(
        onDismissRequest = { show = false },
        title = { Text(tr("📱 Écran d'appel"), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(tr("Pour que l'écran s'allume et montre qui t'appelle, même verrouillé, Di-Allô a besoin de ces autorisations :"), fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                CallScreenAccessList { if (it == 0) show = false }
            }
        },
        confirmButton = { TextButton(onClick = { show = false }) { Text(tr("Plus tard")) } },
        dismissButton = { TextButton(onClick = { sp.edit().putBoolean("never", true).apply(); show = false }) { Text(tr("Ne plus demander")) } },
    )
}

/**
 * Tonalité de fin d'appel : un seul « tut » quand le correspondant raccroche ou que l'appel coupe.
 * Rien quand c'est toi qui raccroches. Joué sur le flux de l'appel, sans jamais toucher au mode audio :
 * un mode « communication » resté actif (appli mise en pause par HyperOS) empêche le téléphone de sonner.
 */
internal object EndTone {
    private val main = Handler(Looper.getMainLooper())

    fun onEnded(cause: DisconnectCause?) {
        val code = cause?.code ?: return
        if (code == DisconnectCause.LOCAL || code == DisconnectCause.REJECTED || code == DisconnectCause.MISSED ||
            code == DisconnectCause.CANCELED || code == DisconnectCause.ANSWERED_ELSEWHERE || code == DisconnectCause.CALL_PULLED) return
        try {
            val tg = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
            tg.startTone(ToneGenerator.TONE_SUP_BUSY, 400)   // 425 Hz, un seul bip
            main.postDelayed({ try { tg.release() } catch (_: Throwable) { } }, 600)
        } catch (_: Throwable) { }
    }
}
