package fr.soaresden.diallo.rec

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.soaresden.diallo.Amber
import fr.soaresden.diallo.CallGreen
import fr.soaresden.diallo.SectionCard
import fr.soaresden.diallo.muted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** État de l'enregistreur, vérifié à l'ouverture de Di-Allô. */
object RecHealth {
    /** Texte du bandeau d'accueil quand quelque chose manque (null = tout va bien ou désactivé). */
    val problem = mutableStateOf<String?>(null)
    val wizard = mutableStateOf(false)

    fun devOptions(ctx: Context) = try { Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 } catch (_: Throwable) { false }
    fun wifi(ctx: Context) = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (_: Throwable) { false }

    /** Vérifie (et relance le service si besoin). Hors fil principal. */
    fun check(ctx: Context) {
        if (!CallRecorder.enabled(ctx)) { problem.value = null; return }
        if (!AdbLauncher.paired(ctx)) { problem.value = "🎙️ Enregistrement des appels : configuration à terminer"; return }
        if (CallRecorder.serverVersion(ctx) == RecServer.VERSION) { problem.value = null; return }
        problem.value = if (CallRecorder.ensureServer(ctx)) null
        else "🎙️ Enregistrement indisponible : " + (AdbLauncher.lastError ?: "service arrêté")
    }

    // ---------- Liens vers les bons écrans ----------
    fun open(ctx: Context, vararg actions: Pair<String, String?>) {
        for ((a, highlight) in actions) try {
            val i = Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (highlight != null) {   // fait clignoter la bonne ligne dans les réglages
                i.putExtra(":settings:fragment_args_key", highlight)
                i.putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", highlight) })
            }
            ctx.startActivity(i); return
        } catch (_: Throwable) { }
    }
    fun openAbout(ctx: Context) = open(ctx, Settings.ACTION_DEVICE_INFO_SETTINGS to null, Settings.ACTION_SETTINGS to null)
    fun openWifi(ctx: Context) = open(ctx, Settings.ACTION_WIFI_SETTINGS to null)
    fun openWirelessDebug(ctx: Context) = open(ctx, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS to "toggle_adb_wireless", Settings.ACTION_SETTINGS to null)
}

/** Réglages › Enregistrement : l'enregistreur intégré. */
@Composable
internal fun RecorderCard() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var enabled by remember { mutableStateOf(CallRecorder.enabled(ctx)) }
    var mode by remember { mutableStateOf(CallRecorder.mode(ctx)) }
    val problem by RecHealth.problem
    val wiz by RecHealth.wizard
    LaunchedEffect(enabled, wiz) { withContext(Dispatchers.IO) { RecHealth.check(ctx.applicationContext) } }

    SectionCard("🎙️ Enregistreur Di-Allô") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Enregistrer les appels", fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                Text("MP3 dans Rec/ · les deux voix · sans autre appli", fontSize = 12.sp, color = muted())
            }
            Switch(enabled, { enabled = it; CallRecorder.setEnabled(ctx, it); if (it && !AdbLauncher.paired(ctx)) RecHealth.wizard.value = true })
        }
        if (!enabled) return@SectionCard
        Spacer(Modifier.height(10.dp))
        Text("Quand ?", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
        listOf(
            CallRecorder.MANUAL to "Quand je veux : bouton 🔴 pendant l'appel",
            CallRecorder.CONTACTS to "Tout seul pour mes contacts (🔴 pour les autres)",
            CallRecorder.ALL to "Tout seul pour tous les appels"
        ).forEach { (id, label) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(mode == id, { mode = id; CallRecorder.setMode(ctx, id) })
                Text(label, fontSize = 13.sp, color = cs.onSurface)
            }
        }
        Text("Le bouton 🔴 reste toujours disponible pendant l'appel pour démarrer ou arrêter.", fontSize = 11.5.sp, color = muted(.55f))
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(problem?.removePrefix("🎙️ ") ?: "✅ Prêt à enregistrer", fontSize = 13.sp, color = if (problem == null) cs.primary else Amber, modifier = Modifier.weight(1f))
            TextButton(onClick = { RecHealth.wizard.value = true }) { Text(if (problem == null) "Vérifier" else "Configurer") }
        }
    }
}

/** Assistant d'association, étape par étape, avec les liens vers les bons écrans Android. */
@Composable
fun RecSetupWizard(onClose: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var dev by remember { mutableStateOf(false) }
    var wifi by remember { mutableStateOf(false) }
    var adbOn by remember { mutableStateOf(false) }
    var paired by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val pairing by AdbLauncher.pairing
    // l'écran se met à jour tout seul quand on revient des réglages
    LaunchedEffect(Unit) {
        while (true) {
            dev = RecHealth.devOptions(ctx); wifi = RecHealth.wifi(ctx); adbOn = AdbLauncher.wifiDebugOn(ctx); paired = AdbLauncher.paired(ctx)
            server = withContext(Dispatchers.IO) { CallRecorder.serverVersion(ctx) == RecServer.VERSION }
            delay(1200)
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(14.dp), shape = RoundedCornerShape(24.dp), color = cs.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
                Text("🎙️ Activer l'enregistrement", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(
                    "Android réserve le son des appels aux outils de développement. Di-Allô passe par le « Débogage sans fil » de ton téléphone : " +
                        "une association une seule fois, puis il l'allume et l'éteint tout seul quand il en a besoin.",
                    fontSize = 12.5.sp, color = muted(.75f), modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
                if (server && paired) {
                    Step(1, true, "Tout est prêt", "Le service d'enregistrement tourne. Bouton 🔴 dans l'écran d'appel.") { }
                } else {
                    Step(1, dev, "Options pour les développeurs", if (dev) "Activées" else "Réglages › À propos du téléphone › touche 7 fois « Version de l'OS ».") {
                        OutlinedButton(onClick = { RecHealth.openAbout(ctx) }) { Text("Ouvrir « À propos »") }
                    }
                    Step(2, wifi, "Wi-Fi connecté", if (wifi) "OK" else "Le débogage sans fil ne marche qu'en Wi-Fi.") {
                        OutlinedButton(onClick = { RecHealth.openWifi(ctx) }) { Text("Ouvrir le Wi-Fi") }
                    }
                    Step(3, paired || adbOn, "Débogage sans fil", if (paired || adbOn) "Activé" else "Options pour les développeurs › « Débogage sans fil » › Activer (coche « Toujours autoriser sur ce réseau »).") {
                        OutlinedButton(onClick = { RecHealth.openWirelessDebug(ctx) }, enabled = dev) { Text("Ouvrir le débogage sans fil") }
                    }
                    Step(4, paired, "Associer Di-Allô", if (paired) "Associé" else "Dans « Débogage sans fil », touche « Associer l'appareil avec un code ». Une notification Di-Allô apparaît : tapes-y le code à 6 chiffres sans fermer l'écran du code.") {
                        Button(onClick = { AdbLauncher.askCode(ctx); RecHealth.openWirelessDebug(ctx) }, enabled = dev && wifi) { Text("Associer") }
                        var manual by remember { mutableStateOf(false) }
                        TextButton(onClick = { manual = !manual }) { Text(if (manual) "Masquer" else "Écran partagé : saisir ici", fontSize = 12.sp) }
                        if (manual) {
                            var port by remember { mutableStateOf("") }; var code by remember { mutableStateOf("") }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), label = { Text("Port") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f), label = { Text("Code") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            }
                            Button(onClick = { scope.launch(Dispatchers.IO) { AdbLauncher.pair(ctx, code, port.toIntOrNull()) } }, enabled = code.length == 6, modifier = Modifier.padding(top = 6.dp)) { Text("Associer avec ce code") }
                        }
                    }
                    Step(5, server, "Service d'enregistrement", if (server) "En marche" else "Lancé automatiquement après l'association.") {
                        if (paired) Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = {
                                busy = true
                                scope.launch(Dispatchers.IO) {
                                    val ok = CallRecorder.ensureServer(ctx)
                                    AdbLauncher.pairing.value = if (ok) "✅ Service lancé" else "⚠️ ${AdbLauncher.lastError ?: "Lancement impossible"}"
                                    RecHealth.check(ctx); busy = false
                                }
                            }, enabled = !busy) { Text(if (busy) "Lancement…" else "Lancer maintenant") }
                            if (busy) { Spacer(Modifier.width(8.dp)); CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
                        }
                    }
                }
                pairing?.let { Text(it, fontSize = 12.5.sp, color = if (it.startsWith("⚠️")) Amber else cs.primary, modifier = Modifier.padding(top = 8.dp)) }
                if (paired) TextButton(onClick = { CallRecorder.prefs(ctx).edit().putBoolean("paired", false).apply(); AdbLauncher.pairing.value = null }) { Text("Refaire l'association", color = muted(.55f), fontSize = 12.sp) }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onClose) { Text(if (server && paired) "Terminé" else "Fermer") }
                }
            }
        }
    }
}

@Composable
private fun Step(n: Int, done: Boolean, title: String, text: String, actions: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(if (done) CallGreen else cs.surfaceVariant), contentAlignment = Alignment.Center) {
            Text(if (done) "✓" else "$n", color = if (done) Color.White else cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, color = cs.onSurface)
            Text(text, fontSize = 12.5.sp, color = muted(.7f))
            if (!done) Column(Modifier.padding(top = 4.dp)) { actions() }
        }
    }
}
